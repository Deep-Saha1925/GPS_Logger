package com.deep.gpslogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Runs GPS logging as a foreground service so it survives screen-off,
 * app-switching, and being swiped from Recents.
 *
 * Output format: a real .xlsx workbook (not CSV) matching the schema
 * expected by the downstream route-upload backend:
 *   stop_order (int), stop_name (text), latitude (number),
 *   longitude (number), distance_km (number), slack_min (number)
 *
 * Since xlsx is a zip container, it can't be appended to like a text file -
 * each new reading triggers a full regeneration of the workbook from the
 * accumulated in-memory row list. At realistic polling intervals (5s+) over
 * a multi-hour session this is a few thousand small rows at most, so
 * regenerating the whole (tiny) file each time is cheap.
 *
 * Reliability notes:
 *  - Holds a partial WakeLock for the entire logging session so the CPU
 *    doesn't sleep mid-session during Doze mode.
 *  - Every write is wrapped so a single unexpected exception can't silently
 *    kill logging forever - it's caught, logged, and the loop continues.
 *  - Row history is also mirrored to a small private-storage cache file, so
 *    if Android kills the whole process and restarts the service via
 *    START_STICKY, the accumulated rows can be reloaded and logging resumed
 *    into the same output file instead of starting over or doing nothing.
 */
class LocationLoggingService : Service() {

    companion object {
        const val CHANNEL_ID = "gps_logger_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "GPSLoggerService"
        private const val PREFS_NAME = "gps_logger_session"
        private const val CACHE_FILE_NAME = "gps_session_cache.tsv"

        val HEADERS = listOf("stop_order", "stop_name", "latitude", "longitude", "distance_km", "slack_min")

        const val ACTION_START = "com.deep.gpslogger.action.START"
        const val ACTION_STOP = "com.deep.gpslogger.action.STOP"

        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_CUSTOM_URI = "custom_uri" // nullable string form of a Uri

        var isRunning = false
            private set
        var rowCount = 0
            private set
    }

    data class RouteRow(
        val stopOrder: Int,
        val stopName: String,
        val latitude: Double,
        val longitude: Double,
        val distanceKm: Double,
        val slackMin: Double
    )

    private lateinit var locationManager: LocationManager
    private lateinit var geocoder: Geocoder
    private var outputUri: Uri? = null

    private val accumulatedRows = mutableListOf<RouteRow>()

    private var latestLocation: Location? = null
    private var loggingJob: Job? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in logging scope, attempting to recover", throwable)
        if (isRunning) {
            relaunchLoopAfterCrash()
        }
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)

    private var wakeLock: PowerManager.WakeLock? = null

    private var intervalSeconds = 10
    private var previousLocation: Location? = null
    private var cumulativeDistanceKm = 0.0
    private var lastRowTimeMillis = 0L
    private var stopOrderCounter = 0

    private val locationListener = LocationListener { location ->
        latestLocation = location
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        geocoder = Geocoder(this, Locale.getDefault())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.w(TAG, "Restarted with null intent - attempting recovery")
            attemptRecoveryAfterProcessDeath()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                stopLoggingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) {
                    Log.w(TAG, "Ignored duplicate ACTION_START while already running")
                    return START_STICKY
                }
                intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL_SECONDS, 10)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "gps_log_${System.currentTimeMillis()}.xlsx"
                val customUriStr = intent.getStringExtra(EXTRA_CUSTOM_URI)

                startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

                val uri = try {
                    if (customUriStr != null) {
                        Uri.parse(customUriStr)
                    } else {
                        createDefaultXlsxFile(fileName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create/resolve output file", e)
                    null
                }

                if (uri == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                clearCacheFile()
                persistSession(intervalSeconds, uri)
                beginLogging(uri, isResume = false)
            }
        }
        return START_STICKY
    }

    private fun attemptRecoveryAfterProcessDeath() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasActive = prefs.getBoolean("session_active", false)
        if (!wasActive) {
            stopSelf()
            return
        }

        val savedInterval = prefs.getInt("interval_seconds", 10)
        val savedUriStr = prefs.getString("output_uri", null)

        if (savedUriStr == null) {
            Log.w(TAG, "No persisted URI to recover session, giving up")
            clearPersistedSession()
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification("Resuming logging..."))

        intervalSeconds = savedInterval
        val uri = Uri.parse(savedUriStr)

        val restoredRows = loadRowsFromCache()
        accumulatedRows.clear()
        accumulatedRows.addAll(restoredRows)
        stopOrderCounter = restoredRows.size
        cumulativeDistanceKm = restoredRows.lastOrNull()?.distanceKm ?: 0.0
        rowCount = restoredRows.size

        Log.i(TAG, "Recovery restored ${restoredRows.size} prior rows, resuming logging")
        beginLogging(uri, isResume = true)
    }

    private fun beginLogging(uri: Uri, isResume: Boolean) {
        outputUri = uri

        if (!isResume) {
            accumulatedRows.clear()
            previousLocation = null
            cumulativeDistanceKm = 0.0
            stopOrderCounter = 0
            rowCount = 0
        }
        lastRowTimeMillis = System.currentTimeMillis()
        isRunning = true

        latestLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        acquireWakeLock()
        startLocationUpdates()
        updateNotification("Logging every ${intervalSeconds}s...")
        launchLoggingLoop()
    }

    private fun launchLoggingLoop() {
        loggingJob?.cancel()
        loggingJob = scope.launch {
            latestLocation?.let { loc -> safeWriteRow(loc) }
            while (isRunning) {
                delay(intervalSeconds * 1000L)
                if (isRunning) {
                    latestLocation?.let { loc -> safeWriteRow(loc) }
                }
            }
        }
    }

    private suspend fun safeWriteRow(loc: Location) {
        try {
            writeRow(loc)
        } catch (e: Exception) {
            Log.e(TAG, "Row write failed, continuing to next interval", e)
        }
    }

    private fun relaunchLoopAfterCrash() {
        Log.w(TAG, "Relaunching logging loop after unexpected crash")
        launchLoggingLoop()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GPSLogger:LoggingWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
        wakeLock = null
    }

    private fun startLocationUpdates() {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!locationManager.isProviderEnabled(provider)) continue
            try {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, locationListener)
            } catch (e: SecurityException) {
                Log.e(TAG, "No permission for $provider", e)
            }
        }
    }

    private suspend fun writeRow(loc: Location) {
        stopOrderCounter += 1

        val now = System.currentTimeMillis()
        val slackMin = (now - lastRowTimeMillis) / 60000.0
        lastRowTimeMillis = now

        previousLocation?.let { prev ->
            cumulativeDistanceKm += prev.distanceTo(loc) / 1000.0
        }
        previousLocation = loc

        val stopName = withTimeoutOrNull(2000L) {
            withContext(Dispatchers.IO) { reverseGeocodeAndroid(loc.latitude, loc.longitude) }
        } ?: withTimeoutOrNull(2000L) {
            withContext(Dispatchers.IO) { reverseGeocodeOWM(loc.latitude, loc.longitude) }
        } ?: "Stop $stopOrderCounter"

        val row = RouteRow(
            stopOrder = stopOrderCounter,
            stopName = stopName,
            latitude = loc.latitude,
            longitude = loc.longitude,
            distanceKm = Math.round(cumulativeDistanceKm * 100.0) / 100.0,
            slackMin = Math.round(slackMin * 100.0) / 100.0
        )
        accumulatedRows.add(row)
        appendRowToCache(row)

        try {
            regenerateXlsxFile()
            rowCount = accumulatedRows.size
            updateNotification("Logging every ${intervalSeconds}s... ($rowCount rows)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write xlsx file", e)
        }
    }

    private fun regenerateXlsxFile() {
        val uri = outputUri ?: return
        val rowsAsAny: List<List<Any?>> = accumulatedRows.map { r ->
            listOf(r.stopOrder, r.stopName, r.latitude, r.longitude, r.distanceKm, r.slackMin)
        }
        contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            XlsxWriter.write(stream, HEADERS, rowsAsAny)
        } ?: throw Exception("Could not open output stream for xlsx write")
    }

    // --- Private-storage row cache, used only to survive a process kill+restart ---

    private fun cacheFile(): File = File(filesDir, CACHE_FILE_NAME)

    private fun clearCacheFile() {
        try {
            cacheFile().delete()
        } catch (_: Exception) {
        }
    }

    private fun appendRowToCache(row: RouteRow) {
        try {
            cacheFile().appendText(
                "${row.stopOrder}\t${row.stopName.replace("\t", " ")}\t${row.latitude}\t${row.longitude}\t${row.distanceKm}\t${row.slackMin}\n"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write row cache", e)
        }
    }

    private fun loadRowsFromCache(): List<RouteRow> {
        val file = cacheFile()
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size != 6) return@mapNotNull null
                try {
                    RouteRow(
                        stopOrder = parts[0].toInt(),
                        stopName = parts[1],
                        latitude = parts[2].toDouble(),
                        longitude = parts[3].toDouble(),
                        distanceKm = parts[4].toDouble(),
                        slackMin = parts[5].toDouble()
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read row cache", e)
            emptyList()
        }
    }

    private val plusCodePattern = Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}(\\s|$)")

    private fun isUsableName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return !plusCodePattern.containsMatchIn(name)
    }

    private fun reverseGeocodeAndroid(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            val addr = addresses?.firstOrNull() ?: return null
            val candidates = listOf(
                addr.thoroughfare, addr.subLocality, addr.locality, addr.subAdminArea, addr.featureName
            )
            candidates.firstOrNull { isUsableName(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun reverseGeocodeOWM(lat: Double, lon: Double): String? {
        val apiKey = BuildConfig.OWM_API_KEY
        if (apiKey.isBlank()) return null
        var connection: HttpURLConnection? = null
        return try {
            val urlStr = "https://api.openweathermap.org/geo/1.0/reverse?lat=$lat&lon=$lon&limit=1&appid=$apiKey"
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONArray(body)
            if (results.length() == 0) return null
            val name = results.getJSONObject(0).optString("name", "")
            if (name.isBlank()) null else name
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun createDefaultXlsxFile(fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(
                MediaStore.Downloads.MIME_TYPE,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/gps_logs")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Could not create file in Downloads")

        // Write an empty (header-only) workbook immediately so the file is
        // valid the instant it's created, even before the first GPS row lands.
        contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            XlsxWriter.write(stream, HEADERS, emptyList())
        }
        return uri
    }

    private fun persistSession(interval: Int, uri: Uri) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("session_active", true)
            .putInt("interval_seconds", interval)
            .putString("output_uri", uri.toString())
            .apply()
    }

    private fun clearPersistedSession() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .clear()
            .apply()
    }

    private fun stopLoggingAndSelf() {
        loggingJob?.cancel()
        loggingJob = null
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        releaseWakeLock()
        clearPersistedSession()
        clearCacheFile()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Logger",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing GPS logging status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, LocationLoggingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Logger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) {
            loggingJob?.cancel()
            loggingJob = null
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: Exception) {
            }
            releaseWakeLock()
            isRunning = false
            // Deliberately keep persisted session + cache file so recovery
            // can resume if the system restarts this service.
        } else {
            releaseWakeLock()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
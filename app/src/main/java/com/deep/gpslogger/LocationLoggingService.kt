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
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Runs GPS logging as a foreground service so it survives screen-off,
 * app-switching, and being swiped from Recents.
 *
 * Reliability notes (why these exist - see fixes below):
 *  - Holds a partial WakeLock for the entire logging session. Without this,
 *    the CPU sleeps during Doze mode and the write loop silently stalls even
 *    though the foreground notification is still showing.
 *  - The write loop is wrapped so a single unexpected exception can never
 *    silently kill logging forever - it's caught, logged, and the loop
 *    continues on the next tick instead of dying quietly.
 *  - Session details are persisted to SharedPreferences so that if Android
 *    kills the whole process and restarts the service via START_STICKY
 *    (which arrives with a null intent - no extras), we can recover and
 *    resume logging into the same file instead of doing nothing.
 */
class LocationLoggingService : Service() {

    companion object {
        const val CHANNEL_ID = "gps_logger_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "GPSLoggerService"
        private const val PREFS_NAME = "gps_logger_session"

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

    private lateinit var locationManager: LocationManager
    private lateinit var geocoder: Geocoder
    private var outputStream: OutputStream? = null
    private var outputUri: Uri? = null

    private var latestLocation: Location? = null
    private var loggingJob: Job? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        // Last-resort net: if something still escapes the loop's own try/catch,
        // log it and attempt to relaunch the loop rather than dying silently.
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
    private var loggingStartTimeMillis = 0L
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
            // Service was restarted by the system (START_STICKY) after the
            // process was killed. All in-memory state is gone, but if a
            // session was active we can recover using persisted details.
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
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "gps_log_${System.currentTimeMillis()}.csv"
                val customUriStr = intent.getStringExtra(EXTRA_CUSTOM_URI)

                startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

                val (uri, stream) = try {
                    if (customUriStr != null) {
                        val parsed = Uri.parse(customUriStr)
                        parsed to contentResolver.openOutputStream(parsed, "wa")
                    } else {
                        createDefaultCsvFile(fileName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open output stream", e)
                    null to null
                }

                if (stream == null || uri == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                persistSession(intervalSeconds, uri)
                beginLogging(stream, isResume = false)
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

        // Must call startForeground promptly after any system-initiated restart.
        startForeground(NOTIFICATION_ID, buildNotification("Resuming logging..."))

        intervalSeconds = savedInterval
        val uri = Uri.parse(savedUriStr)
        val stream = try {
            contentResolver.openOutputStream(uri, "wa")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reopen file during recovery", e)
            null
        }

        if (stream == null) {
            Log.e(TAG, "Recovery failed - could not reopen output stream")
            clearPersistedSession()
            stopSelf()
            return
        }

        Log.i(TAG, "Recovery succeeded, resuming logging without rewriting header")
        beginLogging(stream, isResume = true, resumedUri = uri)
    }

    private fun beginLogging(stream: OutputStream, isResume: Boolean, resumedUri: Uri? = null) {
        outputStream = stream
        outputUri = resumedUri ?: outputUri

        if (!isResume) {
            previousLocation = null
            cumulativeDistanceKm = 0.0
            stopOrderCounter = 0
            loggingStartTimeMillis = System.currentTimeMillis()
            rowCount = 0
        }
        isRunning = true

        latestLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        if (!isResume) {
            try {
                outputStream?.write("stop_order,stop_name,latitude,longitude,distance_km,time_elapsed\n".toByteArray())
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write header", e)
            }
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

    /**
     * Every write is wrapped here so that any unexpected failure (a file
     * write error, a geocoding hiccup, anything) is caught, logged, and the
     * loop simply continues on the next interval - it never dies silently.
     */
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
                acquire() // held for the whole session; released in stopLoggingAndSelf/onDestroy
            }
            Log.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
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

        val elapsedMillis = System.currentTimeMillis() - loggingStartTimeMillis
        val elapsedFormatted = formatElapsed(elapsedMillis)

        previousLocation?.let { prev ->
            cumulativeDistanceKm += prev.distanceTo(loc) / 1000.0
        }
        previousLocation = loc

        val stopName = withTimeoutOrNull(2000L) {
            withContext(Dispatchers.IO) { reverseGeocodeAndroid(loc.latitude, loc.longitude) }
        } ?: withTimeoutOrNull(2000L) {
            withContext(Dispatchers.IO) { reverseGeocodeOWM(loc.latitude, loc.longitude) }
        } ?: "Stop $stopOrderCounter"

        val distanceStr = String.format(Locale.US, "%.2f", cumulativeDistanceKm)
        val safeStopName = "\"" + stopName.replace("\"", "\"\"") + "\""

        val line = "$stopOrderCounter,$safeStopName,${loc.latitude},${loc.longitude},$distanceStr,$elapsedFormatted\n"
        try {
            outputStream?.write(line.toByteArray())
            outputStream?.flush()
            rowCount += 1
            updateNotification("Logging every ${intervalSeconds}s... ($rowCount rows)")
        } catch (e: Exception) {
            Log.e(TAG, "Write error", e)
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

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun createDefaultCsvFile(fileName: String): Pair<Uri, OutputStream> {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/gps_logs")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Could not create file in Downloads")
        val stream = contentResolver.openOutputStream(uri, "wa")
            ?: throw Exception("Could not open output stream")
        return uri to stream
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
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (_: Exception) {
        }
        outputStream = null
        releaseWakeLock()
        clearPersistedSession()
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
            // onDestroy while still "running" means this wasn't an explicit
            // user Stop (that path already calls stopLoggingAndSelf, which
            // sets isRunning = false first). This is the system killing us -
            // clean up local resources but deliberately KEEP the persisted
            // session so attemptRecoveryAfterProcessDeath() can resume
            // logging into the same file if the system restarts us.
            loggingJob?.cancel()
            loggingJob = null
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: Exception) {
            }
            try {
                outputStream?.flush()
                outputStream?.close()
            } catch (_: Exception) {
            }
            outputStream = null
            releaseWakeLock()
            isRunning = false
        } else {
            releaseWakeLock()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
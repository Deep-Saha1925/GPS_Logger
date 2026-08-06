package com.deep.gpslogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class LocationLoggingService : Service() {

    companion object {
        const val CHANNEL_ID = "gps_logger_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.deep.gpslogger.action.START"
        const val ACTION_STOP = "com.deep.gpslogger.action.STOP"

        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_CUSTOM_URI = "custom_uri" // nullable string form of a Uri

        // Simple broadcast-free status channel other components can read
        var isRunning = false
            private set
        var rowCount = 0
            private set
    }

    private lateinit var locationManager: LocationManager
    private lateinit var geocoder: Geocoder
    private var outputStream: OutputStream? = null

    private var latestLocation: Location? = null
    private var loggingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())

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
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoggingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) {
                    // Already logging - ignore duplicate start requests instead
                    // of spawning a second write loop / output stream.
                    Log.w("GPSLoggerService", "Ignored duplicate ACTION_START while already running")
                    return START_STICKY
                }
                intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL_SECONDS, 10)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "gps_log_${System.currentTimeMillis()}.csv"
                val customUriStr = intent.getStringExtra(EXTRA_CUSTOM_URI)

                startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

                val stream = try {
                    if (customUriStr != null) {
                        contentResolver.openOutputStream(Uri.parse(customUriStr))
                    } else {
                        createDefaultCsvFile(fileName)
                    }
                } catch (e: Exception) {
                    Log.e("GPSLoggerService", "Failed to open output stream", e)
                    null
                }

                if (stream == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                beginLogging(stream)
            }
        }
        // START_STICKY: if Android kills the service due to memory pressure,
        // it will try to recreate it (without the original intent extras though,
        // which is a known platform limitation - see note below).
        return START_STICKY
    }

    private fun beginLogging(stream: OutputStream) {
        outputStream = stream
        previousLocation = null
        cumulativeDistanceKm = 0.0
        stopOrderCounter = 0
        loggingStartTimeMillis = System.currentTimeMillis()
        rowCount = 0
        isRunning = true

        latestLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        try {
            outputStream?.write("stop_order,stop_name,latitude,longitude,distance_km,time_elapsed\n".toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e("GPSLoggerService", "Failed to write header", e)
        }

        startLocationUpdates()
        updateNotification("Logging every ${intervalSeconds}s...")

        loggingJob = scope.launch {
            latestLocation?.let { loc -> writeRow(loc) }
            while (true) {
                delay(intervalSeconds * 1000L)
                latestLocation?.let { loc -> writeRow(loc) }
            }
        }
    }

    private fun startLocationUpdates() {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!locationManager.isProviderEnabled(provider)) continue
            try {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, locationListener)
            } catch (e: SecurityException) {
                Log.e("GPSLoggerService", "No permission for $provider", e)
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
            Log.e("GPSLoggerService", "Write error", e)
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

    private fun createDefaultCsvFile(fileName: String): OutputStream {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/gps_logs")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Could not create file in Downloads")
        return contentResolver.openOutputStream(uri) ?: throw Exception("Could not open output stream")
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
        stopLoggingAndSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
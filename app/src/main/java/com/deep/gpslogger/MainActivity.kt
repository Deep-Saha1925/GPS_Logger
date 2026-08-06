package com.deep.gpslogger

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.deep.gpslogger.ui.theme.GPSLoggerTheme
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var geocoder: Geocoder
    private var outputStream: OutputStream? = null

    private var latestLocation: Location? = null
    private var loggingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private var isLogging = mutableStateOf(false)
    private var isPaused = mutableStateOf(false)
    private var statusText = mutableStateOf("Not logging")
    private var selectedIntervalSeconds = mutableStateOf(10)
    private var rowCount = mutableStateOf(0)

    private var currentFileName = mutableStateOf("")
    private var currentLocationText = mutableStateOf("Location: —")
    private var userFileNameInput = mutableStateOf("")

    // New: lets the user pick exactly where the file is saved
    private var useCustomLocation = mutableStateOf(false)

    private val intervalOptions = listOf(5, 10, 30, 60)

    private var previousLocation: Location? = null
    private var cumulativeDistanceKm = 0.0
    private var loggingStartTimeMillis = 0L
    private var stopOrderCounter = 0

    private val locationListener = LocationListener { location ->
        latestLocation = location
        currentLocationText.value = String.format(
            Locale.US, "Location: %.5f, %.5f", location.latitude, location.longitude
        )
    }

    private fun isLocationEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fineGranted || coarseGranted) {
                acquireOutputStreamAndStart()
            } else {
                statusText.value = "Location permission denied."
            }
        }

    // New: system "Save As" style picker for a custom save location
    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                try {
                    val stream = contentResolver.openOutputStream(uri)
                    if (stream == null) {
                        statusText.value = "Could not open the selected file."
                        return@registerForActivityResult
                    }
                    currentFileName.value = queryDisplayName(uri) ?: "selected_file.csv"
                    beginActualLogging(stream)
                } catch (e: Exception) {
                    statusText.value = "Failed to open file: ${e.message}"
                }
            } else {
                statusText.value = "File selection cancelled."
            }
        }

    // Asks the system content resolver for the file's real display name,
    // since the URI itself (uri.lastPathSegment) is often an opaque
    // provider-specific ID and NOT the actual filename.
    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        geocoder = Geocoder(this, Locale.getDefault())

        setContent {
            GPSLoggerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        val logging by isLogging
                        val paused by isPaused
                        val status by statusText
                        val interval by selectedIntervalSeconds
                        val count by rowCount
                        val fileName by currentFileName
                        val locationDisplay by currentLocationText
                        val fileNameInput by userFileNameInput
                        val customLocation by useCustomLocation

                        var expanded by remember { mutableStateOf(false) }

                        Text(text = status)
                        Text(text = "Rows logged: $count", modifier = Modifier.padding(top = 8.dp))

                        if (!logging) {
                            OutlinedTextField(
                                value = fileNameInput,
                                onValueChange = { userFileNameInput.value = it },
                                label = { Text("File name (optional)") },
                                placeholder = { Text("e.g. Monday_Route") },
                                modifier = Modifier.padding(top = 8.dp)
                            )

                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                Checkbox(
                                    checked = customLocation,
                                    onCheckedChange = { useCustomLocation.value = it }
                                )
                                Text(
                                    text = "Choose save location manually",
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }

                        if (fileName.isNotBlank()) {
                            Text(text = "File: $fileName", modifier = Modifier.padding(top = 8.dp))
                        }

                        if (logging) {
                            Text(text = locationDisplay, modifier = Modifier.padding(top = 8.dp))
                        }

                        Text(text = "Polling interval: ${interval}s", modifier = Modifier.padding(top = 24.dp))

                        Button(onClick = { expanded = true }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Change Interval")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            intervalOptions.forEach { seconds ->
                                DropdownMenuItem(
                                    text = { Text("${seconds}s") },
                                    onClick = {
                                        selectedIntervalSeconds.value = seconds
                                        expanded = false
                                    }
                                )
                            }
                        }

                        if (!logging) {
                            Button(
                                onClick = { checkPermissionAndStart() },
                                modifier = Modifier.padding(top = 24.dp)
                            ) {
                                Text("Start Logging")
                            }
                        } else {
                            Row(modifier = Modifier.padding(top = 24.dp)) {
                                Button(onClick = { togglePause() }) {
                                    Text(if (paused) "Resume" else "Pause")
                                }
                                Button(
                                    onClick = { stopLogging() },
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Text("Stop Logging")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionAndStart() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            acquireOutputStreamAndStart()
        } else {
            requestPermissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun sanitizedFileName(): String {
        val rawName = userFileNameInput.value.trim()
        return if (rawName.isNotBlank()) {
            val sanitized = rawName.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            if (sanitized.endsWith(".csv", ignoreCase = true)) sanitized else "$sanitized.csv"
        } else {
            "gps_log_${System.currentTimeMillis()}.csv"
        }
    }

    // Decides where the file goes: either opens the system picker (custom location)
    // or auto-creates it in Downloads/gps_logs (default), then hands off to
    // beginActualLogging() once we have a writable stream.
    private fun acquireOutputStreamAndStart() {
        if (!isLocationEnabled()) {
            statusText.value = "Please turn ON Location in phone settings first."
            return
        }

        if (useCustomLocation.value) {
            createDocumentLauncher.launch(sanitizedFileName())
        } else {
            try {
                val stream = createCsvFile()
                beginActualLogging(stream)
            } catch (e: Exception) {
                statusText.value = "Failed to create file: ${e.message}"
            }
        }
    }

    // All the actual GPS/logging setup — unchanged from before, just now
    // takes the already-opened stream instead of creating it itself.
    private fun beginActualLogging(stream: OutputStream) {
        outputStream = stream

        previousLocation = null
        cumulativeDistanceKm = 0.0
        stopOrderCounter = 0
        loggingStartTimeMillis = System.currentTimeMillis()
        isPaused.value = false
        currentLocationText.value = "Location: —"

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
            statusText.value = "Failed to write header: ${e.message}"
            return
        }

        startLocationUpdates()

        isLogging.value = true
        rowCount.value = 0
        statusText.value = "Logging every ${selectedIntervalSeconds.value}s..."

        loggingJob = scope.launch {
            latestLocation?.let { loc -> writeRow(loc) }
            while (true) {
                delay(selectedIntervalSeconds.value * 1000L)
                if (!isPaused.value) {
                    latestLocation?.let { loc -> writeRow(loc) }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        var registeredAny = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!locationManager.isProviderEnabled(provider)) continue
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    1000L,
                    0f,
                    locationListener
                )
                registeredAny = true
                Log.d("GPSLogger", "Subscribed to $provider updates.")
            } catch (e: SecurityException) {
                Log.d("GPSLogger", "No permission for $provider: ${e.message}")
            }
        }
        if (!registeredAny) {
            statusText.value = "No location provider available/permitted."
        }
    }

    private fun togglePause() {
        if (isPaused.value) {
            isPaused.value = false
            startLocationUpdates()
            statusText.value = "Logging every ${selectedIntervalSeconds.value}s..."
        } else {
            isPaused.value = true
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: Exception) {
            }
            statusText.value = "Paused. ${rowCount.value} rows so far."
        }
    }

    private fun stopLogging() {
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

        isLogging.value = false
        isPaused.value = false
        currentLocationText.value = "Location: —"
        statusText.value = "Stopped. Saved ${rowCount.value} rows."
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
            rowCount.value += 1
        } catch (e: Exception) {
            statusText.value = "Write error: ${e.message}"
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
                addr.thoroughfare,
                addr.subLocality,
                addr.locality,
                addr.subAdminArea,
                addr.featureName
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
            val urlStr = "https://api.openweathermap.org/geo/1.0/reverse" +
                    "?lat=$lat&lon=$lon&limit=1&appid=$apiKey"
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

    // Default path: auto-saves into Downloads/gps_logs, now correctly
    // using the user's typed filename (this was the actual bug).
    private fun createCsvFile(): OutputStream {
        val fileName = sanitizedFileName()
        currentFileName.value = fileName

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/gps_logs")
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Could not create file in Downloads")

        return resolver.openOutputStream(uri) ?: throw Exception("Could not open output stream")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogging()
    }
}
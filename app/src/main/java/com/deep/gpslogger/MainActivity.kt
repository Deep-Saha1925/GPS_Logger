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

    // ---- New: on-screen display state (purely additive, doesn't affect logging logic) ----
    private var currentFileName = mutableStateOf("")
    private var currentLocationText = mutableStateOf("Location: —")
    private var userFileNameInput = mutableStateOf("")

    private val intervalOptions = listOf(5, 10, 30, 60)

    // ---- Trip-tracking state (reset each time a new session starts) ----
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
                startLogging()
            } else {
                statusText.value = "Location permission denied."
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
            startLogging()
        } else {
            requestPermissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startLogging() {

        if (!isLocationEnabled()) {
            statusText.value = "Please turn ON Location in phone settings first."
            return
        }

        // Reset trip state for a fresh session
        previousLocation = null
        cumulativeDistanceKm = 0.0
        stopOrderCounter = 0
        loggingStartTimeMillis = System.currentTimeMillis()
        isPaused.value = false
        currentLocationText.value = "Location: —"

        // Seed with last known location so we have something to log immediately
        latestLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        try {
            outputStream = createCsvFile()
            outputStream?.write("stop_order,stop_name,latitude,longitude,distance_km,time_elapsed\n".toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            statusText.value = "Failed to create file: ${e.message}"
            return
        }

        startLocationUpdates()

        isLogging.value = true
        rowCount.value = 0
        statusText.value = "Logging every ${selectedIntervalSeconds.value}s..."

        loggingJob = scope.launch {
            // Write an initial row right away if we have any fix at all
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
            // Resume
            isPaused.value = false
            startLocationUpdates()
            statusText.value = "Logging every ${selectedIntervalSeconds.value}s..."
        } else {
            // Pause
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

        // 1. Try Android's Geocoder first (best granularity: street/neighborhood).
        // 2. If it returns nothing usable, fall back to OpenWeatherMap (city-level).
        // 3. If both fail, use "Stop N". Each step is timeout-guarded so a
        //    hung network call can never stall the write loop.
        val stopName = withTimeoutOrNull(2000L) {
            withContext(Dispatchers.IO) { reverseGeocodeAndroid(loc.latitude, loc.longitude) }
        } ?: withTimeoutOrNull(2000L) {
            withContext(Dispatchers.IO) { reverseGeocodeOWM(loc.latitude, loc.longitude) }
        } ?: "Stop $stopOrderCounter"

        val distanceStr = String.format(Locale.US, "%.2f", cumulativeDistanceKm)

        // Quote stop_name for CSV safety (it may contain commas)
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

    /**
     * Reverse-geocodes a lat/lon into a short place name using Android's
     * built-in Geocoder (Google backend). Returns null - not a placeholder -
     * when nothing usable comes back, so the caller can fall through to
     * the OpenWeatherMap fallback.
     *
     * Note: Geocoder often returns a Plus Code (e.g. "FGJQ+Q96") in
     * featureName when there's no named street/POI nearby (common in
     * rural areas). We explicitly skip those and prefer real names.
     */
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

            // Prefer real, human-readable names over featureName,
            // since featureName is where Plus Codes usually show up.
            val candidates = listOf(
                addr.thoroughfare,      // street name
                addr.subLocality,       // neighborhood
                addr.locality,          // town/city
                addr.subAdminArea,      // district
                addr.featureName        // last resort - may be a Plus Code
            )

            candidates.firstOrNull { isUsableName(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fallback reverse geocoder using OpenWeatherMap's Geocoding API.
     * Coarser than Geocoder (city/district level only, no street names)
     * but tends to reliably return *something*, so it's used only when
     * the Android geocoder comes back empty. Returns null on any
     * failure (no key configured, no network, bad response, etc.)
     * so the caller can fall back to "Stop N".
     */
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

    private fun createCsvFile(): OutputStream {
        val fileName = "gps_log_${System.currentTimeMillis()}.csv"
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
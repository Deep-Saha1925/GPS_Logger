package com.deep.gpslogger

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var locationManager: LocationManager
    private var outputStream: OutputStream? = null

    private var latestLocation: Location? = null
    private var loggingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private var isLogging = mutableStateOf(false)
    private var statusText = mutableStateOf("Not logging")
    private var selectedIntervalSeconds = mutableStateOf(10)
    private var rowCount = mutableStateOf(0)

    private val intervalOptions = listOf(5, 10, 30, 60)

    private val locationListener = LocationListener { location ->
        latestLocation = location
    }

    private fun isLocationEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startLogging()
            } else {
                statusText.value = "Location permission denied."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        setContent {
            GPSLoggerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        val logging by isLogging
                        val status by statusText
                        val interval by selectedIntervalSeconds
                        val count by rowCount

                        var expanded by remember { mutableStateOf(false) }

                        Text(text = status)
                        Text(text = "Rows logged: $count", modifier = Modifier.padding(top = 8.dp))

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
                            Button(
                                onClick = { stopLogging() },
                                modifier = Modifier.padding(top = 24.dp)
                            ) {
                                Text("Stop Logging")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionAndStart() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startLogging()
        } else {
            requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startLogging() {

        if (!isLocationEnabled()) {
            statusText.value = "Please turn ON Location in phone settings first."
            return
        }

        // Seed with last known location so we have something to log
        // immediately, instead of waiting for the first fresh GPS fix.
        latestLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        try {
            outputStream = createCsvFile()
            outputStream?.write("timestamp,latitude,longitude\n".toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            statusText.value = "Failed to create file: ${e.message}"
            return
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener
            )
            Log.d("GPSLogger", "Location updates requested successfully.")
        } catch (e: SecurityException) {
            statusText.value = "Permission error starting GPS."
            return
        }

        isLogging.value = true
        rowCount.value = 0
        statusText.value = "Logging every ${selectedIntervalSeconds.value}s..."

        loggingJob = scope.launch {
            // Write an initial row right away if we have any fix at all
            // (seeded last-known or an early live update).
            latestLocation?.let { loc ->
                writeRow(loc.latitude, loc.longitude)
            }

            while (true) {
                delay(selectedIntervalSeconds.value * 1000L)
                latestLocation?.let { loc ->
                    writeRow(loc.latitude, loc.longitude)
                }
            }
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
        statusText.value = "Stopped. Saved ${rowCount.value} rows."
    }

    private fun writeRow(lat: Double, lon: Double) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val line = "$timestamp,$lat,$lon\n"
        try {
            outputStream?.write(line.toByteArray())
            outputStream?.flush()
            rowCount.value += 1
        } catch (e: Exception) {
            statusText.value = "Write error: ${e.message}"
        }
    }

    private fun createCsvFile(): OutputStream {
        val fileName = "gps_log_${System.currentTimeMillis()}.csv"

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
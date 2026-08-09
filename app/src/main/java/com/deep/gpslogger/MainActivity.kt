package com.deep.gpslogger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

class MainActivity : ComponentActivity() {

    private lateinit var locationManager: LocationManager

    private var isLogging = mutableStateOf(false)
    private var statusText = mutableStateOf("Not logging")
    private var selectedIntervalSeconds = mutableStateOf(10)
    private var userFileNameInput = mutableStateOf("")
    private var useCustomLocation = mutableStateOf(false)

    private val intervalOptions = listOf(5, 10, 30, 60)

    private fun isLocationEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fineGranted || coarseGranted) {
                proceedToStart()
            } else {
                statusText.value = "Location permission denied."
            }
        }

    private val createDocumentLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        ) { uri ->
            if (uri != null) {
                // Without this, the write grant on a user-picked file can be
                // lost once the picking Activity's transaction ends - which
                // would break reopening the file later (e.g. after a
                // process-death recovery in the service).
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Some providers don't support persistable grants; logging
                    // will still work for this session, just not survive a
                    // full process kill+restart for a custom save location.
                }
                startServiceWithUri(uri.toString())
            } else {
                statusText.value = "File selection cancelled."
            }
        }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Some OEM builds (and some Android versions) reject this intent -
            // fall back to the general battery settings screen instead.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun sanitizedFileName(): String {
        val rawName = userFileNameInput.value.trim()
        return if (rawName.isNotBlank()) {
            val sanitized = rawName.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            if (sanitized.endsWith(".xlsx", ignoreCase = true)) sanitized else "$sanitized.xlsx"
        } else {
            "gps_log_${System.currentTimeMillis()}.xlsx"
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
                        val fileNameInput by userFileNameInput
                        val customLocation by useCustomLocation

                        var expanded by remember { mutableStateOf(false) }

                        Text(text = status)
                        Text(
                            text = "Logging runs in the background via a notification.",
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        if (!logging && !isIgnoringBatteryOptimizations()) {
                            Text(
                                text = "For long sessions, disable battery optimization for this app - " +
                                        "otherwise the phone may pause logging after a while.",
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            Button(
                                onClick = { requestIgnoreBatteryOptimizations() },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("Disable Battery Optimization")
                            }
                        }

                        if (!logging) {
                            OutlinedTextField(
                                value = fileNameInput,
                                onValueChange = { userFileNameInput.value = it },
                                label = { Text("File name (optional)") },
                                placeholder = { Text("e.g. Monday_Route") },
                                modifier = Modifier.padding(top = 16.dp)
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
                                onClick = {
                                    isLogging.value = true // optimistic lock, prevents double-tap race
                                    checkPermissionAndStart()
                                },
                                modifier = Modifier.padding(top = 24.dp)
                            ) {
                                Text("Start Logging")
                            }
                        } else {
                            Button(
                                onClick = { stopService() },
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

    override fun onResume() {
        super.onResume()
        isLogging.value = LocationLoggingService.isRunning
        if (LocationLoggingService.isRunning) {
            statusText.value = "Logging every ${selectedIntervalSeconds.value}s... (${LocationLoggingService.rowCount} rows)"
        }
    }

    private fun checkPermissionAndStart() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val permissionsNeeded = mutableListOf<String>()
        if (!fineGranted && !coarseGranted) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!notificationsGranted) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsNeeded.isEmpty()) {
            proceedToStart()
        } else {
            requestPermissions.launch(permissionsNeeded.toTypedArray())
        }
    }

    private fun proceedToStart() {
        if (!isLocationEnabled()) {
            statusText.value = "Please turn ON Location in phone settings first."
            return
        }

        if (useCustomLocation.value) {
            createDocumentLauncher.launch(sanitizedFileName())
        } else {
            startServiceWithUri(null)
        }
    }

    private fun startServiceWithUri(uriString: String?) {
        val intent = Intent(this, LocationLoggingService::class.java).apply {
            action = LocationLoggingService.ACTION_START
            putExtra(LocationLoggingService.EXTRA_INTERVAL_SECONDS, selectedIntervalSeconds.value)
            putExtra(LocationLoggingService.EXTRA_FILE_NAME, sanitizedFileName())
            if (uriString != null) {
                putExtra(LocationLoggingService.EXTRA_CUSTOM_URI, uriString)
            }
        }
        ContextCompat.startForegroundService(this, intent)
        isLogging.value = true
        statusText.value = "Logging started in background."
    }

    private fun stopService() {
        val intent = Intent(this, LocationLoggingService::class.java).apply {
            action = LocationLoggingService.ACTION_STOP
        }
        startService(intent)
        isLogging.value = false
        statusText.value = "Stopped."
    }
}
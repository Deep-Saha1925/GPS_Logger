<div align="center">
  <img src="assets/logo.svg" alt="GPS Logger logo" width="140" />

  # GPS Logger

  A lightweight Android app that logs your device's GPS location to a CSV file at a configurable interval — even while running in the background.

  ![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
  ![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
  ![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
  ![License](https://img.shields.io/badge/license-MIT-lightgrey)

  ### [⬇ Download latest APK (v1.0)](https://github.com/Deep-Saha1925/GPS_Logger/releases/latest)
</div>

---

## Overview

GPS Logger is a small, focused Android app built for one job: **record where you are, on a schedule, and save it as a CSV you can actually use.**

It was built as a supporting utility for a larger project that needed a reliable location trail — so it's intentionally simple, transparent about what it logs, and easy to read the output of.

## Features

- **Configurable polling interval** — choose 5s, 10s, 30s, or 60s from a dropdown
- **Runs in the background** — implemented as a foreground service with a persistent notification, so logging continues even when the screen is off, you switch apps, or the app is swiped from Recents
- **CSV export** — every reading is appended as a row: `stop_order, stop_name, latitude, longitude, distance_km, time_elapsed`
- **Reverse geocoding** — each row includes a human-readable place name (street/neighborhood/city) resolved via Android's Geocoder, with an OpenWeatherMap fallback for areas Geocoder can't resolve
- **Distance & elapsed time tracking** — cumulative distance travelled (km) and elapsed session time are calculated automatically per row
- **Flexible save location** — save automatically to `Downloads/gps_logs/`, or pick any folder via the native Android file picker
- **Custom file naming** — name your log file before starting a session
- **Pause / Resume** — pause logging without ending the session or losing your file

## How it works

1. Tap **Start Logging** — grant location and notification permissions if prompted
2. Choose a polling interval and (optionally) a custom file name or save location
3. The app starts a foreground service — you'll see an ongoing notification confirming it's active
4. Every N seconds, the current GPS fix is written as a new row to the CSV
5. Tap **Stop Logging** (from the app or the notification) to end the session and close the file

## CSV output format

```csv
stop_order,stop_name,latitude,longitude,distance_km,time_elapsed
1,"Park Street",22.55412,88.35119,0.00,00:00:02
2,"Camac Street",22.55390,88.35267,0.18,00:00:12
3,"Stop 3",22.55401,88.35502,0.42,00:00:22
```

- `stop_order` — sequence number of the reading within the session
- `stop_name` — resolved place name, or `Stop N` if no name could be resolved
- `latitude` / `longitude` — GPS coordinates
- `distance_km` — cumulative distance travelled since the session started
- `time_elapsed` — time since the session started, in `HH:MM:SS`

## Permissions used

| Permission | Why it's needed |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | To read GPS coordinates |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | To keep logging while the app isn't in the foreground |
| `POST_NOTIFICATIONS` | To show the required ongoing "logging active" notification |

No data leaves the device except for optional, anonymous reverse-geocoding lookups (place name only, not stored or linked to you) when Android's built-in Geocoder can't resolve a location.

## Getting started (build from source)

**Requirements:** Android Studio (recent version), a device or emulator running Android 7.0 (API 24) or higher.

```bash
git clone https://github.com/Deep-Saha1925/GPS_Logger.git
```

1. Open the project in Android Studio
2. Let Gradle sync
3. Run on a physical device (recommended — background location behaves inconsistently on some emulators)

## Installing the pre-built APK

Grab the latest signed APK from the [**Releases page**](https://github.com/Deep-Saha1925/GPS_Logger/releases/latest) — no need to build from source.

1. Download the `.apk` file to your phone
2. Tap to open it — allow installation from that source when prompted
3. Open the app, grant location and notification permissions
4. Start logging

> Since this isn't distributed via the Play Store, Android will show an "unknown sources" warning the first time — this is expected for any app shared outside the Store.

## Tech stack

- **Kotlin**
- **Jetpack Compose** for UI
- **Android LocationManager** for GPS
- **Foreground Service** for background operation
- **MediaStore API** for saving files to shared storage without extra permissions
- **Android Geocoder** + OpenWeatherMap Geocoding API (fallback) for place names

## Known limitations

- Some manufacturers (e.g., Vivo, Xiaomi, Oppo) apply aggressive battery optimization that can still kill background services despite correct implementation. If logging stops unexpectedly, check your phone's battery optimization settings and allow the app to run unrestricted.
- Indoor or low-signal environments may produce less frequent or less accurate GPS fixes.

## License

MIT — free to use, modify, and share.

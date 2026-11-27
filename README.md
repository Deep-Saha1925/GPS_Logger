<div align="center">
  <img src="assets/logo.svg" alt="GPS Logger logo" width="140" />

# GPS Logger

A lightweight Android app that logs your device's GPS location to an Excel (.xlsx) file — automatically at a configurable interval, or manually at the exact spots you choose to name — even while running in the background.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

### [⬇ Download latest APK (v1.0)](https://github.com/Deep-Saha1925/GPS_Logger/releases/latest)
</div>

---

## Overview

GPS Logger is a small, focused Android app built for one job: **record where you are — on a schedule, or at points you mark yourself — and save it as an Excel file you can actually use.**

It was built as a supporting utility for a larger project that needed a reliable location trail — so it's intentionally simple, transparent about what it logs, and easy to read the output of.

## Features

- **Two logging modes** — **Auto** logs on a timer; **Manual** logs only when you tap **LOG CURRENT LOC** and name the spot (see [Manual logging](#manual-logging))
- **Configurable polling interval** (Auto mode) — choose 5s, 10s, 30s, or 60s from a dropdown
- **Runs in the background** — implemented as a foreground service with a persistent notification, so logging continues even when the screen is off, you switch apps, or the app is swiped from Recents
- **Excel (.xlsx) export** — every reading becomes a row: `stop_order, stop_name, latitude, longitude, distance_km, slack_min`. The workbook is rewritten after each new reading, so the file is always valid
- **Reverse geocoding** — each row includes a human-readable place name (street/neighborhood/city) resolved via Android's Geocoder, with an OpenWeatherMap fallback for areas Geocoder can't resolve
- **Distance & slack-time tracking** — cumulative distance travelled (km) and the minutes since the previous row are calculated automatically per row
- **Flexible save location** — save automatically to `Downloads/gps_logs/`, or pick any folder via the native Android file picker
- **Custom file naming** — name your log file before starting a session
- **Crash recovery** — if Android kills the app process, the session resumes into the same file with the rows logged so far

## How it works

### Auto logging

1. Choose **Auto** and pick a polling interval
2. Optionally set a custom file name or save location
3. Tap **Start Logging** — grant location and notification permissions if prompted
4. The app starts a foreground service — you'll see an ongoing notification confirming it's active
5. Every N seconds, the current GPS fix is written as a new row to the .xlsx file
6. Tap **Stop Logging** (from the app or the notification) to end the session

### Manual logging

Use this when you only care about specific points on a route — for example, travelling from A to F and marking B, C, D and E as you pass them.

1. Choose **Manual**, optionally set a file name or save location, and tap **Start Logging**
2. Travel as normal. When you reach a point you want to record (say, B), tap **LOG CURRENT LOC**
3. Type a name for the spot (e.g. `Stop B`) and tap **Save**
4. The current GPS position is written as a new row under that name, and a status line confirms it (e.g. `Logged #2: Stop B`)
5. Repeat at each point, then tap **Stop Logging** to end the session

Notes:

- Nothing is logged on a timer in Manual mode — only the points you tap. GPS stays active in the background so a fresh fix is ready when you tap
- If you leave the name blank, the place name is resolved automatically (same as Auto mode), falling back to `Stop N`
- If there's no GPS fix yet, the app tells you to wait a few seconds and try again; if the fix is more than 30 seconds old, the status line says so
- Manual rows are saved to the same file format and location as Auto rows, and survive screen-off and process restarts

## Output format

The log is a real Excel workbook (`.xlsx`) with one header row and one row per reading. Auto and Manual modes produce the same columns:

| stop_order | stop_name | latitude | longitude | distance_km | slack_min |
|---|---|---|---|---|---|
| 1 | Stop B | 22.55412 | 88.35119 | 0.00 | 4.20 |
| 2 | Stop C | 22.55390 | 88.35267 | 0.18 | 6.75 |
| 3 | Stop D | 22.55401 | 88.35502 | 0.42 | 5.10 |

- `stop_order` — sequence number of the reading within the session
- `stop_name` — the name you typed (Manual mode), otherwise the resolved place name, or `Stop N` if none could be resolved
- `latitude` / `longitude` — GPS coordinates
- `distance_km` — cumulative distance travelled between logged points since the session started
- `slack_min` — minutes since the previous row (for the first row, minutes since the session started)

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

**Optional:** to enable the OpenWeatherMap place-name fallback, add `OWM_API_KEY=your_key` to `local.properties`. Without it, only Android's built-in Geocoder is used.

## Installing the pre-built APK

Grab the latest signed APK from the [**Releases page**](https://github.com/Deep-Saha1925/GPS_Logger/releases/latest) — no need to build from source.

1. Download the `.apk` file to your phone
2. Tap to open it — allow installation from that source when prompted
3. Open the app, grant location and notification permissions
4. Choose Auto or Manual mode and start logging

> Since this isn't distributed via the Play Store, Android will show an "unknown sources" warning the first time — this is expected for any app shared outside the Store.

## Tech stack

- **Kotlin**
- **Jetpack Compose** for UI
- **Android LocationManager** for GPS
- **Foreground Service** for background operation
- **MediaStore API** for saving files to shared storage without extra permissions
- A small custom **XLSX writer** (no external library) for the Excel output
- **Android Geocoder** + OpenWeatherMap Geocoding API (fallback) for place names

## Known limitations

- Some manufacturers (e.g., Vivo, Xiaomi, Oppo) apply aggressive battery optimization that can still kill background services despite correct implementation. If logging stops unexpectedly, check your phone's battery optimization settings and allow the app to run unrestricted.
- Indoor or low-signal environments may produce less frequent or less accurate GPS fixes.

## License

MIT — free to use, modify, and share.
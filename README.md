# Tag Mobile Video

Android app that controls an **nRF54L15 Tag** over BLE and records **phone camera video** in parallel with sensor streaming.

- **Package:** `com.nordic.tagmobile`
- **Version:** `0.3.0` (see `android/app/build.gradle.kts`)
- **Stack:** Kotlin, ViewBinding, Camera2 + MediaRecorder, Nordic `ble-ktx` + BLE scanner
- **Paired firmware:** `C:\nordic\v\app_tag` (TAG_STREAM GATT + protocol v8)

This is the **video fork** of `app_tag_mobile`. Sensor CSV / History / logs are the same idea; Device screen adds live camera preview and MP4/WebM capture.

---

## What it does

1. Gate on dog/user **Profile** (first launch).
2. **Scan** nearby BLE devices; highlight likely Tags.
3. **Connect** only if GATT service `TAG_STREAM` is present.
4. On **Start**: sync time with the Tag, start sensor notify stream, start phone video.
5. On **Stop**: stop Tag stream + video, analyze packet gaps, auto-save CSV + session log (+ expect matching video) into **History**.

---

## Project layout

```
app_tag_mobile_video/
├── README.md                     ← this file
├── BUILD_APK_GITHUB.md           ← CI APK download steps
├── CUSTOM_DATA_ARCHIVE.md        ← how Custom Data UI was removed / restore notes
├── REQUIREMENTS_LOG_HISTORY.md   ← logging / history requirements
├── preview_video.html            ← UI mock for camera screen
├── .github/workflows/            ← assembleDebug / release APK
└── android/                      ← Android Studio project
    └── app/src/main/java/com/nordic/tagmobile/
        ├── TagApp.kt             ← Application; owns shared BLE manager/scanner
        ├── MainActivity.kt       ← Home
        ├── ScannerActivity.kt    ← BLE scan + connect
        ├── DeviceActivity.kt     ← Camera preview + Start/Stop record
        ├── CameraConfigActivity.kt
        ├── ProfileActivity.kt
        ├── HistoryActivity.kt
        ├── LogViewerActivity.kt / SettingsActivity.kt
        ├── TagSession.kt         ← process-global session state
        ├── ble/                  ← TagBleManager, TagBleScanner
        ├── protocol/             ← UUIDs, commands, v8 parser, CSV
        ├── storage/              ← RecordingStore (data/ logs/ videos/)
        ├── analysis/             ← SessionAnalyzer (gaps / feedback)
        ├── model/                ← CameraConfig, profiles, RecordingState
        └── log/                  ← TagLogger
```

---

## End-to-end flow

```
MainActivity
  └─ (profile incomplete?) → ProfileActivity
  └─ Scan FAB → permissions + BT on → ScannerActivity
        └─ connect Tag → assign profile → DeviceActivity
              ├─ Camera2 preview (+ flash / flip / settings)
              ├─ Start → BLE START(+time) + MediaRecorder
              ├─ Notify packets → SensorPacketParser v8 → rows
              └─ Stop → BLE STOP + stop video → CSV/log save → History
```

### BLE scan (`TagBleScanner`)

- Scans **all** nearby LE devices (no UUID filter at scan time).
- Uses legacy 1M-friendly settings (`setLegacy(true)`).
- Marks a device as a Tag hint if:
  - advertisement includes `TAG_STREAM` UUID, or
  - name is `Tag` / `Tag_*`.

### BLE connect (`TagBleManager`)

Required GATT (else “Not a Tag device”):

| Role | UUID |
|------|------|
| Service `TAG_STREAM` | `7f5e0a10-4c1d-4b9a-9c22-a1b2c3d4e5f6` |
| Notify `TAG_SENSOR_DATA` | `7f5e0a11-4c1d-4b9a-9c22-a1b2c3d4e5f6` |
| Write `TAG_COMMAND` | `7f5e0a12-4c1d-4b9a-9c22-a1b2c3d4e5f6` |

After connect: request **MTU 247**, enable notifications → `onReady`.

**Not** Nordic UART (NUS). Custom Tag stream only.

### Commands

Matches firmware (`app_tag` / `tag_control.h`) and sibling `app_tag_mobile`:

| Command | Opcode | Payload |
|---------|--------|---------|
| START | `0x01` | `0x01` + **int64 LE unix ms** (9 bytes) |
| STOP | `0x02` | 1 byte |

On Start, camera/MediaRecorder is prepared first; BLE START is sent only after video capture session is running.

### Sensor packets (protocol v8)

- Framing: `0xA1` … `0x5A`, version `8`
- Header 18 bytes + up to 10 samples × 21 bytes
- Samples: BMI270 accel/gyro, BME688 humidity/env temp, TMP117 body temp + status flags
- App maps Tag uptime → wall clock using sync base captured at Start

### Video

- Camera2 + `MediaRecorder`, **video only** (no audio)
- Config: resolution (720/1080/4K), orientation, MP4/WebM, H.264/H.265, 24/30/60 fps
- Files under `filesDir/videos/`
- Overlay wall-clock timestamp on preview

### Persistence

| Kind | Directory |
|------|-----------|
| CSV | `filesDir/data/` |
| Session log | `filesDir/logs/` |
| Video | `filesDir/videos/` |
| Meta | `data/<base>.meta.json` |

History pairs CSV ↔ video by **shared base name**.

---

## Architecture notes

- Activities + XML layouts (no Compose / ViewModel / DI).
- `TagApp` holds one `TagBleManager` and one `TagBleScanner`; activities swap listeners.
- `TagSession` is in-memory global state for the current connection / recording.
- Profiles, camera prefs, log prefs → SharedPreferences.

`RecordingState`: `IDLE | SYNCING | RECEIVING | RECEIVED | CONVERTING | SAVING` — UI mainly uses `RECEIVING` → `SAVING` → `RECEIVED`.

---

## Build

### Local

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Open `android/` in Android Studio (JDK 17, SDK 34).

## GitHub Actions — build & install APK

Repo: https://github.com/the-gokul/app_tag_mobile_video  
Workflow file: `.github/workflows/build-apk.yml` (`Build Tag APK`)

### Step-by-step (how it works)

1. **Trigger** — push to `main`/`master`, or manually: **Actions → Build Tag APK → Run workflow**.
2. **Checkout** — clones this repository on `ubuntu-latest`.
3. **JDK 17** — Temurin Java for Android Gradle Plugin.
4. **Android SDK** — installs `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`.
5. **Gradle 8.7** — uses committed wrapper (`android/gradlew`) when present.
6. **Build** — `cd android && ./gradlew assembleDebug`.
7. **On success**
   - Upload artifact **`tag-mobile-video-debug-apk`** (`app-debug.apk`)
   - Create a GitHub **Release** `v0.3.0-buildN` with the APK attached
8. **On failure** — upload artifact **`build-log`** (open it to see the Gradle error).

### How to download the APK

1. Open **Actions** → green run (or **Releases**).
2. **Artifacts** → download **tag-mobile-video-debug-apk** (zip expires after retention days).
3. Unzip → install `app-debug.apk` on the phone (allow unknown sources).

Commands equivalent to CI (local, if Android SDK is installed):

```bash
cd android
./gradlew assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Why workflow #9 failed (run after A–D push)

| | |
|--|--|
| **Failed step** | `Build Debug APK` (`./gradlew assembleDebug`) |
| **Not the cause** | Release step / permissions (those were skipped after build failed) |
| **Real error** | Android resource linking failed in `activity_device.xml` |
| **Detail** | Flash button used `@android:drawable/ic_menu_always_invoke_with_default_browser` — that drawable **does not exist** in the Android SDK |
| **Fix** | Replaced with app drawables `ic_flash` / `ic_switch_camera`; hardened CI SDK setup like `app_tag_mobile` |

Gradle excerpt from the failed `build-log` artifact:

```text
> Task :app:processDebugResources FAILED
Android resource linking failed
.../layout/activity_device.xml: error: resource
android:drawable/ic_menu_always_invoke_with_default_browser not found.
```

### Troubleshooting

| Symptom | What to do |
|---------|------------|
| Red X on Actions | Open the run → **build-log** artifact → search `FAILURE:` / `error:` |
| Artifact missing | Build failed before upload; fix compile/resource error first |
| Node 20 deprecation warnings | Warnings only; not the failure |
| Need a new build | Push to `main` or **Run workflow** |

---

## Firmware expectations (nRF54L15 / `app_tag`)

| Item | Expectation |
|------|-------------|
| Advertise | Name `Tag` / `Tag_*`; include `TAG_STREAM`; prefer legacy 1M |
| GATT | Service + sensor notify + command write |
| START | `0x01` + int64 LE unix ms → sync + `recording_active` |
| STOP | `0x02` |
| Notify | Protocol v8 packets only while CCC enabled **and** recording active |
| Sensors | BMI270 / BME688 / TMP117 with flag status |
| Device id | Serial → `Tag_%08x` |

Older tree `tag/` (notify-only, no COMMAND char) will **fail** connect with this app.

---

## Known issues / fixes

**Fixed in current tree (A–D):**

1. BLE Start matches firmware: `0x01` + int64 LE unix ms (no `0x03` TIME).
2. Video / CSV / log share one `TagSession.sessionBaseName` set at Start.
3. FileProvider exposes `data/`, `logs/`, `videos/`.
4. Camera/MediaRecorder is prepared before BLE Start; failures abort without leaving the Tag streaming.

**Still open (optional):**

- Cloud Sync in History is a stub toast; CustomData “Save to Tag” is not a real BLE write.
- WebM + H.264 combo may fail on some devices (prefer MP4 + H.264).

**CI (fixed):** invalid flash drawable broke `processDebugResources` on GitHub Actions — see **GitHub Actions** section above.

---

## Related repos / folders

| Path | Role |
|------|------|
| `app_tag` | nRF54L15 Tag firmware (control + stream) |
| `app_tag_mobile` | Mobile client without video (reference BLE protocol) |
| `app_tag_mobile_video` | This app — BLE + camera video |
| `tag` | Older firmware tree (may lack COMMAND char) |

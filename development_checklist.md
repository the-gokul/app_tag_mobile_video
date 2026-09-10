# VETiNSTANT Dog Collar Data Collection App — Development Checklist

This checklist tracks the current state of the `app_tag_mobile_video` codebase against the required features for the ML data collection pipeline.

**Last updated:** 2026-09-10 (login Name+Phone; firmware GATT; cloud P2 deferred)

---

## Priority table (what to do next)

Effort key: **Small** ≈ hours–1 day · **Medium** ≈ few days · **High** ≈ 1+ weeks / multi-system

| Priority | Area | Status in app today | Remaining work | Effort | Why it matters |
| ---: | --- | --- | --- | --- | --- |
| P0 | Video timestamp orientation | Partial | Fix/verify `LiveTimestampComposer` vs `orientationHint`; acceptance-test saved MP4 | Medium | Timestamp must be readable in final video |
| P0 | Timestamp sync accuracy (`offset_ms`) | Partial | Measure phone↔collar↔video offset; fill `offset_ms` in manifest | Medium | Align video events with sensor rows for ML |
| P0 | Session folder + `manifest.json` | **Done** | — | — | Cloud-ready session package |
| P1 | Phone storage check | **Done** (`StorageGate` ~200 MB) | Tune threshold if needed | — | Prevents failed recordings |
| P1 | Session quality GOOD/WARNING/FAILED | **Done** (`SessionAnalyzer`) | Optional: tune thresholds | — | Filter bad sessions |
| P1 | Pet management (local) | Add / edit / delete / assign **done** | — | — | Fix wrong pet details without re-add |
| P1 | Pet wearing-collar confirm | **Done** (1 pet skip; 2+ confirm) | — | — | Avoid wrong pet↔Tag pairing |
| P1 | Crash / phone-restart recovery | Partial | Phone kill mid-record still open; **BLE disconnect while recording → auto-save SESSION_LOSS** done | Medium | Protect mid-record failures |
| P1 | Phone battery readiness | Missing (optional) | `BatteryManager` gate before Start | Small | Avoid mid-record phone death |
| P1 | Collar battery readiness | **Blocked (no HW)** | Needs board sense path + firmware + app | High | Not deployable on current nRF54L tag |
| P2 | Login (Name + Phone, no password) | **Done** (local) | OTP / cloud sync later (P3) | — | Who collected the session |
| P2 | Firmware version over BLE | **Done** (app + `app_tag` GATT) | Flash updated collar FW; bump `TAG_FW_VERSION_STR` as needed | — | Know which FW produced data |
| P2 | **Cloud integration (later)** | Not started | Choose cloud, then items below | High | Move packages off-device |
| P2↳ | · Batch upload queue | Deferred | After cloud chosen | High | Upload session folders |
| P2↳ | · File integrity (size/checksum) | Deferred | After cloud chosen | Medium | Corrupt upload detection |
| P2↳ | · Device ownership (device↔user) | Deferred | After cloud chosen | High | Fleet management |
| P2↳ | · BLE auto-reconnect / background | Deferred | Later (policy already decided in §6) | Medium | Field robustness |
| P3 | Auth / OTP / cloud IDs | Missing | Cloud `user_id` / `pet_id` | High | Multi-device sync |
| P3 | Portal / annotation / datasets | Missing | DB, S3, API, portal | High | ML workflow |

**Already strong:** BLE scan/connect, camera record, live timestamp burn-in, session packages, gallery, pet add/edit/delete/assign + wear-confirm, **login Name+Phone**, firmware version read, storage gate, quality labels.

**Login (local):** First install → Name + Phone → Pets (no owner name on pet form) → Main. Pets screen has top-right circular user icon to edit name/phone.

**Cloud integration:** Upload queue, integrity, device ownership, and BLE reconnect are **deferred** until a cloud provider is chosen. Do not start those yet.

**Collar battery (blocked):** Current nRF54L15 tag has **no battery voltage / fuel-gauge HW**. Manifest `battery.*` stays `null`.

**Upload model:** No live streaming — **batch file transfer after recording completes** (when cloud lands).

---

## Session package layout (current)

```text
files/sessions/SESSION-yyyyMMdd-HHmmss-xxxx/
  ├── SESSION-yyyyMMdd-HHmmss-xxxx.xlsx
  ├── SESSION-yyyyMMdd-HHmmss-xxxx.mp4
  ├── SESSION-yyyyMMdd-HHmmss-xxxx.log
  └── manifest.json          ← schema_version 2 (user/pet/device/sync/quality/…)
```

Gallery copy: `Movies/Tag/SESSION-….mp4` (unchanged public gallery path).

---

## 1. Overall workflow — DECIDED ✅

- [x] Basic flow defined (User → Scan → Connect → Select Pet → Record → Save → Upload → Portal)
- [x] Local record path implemented through Save (Upload/Portal still future)

---

## 2. User Management / Login

**Current Status:** **Done locally** — `LoginActivity` + `AppUser` (Name + Phone, no password). Owner name removed from pet form.

### UX (DECIDED ✅)

```text
First install
  → 1) Login (Name + Phone)
  → 2) Pets (animal type, pet name, breed, age, weight, gender)  ← no "Your Name" here
  → Main / Scan …

Pets screen
  ┌─────────────────────────────────────┐
  │ ← Pets                    (● User)  │  ← top-right circular icon
  │  pet list (add / edit / delete)     │
  └─────────────────────────────────────┘
       tap User → edit Name + Phone only
```

- [x] Dedicated login / first-run screen (**Name + Phone**, no password)
- [x] No password
- [x] Separate **user** identity (`AppUser`) vs pets (`UserProfile`)
- [x] Phone number field + save
- [x] Top-right circular user icon on Pets screen → edit name/phone
- [x] Pet form does **not** collect owner name
- [ ] Unique `user_id` (Cloud synced) — later / cloud
- [ ] OTP / cloud auth — **P3**
- [ ] User logout/session handling — later

---

## 3. Pet Management

**Current Status:** Local add / **edit** / delete / list / assign done. Cloud pets table still missing.

| Field | Where |
| --- | --- |
| User name / phone | `AppUser` (login) — **not** on pet form |
| Animal type | `UserProfile.animalType` |
| Pet name | `UserProfile.dogName` |
| Breed / Age / Weight / Gender | `UserProfile.*` |
| Local pet id | `UserProfile.id` (UUID) |

Also written into XLSX Summary + `manifest.json` user/pet blocks.

- [x] Add pet
- [x] **Edit pet** (`AddProfileActivity` + `EXTRA_PROFILE_ID`)
- [x] Delete pet
- [x] Pet details (Name, Breed, Sex, Age, Weight) — **no owner name field**
- [x] Unique local `pet_id`
- [ ] Cloud `pets` table / sync
- [ ] Soft-delete / deactivate

---

## 4. BLE Scanning

**Current Status:** Implemented

- [x] Request Bluetooth permission
- [x] Check Bluetooth enabled
- [x] Start / stop scan, timeout, scan again
- [x] Filter Tag devices
- [x] Display name + RSSI
- [x] Select device

---

## 5. How to Identify a VETiNSTANT Collar

**Current Status:** TAG_STREAM + firmware version characteristic (flash updated `app_tag`)

- [x] TAG_STREAM service UUID filtering
- [x] Device name / id from advertising + sensor packets (partial)
- [x] Firmware version GATT characteristic `7f5e0a13-…` (`TAG_FW_VERSION_STR`, default `1.0.0`)
- [ ] Battery level — **blocked: no HW on current nRF54L tag**
- [ ] Rich device status characteristic

---

## 6. BLE Connection

**Current Status:** Implemented (connect path). Auto-reconnect / background **not** done.

### Reconnect policy (DECIDED when we implement)

- Drop mid-session → try **auto-reconnect to same Tag** — **do NOT** show assign/confirm again
- Keep the **already assigned pet** for that recording
- To use **another pet**: user must **Stop** (or cancel) → Disconnect → Scan/Connect again → assign flow (1 pet skip / 2+ confirm)
- Do **not** re-assign while a recording is in progress

**Tag FW (battery):** On BLE disconnect, collar **auto-STOPs** sensor sampling (`tag_control_stop_recording` in `disconnected`) — same effect as app STOP. Requires flashing updated `app_tag`.

- [x] Connect / discover / notify / START-STOP writes
- [x] UI connected states
- [x] Tag auto-STOP sensors on BLE disconnect (`app_tag`)
- [ ] Robust backgrounding / phone-call handling
- [ ] Auto-reconnection (same Tag + same pet; no re-assign)

---

## 7. Device Ownership

**Current Status:** Deferred — part of **cloud integration (later)**. Choose cloud first.

- [ ] Register device
- [ ] Associate device with user
- [ ] Ownership states
- [ ] Firmware/HW revision in DB

---

## 8. Pet Selection — IMPORTANT

**Current Status:** Assign + wear-confirm UX implemented. Block-Start for incomplete pet is **not needed**.

### Recommended UX (DECIDED ✅)

| Pets in list | Confirm dialog? |
| --- | --- |
| **Only 1 pet** | **Skip confirm** — Tag → that pet → Device / Start |
| **2 or more pets** | Assign picker → **confirm** — e.g. `Confirm: Bruno is wearing Tag_xxx?` |

- [x] Show local pets (`UserProfile.loadAll`)
- [x] Select / assign pet after connect (`ScannerActivity` assign dialog)
- [x] Explicit wearing-collar confirm — **only when 2+ pets** (1 pet: auto-assign / skip)
- [x] ~~Block Start if no complete pet~~ — **NOT NEEDED** (assign dialog already required before next screen; empty list disables Assign)
- [ ] Cloud “pets for current user” filter

---

## 9. Recording Readiness Check

**Current Status:** Partial

- [x] BLE connected & ready
- [x] Camera available
- [x] Required permissions
- [x] Phone storage sufficient (`StorageGate`, ~200 MB)
- [ ] Phone battery sufficient (optional)
- [ ] Collar battery — **N/A (no HW)**
- [x] Pet assigned / confirmed per §8 (1 pet skip; 2+ confirm)

---

## 10. Session Creation

**Current Status:** Implemented

- [x] Unique `SESSION-…` id at Start (`RecordingStore.makeSessionId`)
- [x] Start/end times in manifest
- [x] Local user/pet/device ids/names in manifest
- [ ] Strict cloud-synced `user_id` / `pet_id` / `device_id`

---

## 11. Video Recording

**Current Status:** Implemented

- [x] Start / stop camera video
- [x] Save `SESSION-….mp4` in session folder
- [x] Live timestamp burn-in (`LiveTimestampComposer`)
- [ ] Timestamp orientation fully verified on saved MP4 (P0 open)

---

## 12. Collar Sensor Data Recording

**Current Status:** Implemented (XLSX primary — no app-side CSV; ML may convert later)

- [x] Phone receives BLE SENSOR_DATA
- [x] Parse v8 packets → rows
- [x] Save `SESSION-….xlsx` (data + Summary with profile/sensor info)
- [x] `CsvExporter` helpers exist for row formatting inside XLSX

---

## 13. Timestamp Synchronization — P0 OPEN 🔴

**Current Status:** Partial

- [x] `syncBaseUnixMs` (phone) at Start
- [x] START command sends unix ms to tag
- [x] First-packet uptime latch → relative sample times
- [x] Sync fields written in `manifest.json` (`phone_start_timestamp_ms`, `collar_uptime_at_sync_ms`, `sync_method`)
- [ ] Measured `offset_ms` (still `null`)
- [ ] Documented video-event vs IMU-peak calibration (10–20 trials)
- [ ] Overlay time proven aligned to XLSX time

---

## 14. Local Storage

**Current Status:** Implemented (session packages)

- [x] Session folder under `files/sessions/SESSION-…/`
- [x] `SESSION-….xlsx` + `SESSION-….mp4` + `SESSION-….log` + `manifest.json`
- [x] Offline recording (no internet required)
- [x] Legacy flat `data/` / `videos/` / `logs/` still readable in History

---

## 15. Local Storage Failure

**Current Status:** Partial

- [x] Insufficient storage blocked before Start (`StorageGate`)
- [x] Stop / abort cleans up failed Start (partial session discard)
- [x] Stop safely on BLE START failure (after video rolled)
- [x] BLE disconnect while recording → auto Stop + save as **SESSION_LOSS**
- [ ] Crash / phone-restart recovery for in-progress recording (app killed)

---

## 16–17. Upload System & Queue

**Current Status:** Deferred — **cloud integration (later)**. Pick cloud/API first, then implement.

**Upload model:** No live streaming — **batch file transfer after recording completes**.

- [ ] Pick cloud / API
- [ ] Background upload of session folder
- [ ] Retry / resume
- [ ] Network detection
- [ ] Progress / failure UI
- [ ] Multi-session queue

---

## 18. File Integrity

**Current Status:** Deferred — **cloud integration (later)** (checksums can also be local before upload).

- [ ] File size verification
- [ ] Checksum / hash in manifest
- [ ] Cloud existence check before complete

---

## 19–25. Cloud Infrastructure

**Current Status:** Missing

- [ ] PostgreSQL
- [ ] Object storage (S3)
- [ ] Secure API
- [ ] Research portal
- [ ] ZIP download of `SESSION-…` packages

---

## 26. Raw Data Protection

**Current Status:** Implemented locally

- [x] New sessions use unique `SESSION-…` folders (no overwrite of prior takes)
- [x] History permanent delete is explicit

---

## 27–28. Annotation & ML Dataset Management

**Current Status:** Missing (portal)

- [ ] Annotation tool integration
- [ ] Dataset versioning

---

## 29–30. Firmware / App Version Tracking

**Current Status:** **Done** for app + collar GATT (requires flashing updated `app_tag`).

**Meaning:** After connect, phone reads TAG_STREAM characteristic `7f5e0a13-…` (UTF-8 string, default `1.0.0`) into `TagSession.firmwareVersion` → `manifest.json` `device.firmware_version`. Older collar FW without the char → stays `null` (connect still works).

- [x] App version in `manifest.json` (`app.version`, `version_code`)
- [x] Full structured `manifest.json` beside session files
- [x] Firmware version over BLE (`app_tag` + `TagBleManager` read)

---

## 31. Battery and Hardware Metadata

**Current Status:** Collar battery blocked by HW

| Source | Today | Notes |
| --- | --- | --- |
| Collar battery % | **No** | No HW sense; manifest `null` |
| Phone battery % | Not implemented | Optional readiness via `BatteryManager` |
| RSSI | Yes | Scanner UI |
| Firmware / HW revision BLE | Yes (FW string) | `device.firmware_version` after connect; null on old FW |

- [x] RSSI in UI
- [ ] Collar battery — **N/A until HW revision**
- [ ] Collar START/END battery in manifest — deferred
- [ ] Optional phone battery START/END + readiness

---

## 32–33. Data Quality / Session Quality

**Current Status:** Implemented

| Label | When |
| --- | --- |
| **FAILED** | No sensor data; missing/empty video; missing samples ≥ ~40%; under ~3s with almost no samples |
| **WARNING** | Packet/sample gaps; parse failures; missing ≥ ~5%; very short recording |
| **SESSION_LOSS** | Tag/BLE disconnected while recording — auto Stop + save (not WARNING/FAILED) |
| **GOOD** | Otherwise |

- [x] `SessionAnalyzer` runs on Stop
- [x] `manifest.json` → `quality.status` + `missing_sample_percent` + duration
- [x] History status + Stop toast show quality
- [x] BLE disconnect while recording → auto-save with `quality=SESSION_LOSS`, `termination=BLE_DISCONNECT`

---

# Summary: Phase 1 Readiness

**Done recently**
- Session folders + `SESSION-….xlsx/mp4/log` + full `manifest.json` (v2)
- Phone storage gate before Start
- GOOD / WARNING / FAILED quality labeling
- Pet **edit** + wear-confirm (1 pet skip / 2+ confirm)
- **Login** Name + Phone (then pets; no owner name on pet form; user icon on Pets screen)
- **Firmware version over BLE** (`7f5e0a13-…` → manifest) — flash updated `app_tag`
- BLE disconnect while recording → **auto-save `SESSION_LOSS`** (distinct from WARNING/FAILED)

**Still open for Phase 1**
1. **P0** — Timestamp orientation acceptance on saved MP4  
2. **P0** — Measured sync `offset_ms` + calibration notes  
3. **P1** — App kill / phone-restart recovery; optional phone battery gate  
4. **Collar battery** — deferred (no HW)  

**Cloud integration (later — do not start yet)**  
Batch upload queue · file integrity · device ownership · BLE auto-reconnect / background · portal  

**Decided not needed:** Block Start for incomplete pet — assign-before-Device already covers it.

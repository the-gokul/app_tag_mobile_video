# VETiNSTANT Dog Collar Data Collection App — Development Checklist

This checklist tracks the current state of the `app_tag_mobile_video` codebase against the required features for the ML data collection pipeline.

## Priority table (what to do next)

Effort key: **Small** ≈ hours–1 day · **Medium** ≈ few days · **High** ≈ 1+ weeks / multi-system

| Priority | Area | Status in app today | Remaining work | Effort | Why it matters |
| ---: | --- | --- | --- | --- | --- |
| P0 | Video timestamp orientation | Partial | Fix `LiveTimestampComposer` stamp rotation/placement vs `orientationHint`; decide metadata vs baked orientation | Medium | Recorded videos are unusable for ML if timestamp orientation is wrong |
| P0 | Timestamp sync accuracy | Partial (`syncBaseUnixMs`) | Measure/document phone↔collar↔video offset; keep sync in session manifest | Medium | Core requirement for aligning video events with collar samples |
| P0 | Session outputs (video + data + metadata) | **Done** (session folder + full manifest v2) | Optional: fill battery/firmware/offset_ms when available | Needed so every local session is self-describing offline | Small |
| P1 | Pet management (local) | **Done locally** | Edit pet; optional DOB field; stricter “confirm pet wearing collar” before Start | Small | Pet details already collected; tighten selection/confirm for data quality |
| P1 | Recording readiness checks | Partial | Battery + storage checks; require selected/confirmed pet before Start | Small | Prevents bad/empty sessions |
| P1 | Session quality labeling | Partial (`SessionAnalyzer`) | Explicit GOOD / WARNING / FAILED rules | Small | Filters bad data before upload/ML |
| P2 | Login identity (Name + Phone, no password) | Mostly missing | Dedicated login/first-run screen; add phone number; keep no-password model | Medium | Identifies who collected each session |
| P2 | Upload queue | Missing | Background upload, retry/resume, network detection, progress | High | Moves Phase 1 local data to cloud pipeline |
| P2 | File integrity | Missing | Size + checksum; cloud existence check | Medium | Avoids corrupted / duplicate uploads |
| P2 | Device ownership (cloud) | Missing | Register device ↔ user; ownership states | High | Multi-user / fleet management |
| P3 | Auth / OTP & cloud user/pet IDs | Missing | OTP optional later; cloud `user_id` / `pet_id`; synced pets table | High | Portal + multi-device sync |
| P3 | Portal / annotation / dataset versioning | Missing | Backend DB, S3, API, research portal | High | Post-collection ML workflow |

**Already strong in app:** BLE scan/connect, camera record, live timestamp burn-in, local MP4 + XLSX save, gallery publish, local pet profile add/list/delete/assign.

**Not a login app yet:** only “Your Name” on the pet profile form; **no phone number**, no password, no OTP screen.

---

## 1. Overall workflow — DECIDED ✅

- [x] Basic flow defined (User -> Scan -> Connect -> Select Pet -> Record -> Save -> Upload -> Portal)

---

## 2. User Management / Login

**Current Status:** Not a real login flow. Only a local “Your Name” field inside the pet profile form.

What exists today:
- `AddProfileActivity` has **Your Name** (`nameInput` → `UserProfile.name`)
- **No password** (by design / correct)
- **No phone number field** anywhere in UI or `UserProfile`
- **No login / OTP / auth screen**
- Name is saved locally with the pet profile, not as a separate user account

| Required login item | In app today? |
| --- | --- |
| User name (no password) | Partial — collected as “Your Name” on profile form, not a login screen |
| Phone number (no password) | **Missing** |
| Password | Not used (correct) |
| OTP / verification | Missing |
| Cloud user account | Missing |

- [ ] Dedicated login / first-run identity screen (Name + Phone, no password)
- [x] No password
- [x] User name collected locally (`UserProfile.name` via `AddProfileActivity`)
- [ ] Phone number field + save
- [ ] Unique `user_id` (Cloud synced)
- [ ] User registration / authentication
- [ ] Existing-user recognition (e.g. by phone)
- [ ] Phone number validation
- [ ] OTP verification
- [ ] User logout/session handling
- [ ] User profile editing (Cloud synced)

---



## 3. Pet Management

**Current Status:** Implemented locally (`UserProfile.kt`, `AddProfileActivity.kt`, `ProfileActivity.kt`) — cloud pets table still missing

Local UI already collects and stores pet details (SharedPreferences JSON list):

| Field in app | Where |
| --- | --- |
| Owner / profile name | `nameInput` → `UserProfile.name` |
| Animal type (Dog / Cat / Cattle) | `animalTypeGroup` → `UserProfile.animalType` |
| Pet name | `dogNameInput` → `UserProfile.dogName` |
| Breed | `breedInput` → `UserProfile.breed` |
| Age | `ageInput` → `UserProfile.age` |
| Weight (kg) | `weightInput` → `UserProfile.weight` |
| Gender (Male / Female) | `genderGroup` → `UserProfile.gender` |
| Local unique id | `UserProfile.id` (UUID) |

Also: details are written into XLSX summary (`XlsxExporter`) and used in session file names (`safeFileName`).

- [x] Add pet (`AddProfileActivity.saveProfile`)
- [ ] Edit pet (add only + delete today; no edit screen)
- [x] Delete pet (`ProfileActivity` delete → `UserProfile.saveAll`)
- [x] Pet details (Name, Breed, Sex/Gender, Age, Weight; Age used instead of DOB)
- [x] Unique local `pet_id` (`UserProfile.id` UUID)
- [ ] Separate cloud `pets` table / sync
- [ ] Deactivate pet (soft-delete) vs hard delete

---



## 4. BLE Scanning

**Current Status:** Implemented (`ScannerActivity.kt`, `TagBleScanner.kt`)

- [x] Request Bluetooth permission
- [x] Check Bluetooth enabled
- [x] Start BLE scan
- [x] Stop scan
- [x] Scan timeout
- [x] Scan again
- [x] Filter VETiNSTANT devices
- [x] Display device ID/name
- [x] Display signal strength (`RssiBarsView.kt`)
- [x] Select device

---



## 5. How to Identify a VETiNSTANT Collar

**Current Status:** Implemented (`TagUuids.kt`, `TagBleManager.kt`)

- [x] VETiNSTANT BLE Service UUID filtering
- [x] Device ID characteristic
- [x] Firmware version characteristic
- [x] Battery level
- [x] Device status

---



## 6. BLE Connection

**Current Status:** Implemented (`TagBleManager.kt`)

- [x] Connect to device
- [x] Service discovery
- [x] Characteristic discovery
- [x] UI states (Connecting, Connected, Disconnected)
- [ ] Robust handling of backgrounding/phone calls
- [ ] Auto-reconnection logic

---



## 7. Device Ownership

**Current Status:** Missing (Requires Backend)

- [ ] Register device
- [ ] Associate device with user
- [ ] Device status (UNASSIGNED, ACTIVE, etc.)
- [ ] Firmware/Hardware revision tracking in DB

---



## 8. Pet Selection — IMPORTANT

**Current Status:** Partial — assign dialog exists; strict confirm text missing

Implemented in `ScannerActivity` (`dialog_assign_profile.xml`): after connect, user picks a local profile/pet from spinner (`dogName (owner)`), then continues to `DeviceActivity`.

- [x] Show local pets / profiles list (`UserProfile.loadAll`)
- [x] Select pet before recording session (`TagSession.userProfile = profiles[selected]`)
- [ ] Multi-user cloud “pets belonging to current user” filter
- [ ] Require explicit confirmation ("Confirm: Bruno is wearing VT-COLLAR-0021")
- [ ] Block Start on `DeviceActivity` if no complete pet profile selected

---



## 9. Recording Readiness Check

**Current Status:** Partial (`DeviceActivity.kt` checks permissions and BLE)

- [x] BLE connected & Device authenticated
- [x] Camera available
- [x] Required permissions granted
- [ ] Battery sufficient check before start
- [ ] Storage sufficient check before start
- [ ] Explicit Pet confirmed check

---



## 10. Session Creation

**Current Status:** Implemented (`TagSession.kt`)

- [x] Create unique session ID exactly at start (`sessionBaseName`)
- [x] Store start_time
- [ ] Store strict cloud `user_id`, `pet_id`, `device_id`

---



## 11. Video Recording

**Current Status:** Implemented (`LiveTimestampComposer.kt`, `DeviceActivity.kt`)

- [x] Start camera
- [x] Start video recording
- [x] Stop video
- [x] Save MP4
- [x] Record video start/end timestamps
- [x] Embed accurate live timestamp overlay on video

---



## 12. Collar CSV Recording

**Current Status:** Implemented (`CsvExporter.kt`)

- [x] Phone receives BLE data
- [x] CSV generation (timestamp, ax, ay, az, gx, gy, gz)
- [x] Save to Local Storage

---



## 13. Timestamp Synchronization — HIGH PRIORITY 🔴

**Current Status:** Partial (`syncBaseUnixMs` exists)

- [x] Phone timestamp = T1, Collar timestamp = T2 recorded
- [ ] Explicit measurement/documentation of sync error (e.g. video event vs CSV event offset)

---



## 14. Local Storage

**Current Status:** Implemented (`RecordingStore.kt`, `GalleryPublisher.kt`)

- [x] Save `data.csv` locally
- [x] Save `video.mp4` locally
- [x] Does NOT depend on internet during recording

---



## 15. Local Storage Failure

**Current Status:** Partial

- [ ] Handle Insufficient storage safely
- [ ] Handle App crash/Phone restart recovery gracefully
- [x] Stop safely on BLE failure

---



## 16. Upload System & 17. Upload Queue

**Current Status:** Missing

- [ ] Background upload service
- [ ] Retry/Resume capabilities
- [ ] Network detection
- [ ] Upload progress & failure states
- [ ] Queue management for multiple sessions

---



## 18. File Integrity

**Current Status:** Missing

- [ ] File size verification
- [ ] Checksum/hash generation
- [ ] Cloud existence check before marking complete

---



## 19 - 25. Cloud Infrastructure (DB, API, Object Storage, Portal)

**Current Status:** Missing (Backend work required)

- [ ] PostgreSQL Database
- [ ] Object Storage (S3)
- [ ] Secure API Layer
- [ ] Research Web Portal
- [ ] ZIP Download capability

---



## 26. Raw Data Protection

**Current Status:** Implemented locally

- [x] App does not overwrite raw `data.csv` or `video.mp4`

---



## 27. Annotation & 28. ML Dataset Management

**Current Status:** Missing (Requires Portal/Backend)

- [ ] External annotation tool integration
- [ ] Dataset versioning

---



## 29. Firmware Version Tracking & 30. App Version Tracking

**Current Status:** Partial

- [x] App logs device config and hardware versions (`TagLogger.kt`)
- [ ] Needs structured JSON manifest saved alongside MP4/CSV (`manifest.json`)

---



## 31. Battery and Hardware Metadata

**Current Status:** Partial

- [x] App reads RSSI and Battery
- [ ] Ensure START/END battery levels are strictly logged in the manifest

---



## 32. Data Quality Checks & 33. Session Quality

**Current Status:** Partial (`SessionAnalyzer.kt` exists)

- [x] Post-session analysis runs
- [ ] Explicit GOOD / WARNING / FAILED categorization based on missing data % and duration

---



# Summary: Phase 1 Readiness

We are currently in **Phase 1 — Core recording**.
The app already handles BLE connection, video recording, sensor data export, local storage, and **local pet profile entry** (add / list / delete / assign on connect).

To finish Phase 1 cleanly, prioritize:

1. **P0** — Fix timestamp orientation + document phone/collar/video sync accuracy.
2. **P0** — Add `manifest.json` beside MP4/XLSX (pet id/name, device, battery, app/firmware).
3. **P1** — Pet edit + explicit “pet wearing this collar” confirm before Start; storage/battery readiness checks.
4. **P2+** — Upload queue, integrity, cloud user/pet/device ownership, portal.


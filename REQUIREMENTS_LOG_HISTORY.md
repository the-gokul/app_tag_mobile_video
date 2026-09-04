Tag Mobile App — Logging, History & Storage Requirements
=========================================================
Based on discussion (September 2026)
Project: C:\nordic\v\app_tag_mobile
Status: REQUIREMENTS / DESIGN ONLY (not implemented yet)


1. PURPOSE
----------
Add a normal mobile-app style menu, settings, logging, and file history so users can:
- See BLE and data-receive activity inside the app
- Auto-save CSV and log after each Start→Stop recording
- Browse history and share CSV and/or log files when they want
- Get feedback after Stop about packet counts and possible packet loss


2. MENU (TOP-RIGHT)
-------------------
Add a 3-horizontal-bar (hamburger / overflow) icon on the top-right of the Home
screen (and optionally Device screen).

Menu items:
- Settings
- Logs (log viewer / log management entry)
- History (list of past recordings)
- About (optional: app version)


3. SETTINGS (UNDER MENU)
------------------------
Useful options (v1):
- Logging On/Off
- Log level:
  - Errors only
  - BLE + control (recommended default)
  - Verbose (every packet — large files; optional)
- Optional later: default CSV SI units, scan filter preferences

No cloud/server settings required for v1.


4. LOGGING — WHAT TO RECORD
---------------------------
Categories:
- App: start, permission grant/deny
- BLE: scan start/stop, connect attempt, GATT OK/fail, disconnect, MTU
- Control: START (+ sync time), STOP, notify enable
- Data: packets received (summary or per-packet if verbose), parse OK/fail
- Gaps / loss: jumps in packet_id or sample_number → "possible packet gap"
- File: auto-save of CSV and log (names, sizes, success/fail)
- Errors: same text shown in Toasts, also written to log

Example line format:
  2026-09-03 10:54:12.345 | BLE | CONNECT_OK | Tag | AA:BB:CC:DD:EE:FF | MTU=247

In-app log viewer:
- Scrollable list of recent events (live buffer)
- Filters nice-to-have: All / BLE / Data / Errors

Note on packet loss wording:
- App can detect SEQUENCE GAPS from packet_id / sample_number
- Do NOT claim absolute "confirmed radio loss" unless firmware adds ACK later
- UI text: "No packet loss detected" OR "Possible packet loss" / "Possible packet gap"


5. AUTO-SAVE AFTER START → STOP (IMPORTANT)
-------------------------------------------
CSV and log are AUTO-SAVED when the user completes a recording:

  Start  → begin session (clear buffers, log START + time sync, collect data)
  Stop   → stop receive, verify counts/gaps, show feedback on screen,
           then AUTO-WRITE both files (no mandatory "Save" name step for storage)

File naming (auto-generated), same BASE name for both files, for example:
  Tag_2026-09-03_10-54-12

Then write:
  csv/Tag_2026-09-03_10-54-12.csv
  logs/Tag_2026-09-03_10-54-12.log

Same base name, DIFFERENT folders (see section 6).

What about the existing Save button?
- After Stop, files are already stored in History
- Save button can become optional Export (copy to Downloads) OR Share shortcut
- Primary storage path is auto-save on Stop, not manual Save-for-storage


6. FOLDER LAYOUT (APP LOCAL "BACKEND" — PHONE ONLY)
--------------------------------------------------
No cloud/server backend in v1.
"Backend" means the app's own storage on the phone.

Separate folders (NOT the same folder for CSV and log):

  Tag app files/
  ├── csv/
  │   └── Tag_2026-09-03_10-54-12.csv
  └── logs/
      └── Tag_2026-09-03_10-54-12.log

| Type         | Folder | Extension |
|--------------|--------|-----------|
| Sensor data  | csv/   | .csv      |
| Session log  | logs/  | .log      |


7. AFTER STOP — VERIFICATION & FEEDBACK (REQUIRED)
--------------------------------------------------
Immediately after Stop, analyze the session and show feedback on the Device screen.

Checks:
- Packets received (notify frame count)
- Samples / CSV rows count
- Sequence gaps (packet_id / sample_number jumps)
- Parse failures
- Empty session (0 packets)

Feedback examples:

  OK:
    Recording complete
    Packets: 24 · Samples: 120
    Status: No packet loss detected
    Saved to History (CSV + log)

  Gap:
    Recording complete
    Packets: 22 · Samples: 110
    Status: Possible packet loss
    Missing ~2 packets (e.g. expected id 10→11, got 10→13)
    Saved to History (CSV + log)

  No data:
    Recording complete
    Packets: 0
    Status: No data received — check connection / Start


8. HISTORY (FRONT-END LIST)
---------------------------
Under menu → History:

- List all auto-saved takes, newest first
- Each row shows: name/date, packet/sample counts, loss status summary
- Tap row → detail:
  - Share CSV
  - Share log
  - Share both (optional)
  - Export / Download copy to Files/Downloads (optional)
  - NO DELETE option (see section 9)

History treats csv/Name.csv and logs/Name.log as one recording (paired by base name).


9. NO DELETE
------------
- No Delete button in History
- No in-app delete of CSV or log files
- Files remain until OS uninstall or user removes them outside the app
- Retention/auto-clean limits are OUT OF SCOPE for v1 (can add later if storage grows)


10. SHARE
---------
User can share when they want (not automatic upload):
- Share CSV only
- Share log only
- Share both (nice-to-have)

Uses Android system share sheet (Drive, WhatsApp, email, etc.).
No forced cloud backend.


11. IN-APP LOG VIEWER vs HISTORY FILES
--------------------------------------
| Feature        | Role                                      |
|----------------|-------------------------------------------|
| Log viewer     | Live/recent events while using the app    |
| logs/ folder   | Permanent session log file per recording  |
| csv/ folder    | Permanent sensor CSV per recording        |
| History screen | Browse past takes, share CSV/log          |


12. OUT OF SCOPE (v1)
---------------------
- Cloud / server upload of CSV or logs
- Delete management in app
- Firmware TAG_CONFIG ("Save to Tag" over BLE still app-local)
- Firmware dual 1M + Coded advertising (separate if Tag not visible on some phones)
- Absolute packet-loss proof without firmware ACK protocol


13. EXISTING APP CONTEXT (ALREADY BUILT)
---------------------------------------
- Kotlin Android app, XML + ViewBinding
- Nordic BLE libraries (ble-ktx + scanner)
- Scan shows ALL nearby BLE devices; connect only if TAG_STREAM GATT exists
- Start sends mobile unix time; Stop ends receive; protocol packet v8
- Tag firmware device name: "Tag"
- GitHub Actions builds APK (no local Android SDK required on dev PC)
- preview_home.html remains UI design reference


14. SUGGESTED IMPLEMENTATION ORDER (WHEN APPROVED)
--------------------------------------------------
1. Top-right menu on Home
2. Settings (log on/off + level)
3. In-app log viewer + memory buffer
4. After Stop: verification feedback UI
5. Auto-save to csv/ and logs/ with same base name
6. History list + share CSV/log (no delete)
7. Optional: Export copy to Downloads; adjust old Save button role
8. Rebuild APK via GitHub Actions


15. ACCEPTANCE CHECKLIST
------------------------
[ ] Menu icon top-right opens Settings / Logs / History
[ ] Start→Stop auto-creates Name.csv under csv/ and Name.log under logs/
[ ] Base file names match; folders differ
[ ] After Stop, on-screen feedback shows counts + loss/gap status
[ ] History lists past takes without a Delete action
[ ] User can share CSV and/or log from History
[ ] Log viewer shows events inside the app
[ ] No cloud backend required for v1


END OF REQUIREMENTS
-------------------
Document created from chat discussion. Implement only when explicitly requested.

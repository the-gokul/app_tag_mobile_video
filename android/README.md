# Tag Mobile — Android

UI from `preview_home.html`. BLE via Nordic Maven libraries:

- `no.nordicsemi.android:ble-ktx`
- `no.nordicsemi.android.support.v18:scanner`

## Behavior

1. **Scan** — lists **all** nearby BLE devices (no name filter).
2. **Connect** — succeeds only if TAG_STREAM GATT is present.
3. **Start / Stop / Save** — START+time sync, STOP, CSV with `timestamp_ms` + `date_time`.

Build APK with GitHub Actions (see `BUILD_APK_GITHUB.md`).

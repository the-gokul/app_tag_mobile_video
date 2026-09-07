# Tag Mobile — Android

UI from `preview_home.html`. BLE via Nordic Maven libraries:

- `no.nordicsemi.android:ble-ktx`
- `no.nordicsemi.android.support.v18:scanner`

## Behavior

1. **Scan** — lists **Tag** devices only (`Tag` / `Tag_*` or `TAG_STREAM`).
2. **Connect** — succeeds only if TAG_STREAM GATT is present.
3. **Start / Stop / Save** — START+time sync, STOP, Excel/CSV with timestamps.

Build APK with GitHub Actions (see `BUILD_APK_GITHUB.md`).

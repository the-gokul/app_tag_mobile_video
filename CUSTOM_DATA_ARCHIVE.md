# Custom data section (archived)

The Device screen **Custom data** card was removed from the live UI (Sept 2026).
CSV always exports raw + SI columns; tag rates stay compile-time until TAG_CONFIG exists.

Keep these files in the project for a future restore:
- `android/app/src/main/java/com/nordic/tagmobile/CustomDataActivity.kt`
- `android/app/src/main/res/layout/activity_custom_data.xml`
- related strings / `TagSession.customDataEnabled` / `DeviceConfig`

## Former Device screen card (XML)

```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="16dp"
    app:strokeColor="#D1D5DB"
    app:strokeWidth="1dp">

    <LinearLayout
        android:id="@+id/customDataRow"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="16dp">

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Custom data"
                android:textStyle="bold"
                android:textColor="#111827" />

            <ImageButton
                android:id="@+id/infoBtn"
                android:layout_width="36dp"
                android:layout_height="36dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_info"
                android:contentDescription="Info" />
        </LinearLayout>

        <TextView
            android:id="@+id/customDataMode"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Default"
            android:textColor="#6B7280"
            android:layout_marginEnd="8dp" />

        <com.google.android.material.switchmaterial.SwitchMaterial
            android:id="@+id/customDataToggle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content" />
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

## Former DeviceActivity wiring (Kotlin)

```kotlin
binding.customDataRow.setOnClickListener {
    startActivity(Intent(this, CustomDataActivity::class.java))
}
binding.infoBtn.setOnClickListener { showDeviceInfo() }
binding.customDataToggle.setOnCheckedChangeListener { _, checked ->
    if (checked) {
        binding.customDataToggle.isChecked = false
        startActivity(Intent(this, CustomDataActivity::class.java))
    } else {
        TagSession.customDataEnabled = false
        TagSession.deviceConfig = DeviceConfig.default()
        updateCustomDataLabel()
    }
}

private fun updateCustomDataLabel() {
    binding.customDataMode.text =
        if (TagSession.customDataEnabled) "Custom" else "Default"
    binding.customDataToggle.isChecked = TagSession.customDataEnabled
}
```

To restore: paste the card above `DATA CAPTURE` in `activity_device.xml`, re-add the listeners, and open `CustomDataActivity` from the row.

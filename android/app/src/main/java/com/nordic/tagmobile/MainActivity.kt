package com.nordic.tagmobile

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nordic.tagmobile.databinding.ActivityMainBinding
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingOpenScanner = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val bleOk = AppPermissions.ble().all {
            grants[it] != false &&
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (bleOk) {
            TagLogger.log(LogCategory.APP, "PERMISSIONS_GRANTED", "BLE permissions OK")
            if (pendingOpenScanner) ensureReadyAndScan()
        } else {
            pendingOpenScanner = false
            TagLogger.log(LogCategory.ERRORS, "PERMISSIONS_DENIED", "BLE permissions denied")
            Toast.makeText(this, R.string.ble_permission_rationale, Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (pendingOpenScanner) ensureReadyAndScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Load persisted profile + camera config into session
        val profiles = com.nordic.tagmobile.model.UserProfile.loadAll(this)
        TagSession.userProfile = profiles.firstOrNull() ?: com.nordic.tagmobile.model.UserProfile()
        TagSession.cameraConfig = com.nordic.tagmobile.model.CameraConfig.load(this)

        // First-run: force profile setup
        if (!TagSession.userProfile.isComplete) {
            startActivity(
                android.content.Intent(this, ProfileActivity::class.java).apply {
                    putExtra(ProfileActivity.EXTRA_FIRST_RUN, true)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            return
        }

        binding.scanFab.setOnClickListener { beginScanFlow() }
        binding.connectedCard.setOnClickListener {
            if (TagSession.isConnected()) {
                startActivity(Intent(this, DeviceActivity::class.java))
            }
        }
        requestAppPermissionsOnLaunch()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_profile -> {
                startActivity(Intent(this, ProfileActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_logs -> {
                startActivity(Intent(this, LogViewerActivity::class.java))
                true
            }
            R.id.action_history -> {
                startActivity(Intent(this, HistoryActivity::class.java))
                true
            }
            R.id.action_about -> {
                val version = try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (_: Exception) {
                    "?"
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.about)
                    .setMessage(getString(R.string.about_message, version))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        renderHome()
    }

    private fun requestAppPermissionsOnLaunch() {
        val missing = AppPermissions.all().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.permissions_title)
            .setMessage(R.string.permissions_message)
            .setPositiveButton(R.string.grant_permissions) { _, _ ->
                permissionLauncher.launch(missing.toTypedArray())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginScanFlow() {
        pendingOpenScanner = true
        val missing = AppPermissions.ble().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        ensureReadyAndScan()
    }

    private fun ensureReadyAndScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            Toast.makeText(this, R.string.location_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        pendingOpenScanner = false
        TagLogger.log(LogCategory.BLE, "SCAN_OPEN", "Opening scanner")
        startActivity(Intent(this, ScannerActivity::class.java))
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            true
        }
    }

    private fun renderHome() {
        val device = TagSession.connectedDevice
        if (device == null) {
            binding.emptyState.visibility = View.VISIBLE
            binding.connectedCard.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.connectedCard.visibility = View.VISIBLE
            binding.deviceName.text = device.name
            binding.deviceMac.text = device.address
            binding.deviceRssi.text = "${device.rssi} dBm"
        }
    }
}

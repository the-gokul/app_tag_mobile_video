package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.analysis.SessionAnalyzer
import com.nordic.tagmobile.ble.TagBleManager
import com.nordic.tagmobile.databinding.ActivityDeviceBinding
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.protocol.CsvExporter
import com.nordic.tagmobile.protocol.SensorPacketParser
import com.nordic.tagmobile.protocol.SensorPacketParser.HEADER_SIZE
import com.nordic.tagmobile.storage.RecordingStore

class DeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceBinding
    private val bleManager get() = TagApp.instance.bleManager
    private var pendingExportCsv: String? = null
    private var pendingExportName: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val csv = pendingExportCsv
        val name = pendingExportName
        if (uri != null && csv != null) {
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(csv.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Exported ${name ?: "file.csv"}", Toast.LENGTH_LONG).show()
                TagLogger.log(LogCategory.FILE, "EXPORT_OK", name ?: "")
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                TagLogger.log(LogCategory.ERRORS, "EXPORT_FAIL", e.message ?: "")
            }
        }
        pendingExportCsv = null
        pendingExportName = null
        updateRecordingUi()
    }

    private val bleListener = object : TagBleManager.Listener {
        override fun onReady(device: android.bluetooth.BluetoothDevice) = Unit

        override fun onDisconnected() {
            runOnUiThread {
                TagLogger.log(LogCategory.BLE, "DISCONNECTED", deviceLabel())
                TagSession.clearConnection()
                Toast.makeText(this@DeviceActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        override fun onPacket(data: ByteArray) {
            if (TagSession.recordingState != RecordingState.RECEIVING) return
            if (TagSession.tagUptimeAtSync == null && data.size >= HEADER_SIZE) {
                val buf = java.nio.ByteBuffer.wrap(data)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.position(14)
                TagSession.tagUptimeAtSync = buf.int.toLong() and 0xFFFFFFFFL
            }
            val parsed = SensorPacketParser.parsePacket(
                data,
                TagSession.syncBaseUnixMs,
                TagSession.tagUptimeAtSync,
            )
            if (parsed == null) {
                TagSession.parseFailures++
                TagLogger.log(LogCategory.ERRORS, "PARSE_FAIL", "bytes=${data.size}")
                return
            }
            runOnUiThread {
                maybeUpdateDeviceId(parsed.deviceId)
                TagSession.receivedRows.addAll(parsed.rows)
                TagSession.packetIds.add(parsed.packetId)
                TagSession.packetCount++
                TagLogger.logDataVerbose(
                    "PACKET",
                    "id=${parsed.packetId} samples=${parsed.rows.size}",
                )
                updateRecordingUi()
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                TagLogger.log(LogCategory.ERRORS, "BLE_ERROR", message)
                Toast.makeText(this@DeviceActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val device = TagSession.connectedDevice
        if (device == null) {
            finish()
            return
        }

        binding.deviceTitle.text = device.name
        binding.backBtn.setOnClickListener { finish() }
        binding.deviceMenuBtn.setOnClickListener { showDeviceMenu(it) }
        binding.startBtn.setOnClickListener { startRecording() }
        binding.stopBtn.setOnClickListener { stopRecording() }
        binding.saveBtn.text = getString(R.string.export)
        binding.saveBtn.setOnClickListener { exportLastRecording() }
        binding.openCameraBtn.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        bleManager.listener = bleListener
        updateRecordingUi()
    }

    override fun onResume() {
        super.onResume()
        updateRecordingUi()
    }

    private fun deviceLabel(): String =
        TagSession.connectedDevice?.let { "${it.name} ${it.address}" } ?: "?"

    private fun maybeUpdateDeviceId(deviceId: String) {
        val connected = TagSession.connectedDevice ?: return
        if (connected.name == deviceId) return
        if (connected.name.equals("Tag", ignoreCase = true) ||
            !connected.name.startsWith("Tag_", ignoreCase = true)
        ) {
            connected.name = deviceId
            binding.deviceTitle.text = deviceId
        }
    }

    private fun showDeviceMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.disconnect))
        popup.menu.add(0, 2, 0, getString(R.string.logs))
        popup.menu.add(0, 3, 0, getString(R.string.history))
        popup.menu.add(0, 4, 0, getString(R.string.profile))
        popup.menu.add(0, 5, 0, getString(R.string.camera_settings))
        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                1 -> {
                    bleManager.disconnectTag()
                    TagSession.clearConnection()
                    finish()
                    true
                }
                2 -> {
                    startActivity(Intent(this, LogViewerActivity::class.java))
                    true
                }
                3 -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                4 -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    true
                }
                5 -> {
                    startActivity(Intent(this, CameraConfigActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun startRecording() {
        if (!bleManager.isTagReady) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        TagLogger.clearSessionLog()
        TagSession.receivedRows.clear()
        TagSession.packetIds.clear()
        TagSession.packetCount = 0
        TagSession.parseFailures = 0
        TagSession.lastFeedbackText = ""
        TagSession.lastHistoryEntry = null
        TagSession.tagUptimeAtSync = null
        TagSession.recordingState = RecordingState.SYNCING
        updateRecordingUi()
        TagSession.syncBaseUnixMs = System.currentTimeMillis()
        TagLogger.log(
            LogCategory.CONTROL,
            "START",
            "unix_ms=${TagSession.syncBaseUnixMs} device=${deviceLabel()}",
        )
        bleManager.startRecording(TagSession.syncBaseUnixMs)
        binding.root.postDelayed({
            if (TagSession.recordingState == RecordingState.SYNCING) {
                TagSession.recordingState = RecordingState.RECEIVING
                updateRecordingUi()
            }
        }, 300)
    }

    private fun stopRecording() {
        if (TagSession.recordingState != RecordingState.RECEIVING &&
            TagSession.recordingState != RecordingState.SYNCING
        ) return

        bleManager.stopRecording()
        TagLogger.log(LogCategory.CONTROL, "STOP", deviceLabel())

        val report = SessionAnalyzer.analyze(
            packetCount = TagSession.packetCount,
            rows = TagSession.receivedRows.toList(),
            packetIds = TagSession.packetIds.toList(),
            parseFailures = TagSession.parseFailures,
        )
        if (report.hasPossibleLoss) {
            TagLogger.log(LogCategory.GAPS, "POSSIBLE_LOSS", report.statusDetail)
        } else {
            TagLogger.logDataSummary(
                "SESSION_OK",
                "packets=${report.packetCount} samples=${report.sampleCount}",
            )
        }
        TagLogger.logDataSummary(
            "SESSION_SUMMARY",
            "packets=${report.packetCount} samples=${report.sampleCount} status=${report.statusShort}",
        )

        TagSession.lastFeedbackText = report.feedbackText
        TagSession.recordingState = RecordingState.SAVING
        updateRecordingUi()

        try {
            val deviceName = TagSession.connectedDevice?.name
                ?: TagSession.receivedRows.firstOrNull()?.deviceId
                ?: "Tag"
            val profilePrefix = TagSession.userProfile.safeFileName
            val baseName = RecordingStore.makeBaseName(deviceName, profilePrefix = profilePrefix)
            val csv = CsvExporter.build(TagSession.receivedRows)
            val logBody = buildString {
                appendLine("Tag session log")
                appendLine("base_name=$baseName")
                appendLine("device=${deviceLabel()}")
                appendLine("packets=${report.packetCount}")
                appendLine("samples=${report.sampleCount}")
                appendLine("status=${report.statusShort}")
                if (report.statusDetail.isNotBlank()) appendLine(report.statusDetail)
                appendLine("---")
                append(TagLogger.sessionSnapshot())
            }
            val entry = RecordingStore.saveRecording(
                context = this,
                baseName = baseName,
                csvContent = csv,
                logContent = logBody,
                packetCount = report.packetCount,
                sampleCount = report.sampleCount,
                status = report.statusShort,
            )
            TagSession.lastHistoryEntry = entry
            TagSession.lastFeedbackText = report.feedbackText
            TagSession.recordingState = RecordingState.RECEIVED
            updateRecordingUi()
            Toast.makeText(this, "Saved ${entry.baseName}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "AUTO_SAVE_FAIL", e.message ?: "")
            TagSession.recordingState = RecordingState.RECEIVED
            TagSession.lastFeedbackText =
                report.feedbackText.replace(
                    "Saved to History (CSV + log)",
                    "Save failed: ${e.message}",
                )
            updateRecordingUi()
            Toast.makeText(this, "Auto-save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportLastRecording() {
        val entry = TagSession.lastHistoryEntry
        if (entry == null || !entry.csvFile.exists()) {
            Toast.makeText(this, R.string.export_hint, Toast.LENGTH_SHORT).show()
            return
        }
        pendingExportCsv = entry.csvFile.readText(Charsets.UTF_8)
        pendingExportName = entry.csvFile.name
        exportLauncher.launch(entry.csvFile.name)
    }

    private fun updateRecordingUi() {
        val state = TagSession.recordingState
        val isRunning = state == RecordingState.SYNCING || state == RecordingState.RECEIVING

        binding.startBtn.isEnabled = !isRunning && state != RecordingState.SAVING
        binding.stopBtn.isEnabled = isRunning
        binding.saveBtn.visibility =
            if (state == RecordingState.RECEIVED && TagSession.lastHistoryEntry != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
        binding.saveBtn.isEnabled = state == RecordingState.RECEIVED

        when (state) {
            RecordingState.IDLE -> {
                binding.recordStatus.text = getString(R.string.status_ready)
                binding.recordMeta.text =
                    "Start sends mobile date/time to tag once. Stop auto-saves CSV + log."
            }
            RecordingState.SYNCING -> {
                binding.recordStatus.text = "Syncing mobile time to tag…"
                binding.recordMeta.text = "Sending START command…"
            }
            RecordingState.RECEIVING -> {
                binding.recordStatus.text =
                    "Receiving… ${TagSession.packetCount} packets"
                binding.recordMeta.text =
                    "Synced at ${CsvExporter.formatDateTime(TagSession.syncBaseUnixMs)}. " +
                        "Rows: ${TagSession.receivedRows.size}"
            }
            RecordingState.RECEIVED -> {
                binding.recordStatus.text =
                    "Received · ${TagSession.packetCount} packets"
                binding.recordMeta.text =
                    TagSession.lastFeedbackText.ifBlank {
                        "Auto-saved to History. Use Export for a copy."
                    }
            }
            RecordingState.CONVERTING -> {
                binding.recordStatus.text = "Preparing files…"
            }
            RecordingState.SAVING -> {
                binding.recordStatus.text = "Saving CSV + log…"
                binding.recordMeta.text = TagSession.lastFeedbackText
            }
        }
    }
}

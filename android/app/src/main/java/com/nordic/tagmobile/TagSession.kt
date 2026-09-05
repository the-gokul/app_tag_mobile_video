package com.nordic.tagmobile

import com.nordic.tagmobile.model.ConnectedDevice
import com.nordic.tagmobile.model.CameraConfig
import com.nordic.tagmobile.model.DeviceConfig
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.model.UserProfile
import com.nordic.tagmobile.protocol.SensorCsvRow
import com.nordic.tagmobile.storage.HistoryEntry

object TagSession {
    var connectedDevice: ConnectedDevice? = null
    var recordingState: RecordingState = RecordingState.IDLE
    var syncBaseUnixMs: Long = 0L
    var tagUptimeAtSync: Long? = null
    val receivedRows: MutableList<SensorCsvRow> = mutableListOf()
    val packetIds: MutableList<Long> = mutableListOf()
    var packetCount: Int = 0
    var parseFailures: Int = 0
    var deviceConfig: DeviceConfig = DeviceConfig.default()
    var customDataEnabled: Boolean = false
    var includeSiUnits: Boolean = false
    var lastHistoryEntry: HistoryEntry? = null
    var lastFeedbackText: String = ""

    // ── Profile & Camera ─────────────────────────────────────────────────────
    var userProfile: UserProfile = UserProfile()
    var cameraConfig: CameraConfig = CameraConfig()

    fun resetRecording() {
        recordingState = RecordingState.IDLE
        syncBaseUnixMs = 0L
        tagUptimeAtSync = null
        receivedRows.clear()
        packetIds.clear()
        packetCount = 0
        parseFailures = 0
        lastFeedbackText = ""
    }

    fun isConnected(): Boolean = connectedDevice != null

    fun clearConnection() {
        connectedDevice = null
        resetRecording()
        customDataEnabled = false
        includeSiUnits = false
        deviceConfig = DeviceConfig.default()
        lastHistoryEntry = null
    }
}

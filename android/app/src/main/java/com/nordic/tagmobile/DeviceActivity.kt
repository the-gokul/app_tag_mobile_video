package com.nordic.tagmobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nordic.tagmobile.analysis.SessionAnalyzer
import com.nordic.tagmobile.ble.TagBleManager
import com.nordic.tagmobile.databinding.ActivityDeviceBinding
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.CameraConfig
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.protocol.CsvExporter
import com.nordic.tagmobile.protocol.SensorPacketParser
import com.nordic.tagmobile.protocol.SensorPacketParser.HEADER_SIZE
import com.nordic.tagmobile.storage.RecordingStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceBinding
    private val bleManager get() = TagApp.instance.bleManager

    // Camera fields
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var videoFile: File? = null
    private var isRecording = false
    private var isFlashOn = false
    private var isFrontCamera = false
    private var timestampHandler: Handler? = null
    private val timestampRunnable = object : Runnable {
        override fun run() {
            updateTimestamp()
            timestampHandler?.postDelayed(this, 500)
        }
    }
    private val cameraConfig: CameraConfig get() = TagSession.cameraConfig

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close(); cameraDevice = null
            runOnUiThread { Toast.makeText(this@DeviceActivity, "Camera error $error", Toast.LENGTH_SHORT).show() }
        }
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

        applyOrientationFromConfig()

        binding.deviceTitle.text = device.name
        binding.backBtn.setOnClickListener { finish() }
        binding.deviceMenuBtn.setOnClickListener { showDeviceMenu(it) }
        
        binding.recordBtnContainer.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }
        binding.flashBtn.setOnClickListener { toggleFlash() }
        binding.switchCameraBtn.setOnClickListener { switchCamera() }

        bleManager.listener = bleListener
        
        binding.timestampText.text = currentTimestamp()

        timestampHandler = Handler(mainLooper)
        timestampHandler?.post(timestampRunnable)
    }

    override fun onResume() {
        super.onResume()
        applyOrientationFromConfig()
        startBackgroundThread()
        if (binding.cameraPreview.isAvailable) {
            openCamera()
        } else {
            binding.cameraPreview.surfaceTextureListener = surfaceListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        timestampHandler?.removeCallbacks(timestampRunnable)
        super.onDestroy()
    }

    private fun applyOrientationFromConfig() {
        requestedOrientation = when (cameraConfig.orientation) {
            CameraConfig.Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            CameraConfig.Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            CameraConfig.Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Camera permission needed", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            facing == if (isFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return
        manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
    }

    private fun toggleFlash() {
        if (isFrontCamera) return
        isFlashOn = !isFlashOn
        startPreview()
    }

    private fun switchCamera() {
        isFrontCamera = !isFrontCamera
        isFlashOn = false
        closeCamera()
        openCamera()
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        mediaRecorder?.release(); mediaRecorder = null
    }

    private fun startPreview() {
        val texture = binding.cameraPreview.surfaceTexture ?: return
        val previewSurface = Surface(texture)
        val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.FLASH_MODE, if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }
        cameraDevice!!.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@DeviceActivity, "Preview failed", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler,
        )
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

        // Setup BLE session
        TagLogger.clearSessionLog()
        TagSession.receivedRows.clear()
        TagSession.packetIds.clear()
        TagSession.packetCount = 0
        TagSession.parseFailures = 0
        TagSession.lastFeedbackText = ""
        TagSession.lastHistoryEntry = null
        TagSession.tagUptimeAtSync = null
        TagSession.syncBaseUnixMs = System.currentTimeMillis()
        TagSession.recordingState = RecordingState.RECEIVING

        TagLogger.log(
            LogCategory.CONTROL,
            "START",
            "unix_ms=${TagSession.syncBaseUnixMs} device=${deviceLabel()}",
        )
        bleManager.startRecording(TagSession.syncBaseUnixMs)

        // Setup video recording
        isRecording = true
        binding.recordBtnInner.setBackgroundResource(R.drawable.bg_record_btn_inner_active)
        binding.recordBtnLabel.text = getString(R.string.stop)

        val profile = TagSession.userProfile
        val deviceName = TagSession.connectedDevice?.name?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "Tag"
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(TagSession.syncBaseUnixMs))
        val safeProfile = profile.safeFileName
        val baseName = if (safeProfile.isNotBlank()) "${safeProfile}_${deviceName}_${ts}" else "${deviceName}_${ts}"
        val ext = if (cameraConfig.videoFormat == CameraConfig.VideoFormat.MP4) "mp4" else "webm"
        val videoDir = File(filesDir, "videos").also { it.mkdirs() }
        videoFile = File(videoDir, "$baseName.$ext")

        // Setup MediaRecorder (Video only, NO AUDIO)
        @Suppress("DEPRECATION")
        val mr = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(cameraConfig.videoFormat.outputFormat)
            setVideoEncoder(cameraConfig.videoCodec.encoderValue)
            setVideoSize(cameraConfig.resolution.width, cameraConfig.resolution.height)
            setVideoFrameRate(cameraConfig.frameRate.fps)
            setOutputFile(videoFile!!.absolutePath)
            prepare()
        }
        mediaRecorder = mr

        // Restart camera session with recorder surface
        val texture = binding.cameraPreview.surfaceTexture ?: return
        val previewSurface = Surface(texture)
        val recorderSurface = mr.surface

        val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(recorderSurface)
            set(CaptureRequest.FLASH_MODE, if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }

        captureSession?.close()
        cameraDevice!!.createCaptureSession(
            listOf(previewSurface, recorderSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                    mr.start()
                    TagLogger.log(LogCategory.FILE, "VIDEO_RECORDING_START", videoFile!!.name)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@DeviceActivity, "Recording setup failed", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler,
        )
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        binding.recordBtnInner.setBackgroundResource(R.drawable.bg_record_btn_inner)
        binding.recordBtnLabel.text = getString(R.string.start)

        // Stop BLE
        bleManager.stopRecording()
        TagLogger.log(LogCategory.CONTROL, "STOP", deviceLabel())

        // Stop video
        try {
            captureSession?.stopRepeating()
            mediaRecorder?.stop()
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "VIDEO_STOP_ERR", e.message ?: "")
        }
        mediaRecorder?.release(); mediaRecorder = null
        TagLogger.log(LogCategory.FILE, "VIDEO_SAVED", videoFile?.name ?: "")

        // Restart preview-only session
        startPreview()

        val vFile = videoFile
        val vSize = vFile?.length()?.let { formatBytes(it) } ?: "?"

        // Save data files (CSV/Log)
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

        try {
            val deviceName = TagSession.connectedDevice?.name
                ?: TagSession.receivedRows.firstOrNull()?.deviceId
                ?: "Tag"
            val profilePrefix = TagSession.userProfile.safeFileName
            val baseName = RecordingStore.makeBaseName(deviceName, profilePrefix = profilePrefix)
            val dataFile = RecordingStore.dataFile(this, baseName)
            
            com.nordic.tagmobile.protocol.ExcelExporter.exportToExcel(
                file = dataFile,
                rows = TagSession.receivedRows,
                profile = TagSession.userProfile,
                packetCount = report.packetCount,
                sampleCount = report.sampleCount,
                status = report.statusShort
            )
            
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
                logContent = logBody,
                packetCount = report.packetCount,
                sampleCount = report.sampleCount,
                status = report.statusShort,
            )
            TagSession.lastHistoryEntry = entry
            TagSession.lastFeedbackText = report.feedbackText
            TagSession.recordingState = RecordingState.RECEIVED
            Toast.makeText(this, "Saved ${entry.baseName}\nVideo: $vSize", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "AUTO_SAVE_FAIL", e.message ?: "")
            TagSession.recordingState = RecordingState.RECEIVED
            TagSession.lastFeedbackText =
                report.feedbackText.replace(
                    "Saved to History",
                    "Save failed: ${e.message}",
                )
            Toast.makeText(this, "Auto-save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun currentTimestamp(): String {
        val fmt = SimpleDateFormat("dd-MM-yyyy HH:mm:ss:SSS", Locale.US)
        return fmt.format(Date())
    }

    private fun updateTimestamp() {
        binding.timestampText.text = currentTimestamp()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}

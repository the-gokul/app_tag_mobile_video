package com.nordic.tagmobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nordic.tagmobile.analysis.SessionAnalyzer
import com.nordic.tagmobile.ble.TagBleManager
import com.nordic.tagmobile.databinding.ActivityDeviceBinding
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.protocol.SensorPacketParser
import com.nordic.tagmobile.protocol.SensorPacketParser.HEADER_SIZE
import com.nordic.tagmobile.protocol.XlsxExporter
import com.nordic.tagmobile.camera.TimestampBurnOverlay
import com.nordic.tagmobile.storage.GalleryPublisher
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
    private var lastVideoFile: File? = null
    private var timestampOverlay: TimestampBurnOverlay? = null
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
    private var previewSize: Size? = null
    private var activeCameraId: String? = null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            configureTransform(w, h)
        }
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
                if (isRecording && message.contains("START", ignoreCase = true)) {
                    // Tag rejected Start after camera already rolled — stop video and reset UI
                    try {
                        captureSession?.stopRepeating()
                    } catch (_: Exception) {
                    }
                    try {
                        timestampOverlay?.release()
                    } catch (_: Exception) {
                    }
                    timestampOverlay = null
                    try {
                        mediaRecorder?.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        mediaRecorder?.release()
                    } catch (_: Exception) {
                    }
                    mediaRecorder = null
                    videoFile?.delete()
                    videoFile = null
                    isRecording = false
                    TagSession.recordingState = RecordingState.IDLE
                    TagSession.sessionBaseName = ""
                    setRecordButtonUi(recording = false)
                    startPreview()
                    Toast.makeText(this@DeviceActivity, message, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@DeviceActivity, message, Toast.LENGTH_SHORT).show()
                }
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

        // Freeze current phone orientation — no auto-rotate while recording
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        binding.deviceTitle.text = device.name
        binding.backBtn.setOnClickListener { finish() }
        binding.deviceMenuBtn.setOnClickListener { showDeviceMenu(it) }
        
        binding.recordBtnContainer.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }
        binding.flashBtn.setOnClickListener { toggleFlash() }
        binding.switchCameraBtn.setOnClickListener { switchCamera() }
        binding.lastVideoThumb.setOnClickListener { openLastVideo() }
        setRecordButtonUi(recording = false)
        refreshLastVideoThumb()

        bleManager.listener = bleListener
        
        binding.timestampText.text = currentTimestamp()

        timestampHandler = Handler(mainLooper)
        timestampHandler?.post(timestampRunnable)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        refreshLastVideoThumb()
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
        activeCameraId = cameraId
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val choices = map?.getOutputSizes(SurfaceTexture::class.java)
        previewSize = choosePreviewSize(choices)
        previewSize?.let { size ->
            binding.cameraPreview.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
            configureTransform(binding.cameraPreview.width, binding.cameraPreview.height)
        }
        manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
    }

    /** Prefer sensor aspect vs view aspect (phone-camera style). */
    private fun choosePreviewSize(choices: Array<Size>?): Size {
        if (choices.isNullOrEmpty()) return Size(1280, 720)
        val viewW = binding.cameraPreview.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val viewH = binding.cameraPreview.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val viewAspect = viewW.toFloat() / viewH.toFloat().coerceAtLeast(1f)
        val candidates = choices.filter { it.width <= 1920 && it.height <= 1080 }.ifEmpty { choices.toList() }
        return candidates.minByOrNull { size ->
            val sensorAspect = size.width.toFloat() / size.height.toFloat()
            val previewAspect = if (isPortraitDisplay()) 1f / sensorAspect else sensorAspect
            kotlin.math.abs(previewAspect - viewAspect)
        } ?: candidates[0]
    }

    private fun isPortraitDisplay(): Boolean {
        val rot = windowManager.defaultDisplay.rotation
        return rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180
    }

    /**
     * Center-crop preview (phone camera style) — fills the frame without squashing.
     * Based on Camera2Basic transform, applied for all rotations.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val size = previewSize ?: return
        if (viewWidth == 0 || viewHeight == 0) return
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(
            viewHeight.toFloat() / bufferRect.height(),
            viewWidth.toFloat() / bufferRect.width(),
        )
        matrix.postScale(scale, scale, centerX, centerY)
        when (rotation) {
            Surface.ROTATION_90 -> matrix.postRotate(90f, centerX, centerY)
            Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
            Surface.ROTATION_270 -> matrix.postRotate(270f, centerX, centerY)
        }
        binding.cameraPreview.setTransform(matrix)
    }

    /** Write rotation metadata so portrait clips play as portrait (and landscape as landscape). */
    private fun videoOrientationHint(): Int {
        val cameraId = activeCameraId ?: return if (isPortraitDisplay()) 90 else 0
        return try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val deviceRotation = when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation + deviceRotation) % 360
            } else {
                (sensorOrientation - deviceRotation + 360) % 360
            }
        } catch (_: Exception) {
            if (isPortraitDisplay()) 90 else 0
        }
    }

    private fun setRecordButtonUi(recording: Boolean) {
        val density = resources.displayMetrics.density
        val sizeDp = if (recording) 28f else 60f
        val px = (sizeDp * density).toInt()
        val lp = binding.recordBtnInner.layoutParams
        lp.width = px
        lp.height = px
        binding.recordBtnInner.layoutParams = lp
        binding.recordBtnInner.setBackgroundResource(
            if (recording) R.drawable.bg_record_btn_inner_active
            else R.drawable.bg_record_btn_inner,
        )
        binding.recordBtnLabel.text = getString(if (recording) R.string.stop else R.string.start)
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
        try {
            timestampOverlay?.release()
        } catch (_: Exception) {
        }
        timestampOverlay = null
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
        val camera = cameraDevice
        val texture = binding.cameraPreview.surfaceTexture
        if (camera == null || texture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Shared base name for video + CSV + log (must match History pairing)
        TagLogger.clearSessionLog()
        TagSession.receivedRows.clear()
        TagSession.packetIds.clear()
        TagSession.packetCount = 0
        TagSession.parseFailures = 0
        TagSession.lastFeedbackText = ""
        TagSession.lastHistoryEntry = null
        TagSession.tagUptimeAtSync = null
        TagSession.syncBaseUnixMs = System.currentTimeMillis()
        val deviceName = TagSession.connectedDevice?.name ?: "Tag"
        val profilePrefix = TagSession.userProfile.safeFileName
        TagSession.sessionBaseName = RecordingStore.makeBaseName(
            deviceName,
            atMs = TagSession.syncBaseUnixMs,
            profilePrefix = profilePrefix,
        )

        val videoDir = File(filesDir, "videos").also { it.mkdirs() }
        videoFile = File(videoDir, "${TagSession.sessionBaseName}.mp4")

        // Phone-default camcorder profile (no CameraConfig forced size/orientation)
        val camProfile = try {
            val id = activeCameraId?.toIntOrNull()
            when {
                id != null && CamcorderProfile.hasProfile(id, CamcorderProfile.QUALITY_720P) ->
                    CamcorderProfile.get(id, CamcorderProfile.QUALITY_720P)
                id != null && CamcorderProfile.hasProfile(id, CamcorderProfile.QUALITY_HIGH) ->
                    CamcorderProfile.get(id, CamcorderProfile.QUALITY_HIGH)
                CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_720P) ->
                    CamcorderProfile.get(CamcorderProfile.QUALITY_720P)
                else -> CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
            }
        } catch (_: Exception) {
            CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
        }

        // Prepare MediaRecorder before BLE Start so a camera failure does not leave the Tag streaming
        val orientationHint = videoOrientationHint()
        val mr: MediaRecorder
        try {
            @Suppress("DEPRECATION")
            mr = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(camProfile.videoFrameWidth, camProfile.videoFrameHeight)
                setVideoFrameRate(camProfile.videoFrameRate)
                setVideoEncodingBitRate(camProfile.videoBitRate)
                setOrientationHint(orientationHint)
                setOutputFile(videoFile!!.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "VIDEO_PREPARE_ERR", e.message ?: "")
            videoFile?.delete()
            videoFile = null
            Toast.makeText(this, "Video setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        mediaRecorder = mr

        // Burn UI-matching timestamp into the encoded video (preview TextView unchanged)
        val overlay = try {
            TimestampBurnOverlay(
                outputSurface = mr.surface,
                videoWidth = camProfile.videoFrameWidth,
                videoHeight = camProfile.videoFrameHeight,
                orientationHint = orientationHint,
                timestampText = { currentTimestamp() },
            ).also { it.start() }
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "TIMESTAMP_OVERLAY_ERR", e.message ?: "")
            try {
                mr.reset()
                mr.release()
            } catch (_: Exception) {
            }
            mediaRecorder = null
            videoFile?.delete()
            videoFile = null
            Toast.makeText(this, "Video overlay setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        timestampOverlay = overlay
        val recorderInput = overlay.cameraInputSurface
        if (recorderInput == null) {
            abortStartAfterCameraFail("Overlay surface missing")
            return
        }

        val previewSurface = Surface(texture)
        val request = try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderInput)
                set(
                    CaptureRequest.FLASH_MODE,
                    if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
                )
            }
        } catch (e: Exception) {
            abortStartAfterCameraFail("Capture request failed: ${e.message}")
            return
        }

        captureSession?.close()
        try {
            camera.createCaptureSession(
                listOf(previewSurface, recorderInput),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(request.build(), null, backgroundHandler)
                            mr.start()
                        } catch (e: Exception) {
                            runOnUiThread {
                                abortStartAfterCameraFail("Video start failed: ${e.message}")
                            }
                            return
                        }

                        // Camera OK → start Tag stream
                        TagSession.recordingState = RecordingState.RECEIVING
                        TagLogger.log(
                            LogCategory.CONTROL,
                            "START",
                            "unix_ms=${TagSession.syncBaseUnixMs} base=${TagSession.sessionBaseName} device=${deviceLabel()}",
                        )
                        bleManager.startRecording(TagSession.syncBaseUnixMs)
                        TagLogger.log(LogCategory.FILE, "VIDEO_RECORDING_START", videoFile!!.name)

                        runOnUiThread {
                            isRecording = true
                            setRecordButtonUi(recording = true)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread {
                            abortStartAfterCameraFail("Recording setup failed")
                        }
                    }
                },
                backgroundHandler,
            )
        } catch (e: Exception) {
            abortStartAfterCameraFail("Camera session failed: ${e.message}")
        }
    }

    /** Release recorder / partial video file when Start fails before BLE is running. */
    private fun abortStartAfterCameraFail(message: String) {
        TagLogger.log(LogCategory.ERRORS, "VIDEO_START_ABORT", message)
        try {
            timestampOverlay?.release()
        } catch (_: Exception) {
        }
        timestampOverlay = null
        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        videoFile?.delete()
        videoFile = null
        TagSession.sessionBaseName = ""
        TagSession.recordingState = RecordingState.IDLE
        isRecording = false
        setRecordButtonUi(recording = false)
        startPreview()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        setRecordButtonUi(recording = false)

        // Stop BLE
        bleManager.stopRecording()
        TagLogger.log(LogCategory.CONTROL, "STOP", deviceLabel())

        // Stop camera request first, then overlay, then MediaRecorder
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            timestampOverlay?.release()
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "OVERLAY_STOP_ERR", e.message ?: "")
        }
        timestampOverlay = null
        try {
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
        // Publish a copy into Movies/Tag so Gallery / Photos can see it
        if (vFile != null && vFile.exists() && vFile.length() > 0L) {
            val galleryUri = GalleryPublisher.publishVideo(this, vFile)
            if (galleryUri != null) {
                TagLogger.log(LogCategory.FILE, "GALLERY_URI", galleryUri.toString())
            }
        }

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
            val baseName = TagSession.sessionBaseName.ifBlank {
                val deviceName = TagSession.connectedDevice?.name
                    ?: TagSession.receivedRows.firstOrNull()?.deviceId
                    ?: "Tag"
                RecordingStore.makeBaseName(
                    deviceName,
                    atMs = TagSession.syncBaseUnixMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    profilePrefix = TagSession.userProfile.safeFileName,
                )
            }
            val dataFile = RecordingStore.dataFile(this, baseName)
            XlsxExporter.write(
                outFile = dataFile,
                rows = TagSession.receivedRows,
                summary = XlsxExporter.SummaryInfo(
                    profile = TagSession.userProfile,
                    deviceConfig = TagSession.deviceConfig,
                    deviceName = TagSession.connectedDevice?.name
                        ?: TagSession.receivedRows.firstOrNull()?.deviceId
                        ?: "Tag",
                    packetCount = report.packetCount,
                    sampleCount = report.sampleCount,
                    status = report.statusShort,
                ),
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
            Toast.makeText(this, "Saved ${entry.baseName}\nVideo: $vSize (also in Gallery)", Toast.LENGTH_LONG).show()
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
        refreshLastVideoThumb()
    }

    private fun latestVideoFile(): File? {
        val dir = File(filesDir, "videos")
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.isFile && (it.extension.equals("mp4", true) || it.extension.equals("webm", true)) && it.length() > 0L }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun refreshLastVideoThumb() {
        val file = latestVideoFile()
        lastVideoFile = file
        if (file == null) {
            binding.lastVideoThumb.setImageDrawable(null)
            binding.lastVideoThumb.visibility = View.GONE
            return
        }
        val frame = extractVideoFrame(file)
        if (frame == null) {
            binding.lastVideoThumb.setImageDrawable(null)
            binding.lastVideoThumb.visibility = View.VISIBLE
            return
        }
        binding.lastVideoThumb.setImageBitmap(frame)
        binding.lastVideoThumb.visibility = View.VISIBLE
        binding.lastVideoThumb.clipToOutline = true
    }

    private fun extractVideoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun openLastVideo() {
        if (isRecording) return
        val file = lastVideoFile ?: latestVideoFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.no_last_video, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.last_video)))
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "OPEN_LAST_VIDEO_FAIL", e.message ?: "")
            Toast.makeText(this, R.string.open_last_video_failed, Toast.LENGTH_SHORT).show()
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

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
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nordic.tagmobile.databinding.ActivityCameraBinding
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.CameraConfig
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.storage.RecordingStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * CameraActivity — full camera preview with BLE-synced recording.
 *
 * - Start button: sends BLE START to tag + begins video recording
 * - Stop button:  sends BLE STOP to tag + stops video recording + saves files
 * - Timestamp overlay drawn in real-time on top of camera preview
 * - Orientation follows CameraConfig (auto/portrait/landscape)
 * - Resolution, codec, format, fps applied from CameraConfig
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val bleManager get() = TagApp.instance.bleManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var videoFile: File? = null
    private var isRecording = false
    private var timestampHandler: Handler? = null
    private val timestampRunnable = object : Runnable {
        override fun run() {
            updateTimestamp()
            timestampHandler?.postDelayed(this, 500)
        }
    }

    private val cameraConfig: CameraConfig get() = TagSession.cameraConfig

    // ── Surface/texture listener ──────────────────────────────────────────────
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    // ── Camera state ──────────────────────────────────────────────────────────
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
            runOnUiThread { Toast.makeText(this@CameraActivity, "Camera error $error", Toast.LENGTH_SHORT).show() }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyOrientationFromConfig()

        binding.backFromCameraBtn.setOnClickListener { finish() }
        binding.cameraConfigBtn.setOnClickListener {
            startActivity(Intent(this, CameraConfigActivity::class.java))
        }
        binding.startBtn.setOnClickListener { startCapture() }
        binding.stopBtn.setOnClickListener { stopCapture() }

        binding.startBtn.isEnabled = true
        binding.stopBtn.isEnabled = false
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

    // ── Orientation ───────────────────────────────────────────────────────────
    private fun applyOrientationFromConfig() {
        requestedOrientation = when (cameraConfig.orientation) {
            CameraConfig.Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            CameraConfig.Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            CameraConfig.Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    // ── Camera open / close ───────────────────────────────────────────────────
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
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return
        manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        mediaRecorder?.release(); mediaRecorder = null
    }

    // ── Preview ───────────────────────────────────────────────────────────────
    private fun startPreview() {
        val texture = binding.cameraPreview.surfaceTexture ?: return
        val previewSurface = Surface(texture)
        val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
        }
        cameraDevice!!.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@CameraActivity, "Preview failed", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler,
        )
    }

    // ── Recording ─────────────────────────────────────────────────────────────
    private fun startCapture() {
        if (!bleManager.isTagReady) {
            Toast.makeText(this, "Not connected to tag", Toast.LENGTH_SHORT).show()
            return
        }
        isRecording = true
        binding.startBtn.isEnabled = false
        binding.stopBtn.isEnabled = true

        // BLE sync
        TagSession.syncBaseUnixMs = System.currentTimeMillis()
        TagSession.recordingState = RecordingState.RECEIVING
        TagLogger.log(LogCategory.CONTROL, "CAMERA_START", "unix_ms=${TagSession.syncBaseUnixMs}")
        bleManager.startRecording(TagSession.syncBaseUnixMs)

        // Video file
        val profile = TagSession.userProfile
        val deviceName = TagSession.connectedDevice?.name?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "Tag"
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safeProfile = profile.safeFileName
        val baseName = "${safeProfile}_${deviceName}_${ts}"
        val ext = if (cameraConfig.videoFormat == CameraConfig.VideoFormat.MP4) "mp4" else "webm"
        val videoDir = File(filesDir, "videos").also { it.mkdirs() }
        videoFile = File(videoDir, "$baseName.$ext")

        // Setup MediaRecorder
        @Suppress("DEPRECATION")
        val mr = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(cameraConfig.videoFormat.outputFormat)
            setVideoEncoder(cameraConfig.videoCodec.encoderValue)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
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
                    Toast.makeText(this@CameraActivity, "Recording setup failed", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler,
        )
    }

    private fun stopCapture() {
        if (!isRecording) return
        isRecording = false
        binding.startBtn.isEnabled = true
        binding.stopBtn.isEnabled = false

        // Stop BLE
        bleManager.stopRecording()
        TagSession.recordingState = RecordingState.SAVING
        TagLogger.log(LogCategory.CONTROL, "CAMERA_STOP", "")

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
        TagSession.recordingState = RecordingState.RECEIVED

        runOnUiThread {
            Toast.makeText(this, "Video saved · $vSize", Toast.LENGTH_LONG).show()
        }
    }

    // ── Timestamp helpers ─────────────────────────────────────────────────────
    private fun currentTimestamp(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)
        return fmt.format(Date())
    }

    private fun updateTimestamp() {
        binding.timestampText.text = currentTimestamp()
    }

    // ── Background thread ─────────────────────────────────────────────────────
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

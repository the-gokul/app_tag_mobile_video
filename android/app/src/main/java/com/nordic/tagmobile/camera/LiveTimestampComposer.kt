package com.nordic.tagmobile.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 → OpenGL (camera + timestamp) → MediaRecorder.
 *
 * Standard Android camera pattern (NOT portrait-pixel bake):
 * - Encode buffer is sensor-native landscape (e.g. 1280x720).
 * - MediaRecorder.setOrientationHint(90) makes Gallery play it as portrait.
 * - Camera is drawn with SurfaceTexture transform only (no UV hacks).
 * - Timestamp is placed/rotated in the landscape buffer so that AFTER the
 *   player applies orientationHint it appears upright at the bottom.
 */
class LiveTimestampComposer(
    private val outputSurface: Surface,
    private val videoWidth: Int,
    private val videoHeight: Int,
    /** Same value passed to MediaRecorder.setOrientationHint (0/90/180/270). */
    private val orientationHint: Int,
    private val timestampText: () -> String,
) : SurfaceTexture.OnFrameAvailableListener {

    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    private var surfaceTexture: SurfaceTexture? = null
    var cameraInputSurface: Surface? = null
        private set

    private var oesTexId = 0
    private var textTexId = 0
    private var program = 0
    private var textProgram = 0
    private val stMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)
    private var frameAvailable = false

    fun start() {
        if (running.getAndSet(true)) return
        val t = HandlerThread("LiveTimestampComposer").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val ready = CountDownLatch(1)
        var error: Exception? = null
        h.post {
            try {
                initEgl()
                initGl()
            } catch (e: Exception) {
                error = e
                running.set(false)
                releaseInternal()
            } finally {
                ready.countDown()
            }
        }
        ready.await(3, TimeUnit.SECONDS)
        error?.let { throw it }
        if (cameraInputSurface == null) {
            running.set(false)
            throw IllegalStateException("Failed to create camera input surface")
        }
    }

    fun release() {
        running.set(false)
        val h = handler
        if (h != null) {
            val done = CountDownLatch(1)
            h.post {
                try {
                    releaseInternal()
                } finally {
                    done.countDown()
                }
            }
            done.await(2, TimeUnit.SECONDS)
        } else {
            releaseInternal()
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        handler?.post {
            synchronized(this) { frameAvailable = true }
            drawFrame()
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            throw RuntimeException("eglChooseConfig failed")
        }
        val cfg = configs[0] ?: throw RuntimeException("No EGL config")
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, cfg, outputSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun initGl() {
        oesTexId = createOesTexture()
        textTexId = create2dTexture()
        program = buildProgram(VERTEX_OES, FRAGMENT_OES)
        textProgram = buildProgram(VERTEX_TEX, FRAGMENT_TEX)

        val st = SurfaceTexture(oesTexId)
        st.setDefaultBufferSize(videoWidth, videoHeight)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        cameraInputSurface = Surface(st)
    }

    private fun drawFrame() {
        if (!running.get() || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        val st = surfaceTexture ?: return

        synchronized(this) {
            if (!frameAvailable) return
            frameAvailable = false
        }

        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        GLES20.glViewport(0, 0, videoWidth, videoHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 1) Camera — rotate the quad to correct for sensor vs orientationHint
        //    The sensor is landscape; orientationHint tells the player to rotate CW.
        //    We counter-rotate the quad the same amount so frames are upright in the buffer.
        Matrix.setIdentityM(rotMatrix, 0)
        when (orientationHint) {
            90  -> Matrix.rotateM(rotMatrix, 0, 90f,  0f, 0f, 1f)
            180 -> Matrix.rotateM(rotMatrix, 0, 180f, 0f, 0f, 1f)
            270 -> Matrix.rotateM(rotMatrix, 0, -90f, 0f, 0f, 1f)
            // 0 or unknown: no rotation needed
        }
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uTex = GLES20.glGetUniformLocation(program, "uTexture")
        val uMat = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val uRot = GLES20.glGetUniformLocation(program, "uRotation")
        FULL_QUAD.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, FULL_QUAD)
        GLES20.glEnableVertexAttribArray(aPos)
        FULL_QUAD.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, FULL_QUAD)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uMat, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uRot, 1, false, rotMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 2) Timestamp — compensated for orientationHint so playback shows bottom-center upright
        val bmp = renderTimestampBitmap(timestampText())
        uploadBitmap(textTexId, bmp)
        val mvp = stampMvp(bmp.width, bmp.height)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(textProgram)
        val tp = GLES20.glGetAttribLocation(textProgram, "aPosition")
        val tt = GLES20.glGetAttribLocation(textProgram, "aTexCoord")
        val tu = GLES20.glGetUniformLocation(textProgram, "uTexture")
        val tm = GLES20.glGetUniformLocation(textProgram, "uMVP")
        UNIT_QUAD.position(0)
        GLES20.glVertexAttribPointer(tp, 2, GLES20.GL_FLOAT, false, 16, UNIT_QUAD)
        GLES20.glEnableVertexAttribArray(tp)
        UNIT_QUAD.position(2)
        GLES20.glVertexAttribPointer(tt, 2, GLES20.GL_FLOAT, false, 16, UNIT_QUAD)
        GLES20.glEnableVertexAttribArray(tt)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTexId)
        GLES20.glUniform1i(tu, 0)
        GLES20.glUniformMatrix4fv(tm, 1, false, mvp, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        bmp.recycle()

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun stampMvp(bw: Int, bh: Int): FloatArray {
        val mvp = FloatArray(16)
        val proj = FloatArray(16)
        // Orthographic projection: left=0, right=width, bottom=0, top=height.
        // This allows us to position and rotate the timestamp using exact pixel coordinates,
        // completely avoiding the NDC aspect ratio distortion that caused the squishing.
        Matrix.orthoM(proj, 0, 0f, videoWidth.toFloat(), 0f, videoHeight.toFloat(), -1f, 1f)

        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)

        // Margin from the bottom matches the UI (~20% of the screen height)
        val marginPx = minOf(videoWidth, videoHeight) * 0.20f

        val scaleX = bw / 2f
        val scaleY = bh / 2f

        // Pixel coordinates placement before Player's CW rotation
        when (orientationHint) {
            90 -> {
                // To appear at the bottom, we place it at the RIGHT edge of the landscape buffer
                val cx = videoWidth.toFloat() - marginPx - bh / 2f
                val cy = videoHeight.toFloat() / 2f
                Matrix.translateM(model, 0, cx, cy, 0f)
                Matrix.rotateM(model, 0, 90f, 0f, 0f, 1f)
            }
            270 -> {
                // To appear at the bottom, we place it at the LEFT edge of the landscape buffer
                val cx = marginPx + bh / 2f
                val cy = videoHeight.toFloat() / 2f
                Matrix.translateM(model, 0, cx, cy, 0f)
                Matrix.rotateM(model, 0, -90f, 0f, 0f, 1f)
            }
            180 -> {
                // To appear at the bottom, we place it at the TOP edge
                val cx = videoWidth.toFloat() / 2f
                val cy = videoHeight.toFloat() - marginPx - bh / 2f
                Matrix.translateM(model, 0, cx, cy, 0f)
                Matrix.rotateM(model, 0, 180f, 0f, 0f, 1f)
            }
            else -> {
                // Text at BOTTOM edge
                val cx = videoWidth.toFloat() / 2f
                val cy = marginPx + bh / 2f
                Matrix.translateM(model, 0, cx, cy, 0f)
            }
        }

        Matrix.scaleM(model, 0, scaleX, scaleY, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)
        return mvp
    }

    private fun renderTimestampBitmap(text: String): Bitmap {
        val shortSide = minOf(videoWidth, videoHeight).toFloat()
        val textSizePx = (shortSide * 0.042f).coerceIn(34f, 64f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }
        val padX = textSizePx * 0.45f
        val padY = textSizePx * 0.28f
        val w = (textPaint.measureText(text) + padX * 2).toInt().coerceAtLeast(8)
        val fm = textPaint.fontMetrics
        val h = (fm.descent - fm.ascent + padY * 2).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        canvas.drawText(text, padX, padY - fm.ascent, textPaint)
        return bmp
    }

    private fun releaseInternal() {
        try {
            cameraInputSurface?.release()
        } catch (_: Exception) {
        }
        cameraInputSurface = null
        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
        } catch (_: Exception) {
        }
        surfaceTexture = null
        if (oesTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0)
            oesTexId = 0
        }
        if (textTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textTexId), 0)
            textTexId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textProgram != 0) {
            GLES20.glDeleteProgram(textProgram)
            textProgram = 0
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun uploadBitmap(texId: Int, bmp: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun create2dTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val link = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link failed: $log")
        }
        return prog
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(data)
                .also { it.position(0) }

        private val FULL_QUAD = floatBuffer(
            floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            ),
        )
        private val UNIT_QUAD = floatBuffer(
            floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f,
            ),
        )

        private const val VERTEX_OES = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            uniform mat4 uRotation;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = uRotation * aPosition;
              vTexCoord = (uSTMatrix * vec4(aTexCoord.xy, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
              gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
        private const val VERTEX_TEX = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVP;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = uMVP * aPosition;
              vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_TEX = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
              gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}

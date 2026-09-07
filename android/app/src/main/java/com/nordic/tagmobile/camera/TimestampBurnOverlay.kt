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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera frames → OpenGL → MediaRecorder, burning a timestamp into the encoded video.
 *
 * Portrait hold → portrait file (H>W), timestamp at bottom.
 * Landscape hold → landscape file (W>H), timestamp on the right.
 * Sensor frames are rotated in UV space into the encoder buffer (orientation-hint = 0).
 */
class TimestampBurnOverlay(
    private val outputSurface: Surface,
    private val videoWidth: Int,
    private val videoHeight: Int,
    /** Degrees to rotate camera content so it is upright in the saved file (0/90/180/270). */
    private val contentRotation: Int,
    private val timestampText: () -> String,
) : SurfaceTexture.OnFrameAvailableListener {

    private val outputPortrait: Boolean get() = videoHeight > videoWidth

    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    private var surfaceTexture: SurfaceTexture? = null
    /** Camera2 target surface (feed this to the capture session). */
    var cameraInputSurface: Surface? = null
        private set

    private var oesTexId = 0
    private var textTexId = 0
    private var program = 0
    private var textProgram = 0
    private val stMatrix = FloatArray(16)
    private var frameAvailable = false

    fun start() {
        if (running.getAndSet(true)) return
        val t = HandlerThread("TimestampBurnOverlay").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val ready = java.util.concurrent.CountDownLatch(1)
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
        ready.await(3, java.util.concurrent.TimeUnit.SECONDS)
        error?.let { throw it }
        if (cameraInputSurface == null) {
            running.set(false)
            throw IllegalStateException("Overlay GL init failed")
        }
    }

    fun release() {
        if (!running.getAndSet(false)) return
        val h = handler
        val t = thread
        if (h != null) {
            h.post { releaseInternal() }
            t?.quitSafely()
            try {
                t?.join(1500)
            } catch (_: InterruptedException) {
            }
        } else {
            releaseInternal()
        }
        thread = null
        handler = null
    }

    @Synchronized
    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameAvailable = true
        handler?.post { drawFrame() }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize" }

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
        val num = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, num, 0),
        ) { "eglChooseConfig" }
        val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0,
        )
        val surfAttrib = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], outputSurface, surfAttrib, 0,
        )
        check(
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext),
        ) { "eglMakeCurrent" }
    }

    private fun initGl() {
        oesTexId = createOesTexture()
        textTexId = createTexture2D()
        // Camera sensor frames are landscape; MVP maps them into portrait or landscape output
        val bufW = maxOf(videoWidth, videoHeight)
        val bufH = minOf(videoWidth, videoHeight)
        surfaceTexture = SurfaceTexture(oesTexId).also {
            it.setDefaultBufferSize(bufW, bufH)
            it.setOnFrameAvailableListener(this, handler)
        }
        cameraInputSurface = Surface(surfaceTexture)

        program = buildProgram(VERTEX_OES, FRAGMENT_OES)
        textProgram = buildProgram(VERTEX_TEX, FRAGMENT_TEX)
        Matrix.setIdentityM(stMatrix, 0)
    }

    @Synchronized
    private fun drawFrame() {
        if (!running.get()) return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (!frameAvailable) return
        frameAvailable = false

        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        GLES20.glViewport(0, 0, videoWidth, videoHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Full-screen quad; rotate sensor frames in UV space so portrait/landscape
        // buffers get upright pixels with no stretch (swapped size ↔ 90° is 1:1).
        val posMvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val texM = cameraTexMatrix()
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uTex = GLES20.glGetUniformLocation(program, "uTexture")
        val uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        val uMat = GLES20.glGetUniformLocation(program, "uSTMatrix")
        FULL_QUAD.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, FULL_QUAD)
        GLES20.glEnableVertexAttribArray(aPos)
        FULL_QUAD.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, FULL_QUAD)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, posMvp, 0)
        GLES20.glUniformMatrix4fv(uMat, 1, false, texM, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Portrait → bottom; landscape → right
        val label = timestampText()
        val bmp = renderTimestampBitmap(label)
        uploadBitmap(textTexId, bmp)
        val textMvp = timestampMvp(bmp.width, bmp.height)
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
        GLES20.glUniformMatrix4fv(tm, 1, false, textMvp, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        bmp.recycle()

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Degrees to rotate sensor (landscape) frames into the output buffer.
     * Portrait file must always bake ~90/270 — otherwise content stays landscape inside a tall file.
     */
    private fun bakeDegrees(): Int {
        val hint = ((contentRotation % 360) + 360) % 360
        return if (outputPortrait) {
            when (hint) {
                0, 180 -> 90
                else -> hint
            }
        } else {
            when (hint) {
                90, 270 -> 0
                else -> hint
            }
        }
    }

    /**
     * SurfaceTexture matrix + rotate about UV center.
     * For portrait output (H>W) with landscape sensor buffer, 90° UV rotate maps 1:1
     * onto the swapped encoder size — upright, no shrink.
     */
    private fun cameraTexMatrix(): FloatArray {
        val rot = bakeDegrees()
        val rotM = FloatArray(16)
        Matrix.setIdentityM(rotM, 0)
        if (rot != 0) {
            Matrix.translateM(rotM, 0, 0.5f, 0.5f, 0f)
            // Clockwise bake (matches Camera/MediaRecorder orientation degrees)
            Matrix.rotateM(rotM, 0, -rot.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(rotM, 0, -0.5f, -0.5f, 0f)
        }
        val out = FloatArray(16)
        // Apply camera ST first, then rotate the sampled image
        Matrix.multiplyMM(out, 0, rotM, 0, stMatrix, 0)
        return out
    }

    /** Portrait → bottom-center; landscape → right-center. */
    private fun timestampMvp(bw: Int, bh: Int): FloatArray {
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        val scaleX = (bw.toFloat() / videoWidth.coerceAtLeast(1)) * 2f
        val scaleY = (bh.toFloat() / videoHeight.coerceAtLeast(1)) * 2f
        val margin = 0.20f
        if (outputPortrait) {
            Matrix.translateM(mvp, 0, 0f, -1f + margin + scaleY / 2f, 0f)
        } else {
            Matrix.translateM(mvp, 0, 1f - margin - scaleX / 2f, 0f, 0f)
        }
        Matrix.scaleM(mvp, 0, scaleX / 2f, scaleY / 2f, 1f)
        return mvp
    }

    private fun renderTimestampBitmap(text: String): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }
        val padX = 20f
        val padY = 12f
        val w = (textPaint.measureText(text) + padX * 2).toInt().coerceAtLeast(8)
        val fm = textPaint.fontMetrics
        val h = (fm.descent - fm.ascent + padY * 2).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Match UI: #8C000000 background
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 6f, 6f, bg)
        val y = padY - fm.ascent
        canvas.drawText(text, padX, y, textPaint)
        return bmp
    }

    private fun releaseInternal() {
        try {
            cameraInputSurface?.release()
        } catch (_: Exception) {
        }
        cameraInputSurface = null
        try {
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

    companion object {
        private fun createOesTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
            return ids[0]
        }

        private fun createTexture2D(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        private fun uploadBitmap(texId: Int, bmp: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val v = loadShader(GLES20.GL_VERTEX_SHADER, vs)
            val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            return p
        }

        private fun loadShader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            return s
        }

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
            uniform mat4 uMVP;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = uMVP * aPosition;
              vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
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

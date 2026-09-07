package com.nordic.tagmobile.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera → SurfaceTexture → GLES composite (camera + timestamp) → MediaRecorder Surface.
 * Preview TextureView stays separate (UI overlay still shows live timestamp).
 */
class TimestampOverlayPipe(
    private val encoderSurface: Surface,
    private val videoWidth: Int,
    private val videoHeight: Int,
) : SurfaceTexture.OnFrameAvailableListener {

    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId = 0
    private var textTextureId = 0
    private var program = 0
    private var cameraSt: SurfaceTexture? = null
    private var cameraInputSurface: Surface? = null

    private var posHandle = 0
    private var texHandle = 0
    private var mvpHandle = 0
    private var stMatrixHandle = 0
    private var samplerHandle = 0

    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val frameLatch = Object()
    private var frameAvailable = false

    private val timeFmt = SimpleDateFormat("dd-MM-yyyy HH:mm:ss:SSS", Locale.US)
    private var lastText = ""
    private var textBmp: Bitmap? = null

    val inputSurface: Surface
        get() = cameraInputSurface
            ?: throw IllegalStateException("Pipe not started")

    fun start() {
        if (running.getAndSet(true)) return
        val t = HandlerThread("TimestampOverlayPipe").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        val done = CountDownLatch(1)
        var error: Exception? = null
        handler!!.post {
            try {
                initGl()
            } catch (e: Exception) {
                error = e
            } finally {
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        error?.let { throw it }
        scheduleDraw()
    }

    fun release() {
        if (!running.getAndSet(false)) return
        val done = CountDownLatch(1)
        handler?.post {
            try {
                releaseGl()
            } finally {
                done.countDown()
            }
        }
        done.await(2, TimeUnit.SECONDS)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(frameLatch) {
            frameAvailable = true
            frameLatch.notifyAll()
        }
    }

    private fun scheduleDraw() {
        handler?.post(object : Runnable {
            override fun run() {
                if (!running.get()) return
                synchronized(frameLatch) {
                    if (!frameAvailable) {
                        try {
                            frameLatch.wait(50)
                        } catch (_: InterruptedException) {
                        }
                    }
                    frameAvailable = false
                }
                try {
                    drawFrame()
                } catch (_: Exception) {
                }
                if (running.get()) handler?.post(this)
            }
        })
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, num, 0)
        val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0)
        val surfAttrib = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfAttrib, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        oesTextureId = createOesTexture()
        cameraSt = SurfaceTexture(oesTextureId).also {
            it.setDefaultBufferSize(videoWidth, videoHeight)
            it.setOnFrameAvailableListener(this)
        }
        cameraInputSurface = Surface(cameraSt)

        program = buildProgram(VERT, FRAG_OES)
        posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")

        textTextureId = createTexture2D()
        Matrix.setIdentityM(mvpMatrix, 0)
        GLES20.glViewport(0, 0, videoWidth, videoHeight)
    }

    private fun releaseGl() {
        cameraInputSurface?.release()
        cameraInputSurface = null
        cameraSt?.setOnFrameAvailableListener(null)
        cameraSt?.release()
        cameraSt = null
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (textTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textTextureId), 0)
            textTextureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        textBmp?.recycle()
        textBmp = null
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun drawFrame() {
        val st = cameraSt ?: return
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Camera full-frame
        drawOesQuad(identity)

        // Timestamp overlay near bottom-center (matches on-screen style)
        updateTextTexture(timeFmt.format(Date()))
        drawTextQuad()

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawOesQuad(mvp: FloatArray) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
        enableAttribs(FULL_QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        disableAttribs()
    }

    private fun drawTextQuad() {
        // Switch temporarily to a 2D sampler program for RGBA text
        // Reuse OES program won't sample 2D correctly — use simple 2D program
        if (textProgram == 0) {
            textProgram = buildProgram(VERT, FRAG_2D)
            textPos = GLES20.glGetAttribLocation(textProgram, "aPosition")
            textTex = GLES20.glGetAttribLocation(textProgram, "aTexCoord")
            textMvp = GLES20.glGetUniformLocation(textProgram, "uMVPMatrix")
            textSt = GLES20.glGetUniformLocation(textProgram, "uSTMatrix")
            textSamp = GLES20.glGetUniformLocation(textProgram, "sTexture")
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(textProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId)
        GLES20.glUniform1i(textSamp, 0)
        // Place banner in lower third, ~60% width
        val scaleX = 0.72f
        val scaleY = 0.09f
        val translateY = -0.62f
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        Matrix.translateM(mvp, 0, 0f, translateY, 0f)
        Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f)
        GLES20.glUniformMatrix4fv(textMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(textSt, 1, false, identity, 0)
        enableAttribs(FULL_QUAD, textPos, textTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        disableAttribs(textPos, textTex)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
    }

    private var textProgram = 0
    private var textPos = 0
    private var textTex = 0
    private var textMvp = 0
    private var textSt = 0
    private var textSamp = 0

    private fun updateTextTexture(text: String) {
        if (text == lastText && textBmp != null) return
        lastText = text
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        val padX = 28
        val padY = 18
        val w = (paint.measureText(text) + padX * 2).toInt().coerceAtLeast(64)
        val h = (paint.textSize + padY * 2).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0x8C000000.toInt())
        val y = h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, w / 2f, y, paint)
        textBmp?.recycle()
        textBmp = bmp
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    private fun enableAttribs(
        quad: FloatBuffer,
        pos: Int = posHandle,
        tex: Int = texHandle,
    ) {
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tex)
    }

    private fun disableAttribs(pos: Int = posHandle, tex: Int = texHandle) {
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
    }

    private fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun createTexture2D(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun buildProgram(vert: String, frag: String): Int {
        val v = loadShader(GLES20.GL_VERTEX_SHADER, vert)
        val f = loadShader(GLES20.GL_FRAGMENT_SHADER, frag)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private val FULL_QUAD: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ),
            )
            .also { it.position(0) }

        private const val VERT = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = uMVPMatrix * aPosition;
              vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAG_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
              gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val FRAG_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
              gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}

package com.zyyme.workdayalarmclock.camera

import android.graphics.Bitmap
import android.graphics.Canvas
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

/**
 * 录像画面水印：摄像头画面经 SurfaceTexture 进 GL，叠加"日期时间 + 移动/静止"标签后
 * 送入编码器输入 Surface。独立 GL 线程；任何初始化失败都由调用方回退到无水印直连接。
 */
internal class OverlayRenderer(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    /** 录像旋转角度（0/90/180/270）：文字按显示方向叠加以保证回放时数字正向。 */
    private val rotationDegrees: Int,
    private val isMoving: () -> Boolean
) {
    private val glThread = HandlerThread("overlay-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val initLatch = CountDownLatch(1)

    @Volatile private var initOk = false
    @Volatile private var released = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var cameraProgram = 0
    private var textProgram = 0
    private var oesTex = 0
    private var textTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    private val texMatrix = FloatArray(16)
    private val identityMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private var textBitmap = Bitmap.createBitmap(TEXT_W, TEXT_H, Bitmap.Config.ARGB_8888)
    private var lastTextKey = ""
    private var textRatio = 6.4f

    init {
        glHandler.post {
            try {
                initGl()
            } catch (_: Exception) {
                initOk = false
            }
            initLatch.countDown()
        }
        try {
            initLatch.await(4, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 供摄像头采集会话输出的 Surface；初始化失败返回 null。 */
    fun cameraSurface(): Surface? = if (initOk) cameraSurface else null

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0
        ) return
        val config = configs[0] ?: return
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        cameraProgram = buildProgram(CAMERA_VERTEX_SRC, CAMERA_FRAGMENT_SRC)
        textProgram = buildProgram(TEXT_VERTEX_SRC, TEXT_FRAGMENT_SRC)

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        oesTex = ids[0]
        textTex = ids[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )

        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(width, height)
        surfaceTexture = st
        cameraSurface = Surface(st)
        st.setOnFrameAvailableListener({ listener ->
            glHandler.post { drawFrame(listener) }
        }, glHandler)

        // 文本纹理初传（全透明）
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        uploadTextTexture(textBitmap)
        initOk = true
    }

    private fun drawFrame(st: SurfaceTexture) {
        if (released || !initOk) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 摄像头画面
            GLES20.glUseProgram(cameraProgram)
            quadVertices.position(0)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
            quadTexCoords.position(0)
            GLES20.glEnableVertexAttribArray(1)
            GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix"), 1, false, texMatrix, 0
            )
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(cameraProgram, "uMvpMatrix"), 1, false, identityMatrix, 0
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "sTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            updateTextOverlay()
            drawTextOverlay()

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (_: Exception) {
        }
    }

    private fun updateTextOverlay() {
        val moving = isMoving()
        val nowKey = timeFmt.format(Date()) + (if (moving) "M" else "S")
        if (nowKey == lastTextKey) return
        lastTextKey = nowKey

        val bitmap = textBitmap
        bitmap.eraseColor(0)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.color = 0x66000000
        canvas.drawRoundRect(
            RectFHolder.rect(2f, 2f, (TEXT_W - 2).toFloat(), (TEXT_H - 2).toFloat()),
            14f, 14f, bgPaint
        )
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        timePaint.color = ColorHolder.WHITE
        timePaint.textSize = 52f
        timePaint.setShadowLayer(3f, 1f, 1f, 0xFF000000.toInt())
        canvas.drawText(timeFmt.format(Date()), 22f, 64f, timePaint)

        val statePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        statePaint.textSize = 44f
        statePaint.setShadowLayer(3f, 1f, 1f, 0xFF000000.toInt())
        if (moving) {
            statePaint.color = ColorHolder.MOTION
            canvas.drawText("● 移动", 22f + timePaint.measureText(timeFmt.format(Date())) + 26f, 62f, statePaint)
        } else {
            statePaint.color = ColorHolder.STATIC
            canvas.drawText("静止", 22f + timePaint.measureText(timeFmt.format(Date())) + 26f, 62f, statePaint)
        }
        textRatio = TEXT_W.toFloat() / TEXT_H
        uploadTextTexture(bitmap)
    }

    private fun uploadTextTexture(bitmap: Bitmap) {
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.flip()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, TEXT_W, TEXT_H, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
    }

    private fun drawTextOverlay() {
        val bufAspect = width.toFloat() / height.toFloat()
        // 显示坐标系（播放器按 rotationDegrees 旋转后的画面）里文字条的布局：
        // 文字贴显示画面的左上角，长宽按显示比例校正，再反旋转回像素缓冲坐标。
        val quadH = 0.11f
        val quadW = if (rotationDegrees % 180 == 0) {
            quadH * textRatio / bufAspect
        } else {
            quadH * textRatio * bufAspect
        }
        val left = -0.96f
        val top = 0.88f
        // 显示坐标系四角（triangle strip：左下、右下、左上、右上）
        val corners = floatArrayOf(
            left, top - quadH,
            left + quadW, top - quadH,
            left, top,
            left + quadW, top
        )
        rotateIntoBufferSpace(corners)
        val fb = ByteBuffer.allocateDirect(corners.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(corners).position(0)

        GLES20.glUseProgram(textProgram)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, fb)
        quadTexCoords.position(0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(textProgram, "uMvpMatrix"), 1, false, identityMatrix, 0
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(textProgram, "sTexture"), 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** 把显示坐标系的顶点反旋转（R(-θ)）到像素缓冲坐标。 */
    private fun rotateIntoBufferSpace(corners: FloatArray) {
        if (rotationDegrees == 0) return
        val rad = Math.toRadians(-rotationDegrees.toDouble())
        val c = Math.cos(rad).toFloat()
        val s = Math.sin(rad).toFloat()
        var i = 0
        while (i < corners.size) {
            val x = corners[i]
            val y = corners[i + 1]
            corners[i] = x * c - y * s
            corners[i + 1] = x * s + y * c
            i += 2
        }
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            return shader
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertexSrc))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc))
        GLES20.glBindAttribLocation(program, 0, "aPosition")
        GLES20.glBindAttribLocation(program, 1, "aTexCoord")
        GLES20.glLinkProgram(program)
        return program
    }

    fun release() {
        if (released) return
        released = true
        glHandler.post {
            try {
                surfaceTexture?.release()
            } catch (_: Exception) {
            }
            try {
                cameraSurface?.release()
            } catch (_: Exception) {
            }
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (_: Exception) {
            }
            textBitmap.recycle()
        }
        glThread.quitSafely()
    }

    private object RectFHolder {
        private val rect = android.graphics.RectF()
        fun rect(left: Float, top: Float, right: Float, bottom: Float): android.graphics.RectF {
            rect.set(left, top, right, bottom)
            return rect
        }
    }

    private object ColorHolder {
        val WHITE = android.graphics.Color.WHITE
        val MOTION = android.graphics.Color.rgb(255, 92, 92)
        val STATIC = android.graphics.Color.rgb(160, 168, 176)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val TEXT_W = 640
        private const val TEXT_H = 96

        private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val quadTexCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

        private const val CAMERA_VERTEX_SRC = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uTexMatrix;
uniform mat4 uMvpMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = uMvpMatrix * aPosition;
    vTexCoord = (uTexMatrix * aTexCoord).xy;
}
"""

        private const val CAMERA_FRAGMENT_SRC = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
void main() {
    gl_FragColor = texture2D(sTexture, vTexCoord);
}
"""

        private const val TEXT_VERTEX_SRC = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uMvpMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = uMvpMatrix * aPosition;
    vTexCoord = aTexCoord.xy;
}
"""

        private const val TEXT_FRAGMENT_SRC = """
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D sTexture;
void main() {
    gl_FragColor = texture2D(sTexture, vTexCoord);
}
"""
    }
}

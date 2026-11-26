package com.deltaforce.assistant.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.deltaforce.assistant.game.UE4Parser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class ESPRenderer(private val context: Context) {
    companion object {
        private const val TAG = "ESPRenderer"
    }

    // EGL 相关
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    // OpenGL 程序
    private var lineProgram = -1
    private var boxProgram = -1
    private var textProgram = -1
    private var circleProgram = -1

    // Uniform 位置
    private var uMVPMatrix = -1
    private var uColor = -1
    private var uSize = -1

    // 渲染数据
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private var screenWidth = 1920
    private var screenHeight = 1080

    // 顶点缓冲区
    private var lineVertexBuffer: FloatBuffer? = null
    private var boxVertexBuffer: FloatBuffer? = null

    // 渲染队列
    private val renderQueue = mutableListOf<RenderCommand>()

    fun initialize(surface: Surface, width: Int, height: Int) {
        this.surface = surface
        this.screenWidth = width
        this.screenHeight = height
        
        initEGL()
        initShaders()
        initBuffers()
        initMatrices()
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig!!, surface, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun initShaders() {
        lineProgram = createProgram(VERTEX_SHADER_LINE, FRAGMENT_SHADER_LINE)
        boxProgram = createProgram(VERTEX_SHADER_BOX, FRAGMENT_SHADER_BOX)
        circleProgram = createProgram(VERTEX_SHADER_CIRCLE, FRAGMENT_SHADER_CIRCLE)

        uMVPMatrix = GLES30.glGetUniformLocation(lineProgram, "uMVPMatrix")
        uColor = GLES30.glGetUniformLocation(lineProgram, "uColor")
        uSize = GLES30.glGetUniformLocation(lineProgram, "uSize")
    }

    private fun initBuffers() {
        // 线条顶点缓冲区 (动态)
        lineVertexBuffer = ByteBuffer.allocateDirect(10000 * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        
        // 方框顶点 (单位正方形)
        val boxVertices = floatArrayOf(
            -0.5f, -0.5f, 0f,
             0.5f, -0.5f, 0f,
             0.5f,  0.5f, 0f,
            -0.5f,  0.5f, 0f
        )
        boxVertexBuffer = ByteBuffer.allocateDirect(boxVertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(boxVertices); position(0) }
    }

    private fun initMatrices() {
        // 正交投影矩阵 (屏幕坐标)
        Matrix.orthoM(projMatrix, 0, 0f, screenWidth.toFloat(), screenHeight.toFloat(), 0f, -1f, 1f)
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)
    }

    fun updateMatrices(viewMatrix: FloatArray, projMatrix: FloatArray, width: Int, height: Int) {
        this.viewMatrix = viewMatrix.clone()
        this.projMatrix = projMatrix.clone()
        this.screenWidth = width
        this.screenHeight = height
        
        Matrix.multiplyMM(mvpMatrix, 0, this.projMatrix, 0, this.viewMatrix, 0)
        
        // 重新初始化正交投影用于 2D 绘制
        Matrix.orthoM(this.projMatrix, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, this.projMatrix, 0, this.viewMatrix, 0)
    }

    fun beginFrame() {
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        
        renderQueue.clear()
    }

    fun endFrame() {
        executeRenderQueue()
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun drawLine(start: UE4Parser.Vector2, end: UE4Parser.Vector2, color: Int, width: Float) {
        renderQueue.add(RenderCommand.Line(start, end, color, width))
    }

    fun drawBox(center: UE4Parser.Vector2, size: UE4Parser.Vector2, color: Int, rotation: Float) {
        renderQueue.add(RenderCommand.Box(center, size, color, rotation))
    }

    fun drawCircle(center: UE4Parser.Vector2, radius: Float, color: Int, filled: Boolean) {
        renderQueue.add(RenderCommand.Circle(center, radius, color, filled))
    }

    fun drawText(text: String, position: UE4Parser.Vector2, color: Int, size: Float) {
        renderQueue.add(RenderCommand.Text(text, position, color, size))
    }

    fun drawHealthBar(position: UE4Parser.Vector2, size: UE4Parser.Vector2, percent: Float, color: Int) {
        renderQueue.add(RenderCommand.HealthBar(position, size, percent, color))
    }

    private fun executeRenderQueue() {
        for (cmd in renderQueue) {
            when (cmd) {
                is RenderCommand.Line -> drawLineImpl(cmd)
                is RenderCommand.Box -> drawBoxImpl(cmd)
                is RenderCommand.Circle -> drawCircleImpl(cmd)
                is RenderCommand.Text -> drawTextImpl(cmd)
                is RenderCommand.HealthBar -> drawHealthBarImpl(cmd)
            }
        }
    }

    private fun drawLineImpl(cmd: RenderCommand.Line) {
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0)
        
        val color = intToColor(cmd.color)
        GLES30.glUniform4f(uColor, color[0], color[1], color[2], color[3])
        GLES30.glUniform1f(uSize, cmd.width)

        val vertices = floatArrayOf(
            cmd.start.x, cmd.start.y, 0f,
            cmd.end.x, cmd.end.y, 0f
        )
        
        lineVertexBuffer!!.position(0)
        lineVertexBuffer!!.put(vertices)
        lineVertexBuffer!!.position(0)

        val posLoc = GLES30.glGetAttribLocation(lineProgram, "aPosition")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 12, lineVertexBuffer!!)
        
        GLES30.glLineWidth(cmd.width)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 2)
        GLES30.glDisableVertexAttribArray(posLoc)
    }

    private fun drawBoxImpl(cmd: RenderCommand.Box) {
        GLES30.glUseProgram(boxProgram)
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0)
        
        val color = intToColor(cmd.color)
        GLES30.glUniform4f(uColor, color[0], color[1], color[2], color[3])

        // 计算变换矩阵
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, cmd.center.x, cmd.center.y, 0f)
        Matrix.rotateM(modelMatrix, 0, cmd.rotation, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, cmd.size.x, cmd.size.y, 1f)
        
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, mvpMatrix, 0, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(boxProgram, "uMVPMatrix"), 1, false, mvp, 0)

        val posLoc = GLES30.glGetAttribLocation(boxProgram, "aPosition")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 12, boxVertexBuffer!!)
        
        GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, 4)
        GLES30.glDisableVertexAttribArray(posLoc)
    }

    private fun drawCircleImpl(cmd: RenderCommand.Circle) {
        GLES30.glUseProgram(circleProgram)
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0)
        
        val color = intToColor(cmd.color)
        GLES30.glUniform4f(uColor, color[0], color[1], color[2], color[3])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(circleProgram, "uRadius"), cmd.radius)

        // 生成圆顶点
        val segments = 32
        val vertices = FloatArray((segments + 2) * 3)
        vertices[0] = cmd.center.x
        vertices[1] = cmd.center.y
        vertices[2] = 0f
        
        for (i in 0..segments) {
            val angle = 2f * Math.PI * i / segments
            val idx = (i + 1) * 3
            vertices[idx] = cmd.center.x + cmd.radius * cos(angle).toFloat()
            vertices[idx + 1] = cmd.center.y + cmd.radius * sin(angle).toFloat()
            vertices[idx + 2] = 0f
        }

        val buffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
        
        val posLoc = GLES30.glGetAttribLocation(circleProgram, "aPosition")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 12, buffer)
        
        val mode = if (cmd.filled) GLES30.GL_TRIANGLE_FAN else GLES30.GL_LINE_LOOP
        GLES30.glDrawArrays(mode, 0, segments + 2)
        GLES30.glDisableVertexAttribArray(posLoc)
    }

    private fun drawTextImpl(cmd: RenderCommand.Text) {
        // 文字渲染需要字体纹理，简化版跳过
    }

    private fun drawHealthBarImpl(cmd: RenderCommand.HealthBar) {
        // 绘制背景
        drawBoxImpl(RenderCommand.Box(
            UE4Parser.Vector2(cmd.position.x + cmd.size.x / 2, cmd.position.y + cmd.size.y / 2),
            cmd.size,
            Color.BLACK,
            0f
        ))
        
        // 绘制前景
        val fillSize = UE4Parser.Vector2(cmd.size.x * cmd.percent, cmd.size.y)
        drawBoxImpl(RenderCommand.Box(
            UE4Parser.Vector2(cmd.position.x + fillSize.x / 2, cmd.position.y + cmd.size.y / 2),
            fillSize,
            cmd.color,
            0f
        ))
    }

    private fun intToColor(color: Int): FloatArray {
        return floatArrayOf(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            ((color shr 24) and 0xFF) / 255f
        )
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(program)}")
        }
        
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    fun release() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }

    sealed class RenderCommand {
        data class Line(
            val start: UE4Parser.Vector2,
            val end: UE4Parser.Vector2,
            val color: Int,
            val width: Float
        ) : RenderCommand()
        
        data class Box(
            val center: UE4Parser.Vector2,
            val size: UE4Parser.Vector2,
            val color: Int,
            val rotation: Float
        ) : RenderCommand()
        
        data class Circle(
            val center: UE4Parser.Vector2,
            val radius: Float,
            val color: Int,
            val filled: Boolean
        ) : RenderCommand()
        
        data class Text(
            val text: String,
            val position: UE4Parser.Vector2,
            val color: Int,
            val size: Float
        ) : RenderCommand()
        
        data class HealthBar(
            val position: UE4Parser.Vector2,
            val size: UE4Parser.Vector2,
            val percent: Float,
            val color: Int
        ) : RenderCommand()
    }

    companion object {
        const val VERTEX_SHADER_LINE = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            uniform mat4 uMVPMatrix;
            uniform float uSize;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                gl_PointSize = uSize;
            }
        """.trimIndent()

        const val FRAGMENT_SHADER_LINE = """
            #version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = uColor;
            }
        """.trimIndent()

        const val VERTEX_SHADER_BOX = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            uniform mat4 uMVPMatrix;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        const val FRAGMENT_SHADER_BOX = """
            #version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = uColor;
            }
        """.trimIndent()

        const val VERTEX_SHADER_CIRCLE = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            uniform mat4 uMVPMatrix;
            uniform float uRadius;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        const val FRAGMENT_SHADER_CIRCLE = """
            #version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = uColor;
            }
        """.trimIndent()
    }
}
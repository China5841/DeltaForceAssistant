package com.deltaforce.assistant.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class OverlayView(context: Context, attrs: AttributeSet) : GLSurfaceView(context, attrs) {
    private val renderer: ESPRenderer

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        getHolder().setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        
        renderer = ESPRenderer(context)
        setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
                renderer.initialize(holder.surface, width, height)
            }
            
            override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
                renderer.updateMatrices(
                    FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f },
                    FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f },
                    width, height
                )
            }
            
            override fun onDrawFrame(gl: GL10) {
                // 渲染循环由外部驱动
            }
        })
        
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun getRenderer(): ESPRenderer = renderer

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 触摸穿透
        return false
    }
}
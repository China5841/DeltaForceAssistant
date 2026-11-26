package com.deltaforce.assistant.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.deltaforce.assistant.features.Aimbot
import com.deltaforce.assistant.features.ESP
import com.deltaforce.assistant.features.Radar
import com.deltaforce.assistant.render.ESPRenderer

class GameLoop(
    private val gameContext: GameContext,
    private val espRenderer: ESPRenderer,
    private val esp: ESP,
    private val aimbot: Aimbot,
    private val radar: Radar
) {
    companion object {
        private const val TAG = "GameLoop"
        private const val TARGET_FPS = 144
        private const val FRAME_TIME_NS = 1_000_000_000L / TARGET_FPS
    }

    private var isRunning = false
    private var loopThread: Thread? = null
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fps = 0

    // 调试绘制
    private val debugPaint = Paint().apply {
        color = Color.WHITE
        textSize = 14f
        isAntiAlias = true
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        
        loopThread = Thread {
            gameLoop()
        }.apply {
            name = "GameLoopThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "Game loop started")
    }

    fun stop() {
        isRunning = false
        loopThread?.interrupt()
        loopThread?.join(1000)
        Log.i(TAG, "Game loop stopped")
    }

    private fun gameLoop() {
        while (isRunning) {
            val frameStart = System.nanoTime()
            
            try {
                // 1. 更新游戏上下文 (读取内存、解析实体)
                val updateSuccess = gameContext.update()
                
                if (updateSuccess) {
                    // 2. 更新功能模块
                    esp.render(espRenderer)  // ESP 渲染到 ESPRenderer
                    aimbot.update()          // 自瞄逻辑
                    radar.render(null)       // 雷达 (需要 Canvas，后续整合)
                }

                // 3. 更新 ESPRenderer 矩阵
                espRenderer.updateMatrices(
                    gameContext.viewMatrix,
                    gameContext.projMatrix,
                    gameContext.screenWidth,
                    gameContext.screenHeight
                )

                // 4. 渲染帧
                espRenderer.beginFrame()
                // ESP 已在 esp.render() 中加入渲染队列
                espRenderer.endFrame()

            } catch (e: Exception) {
                Log.e(TAG, "Game loop error", e)
            }

            // 5. 计算 FPS
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFrameTime >= 1000) {
                fps = frameCount
                frameCount = 0
                lastFrameTime = now
            }

            // 6. 帧率控制
            val frameTime = System.nanoTime() - frameStart
            val sleepTime = FRAME_TIME_NS - frameTime
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime / 1_000_000, (sleepTime % 1_000_000).toInt())
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    fun getFps(): Int = fps
    
    fun isAlive(): Boolean = isRunning && loopThread?.isAlive == true
}
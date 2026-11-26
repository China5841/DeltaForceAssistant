package com.deltaforce.assistant.features

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.deltaforce.assistant.game.GameContext
import com.deltaforce.assistant.game.UE4Parser

class Radar(
    private val gameContext: GameContext,
    private val config: RadarConfig
) {
    private val backgroundPaint = Paint().apply {
        color = Color.argb(180, 0x0A, 0x0E, 0x17)
        isAntiAlias = true
    }
    
    private val borderPaint = Paint().apply {
        color = Color.argb(200, 0x00, 0xFF, 0x88)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val localPaint = Paint().apply {
        color = Color.argb(255, 0x00, 0xFF, 0x88)
        isAntiAlias = true
    }
    
    private val enemyPaint = Paint().apply {
        color = Color.argb(255, 0xFF, 0x2D, 0x55)
        isAntiAlias = true
    }
    
    private val teammatePaint = Paint().apply {
        color = Color.argb(255, 0x00, 0xD4, 0xFF)
        isAntiAlias = true
    }
    
    private val vehiclePaint = Paint().apply {
        color = Color.argb(255, 0xFF, 0x6B, 0x00)
        isAntiAlias = true
    }
    
    private val lootPaint = Paint().apply {
        color = Color.argb(255, 0xA8, 0x55, 0xF7)
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 10f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private var radarX = 100f
    private var radarY = 100f
    private var isDragging = false

    fun render(canvas: Canvas) {
        if (!config.enabled) return

        val size = config.size
        val centerX = radarX + size / 2
        val centerY = radarY + size / 2

        // 绘制背景
        canvas.drawCircle(centerX, centerY, size / 2, backgroundPaint)
        canvas.drawCircle(centerX, centerY, size / 2, borderPaint)

        // 绘制距离圆环
        for (i in 1..3) {
            val radius = size / 2 * i / 4
            val circlePaint = Paint().apply {
                color = Color.argb(50, 0x00, 0xFF, 0x88)
                strokeWidth = 1f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            canvas.drawCircle(centerX, centerY, radius, circlePaint)
        }

        // 绘制十字线
        canvas.drawLine(centerX - size / 2, centerY, centerX + size / 2, centerY, borderPaint)
        canvas.drawLine(centerX, centerY - size / 2, centerX, centerY + size / 2, borderPaint)

        // 绘制本地玩家
        canvas.drawCircle(centerX, centerY, 6f, localPaint)

        // 绘制实体
        drawEntities(canvas, centerX, centerY, size / 2)

        // 绘制标签
        drawLabels(canvas, centerX, centerY, size / 2)
    }

    private fun drawEntities(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val localLoc = gameContext.getLocalLocation()
        val localRot = getLocalRotation()

        for (actor in gameContext.actors) {
            if (!shouldRender(actor)) continue

            val worldPos = actor.location
            val dx = worldPos.x - localLoc.x
            val dy = worldPos.y - localLoc.y
            val distance = sqrt(dx * dx + dy * dy).toFloat()

            if (distance > config.range) continue

            // 旋转到雷达坐标系
            val angle = atan2(dy, dx) - localRot
            val radarDist = (distance / config.range) * radius * 0.9f
            
            val x = centerX + radarDist * cos(angle).toFloat()
            val y = centerY + radarDist * sin(angle).toFloat()

            val paint = when {
                actor.teamId == gameContext.getLocalTeamId() -> teammatePaint
                actor.className.contains("Vehicle", true) -> vehiclePaint
                actor.className.contains("Loot", true) || actor.className.contains("Item", true) -> lootPaint
                else -> enemyPaint
            }

            canvas.drawCircle(x, y, 4f, paint)
        }
    }

    private fun drawLabels(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        canvas.drawText("RADAR", centerX, radarY - 10, textPaint)
        canvas.drawText("${config.range.toInt()}m", centerX, radarY + radius + 20, textPaint)
    }

    private fun shouldRender(actor: UE4Parser.ActorInfo): Boolean {
        if (actor.health <= 0) return false
        if (!config.showEnemies && actor.teamId != gameContext.getLocalTeamId() && 
            !actor.className.contains("Vehicle", true) && !actor.className.contains("Loot", true)) return false
        if (!config.showTeammates && actor.teamId == gameContext.getLocalTeamId()) return false
        if (!config.showVehicles && actor.className.contains("Vehicle", true)) return false
        if (!config.showLoot && (actor.className.contains("Loot", true) || actor.className.contains("Item", true))) return false
        return true
    }

    private fun getLocalRotation(): Float {
        if (gameContext.localPlayerController == 0L) return 0f
        val rotOffset = gameContext.offsets.APlayerController_ControlRotation
        return gameContext.memoryReader.readFloat(gameContext.pid, gameContext.localPlayerController + rotOffset) ?: 0f
    }

    fun onTouch(x: Float, y: Float, action: Int): Boolean {
        val size = config.size
        val centerX = radarX + size / 2
        val centerY = radarY + size / 2
        
        val dx = x - centerX
        val dy = y - centerY
        val dist = sqrt(dx * dx + dy * dy).toFloat()
        
        if (dist <= size / 2) {
            when (action) {
                0 -> { // DOWN
                    isDragging = true
                }
                2 -> { // MOVE
                    if (isDragging) {
                        radarX = x - size / 2
                        radarY = y - size / 2
                    }
                }
                1, 3 -> { // UP, CANCEL
                    isDragging = false
                }
            }
            return true
        }
        return false
    }
}

data class RadarConfig(
    var enabled: Boolean = true,
    var size: Float = 200f,
    var range: Float = 200f,
    var showEnemies: Boolean = true,
    var showTeammates: Boolean = true,
    var showVehicles: Boolean = true,
    var showLoot: Boolean = false,
    var rotationLocked: Boolean = false
)
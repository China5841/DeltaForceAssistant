package com.deltaforce.assistant.features

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.deltaforce.assistant.game.GameContext
import com.deltaforce.assistant.game.UE4Parser

class ESP(
    private val gameContext: GameContext,
    private val config: ESPConfig
) {
    private val bonePaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val boxPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val healthPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 14f
        isAntiAlias = true
    }

    // 骨骼连接定义 (UE4 标准骨骼索引)
    private val bonePairs = listOf(
        Pair(66, 65),  // Head -> Neck
        Pair(65, 37),  // Neck -> Chest
        Pair(37, 2),   // Chest -> Pelvis
        Pair(37, 9),   // Chest -> LShoulder
        Pair(9, 10),   // LShoulder -> LElbow
        Pair(10, 11),  // LElbow -> LWrist
        Pair(37, 38),  // Chest -> RShoulder
        Pair(38, 39),  // RShoulder -> RElbow
        Pair(39, 40),  // RElbow -> RWrist
        Pair(2, 58),   // Pelvis -> LThigh
        Pair(58, 59),  // LThigh -> LKnee
        Pair(59, 60),  // LKnee -> LAnkle
        Pair(2, 71),   // Pelvis -> RThigh
        Pair(71, 72),  // RThigh -> RKnee
        Pair(72, 73),  // RKnee -> RAnkle
    )

    fun render(canvas: Canvas) {
        if (!config.enabled) return

        val enemies = gameContext.getValidEnemies()
        
        for (actor in enemies) {
            renderActor(canvas, actor)
        }
    }

    private fun renderActor(canvas: Canvas, actor: UE4Parser.ActorInfo) {
        val screenPos = gameContext.worldToScreen(actor.location)
        if (screenPos == null) return

        val distance = actor.location.distanceTo(gameContext.getLocalLocation())
        if (distance > config.maxDistance) return

        // 获取骨骼屏幕坐标
        val bonePositions = getBoneScreenPositions(actor)
        if (bonePositions.isEmpty()) return

        // 绘制骨骼
        if (config.showSkeleton) {
            drawSkeleton(canvas, bonePositions)
        }

        // 绘制方框
        if (config.showBox) {
            drawBox(canvas, bonePositions, screenPos)
        }

        // 绘制血条
        if (config.showHealth) {
            drawHealthBar(canvas, bonePositions, actor)
        }

        // 绘制名字/距离
        if (config.showName || config.showDistance) {
            drawInfo(canvas, bonePositions, actor, distance)
        }

        // 绘制视线射线
        if (config.showViewLine) {
            drawViewLine(canvas, bonePositions)
        }
    }

    private fun getBoneScreenPositions(actor: UE4Parser.ActorInfo): Map<Int, UE4Parser.Vector2> {
        val meshPtr = gameContext.memoryReader.readLong(gameContext.pid, actor.address + gameContext.offsets.APawn_Mesh)
        if (meshPtr == null || meshPtr == 0L) return emptyMap()

        val boneArrayPtr = gameContext.memoryReader.readLong(gameContext.pid, meshPtr + gameContext.offsets.USkeletalMeshComponent_BoneArray)
        if (boneArrayPtr == null || boneArrayPtr == 0L) return emptyMap()

        val componentToWorldPtr = gameContext.memoryReader.readLong(gameContext.pid, meshPtr + gameContext.offsets.USkeletalMeshComponent_ComponentToWorld)
        if (componentToWorldPtr == null) return emptyMap()

        // 读取骨骼矩阵数组 (简化版：直接读取世界坐标)
        val boneCount = 100 // 估算
        val positions = mutableMapOf<Int, UE4Parser.Vector2>()
        
        // 这里需要完整的骨骼矩阵解析
        // 简化版：使用已知的头部、胸部、骨盆位置
        val headWorld = getBoneWorldPosition(actor.address, 66)
        val chestWorld = getBoneWorldPosition(actor.address, 37)
        val pelvisWorld = getBoneWorldPosition(actor.address, 2)
        
        headWorld?.let { positions[66] = gameContext.worldToScreen(it) ?: return@let }
        chestWorld?.let { positions[37] = gameContext.worldToScreen(it) ?: return@let }
        pelvisWorld?.let { positions[2] = gameContext.worldToScreen(it) ?: return@let }

        return positions
    }

    private fun getBoneWorldPosition(actorPtr: Long, boneIndex: Int): UE4Parser.Vector3? {
        // 完整实现需要解析骨骼层级和矩阵
        // 这里返回模拟数据
        val meshPtr = gameContext.memoryReader.readLong(gameContext.pid, actorPtr + gameContext.offsets.APawn_Mesh)
        return if (meshPtr != null && meshPtr != 0L) {
            // 读取骨骼矩阵
            null // 简化版
        } else null
    }

    private fun drawSkeleton(canvas: Canvas, bones: Map<Int, UE4Parser.Vector2>) {
        for ((a, b) in bonePairs) {
            val posA = bones[a]
            val posB = bones[b]
            if (posA != null && posB != null) {
                canvas.drawLine(posA.x, posA.y, posB.x, posB.y, bonePaint)
            }
        }
    }

    private fun drawBox(canvas: Canvas, bones: Map<Int, UE4Parser.Vector2>, center: UE4Parser.Vector2) {
        // 计算包围盒
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (pos in bones.values) {
            minX = minOf(minX, pos.x)
            minY = minOf(minY, pos.y)
            maxX = maxOf(maxX, pos.x)
            maxY = maxOf(maxY, pos.y)
        }

        if (minX < maxX && minY < maxY) {
            canvas.drawRect(minX, minY, maxX, maxY, boxPaint)
        }
    }

    private fun drawHealthBar(canvas: Canvas, bones: Map<Int, UE4Parser.Vector2>, actor: UE4Parser.ActorInfo) {
        val pelvis = bones[2] ?: return
        val head = bones[66] ?: return
        
        val barHeight = (head.y - pelvis.y).absoluteValue
        val barWidth = 4f
        val barX = pelvis.x - barWidth - 4
        val barY = head.y
        
        val healthPercent = actor.health / actor.maxHealth
        val currentHeight = barHeight * healthPercent
        
        // 背景
        healthPaint.color = Color.BLACK
        canvas.drawRect(barX, barY, barX + barWidth, barY + barHeight, healthPaint)
        
        // 前景
        healthPaint.color = if (healthPercent > 0.5f) Color.GREEN else if (healthPercent > 0.25f) Color.YELLOW else Color.RED
        canvas.drawRect(barX, barY + barHeight - currentHeight, barX + barWidth, barY + barHeight, healthPaint)
    }

    private fun drawInfo(canvas: Canvas, bones: Map<Int, UE4Parser.Vector2>, actor: UE4Parser.ActorInfo, distance: Float) {
        val head = bones[66] ?: return
        val y = head.y - 20f
        
        var text = ""
        if (config.showName) text += actor.name
        if (config.showDistance) text += " [${distance.toInt()}m]"
        
        canvas.drawText(text, head.x, y, textPaint)
    }

    private fun drawViewLine(canvas: Canvas, bones: Map<Int, UE4Parser.Vector2>) {
        val head = bones[66] ?: return
        val pelvis = bones[2] ?: return
        
        // 计算视线方向 (简化)
        canvas.drawLine(pelvis.x, pelvis.y, head.x, head.y, bonePaint)
    }
}

data class ESPConfig(
    var enabled: Boolean = true,
    var showSkeleton: Boolean = true,
    var showBox: Boolean = true,
    var showHealth: Boolean = true,
    var showName: Boolean = true,
    var showDistance: Boolean = true,
    var showViewLine: Boolean = false,
    var maxDistance: Float = 300f,
    var skeletonColor: Int = Color.GREEN,
    var boxColor: Int = Color.RED,
    var healthColor: Int = Color.GREEN,
    var textColor: Int = Color.WHITE
)
package com.deltaforce.assistant.features

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.deltaforce.assistant.game.GameContext
import com.deltaforce.assistant.game.UE4Parser
import com.deltaforce.assistant.input.InputInjector
import com.deltaforce.assistant.memory.MemoryReader
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Aimbot(
    private val gameContext: GameContext,
    private val config: AimbotConfig
) {
    companion object {
        private const val TAG = "Aimbot"
        private const val BONE_MATRIX_SIZE = 64 // FMatrix = 4x4 float = 64 bytes
    }

    private var lastTarget: UE4Parser.ActorInfo? = null
    private var lastTargetTime = 0L
    private var lastScreenPos: UE4Parser.Vector2? = null
    private var accumulatedDeltaX = 0f
    private var accumulatedDeltaY = 0f

    // 调试可视化
    private val debugPaint = Paint().apply {
        color = Color.argb(200, 0xFF, 0x2D, 0x55)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val fovPaint = Paint().apply {
        color = Color.argb(100, 0x00, 0xFF, 0x88)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val targetLinePaint = Paint().apply {
        color = Color.argb(255, 0x00, 0xFF, 0x88)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    fun update() {
        if (!config.enabled) return

        val enemies = gameContext.getValidEnemies()
        if (enemies.isEmpty()) {
            lastTarget = null
            lastScreenPos = null
            accumulatedDeltaX = 0f
            accumulatedDeltaY = 0f
            return
        }

        // 选择最佳目标
        val target = selectTarget(enemies)
        if (target != null) {
            aimAt(target)
        }
    }

    /** 绘制调试信息 (FOV 圆、准星、目标连线) */
    fun drawDebug(canvas: Canvas) {
        if (!config.enabled || !config.showDebug) return

        val centerX = gameContext.screenWidth / 2f
        val centerY = gameContext.screenHeight / 2f
        val fovRadius = getFovRadius()

        // 绘制 FOV 圆
        canvas.drawCircle(centerX, centerY, fovRadius, fovPaint)

        // 绘制准星
        val crosshairSize = 12f
        canvas.drawLine(centerX - crosshairSize, centerY, centerX + crosshairSize, centerY, debugPaint)
        canvas.drawLine(centerX, centerY - crosshairSize, centerX, centerY + crosshairSize, debugPaint)

        // 绘制当前目标连线
        if (lastTarget != null && lastScreenPos != null) {
            canvas.drawLine(centerX, centerY, lastScreenPos!!.x, lastScreenPos!!.y, targetLinePaint)
            
            // 目标信息
            val infoPaint = Paint().apply {
                color = Color.WHITE
                textSize = 14f
                isAntiAlias = true
            }
            canvas.drawText(
                "Target: ${lastTarget!!.name} [${lastTarget!!.health.toInt()}/${lastTarget!!.maxHealth.toInt()}]",
                lastScreenPos!!.x + 10, lastScreenPos!!.y - 10, infoPaint
            )
            canvas.drawText(
                "Dist: ${lastTarget!!.location.distanceTo(gameContext.getLocalLocation()).toInt()}m",
                lastScreenPos!!.x + 10, lastScreenPos!!.y + 10, infoPaint
            )
        }

        // 绘制 FOV 设置信息
        val debugInfoPaint = Paint().apply {
            color = Color.argb(200, 0x00, 0xFF, 0x88)
            textSize = 12f
            isAntiAlias = true
        }
        canvas.drawText("FOV: ${config.fov}° (Radius: ${fovRadius.toInt()}px)", 20f, 40f, debugInfoPaint)
        canvas.drawText("Smooth: ${config.smooth}", 20f, 60f, debugInfoPaint)
        canvas.drawText("Target Bone: ${config.targetBone.name}", 20f, 80f, debugInfoPaint)
        canvas.drawText("Selection: ${config.targetSelection.name}", 20f, 100f, debugInfoPaint)
    }

    private fun selectTarget(enemies: List<UE4Parser.ActorInfo>): UE4Parser.ActorInfo? {
        val fovRadius = getFovRadius()
        val centerX = gameContext.screenWidth / 2f
        val centerY = gameContext.screenHeight / 2f

        return when (config.targetSelection) {
            TargetSelection.CLOSEST -> enemies
                .filter { isInFov(it, centerX, centerY, fovRadius) }
                .minByOrNull { it.location.distanceTo(gameContext.getLocalLocation()) }
            
            TargetSelection.CROSSHAIR -> enemies
                .filter { isInFov(it, centerX, centerY, fovRadius) }
                .minByOrNull { getFovDistance(it) }
            
            TargetSelection.THREAT -> enemies
                .filter { isInFov(it, centerX, centerY, fovRadius) }
                .maxByOrNull { getThreatScore(it) }
            
            TargetSelection.LOWEST_HP -> enemies
                .filter { isInFov(it, centerX, centerY, fovRadius) }
                .minByOrNull { it.health }
            
            TargetSelection.LOCK_ON -> {
                // 锁定模式：保持当前目标直到死亡或超出 FOV
                if (lastTarget != null && enemies.contains(lastTarget) && isInFov(lastTarget!!, centerX, centerY, fovRadius)) {
                    lastTarget
                } else {
                    enemies
                        .filter { isInFov(it, centerX, centerY, fovRadius) }
                        .minByOrNull { getFovDistance(it) }
                }
            }
            
            else -> enemies.firstOrNull()
        }
    }

    private fun isInFov(actor: UE4Parser.ActorInfo, centerX: Float, centerY: Float, fovRadius: Float): Boolean {
        val screenPos = gameContext.worldToScreen(actor.location)
        if (screenPos == null) return false
        val dx = screenPos.x - centerX
        val dy = screenPos.y - centerY
        return (dx * dx + dy * dy) <= (fovRadius * fovRadius)
    }

    private fun getFovRadius(): Float {
        return config.fov * min(gameContext.screenWidth, gameContext.screenHeight) / 180f * config.fovScale
    }

    private fun getFovDistance(actor: UE4Parser.ActorInfo): Float {
        val screenPos = gameContext.worldToScreen(actor.location)
        if (screenPos == null) return Float.MAX_VALUE
        
        val centerX = gameContext.screenWidth / 2f
        val centerY = gameContext.screenHeight / 2f
        val dx = screenPos.x - centerX
        val dy = screenPos.y - centerY
        return sqrt(dx * dx + dy * dy)
    }

    private fun getThreatScore(actor: UE4Parser.ActorInfo): Float {
        val distance = actor.location.distanceTo(gameContext.getLocalLocation())
        val healthFactor = actor.health / actor.maxHealth
        val screenDist = getFovDistance(actor)
        val fovRadius = getFovRadius()
        val fovFactor = 1f - (screenDist / fovRadius).coerceIn(0f, 1f)
        return (1000f / (distance + 1)) * (1f - healthFactor * 0.3f) * (1f + fovFactor)
    }

    private fun aimAt(target: UE4Parser.ActorInfo) {
        val targetPos = getTargetBonePosition(target)
        if (targetPos == null) return

        val screenTarget = gameContext.worldToScreen(targetPos)
        if (screenTarget == null) return

        val centerX = gameContext.screenWidth / 2f
        val centerY = gameContext.screenHeight / 2f
        
        var deltaX = screenTarget.x - centerX
        var deltaY = screenTarget.y - centerY

        // FOV 检查
        val fovRadius = getFovRadius()
        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
        if (distance > fovRadius) {
            lastTarget = null
            return
        }

        // 目标预判
        if (config.prediction) {
            val predicted = predictTargetPosition(target, targetPos)
            val screenPredicted = gameContext.worldToScreen(predicted)
            if (screenPredicted != null) {
                deltaX = screenPredicted.x - centerX
                deltaY = screenPredicted.y - centerY
            }
        }

        // 平滑处理 (指数移动平均)
        val smoothFactor = config.smooth.coerceIn(0.01f, 1f)
        accumulatedDeltaX = accumulatedDeltaX * (1f - smoothFactor) + deltaX * smoothFactor
        accumulatedDeltaY = accumulatedDeltaY * (1f - smoothFactor) + deltaY * smoothFactor

        // 人性化曲线 + 抖动
        val humanized = applyHumanizedCurve(accumulatedDeltaX, accumulatedDeltaY)
        
        // 执行移动
        injectInput(humanized.first, humanized.second)

        // 更新状态
        lastTarget = target
        lastScreenPos = screenTarget
        lastTargetTime = System.currentTimeMillis()

        // 自动开火
        if (config.autoFire && shouldFire(target)) {
            injectClick()
        }

        // 压枪控制
        if (config.recoilControl) {
            applyRecoilControl()
        }
    }

    /** 目标位置预判 (基于速度) */
    private fun predictTargetPosition(target: UE4Parser.ActorInfo, currentPos: UE4Parser.Vector3): UE4Parser.Vector3 {
        // 读取目标速度 (需要从 Actor 读取 Velocity)
        val velocity = getTargetVelocity(target)
        if (velocity == null) return currentPos

        // 计算子弹飞行时间
        val localPos = gameContext.getLocalLocation()
        val distance = currentPos.distanceTo(localPos)
        val bulletSpeed = getCurrentWeaponBulletSpeed()
        val travelTime = distance / bulletSpeed

        // 预判位置
        return UE4Parser.Vector3(
            currentPos.x + velocity.x * travelTime,
            currentPos.y + velocity.y * travelTime,
            currentPos.z + velocity.z * travelTime
        )
    }

    private fun getTargetVelocity(target: UE4Parser.ActorInfo): UE4Parser.Vector3? {
        // ACharacter::Velocity 偏移 (需要具体偏移量)
        val velocityOffset = gameContext.offsets.ACharacter_Velocity
        if (velocityOffset == 0L) return null
        
        return gameContext.memoryReader.readBytes(gameContext.pid, target.address + velocityOffset, 12)?.let { data ->
            java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).run {
                UE4Parser.Vector3(getFloat(), getFloat(), getFloat())
            }
        }
    }

    private fun getCurrentWeaponBulletSpeed(): Float {
        // 根据当前武器返回子弹速度
        // 简化版：返回常见步枪速度
        return 900f // m/s
    }

    /** 人性化移动曲线 (贝塞尔 + 抖动 + 微动) */
    private fun applyHumanizedCurve(deltaX: Float, deltaY: Float): Pair<Float, Float> {
        // 1. 贝塞尔缓动
        val t = (config.smooth * 0.5f + 0.25f).coerceIn(0.1f, 0.9f)
        val easedX = deltaX * (3f * t * t - 2f * t * t * t)
        val easedY = deltaY * (3f * t * t - 2f * t * t * t)

        // 2. 随机抖动 (高斯分布近似)
        val jitterRange = config.jitter
        val jitterX = (nextGaussian() * jitterRange).toFloat()
        val jitterY = (nextGaussian() * jitterRange).toFloat()

        // 3. 微动抖动 (模拟手部微颤)
        val microJitter = 0.3f
        val microX = (Math.random() - 0.5) * microJitter * 2
        val microY = (Math.random() - 0.5) * microJitter * 2

        return (easedX + jitterX + microX).toFloat() to (easedY + jitterY + microY).toFloat()
    }

    private fun nextGaussian(): Double {
        // Box-Muller 变换生成高斯分布
        val u1 = Math.random()
        val u2 = Math.random()
        return sqrt(-2.0 * Math.log(u1)) * cos(2.0 * Math.PI * u2)
    }

    private fun shouldFire(target: UE4Parser.ActorInfo): Boolean {
        if (!config.visibilityCheck) return true
        
        // 射线检测：从相机位置到目标骨骼
        val cameraLoc = getCameraLocation()
        val targetBone = getTargetBonePosition(target)
        if (cameraLoc == null || targetBone == null) return true
        
        return lineTrace(cameraLoc, targetBone)
    }

    private fun getCameraLocation(): UE4Parser.Vector3? {
        val cameraManager = gameContext.cameraManager
        if (cameraManager == 0L) return null
        
        val locOffset = gameContext.offsets.APlayerCameraManager_CameraLocation
        return gameContext.memoryReader.readBytes(gameContext.pid, cameraManager + locOffset, 12)?.let { data ->
            java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).run {
                UE4Parser.Vector3(getFloat(), getFloat(), getFloat())
            }
        }
    }

    private fun lineTrace(start: UE4Parser.Vector3, end: UE4Parser.Vector3): Boolean {
        // 这里需要调用 UWorld::LineTraceSingleByChannel
        // 简化版：返回 true (假设可见)
        // 完整版需要通过 Frida Hook 或原生调用实现
        return true
    }

    private val inputInjector = InputInjector().apply { initialize() }

    private fun injectInput(deltaX: Float, deltaY: Float) {
        // 使用 uinput 虚拟设备注入移动
        inputInjector.move(deltaX, deltaY)
    }

    private fun injectClick() {
        inputInjector.click(0) // 左键
    }

    /** 压枪控制 - 根据武器模式反向补偿 */
    private fun applyRecoilControl() {
        val pattern = RecoilPatterns.getPattern(config.currentWeapon)
        if (pattern == null) return

        val shotsFired = getShotsFired()
        if (shotsFired >= pattern.offsets.size) return

        val offset = pattern.offsets[shotsFired]
        val compensationX = -offset.x * config.recoilStrength
        val compensationY = -offset.y * config.recoilStrength

        // 累加到移动量
        accumulatedDeltaX += compensationX
        accumulatedDeltaY += compensationY
    }

    private fun getShotsFired(): Int {
        // 读取当前武器已发射子弹数
        // AWeapon::ShotsFired 或本地计数
        return 0 // 简化版
    }

    /** 获取真实骨骼世界坐标 */
    private fun getTargetBonePosition(actor: UE4Parser.ActorInfo): UE4Parser.Vector3? {
        val meshPtr = gameContext.memoryReader.readLong(gameContext.pid, actor.address + gameContext.offsets.APawn_Mesh)
        if (meshPtr == null || meshPtr == 0L) {
            // 回退：使用角色位置 + 骨骼偏移
            return getApproximateBonePosition(actor)
        }

        // 读取骨骼矩阵数组
        val boneArrayPtr = gameContext.memoryReader.readLong(gameContext.pid, meshPtr + gameContext.offsets.USkeletalMeshComponent_BoneArray)
        if (boneArrayPtr == null || boneArrayPtr == 0L) return getApproximateBonePosition(actor)

        val boneIndex = when (config.targetBone) {
            TargetBone.HEAD -> 66
            TargetBone.NECK -> 65
            TargetBone.CHEST -> 37
            TargetBone.PELVIS -> 2
            else -> 66
        }

        // 读取单个骨骼矩阵
        val boneMatrixPtr = boneArrayPtr + boneIndex * BONE_MATRIX_SIZE
        return gameContext.memoryReader.readBytes(gameContext.pid, boneMatrixPtr, BONE_MATRIX_SIZE)?.let { data ->
            val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            // FMatrix 列主序：[3] [7] [11] 是位移
            buffer.position(12) // M[3][0]
            val x = buffer.getFloat()
            buffer.position(28) // M[3][1]
            val y = buffer.getFloat()
            buffer.position(44) // M[3][2]
            val z = buffer.getFloat()
            UE4Parser.Vector3(x, y, z)
        } ?: getApproximateBonePosition(actor)
    }

    private fun getApproximateBonePosition(actor: UE4Parser.ActorInfo): UE4Parser.Vector3 {
        val baseZ = actor.location.z
        return when (config.targetBone) {
            TargetBone.HEAD -> UE4Parser.Vector3(actor.location.x, actor.location.y, baseZ + 70f)
            TargetBone.NECK -> UE4Parser.Vector3(actor.location.x, actor.location.y, baseZ + 55f)
            TargetBone.CHEST -> UE4Parser.Vector3(actor.location.x, actor.location.y, baseZ + 35f)
            TargetBone.PELVIS -> UE4Parser.Vector3(actor.location.x, actor.location.y, baseZ + 10f)
        }
    }
}

// ==================== 配置类 ====================

data class AimbotConfig(
    // 基础开关
    var enabled: Boolean = false,
    var showDebug: Boolean = true,        // 显示 FOV 圆、准星、连线
    
    // FOV 区域设置 (新增可调节)
    var fov: Float = 5f,                  // 基础 FOV 角度 (度)
    var fovScale: Float = 1.0f,           // FOV 缩放倍数 (0.1 - 5.0)
    var minFov: Float = 0.5f,             // 最小 FOV
    var maxFov: Float = 30f,              // 最大 FOV
    
    // 平滑与人性化
    var smooth: Float = 0.85f,            // 平滑系数 (0.01 - 1.0)
    var prediction: Boolean = true,       // 弹道预判
    var jitter: Float = 1.5f,             // 抖动幅度
    var humanizeCurve: Boolean = true,    // 贝塞尔曲线
    
    // 目标选择
    var targetBone: TargetBone = TargetBone.HEAD,
    var targetSelection: TargetSelection = TargetSelection.CROSSHAIR,
    var lockOnTarget: Boolean = false,    // 锁定模式
    
    // 射击控制
    var visibilityCheck: Boolean = true,  // 穿墙检测
    var autoFire: Boolean = false,        // 自动开火
    var delayMs: Long = 15,               // 射击延迟
    var triggerDelay: Float = 0.1f,       // 扳机延迟 (秒)
    
    // 压枪控制
    var recoilControl: Boolean = true,
    var recoilStrength: Float = 1.0f,     // 压枪强度
    var currentWeapon: String = "AK-74",  // 当前武器
    
    // 高级
    var maxDistance: Float = 300f,        // 最大有效距离
    var ignoreKnocked: Boolean = true,    // 忽略倒地
    var ignoreBots: Boolean = true,       // 忽略 AI
    var onlyVisible: Boolean = true,      // 仅可见目标
)

enum class TargetBone { HEAD, NECK, CHEST, PELVIS }
enum class TargetSelection { CLOSEST, CROSSHAIR, THREAT, LOWEST_HP, LOCK_ON }

// ==================== 压枪模式库 ====================

data class RecoilPattern(
    val weaponName: String,
    val offsets: List<RecoilOffset>
) {
    data class RecoilOffset(val x: Float, val y: Float)
}

object RecoilPatterns {
    private val patterns = mapOf(
        "AK-74" to RecoilPattern("AK-74", listOf(
            RecoilPattern.RecoilOffset(0f, 2.1f),
            RecoilPattern.RecoilOffset(-0.3f, 2.0f),
            RecoilPattern.RecoilOffset(0.2f, 1.9f),
            RecoilPattern.RecoilOffset(-0.4f, 1.8f),
            RecoilPattern.RecoilOffset(0.1f, 1.7f),
            RecoilPattern.RecoilOffset(-0.2f, 1.6f),
            RecoilPattern.RecoilOffset(0.3f, 1.5f),
            RecoilPattern.RecoilOffset(-0.1f, 1.4f),
            RecoilPattern.RecoilOffset(0.2f, 1.3f),
            RecoilPattern.RecoilOffset(-0.3f, 1.2f),
            RecoilPattern.RecoilOffset(0.1f, 1.1f),
            RecoilPattern.RecoilOffset(-0.2f, 1.0f),
            RecoilPattern.RecoilOffset(0.2f, 0.9f),
            RecoilPattern.RecoilOffset(-0.1f, 0.8f),
            RecoilPattern.RecoilOffset(0.1f, 0.7f),
            RecoilPattern.RecoilOffset(0f, 0.6f),
            RecoilPattern.RecoilOffset(-0.1f, 0.5f),
            RecoilPattern.RecoilOffset(0f, 0.4f),
            RecoilPattern.RecoilOffset(0f, 0.3f),
            RecoilPattern.RecoilOffset(0f, 0.2f),
        )),
        "M4A1" to RecoilPattern("M4A1", listOf(
            RecoilPattern.RecoilOffset(0f, 1.5f),
            RecoilPattern.RecoilOffset(0.2f, 1.4f),
            RecoilPattern.RecoilOffset(-0.1f, 1.3f),
            RecoilPattern.RecoilOffset(0.1f, 1.2f),
            RecoilPattern.RecoilOffset(-0.2f, 1.1f),
            RecoilPattern.RecoilOffset(0.1f, 1.0f),
            RecoilPattern.RecoilOffset(-0.1f, 0.9f),
            RecoilPattern.RecoilOffset(0.2f, 0.8f),
            RecoilPattern.RecoilOffset(-0.1f, 0.7f),
            RecoilPattern.RecoilOffset(0.1f, 0.6f),
        )),
        "SCAR-H" to RecoilPattern("SCAR-H", listOf(
            RecoilPattern.RecoilOffset(0f, 2.5f),
            RecoilPattern.RecoilOffset(-0.5f, 2.3f),
            RecoilPattern.RecoilOffset(0.3f, 2.1f),
            RecoilPattern.RecoilOffset(-0.4f, 1.9f),
            RecoilPattern.RecoilOffset(0.2f, 1.7f),
        )),
        "Vector" to RecoilPattern("Vector", listOf(
            RecoilPattern.RecoilOffset(0f, 0.8f),
            RecoilPattern.RecoilOffset(0.1f, 0.7f),
            RecoilPattern.RecoilOffset(-0.1f, 0.6f),
            RecoilPattern.RecoilOffset(0.1f, 0.5f),
        )),
    )

    fun getPattern(weapon: String): RecoilPattern? = patterns[weapon]
    
    fun getAllWeapons(): List<String> = patterns.keys.toList()
}
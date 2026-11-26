package com.deltaforce.assistant.game

import android.util.Log
import com.deltaforce.assistant.memory.MemoryReader

class GameContext(
    val pid: Int,
    val memoryReader: MemoryReader,
    val ue4Parser: UE4Parser
) {
    companion object {
        private const val TAG = "GameContext"
    }

    // 偏移量 (基于当前版本，需要动态更新)
    var offsets = Offsets()

    var worldPtr: Long = 0
    var localPlayerController: Long = 0
    var localPawn: Long = 0
    var cameraManager: Long = 0
    var viewMatrix = FloatArray(16)
    var projMatrix = FloatArray(16)
    var screenWidth = 1920
    var screenHeight = 1080

    // 实体缓存
    val actors = mutableListOf<UE4Parser.ActorInfo>()
    var lastUpdateTime = 0L

    fun update(): Boolean {
        try {
            // 获取 UWorld
            worldPtr = ue4Parser.getWorld()
            if (worldPtr == 0L) {
                Log.w(TAG, "Failed to get UWorld")
                return false
            }

            // 获取所有 Actors
            val allActors = ue4Parser.getAllActors(worldPtr)
            if (allActors == null) return false

            actors.clear()
            actors.addAll(allActors)

            // 查找本地玩家
            findLocalPlayer()

            // 更新相机矩阵
            updateCameraMatrix()

            lastUpdateTime = System.currentTimeMillis()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Update failed", e)
            return false
        }
    }

    private fun findLocalPlayer() {
        // 遍历 Actors 查找本地玩家控制器
        for (actor in actors) {
            if (actor.className.contains("PlayerController", true) && actor.isPlayer) {
                localPlayerController = actor.address
                localPawn = getPawn(localPlayerController)
                cameraManager = getCameraManager(localPlayerController)
                break
            }
        }
    }

    private fun getPawn(controllerPtr: Long): Long {
        // 读取 AController -> APawn
        val pawnOffset = offsets.AController_Pawn
        return memoryReader.readLong(pid, controllerPtr + pawnOffset) ?: 0L
    }

    private fun getCameraManager(controllerPtr: Long): Long {
        val cameraOffset = offsets.APlayerController_CameraManager
        return memoryReader.readLong(pid, controllerPtr + cameraOffset) ?: 0L
    }

    private fun updateCameraMatrix() {
        if (cameraManager == 0L) return
        
        // 读取 CameraManager 的 ViewMatrix 和 ProjectionMatrix
        val viewOffset = offsets.APlayerCameraManager_ViewMatrix
        val projOffset = offsets.APlayerCameraManager_ProjMatrix
        
        memoryReader.readBytes(pid, cameraManager + viewOffset, 64)?.let { data ->
            java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).run {
                for (i in 0..15) viewMatrix[i] = getFloat(i * 4)
            }
        }
        
        memoryReader.readBytes(pid, cameraManager + projOffset, 64)?.let { data ->
            java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).run {
                for (i in 0..15) projMatrix[i] = getFloat(i * 4)
            }
        }
    }

    fun worldToScreen(world: UE4Parser.Vector3): UE4Parser.Vector2? {
        return world.toScreen(viewMatrix, projMatrix, screenWidth, screenHeight)
    }

    fun getValidEnemies(): List<UE4Parser.ActorInfo> {
        return actors.filter { actor ->
            actor.health > 0 &&
            actor.teamId != getLocalTeamId() &&
            !actor.className.contains("Bot", true) &&
            actor.distanceTo(getLocalLocation()) < offsets.maxEspDistance
        }
    }

    fun getLocalTeamId(): Int {
        if (localPawn == 0L) return -1
        val teamOffset = offsets.APawn_TeamId
        return memoryReader.readInt(pid, localPawn + teamOffset) ?: -1
    }

    fun getLocalLocation(): UE4Parser.Vector3 {
        if (localPawn == 0L) return UE4Parser.Vector3(0f, 0f, 0f)
        val locOffset = offsets.AActor_RootComponent + offsets.USceneComponent_RelativeLocation
        return memoryReader.readBytes(pid, localPawn + locOffset, 12)?.let { data ->
            java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).run {
                UE4Parser.Vector3(getFloat(), getFloat(), getFloat())
            }
        } ?: UE4Parser.Vector3(0f, 0f, 0f)
    }
}

class Offsets {
    // 基础偏移量 (需要根据版本动态解析)
    var GUObjectArray: Long = 0
    var GNames: Long = 0
    var GWorld: Long = 0

    // Actor 相关
    var AActor_RootComponent: Long = 0x190
    var AActor_PlayerState: Long = 0x2B0
    var AActor_Health: Long = 0x1000 // 需要具体类
    var AActor_MaxHealth: Long = 0x1004

    // Pawn 相关
    var APawn_PlayerState: Long = 0x2B0
    var APawn_TeamId: Long = 0x1010
    var APawn_Mesh: Long = 0x310

    // Controller 相关
    var AController_Pawn: Long = 0x300
    var APlayerController_CameraManager: Long = 0x340
    var APlayerController_ControlRotation: Long = 0x320

    // CameraManager 相关
    var APlayerCameraManager_ViewMatrix: Long = 0x1E40
    var APlayerCameraManager_ProjMatrix: Long = 0x1E80

    // Component 相关
    var USceneComponent_RelativeLocation: Long = 0x120
    var USceneComponent_ComponentToWorld: Long = 0x1A0

    // SkeletalMesh 相关
    var USkeletalMeshComponent_BoneArray: Long = 0x5A0
    var USkeletalMeshComponent_ComponentToWorld: Long = 0x1A0

    // 配置
    var maxEspDistance: Float = 300f
}
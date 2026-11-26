package com.deltaforce.assistant.game

import android.util.Log

class UE4Parser {
    companion object {
        private const val TAG = "UE4Parser"
        private var sLoaded = false

        fun load() {
            if (!sLoaded) {
                System.loadLibrary("ue4parser")
                sLoaded = true
            }
        }
    }

    external fun initialize(pid: Int, ue4Base: Long): Boolean
    external fun getGNames(): java.util.List<String>?
    external fun getGObjects(): java.util.List<UObjectInfo>?
    external fun findObjectByName(name: String): UObjectInfo?
    external fun getWorld(): Long
    external fun getAllActors(worldPtr: Long): java.util.List<ActorInfo>?

    data class UObjectInfo(
        val address: Long,
        val serialNumber: Int,
        val flags: Int,
        val clusterIndex: Int
    ) {
        var name: String? = null
        var className: String? = null
        var outer: Long = 0
    }

    data class ActorInfo(
        val address: Long,
        val name: String,
        val className: String,
        val location: Vector3,
        val rotation: Rotator,
        val health: Float,
        val maxHealth: Float,
        val teamId: Int,
        val isPlayer: Boolean
    )

    data class Vector3(
        val x: Float,
        val y: Float,
        val z: Float
    ) {
        operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
        operator fun times(scalar: Float) = Vector3(x * scalar, y * scalar, z * scalar)
        fun distanceTo(other: Vector3): Float {
            val dx = x - other.x
            val dy = y - other.y
            val dz = z - other.z
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()
        }
        fun toScreen(viewMatrix: FloatArray, projMatrix: FloatArray, screenWidth: Int, screenHeight: Int): Vector2? {
            val clip = Vector4(
                x * viewMatrix[0] + y * viewMatrix[4] + z * viewMatrix[8] + viewMatrix[12],
                x * viewMatrix[1] + y * viewMatrix[5] + z * viewMatrix[9] + viewMatrix[13],
                x * viewMatrix[2] + y * viewMatrix[6] + z * viewMatrix[10] + viewMatrix[14],
                x * viewMatrix[3] + y * viewMatrix[7] + z * viewMatrix[11] + viewMatrix[15]
            )
            if (clip.w < 0.1f) return null
            val ndc = Vector3(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w)
            return Vector2(
                (screenWidth * (ndc.x + 1) * 0.5f),
                (screenHeight * (1 - ndc.y) * 0.5f)
            )
        }
    }

    data class Vector4(
        val x: Float,
        val y: Float,
        val z: Float,
        val w: Float
    )

    data class Vector2(
        val x: Float,
        val y: Float
    )

    data class Rotator(
        val pitch: Float,
        val yaw: Float,
        val roll: Float
    )
}
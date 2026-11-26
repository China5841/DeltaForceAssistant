package com.deltaforce.assistant.memory

import android.util.Log

class MemoryReader {
    companion object {
        private const val TAG = "MemoryReader"
        private var sLoaded = false

        fun load() {
            if (!sLoaded) {
                System.loadLibrary("memory")
                sLoaded = true
            }
        }
    }

    // Native methods
    external fun attach(pid: Int): Boolean
    external fun detach(pid: Int): Boolean
    external fun readMemory(pid: Int, address: Long, size: Int): ByteArray
    external fun writeMemory(pid: Int, address: Long, data: ByteArray): Boolean
    external fun getModuleBase(pid: Int, moduleName: String): Long
    external fun getModuleInfo(pid: Int, moduleName: String): ModuleInfo?

    data class ModuleInfo(
        val baseAddress: Long,
        val endAddress: Long,
        val permissions: String,
        val path: String
    ) {
        val size: Long get() = endAddress - baseAddress
    }

    fun readBytes(pid: Int, address: Long, size: Int): ByteArray? {
        val data = readMemory(pid, address, size)
        return if (data.size == size) data else null
    }

    fun readInt(pid: Int, address: Long): Int? {
        val data = readBytes(pid, address, 4) ?: return null
        return (data[0].toInt() and 0xFF) |
               ((data[1].toInt() and 0xFF) shl 8) |
               ((data[2].toInt() and 0xFF) shl 16) |
               ((data[3].toInt() and 0xFF) shl 24)
    }

    fun readLong(pid: Int, address: Long): Long? {
        val data = readBytes(pid, address, 8) ?: return null
        var value = 0L
        for (i in 0..7) {
            value = value or ((data[i].toLong() and 0xFF) shl (i * 8))
        }
        return value
    }

    fun readFloat(pid: Int, address: Long): Float? {
        val data = readBytes(pid, address, 4) ?: return null
        return java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
    }

    fun readDouble(pid: Int, address: Long): Double? {
        val data = readBytes(pid, address, 8) ?: return null
        return java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
    }

    fun writeBytes(pid: Int, address: Long, data: ByteArray): Boolean {
        return writeMemory(pid, address, data)
    }

    fun writeInt(pid: Int, address: Long, value: Int): Boolean {
        val data = ByteArray(4)
        for (i in 0..3) {
            data[i] = (value shr (i * 8) and 0xFF).toByte()
        }
        return writeBytes(pid, address, data)
    }

    fun writeLong(pid: Int, address: Long, value: Long): Boolean {
        val data = ByteArray(8)
        for (i in 0..7) {
            data[i] = (value shr (i * 8) and 0xFF).toByte()
        }
        return writeBytes(pid, address, data)
    }
}
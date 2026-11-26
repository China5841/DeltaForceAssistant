package com.deltaforce.assistant.utils

import android.util.Log

object RootUtil {
    private const val TAG = "RootUtil"

    /** 检查是否有 Root 权限 */
    fun checkRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    /** 执行 Root 命令 (同步) */
    fun executeRootCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.use { os ->
                os.write("$command\n".toByteArray())
                os.write("exit\n".toByteArray())
                os.flush()
            }
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root command failed: $command", e)
            false
        }
    }

    /** 执行 Root 命令并获取输出 */
    fun executeRootCommandWithOutput(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.use { os ->
                os.write("$command\n".toByteArray())
                os.write("exit\n".toByteArray())
                os.flush()
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                Log.e(TAG, "Command failed ($exitCode): $command\nError: $error")
                return null
            }
            output.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Root command with output failed: $command", e)
            null
        }
    }

    /** 异步执行 Root 命令 */
    fun executeRootCommandAsync(command: String, callback: ((Boolean) -> Unit)? = null) {
        Thread {
            val result = executeRootCommand(command)
            callback?.invoke(result)
        }.start()
    }

    /** 批量执行 Root 命令 */
    fun executeRootCommands(commands: List<String>): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.use { os ->
                commands.forEach { cmd ->
                    os.write("$cmd\n".toByteArray())
                }
                os.write("exit\n".toByteArray())
                os.flush()
            }
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Batch root commands failed", e)
            false
        }
    }

    /** 检查 Magisk 是否安装 */
    fun isMagiskInstalled(): Boolean {
        return executeRootCommandWithOutput("which magisk") != null
    }

    /** 检查 KernelSU 是否安装 */
    fun isKernelSUInstalled(): Boolean {
        return executeRootCommandWithOutput("which ksud") != null
    }

    /** 获取 Root 类型 */
    fun getRootType(): String {
        if (isKernelSUInstalled()) return "KernelSU"
        if (isMagiskInstalled()) return "Magisk"
        return "Unknown"
    }
}
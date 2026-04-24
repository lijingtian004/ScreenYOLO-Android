package com.example.screenyolo

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用日志管理器
 * 支持日志文件持久化和性能统计
 */
object AppLogger {

    private const val TAG = "ScreenYOLO"
    private const val LOG_FILE_NAME = "screenyolo.log"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB

    private var logFile: File? = null
    private var isInitialized = false

    /**
     * 初始化日志系统
     */
    fun init(context: Context) {
        if (isInitialized) return
        logFile = File(context.filesDir, LOG_FILE_NAME)
        isInitialized = true
        i("日志系统初始化完成")
    }

    /**
     * 记录 Info 级别日志
     */
    fun i(message: String) {
        Log.i(TAG, message)
        writeToFile("INFO", message)
    }

    /**
     * 记录 Debug 级别日志
     */
    fun d(message: String) {
        Log.d(TAG, message)
        writeToFile("DEBUG", message)
    }

    /**
     * 记录 Warning 级别日志
     */
    fun w(message: String) {
        Log.w(TAG, message)
        writeToFile("WARN", message)
    }

    /**
     * 记录 Error 级别日志
     */
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val errorMsg = if (throwable != null) "$message: ${throwable.message}" else message
        writeToFile("ERROR", errorMsg)
    }

    /**
     * 记录性能统计信息
     */
    fun logPerformance(stats: PerformanceStats) {
        val msg = buildString {
            append("性能统计 | ")
            append("推理: ${stats.inferenceTimeMs}ms | ")
            append("FPS: ${stats.fps} | ")
            append("CPU: ${stats.cpuUsage}% | ")
            append("内存: ${stats.memoryUsageMB}MB | ")
            append("温度: ${stats.batteryTemp}°C")
        }
        d(msg)
    }

    /**
     * 获取日志文件内容
     */
    fun getLogContent(): String {
        return try {
            logFile?.readText() ?: "日志文件不存在"
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /**
     * 清空日志文件
     */
    fun clearLogs() {
        try {
            logFile?.writeText("")
            i("日志已清空")
        } catch (e: Exception) {
            Log.e(TAG, "清空日志失败", e)
        }
    }

    /**
     * 获取日志文件大小（字节）
     */
    fun getLogFileSize(): Long {
        return logFile?.length() ?: 0
    }

    private fun writeToFile(level: String, message: String) {
        try {
            val file = logFile ?: return
            // 检查日志文件大小，超过限制则清空
            if (file.length() > MAX_LOG_SIZE) {
                file.writeText("")
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logLine = "[$timestamp] [$level] $message\n"
            file.appendText(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "写入日志文件失败", e)
        }
    }
}

/**
 * 性能统计数据类
 */
data class PerformanceStats(
    val inferenceTimeMs: Long = 0,
    val fps: Int = 0,
    val cpuUsage: Float = 0f,
    val memoryUsageMB: Float = 0f,
    val batteryTemp: Float = 0f
)

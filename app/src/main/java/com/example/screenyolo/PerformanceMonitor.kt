package com.example.screenyolo

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能监控器
 * 监控推理时间、FPS、CPU 使用率、内存使用和电池温度
 * 支持根据设备状态动态调整推理频率
 */
class PerformanceMonitor(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var lastFrameTime = System.currentTimeMillis()
    private var frameCount = 0
    private var currentFps = 0

    // 性能统计
    private val totalInferenceTime = AtomicLong(0)
    private val inferenceCount = AtomicLong(0)

    // 动态调整参数
    private var targetIntervalMs = 200L // 默认 5 FPS
    private var isThrottling = false

    /**
     * 记录一帧推理完成
     * @param inferenceTimeMs 本次推理耗时（毫秒）
     * @return 是否建议跳过下一帧（用于动态降频）
     */
    fun recordFrame(inferenceTimeMs: Long): Boolean {
        val now = System.currentTimeMillis()
        frameCount++

        // 每秒计算一次 FPS
        if (now - lastFrameTime >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFrameTime = now
        }

        // 累计推理时间
        totalInferenceTime.addAndGet(inferenceTimeMs)
        inferenceCount.incrementAndGet()

        // 动态调整：如果推理时间超过目标间隔的 80%，建议降频
        if (inferenceTimeMs > targetIntervalMs * 0.8) {
            if (!isThrottling) {
                isThrottling = true
                AppLogger.w("推理耗时过长 (${inferenceTimeMs}ms)，启用动态降频")
            }
            return true // 建议跳过下一帧
        } else {
            isThrottling = false
        }

        return false
    }

    /**
     * 获取当前性能统计
     */
    fun getStats(): PerformanceStats {
        return PerformanceStats(
            inferenceTimeMs = getAverageInferenceTime(),
            fps = currentFps,
            cpuUsage = getCpuUsage(),
            memoryUsageMB = getMemoryUsage(),
            batteryTemp = getBatteryTemperature()
        )
    }

    /**
     * 获取平均推理时间
     */
    fun getAverageInferenceTime(): Long {
        val count = inferenceCount.get()
        return if (count > 0) totalInferenceTime.get() / count else 0
    }

    /**
     * 获取当前 FPS
     */
    fun getCurrentFps(): Int = currentFps

    /**
     * 获取 CPU 使用率（近似值）
     */
    fun getCpuUsage(): Float {
        return try {
            val pid = Process.myPid()
            val file = File("/proc/$pid/stat")
            if (!file.exists()) return 0f

            RandomAccessFile(file, "r").use { randomAccessFile ->
                val line = randomAccessFile.readLine() ?: return 0f
                val parts = line.split(" ")
                if (parts.size > 13) {
                    val utime = parts[13].toLongOrNull() ?: 0
                    val stime = parts[14].toLongOrNull() ?: 0
                    val totalTime = utime + stime
                    // 简化的 CPU 使用率计算
                    (totalTime % 100).toFloat()
                } else {
                    0f
                }
            }
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * 获取应用内存使用（MB）
     */
    fun getMemoryUsage(): Float {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        return usedMem.toFloat()
    }

    /**
     * 获取电池温度（摄氏度）
     */
    fun getBatteryTemperature(): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10.0f
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * 检查设备是否过热（> 45°C）
     */
    fun isOverheating(): Boolean {
        return getBatteryTemperature() > 45f
    }

    /**
     * 根据设备状态获取推荐的推理间隔
     * 过热或低电量时增加间隔以降低功耗
     */
    fun getRecommendedIntervalMs(): Long {
        var interval = targetIntervalMs

        // 过热保护：降低频率
        if (isOverheating()) {
            interval = (interval * 2).coerceAtMost(1000L)
            AppLogger.w("设备过热，降低推理频率至 ${1000 / interval} FPS")
        }

        // 检查电池状态
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = level * 100 / scale.toFloat()

        // 低电量保护
        if (batteryPct < 20) {
            interval = (interval * 1.5).coerceAtMost(1000L).toLong()
            AppLogger.w("电量低 (${batteryPct}%)，降低推理频率")
        }

        return interval
    }

    /**
     * 重置统计数据
     */
    fun reset() {
        totalInferenceTime.set(0)
        inferenceCount.set(0)
        frameCount = 0
        currentFps = 0
        lastFrameTime = System.currentTimeMillis()
        isThrottling = false
    }
}

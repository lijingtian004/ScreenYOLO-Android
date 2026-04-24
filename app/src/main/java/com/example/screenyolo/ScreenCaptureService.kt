package com.example.screenyolo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * 屏幕捕获服务
 * 负责启动 MediaProjection 捕获屏幕内容，调用 YOLO 模型进行推理，
 * 并通过广播将检测结果发送给 OverlayService 绘制检测框
 *
 * 支持功能：
 * - 自定义截图区域
 * - 性能监控（FPS、推理时间、CPU、内存、温度）
 * - Bitmap 对象池复用
 * - 动态降频（过热/低电量保护）
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_ENABLED_CLASSES = "enabled_classes"
        const val EXTRA_CAPTURE_REGION = "capture_region"
        const val CHANNEL_ID = "screen_yolo_channel"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var detector: Detector? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var inferIntervalMs = 200L // 限制 5 FPS，避免过热和卡顿

    // 性能监控
    private var performanceMonitor: PerformanceMonitor? = null

    // 截图区域
    private var captureRegion: CaptureRegion = CaptureRegion()

    // 屏幕尺寸
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppLogger.init(this)
        AppLogger.i("ScreenCaptureService 创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 获取截图区域设置
        captureRegion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CAPTURE_REGION, CaptureRegion::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CAPTURE_REGION)
        } ?: CaptureRegion()

        val modelInfo = getModelPath()
        if (modelInfo == null) {
            sendBroadcast(Intent("com.example.screenyolo.MODEL_MISSING"))
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // 根据模型类型创建对应的检测器
            detector = when (modelInfo.type) {
                ModelType.TFLITE -> YoloDetector(this, modelInfo.path)
                ModelType.NCNN -> {
                    try {
                        NcnnDetector(this, modelInfo.paramPath!!, modelInfo.binPath!!)
                    } catch (e: UnsupportedOperationException) {
                        // ncnn 未集成，发送广播提示用户
                        AppLogger.w("ncnn 不可用: ${e.message}")
                        val ncnnIntent = Intent("com.example.screenyolo.NCNN_NOT_AVAILABLE")
                        ncnnIntent.putExtra("message", e.message)
                        sendBroadcast(ncnnIntent)
                        null
                    }
                }
            }

            // 如果检测器创建失败，停止服务
            if (detector == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            // 应用类别过滤设置
            val enabledClasses = intent.getStringArrayListExtra(EXTRA_ENABLED_CLASSES)
            if (enabledClasses != null && enabledClasses.isNotEmpty()) {
                detector?.setEnabledClasses(enabledClasses.toSet())
            }
        } catch (e: Exception) {
            AppLogger.e("创建检测器失败", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 初始化性能监控
        performanceMonitor = PerformanceMonitor(this)

        startForeground(1, buildNotification())
        startOverlay()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        // 如果有自定义区域，使用区域的尺寸创建 ImageReader
        val captureWidth: Int
        val captureHeight: Int
        if (captureRegion.enabled && captureRegion.width > 0 && captureRegion.height > 0) {
            captureWidth = captureRegion.width
            captureHeight = captureRegion.height
            AppLogger.i("使用自定义截图区域: ${captureRegion.width}x${captureRegion.height} @ (${captureRegion.x},${captureRegion.y})")
        } else {
            captureWidth = screenWidth
            captureHeight = screenHeight
        }

        // 通过广播发送屏幕尺寸给 OverlayService
        OverlayService.sendScale(this, screenWidth, screenHeight)

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenYOLO",
            captureWidth, captureHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isRunning = true
        scheduleNextFrame()

        return START_STICKY
    }

    /**
     * 定时调度下一帧处理
     */
    private fun scheduleNextFrame() {
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            processFrame()
            // 动态调整间隔
            performanceMonitor?.let {
                inferIntervalMs = it.getRecommendedIntervalMs()
            }
            scheduleNextFrame()
        }, inferIntervalMs)
    }

    /**
     * 处理单帧图像：获取 Image，转为 Bitmap，推理，发送结果
     */
    private fun processFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        val startTime = SystemClock.elapsedRealtime()

        val bitmap = imageToBitmap(image)
        image.close()

        if (bitmap != null) {
            val results = detector?.detect(bitmap) ?: emptyList()
            val inferenceTime = SystemClock.elapsedRealtime() - startTime

            // 记录性能数据
            performanceMonitor?.let { monitor ->
                val shouldSkip = monitor.recordFrame(inferenceTime)
                if (shouldSkip) {
                    AppLogger.d("推理耗时 ${inferenceTime}ms，跳过下一帧")
                }
                // 每 30 帧记录一次性能统计
                if (monitor.getStats().fps > 0 && inferenceTime % 30 == 0L) {
                    AppLogger.logPerformance(monitor.getStats())
                }
            }

            // 通过广播发送检测结果给 OverlayService
            OverlayService.sendDetections(this, results)

            // 使用 Bitmap 对象池回收
            BitmapPool.recycle(bitmap)
        }
    }

    /**
     * 将 Image 转换为 Bitmap，并裁剪掉 rowPadding 造成的多余宽度
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // 从对象池获取 Bitmap
            val bitmap = BitmapPool.get(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // 如果有 rowPadding，需要裁剪掉多余的像素
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            AppLogger.e("Image 转 Bitmap 失败", e)
            null
        }
    }

    /**
     * 启动悬浮窗服务
     */
    private fun startOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        startService(intent)
    }

    /**
     * 模型类型枚举
     */
    enum class ModelType {
        TFLITE, NCNN
    }

    /**
     * 模型信息数据类
     */
    data class ModelInfo(
        val type: ModelType,
        val path: String,
        val paramPath: String? = null,
        val binPath: String? = null
    )

    /**
     * 获取模型文件路径
     * 支持 TFLite (.tflite) 和 ncnn (.param + .bin) 格式
     */
    private fun getModelPath(): ModelInfo? {
        // 检查 TFLite 模型
        val tfliteFile = File(filesDir, "model.tflite")
        if (tfliteFile.exists()) {
            return ModelInfo(ModelType.TFLITE, tfliteFile.absolutePath)
        }

        // 检查 ncnn 模型（需要 .param 和 .bin 两个文件）
        val paramFile = File(filesDir, "model.param")
        val binFile = File(filesDir, "model.bin")
        if (paramFile.exists() && binFile.exists()) {
            return ModelInfo(
                ModelType.NCNN,
                paramFile.absolutePath,
                paramFile.absolutePath,
                binFile.absolutePath
            )
        }

        return null
    }

    /**
     * 创建通知渠道（Android O 以上需要）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen YOLO",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建前台服务通知
     */
    private fun buildNotification(): Notification {
        val stats = performanceMonitor?.getStats()
        val contentText = if (stats != null) {
            "正在检测 | FPS: ${stats.fps} | 推理: ${stats.inferenceTimeMs}ms"
        } else {
            "正在检测屏幕内容..."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenYOLO")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        detector?.close()
        BitmapPool.clear()
        AppLogger.i("ScreenCaptureService 销毁")
        stopService(Intent(this, OverlayService::class.java))
        super.onDestroy()
    }
}

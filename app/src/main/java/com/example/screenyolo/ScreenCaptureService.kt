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
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * 屏幕捕获服务
 * 负责启动 MediaProjection 捕获屏幕内容，调用 YOLO 模型进行推理，
 * 并通过广播将检测结果发送给 OverlayService 绘制检测框
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_ENABLED_CLASSES = "enabled_classes"
        const val CHANNEL_ID = "screen_yolo_channel"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var yoloDetector: YoloDetector? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val inferIntervalMs = 200L // 限制 5 FPS，避免过热和卡顿

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val modelPath = getModelPath()
        if (modelPath == null) {
            sendBroadcast(Intent("com.example.screenyolo.MODEL_MISSING"))
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            yoloDetector = YoloDetector(this, modelPath)
            // 应用类别过滤设置
            val enabledClasses = intent?.getStringArrayListExtra(EXTRA_ENABLED_CLASSES)
            if (enabledClasses != null && enabledClasses.isNotEmpty()) {
                yoloDetector?.setEnabledClasses(enabledClasses.toSet())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1, buildNotification())
        startOverlay()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 通过广播发送屏幕尺寸给 OverlayService
        OverlayService.sendScale(this, width, height)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenYOLO",
            width, height, density,
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
            scheduleNextFrame()
        }, inferIntervalMs)
    }

    /**
     * 处理单帧图像：获取 Image，转为 Bitmap，推理，发送结果
     */
    private fun processFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        val bitmap = imageToBitmap(image)
        image.close()

        if (bitmap != null) {
            val results = yoloDetector?.detect(bitmap) ?: emptyList()
            // 通过广播发送检测结果给 OverlayService
            OverlayService.sendDetections(this, results)
            bitmap.recycle()
        }
    }

    /**
     * 将 Image 转换为 Bitmap，并裁剪掉 rowPadding 造成的多余宽度
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // 创建与 Image 实际宽度一致的 Bitmap，避免 rowPadding 带来的黑边
        val bitmap = Bitmap.createBitmap(
            image.width,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 如果有 rowPadding，需要裁剪掉多余的像素
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
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
     * 获取模型文件路径
     */
    private fun getModelPath(): String? {
        val localFile = File(filesDir, "model.tflite")
        if (localFile.exists()) return localFile.absolutePath
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenYOLO")
            .setContentText("正在检测屏幕内容...")
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
        yoloDetector?.close()
        stopService(Intent(this, OverlayService::class.java))
        super.onDestroy()
    }
}

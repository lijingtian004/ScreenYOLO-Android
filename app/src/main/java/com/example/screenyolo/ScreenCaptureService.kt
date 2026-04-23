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

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val CHANNEL_ID = "screen_yolo_channel"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var yoloDetector: YoloDetector? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var overlayService: OverlayService? = null
    private val inferIntervalMs = 200L // 5 FPS

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

        overlayService?.setScale(width, height)

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

    private fun scheduleNextFrame() {
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            processFrame()
            scheduleNextFrame()
        }, inferIntervalMs)
    }

    private fun processFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        val bitmap = imageToBitmap(image)
        image.close()

        if (bitmap != null) {
            val results = yoloDetector?.detect(bitmap) ?: emptyList()
            overlayService?.updateDetections(results)
            bitmap.recycle()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        startService(intent)
        // Simple binding workaround: we start service and will communicate via broadcast if needed.
        // For simplicity in this sample, we use a static bridge.
        OverlayServiceHolder.service = null
        // Actually start and hold reference through a local binder pattern is complex.
        // We'll use a simpler broadcast-based approach in MainActivity to update overlay.
    }

    private fun getModelPath(): String? {
        val localFile = File(filesDir, "model.tflite")
        if (localFile.exists()) return localFile.absolutePath

        val assetFile = File(filesDir, "yolov8n_float32.tflite")
        if (assetFile.exists()) return assetFile.absolutePath

        return null
    }

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

// Simple holder to let MainActivity pass overlay service reference.
object OverlayServiceHolder {
    var view: OverlayView? = null
}

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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.screenyolo.engine.EngineType

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_ENGINE_TYPE = "engine_type"
        const val CHANNEL_ID = "screen_yolo_channel"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var yoloDetector: YoloDetector? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var overlayService: OverlayService? = null
    private lateinit var logManager: LogManager

    // Background inference thread
    private var inferThread: HandlerThread? = null
    private var inferHandler: Handler? = null
    private var isInferring = false

    // Performance stats
    private val frameTimestamps = ArrayDeque<Long>()
    private var lastLatencyMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        logManager = LogManager.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val engineOrdinal = intent?.getIntExtra(EXTRA_ENGINE_TYPE, 0) ?: 0
        val engineType = EngineType.fromOrdinal(engineOrdinal)

        if (data == null) {
            logManager.logEvent("ERROR", "Missing MediaProjection data, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            yoloDetector = YoloDetector(this, engineType)
            logManager.logEvent("INFO", "Model loaded: ${engineType.displayName}")
        } catch (e: Exception) {
            e.printStackTrace()
            logManager.logEvent("ERROR", "Model load failed: ${e.message}")
            sendBroadcast(Intent("com.example.screenyolo.MODEL_MISSING"))
            stopSelf()
            return START_NOT_STICKY
        }

        // Start background inference thread
        inferThread = HandlerThread("YoloInference").apply { start() }
        inferHandler = Handler(inferThread!!.looper)

        startForeground(1, buildNotification(engineType.displayName))
        startOverlay()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        overlayService?.setScale(width, height, yoloDetector?.inputSize ?: 640)

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
            if (isRunning) scheduleNextFrame()
        }, 8L) // ~120fps loop cap, actual speed limited by inference
    }

    private fun processFrame() {
        if (isInferring) return // Skip if background thread still busy

        val image = imageReader?.acquireLatestImage() ?: return
        val bitmap = imageToBitmap(image)
        image.close()

        if (bitmap != null) {
            isInferring = true
            inferHandler?.post {
                val startTime = SystemClock.elapsedRealtime()
                val results = yoloDetector?.detect(bitmap) ?: emptyList()
                val latency = SystemClock.elapsedRealtime() - startTime
                bitmap.recycle()

                handler.post {
                    if (!isRunning) return@post
                    lastLatencyMs = latency

                    // Update FPS: count completed inferences in last 1000ms
                    val now = SystemClock.elapsedRealtime()
                    frameTimestamps.addLast(now)
                    while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > 1000L) {
                        frameTimestamps.removeFirst()
                    }
                    val fps = frameTimestamps.size.toFloat()

                    overlayService?.updateDetections(results)
                    overlayService?.updateStats(fps, lastLatencyMs)
                    isInferring = false

                    // Log inference results
                    if (results.isNotEmpty()) {
                        val top = results.maxByOrNull { it.confidence }
                        logManager.logEvent("DETECT", "${results.size} objects, top=${top?.label}(${"%.2f".format(top?.confidence ?: 0f)}), latency=${latency}ms, fps=${"%.1f".format(fps)}")
                    }
                    logManager.logEvent("PERF", "latency=${latency}ms, fps=${"%.1f".format(fps)}, objects=${results.size}")
                }
            }
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
        OverlayServiceHolder.view = null
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

    private fun buildNotification(engineName: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenYOLO [$engineName]")
            .setContentText("正在检测屏幕内容...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        logManager.logEvent("INFO", "ScreenCaptureService stopped")
        handler.removeCallbacksAndMessages(null)
        inferThread?.quitSafely()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        yoloDetector?.close()
        stopService(Intent(this, OverlayService::class.java))
        super.onDestroy()
    }
}

object OverlayServiceHolder {
    var view: OverlayView? = null
}

package com.example.screenyolo

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.WindowManager

/**
 * 悬浮窗服务，负责在屏幕上绘制检测框
 * 通过 BroadcastReceiver 接收 ScreenCaptureService 发送的检测结果
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView

    // 广播接收器：接收检测结果并更新悬浮窗
    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_DETECTIONS -> {
                    @Suppress("UNCHECKED_CAST")
                    val detections = intent.getSerializableExtra("detections") as? ArrayList<Detection>
                    detections?.let { overlayView.updateDetections(it) }
                }
                ACTION_SET_SCALE -> {
                    val w = intent.getIntExtra("width", 0)
                    val h = intent.getIntExtra("height", 0)
                    if (w > 0 && h > 0) {
                        overlayView.setScale(w, h)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayView, params)

        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_DETECTIONS)
            addAction(ACTION_SET_SCALE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(detectionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(detectionReceiver, filter)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(detectionReceiver)
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }

    companion object {
        const val ACTION_UPDATE_DETECTIONS = "com.example.screenyolo.UPDATE_DETECTIONS"
        const val ACTION_SET_SCALE = "com.example.screenyolo.SET_SCALE"

        /**
         * 发送检测结果到 OverlayService
         */
        fun sendDetections(context: Context, detections: List<Detection>) {
            val intent = Intent(ACTION_UPDATE_DETECTIONS).apply {
                putExtra("detections", ArrayList(detections))
            }
            context.sendBroadcast(intent)
        }

        /**
         * 发送屏幕尺寸到 OverlayService 用于缩放计算
         */
        fun sendScale(context: Context, width: Int, height: Int) {
            val intent = Intent(ACTION_SET_SCALE).apply {
                putExtra("width", width)
                putExtra("height", height)
            }
            context.sendBroadcast(intent)
        }
    }
}

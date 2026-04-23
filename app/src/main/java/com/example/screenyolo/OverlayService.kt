package com.example.screenyolo

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.WindowManager

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView

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
    }

    fun updateDetections(detections: List<Detection>) {
        overlayView.updateDetections(detections)
    }

    fun updateStats(fps: Float, latencyMs: Long) {
        overlayView.updateStats(fps, latencyMs)
    }

    fun setScale(w: Int, h: Int, inputSize: Int = 640) {
        overlayView.setScale(w, h, inputSize)
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}

package com.example.screenyolo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class OverlayView(context: Context) : View(context) {

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 36f
        isFakeBoldText = true
    }

    private val textBgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private var detections: List<Detection> = emptyList()
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    fun setScale(screenWidth: Int, screenHeight: Int) {
        scaleX = screenWidth / YoloDetector.INPUT_SIZE.toFloat()
        scaleY = screenHeight / YoloDetector.INPUT_SIZE.toFloat()
    }

    fun updateDetections(detections: List<Detection>) {
        this.detections = detections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (d in detections) {
            val left = d.x1 * scaleX
            val top = d.y1 * scaleY
            val right = d.x2 * scaleX
            val bottom = d.y2 * scaleY

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${d.label} ${(d.confidence * 100).toInt()}%"
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.fontMetrics.run { descent - ascent }

            val bgRect = RectF(left, top - textHeight, left + textWidth, top)
            canvas.drawRect(bgRect, textBgPaint)
            canvas.drawText(label, left, top - textPaint.fontMetrics.descent, textPaint)
        }
    }
}

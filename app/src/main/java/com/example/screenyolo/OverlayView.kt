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

    private val statsTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isFakeBoldText = true
    }

    private val statsBgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private var detections: List<Detection> = emptyList()
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    private var fps: Float = 0f
    private var latencyMs: Long = 0L

    fun setScale(screenWidth: Int, screenHeight: Int, inputSize: Int = 640) {
        scaleX = screenWidth / inputSize.toFloat()
        scaleY = screenHeight / inputSize.toFloat()
    }

    fun updateDetections(detections: List<Detection>) {
        this.detections = detections
        invalidate()
    }

    fun updateStats(fps: Float, latencyMs: Long) {
        this.fps = fps
        this.latencyMs = latencyMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw stats panel top-left
        val statsLines = listOf(
            "FPS: %.1f".format(fps),
            "延迟: ${latencyMs}ms"
        )
        val padding = 16f
        val lineHeight = statsTextPaint.fontMetrics.run { descent - ascent } + 8f
        val maxTextWidth = statsLines.maxOf { statsTextPaint.measureText(it) }
        val panelWidth = maxTextWidth + padding * 2
        val panelHeight = lineHeight * statsLines.size + padding * 2

        canvas.drawRoundRect(
            20f, 20f, 20f + panelWidth, 20f + panelHeight,
            12f, 12f, statsBgPaint
        )

        statsLines.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                20f + padding,
                20f + padding + (index + 1) * lineHeight - statsTextPaint.fontMetrics.descent,
                statsTextPaint
            )
        }

        // Draw detection boxes
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

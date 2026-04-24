package com.example.screenyolo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 悬浮窗绘制视图
 * 负责在屏幕上绘制检测框和标签
 * 使用 letterbox 方式保持宽高比，避免检测框位置偏移
 */
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

    // letterbox 缩放相关参数
    private var scaleFactor: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    /**
     * 设置屏幕尺寸，计算 letterbox 缩放参数
     * YOLO 模型输入是 640x640 正方形，而屏幕是长方形，
     * 使用 letterbox 方式保持宽高比，避免图像拉伸导致检测框偏移
     */
    fun setScale(screenWidth: Int, screenHeight: Int) {
        val modelSize = YoloDetector.INPUT_SIZE.toFloat()

        // 计算缩放因子：以较小的比例为准，保持宽高比
        scaleFactor = minOf(screenWidth / modelSize, screenHeight / modelSize)

        // 计算居中偏移量
        offsetX = (screenWidth - modelSize * scaleFactor) / 2f
        offsetY = (screenHeight - modelSize * scaleFactor) / 2f
    }

    /**
     * 更新检测结果并重绘
     */
    fun updateDetections(detections: List<Detection>) {
        this.detections = detections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (d in detections) {
            // 使用 letterbox 参数将模型坐标映射到屏幕坐标
            val left = d.x1 * scaleFactor + offsetX
            val top = d.y1 * scaleFactor + offsetY
            val right = d.x2 * scaleFactor + offsetX
            val bottom = d.y2 * scaleFactor + offsetY

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

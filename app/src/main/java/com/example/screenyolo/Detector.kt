package com.example.screenyolo

import android.graphics.Bitmap

/**
 * 目标检测器通用接口
 * 支持多种推理后端（TFLite、ncnn 等）
 */
interface Detector {

    companion object {
        const val INPUT_SIZE = 640
        const val NUM_CLASSES = 80
        const val CONF_THRESHOLD = 0.25f
        const val IOU_THRESHOLD = 0.45f

        val LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
            "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }

    /**
     * 对输入的 Bitmap 进行目标检测
     * @param bitmap 输入图像
     * @return 检测到的目标列表
     */
    fun detect(bitmap: Bitmap): List<Detection>

    /**
     * 设置要检测的类别列表
     * @param classes 要检测的类别名称集合，如果为空则检测所有类别
     */
    fun setEnabledClasses(classes: Set<String>)

    /**
     * 获取当前启用的类别列表
     */
    fun getEnabledClasses(): Set<String>

    /**
     * 释放检测器资源
     */
    fun close()
}

/**
 * 非极大值抑制（NMS）工具函数
 */
fun nms(detections: List<Detection>): List<Detection> {
    val sorted = detections.sortedByDescending { it.confidence }
    val picked = mutableListOf<Detection>()
    val suppressed = BooleanArray(sorted.size)

    for (i in sorted.indices) {
        if (suppressed[i]) continue
        picked.add(sorted[i])
        for (j in i + 1 until sorted.size) {
            if (suppressed[j]) continue
            if (iou(sorted[i], sorted[j]) > Detector.IOU_THRESHOLD) {
                suppressed[j] = true
            }
        }
    }
    return picked
}

/**
 * 计算两个检测框的 IoU（交并比）
 */
fun iou(a: Detection, b: Detection): Float {
    val x1 = maxOf(a.x1, b.x1)
    val y1 = maxOf(a.y1, b.y1)
    val x2 = minOf(a.x2, b.x2)
    val y2 = minOf(a.y2, b.y2)
    val interArea = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
    val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
    val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
    return interArea / (areaA + areaB - interArea + 1e-6f)
}
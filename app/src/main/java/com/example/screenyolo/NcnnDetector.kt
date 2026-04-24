package com.example.screenyolo

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.tencent.ncnn.Ncnn
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ncnn YOLO 目标检测器
 * 使用腾讯 ncnn 框架进行推理，支持 .param 和 .bin 模型文件
 */
class NcnnDetector(
    context: Context,
    private val paramPath: String,
    private val binPath: String
) : Detector {

    private val net = Ncnn.Net()
    private val intValues = IntArray(Detector.INPUT_SIZE * Detector.INPUT_SIZE)

    // 类别过滤集合：如果为空则检测所有类别
    private var enabledClasses: Set<String> = emptySet()

    init {
        // 加载 ncnn 模型
        val ret = net.loadParam(paramPath)
        if (ret != 0) {
            throw RuntimeException("Failed to load ncnn param file: $paramPath")
        }

        val retBin = net.loadModel(binPath)
        if (retBin != 0) {
            throw RuntimeException("Failed to load ncnn bin file: $binPath")
        }
    }

    /**
     * 设置要检测的类别列表
     * @param classes 要检测的类别名称集合，如果为空则检测所有类别
     */
    override fun setEnabledClasses(classes: Set<String>) {
        enabledClasses = classes
    }

    /**
     * 获取当前启用的类别列表
     */
    override fun getEnabledClasses(): Set<String> = enabledClasses

    /**
     * 对输入的 Bitmap 进行 YOLO 目标检测
     * @param bitmap 屏幕截图的 Bitmap
     * @return 检测到的目标列表
     */
    override fun detect(bitmap: Bitmap): List<Detection> {
        val start = SystemClock.elapsedRealtime()

        // 将输入图像缩放到模型要求的 640x640
        val scaled = Bitmap.createScaledBitmap(bitmap, Detector.INPUT_SIZE, Detector.INPUT_SIZE, true)
        scaled.getPixels(intValues, 0, Detector.INPUT_SIZE, 0, 0, Detector.INPUT_SIZE, Detector.INPUT_SIZE)

        // 创建 ncnn Mat 并填充数据
        val mat = Ncnn.Mat.fromPixels(
            intValues,
            Ncnn.Mat.PixelType.PIXEL_RGB,
            Detector.INPUT_SIZE,
            Detector.INPUT_SIZE
        )

        // 归一化到 0~1
        val meanVals = Ncnn.Mat(3).apply {
            put(0, 0.0f)
            put(1, 0.0f)
            put(2, 0.0f)
        }
        val normVals = Ncnn.Mat(3).apply {
            put(0, 1 / 255.0f)
            put(1, 1 / 255.0f)
            put(2, 1 / 255.0f)
        }

        // 创建提取器并设置输入
        val ex = net.createExtractor()
        ex.input("in0", mat)

        // 提取输出
        val out = Ncnn.Mat()
        ex.extract("out0", out)

        // 解析输出
        val detections = parseNcnnOutput(out)
        val filtered = nms(detections)

        // 回收资源
        scaled.recycle()
        mat.release()
        out.release()

        val cost = SystemClock.elapsedRealtime() - start
        android.util.Log.d("NcnnDetector", "Inference cost: ${cost}ms")

        return filtered
    }

    /**
     * 解析 ncnn 输出
     * ncnn YOLO 输出格式: [num_anchors, 84] 或 [84, num_anchors]
     */
    private fun parseNcnnOutput(out: Ncnn.Mat): List<Detection> {
        val results = mutableListOf<Detection>()

        // 获取输出维度
        val w = out.w // 宽度（通常是 8400，即 anchor 数量）
        val h = out.h // 高度（通常是 84，即 4 个坐标 + 80 个类别分数）
        val c = out.c // 通道数

        // 判断输出格式
        val isTransposed = h == 84 && w == 8400
        val numAnchors = if (isTransposed) w else h
        val numValues = if (isTransposed) h else w

        if (numValues != 84) {
            android.util.Log.w("NcnnDetector", "Unexpected output shape: w=$w, h=$h, c=$c")
            return results
        }

        for (i in 0 until numAnchors) {
            val cx: Float
            val cy: Float
            val bw: Float
            val bh: Float
            val scores = FloatArray(Detector.NUM_CLASSES)

            if (isTransposed) {
                // 格式: [84, 8400]
                cx = out[i, 0, 0]
                cy = out[i, 1, 0]
                bw = out[i, 2, 0]
                bh = out[i, 3, 0]
                for (c_idx in 0 until Detector.NUM_CLASSES) {
                    scores[c_idx] = out[i, 4 + c_idx, 0]
                }
            } else {
                // 格式: [8400, 84]
                cx = out[0, 0, i]
                cy = out[0, 1, i]
                bw = out[0, 2, i]
                bh = out[0, 3, i]
                for (c_idx in 0 until Detector.NUM_CLASSES) {
                    scores[c_idx] = out[0, 4 + c_idx, i]
                }
            }

            var maxScore = 0f
            var classId = 0
            for (c_idx in 0 until Detector.NUM_CLASSES) {
                if (scores[c_idx] > maxScore) {
                    maxScore = scores[c_idx]
                    classId = c_idx
                }
            }

            if (maxScore > Detector.CONF_THRESHOLD) {
                val label = Detector.LABELS[classId]
                // 应用类别过滤
                if (enabledClasses.isNotEmpty() && !enabledClasses.contains(label)) {
                    continue
                }
                val x1 = cx - bw / 2f
                val y1 = cy - bh / 2f
                val x2 = cx + bw / 2f
                val y2 = cy + bh / 2f
                results.add(Detection(x1, y1, x2, y2, maxScore, classId, label))
            }
        }

        return results
    }

    /**
     * 释放检测器资源
     */
    override fun close() {
        net.clear()
    }
}

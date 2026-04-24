package com.example.screenyolo

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOLO TFLite 目标检测器
 * 支持自定义类别过滤，只检测用户感兴趣的物体
 */
class YoloDetector(context: Context, modelPath: String) : Detector {

    private val interpreter: Interpreter
    private val intValues = IntArray(Detector.INPUT_SIZE * Detector.INPUT_SIZE)
    private val inputBuffer: ByteBuffer

    // 类别过滤集合：如果为空则检测所有类别
    private var enabledClasses: Set<String> = emptySet()

    init {
        val options = Interpreter.Options().apply {
            numThreads = 4
            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                addDelegate(GpuDelegate())
            }
        }
        interpreter = Interpreter(File(modelPath), options)
        inputBuffer = ByteBuffer.allocateDirect(1 * Detector.INPUT_SIZE * Detector.INPUT_SIZE * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
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
     * 检查某个类别是否被启用
     */
    fun isClassEnabled(label: String): Boolean {
        return enabledClasses.isEmpty() || enabledClasses.contains(label)
    }

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

        // 填充输入缓冲区：将 RGB 像素归一化到 0~1
        inputBuffer.rewind()
        for (pixelValue in intValues) {
            inputBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
        }

        // 获取模型输出并运行推理
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = Array(outputShape[0]) {
            Array(outputShape[1]) { FloatArray(outputShape[2]) }
        }

        interpreter.run(inputBuffer, output)

        // 解析输出并进行非极大值抑制
        val detections = parseOutput(output, outputShape)
        val filtered = nms(detections)

        // 回收临时 Bitmap，避免内存泄漏
        scaled.recycle()

        val cost = SystemClock.elapsedRealtime() - start
        android.util.Log.d("YoloDetector", "Inference cost: ${cost}ms")

        return filtered
    }

    private fun parseOutput(output: Array<Array<FloatArray>>, shape: IntArray): List<Detection> {
        val isTransposed = shape[1] == 84 && shape[2] == 8400
        val numAnchors = if (isTransposed) shape[2] else shape[1]

        val results = mutableListOf<Detection>()
        for (i in 0 until numAnchors) {
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            val scores: FloatArray

            if (isTransposed) {
                cx = output[0][0][i]
                cy = output[0][1][i]
                w  = output[0][2][i]
                h  = output[0][3][i]
                scores = FloatArray(Detector.NUM_CLASSES) { c -> output[0][4 + c][i] }
            } else {
                cx = output[0][i][0]
                cy = output[0][i][1]
                w  = output[0][i][2]
                h  = output[0][i][3]
                scores = FloatArray(Detector.NUM_CLASSES) { c -> output[0][i][4 + c] }
            }

            var maxScore = 0f
            var classId = 0
            for (c in 0 until Detector.NUM_CLASSES) {
                if (scores[c] > maxScore) {
                    maxScore = scores[c]
                    classId = c
                }
            }

            if (maxScore > Detector.CONF_THRESHOLD) {
                val label = Detector.LABELS[classId]
                // 应用类别过滤：如果启用了过滤且当前类别不在列表中，则跳过
                if (enabledClasses.isNotEmpty() && !enabledClasses.contains(label)) {
                    continue
                }
                val x1 = cx - w / 2f
                val y1 = cy - h / 2f
                val x2 = cx + w / 2f
                val y2 = cy + h / 2f
                results.add(Detection(x1, y1, x2, y2, maxScore, classId, label))
            }
        }
        return results
    }

    /**
     * 释放检测器资源
     */
    override fun close() {
        interpreter.close()
    }
}

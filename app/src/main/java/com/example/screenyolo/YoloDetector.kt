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
 * YOLO 目标检测器
 * 支持自定义类别过滤，只检测用户感兴趣的物体
 */
class YoloDetector(context: Context, modelPath: String) {

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

    private val interpreter: Interpreter
    private val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
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
        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
    }

    /**
     * 设置要检测的类别列表
     * @param classes 要检测的类别名称集合，如果为空则检测所有类别
     */
    fun setEnabledClasses(classes: Set<String>) {
        enabledClasses = classes
    }

    /**
     * 获取当前启用的类别列表
     */
    fun getEnabledClasses(): Set<String> = enabledClasses

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
    fun detect(bitmap: Bitmap): List<Detection> {
        val start = SystemClock.elapsedRealtime()

        // 将输入图像缩放到模型要求的 640x640
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        scaled.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

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
                scores = FloatArray(NUM_CLASSES) { c -> output[0][4 + c][i] }
            } else {
                cx = output[0][i][0]
                cy = output[0][i][1]
                w  = output[0][i][2]
                h  = output[0][i][3]
                scores = FloatArray(NUM_CLASSES) { c -> output[0][i][4 + c] }
            }

            var maxScore = 0f
            var classId = 0
            for (c in 0 until NUM_CLASSES) {
                if (scores[c] > maxScore) {
                    maxScore = scores[c]
                    classId = c
                }
            }

            if (maxScore > CONF_THRESHOLD) {
                val label = LABELS[classId]
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

    private fun nms(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }
        val picked = mutableListOf<Detection>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            picked.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i], sorted[j]) > IOU_THRESHOLD) {
                    suppressed[j] = true
                }
            }
        }
        return picked
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.x1, b.x1)
        val y1 = maxOf(a.y1, b.y1)
        val x2 = minOf(a.x2, b.x2)
        val y2 = minOf(a.y2, b.y2)
        val interArea = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        return interArea / (areaA + areaB - interArea + 1e-6f)
    }

    fun close() {
        interpreter.close()
    }
}

package com.example.screenyolo.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.screenyolo.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TFLiteEngine(
    modelPath: String,
    private val isQuantized: Boolean = false
) : InferenceEngine {

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

    override val name: String = if (isQuantized) "TFLite INT8" else "TFLite FP32"
    override val inputSize: Int = INPUT_SIZE

    private val interpreter: Interpreter
    private val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputBuffer: ByteBuffer

    init {
        val options = Interpreter.Options().apply {
            numThreads = 4
            if (!isQuantized && CompatibilityList().isDelegateSupportedOnThisDevice) {
                addDelegate(GpuDelegate())
            }
        }
        interpreter = Interpreter(File(modelPath), options)
        val bytesPerPixel = if (isQuantized) 1 else 4
        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * bytesPerPixel)
        inputBuffer.order(ByteOrder.nativeOrder())
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val start = SystemClock.elapsedRealtime()

        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        scaled.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        inputBuffer.rewind()
        if (isQuantized) {
            for (pixelValue in intValues) {
                inputBuffer.put((pixelValue shr 16 and 0xFF).toByte())
                inputBuffer.put((pixelValue shr 8 and 0xFF).toByte())
                inputBuffer.put((pixelValue and 0xFF).toByte())
            }
        } else {
            for (pixelValue in intValues) {
                inputBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)
                inputBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)
                inputBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
            }
        }

        val outputShape = interpreter.getOutputTensor(0).shape()
        val detections = if (isQuantized) {
            runQuantized(outputShape)
        } else {
            runFloat(outputShape)
        }

        val cost = SystemClock.elapsedRealtime() - start
        android.util.Log.d("TFLiteEngine", "[$name] Inference cost: ${cost}ms")
        return detections
    }

    private fun runFloat(shape: IntArray): List<Detection> {
        val output = Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
        interpreter.run(inputBuffer, output)
        return parseAndNms(output, shape)
    }

    private fun runQuantized(shape: IntArray): List<Detection> {
        // INT8 model output is typically [1, 84, 8400] with uint8 data
        val outputBuffer = ByteBuffer.allocateDirect(
            shape[0] * shape[1] * shape[2] * 1
        ).order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        // Read quantization params from output tensor
        val outputTensor = interpreter.getOutputTensor(0)
        val quantParams = outputTensor.quantizationParams()
        val scale = quantParams.scale
        val zeroPoint = quantParams.zeroPoint

        val isTransposed = shape[1] == 84 && shape[2] == 8400
        val numAnchors = if (isTransposed) shape[2] else shape[1]

        val results = mutableListOf<Detection>()
        for (i in 0 until numAnchors) {
            fun getVal(idx: Int): Float {
                val raw = outputBuffer.get(idx).toInt() and 0xFF
                return (raw - zeroPoint) * scale
            }

            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            val scores: FloatArray

            if (isTransposed) {
                val base = i
                cx = getVal(base)
                cy = getVal(base + numAnchors)
                w = getVal(base + numAnchors * 2)
                h = getVal(base + numAnchors * 3)
                scores = FloatArray(NUM_CLASSES) { c ->
                    getVal(base + numAnchors * (4 + c))
                }
            } else {
                val base = i * shape[2]
                cx = getVal(base)
                cy = getVal(base + 1)
                w = getVal(base + 2)
                h = getVal(base + 3)
                scores = FloatArray(NUM_CLASSES) { c ->
                    getVal(base + 4 + c)
                }
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
                val x1 = cx - w / 2f
                val y1 = cy - h / 2f
                val x2 = cx + w / 2f
                val y2 = cy + h / 2f
                results.add(Detection(x1, y1, x2, y2, maxScore, classId, LABELS[classId]))
            }
        }
        return nms(results)
    }

    private fun parseAndNms(output: Array<Array<FloatArray>>, shape: IntArray): List<Detection> {
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
                w = output[0][2][i]
                h = output[0][3][i]
                scores = FloatArray(NUM_CLASSES) { c -> output[0][4 + c][i] }
            } else {
                cx = output[0][i][0]
                cy = output[0][i][1]
                w = output[0][i][2]
                h = output[0][i][3]
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
                val x1 = cx - w / 2f
                val y1 = cy - h / 2f
                val x2 = cx + w / 2f
                val y2 = cy + h / 2f
                results.add(Detection(x1, y1, x2, y2, maxScore, classId, LABELS[classId]))
            }
        }
        return nms(results)
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

    override fun close() {
        interpreter.close()
    }
}

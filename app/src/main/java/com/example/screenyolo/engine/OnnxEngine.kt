package com.example.screenyolo.engine

import android.graphics.Bitmap
import android.os.SystemClock
import ai.onnxruntime.*
import com.example.screenyolo.Detection
import java.nio.FloatBuffer

class OnnxEngine(modelPath: String) : InferenceEngine {

    companion object {
        const val INPUT_SIZE = 640
        const val NUM_CLASSES = 80
        const val CONF_THRESHOLD = 0.25f
        const val IOU_THRESHOLD = 0.45f

        val LABELS = TFLiteEngine.LABELS
    }

    override val name: String = "ONNX Runtime"
    override val inputSize: Int = INPUT_SIZE

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            // Try NNAPI delegate on Android
            try {
                addNnapi()
            } catch (_: Exception) {
                // NNAPI not available
            }
        }
        session = env.createSession(modelPath, opts)
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val start = SystemClock.elapsedRealtime()

        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // ONNX YOLOv8 input: NCHW [1, 3, 640, 640]
        val floatBuffer = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = pixels[y * INPUT_SIZE + x]
                floatBuffer.put(((pixel shr 16) and 0xFF) / 255.0f)
            }
        }
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = pixels[y * INPUT_SIZE + x]
                floatBuffer.put(((pixel shr 8) and 0xFF) / 255.0f)
            }
        }
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = pixels[y * INPUT_SIZE + x]
                floatBuffer.put((pixel and 0xFF) / 255.0f)
            }
        }
        floatBuffer.rewind()

        val inputShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, inputShape)

        val inputName = session.inputNames.iterator().next()
        val results = session.run(mapOf(inputName to inputTensor))

        val outputTensor = results.get(0)
        @Suppress("UNCHECKED_CAST")
        val outputArray = outputTensor.value as Array<Array<FloatArray>>

        val detections = parseAndNms(outputArray)

        inputTensor.close()
        outputTensor.close()
        results.close()

        val cost = SystemClock.elapsedRealtime() - start
        android.util.Log.d("OnnxEngine", "[$name] Inference cost: ${cost}ms")
        return detections
    }

    private fun parseAndNms(output: Array<Array<FloatArray>>): List<Detection> {
        // ONNX YOLOv8 output: [1, 84, 8400]
        val shape = arrayOf(output.size, output[0].size, output[0][0].size)
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
        session.close()
        env.close()
    }
}

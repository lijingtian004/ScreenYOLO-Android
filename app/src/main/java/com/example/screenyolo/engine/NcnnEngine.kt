package com.example.screenyolo.engine

import android.graphics.Bitmap
import android.os.SystemClock
import com.example.screenyolo.Detection
import com.example.screenyolo.LogManager

class NcnnEngine(paramPath: String, binPath: String, userInputSize: Int = -1) : InferenceEngine {

    companion object {
        const val NUM_CLASSES = 80
        const val CONF_THRESHOLD = 0.25f
        const val IOU_THRESHOLD = 0.45f

        val LABELS = TFLiteEngine.LABELS

        init {
            System.loadLibrary("yolov8ncnn")
        }
    }

    override val name: String = "NCNN"
    override val inputSize: Int

    private external fun nativeLoadModel(paramPath: String, binPath: String, inputSize: Int): Boolean
    private external fun nativeDetect(bitmap: Bitmap, inputSize: Int): Array<Detection>

    init {
        // Determine input size: user preference overrides auto-detect
        inputSize = if (userInputSize > 0) {
            userInputSize
        } else {
            readInputSizeFromParam(paramPath)
        }
        val success = nativeLoadModel(paramPath, binPath, inputSize)
        if (!success) {
            throw RuntimeException("Failed to load NCNN model: $paramPath + $binPath")
        }
    }

    private fun readInputSizeFromParam(paramPath: String): Int {
        // Parse param file to find input shape
        // Look for line like "Input            in0                      1 1 in0 0=640 1=640 2=3"
        val file = java.io.File(paramPath)
        if (!file.exists()) return 640
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("Input") || trimmed.startsWith("Convolution") || trimmed.startsWith("Split")) {
                // Find shape hints
            }
            // Look for shape definition: "0=XXX 1=XXX" where XXX is the input size
            val match = Regex("""0=(\d+)\s+1=\1""").find(trimmed)
            if (match != null) {
                val size = match.groupValues[1].toIntOrNull()
                if (size != null && size >= 64 && size <= 2048) {
                    return size
                }
            }
        }
        return 640
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val start = SystemClock.elapsedRealtime()
        val results = nativeDetect(bitmap, inputSize)
        val cost = SystemClock.elapsedRealtime() - start
        android.util.Log.d("NcnnEngine", "[$name] Inference cost: ${cost}ms, inputSize=$inputSize")
        return results.toList()
    }

    override fun close() {
        // ncnn net clear is handled when loading new model
    }
}

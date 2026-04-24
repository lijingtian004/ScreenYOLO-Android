package com.example.screenyolo

import android.content.Context
import android.graphics.Bitmap
import com.example.screenyolo.engine.*
import java.io.File

private fun findFileRecursive(dir: File, suffix: String): File? {
    if (!dir.exists() || !dir.isDirectory) return null
    dir.listFiles()?.forEach { file ->
        if (file.isFile && file.name.endsWith(suffix, ignoreCase = true)) {
            return file
        }
        if (file.isDirectory) {
            val found = findFileRecursive(file, suffix)
            if (found != null) return found
        }
    }
    return null
}

class YoloDetector(context: Context, engineType: EngineType) {

    private val engine: InferenceEngine

    init {
        engine = when (engineType) {
            EngineType.TFLITE_FP32 -> {
                val f = File(context.filesDir, "custom_model")
                if (!f.exists()) throw IllegalStateException("Model file not found. Please import a model first.")
                TFLiteEngine(f.absolutePath, isQuantized = false)
            }
            EngineType.TFLITE_INT8 -> {
                val f = File(context.filesDir, "custom_model")
                if (!f.exists()) throw IllegalStateException("Model file not found. Please import a model first.")
                TFLiteEngine(f.absolutePath, isQuantized = true)
            }
            EngineType.ONNX -> {
                val f = File(context.filesDir, "custom_model")
                if (!f.exists()) throw IllegalStateException("Model file not found. Please import a model first.")
                OnnxEngine(f.absolutePath)
            }
            EngineType.NCNN -> {
                val dir = File(context.filesDir, "ncnn_model")
                val param = findFileRecursive(dir, ".param")
                val bin = findFileRecursive(dir, ".bin")
                if (param == null || bin == null) {
                    throw IllegalStateException("NCNN model not found. Please import a .zip containing .param and .bin files.")
                }
                NcnnEngine(param.absolutePath, bin.absolutePath)
            }
        }
    }

    val engineName: String get() = engine.name
    val inputSize: Int get() = engine.inputSize

    fun detect(bitmap: Bitmap): List<Detection> = engine.detect(bitmap)

    fun close() = engine.close()
}

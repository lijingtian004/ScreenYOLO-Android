package com.example.screenyolo

import android.content.Context
import android.graphics.Bitmap
import com.example.screenyolo.engine.*
import java.io.File

class YoloDetector(context: Context, engineType: EngineType) {

    private val engine: InferenceEngine

    init {
        val modelFile = File(context.filesDir, "custom_model")
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found. Please import a model first.")
        }
        engine = when (engineType) {
            EngineType.TFLITE_FP32 -> TFLiteEngine(modelFile.absolutePath, isQuantized = false)
            EngineType.TFLITE_INT8 -> TFLiteEngine(modelFile.absolutePath, isQuantized = true)
            EngineType.ONNX -> OnnxEngine(modelFile.absolutePath)
        }
    }

    val engineName: String get() = engine.name
    val inputSize: Int get() = engine.inputSize

    fun detect(bitmap: Bitmap): List<Detection> = engine.detect(bitmap)

    fun close() = engine.close()
}

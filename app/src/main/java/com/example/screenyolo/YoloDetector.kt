package com.example.screenyolo

import android.content.Context
import android.graphics.Bitmap
import com.example.screenyolo.engine.*
import java.io.File

class YoloDetector(context: Context, engineType: EngineType) {

    private val engine: InferenceEngine
    private val modelDir = File(context.filesDir, "models")

    init {
        if (!modelDir.exists()) modelDir.mkdirs()
        val modelFile = File(modelDir, engineType.modelFile)
        if (!modelFile.exists()) {
            // Copy from assets
            context.assets.open(engineType.modelFile).use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        engine = when (engineType) {
            EngineType.TFLITE_FP32 -> TFLiteEngine(modelFile.absolutePath, isQuantized = false)
            EngineType.TFLITE_INT8 -> TFLiteEngine(modelFile.absolutePath, isQuantized = true)
            EngineType.ONNX -> OnnxEngine(modelFile.absolutePath)
        }
    }

    val engineName: String get() = engine.name

    fun detect(bitmap: Bitmap): List<Detection> = engine.detect(bitmap)

    fun close() = engine.close()
}

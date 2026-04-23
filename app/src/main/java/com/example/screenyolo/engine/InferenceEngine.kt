package com.example.screenyolo.engine

import android.graphics.Bitmap
import com.example.screenyolo.Detection

interface InferenceEngine {
    val name: String
    val inputSize: Int
    fun detect(bitmap: Bitmap): List<Detection>
    fun close()
}

enum class EngineType(val displayName: String) {
    TFLITE_FP32("TFLite FP32"),
    TFLITE_INT8("TFLite INT8"),
    ONNX("ONNX Runtime");

    companion object {
        fun fromOrdinal(ordinal: Int): EngineType = entries.getOrElse(ordinal) { TFLITE_FP32 }
    }
}

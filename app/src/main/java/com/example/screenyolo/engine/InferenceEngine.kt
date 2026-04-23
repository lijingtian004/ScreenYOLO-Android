package com.example.screenyolo.engine

import android.graphics.Bitmap
import com.example.screenyolo.Detection

interface InferenceEngine {
    val name: String
    val inputSize: Int
    fun detect(bitmap: Bitmap): List<Detection>
    fun close()
}

enum class EngineType(val displayName: String, val modelFile: String) {
    TFLITE_FP32("TFLite FP32", "yolov8n_float32.tflite"),
    TFLITE_INT8("TFLite INT8", "yolov8n_full_integer_quant.tflite"),
    ONNX("ONNX Runtime", "yolov8n.onnx");

    companion object {
        fun fromOrdinal(ordinal: Int): EngineType = entries.getOrElse(ordinal) { TFLITE_FP32 }
    }
}

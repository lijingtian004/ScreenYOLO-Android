package com.example.screenyolo

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock

/**
 * ncnn YOLO 目标检测器（占位实现）
 *
 * 注意：ncnn 官方未提供 Maven Central 依赖，需要通过以下方式之一集成：
 * 1. 下载 ncnn-android 预编译库，通过 CMake/NDK 集成
 * 2. 使用第三方封装的 ncnn Android AAR
 *
 * 当前实现为占位符，加载模型时会抛出异常提示用户。
 * 如需完整 ncnn 支持，请参考：
 * - https://github.com/Tencent/ncnn/releases
 * - https://github.com/Tencent/ncnn/wiki/how-to-build#build-for-android
 */
class NcnnDetector(
    context: Context,
    private val paramPath: String,
    private val binPath: String
) : Detector {

    // 类别过滤集合：如果为空则检测所有类别
    private var enabledClasses: Set<String> = emptySet()

    init {
        // 检查 ncnn 是否可用
        val isAvailable = try {
            Class.forName("com.tencent.ncnn.Ncnn")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

        if (!isAvailable) {
            throw UnsupportedOperationException(
                "ncnn 库未集成。请通过以下方式之一集成 ncnn：\n" +
                "1. 下载 ncnn-android 预编译库（https://github.com/Tencent/ncnn/releases）\n" +
                "2. 在 app/build.gradle 中添加本地 AAR 依赖\n" +
                "3. 使用 TFLite 模型替代（推荐）"
            )
        }

        // 如果 ncnn 可用，这里应该加载模型
        // 完整的 ncnn 集成需要 JNI 层调用 C++ API
        throw UnsupportedOperationException(
            "ncnn Java API 需要额外集成。请使用 TFLite 模型，或参考项目文档集成 ncnn。"
        )
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
     * 对输入的 Bitmap 进行 YOLO 目标检测
     * @param bitmap 屏幕截图的 Bitmap
     * @return 检测到的目标列表
     */
    override fun detect(bitmap: Bitmap): List<Detection> {
        // 占位实现：返回空列表
        return emptyList()
    }

    /**
     * 释放检测器资源
     */
    override fun close() {
        // 占位实现
    }
}

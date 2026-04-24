package com.example.screenyolo

import java.io.Serializable

/**
 * 检测结果数据类
 * @param x1 检测框左上角 x 坐标
 * @param y1 检测框左上角 y 坐标
 * @param x2 检测框右下角 x 坐标
 * @param y2 检测框右下角 y 坐标
 * @param confidence 置信度 (0.0 ~ 1.0)
 * @param classId 类别 ID
 * @param label 类别名称
 */
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val classId: Int,
    val label: String
) : Serializable

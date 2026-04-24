package com.example.screenyolo

import android.os.Parcel
import android.os.Parcelable

/**
 * 自定义截图区域配置
 * 支持预设尺寸和自定义像素尺寸
 */
data class CaptureRegion(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val enabled: Boolean = false
) : Parcelable {

    constructor(parcel: Parcel) : this(
        x = parcel.readInt(),
        y = parcel.readInt(),
        width = parcel.readInt(),
        height = parcel.readInt(),
        enabled = parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(x)
        parcel.writeInt(y)
        parcel.writeInt(width)
        parcel.writeInt(height)
        parcel.writeByte(if (enabled) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CaptureRegion> {
        override fun createFromParcel(parcel: Parcel): CaptureRegion {
            return CaptureRegion(parcel)
        }

        override fun newArray(size: Int): Array<CaptureRegion?> {
            return arrayOfNulls(size)
        }

        // 预设尺寸选项
        val PRESETS = listOf(
            Preset("全屏 (默认)", 0, 0, 0, 0, false),
            Preset("中央区域 (50%)", 25, 25, 50, 50),
            Preset("中央区域 (75%)", 12, 12, 75, 75),
            Preset("上半部分", 0, 0, 100, 50),
            Preset("下半部分", 0, 50, 100, 50),
            Preset("左侧区域", 0, 0, 50, 100),
            Preset("右侧区域", 50, 0, 50, 100),
            Preset("左上角", 0, 0, 50, 50),
            Preset("右上角", 50, 0, 50, 50),
            Preset("左下角", 0, 50, 50, 50),
            Preset("右下角", 50, 50, 50, 50)
        )

        /**
         * 根据屏幕尺寸和预设计算实际像素区域
         */
        fun fromPreset(preset: Preset, screenWidth: Int, screenHeight: Int): CaptureRegion {
            if (!preset.enabled) {
                return CaptureRegion(0, 0, screenWidth, screenHeight, false)
            }
            val x = (screenWidth * preset.xPercent / 100)
            val y = (screenHeight * preset.yPercent / 100)
            val w = (screenWidth * preset.widthPercent / 100)
            val h = (screenHeight * preset.heightPercent / 100)
            return CaptureRegion(x, y, w, h, true)
        }
    }

    /**
     * 预设尺寸数据类
     */
    data class Preset(
        val name: String,
        val xPercent: Int,
        val yPercent: Int,
        val widthPercent: Int,
        val heightPercent: Int,
        val enabled: Boolean = true
    )
}

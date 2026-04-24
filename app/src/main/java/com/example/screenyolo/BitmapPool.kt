package com.example.screenyolo

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Bitmap 对象池
 * 用于复用 Bitmap 对象，减少内存分配和 GC 压力
 * 特别适用于频繁创建/销毁 Bitmap 的屏幕捕获场景
 */
object BitmapPool {

    // 最大缓存大小（以 Bitmap 数量计）
    private const val MAX_POOL_SIZE = 6

    // 使用 LruCache 管理 Bitmap 复用
    private val pool = object : LruCache<String, Bitmap>(MAX_POOL_SIZE) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    // 记录池化统计
    private var hitCount = 0
    private var missCount = 0
    private var recycleCount = 0

    /**
     * 获取或创建指定尺寸的 Bitmap
     * @param width 宽度
     * @param height 高度
     * @param config Bitmap 配置
     * @return 可用的 Bitmap 实例
     */
    fun get(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val key = "$width x $height"
        val cached = pool.get(key)

        return if (cached != null && !cached.isRecycled) {
            hitCount++
            cached
        } else {
            missCount++
            Bitmap.createBitmap(width, height, config)
        }
    }

    /**
     * 将 Bitmap 回收到对象池以便复用
     * @param bitmap 要回收的 Bitmap
     */
    fun recycle(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return

        val key = "${bitmap.width} x ${bitmap.height}"
        // 检查池中是否已有相同尺寸的 Bitmap
        if (pool.get(key) == null) {
            pool.put(key, bitmap)
            recycleCount++
        } else {
            // 池已满，直接回收
            bitmap.recycle()
        }
    }

    /**
     * 清空对象池并回收所有 Bitmap
     */
    fun clear() {
        pool.evictAll()
        hitCount = 0
        missCount = 0
        recycleCount = 0
    }

    /**
     * 获取池化统计信息
     */
    fun getStats(): PoolStats {
        return PoolStats(
            hitCount = hitCount,
            missCount = missCount,
            recycleCount = recycleCount,
            poolSize = pool.size(),
            hitRate = if (hitCount + missCount > 0) hitCount.toFloat() / (hitCount + missCount) else 0f
        )
    }

    /**
     * 池化统计数据类
     */
    data class PoolStats(
        val hitCount: Int,
        val missCount: Int,
        val recycleCount: Int,
        val poolSize: Int,
        val hitRate: Float
    ) {
        override fun toString(): String {
            return "BitmapPool[命中: $hitCount, 未命中: $missCount, 回收: $recycleCount, " +
                    "池大小: $poolSize, 命中率: ${(hitRate * 100).toInt()}%]"
        }
    }
}

package com.zyyme.workdayalarmclock.camera

import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 静止检测（帧差法）：
 * 每 [SAMPLE_INTERVAL_MS] 从分析流（YUV_420_888，直接使用 Y 平面即灰度图）
 * 取一帧，与上一帧逐像素差分，差异像素占比超过阈值判为"变动"。
 * 两种判定取或：① 差异像素占比 ≥ 阈值（整体活动）；
 * ② 少量像素出现强差异（远处/小目标、局部动作，如抬手、探头）。
 * 防抖不对称：进入"变动"需连续 [DEBOUNCE_TO_MOVING] 次（快），
 * 回到"静止"需连续 [DEBOUNCE_TO_STATIC] 次（慢），防灯光频闪误触发。
 * 小分辨率（320x240）+ 每秒 2 次，CPU 占用可忽略。
 */
internal class MotionDetector(
    private val reader: ImageReader,
    private val thresholdRatio: Double,
    private val onStateChanged: (moving: Boolean) -> Unit
) {
    companion object {
        private const val SAMPLE_INTERVAL_MS = 500L
        private const val DEBOUNCE_TO_MOVING = 3
        private const val DEBOUNCE_TO_STATIC = 12
        private const val PIXEL_DIFF_THRESHOLD = 10
        private const val STRONG_DIFF_THRESHOLD = 50
        private const val STRONG_PIXEL_MIN = 24
    }

    @Volatile var isMoving: Boolean = true
        private set

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var prevY: ByteArray? = null
    private var prevWidth = 0
    private var prevHeight = 0
    private var streak = 0
    private var pendingMoving = true

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "motion-detector").apply { start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        prevY = null
    }

    private fun loop() {
        while (running.get()) {
            val deadline = SystemClock.elapsedRealtime() + SAMPLE_INTERVAL_MS
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        process(image)
                    } finally {
                        image.close()
                    }
                }
            } catch (_: Exception) {
            }
            val remain = deadline - SystemClock.elapsedRealtime()
            if (remain > 0) {
                try {
                    Thread.sleep(remain)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun process(image: Image) {
        val plane = image.planes.firstOrNull() ?: return
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        buffer.rewind()
        val current = ByteArray(width * height)
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                current[row * width + col] = buffer.get(rowStart + col)
            }
        }

        val previous = prevY
        if (previous != null && prevWidth == width && prevHeight == height) {
            var diff = 0
            var strong = 0
            for (i in 0 until current.size) {
                val delta = (current[i].toInt() and 0xff) - (previous[i].toInt() and 0xff)
                if (delta > PIXEL_DIFF_THRESHOLD || delta < -PIXEL_DIFF_THRESHOLD) diff++
                if (delta > STRONG_DIFF_THRESHOLD || delta < -STRONG_DIFF_THRESHOLD) strong++
            }
            val ratio = diff.toDouble() / current.size
            // 少量强差异也算动态（远处人物、局部手势等小目标）
            val sampleMoving = ratio >= thresholdRatio || strong >= STRONG_PIXEL_MIN
            if (sampleMoving == pendingMoving) {
                streak++
            } else {
                pendingMoving = sampleMoving
                streak = 1
            }
            val required = if (sampleMoving) DEBOUNCE_TO_MOVING else DEBOUNCE_TO_STATIC
            if (streak >= required && sampleMoving != isMoving) {
                isMoving = sampleMoving
                try {
                    onStateChanged(sampleMoving)
                } catch (_: Exception) {
                }
            }
        }
        prevY = current
        prevWidth = width
        prevHeight = height
    }
}

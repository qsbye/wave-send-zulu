package com.airscan.qr.camera

import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitArray
import com.google.zxing.common.BitMatrix
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sauvola 局部自适应阈值二值化（CPU 实现，积分图加速）。
 * 参考《折腾笔记[47]-cuda的图像动态阈值二值化》：
 *   T = mean * (1 + k * (std / R - 1))
 * 相比 ZXing 自带的 HybridBinarizer（8px 分块），Sauvola 使用大窗口
 * （默认半径 25，约覆盖 5 个模块）估计局部均值与标准差，
 * 对"屏幕拍摄"特有的不平整光照（眩光、暗角、摩尔纹）鲁棒得多。
 *
 * 性能：积分图两次遍历 + 每像素 O(1) 窗口统计，1456x1456 约 50-80ms，
 * 仅作为 Hybrid/Global 兜底失败后的第三级候选。
 *
 * @param radius 窗口半径（实际窗口 2r+1）；推荐 15-25，阴影重的场景取大值
 * @param k      敏感参数（0.1-0.5），越小对阴影边缘越宽容
 */
class SauvolaBinarizer(
    src: LuminanceSource,
    private val radius: Int = 25,
    private val k: Double = 0.15,
) : Binarizer(src) {

    private val lumSource: LuminanceSource = src

    override fun getBlackRow(y: Int, row: BitArray?): BitArray {
        val m = getBlackMatrix()
        val width = lumSource.width
        val result = row ?: BitArray(width)
        result.clear()
        for (x in 0 until width) if (m.get(x, y)) result.set(x)
        return result
    }

    override fun getBlackMatrix(): BitMatrix {
        val w = lumSource.width
        val h = lumSource.height
        val lum = lumSource.matrix
        // 积分图（含首行/列零填充），Long 累加避免浮点误差
        val iw = w + 1
        val sum = LongArray(iw * (h + 1))
        val sumSq = LongArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            var rowSumSq = 0L
            val above = y * iw
            val below = (y + 1) * iw
            for (x in 0 until w) {
                val v = lum[y * w + x].toInt() and 0xFF
                rowSum += v
                rowSumSq += v.toLong() * v
                sum[below + x + 1] = sum[above + x + 1] + rowSum
                sumSq[below + x + 1] = sumSq[above + x + 1] + rowSumSq
            }
        }
        val matrix = BitMatrix(w, h)
        val r = radius
        val rNorm = 127.5 // 0-255 灰度动态范围的一半（R=0.5 归一化等价）
        for (y in 0 until h) {
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r).coerceAtMost(h - 1)
            val rowTop = y0 * iw
            val rowBottom = (y1 + 1) * iw
            val rowOff = y * w
            for (x in 0 until w) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(w - 1)
                val area = (y1 - y0 + 1).toLong() * (x1 - x0 + 1)
                val s = rectSum(sum, iw, rowTop, rowBottom, x0, x1)
                val sq = rectSum(sumSq, iw, rowTop, rowBottom, x0, x1)
                val mean = s.toDouble() / area
                val variance = sq.toDouble() / area - mean * mean
                val std = sqrt(max(0.0, variance))
                val t = mean * (1.0 + k * (std / rNorm - 1.0))
                if ((lum[rowOff + x].toInt() and 0xFF) < t) matrix.set(x, y)
            }
        }
        return matrix
    }

    override fun createBinarizer(source: LuminanceSource): Binarizer {
        return SauvolaBinarizer(source, radius, k)
    }

    private inline fun rectSum(
        integral: LongArray, iw: Int, rowTop: Int, rowBottom: Int, x0: Int, x1: Int,
    ): Long {
        return integral[rowBottom + x1 + 1] - integral[rowTop + x1 + 1] -
            integral[rowBottom + x0] + integral[rowTop + x0]
    }

    companion object {
        /** 便捷入口：直接生成二值化位图。 */
        fun binarize(source: LuminanceSource, radius: Int = 25, k: Double = 0.15): BinaryBitmap {
            return BinaryBitmap(SauvolaBinarizer(source, radius, k))
        }
    }
}

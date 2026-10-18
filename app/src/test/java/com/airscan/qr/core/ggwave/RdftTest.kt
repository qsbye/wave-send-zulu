package com.airscan.qr.core.ggwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * Ooura rdft 正变换的输出布局：
 *   a[0]   = R[0]（直流）
 *   a[1]   = R[n/2]（奈奎斯特）
 *   a[2k]  = R[k], a[2k+1] = I[k] (k = 1 .. n/2-1)
 * 其中 I[k] = +Σ x[j]·sin(2πjk/n)（Ooura 约定，与 e^{-iθ} 标准约定虚部反号；
 * ggwave 仅使用功率谱 re²+im²，符号约定不影响解调）。
 */
class RdftTest {

    private val n = 1024

    @Test
    fun dcSignalAllEnergyInBin0() {
        val a = FloatArray(n) { 1.0f }
        Rdft(n).rdft(a)
        assertEquals(n.toFloat(), a[0], n * 1e-3f)
        for (k in 1 until n / 2) {
            val re = a[2 * k]
            val im = a[2 * k + 1]
            assertTrue("bin $k re", kotlin.math.abs(re) < 1e-2f)
            assertTrue("bin $k im", kotlin.math.abs(im) < 1e-2f)
        }
    }

    @Test
    fun cosineHasSinglePeakAtItsBin() {
        val k0 = 40 // ggwave audible 协议的 freqStart
        val a = FloatArray(n) { i -> cos(2.0 * Math.PI * k0 * i / n).toFloat() }
        Rdft(n).rdft(a)
        // cos 的 DFT：R[k0] = n/2，其余 ≈ 0
        assertEquals(n / 2.0f, a[2 * k0], n * 1e-3f)
        assertEquals(0.0f, a[2 * k0 + 1], 1e-1f)
        // 其余 bin 能量应远小于峰值
        var maxOther = 0.0f
        for (k in 1 until n / 2) {
            if (k == k0) continue
            val re = a[2 * k]
            val im = a[2 * k + 1]
            maxOther = maxOf(maxOther, kotlin.math.abs(re), kotlin.math.abs(im))
        }
        assertTrue("max other bin $maxOther", maxOther < n * 1e-2f)
    }

    @Test
    fun sineHasImaginaryPeakAtItsBin() {
        val k0 = 320 // ggwave ultrasound 协议的 freqStart
        val a = FloatArray(n) { i -> kotlin.math.sin(2.0 * Math.PI * k0 * i / n).toFloat() }
        Rdft(n).rdft(a)
        // Ooura 约定：I[k0] = +Σ sin = +n/2
        assertEquals(0.0f, a[2 * k0], 1e-1f)
        assertEquals(n / 2.0f, a[2 * k0 + 1], n * 1e-3f)
    }

    @Test
    fun magnitudeMatchesDftDefinition() {
        // 任意信号：抽查若干 bin，与直接按定义计算的 O(n^2) DFT 对比
        val rnd = java.util.Random(123)
        val x = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val a = x.copyOf()
        Rdft(n).rdft(a)
        for (k in listOf(1, 7, 100, 400)) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val ang = 2.0 * Math.PI * k * i / n
                re += x[i] * cos(ang)
                im += x[i] * kotlin.math.sin(ang)
            }
            assertEquals("re bin $k", re.toFloat(), a[2 * k], n * 1e-2f)
            assertEquals("im bin $k", im.toFloat(), a[2 * k + 1], n * 1e-2f)
        }
    }
}

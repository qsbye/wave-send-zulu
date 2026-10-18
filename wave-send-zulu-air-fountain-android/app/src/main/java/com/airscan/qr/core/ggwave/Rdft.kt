package com.airscan.qr.core.ggwave

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/**
 * 实数离散傅里叶变换（RDFT），移植自 Ooura fft（ggwave src/fft.h）。
 *
 * ggwave 只使用正向变换 rdft(n, +1, ...)，因此这里仅移植正变换路径
 * （bitrv2 / cftfsub / rftfsub 及表初始化）。输出布局与 C 版一致：
 *   a[2k]   = R[k]  (0 <= k <  n/2)
 *   a[2k+1] = I[k]  (0 <  k <  n/2)
 *   a[1]    = R[n/2]
 *
 * 工作表按 (samplesPerFrame) 惰性初始化并缓存，与 C 版 ip/w 语义相同。
 */
internal class Rdft(private val n: Int) {

    // ip[0],ip[1] 是表指针（nw, nc），ip[2...] 为位反转表
    private val ip = IntArray(2 + (2 + Math.sqrt((n / 2).toDouble()).toInt()))
    private val w = FloatArray(n / 2)

    init {
        ip[0] = 0
    }

    /** 正向 RDFT，就地计算。 */
    fun rdft(a: FloatArray) {
        var nw = ip[0]
        if (n > (nw shl 2)) {
            nw = n shr 2
            makewt(nw, ip, w)
        }
        var nc = ip[1]
        if (n > (nc shl 2)) {
            nc = n shr 2
            makect(nc, ip, w, nw)
        }
        if (n > 4) {
            bitrv2(n, ip, 2, a)
            cftfsub(n, a, w)
            rftfsub(n, a, nc, w, nw)
        } else if (n == 4) {
            cftfsub(n, a, w)
        }
        val xi = a[0] - a[1]
        a[0] += a[1]
        a[1] = xi
    }

    // ---- 表初始化 ----

    private fun makewt(nw: Int, ip: IntArray, w: FloatArray) {
        ip[0] = nw
        ip[1] = 1
        if (nw > 2) {
            val nwh = nw shr 1
            val delta = atan(1.0f) / nwh
            w[0] = 1f
            w[1] = 0f
            w[nwh] = cos(delta * nwh)
            w[nwh + 1] = w[nwh]
            if (nwh > 2) {
                var j = 2
                while (j < nwh) {
                    val x = cos(delta * j)
                    val y = sin(delta * j)
                    w[j] = x
                    w[j + 1] = y
                    w[nw - j] = y
                    w[nw - j + 1] = x
                    j += 2
                }
                bitrv2(nw, ip, 2, w)
            }
        }
    }

    private fun makect(nc: Int, ip: IntArray, c: FloatArray, cOffset: Int) {
        ip[1] = nc
        if (nc > 1) {
            val nch = nc shr 1
            val delta = atan(1.0f) / nch
            c[cOffset] = cos(delta * nch)
            c[cOffset + nch] = 0.5f * c[cOffset]
            for (j in 1 until nch) {
                c[cOffset + j] = 0.5f * cos(delta * j)
                c[cOffset + nc - j] = 0.5f * sin(delta * j)
            }
        }
    }

    // ---- 位反转 ----

    private fun bitrv2(n: Int, ip: IntArray, ipOffset: Int, a: FloatArray) {
        var j: Int
        var j1: Int
        var k: Int
        var k1: Int
        var l = n
        var m = 1
        var m2: Int
        var xr: Float; var xi: Float; var yr: Float; var yi: Float

        ip[ipOffset + 0] = 0
        while ((m shl 3) < l) {
            l = l shr 1
            for (jj in 0 until m) {
                ip[ipOffset + m + jj] = ip[ipOffset + jj] + l
            }
            m = m shl 1
        }
        m2 = 2 * m
        if ((m shl 3) == l) {
            for (kk in 0 until m) {
                for (jj in 0 until kk) {
                    j1 = 2 * jj + ip[ipOffset + kk]
                    k1 = 2 * kk + ip[ipOffset + jj]
                    xr = a[j1]; xi = a[j1 + 1]; yr = a[k1]; yi = a[k1 + 1]
                    a[j1] = yr; a[j1 + 1] = yi; a[k1] = xr; a[k1 + 1] = xi
                    j1 += m2; k1 += 2 * m2
                    xr = a[j1]; xi = a[j1 + 1]; yr = a[k1]; yi = a[k1 + 1]
                    a[j1] = yr; a[j1 + 1] = yi; a[k1] = xr; a[k1 + 1] = xi
                    j1 += m2; k1 -= m2
                    xr = a[j1]; xi = a[j1 + 1]; yr = a[k1]; yi = a[k1 + 1]
                    a[j1] = yr; a[j1 + 1] = yi; a[k1] = xr; a[k1 + 1] = xi
                    j1 += m2; k1 += 2 * m2
                    xr = a[j1]; xi = a[j1 + 1]; yr = a[k1]; yi = a[k1 + 1]
                    a[j1] = yr; a[j1 + 1] = yi; a[k1] = xr; a[k1 + 1] = xi
                }
                j1 = 2 * kk + m2 + ip[ipOffset + kk]
                k1 = j1 + m2
                xr = a[j1]; xi = a[j1 + 1]; yr = a[k1]; yi = a[k1 + 1]
                a[j1] = yr; a[j1 + 1] = yi; a[k1] = xr; a[k1 + 1] = xi
            }
        } else {
            for (kk in 1 until m) {
                for (jj in 0 until kk) {
                    j1 = 2 * jj + ip[ipOffset + kk]
                    k1 = 2 * kk + ip[ipOffset + jj]
                    xr = a[j1]; xi = a[j1 + 1]; yr = a[k1]; yi = a[k1 + 1]
                    a[j1] = yr; a[j1 + 1] = yi; a[k1] = xr; a[k1 + 1] = xi
                    j1 += m2; k1 += m2
                    xr = a[j1]; xi = a[j1 + 1]; yr = a[k1]; yi = a[k1 + 1]
                    a[j1] = yr; a[j1 + 1] = yi; a[k1] = xr; a[k1 + 1] = xi
                }
            }
        }
    }

    // ---- 复数 FFT（频率抽取） ----

    private fun cftfsub(n: Int, a: FloatArray, w: FloatArray) {
        var l = 2
        if (n > 8) {
            cft1st(n, a, w)
            l = 8
            while ((l shl 2) < n) {
                cftmdl(n, l, a, w)
                l = l shl 2
            }
        }
        if ((l shl 2) == n) {
            var j = 0
            while (j < l) {
                val j1 = j + l
                val j2 = j1 + l
                val j3 = j2 + l
                val x0r = a[j] + a[j1]
                val x0i = a[j + 1] + a[j1 + 1]
                val x1r = a[j] - a[j1]
                val x1i = a[j + 1] - a[j1 + 1]
                val x2r = a[j2] + a[j3]
                val x2i = a[j2 + 1] + a[j3 + 1]
                val x3r = a[j2] - a[j3]
                val x3i = a[j2 + 1] - a[j3 + 1]
                a[j] = x0r + x2r
                a[j + 1] = x0i + x2i
                a[j2] = x0r - x2r
                a[j2 + 1] = x0i - x2i
                a[j1] = x1r - x3i
                a[j1 + 1] = x1i + x3r
                a[j3] = x1r + x3i
                a[j3 + 1] = x1i - x3r
                j += 2
            }
        } else {
            var j = 0
            while (j < l) {
                val j1 = j + l
                val x0r = a[j] - a[j1]
                val x0i = a[j + 1] - a[j1 + 1]
                a[j] += a[j1]
                a[j + 1] += a[j1 + 1]
                a[j1] = x0r
                a[j1 + 1] = x0i
                j += 2
            }
        }
    }

    private fun cft1st(n: Int, a: FloatArray, w: FloatArray) {
        var x0r = a[0] + a[2]
        var x0i = a[1] + a[3]
        var x1r = a[0] - a[2]
        var x1i = a[1] - a[3]
        var x2r = a[4] + a[6]
        var x2i = a[5] + a[7]
        var x3r = a[4] - a[6]
        var x3i = a[5] - a[7]
        a[0] = x0r + x2r
        a[1] = x0i + x2i
        a[4] = x0r - x2r
        a[5] = x0i - x2i
        a[2] = x1r - x3i
        a[3] = x1i + x3r
        a[6] = x1r + x3i
        a[7] = x1i - x3r
        var wk1r = w[2]
        x0r = a[8] + a[10]
        x0i = a[9] + a[11]
        x1r = a[8] - a[10]
        x1i = a[9] - a[11]
        x2r = a[12] + a[14]
        x2i = a[13] + a[15]
        x3r = a[12] - a[14]
        x3i = a[13] - a[15]
        a[8] = x0r + x2r
        a[9] = x0i + x2i
        a[12] = x2i - x0i
        a[13] = x0r - x2r
        x0r = x1r - x3i
        x0i = x1i + x3r
        a[10] = wk1r * (x0r - x0i)
        a[11] = wk1r * (x0r + x0i)
        x0r = x3i + x1r
        x0i = x3r - x1i
        a[14] = wk1r * (x0i - x0r)
        a[15] = wk1r * (x0i + x0r)
        var k1 = 0
        var j = 16
        while (j < n) {
            k1 += 2
            val k2 = 2 * k1
            val wk2r = w[k1]
            val wk2i = w[k1 + 1]
            wk1r = w[k2]
            var wk1i = w[k2 + 1]
            var wk3r = wk1r - 2 * wk2i * wk1i
            var wk3i = 2 * wk2i * wk1r - wk1i
            x0r = a[j] + a[j + 2]
            x0i = a[j + 1] + a[j + 3]
            x1r = a[j] - a[j + 2]
            x1i = a[j + 1] - a[j + 3]
            x2r = a[j + 4] + a[j + 6]
            x2i = a[j + 5] + a[j + 7]
            x3r = a[j + 4] - a[j + 6]
            x3i = a[j + 5] - a[j + 7]
            a[j] = x0r + x2r
            a[j + 1] = x0i + x2i
            x0r -= x2r
            x0i -= x2i
            a[j + 4] = wk2r * x0r - wk2i * x0i
            a[j + 5] = wk2r * x0i + wk2i * x0r
            x0r = x1r - x3i
            x0i = x1i + x3r
            a[j + 2] = wk1r * x0r - wk1i * x0i
            a[j + 3] = wk1r * x0i + wk1i * x0r
            x0r = x1r + x3i
            x0i = x1i - x3r
            a[j + 6] = wk3r * x0r - wk3i * x0i
            a[j + 7] = wk3r * x0i + wk3i * x0r
            wk1r = w[k2 + 2]
            wk1i = w[k2 + 3]
            wk3r = wk1r - 2 * wk2r * wk1i
            wk3i = 2 * wk2r * wk1r - wk1i
            x0r = a[j + 8] + a[j + 10]
            x0i = a[j + 9] + a[j + 11]
            x1r = a[j + 8] - a[j + 10]
            x1i = a[j + 9] - a[j + 11]
            x2r = a[j + 12] + a[j + 14]
            x2i = a[j + 13] + a[j + 15]
            x3r = a[j + 12] - a[j + 14]
            x3i = a[j + 13] - a[j + 15]
            a[j + 8] = x0r + x2r
            a[j + 9] = x0i + x2i
            x0r -= x2r
            x0i -= x2i
            a[j + 12] = -wk2i * x0r - wk2r * x0i
            a[j + 13] = -wk2i * x0i + wk2r * x0r
            x0r = x1r - x3i
            x0i = x1i + x3r
            a[j + 10] = wk1r * x0r - wk1i * x0i
            a[j + 11] = wk1r * x0i + wk1i * x0r
            x0r = x1r + x3i
            x0i = x1i - x3r
            a[j + 14] = wk3r * x0r - wk3i * x0i
            a[j + 15] = wk3r * x0i + wk3i * x0r
            j += 16
        }
    }

    private fun cftmdl(n: Int, l: Int, a: FloatArray, w: FloatArray) {
        val m = l shl 2
        var j = 0
        while (j < l) {
            val j1 = j + l
            val j2 = j1 + l
            val j3 = j2 + l
            val x0r = a[j] + a[j1]
            val x0i = a[j + 1] + a[j1 + 1]
            val x1r = a[j] - a[j1]
            val x1i = a[j + 1] - a[j1 + 1]
            val x2r = a[j2] + a[j3]
            val x2i = a[j2 + 1] + a[j3 + 1]
            val x3r = a[j2] - a[j3]
            val x3i = a[j2 + 1] - a[j3 + 1]
            a[j] = x0r + x2r
            a[j + 1] = x0i + x2i
            a[j2] = x0r - x2r
            a[j2 + 1] = x0i - x2i
            a[j1] = x1r - x3i
            a[j1 + 1] = x1i + x3r
            a[j3] = x1r + x3i
            a[j3 + 1] = x1i - x3r
            j += 2
        }
        var wk1r = w[2]
        j = m
        while (j < l + m) {
            val j1 = j + l
            val j2 = j1 + l
            val j3 = j2 + l
            val x0r = a[j] + a[j1]
            val x0i = a[j + 1] + a[j1 + 1]
            val x1r = a[j] - a[j1]
            val x1i = a[j + 1] - a[j1 + 1]
            val x2r = a[j2] + a[j3]
            val x2i = a[j2 + 1] + a[j3 + 1]
            val x3r = a[j2] - a[j3]
            val x3i = a[j2 + 1] - a[j3 + 1]
            a[j] = x0r + x2r
            a[j + 1] = x0i + x2i
            a[j2] = x2i - x0i
            a[j2 + 1] = x0r - x2r
            val x0r2 = x1r - x3i
            val x0i2 = x1i + x3r
            a[j1] = wk1r * (x0r2 - x0i2)
            a[j1 + 1] = wk1r * (x0r2 + x0i2)
            val x0r3 = x3i + x1r
            val x0i3 = x3r - x1i
            a[j3] = wk1r * (x0i3 - x0r3)
            a[j3 + 1] = wk1r * (x0i3 + x0r3)
            j += 2
        }
        var k1 = 0
        val m2 = 2 * m
        var k = m2
        while (k < n) {
            k1 += 2
            val k2 = 2 * k1
            val wk2r = w[k1]
            val wk2i = w[k1 + 1]
            wk1r = w[k2]
            var wk1i = w[k2 + 1]
            var wk3r = wk1r - 2 * wk2i * wk1i
            var wk3i = 2 * wk2i * wk1r - wk1i
            var jj = k
            while (jj < l + k) {
                val j1 = jj + l
                val j2 = j1 + l
                val j3 = j2 + l
                val x0r = a[jj] + a[j1]
                val x0i = a[jj + 1] + a[j1 + 1]
                val x1r = a[jj] - a[j1]
                val x1i = a[jj + 1] - a[j1 + 1]
                val x2r = a[j2] + a[j3]
                val x2i = a[j2 + 1] + a[j3 + 1]
                val x3r = a[j2] - a[j3]
                val x3i = a[j2 + 1] - a[j3 + 1]
                a[jj] = x0r + x2r
                a[jj + 1] = x0i + x2i
                val x0r4 = x0r - x2r
                val x0i4 = x0i - x2i
                a[j2] = wk2r * x0r4 - wk2i * x0i4
                a[j2 + 1] = wk2r * x0i4 + wk2i * x0r4
                val x0r5 = x1r - x3i
                val x0i5 = x1i + x3r
                a[j1] = wk1r * x0r5 - wk1i * x0i5
                a[j1 + 1] = wk1r * x0i5 + wk1i * x0r5
                val x0r6 = x1r + x3i
                val x0i6 = x1i - x3r
                a[j3] = wk3r * x0r6 - wk3i * x0i6
                a[j3 + 1] = wk3r * x0i6 + wk3i * x0r6
                jj += 2
            }
            wk1r = w[k2 + 2]
            wk1i = w[k2 + 3]
            wk3r = wk1r - 2 * wk2r * wk1i
            wk3i = 2 * wk2r * wk1r - wk1i
            var jk = k + m
            while (jk < l + (k + m)) {
                val j1 = jk + l
                val j2 = j1 + l
                val j3 = j2 + l
                val x0r = a[jk] + a[j1]
                val x0i = a[jk + 1] + a[j1 + 1]
                val x1r = a[jk] - a[j1]
                val x1i = a[jk + 1] - a[j1 + 1]
                val x2r = a[j2] + a[j3]
                val x2i = a[j2 + 1] + a[j3 + 1]
                val x3r = a[j2] - a[j3]
                val x3i = a[j2 + 1] - a[j3 + 1]
                a[jk] = x0r + x2r
                a[jk + 1] = x0i + x2i
                val x0r7 = x0r - x2r
                val x0i7 = x0i - x2i
                a[j2] = -wk2i * x0r7 - wk2r * x0i7
                a[j2 + 1] = -wk2i * x0i7 + wk2r * x0r7
                val x0r8 = x1r - x3i
                val x0i8 = x1i + x3r
                a[j1] = wk1r * x0r8 - wk1i * x0i8
                a[j1 + 1] = wk1r * x0i8 + wk1i * x0r8
                val x0r9 = x1r + x3i
                val x0i9 = x1i - x3r
                a[j3] = wk3r * x0r9 - wk3i * x0i9
                a[j3 + 1] = wk3r * x0i9 + wk3i * x0r9
                jk += 2
            }
            k += m2
        }
    }

    // ---- 实部后处理 ----

    private fun rftfsub(n: Int, a: FloatArray, nc: Int, c: FloatArray, cOffset: Int) {
        val m = n shr 1
        val ks = 2 * nc / m
        var kk = 0
        var j = 2
        while (j < m) {
            val k = n - j
            kk += ks
            val wkr = 0.5f - c[cOffset + nc - kk]
            val wki = c[cOffset + kk]
            val xr = a[j] - a[k]
            val xi = a[j + 1] + a[k + 1]
            val yr = wkr * xr - wki * xi
            val yi = wkr * xi + wki * xr
            a[j] -= yr
            a[j + 1] -= yi
            a[k] += yr
            a[k + 1] -= yi
            j += 2
        }
    }
}

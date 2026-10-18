package com.airscan.qr.core.qr

import com.airscan.qr.core.ggwave.ReedSolomon
import kotlin.math.abs

/**
 * 纯 Kotlin QR 码编码器（字节模式 + ISO-8859-1、纠错级 L、自动选版本、8 种掩码择优）。
 *
 * 供 Web(wasmJs) 目标使用——wasm 无法依赖 JVM 版 zxing，这里按 QR 标准实现，
 * 参数与 Android/桌面端的 zxing 编码完全一致（纠错级 L、静区 1、字节模式 ISO-8859-1），
 * 生成结果可被任何标准解码器识别。单元测试中用 zxing 解码做往返校验。
 *
 * 块结构/纠错表参考 nayuki/QrCode-gen 的公开实现；RS 纠错复用 core/ggwave 的
 * GF(2^8)（本原多项式 0x11d，与 QR 标准一致）。
 */
object QrMatrix {

    /** 编码结果：size×size 模块矩阵（true = 黑）。 */
    class Code internal constructor(val version: Int, val size: Int) {
        val modules: Array<BooleanArray> = Array(size) { BooleanArray(size) }
        val isFunction: Array<BooleanArray> = Array(size) { BooleanArray(size) }
    }

    // ---- 纠错级 L 的块参数（下标 = 版本号，下标 0 弃用） ----
    // 每块纠错码字数
    private val ECC_PER_BLOCK = intArrayOf(
        -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30,
        28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        30, 30, 30, 30,
    )
    // 纠错块数（nayuki 权威表 Low 行）
    private val NUM_BLOCKS = intArrayOf(
        -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6,
        7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20,
        21, 22, 24, 25,
    )

    private const val ECL_L = 1 // 格式信息中 L 的二进制 01

    /** 版本 v 的原始数据模块总数（扣除功能图案与格式/版本信息）。 */
    private fun numRawDataModules(ver: Int): Int {
        var result = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val numAlign = ver / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (ver >= 7) result -= 36 // 版本信息
        }
        return result
    }

    /** 版本 v、纠错级 L 的数据码字数。 */
    private fun numDataCodewords(ver: Int): Int =
        numRawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver]

    /** 字符计数指示位数：版本 1-9 为 8 位，10-40 为 16 位。 */
    private fun charCountBits(ver: Int): Int = if (ver <= 9) 8 else 16

    fun encode(data: ByteArray): Code {
        require(data.isNotEmpty()) { "QR 数据为空" }
        // 选择最小可用版本
        var version = 0
        for (v in 1..40) {
            val capBits = numDataCodewords(v) * 8
            val needBits = 4 + charCountBits(v) + data.size * 8
            if (needBits <= capBits) {
                version = v
                break
            }
        }
        require(version > 0) { "数据超出 QR v40-L 容量（${data.size} 字节）" }

        val size = version * 4 + 17
        val qr = Code(version, size)

        // ---- 构造码字序列 ----
        val allCodewords = makeCodewords(data, version)
        drawFunctionPatterns(qr)
        drawCodewords(qr, allCodewords)

        // ---- 掩码择优 ----
        var bestMask = 0
        var bestPenalty = Long.MAX_VALUE
        for (mask in 0..7) {
            applyMask(qr, mask)
            drawFormatBits(qr, mask)
            val penalty = penaltyScore(qr)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
            applyMask(qr, mask) // 还原（掩码自反）
        }
        applyMask(qr, bestMask)
        drawFormatBits(qr, bestMask)
        return qr
    }

    // ---- 码字构造：模式指示 + 计数 + 数据 + 终止符 + 补齐 + RS + 交织 ----

    private fun makeCodewords(data: ByteArray, version: Int): ByteArray {
        val capacityBits = numDataCodewords(version) * 8
        val bits = BitBuffer()
        bits.append(0b0100, 4) // 字节模式
        bits.append(data.size, charCountBits(version))
        for (b in data) bits.append(b.toInt() and 0xFF, 8)

        // 终止符（最多 4 个 0），随后补齐到字节边界，再交替填充 0xEC/0x11
        val terminatorBits = minOf(4, capacityBits - bits.bitLength)
        bits.append(0, terminatorBits)
        bits.padToByte()
        var i = 0
        while (bits.bitLength < capacityBits) {
            bits.append(if (i % 2 == 0) 0xEC else 0x11, 8)
            i++
        }
        val dataCw = bits.toBytes()

        // 分块 + RS 纠错 + 交织（按纯数据码字数切块：短块在前，长块比短块多 1 字节）
        val numBlocks = NUM_BLOCKS[version]
        val eccLen = ECC_PER_BLOCK[version]
        val dataTotal = numDataCodewords(version)
        val numShort = numBlocks - dataTotal % numBlocks
        val shortLen = dataTotal / numBlocks
        val longLen = shortLen + 1

        val blocks = Array(numBlocks) { j ->
            val dataLen = if (j < numShort) shortLen else longLen
            val off = if (j < numShort) j * shortLen else numShort * shortLen + (j - numShort) * longLen
            ReedSolomon(dataLen, eccLen).encode(dataCw, off)
        }

        val out = ByteArray(dataTotal + eccLen * numBlocks)
        var p = 0
        for (i in 0 until longLen) {
            for (j in 0 until numBlocks) {
                val dataLen = if (j < numShort) shortLen else longLen
                if (i < dataLen) out[p++] = blocks[j][i]
            }
        }
        for (i in 0 until eccLen) {
            for (j in 0 until numBlocks) {
                val dataLen = if (j < numShort) shortLen else longLen
                out[p++] = blocks[j][dataLen + i]
            }
        }
        check(p == out.size)
        return out
    }

    private class BitBuffer {
        private val buf = ArrayList<Int>()
        var bitLength = 0
            private set

        fun append(value: Int, len: Int) {
            for (i in len - 1 downTo 0) buf.add((value ushr i) and 1)
            bitLength += len
        }

        fun padToByte() {
            while (bitLength % 8 != 0) buf.add(0).also { bitLength++ }
        }

        fun toBytes(): ByteArray {
            check(bitLength % 8 == 0)
            val out = ByteArray(bitLength / 8)
            for (i in out.indices) {
                var b = 0
                for (k in 0 until 8) b = (b shl 1) or buf[i * 8 + k]
                out[i] = b.toByte()
            }
            return out
        }
    }

    // ---- 功能图案 ----

    private fun drawFunctionPatterns(qr: Code) {
        val size = qr.size
        // 时序图案
        for (i in 0 until size) {
            setFunction(qr, 6, i, i % 2 == 0)
            setFunction(qr, i, 6, i % 2 == 0)
        }
        // 定位图案 + 分隔（参数为中心点坐标）
        drawFinder(qr, 3, 3)
        drawFinder(qr, size - 4, 3)
        drawFinder(qr, 3, size - 4)
        // 校正图案
        val positions = alignmentPositions(qr.version, size)
        val n = positions.size
        for (i in 0 until n) {
            for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                drawAlignment(qr, positions[i], positions[j])
            }
        }
        // 格式信息占位（全 0，最终由 drawFormatBits 覆盖）
        drawFormatBits(qr, 0)
        // 版本信息（v≥7）
        if (qr.version >= 7) drawVersion(qr)
        // 固定暗模块（左下角 (8, 4V+9)）
        setFunction(qr, 8, size - 8, true)
    }

    private fun drawFinder(qr: Code, cx: Int, cy: Int) {
        val size = qr.size
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val dist = maxOf(abs(dx), abs(dy))
                setFunction(qr, x, y, dist != 2 && dist != 4)
            }
        }
    }

    private fun drawAlignment(qr: Code, cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                setFunction(qr, cx + dx, cy + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
        }
    }

    private fun alignmentPositions(ver: Int, size: Int): IntArray {
        if (ver == 1) return IntArray(0)
        val numAlign = ver / 7 + 2
        val step = if (ver == 32) 26 else (ver * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        for (i in numAlign - 1 downTo 1) {
            result[i] = size - 7 - step * (numAlign - 1 - i)
        }
        return result
    }

    private fun drawFormatBits(qr: Code, mask: Int) {
        val size = qr.size
        // BCH(15,5) 计算格式位
        var rem = (ECL_L shl 3) or mask
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = (((ECL_L shl 3) or mask) shl 10 or rem) xor 0x5412

        fun bit(i: Int) = (bits ushr i) and 1 == 1
        // 首份：环绕左上定位图案
        for (i in 0..5) setFunction(qr, 8, i, bit(i))
        setFunction(qr, 8, 7, bit(6))
        setFunction(qr, 8, 8, bit(7))
        setFunction(qr, 7, 8, bit(8))
        for (i in 9 until 15) setFunction(qr, 14 - i, 8, bit(i))
        // 第二份：拆分到右上与左下
        for (i in 0 until 8) setFunction(qr, size - 1 - i, 8, bit(i))
        for (i in 8 until 15) setFunction(qr, 8, size - 15 + i, bit(i))
        setFunction(qr, 8, size - 8, true) // 固定暗模块（左下，(8, 4V+9)）
    }

    private fun drawVersion(qr: Code) {
        val size = qr.size
        var rem = qr.version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = (qr.version shl 12) or rem
        for (i in 0 until 18) {
            val b = (bits ushr i) and 1 == 1
            val a = size - 11 + i % 3
            val c = i / 3
            setFunction(qr, a, c, b)
            setFunction(qr, c, a, b)
        }
    }

    private fun setFunction(qr: Code, x: Int, y: Int, dark: Boolean) {
        qr.modules[y][x] = dark
        qr.isFunction[y][x] = true
    }

    // ---- 数据放置与掩码 ----

    private fun drawCodewords(qr: Code, data: ByteArray) {
        val size = qr.size
        var i = 0 // 位下标
        // 两列一组从右向左的锯齿路径，跳过第 6 列（时序图案）
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!qr.isFunction[y][x] && i < data.size * 8) {
                        qr.modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7)) and 1) != 0
                        i++
                    }
                }
            }
            right -= 2
        }
        check(i == data.size * 8) { "码字放置不完整: $i/${data.size * 8}" }
    }

    private fun applyMask(qr: Code, mask: Int) {
        val size = qr.size
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (qr.isFunction[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) qr.modules[y][x] = !qr.modules[y][x]
            }
        }
    }

    // ---- 掩码惩罚评分（标准 4 规则） ----

    private fun penaltyScore(qr: Code): Long {
        val size = qr.size
        var result = 0L

        // N1：行/列中连续同色 ≥5
        for (y in 0 until size) {
            var runColor = false
            var runLen = 0
            for (x in 0 until size) {
                if (qr.modules[y][x] == runColor) {
                    runLen++
                    if (runLen == 5) result += 3 else if (runLen > 5) result += 1
                } else {
                    runColor = qr.modules[y][x]
                    runLen = 1
                }
            }
        }
        for (x in 0 until size) {
            var runColor = false
            var runLen = 0
            for (y in 0 until size) {
                if (qr.modules[y][x] == runColor) {
                    runLen++
                    if (runLen == 5) result += 3 else if (runLen > 5) result += 1
                } else {
                    runColor = qr.modules[y][x]
                    runLen = 1
                }
            }
        }

        // N2：2×2 同色块
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = qr.modules[y][x]
                if (c == qr.modules[y][x + 1] && c == qr.modules[y + 1][x] && c == qr.modules[y + 1][x + 1]) {
                    result += 3
                }
            }
        }

        // N3：定位图案状 1:1:3:1:1（含前后 4 亮位）
        for (y in 0 until size) {
            for (x in 0 until size - 10) {
                var ok = true
                for (k in 0 until 7) {
                    val expect = k == 2 || k == 3 || k == 4
                    if (qr.modules[y][x + k] != expect) {
                        ok = false
                        break
                    }
                }
                if (ok) {
                    val before = x >= 4 && !(qr.modules[y][x - 1] || qr.modules[y][x - 2] || qr.modules[y][x - 3] || qr.modules[y][x - 4])
                    val after = x + 11 <= size - 1 && !(qr.modules[y][x + 7] || qr.modules[y][x + 8] || qr.modules[y][x + 9] || qr.modules[y][x + 10])
                    if (before || after) result += 40
                }
            }
        }
        for (x in 0 until size) {
            for (y in 0 until size - 10) {
                var ok = true
                for (k in 0 until 7) {
                    val expect = k == 2 || k == 3 || k == 4
                    if (qr.modules[y + k][x] != expect) {
                        ok = false
                        break
                    }
                }
                if (ok) {
                    val before = y >= 4 && !(qr.modules[y - 1][x] || qr.modules[y - 2][x] || qr.modules[y - 3][x] || qr.modules[y - 4][x])
                    val after = y + 11 <= size - 1 && !(qr.modules[y + 7][x] || qr.modules[y + 8][x] || qr.modules[y + 9][x] || qr.modules[y + 10][x])
                    if (before || after) result += 40
                }
            }
        }

        // N4：明暗比例
        var dark = 0
        for (row in qr.modules) for (m in row) if (m) dark++
        val total = size * size
        val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * 10
        return result
    }
}

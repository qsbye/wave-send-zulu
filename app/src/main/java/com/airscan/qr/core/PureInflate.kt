package com.airscan.qr.core

/**
 * 纯 Kotlin gzip/DEFLATE 解压（RFC 1951/1952，puff 风格），
 * 供不支持 java.util.zip 的 wasm 目标使用。
 *
 * Android/桌面发送端由 JDK 生成 gzip，wasm 接收端必须能解开，
 * 因此解压必须完整支持动态/固定/存储三类块；压缩侧 wasm 暂不实现。
 * 在 JVM 上与 java.util.zip 往返一致（见 PlatformPureTest）。
 */
internal object PureInflate {

    private const val MAX_BITS = 15
    private const val MAX_L_CODES = 286
    private const val MAX_D_CODES = 30

    /** 位读取器：DEFLATE 为 LSB-first 位序。 */
    private class Reader(val data: ByteArray, val start: Int, val end: Int) {
        var pos = start
        var buf = 0
        var cnt = 0

        fun need(n: Int): Int {
            while (cnt < n) {
                if (pos >= end) throw IllegalArgumentException("inflateTruncated")
                buf = buf or ((data[pos++].toInt() and 0xFF) shl cnt)
                cnt += 8
            }
            val v = buf and ((1 shl n) - 1)
            buf = buf ushr n
            cnt -= n
            return v
        }

        /** 丢弃到下一字节边界（存储块用）。 */
        fun align() {
            val k = cnt and 7
            buf = buf ushr k
            cnt -= k
        }
    }

    /** 规范 Huffman 表：各长度码字数 + 按长度排序的符号表（puff 结构）。 */
    private class Huff(val counts: IntArray, val symbols: IntArray)

    private fun build(lengths: IntArray, from: Int, count: Int): Huff {
        val counts = IntArray(MAX_BITS + 1)
        for (i in from until from + count) counts[lengths[i]]++
        var left = 1
        for (len in 1..MAX_BITS) {
            left = left shl 1
            left -= counts[len]
            if (left < 0) throw IllegalArgumentException("inflateOverSubscribed")
        }
        val offs = IntArray(MAX_BITS + 2)
        for (len in 1..MAX_BITS) offs[len + 1] = offs[len] + counts[len]
        val symbols = IntArray(count)
        for (i in 0 until count) {
            val l = lengths[from + i]
            if (l != 0) symbols[offs[l]++] = i
        }
        return Huff(counts, symbols)
    }

    private fun decode(h: Huff, r: Reader): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..MAX_BITS) {
            code = code or r.need(1)
            val count = h.counts[len]
            if (code - first < count) return h.symbols[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        throw IllegalArgumentException("inflateInvalidCode")
    }

    /** 解压输出缓冲：自动扩容，总量不超过 [max]。 */
    private class Out(val max: Long) {
        var buf = ByteArray(1 shl 16)
        var len = 0

        fun put(b: Int) {
            if (len.toLong() >= max) throw IllegalArgumentException("inflateOverflow")
            if (len == buf.size) buf = buf.copyOf(buf.size shl 1)
            buf[len++] = b.toByte()
        }

        fun copy(dist: Int, length: Int) {
            if (len.toLong() + length > max) throw IllegalArgumentException("inflateOverflow")
            if (dist <= 0 || dist > len) throw IllegalArgumentException("inflateInvalidDistance")
            if (len + length > buf.size) {
                var s = buf.size
                while (s < len + length) s = s shl 1
                buf = buf.copyOf(s)
            }
            var from = len - dist
            repeat(length) { buf[len++] = buf[from++] } // 逐字节拷贝，重叠距离合法
        }

        fun result(): ByteArray = buf.copyOf(len)
    }

    private val L_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
        67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val L_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val D_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
        1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val D_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
        9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )

    private val fixedLit: Huff by lazy {
        val lengths = IntArray(288)
        for (i in 0 until 144) lengths[i] = 8
        for (i in 144 until 256) lengths[i] = 9
        for (i in 256 until 280) lengths[i] = 7
        for (i in 280 until 288) lengths[i] = 8
        build(lengths, 0, 288)
    }
    private val fixedDist: Huff by lazy {
        val lengths = IntArray(30) { 5 }
        build(lengths, 0, 30)
    }

    /** 对 DEFLATE 原始流（不含 gzip 头尾）解压；[maxOut] 为输出硬上限。 */
    fun inflate(data: ByteArray, start: Int, end: Int, maxOut: Long): ByteArray {
        val r = Reader(data, start, end)
        val out = Out(maxOut)
        while (true) {
            val isLast = r.need(1) == 1
            when (r.need(2)) {
                0 -> { // 存储块
                    r.align()
                    val len = r.need(16)
                    val nlen = r.need(16)
                    if (len != (nlen xor 0xFFFF)) {
                        throw IllegalArgumentException("inflateStoredLengthMismatch")
                    }
                    if (r.pos + len > end) throw IllegalArgumentException("inflateTruncated")
                    repeat(len) { out.put(data[r.pos++].toInt() and 0xFF) }
                }
                1 -> inflateBlock(fixedLit, fixedDist, r, out)
                2 -> { // 动态 Huffman
                    val nlen = r.need(5) + 257
                    val ndist = r.need(5) + 1
                    val ncode = r.need(4) + 4
                    if (nlen > MAX_L_CODES || ndist > MAX_D_CODES) {
                        throw IllegalArgumentException("inflateBadCounts")
                    }
                    val order = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
                    val clens = IntArray(19)
                    var i = 0
                    while (i < ncode) {
                        clens[order[i]] = r.need(3)
                        i++
                    }
                    while (i < 19) {
                        clens[order[i]] = 0
                        i++
                    }
                    val clh = build(clens, 0, 19)
                    val lens = IntArray(nlen + ndist)
                    i = 0
                    while (i < lens.size) {
                        when (val sym = decode(clh, r)) {
                            in 0..15 -> {
                                lens[i] = sym
                                i++
                            }
                            16 -> {
                                if (i == 0) throw IllegalArgumentException("inflateNoPrevLength")
                                val prev = lens[i - 1]
                                val rep = 3 + r.need(2)
                                if (i + rep > lens.size) throw IllegalArgumentException("inflateTooManyRepeats")
                                repeat(rep) { lens[i++] = prev }
                            }
                            17 -> {
                                val rep = 3 + r.need(3)
                                if (i + rep > lens.size) throw IllegalArgumentException("inflateTooManyRepeats")
                                i += rep
                            }
                            18 -> {
                                val rep = 11 + r.need(7)
                                if (i + rep > lens.size) throw IllegalArgumentException("inflateTooManyRepeats")
                                i += rep
                            }
                            else -> throw IllegalArgumentException("inflateBadCodeLengthCode")
                        }
                    }
                    if (lens[256] == 0) throw IllegalArgumentException("inflateNoEndOfBlock")
                    inflateBlock(build(lens, 0, nlen), build(lens, nlen, ndist), r, out)
                }
                else -> throw IllegalArgumentException("inflateBadBlockType")
            }
            if (isLast) break
        }
        return out.result()
    }

    private fun inflateBlock(lit: Huff, dist: Huff, r: Reader, out: Out) {
        while (true) {
            val sym = decode(lit, r)
            when {
                sym < 256 -> out.put(sym)
                sym == 256 -> return
                sym > 285 -> throw IllegalArgumentException("inflateBadLengthCode")
                else -> {
                    val li = sym - 257
                    val length = L_BASE[li] + if (L_EXTRA[li] != 0) r.need(L_EXTRA[li]) else 0
                    val dsym = decode(dist, r)
                    if (dsym > 29) throw IllegalArgumentException("inflateBadDistanceCode")
                    val d = D_BASE[dsym] + if (D_EXTRA[dsym] != 0) r.need(D_EXTRA[dsym]) else 0
                    out.copy(d, length)
                }
            }
        }
    }

    /** 解析 gzip 成员（RFC 1952）并解压；[maxBytes] 为输出硬上限。 */
    fun gunzip(bytes: ByteArray, maxBytes: Long): ByteArray {
        if (bytes.size < 18) throw IllegalArgumentException("gzipIncomplete")
        if ((bytes[0].toInt() and 0xFF) != 0x1F ||
            (bytes[1].toInt() and 0xFF) != 0x8B ||
            (bytes[2].toInt() and 0xFF) != 8
        ) {
            throw IllegalArgumentException("gzipBadFormat")
        }
        val flg = bytes[3].toInt() and 0xFF
        if (flg and 0xE0 != 0) throw IllegalArgumentException("gzipBadFlags")
        var p = 10
        if (flg and 4 != 0) { // FEXTRA
            if (p + 2 > bytes.size) throw IllegalArgumentException("gzipIncomplete")
            val xlen = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)
            p += 2 + xlen
        }
        if (flg and 8 != 0) { // FNAME
            while (p < bytes.size && bytes[p].toInt() != 0) p++
            p++
        }
        if (flg and 16 != 0) { // FCOMMENT
            while (p < bytes.size && bytes[p].toInt() != 0) p++
            p++
        }
        if (flg and 2 != 0) p += 2 // FHCRC
        if (p + 8 > bytes.size) throw IllegalArgumentException("gzipIncomplete")
        val out = inflate(bytes, p, bytes.size - 8, maxBytes)
        val crc = getLE32(bytes, bytes.size - 8)
        val isize = getLE32(bytes, bytes.size - 4)
        if (crc32(out) != crc) throw IllegalArgumentException("gzipCrcMismatch")
        if (out.size != isize) throw IllegalArgumentException("gzipIsizeMismatch")
        return out
    }

    private fun getLE32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /** 标准 CRC-32（gzip 尾部校验用）。 */
    fun crc32(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor -0x12477CE0 else crc ushr 1
            }
        }
        return crc.inv()
    }
}

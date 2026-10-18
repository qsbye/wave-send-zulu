package com.airscan.qr.core

/**
 * 系统化轮播喷泉码（systematic-carousel fountain code）。
 *
 * 移植自 decimen shared/fountain.ts 的 wire v2/v3 行为：
 *
 * 发送端无限轮播：先顺序扫一遍全部 K 个源块（每帧只含一个块），
 * 再发 K 个修复帧（XOR 若干由 seq 确定性派生的随机块子集，度 4..24 均匀分布），
 * 然后进入下一轮。接收端可随时加入、按任意顺序接收：
 *   - 低丢帧率时，扫到完整的一遍系统化帧即可零冗余还原；
 *   - 漏掉的帧由任意一轮的修复帧补上，掉帧只损失时间，不影响正确性。
 *
 * 无回执通道、无重传，收发两端帧率也无需一致。
 */
object Fountain {

    private const val REPAIR_DEGREE_MIN = 4
    private const val REPAIR_DEGREE_MAX = 24

    /** 一个轮播周期的帧数：k 个系统化帧 + k 个修复帧。 */
    fun cycleLength(k: Int): Int = 2 * k

    /**
     * 帧 [seq] 由哪些源块 XOR 组成，收发两端推导结果必须完全一致。
     *
     * 修复帧使用**绝对 seq** 作为种子，因此每一轮的修复子集都不同，
     * 反复观看轮播不会重复播放同一批修复帧。
     */
    fun frameComposition(k: Int, sessionId: Int, seq: UInt): IntArray {
        val pos = (seq % cycleLength(k).toUInt()).toInt()
        if (pos < k) return intArrayOf(pos)
        return repairIndices(k, sessionId, seq)
    }

    private fun repairIndices(k: Int, sessionId: Int, seq: UInt): IntArray {
        val rnd = SplitMix32(frameSeed(sessionId, seq))
        val span = (REPAIR_DEGREE_MAX - REPAIR_DEGREE_MIN + 1).toUInt()
        val d = minOf(k, REPAIR_DEGREE_MIN + (rnd.nextUInt() % span).toInt())
        val chosen = HashSet<Int>(d)
        val ku = k.toUInt()
        while (chosen.size < d) {
            chosen.add((rnd.nextUInt() % ku).toInt())
        }
        return chosen.toIntArray()
    }
}

/**
 * 把载荷按 blockLen 定长分块（最后一块补零），按帧序号生成 XOR 编码帧。
 * XOR 在小端 32 位字上进行，与 JS 版 Uint32Array 视图一致。
 */
class LTEncoder(
    payload: ByteArray,
    val blockLen: Int,
    val sessionId: Int,
) {
    val k: Int = maxOf(1, ((payload.size + blockLen - 1) / blockLen))
    private val words: Int = (blockLen + 3) / 4
    private val blocks: IntArray = IntArray(k * words)
    private val scratch: IntArray = IntArray(words)

    init {
        // 块存储以 words*4 为步长（固定 4 字节对齐），而源数据以 blockLen
        // 为步长切分——blockLen 不是 4 的倍数时两者不等，必须逐块拷贝，
        // 不能让源偏移按 4 连续累加（对齐参考实现 Uint8Array(blocks.buffer)）。
        val backing = ByteArray(k * words * 4)
        for (b in 0 until k) {
            val srcStart = b * blockLen
            val len = minOf(blockLen, payload.size - srcStart)
            if (len > 0) {
                System.arraycopy(payload, srcStart, backing, b * words * 4, len)
            }
        }
        for (i in blocks.indices) {
            blocks[i] = leReadWord(backing, i * 4)
        }
    }

    /** 生成第 [seq] 帧，长度恰好为 blockLen。 */
    fun encode(seq: UInt): ByteArray {
        val idx = Fountain.frameComposition(k, sessionId, seq)
        val out = scratch
        java.util.Arrays.fill(out, 0)
        for (b in idx) {
            val off = b * words
            for (w in 0 until words) {
                out[w] = out[w] xor blocks[off + w]
            }
        }
        val bytes = ByteArray(blockLen)
        for (w in 0 until words) {
            leWriteWord(bytes, w * 4, out[w])
        }
        return bytes
    }
}

private class PendingFrame(
    val idx: HashSet<Int>,
    val words: IntArray,
)

/**
 * 喷泉码解码器（peeling / 剥层）。
 *
 * 收集任意帧即可，内部维护“待解方程组”；一旦某帧只剩一个未知块即可解出，
 * 并级联化简所有等待该块的帧。重复 seq 直接丢弃。
 */
class LTDecoder(
    val k: Int,
    val blockLen: Int,
    val sessionId: Int,
    val totalLen: Long,
) {
    private val words: Int = (blockLen + 3) / 4
    private val solved: Array<IntArray?> = arrayOfNulls(k)
    private val byBlock: Array<HashSet<PendingFrame>?> = arrayOfNulls(k)
    private val seen: HashSet<Long> = HashSet()

    var solvedCount: Int = 0
        private set
    var framesNew: Int = 0
        private set
    var framesDup: Int = 0
        private set
    /** seq 未见过但不携带新信息（覆盖的块都已解出）的帧数。 */
    var framesRedundant: Int = 0
        private set

    val isComplete: Boolean get() = solvedCount >= k

    fun addFrame(seq: Long, block: ByteArray) {
        if (!seen.add(seq)) {
            framesDup++
            return
        }
        framesNew++
        if (isComplete) return

        val idxArr = Fountain.frameComposition(k, sessionId, seq.toUInt())
        val idx = HashSet<Int>()
        for (i in idxArr) idx.add(i)
        val w = IntArray(words)
        val take = minOf(blockLen, block.size)
        var off = 0
        var i = 0
        while (off < take) {
            w[i] = leReadWord(block, off)
            off += 4
            i++
        }

        // 把已知块从方程中消掉。
        val it = idx.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val s = solved[b]
            if (s != null) {
                xorWords(w, s)
                it.remove()
            }
        }

        when {
            idx.isEmpty() -> framesRedundant++
            idx.size == 1 -> resolve(idx.first(), w)
            else -> {
                val pf = PendingFrame(idx, w)
                for (b in idx) {
                    var set = byBlock[b]
                    if (set == null) {
                        set = HashSet()
                        byBlock[b] = set
                    }
                    set.add(pf)
                }
            }
        }
    }

    /** 剥层级联：解出一个块，化简等待它的所有帧，循环往复。 */
    private fun resolve(b0: Int, w0: IntArray) {
        val queueB = ArrayDeque<Int>()
        val queueW = ArrayDeque<IntArray>()
        queueB.add(b0)
        queueW.add(w0)
        while (queueB.isNotEmpty()) {
            val b = queueB.removeLast()
            val w = queueW.removeLast()
            if (solved[b] != null) continue
            solved[b] = w
            solvedCount++
            val waiting = byBlock[b] ?: continue
            byBlock[b] = null
            for (pf in waiting) {
                xorWords(pf.words, w)
                pf.idx.remove(b)
                if (pf.idx.size == 1) {
                    val r = pf.idx.first()
                    byBlock[r]?.remove(pf)
                    if (solved[r] == null) {
                        queueB.add(r)
                        queueW.add(pf.words)
                    }
                }
            }
        }
    }

    /** 全部解出后拼接还原字节；未完成返回 null。 */
    fun assemble(): ByteArray? {
        if (!isComplete) return null
        val out = ByteArray(totalLen.toInt())
        for (b in 0 until k) {
            val start = b * blockLen
            val len = minOf(blockLen, (totalLen - start).toInt())
            if (len <= 0) break
            val s = solved[b]!!
            var copied = 0
            var wi = 0
            while (copied + 4 <= len) {
                leWriteWord(out, start + copied, s[wi])
                copied += 4
                wi++
            }
            if (copied < len) {
                // 最后不足 4 字节的尾巴。
                var v = s[wi]
                while (copied < len) {
                    out[start + copied] = (v and 0xFF).toByte()
                    v = v ushr 8
                    copied++
                }
            }
        }
        return out
    }
}

private fun xorWords(dst: IntArray, src: IntArray) {
    for (i in dst.indices) dst[i] = dst[i] xor src[i]
}

/** 从 [src] 的 [offset] 处小端读取一个 32 位字（越界字节按 0 处理，兼容补零块）。 */
internal fun leReadWord(src: ByteArray, offset: Int): Int {
    var v = 0
    var shift = 0
    var i = offset
    val end = minOf(offset + 4, src.size)
    while (i < end) {
        v = v or ((src[i].toInt() and 0xFF) shl shift)
        shift += 8
        i++
    }
    return v
}

/** 把 32 位字小端写入 [dst] 的 [offset]（越界则忽略，兼容最后一块）。 */
internal fun leWriteWord(dst: ByteArray, offset: Int, value: Int) {
    var v = value
    var i = offset
    var remaining = 4
    while (remaining-- > 0 && i < dst.size) {
        dst[i] = (v and 0xFF).toByte()
        v = v ushr 8
        i++
    }
}

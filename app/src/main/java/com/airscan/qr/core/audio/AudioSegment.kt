package com.airscan.qr.core.audio

/**
 * 喷泉码帧 <-> 声波段 的分段/重组协议。
 *
 * ggwave 单次传输固定 64B（含本协议的段头），而一个喷泉码帧为
 * 22B 帧头 + blockLen(478~1443B)。因此把每个喷泉码帧拆成若干 64B 声波段，
 * 接收端收齐同一帧的所有段后还原出原始帧，再交给现有的 Wire/LTDecoder。
 *
 * 段格式（64B 固定）：
 *   0    u8   magic 'A' (0x41)
 *   1    u8   magic 'S' (0x53)
 *   2    u8   version = 1
 *   3    u8   flags   低 4 位 = 本帧总段数-1（1..16），高 4 位保留
 *   4    u16  sessionId   与喷泉码帧头一致，用于区分发送会话
 *   6    u32  seq         喷泉码帧序号
 *   10   u16  frameLen    原始帧总字节数（22 + blockLen）
 *   12   u8   segIndex    本段序号（0..segCount-1）
 *   13   u8   reserved = 0
 *   14   ...  50B 载荷（原始帧的切片，末段不足补 0）
 *
 * 单帧最大 16 段 × 50B = 800B，覆盖 blockLen ≤ 778B（含 22B 头 = 800B）的情况；
 * 对 1465B 帧（1487B 含头）超过 16 段，故采用 32 段上限（见下）。
 */
object AudioSegment {

    const val SEGMENT_BYTES = 64          // ggwave 固定帧长
    const val HEADER_BYTES = 14
    const val PAYLOAD_BYTES = SEGMENT_BYTES - HEADER_BYTES   // 50

    /**
     * 单段传输耗时估计（秒，Fast 档）：64B 段经 RS(64,24) 得 88B 码字，
     * 按 3B/块分 30 块 × 每块 6 音频帧 × 1024 样本 / 48 kHz ≈ 3.84 s。
     * 用于“预计 x 秒后锁定”提示。
     */
    const val SEGMENT_DURATION_SEC = 3.84f
    const val MAGIC0 = 0x41   // 'A'
    const val MAGIC1 = 0x53   // 'S'
    const val VERSION = 1

    // flags 低 5 位存 segCount-1，支持最多 32 段
    const val MAX_SEGMENTS = 32
    const val MAX_FRAME_BYTES = MAX_SEGMENTS * PAYLOAD_BYTES   // 1600

    /** 计算一帧需要多少段。 */
    fun segmentCount(frameLen: Int): Int =
        (frameLen + PAYLOAD_BYTES - 1) / PAYLOAD_BYTES

    /** 是否支持该帧长。 */
    fun supportsFrame(frameLen: Int): Boolean =
        frameLen in 1..MAX_FRAME_BYTES

    /**
     * 把一个喷泉码帧拆成若干 64B 声波段。
     * @param sessionId 会话 id（与喷泉码帧头一致）
     * @param seq       帧序号
     * @param frame     完整喷泉码帧（22B 头 + blockLen）
     * @return 64B 段数组
     */
    fun split(sessionId: Int, seq: Long, frame: ByteArray): List<ByteArray> {
        val frameLen = frame.size
        require(supportsFrame(frameLen)) { "frame too large for audio: $frameLen" }
        val segCount = segmentCount(frameLen)
        val out = ArrayList<ByteArray>(segCount)
        for (seg in 0 until segCount) {
            val s = ByteArray(SEGMENT_BYTES)
            s[0] = MAGIC0.toByte()
            s[1] = MAGIC1.toByte()
            s[2] = VERSION.toByte()
            s[3] = ((segCount - 1) and 0x1F).toByte()
            // sessionId u16 LE
            s[4] = (sessionId and 0xFF).toByte()
            s[5] = ((sessionId ushr 8) and 0xFF).toByte()
            // seq u32 LE
            s[6] = (seq and 0xFF).toByte()
            s[7] = ((seq ushr 8) and 0xFF).toByte()
            s[8] = ((seq ushr 16) and 0xFF).toByte()
            s[9] = ((seq ushr 24) and 0xFF).toByte()
            // frameLen u16 LE
            s[10] = (frameLen and 0xFF).toByte()
            s[11] = ((frameLen ushr 8) and 0xFF).toByte()
            s[12] = seg.toByte()
            s[13] = 0
            // payload
            val srcOff = seg * PAYLOAD_BYTES
            val n = minOf(PAYLOAD_BYTES, frameLen - srcOff)
            System.arraycopy(frame, srcOff, s, HEADER_BYTES, n)
            out.add(s)
        }
        return out
    }

    /** 解析一个 64B 段，非法返回 null。 */
    fun parse(seg: ByteArray): Segment? {
        if (seg.size < SEGMENT_BYTES) return null
        if ((seg[0].toInt() and 0xFF) != MAGIC0) return null
        if ((seg[1].toInt() and 0xFF) != MAGIC1) return null
        if ((seg[2].toInt() and 0xFF) != VERSION) return null
        val segCount = ((seg[3].toInt() and 0x1F) + 1)
        val sessionId = (seg[4].toInt() and 0xFF) or ((seg[5].toInt() and 0xFF) shl 8)
        val seq = (seg[6].toLong() and 0xFF) or
            ((seg[7].toLong() and 0xFF) shl 8) or
            ((seg[8].toLong() and 0xFF) shl 16) or
            ((seg[9].toLong() and 0xFF) shl 24)
        val frameLen = (seg[10].toInt() and 0xFF) or ((seg[11].toInt() and 0xFF) shl 8)
        val segIndex = seg[12].toInt() and 0xFF
        if (segCount < 1 || segCount > MAX_SEGMENTS) return null
        if (segIndex >= segCount) return null
        if (frameLen < 1 || frameLen > MAX_FRAME_BYTES) return null
        val payload = ByteArray(PAYLOAD_BYTES)
        System.arraycopy(seg, HEADER_BYTES, payload, 0, PAYLOAD_BYTES)
        return Segment(sessionId, seq, frameLen, segCount, segIndex, payload)
    }

    data class Segment(
        val sessionId: Int,
        val seq: Long,
        val frameLen: Int,
        val segCount: Int,
        val segIndex: Int,
        val payload: ByteArray,
    )

    /**
     * 段重组器：收集同一会话同一帧的所有段，拼齐后产出原始帧。
     * 线程不安全；由调用方串行喂入。
     */
    class Reassembler {
        private class Partial(
            val frameLen: Int,
            val segCount: Int,
        ) {
            val data = ByteArray(frameLen)
            val got = BooleanArray(segCount)
            var gotCount = 0
        }

        /** 当前半成品帧的重组进度快照（跨线程读取用，无半成品时为 null）。 */
        data class PendingInfo(val segCount: Int, val gotCount: Int)

        @Volatile
        private var pendingSnapshot: PendingInfo? = null

        private var currentSession = -1
        private var currentSeq = -1L
        private var partial: Partial? = null

        /** 取当前半成品帧的重组进度（供 UI 显示“预计 x 秒后锁定”）。 */
        fun pending(): PendingInfo? = pendingSnapshot

        /** 喂入一个段；若因此拼齐一帧则返回该帧，否则返回 null。 */
        fun feed(seg: Segment): ByteArray? {
            // 会话或帧变化则丢弃旧半成品
            if (seg.sessionId != currentSession || seg.seq != currentSeq ||
                partial == null || partial!!.segCount != seg.segCount ||
                partial!!.frameLen != seg.frameLen
            ) {
                currentSession = seg.sessionId
                currentSeq = seg.seq
                partial = Partial(seg.frameLen, seg.segCount)
            }
            val p = partial!!
            if (!p.got[seg.segIndex]) {
                p.got[seg.segIndex] = true
                p.gotCount++
                val dstOff = seg.segIndex * PAYLOAD_BYTES
                val n = minOf(PAYLOAD_BYTES, p.frameLen - dstOff)
                System.arraycopy(seg.payload, 0, p.data, dstOff, n)
            }
            pendingSnapshot =
                if (p.gotCount == p.segCount) null else PendingInfo(p.segCount, p.gotCount)
            return if (p.gotCount == p.segCount) {
                val done = p.data
                partial = null
                done
            } else null
        }

        fun reset() {
            partial = null
            currentSession = -1
            currentSeq = -1L
            pendingSnapshot = null
        }
    }
}

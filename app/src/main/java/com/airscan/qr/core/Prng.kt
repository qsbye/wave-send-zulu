package com.airscan.qr.core

/**
 * splitmix32 —— 确定性 32 位 PRNG，仅使用整数运算。
 *
 * 移植自 decimen shared/protocol.ts，收发两端必须得到逐位一致的序列，
 * 才能各自独立推导出同一帧 XOR 了哪些源块，因此本文件属于线格式（wire format），
 * 不可随意修改。
 */
class SplitMix32(seed: Int) {

    private var s: Int = seed

    /** 返回下一个 32 位随机数（按无符号解释，请用 [nextUInt]）。 */
    private fun nextRaw(): Int {
        s += 0x9e3779b9.toInt()
        var t = s xor (s ushr 16)
        t *= 0x21f0aaad.toInt()
        t = t xor (t ushr 15)
        t *= 0x735a2d97.toInt()
        t = t xor (t ushr 15)
        return t
    }

    fun nextUInt(): UInt = nextRaw().toUInt()
}

/**
 * 由会话 id 与帧序号派生该帧的随机种子。收发两端同样逐位一致。
 * 对应 TS：
 *   h = imul(sessionId + 1, 0x9e3779b1) ^ (seq + 0x85ebca6b)
 *   h = imul(h ^ (h >>> 13), 0xc2b2ae35)
 *   (h ^ (h >>> 16)) | 0
 */
internal fun frameSeed(sessionId: Int, seq: UInt): Int {
    var h = ((sessionId + 1) * 0x9e3779b1.toInt()) xor
        (seq + 0x85ebca6bu).toInt()
    h = (h xor (h ushr 13)) * 0xc2b2ae35.toInt()
    return h xor (h ushr 16)
}

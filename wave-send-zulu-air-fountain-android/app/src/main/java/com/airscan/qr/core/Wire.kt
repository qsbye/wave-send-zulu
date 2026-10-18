package com.airscan.qr.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 自描述帧协议（与 decimen v3 线格式兼容）。
 *
 * 每个二维码帧都带完整头部，无需握手：接收端可在播放中途锁定流；
 * 发送端重启会产生新的 sessionId，接收端自动重新开始。
 *
 * 布局（小端），共 22 字节，其后跟 blockLen 字节载荷：
 *   0  u8   magic 0xD1
 *   1  u8   magic 0xC3
 *   2  u8   version     = 3
 *   3  u8   flags       低 4 位必须理解，高 4 位可安全忽略
 *   4  u16  sessionId   每次发送随机生成
 *   6  u32  seq         喷泉码 PRNG 种子输入
 *  10  u16  k           源块数
 *  12  u16  blockLen    每帧载荷字节数
 *  14  u32  totalLen    受保护容器（DCF2）总长度
 *  18  u32  payloadFnv  整个容器的 FNV-1a，完成后校验
 */
object Wire {

    const val HEADER_LEN = 22

    const val MAGIC0: Int = 0xD1
    const val MAGIC1: Int = 0xC3
    const val WIRE_VERSION: Int = 3

    const val CRITICAL_FLAGS = 0x0F
    internal const val SUPPORTED_FLAGS = 0x00

    const val FLAG_ENCRYPTED = 0x01
}

data class FrameHeader(
    val flags: Int,
    val sessionId: Int,   // u16
    val seq: Long,        // u32
    val k: Int,           // u16
    val blockLen: Int,    // u16
    val totalLen: Long,   // u32
    val payloadFnv: Long, // u32
)

/** 一帧解析结果的判定——区分“镜头对着无关二维码”和“两端版本不匹配”。 */
sealed class FrameVerdict {
    data object Ok : FrameVerdict()
    data object Foreign : FrameVerdict()
    data class OlderSender(val version: Int) : FrameVerdict()
    data class NewerSender(val version: Int) : FrameVerdict()
    data class UnsupportedFlags(val flags: Int) : FrameVerdict()
    data object Malformed : FrameVerdict()
}

fun packFrame(header: FrameHeader, block: ByteArray): ByteArray {
    val out = ByteArray(Wire.HEADER_LEN + block.size)
    val dv = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    dv.put(Wire.MAGIC0.toByte())
    dv.put(Wire.MAGIC1.toByte())
    dv.put(Wire.WIRE_VERSION.toByte())
    dv.put(header.flags.toByte())
    dv.putShort(header.sessionId.toShort())
    dv.putInt(header.seq.toInt())
    dv.putShort(header.k.toShort())
    dv.putShort(header.blockLen.toShort())
    dv.putInt(header.totalLen.toInt())
    dv.putInt(header.payloadFnv.toInt())
    System.arraycopy(block, 0, out, Wire.HEADER_LEN, block.size)
    return out
}

fun classifyFrame(bytes: ByteArray): FrameVerdict {
    if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != Wire.MAGIC0) return FrameVerdict.Foreign
    when (bytes[1].toInt() and 0xFF) {
        Wire.MAGIC1 -> { /* 继续判定 */ }
        0x0C -> return FrameVerdict.OlderSender(1)
        0x0D -> return FrameVerdict.OlderSender(2)
        else -> return FrameVerdict.Foreign
    }
    val version = bytes[2].toInt() and 0xFF
    if (version == 0) return FrameVerdict.Malformed
    if (version != Wire.WIRE_VERSION) {
        return if (version > Wire.WIRE_VERSION) {
            FrameVerdict.NewerSender(version)
        } else {
            FrameVerdict.OlderSender(version)
        }
    }
    val unknownCritical = (bytes[3].toInt() and 0xFF) and
        Wire.CRITICAL_FLAGS and Wire.SUPPORTED_FLAGS.inv()
    if (unknownCritical != 0) return FrameVerdict.UnsupportedFlags(unknownCritical)

    if (bytes.size <= Wire.HEADER_LEN) return FrameVerdict.Malformed
    val dv = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val k = dv.getShort(10).toInt() and 0xFFFF
    val blockLen = dv.getShort(12).toInt() and 0xFFFF
    val totalLen = dv.getInt(14).toLong() and 0xFFFFFFFFL
    if (k == 0 || blockLen == 0 || totalLen == 0L) return FrameVerdict.Malformed
    if (bytes.size != Wire.HEADER_LEN + blockLen) return FrameVerdict.Malformed
    return FrameVerdict.Ok
}

fun parseFrame(bytes: ByteArray): Pair<FrameHeader, ByteArray>? {
    if (classifyFrame(bytes) !is FrameVerdict.Ok) return null
    val dv = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val header = FrameHeader(
        flags = dv.get(3).toInt() and 0xFF,
        sessionId = dv.getShort(4).toInt() and 0xFFFF,
        seq = dv.getInt(6).toLong() and 0xFFFFFFFFL,
        k = dv.getShort(10).toInt() and 0xFFFF,
        blockLen = dv.getShort(12).toInt() and 0xFFFF,
        totalLen = dv.getInt(14).toLong() and 0xFFFFFFFFL,
        payloadFnv = dv.getInt(18).toLong() and 0xFFFFFFFFL,
    )
    val block = bytes.copyOfRange(Wire.HEADER_LEN, bytes.size)
    return header to block
}

/**
 * 流身份：除 seq 外所有必须保持恒定的字段。任何不一致都重置解码器，
 * 而不仅在 sessionId 变化时——同一文件重启发送时这些字段恰好一致，
 * 继续往旧解码器填帧是正确的。
 */
fun streamIdentity(h: FrameHeader): String =
    listOf(
        h.sessionId,
        h.k,
        h.blockLen,
        h.totalLen,
        h.payloadFnv,
        h.flags and Wire.CRITICAL_FLAGS,
    ).joinToString(":")

/** FNV-1a 32 位哈希，返回无符号 32 位值（0..2^32-1）。 */
fun fnv1a(bytes: ByteArray): Long {
    var h: Int = 0x811c9dc5.toInt()
    for (b in bytes) {
        h = h xor (b.toInt() and 0xFF)
        h *= 0x01000193
    }
    return h.toLong() and 0xFFFFFFFFL
}

fun frameVerdictMessage(v: FrameVerdict): String? = when (v) {
    is FrameVerdict.OlderSender ->
        "对端正在使用旧版传输格式（v${v.version}），请升级发送端。"
    is FrameVerdict.NewerSender ->
        "对端正在使用更新的传输格式（v${v.version}），请升级本应用。"
    is FrameVerdict.UnsupportedFlags ->
        "该传输流使用了当前版本无法识别的特性，请升级本应用。"
    else -> null
}

package com.airscan.qr.util

import java.util.Locale

object Fmt {

    /** 人类可读的字节/速率大小。 */
    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n.toDouble() / (1L shl 30))
        n >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", n.toDouble() / (1L shl 20))
        n >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", n.toDouble() / (1L shl 10))
        else -> "$n B"
    }

    /** 完整 SHA-256 十六进制（小写、分组显示）。 */
    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun hexGrouped(bytes: ByteArray, group: Int = 8): String =
        hex(bytes).chunked(group).joinToString(" ")
}

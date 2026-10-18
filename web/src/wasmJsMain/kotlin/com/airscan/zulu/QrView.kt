package com.airscan.zulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.airscan.qr.core.qr.QrMatrix

/** 纯 Kotlin Base64（wasm 无 java.util.Base64）。 */
object B64 {

    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF shl 16) or
                (data[i + 1].toInt() and 0xFF shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHA[n ushr 18 and 63]).append(ALPHA[n ushr 12 and 63])
                .append(ALPHA[n ushr 6 and 63]).append(ALPHA[n and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val n = data[i].toInt() and 0xFF shl 16
            sb.append(ALPHA[n ushr 18 and 63]).append(ALPHA[n ushr 12 and 63]).append("==")
        } else if (rem == 2) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8)
            sb.append(ALPHA[n ushr 18 and 63]).append(ALPHA[n ushr 12 and 63])
                .append(ALPHA[n ushr 6 and 63]).append("=")
        }
        return sb.toString()
    }

    private fun valOf(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    fun decode(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in s) {
            if (c == '=' || c == '\n' || c == '\r') continue
            val v = valOf(c)
            if (v < 0) continue
            acc = acc shl 6 or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add((acc ushr bits and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}

private val HEX_DIGITS = "0123456789abcdef"

fun fmtBytes(v: Long): String = when {
    v < 1024 -> "$v B"
    v < 1024 * 1024 -> {
        val kb = v * 10 / 1024 // 保留 1 位小数
        "${kb / 10}.${kb % 10} KB"
    }
    else -> {
        val mb = v * 100 / (1024 * 1024) // 保留 2 位小数
        "${mb / 100}." + (mb % 100).toString().padStart(2, '0') + " MB"
    }
}

fun hex(b: ByteArray): String {
    val sb = StringBuilder(b.size * 2)
    for (x in b) {
        val v = x.toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0xF])
    }
    return sb.toString()
}

/**
 * 二维码渲染：直接用 Canvas 按模块绘制矢量方块，任意缩放都锐利，
 * 无需位图转换（wasm 端无 AWT/zxing 可用，编码走纯 Kotlin QrMatrix）。
 */
@Composable
fun QrView(frame: ByteArray?, modifier: Modifier = Modifier) {
    val code = frame?.let { f -> remember(f) { runCatching { QrMatrix.encode(f) }.getOrNull() } }
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color.White)
        val c = code ?: return@Canvas
        val m = size.width / c.size
        for (y in 0 until c.size) {
            val row = c.modules[y]
            for (x in 0 until c.size) {
                if (row[x]) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset(x * m, y * m),
                        size = Size(m + 0.5f, m + 0.5f),
                    )
                }
            }
        }
    }
}

package com.airscan.zulu

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

/**
 * 与 Android 端 [com.airscan.qr.qr.QrEncoder] 完全一致的编码参数：
 * 字节模式 + ISO-8859-1、纠错级 L、静区 1 模块。
 */
object QrBitmap {

    private val HINTS: Map<EncodeHintType, Any> = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        EncodeHintType.MARGIN to 1,
    )

    fun render(frame: ByteArray, sizePx: Int = 880): ImageBitmap {
        val matrix = QRCodeWriter().encode(
            String(frame, Charsets.ISO_8859_1),
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            HINTS,
        )
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        return img.toComposeImageBitmap()
    }
}

fun fmtBytes(v: Long): String = when {
    v < 1024 -> "$v B"
    v < 1024 * 1024 -> "%.1f KB".format(v / 1024.0)
    else -> "%.2f MB".format(v / 1024.0 / 1024.0)
}

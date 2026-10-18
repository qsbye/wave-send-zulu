package com.airscan.qr.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 用 ZXing 把整帧字节编码成二维码位图。
 *
 * - 字节模式 + ISO-8859-1：任意二进制逐字节往返，不做 Base64 膨胀；
 * - 纠错级 L：帧内 ECC 解决“污损”、喷泉码解决“丢帧”，二者分工不同，
 *   L 级 + “整码解出或丢弃”在这种帧大小下吞吐更优；
 * - 静区 1 模块（屏幕展示时白色卡片本身已提供留白）。
 */
object QrEncoder {

    private val HINTS: Map<EncodeHintType, Any> = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        EncodeHintType.MARGIN to 1,
    )

    fun encode(frame: ByteArray, sizePx: Int = 1080): Bitmap {
        @Suppress("DEPRECATION")
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
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) black else white
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}

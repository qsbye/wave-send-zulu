package com.airscan.qr.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.airscan.qr.camera.SauvolaBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * 纯 Kotlin QR 编码器校验：用 zxing 解码做往返测试。
 * 覆盖单块/多块交织/混合长短块版本与全字节范围（ISO-8859-1）。
 */
class QrMatrixTest {

    /** 渲染为 RGB 像素（每模块 scale×scale 放大 + 4 模块静区），供 zxing 解码。 */
    private fun render(code: QrMatrix.Code, scale: Int): Triple<IntArray, Int, Int> {
        val quiet = 4
        val w = (code.size + quiet * 2) * scale
        val pixels = IntArray(w * w) { 0xFFFFFF }
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (!code.modules[y][x]) continue
                for (dy in 0 until scale) {
                    val row = ((y + quiet) * scale + dy) * w
                    for (dx in 0 until scale) pixels[row + (x + quiet) * scale + dx] = 0x000000
                }
            }
        }
        return Triple(pixels, w, w)
    }

    private fun decode(data: ByteArray) {
        val code = QrMatrix.encode(data)
        val hints = mapOf(
            DecodeHintType.CHARACTER_SET to "ISO-8859-1",
            DecodeHintType.TRY_HARDER to true,
        )
        // 满容量随机数据零纠错余量，检测网格抖动可能误判：多尺度/多二值化重试
        val scales = if (code.size > 100) intArrayOf(6, 8, 4, 3) else intArrayOf(4, 6, 3)
        var lastError: Exception? = null
        for (scale in scales) {
            val (pixels, w, h) = render(code, scale)
            val source = RGBLuminanceSource(w, h, pixels)
            for (bin in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source), SauvolaBinarizer(source))) {
                try {
                    val result = QRCodeReader().decode(BinaryBitmap(bin), hints)
                    val decoded = result.text.toByteArray(Charsets.ISO_8859_1)
                    assertTrue("解码字节长度不符: ${decoded.size} != ${data.size}", decoded.contentEquals(data))
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw lastError!!
    }

    @Test
    fun smallAsciiPayload() {
        decode("hello airscan fountain".toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun allByteValues() {
        // 全部 256 个字节值重复若干次，覆盖 ISO-8859-1 高位字节
        val data = ByteArray(256 * 3) { (it % 256).toByte() }
        decode(data)
    }

    @Test
    fun frameSizesAcrossVersions() {
        val rng = SecureRandom()
        // 覆盖：单块版本 / 多块版本 / 混合长短块（v10 起） / 大版本 v40
        listOf(17, 134, 200, 279, 500, 1022, 1487, 2953).forEach { n ->
            val data = ByteArray(n)
            rng.nextBytes(data)
            decode(data)
        }
    }

    @Test
    fun versionAndSizeSanity() {
        val small = QrMatrix.encode("a".toByteArray(Charsets.ISO_8859_1))
        assertEquals(1, small.version)
        assertEquals(21, small.size)
        val big = QrMatrix.encode(ByteArray(2953))
        assertEquals(40, big.version)
        assertEquals(177, big.size)
    }

    @Test
    fun maxCapacityBoundary() {
        // v40-L 最大字节容量 2953，超一个字节应抛出
        try {
            QrMatrix.encode(ByteArray(2954))
            throw AssertionError("应超出容量")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("容量"))
        }
    }
}

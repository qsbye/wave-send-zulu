package com.airscan.qr.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumMap

/**
 * 解码管线回归测试：1:1 复刻 QrAnalyzer 的解码路径（PlanarYUVLuminanceSource +
 * HybridBinarizer + QRCodeReader + 相同 hints），在"传感器原始方向"（未摆正）
 * 的亮度帧上解码，覆盖旋转 0/90/180/270 度与小尺寸场景。
 */
class QrAnalyzerDecodeTest {

    private val payload: ByteArray = ByteArray(500) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    private val hints: Map<DecodeHintType, Any> =
        EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            put(DecodeHintType.CHARACTER_SET, "ISO-8859-1")
            put(DecodeHintType.TRY_HARDER, true)
        }

    @Test
    fun decodesUprightFrame() = roundTrip(0)

    @Test
    fun decodesRotated90Frame() = roundTrip(90)

    @Test
    fun decodesRotated180Frame() = roundTrip(180)

    @Test
    fun decodesRotated270Frame() = roundTrip(270)

    @Test
    fun decodesSmallQrInFrame() {
        // 模拟较远的拍摄距离：二维码只占画面高度的 ~1/3
        val frame = renderCameraFrame(qrPx = 240, frameW = 1280, frameH = 720)
        val decoded = decode(frame, 1280, 720)
        assertTrue("240px 二维码应可解码", decoded)
    }

    @Test
    fun sauvolaBinarizerHandlesUnevenIllumination() {
        // 渲染二维码帧后叠加左亮右暗的线性光照梯度，
        // 验证 Sauvola 局部自适应阈值在此类画面下仍可解码
        val frame = renderCameraFrame(qrPx = 480, frameW = 1280, frameH = 720)
        for (y in 0 until 720) {
            for (x in 0 until 1280) {
                val i = y * 1280 + x
                val v = frame[i].toInt() and 0xFF
                val scaled = (v * (0.55 + 0.45 * x / 1280.0)).toInt().coerceIn(0, 255)
                frame[i] = scaled.toByte()
            }
        }
        val source = PlanarYUVLuminanceSource(frame, 1280, 720, 0, 0, 1280, 720, false)
        val bitmap = BinaryBitmap(SauvolaBinarizer(source))
        val result = QRCodeReader().decode(bitmap, hints)
        assertEquals(payload.size, result.text.toByteArray(Charsets.ISO_8859_1).size)
    }

    /** 按传感器帧（旋转 rotation 度后的码面）渲染并走 analyze() 的解码路径。 */
    private fun roundTrip(rotation: Int) {
        val frame = renderCameraFrame(qrPx = 600, frameW = 1280, frameH = 720)
        val (data, w, h) = rotateFrame(frame, 1280, 720, rotation)
        val decoded = decode(data, w, h)
        assertTrue("${rotation}° 旋转帧应可解码", decoded)
    }

    /** 与 QrAnalyzer.analyze 相同的解码调用。返回是否解码成功。 */
    private fun decode(data: ByteArray, w: Int, h: Int): Boolean {
        val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            val result = QRCodeReader().decode(bitmap, hints)
            val raw = result.text.toByteArray(Charsets.ISO_8859_1)
            assertTrue("载荷应逐字节还原", raw.contentEquals(payload))
            true
        } catch (e: Exception) {
            println("decode failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 用与发送端 QrEncoder 相同的参数编码，并渲染进白背景相机帧中央。 */
    private fun renderCameraFrame(qrPx: Int, frameW: Int, frameH: Int): ByteArray {
        val encodeHints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(
            String(payload, Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, qrPx, qrPx, encodeHints,
        )
        val data = ByteArray(frameW * frameH) { 0xFF.toByte() } // 白背景
        val ox = (frameW - matrix.width) / 2
        val oy = (frameH - matrix.height) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) data[(oy + y) * frameW + ox + x] = 0
            }
        }
        return data
    }

    /** 顺时针旋转亮度帧（模拟 imageProxy 中的传感器原始方向）。 */
    private fun rotateFrame(
        data: ByteArray, w: Int, h: Int, rotation: Int,
    ): Triple<ByteArray, Int, Int> {
        return when (rotation % 360) {
            0 -> Triple(data, w, h)
            90 -> {
                val out = ByteArray(data.size)
                for (y in 0 until h) for (x in 0 until w) {
                    out[(w - 1 - x) * h + y] = data[y * w + x]
                }
                Triple(out, h, w)
            }
            180 -> {
                val out = ByteArray(data.size)
                for (y in 0 until h) for (x in 0 until w) {
                    out[(h - 1 - y) * w + (w - 1 - x)] = data[y * w + x]
                }
                Triple(out, w, h)
            }
            else -> {
                val out = ByteArray(data.size)
                for (y in 0 until h) for (x in 0 until w) {
                    out[x * h + (h - 1 - y)] = data[y * w + x]
                }
                Triple(out, h, w)
            }
        }
    }
}

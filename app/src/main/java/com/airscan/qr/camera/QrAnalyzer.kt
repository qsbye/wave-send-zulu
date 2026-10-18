package com.airscan.qr.camera

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap
import java.util.concurrent.Executor

/**
 * 检出二维码的四角点坐标（"摆正后"帧画面，像素）。
 * 四点顺时针（左上/右上/右下/左下），跟随二维码的旋转；
 * ZXing 仅返回 3 个定位图案中心，第 4 角按仿射（平行四边形）模型推算，
 * frameWidth/frameHeight 供 FILL_CENTER 预览映射使用。
 */
data class QrQuad(
    /** [x0,y0, x1,y1, x2,y2, x3,y3]，顺序：左上/右上/右下/左下 */
    val points: FloatArray,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * CameraX 帧分析器：ZXing 解出二维码后，把字节模式载荷（ISO-8859-1 逐字节还原）
 * 原样回调给接收逻辑；同时回调检出四边形（null = 本帧未检出）供 UI
 * 绘制蓝色虚线锁定框。STRATEGY_KEEP_ONLY_LATEST：卡顿/速度不匹配时
 * 直接丢弃待分析的旧帧，符合喷泉码"任意帧都行"的接收模型。
 *
 * 注：为满足 F-Droid 的 FOSS 依赖要求，此处使用 ZXing（Apache-2.0）
 * 而非 Google ML Kit（专有）。
 */
class QrAnalyzer(
    private val callbackExecutor: Executor,
    private val onFrame: (ByteArray) -> Unit,
    private val onQuad: (QrQuad?) -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val reader = QRCodeReader()
    private val hints: Map<DecodeHintType, Any> = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
        // 发送端为字节模式 + ISO-8859-1：强制字符集保证 payload 逐字节还原
        put(DecodeHintType.CHARACTER_SET, "ISO-8859-1")
        put(DecodeHintType.TRY_HARDER, true)
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val mediaImage = imageProxy.image ?: return
            val rotation = imageProxy.imageInfo.rotationDegrees
            val srcW = mediaImage.width
            val srcH = mediaImage.height
            // 摆正后的帧尺寸（旋转 90/270 时宽高互换），四边形坐标基于该空间
            val uprightW = if (rotation % 180 == 0) srcW else srcH
            val uprightH = if (rotation % 180 == 0) srcH else srcW

            val yPlane = imageProxy.planes[0]
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val buffer = yPlane.buffer
            val data = when {
                pixelStride == 1 && rowStride == srcW ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                pixelStride == 1 -> {
                    // 行步长与宽度不一致（存在对齐填充），逐行紧缩拷贝
                    val tight = ByteArray(srcW * srcH)
                    val row = ByteArray(srcW)
                    for (y in 0 until srcH) {
                        buffer.position(y * rowStride)
                        buffer.get(row)
                        System.arraycopy(row, 0, tight, y * srcW, srcW)
                    }
                    tight
                }
                else -> {
                    // 像素步长非 1（个别 ROM 的 YUV_420_888 实现），带步长抽取
                    val tight = ByteArray(srcW * srcH)
                    for (y in 0 until srcH) {
                        buffer.position(y * rowStride)
                        for (x in 0 until srcW) {
                            tight[y * srcW + x] = buffer.get()
                        }
                    }
                    tight
                }
            }

            val result = decodeEnsemble(data, srcW, srcH)

            // ZXing 仅给出 3 个定位图案中心，旋转到摆正坐标系后推算第 4 角
            val srcPoints = result.resultPoints ?: emptyArray()
            val quad: QrQuad? = if (srcPoints.size >= 3 && uprightW > 0 && uprightH > 0) {
                val rotated = srcPoints.take(3).map { rotatePoint(it.x, it.y, srcW, srcH, rotation) }
                buildQuad(rotated, uprightW, uprightH)
            } else null

            // ISO-8859-1 下 byte 段与字符 1:1 映射，无损还原载荷（与桌面端一致）
            val raw = result.text.toByteArray(Charsets.ISO_8859_1)
            callbackExecutor.execute {
                onFrame(raw)
                onQuad(quad)
            }
        } catch (_: Exception) {
            // 未检出或解码失败：清除锁定框
            callbackExecutor.execute { onQuad(null) }
        } finally {
            imageProxy.close()
            reader.reset()
        }
    }

    /** 将传感器原始坐标 (x,y) 旋转到摆正坐标系。 */
    private fun rotatePoint(x: Float, y: Float, w: Int, h: Int, rotation: Int): Pair<Float, Float> {
        return when (rotation) {
            90 -> (h - 1 - y) to x
            180 -> (w - 1 - x) to (h - 1 - y)
            270 -> y to (w - 1 - x)
            else -> x to y
        }
    }

    /**
     * 由 3 个定位图案中心构建 4 角四边形。
     * 三个定位图案位于左上、右上、左下；先找出直角点（左上），
     * 再按 y 区分右上 / 左下，第 4 角（右下）按平行四边形模型推算。
     */
    private fun buildQuad(points: List<Pair<Float, Float>>, fw: Int, fh: Int): QrQuad? {
        val (a, b, c) = points
        // 两两距离平方，最远的一对为对角线（右上↔左下），第三点即左上
        val dab = dist2(a, b)
        val dac = dist2(a, c)
        val dbc = dist2(b, c)
        val topLeft: Pair<Float, Float>
        val p1: Pair<Float, Float>
        val p2: Pair<Float, Float>
        when {
            dab >= dac && dab >= dbc -> { topLeft = c; p1 = a; p2 = b }
            dac >= dab && dac >= dbc -> { topLeft = b; p1 = a; p2 = c }
            else -> { topLeft = a; p1 = b; p2 = c }
        }
        // y 较小者为右上，较大者为左下（图像坐标 y 向下增长）
        val (topRight, bottomLeft) = if (p1.second <= p2.second) p1 to p2 else p2 to p1
        val bottomRight = (topRight.first + (bottomLeft.first - topLeft.first)) to
            (topRight.second + (bottomLeft.second - topLeft.second))

        val pts = listOf(topLeft, topRight, bottomRight, bottomLeft)
        return QrQuad(
            points = pts.flatMap { listOf(it.first, it.second) }.toFloatArray(),
            frameWidth = fw,
            frameHeight = fh,
        )
    }

    private fun dist2(p: Pair<Float, Float>, q: Pair<Float, Float>): Float {
        val dx = p.first - q.first
        val dy = p.second - q.second
        return dx * dx + dy * dy
    }

    /**
     * 多假设集成解码（借鉴 AI-QR-Code-Decoder 的"冗余证据集成"思想轻量化落地，
     * 并采用"定位→裁剪→规整化→解码"流水线）：
     * 1. 快路径：全帧 Hybrid / Global 直方图二值化直接解码；
     * 2. 定位：在半分辨率帧上用 Detector 找定位图案（便宜），按几何外扩
     *    裁剪全分辨率 QR 区域——排除边缘眩光带/阴影带干扰；
     * 3. 对小裁剪区依次尝试 GH / Sauvola / Hybrid（区域小，Sauvola 成本可接受）；
     * 4. 兜底：全帧 Sauvola 局部自适应阈值二值化
     *    （参考《折腾笔记[47]》：T = mean * (1 + k * (std/R - 1))，
     *    大窗口对屏幕拍摄特有的眩光/阴影/摩尔纹远比 Hybrid 的 8px 分块鲁棒）。
     * 任一候选完整解出（含纠错）即返回，全部失败抛 NotFoundException。
     */
    private fun decodeEnsemble(data: ByteArray, srcW: Int, srcH: Int): com.google.zxing.Result {
        val full = PlanarYUVLuminanceSource(data, srcW, srcH, 0, 0, srcW, srcH, false)
        // 快路径
        try {
            return reader.decode(com.google.zxing.BinaryBitmap(HybridBinarizer(full)), hints)
        } catch (_: Exception) { }
        try {
            return reader.decode(
                com.google.zxing.BinaryBitmap(com.google.zxing.common.GlobalHistogramBinarizer(full)),
                hints,
            )
        } catch (_: Exception) { }
        // 定位阶段：半分辨率 GH 二值化上找定位图案
        val halfW = srcW / 2
        val halfH = srcH / 2
        val half = PlanarYUVLuminanceSource(
            boxDownsample(data, srcW, srcH), halfW, halfH, 0, 0, halfW, halfH, false,
        )
        try {
            val halfBmp = com.google.zxing.BinaryBitmap(
                com.google.zxing.common.GlobalHistogramBinarizer(half),
            )
            // Detector.detect 仅做图案定位（不跑纠错），找到即返回 4 个角点，
            // 即使后续 ECC 会失败也可用作裁剪锚点
            val points = com.google.zxing.qrcode.detector.Detector(halfBmp.blackMatrix).detect(hints).points
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var diag = 0f
            for (p in points) {
                minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
                minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
            }
            for (i in points.indices) for (j in i + 1 until points.size) {
                val dx = points[i].x - points[j].x
                val dy = points[i].y - points[j].y
                diag = maxOf(diag, kotlin.math.sqrt(dx * dx + dy * dy))
            }
            // 半分辨率角点框外扩 25% 对角线（覆盖静区），×2 映射回全分辨率
            val expand = diag * 0.25f
            val left = ((minX - expand) * 2).toInt().coerceIn(0, srcW - 8)
            val top = ((minY - expand) * 2).toInt().coerceIn(0, srcH - 8)
            val size = ((maxOf(maxX - minX, maxY - minY) + 2 * expand) * 2)
                .toInt().coerceAtMost(minOf(srcW - left, srcH - top))
            if (size >= 64) {
                val crop = PlanarYUVLuminanceSource(data, srcW, srcH, left, top, size, size, false)
                try {
                    return reader.decode(
                        com.google.zxing.BinaryBitmap(
                            com.google.zxing.common.GlobalHistogramBinarizer(crop),
                        ),
                        hints,
                    )
                } catch (_: Exception) { }
                try {
                    return reader.decode(
                        com.google.zxing.BinaryBitmap(SauvolaBinarizer(crop, radius = 20)),
                        hints,
                    )
                } catch (_: Exception) { }
                try {
                    return reader.decode(com.google.zxing.BinaryBitmap(HybridBinarizer(crop)), hints)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        // 兜底：全帧 Sauvola
        try {
            return reader.decode(
                com.google.zxing.BinaryBitmap(SauvolaBinarizer(full, radius = 25)),
                hints,
            )
        } catch (_: Exception) { }
        throw com.google.zxing.NotFoundException.getNotFoundInstance()
    }

    /** 2x2 盒式降采样（求平均），抑制摩尔纹并提升模块对比度。 */
    private fun boxDownsample(data: ByteArray, w: Int, h: Int): ByteArray {
        val outW = w / 2
        val outH = h / 2
        val out = ByteArray(outW * outH)
        for (y in 0 until outH) {
            val r0 = (2 * y) * w
            val r1 = r0 + w
            var o = y * outW
            for (x in 0 until outW) {
                val x2 = 2 * x
                val avg = ((data[r0 + x2].toInt() and 0xFF) + (data[r0 + x2 + 1].toInt() and 0xFF) +
                    (data[r1 + x2].toInt() and 0xFF) + (data[r1 + x2 + 1].toInt() and 0xFF) + 2) shr 2
                out[o++] = avg.toByte()
            }
        }
        return out
    }
}

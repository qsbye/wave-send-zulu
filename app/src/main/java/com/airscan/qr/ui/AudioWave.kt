package com.airscan.qr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * 声波频谱包络图：多点贝塞尔（Catmull-Rom 转三次贝塞尔）平滑曲线，
 * 上下镜像成闭合包络面填充，顶缘描边。
 *
 * levels：电平历史（0..1，按时间升序）；active：信号活动状态（决定颜色）。
 * 数据不足两个点时画基线。
 */
@Composable
fun AudioWave(
    levels: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val strokeColor = if (active) Color(0xFF4CD97B) else Color(0xFF6B7684)
    val fillColor = if (active) Color(0x554CD97B) else Color(0x336B7684)
    Canvas(modifier.height(72.dp).fillMaxWidth()) {
        val midY = size.height / 2f
        val n = levels.size
        if (n < 2) {
            drawLine(
                color = Color(0xFF3A424C),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 2f,
            )
            return@Canvas
        }

        val amp = size.height * 0.46f
        val px = { i: Int -> i * size.width / (n - 1) }
        val topY = { i: Int -> midY - levels[i].coerceIn(0f, 1f) * amp }
        val botY = { i: Int -> midY + levels[i].coerceIn(0f, 1f) * amp }

        // Catmull-Rom -> 三次贝塞尔：控制点 1 = p1+(p2-p0)/6，控制点 2 = p2-(p3-p1)/6
        fun addSmoothSegment(path: Path, i: Int, y: (Int) -> Float) {
            val i0 = max(i - 1, 0)
            val i3 = min(i + 2, n - 1)
            val c1x = px(i) + (px(i + 1) - px(i0)) / 6f
            val c1y = y(i) + (y(i + 1) - y(i0)) / 6f
            val c2x = px(i + 1) - (px(i3) - px(i)) / 6f
            val c2y = y(i + 1) - (y(i3) - y(i)) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, px(i + 1).toFloat(), y(i + 1).toFloat())
        }

        // 闭合包络面：顶缘左→右，底缘右→左
        val fillPath = Path()
        fillPath.moveTo(px(0).toFloat(), topY(0).toFloat())
        for (i in 0 until n - 1) addSmoothSegment(fillPath, i, topY)
        fillPath.lineTo(px(n - 1).toFloat(), botY(n - 1).toFloat())
        for (i in n - 2 downTo 0) addSmoothSegment(fillPath, i, botY)
        fillPath.close()
        drawPath(fillPath, fillColor)

        // 顶缘平滑描边
        val strokePath = Path()
        strokePath.moveTo(px(0).toFloat(), topY(0).toFloat())
        for (i in 0 until n - 1) addSmoothSegment(strokePath, i, topY)
        drawPath(strokePath, strokeColor, style = Stroke(width = 2.5f))
    }
}

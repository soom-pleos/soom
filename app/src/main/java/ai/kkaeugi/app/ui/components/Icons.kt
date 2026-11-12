package ai.kkaeugi.app.ui.components

import ai.kkaeugi.app.ui.theme.KSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 아이콘 여섯 개. 전부 24 격자 위에 선으로 직접 그린다(선 굵기 2dp, 둥근 끝). 이미지 리소스나 아이콘 라이브러리는 쓰지 않는다.
 */
enum class KIcon { PERSON, MIC, FRESH_AIR, RECIRC, REST_STOP, WARNING }

@Composable
fun KIconView(icon: KIcon, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = KSize.iconStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
            drawArc(color, start, sweep, false, topLeft = p(cx - r, cy - r), size = Size(2 * r * u, 2 * r * u), style = stroke)
        when (icon) {
            KIcon.PERSON -> {
                drawCircle(color, radius = 3.6f * u, center = p(12f, 7.6f), style = stroke)
                drawPath(Path().apply {
                    moveTo(5f * u, 20f * u)
                    cubicTo(5f * u, 15.6f * u, 8f * u, 13.8f * u, 12f * u, 13.8f * u)
                    cubicTo(16f * u, 13.8f * u, 19f * u, 15.6f * u, 19f * u, 20f * u)
                }, color, style = stroke)
            }
            KIcon.MIC -> {
                drawRoundRect(color, topLeft = p(9f, 3f), size = Size(6f * u, 10.5f * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u), style = stroke)
                arc(12f, 11f, 6f, 0f, 180f)
                line(12f, 17f, 12f, 21f)
                line(9f, 21f, 15f, 21f)
            }
            KIcon.FRESH_AIR -> {
                // 바람 세 줄. 위 두 줄은 끝이 말린다
                drawPath(Path().apply {
                    moveTo(3f * u, 9f * u); lineTo(12.5f * u, 9f * u)
                    arcTo(Rect(p(10f, 4f), Size(5f * u, 5f * u)), 90f, -270f, false)
                }, color, style = stroke)
                drawPath(Path().apply {
                    moveTo(3f * u, 13.5f * u); lineTo(17.5f * u, 13.5f * u)
                    arcTo(Rect(p(15f, 13.5f), Size(5f * u, 5f * u)), 270f, 270f, false)
                }, color, style = stroke)
                line(3f, 18f, 10f, 18f)
            }
            KIcon.RECIRC -> {
                // 실내에서 도는 화살표
                arc(12f, 12f, 7f, 30f, 280f)
                val end = p(16.5f, 6.64f)
                drawLine(color, end, p(15.3f, 2.6f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, end, p(12.3f, 6.2f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            KIcon.REST_STOP -> {
                // 찻잔
                drawPath(Path().apply {
                    moveTo(4.5f * u, 9f * u); lineTo(16f * u, 9f * u); lineTo(16f * u, 14.5f * u)
                    cubicTo(16f * u, 17.5f * u, 14f * u, 19.5f * u, 11f * u, 19.5f * u)
                    lineTo(9.5f * u, 19.5f * u)
                    cubicTo(6.5f * u, 19.5f * u, 4.5f * u, 17.5f * u, 4.5f * u, 14.5f * u)
                    close()
                }, color, style = stroke)
                drawPath(Path().apply {
                    moveTo(16f * u, 10.5f * u); lineTo(17.5f * u, 10.5f * u)
                    cubicTo(20.5f * u, 10.5f * u, 20.5f * u, 15.5f * u, 17.5f * u, 15.5f * u)
                    lineTo(16f * u, 15.5f * u)
                }, color, style = stroke)
                line(8f, 4f, 8f, 6f)
                line(12.5f, 4f, 12.5f, 6f)
            }
            KIcon.WARNING -> {
                drawPath(Path().apply {
                    moveTo(12f * u, 4f * u); lineTo(21f * u, 19.5f * u); lineTo(3f * u, 19.5f * u); close()
                }, color, style = stroke)
                line(12f, 10f, 12f, 14f)
                drawCircle(color, radius = 1.2f * u, center = p(12f, 16.8f))
            }
        }
    }
}

/** DrawScope 안에서 쓰는 둥근 끝 선 */
internal fun DrawScope.roundLine(color: Color, from: Offset, to: Offset, width: Float) =
    drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round)

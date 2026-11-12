package ai.kkaeugi.app.ui.components

import ai.kkaeugi.app.ui.theme.K
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 좌석 지도. 차를 위에서 본 선 그림에 좌석 다섯 개를 그린다. 이미지 없이 Canvas 로만 그린다.
 * 그림은 세로로 길다(가로 1 : 세로 1.9 안팎). 주어진 칸 안에서 비율을 지켜 가운데에 둔다.
 * seats: 다섯 자리의 값. 순서는 운전석(왼쪽 앞), 조수석, 뒷줄 왼쪽, 뒷줄 가운데, 뒷줄 오른쪽.
 *  true 는 채운 자리, false 는 빈 윤곽, null(그 자리 값을 못 읽음)은 점선 윤곽.
 * 차가 자리별 값을 주면 그 값 그대로다(seatMapUi 가 고른다). 색은 쓰지 않는다.
 * marked: 프로필을 고른 자리(v0.7.3). 자리 가운데에 작은 점을 찍는다. 어떤 프로필인지는 옆의 글이 말한다.
 * selected: 지금 설정 시트가 열려 있는 자리. 굵은 테두리를 두른다.
 * onSeatTap: 자리를 눌렀을 때(정차 화면에서만 준다). 자리 상자보다 조금 넓게 받는다
 */
@Composable
fun SeatMap(
    seats: List<Boolean?>, modifier: Modifier = Modifier,
    marked: List<Boolean> = emptyList(), selected: Int? = null, onSeatTap: ((Int) -> Unit)? = null,
) {
    val c = K.colors
    val tap = if (onSeatTap == null) Modifier else Modifier.pointerInput(onSeatTap) {
        detectTapGestures { pos ->
            val g = SeatGeometry(Size(size.width.toFloat(), size.height.toFloat()))
            val pad = 14.dp.toPx()
            val hit = g.boxes.indexOfFirst { r -> pos.x in (r.left - pad)..(r.right + pad) && pos.y in (r.top - pad)..(r.bottom + pad) }
            if (hit >= 0) onSeatTap(hit)
        }
    }
    Canvas(modifier.then(tap)) {
        val g = SeatGeometry(size)
        val w = g.w
        fun x(f: Float) = g.x(f)
        fun y(f: Float) = g.y(f)
        val thin = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        // 차체: 앞(위)이 조금 더 둥글다
        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    Rect(x(0.06f), y(0.02f), x(0.94f), y(0.98f)),
                    topLeft = CornerRadius(w * 0.34f, w * 0.38f), topRight = CornerRadius(w * 0.34f, w * 0.38f),
                    bottomLeft = CornerRadius(w * 0.24f, w * 0.22f), bottomRight = CornerRadius(w * 0.24f, w * 0.22f),
                ),
            )
        }
        drawPath(body, c.faint, style = thin)
        // 앞 유리와 뒤 유리
        drawPath(Path().apply { moveTo(x(0.19f), y(0.255f)); quadraticBezierTo(x(0.5f), y(0.185f), x(0.81f), y(0.255f)) }, c.faint, style = thin)
        drawPath(Path().apply { moveTo(x(0.21f), y(0.845f)); quadraticBezierTo(x(0.5f), y(0.895f), x(0.79f), y(0.845f)) }, c.faint, style = thin)
        // 사이드 미러
        drawLine(c.faint, Offset(x(0.06f), y(0.275f)), Offset(x(-0.02f), y(0.262f)), strokeWidth = thin.width, cap = StrokeCap.Round)
        drawLine(c.faint, Offset(x(0.94f), y(0.275f)), Offset(x(1.02f), y(0.262f)), strokeWidth = thin.width, cap = StrokeCap.Round)

        // 좌석: 앞줄 둘, 뒷줄 셋
        val boxes = g.boxes
        val radius = CornerRadius(w * 0.07f)
        val dashed = Stroke(width = thin.width, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())))
        boxes.forEachIndexed { i, r ->
            when (seats.getOrNull(i)) {
                true -> drawRoundRect(c.ink, topLeft = r.topLeft, size = r.size, cornerRadius = radius)
                false -> drawRoundRect(c.outline, topLeft = r.topLeft, size = r.size, cornerRadius = radius, style = thin)
                null -> drawRoundRect(c.outline, topLeft = r.topLeft, size = r.size, cornerRadius = radius, style = dashed)
            }
            // 프로필을 고른 자리: 가운데 작은 점. 채운 자리 위에서는 면 색, 빈자리에서는 본문 색
            if (marked.getOrNull(i) == true) drawCircle(if (seats.getOrNull(i) == true) c.surface else c.ink, radius = w * 0.042f, center = r.center)
            // 설정 시트가 열려 있는 자리: 한 겹 바깥에 굵은 테두리
            if (selected == i) {
                val gap = 4.dp.toPx()
                drawRoundRect(
                    c.ink, topLeft = Offset(r.left - gap, r.top - gap), size = Size(r.width + gap * 2, r.height + gap * 2),
                    cornerRadius = CornerRadius(w * 0.07f + gap), style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        // 운전대: 운전석 앞의 작은 호
        val wheelR = w * 0.075f
        drawArc(c.faint, 200f, 140f, false, topLeft = Offset(x(0.305f) - wheelR, y(0.318f) - wheelR), size = Size(wheelR * 2, wheelR * 2), style = thin)
    }
}

/** 좌석 지도의 자리 잡기. 그리는 쪽과 누른 자리를 찾는 쪽이 같은 값을 쓴다 */
private class SeatGeometry(size: Size) {
    private val ratio = 1.9f
    private val h = minOf(size.height, size.width * ratio)
    val w = h / ratio
    private val left = (size.width - w) / 2
    private val top = (size.height - h) / 2
    fun x(f: Float) = left + w * f
    fun y(f: Float) = top + h * f
    private val frontW = 0.27f; private val frontH = 0.17f; private val frontY = 0.33f
    private val rearW = 0.215f; private val rearH = 0.15f; private val rearY = 0.62f
    val boxes = listOf(
        Rect(x(0.17f), y(frontY), x(0.17f + frontW), y(frontY + frontH)),
        Rect(x(0.56f), y(frontY), x(0.56f + frontW), y(frontY + frontH)),
        Rect(x(0.155f), y(rearY), x(0.155f + rearW), y(rearY + rearH)),
        Rect(x(0.3925f), y(rearY), x(0.3925f + rearW), y(rearY + rearH)),
        Rect(x(0.63f), y(rearY), x(0.63f + rearW), y(rearY + rearH)),
    )
}

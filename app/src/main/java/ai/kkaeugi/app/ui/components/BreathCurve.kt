package ai.kkaeugi.app.ui.components

import ai.kkaeugi.app.ui.BreathText
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KMotion
import ai.kkaeugi.app.ui.theme.LocalMotion
import ai.kkaeugi.app.ui.theme.bold
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 숨 곡선. 주행 홈의 주인공이다.
 *  - 실제: 굵은 파랑 실선, 끝점에 점, 아래에 아주 옅은 면
 *  - 내기만 탔다면: 가는 회색 실선(같은 입력으로 계산한 그림자). 끝에 "내기만 2,480"
 *  - 예보: 파랑 점선. 끝에 "예보"
 *  - 한도: 빨강 가는 선, 오른쪽 끝에 "한도 2,000"(이 화면에서 색을 입힌 글자는 이것 하나다)
 *  - 지금: 세로선. 가로 눈금은 "지금, +15분, +30분"
 * 세로 눈금 글자와 범례 상자는 없다. 이름은 선 끝에 바로 붙인다.
 * 재료(BreathText.Chart)는 0~1 로 맞춰져 있다. 세로는 한도와 가장 높은 점이 다 보이는 데까지만 편다.
 * 그림의 왼쪽 끝은 주어진 칸의 왼쪽 끝과 같다(위의 큰 숫자, 아래 문장과 같은 세로선에 선다)
 */
@Composable
fun BreathCurve(
    chart: BreathText.Chart,
    limitLabel: String,
    /** "내기만 탔다면 2,480". 그림자가 실제와 거의 같으면 null(이름을 붙이지 않는다) */
    shadowLabel: String?,
    /** 가로 눈금 단위: "분", 데모 속도면 "초" */
    axisUnit: String,
    modifier: Modifier = Modifier,
    forecastLabel: String = "예보",
) {
    val c = K.colors
    val type = K.type
    val measurer = rememberTextMeasurer()
    val labelStyle = type.sub.copy(color = c.sub)
    val tickStyle = type.sub.copy(color = c.faint)
    val nowStyle = type.sub.bold.copy(color = c.ink)
    val limitStyle = type.sub.copy(color = c.limit)
    Box(modifier) {
    Canvas(Modifier.fillMaxSize()) {
        val g = CurveFrame(chart, size.width, size.height, 112.dp.toPx(), 32.dp.toPx(), 12.dp.toPx())
        val plotW = g.plotW
        val base = g.base
        val topPad = g.topPad
        fun px(x: Float) = g.px(x)
        fun py(y: Float) = g.py(y)
        fun at(p: BreathText.ChartPoint) = Offset(px(p.x), py(p.y))
        fun path(points: List<BreathText.ChartPoint>) = Path().apply {
            points.forEachIndexed { i, p -> val o = at(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        }
        fun text(l: TextLayoutResult, x: Float, y: Float) = drawText(l, topLeft = Offset(x, y))

        val nowX = px(chart.nowX)
        val limitY = py(chart.limitY)

        // 바닥선과 "지금" 세로선
        drawLine(c.line, Offset(0f, base), Offset(plotW, base), strokeWidth = 1.dp.toPx())
        drawLine(c.outline, Offset(nowX, topPad), Offset(nowX, base), strokeWidth = 1.dp.toPx())

        // 그림자(내기만 탔다면)
        if (chart.shadow.size >= 2) drawPath(path(chart.shadow), c.faint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // 실제 곡선 아래 옅은 면
        if (chart.actual.size >= 2) {
            val fill = path(chart.actual).apply {
                lineTo(px(chart.actual.last().x), base); lineTo(px(chart.actual.first().x), base); close()
            }
            drawPath(fill, c.accent.copy(alpha = if (c.dark) 0.14f else 0.08f))
        }

        // 한도선
        drawLine(c.limit, Offset(0f, limitY), Offset(plotW + 6.dp.toPx(), limitY), strokeWidth = 1.5.dp.toPx())

        // 예보(점선), 실제(굵게)
        if (chart.forecast.size >= 2) drawPath(
            path(chart.forecast), c.accent,
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 9.dp.toPx()))),
        )
        if (chart.actual.size >= 2) drawPath(path(chart.actual), c.accent, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        chart.actual.lastOrNull()?.let { p ->
            val o = at(p)
            drawCircle(c.surface, radius = 9.dp.toPx(), center = o)
            drawCircle(c.accent, radius = 6.dp.toPx(), center = o)
        }

        // 선 끝 이름: 한도, 예보(오른쪽 여백), 내기만 탔다면(그림자 끝의 왼쪽 위)
        val labelX = plotW + 14.dp.toPx()
        val limitText = measurer.measure(limitLabel, limitStyle)
        val limitTop = (limitY - limitText.size.height / 2f).coerceIn(0f, base - limitText.size.height)
        text(limitText, labelX, limitTop)
        chart.forecast.lastOrNull()?.let { end ->
            val l = measurer.measure(forecastLabel, labelStyle)
            var top = py(end.y) - l.size.height / 2f
            val gap = limitText.size.height.toFloat()
            if (abs(top - limitTop) < gap) top = if (top >= limitTop) limitTop + gap else limitTop - gap
            text(l, labelX, top.coerceIn(0f, base - l.size.height))
        }
        if (shadowLabel != null) chart.shadow.lastOrNull()?.let { end ->
            val l = measurer.measure(shadowLabel, labelStyle)
            val endY = py(end.y)
            val x = (px(end.x) - l.size.width - 12.dp.toPx()).coerceAtLeast(0f)
            // 그림자는 오르는 선이라 끝점의 왼쪽 위가 비어 있다. 천장에 닿았으면 선 아래에 적는다
            var top = endY - l.size.height - 8.dp.toPx()
            if (top < 0f) top = endY + 8.dp.toPx()
            // 한도선에 걸치면 한도선 위로 올린다
            if (top < limitY && top + l.size.height > limitY) top = limitY - l.size.height - 4.dp.toPx()
            text(l, x, top.coerceAtLeast(0f))
        }

        // 가로 눈금
        val axisTop = base + 6.dp.toPx()
        val nowText = measurer.measure("지금", nowStyle)
        text(nowText, (nowX - nowText.size.width / 2f).coerceAtLeast(0f), axisTop)
        val span = (1f - chart.nowX).coerceAtLeast(0.01f)
        val half = measurer.measure("+${(BreathText.CHART_AHEAD_MIN / 2).toInt()}$axisUnit", tickStyle)
        val halfX = px(chart.nowX + span / 2)
        drawLine(c.line, Offset(halfX, base), Offset(halfX, base + 5.dp.toPx()), strokeWidth = 1.dp.toPx())
        text(half, halfX - half.size.width / 2f, axisTop)
        val full = measurer.measure("+${BreathText.CHART_AHEAD_MIN.toInt()}$axisUnit", tickStyle)
        text(full, plotW - full.size.width, axisTop)
    }
    // 끝점의 느린 숨쉬기(3초 주기, 아주 약하게). 곡선은 다시 그리지 않고 고리 하나만 따로 그린다.
    // 스크린샷과 프리뷰에서는 없다
    if (LocalMotion.current && chart.actual.isNotEmpty()) {
        val breath by rememberInfiniteTransition(label = "breath").animateFloat(0f, 1f, infiniteRepeatable(tween(KMotion.BREATH_MS, easing = LinearEasing), RepeatMode.Restart), label = "ring")
        Canvas(Modifier.fillMaxSize()) {
            val g = CurveFrame(chart, size.width, size.height, 112.dp.toPx(), 32.dp.toPx(), 12.dp.toPx())
            val end = chart.actual.last()
            drawCircle(c.accent.copy(alpha = 0.30f * (1f - breath)), radius = (10f + 10f * breath).dp.toPx(), center = Offset(g.px(end.x), g.py(end.y)), style = Stroke(2.dp.toPx()))
        }
    }
    }
}

/** 곡선을 그릴 자리의 셈: 오른쪽 이름 자리(gutter), 아래 눈금 자리(axisH), 위 여백을 뺀 그림 영역과 좌표 변환 */
private class CurveFrame(chart: BreathText.Chart, width: Float, height: Float, gutter: Float, axisH: Float, val topPad: Float) {
    val plotW = (width - gutter).coerceAtLeast(1f)
    val base = height - axisH
    private val plotH = (base - topPad).coerceAtLeast(1f)
    /** 세로는 한도와 가장 높은 점이 다 보이는 데까지만 편다 */
    private val visibleTop = maxOf(chart.limitY * 1.28f, ((chart.actual + chart.shadow + chart.forecast).maxOfOrNull { it.y } ?: 0f) * 1.08f).coerceIn(0.05f, 1f)
    fun px(x: Float) = x * plotW
    fun py(y: Float) = base - plotH * (y / visibleTop).coerceIn(0f, 1f)
}

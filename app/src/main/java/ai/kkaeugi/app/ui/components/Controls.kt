package ai.kkaeugi.app.ui.components

import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KRadius
import ai.kkaeugi.app.ui.theme.KSize
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.bold
import ai.kkaeugi.app.ui.theme.semi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 주 버튼: 파랑 바탕에 흰 글자. 화면마다 하나만 쓴다 */
@Composable
fun KPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TextStyle = K.type.title, enabled: Boolean = true) {
    val c = K.colors
    Box(
        modifier.defaultMinSize(minHeight = KSize.control).clip(RoundedCornerShape(KRadius.control))
            .background(if (enabled) c.primary else c.outline)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = KSpace.md),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = style.semi, color = c.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/**
 * 보조 버튼: 회색 면에 본문 색 글자, 테두리 없음.
 * onBg: 화면 바탕 위에 놓일 때(면 위가 아닐 때). 바탕과 구별되게 한 단계 진한 회색을 쓴다
 */
@Composable
fun KSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TextStyle = K.type.title, enabled: Boolean = true, onBg: Boolean = false) {
    val c = K.colors
    Box(
        modifier.defaultMinSize(minHeight = KSize.control).clip(RoundedCornerShape(KRadius.control))
            .background(if (onBg) c.fillOnBg else c.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = KSpace.md),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = style.semi, color = if (enabled) c.ink else c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** 누르는 글자("펼치기", "접기"). 색 글자는 이런 링크성 동작에만 쓴다 */
@Composable
fun KTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(KRadius.control)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = KSpace.xs, vertical = KSpace.xs),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = K.type.sub, color = K.colors.accent, maxLines = 1) }
}

/** 작은 조각 버튼(개발자 영역의 검증 버튼, 데모 콘솔). 회색 면, 고른 것은 본문 색 바탕 */
@Composable
fun KChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val c = K.colors
    Box(
        modifier.clip(RoundedCornerShape(KRadius.control)).background(if (selected) c.ink else c.fill)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = KSpace.sm, vertical = KSpace.xs),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = K.type.note.semi, color = if (selected) c.surface else c.ink, maxLines = 1) }
}

/** 스위치. 켜짐은 파랑, 꺼짐은 회색. 손잡이는 흰색 */
@Composable
fun KSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = K.colors
    Box(
        Modifier.size(width = 56.dp, height = 34.dp).clip(CircleShape)
            .background(when { !enabled -> c.line; checked -> c.accent; else -> c.outline })
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(26.dp).background(c.onPrimary, CircleShape)) }
}

/** 설정 목록 한 행(높이 72dp 이상): 제목(중간 굵기), 설명(보조), 오른쪽 조작부 */
@Composable
fun KSettingRow(title: String, modifier: Modifier = Modifier, detail: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = KSize.row).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = KSpace.lg, vertical = KSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = K.type.body.semi, color = K.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = K.type.sub, color = K.colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(KSpace.sm))
        trailing()
    }
}

/** 설정 "켜고 끄기" 한 행 */
@Composable
fun KToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null, enabled: Boolean = true) {
    KSettingRow(title, detail = detail) { KSwitch(checked, onChange, enabled) }
}

/** 여럿 가운데 하나 고르기. 회색 면 위에서 고른 칸만 떠 있다(낮에는 흰 면, 밤에는 한 단계 밝은 회색) */
@Composable
fun <T> KSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = K.colors
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(KRadius.control)).background(c.fill).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { o ->
            val on = o == selected && enabled
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .background(if (on) (if (c.dark) c.outline else c.surface) else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o), style = if (on) K.type.sub.bold else K.type.sub,
                    color = when { !enabled -> c.faint; on -> c.ink; else -> c.sub }, maxLines = 1, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 가는 가로 구분선 */
@Composable
fun KHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(KSize.hairline).background(K.colors.line))
}

/** 상태를 뜻하는 작은 점. 글자는 본문 색으로 두고 색은 점으로만 싣는다 */
@Composable
fun KStatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** 점 하나와 굵은 한 단어("쾌적", "탁함", "한도"). 글자는 언제나 본문 색이다 */
@Composable
fun KStatusWord(text: String, dot: Color, modifier: Modifier = Modifier, style: TextStyle = K.type.body) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        KStatusDot(dot)
        Spacer(Modifier.width(KSpace.xs))
        Text(text, style = style.bold, color = K.colors.ink, maxLines = 1)
    }
}

/**
 * 숫자와 단위. 단위는 숫자보다 두 단계 작은 크기, 흐림 색으로 숫자 기준선에 붙인다.
 * 기본은 제목 크기 숫자에 보조 크기 단위("2" + "명", "12" + "km")
 */
@Composable
fun KNumberUnit(number: String, unit: String, modifier: Modifier = Modifier, numberStyle: TextStyle = K.type.title, unitStyle: TextStyle = K.type.sub) {
    Row(modifier) {
        Text(number, style = numberStyle.bold, color = K.colors.ink, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            Text(unit, style = unitStyle, color = K.colors.faint, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        }
    }
}

/**
 * 남은 시간 원형 표시. 가운데 남은 초. remaining 이 null 이면(아직 읽는 중) 빈 고리만 둔다.
 * 색은 본문 색(강조색을 쓰지 않는다)
 */
@Composable
fun KCountdownRing(remaining: Int?, total: Int, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val c = K.colors
    val fraction = if (remaining == null) 0f else (remaining / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = 4.dp.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(c.line, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w))
            if (fraction > 0f) drawArc(c.ink, -90f, 360f * fraction, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        if (remaining != null) Text(remaining.toString(), style = K.type.body.bold, color = c.ink, maxLines = 1)
    }
}

/** 이름과 값을 위아래로 쌓은 작은 수치. 여러 개를 같은 너비 칸에 나란히 놓으면 줄이 맞는다 */
@Composable
fun KMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = K.type.sub, color = K.colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = K.type.body.bold, color = K.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

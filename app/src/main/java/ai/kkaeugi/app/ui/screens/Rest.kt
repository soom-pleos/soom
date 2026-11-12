package ai.kkaeugi.app.ui.screens

import ai.kkaeugi.app.DemoText
import ai.kkaeugi.app.Proposal
import ai.kkaeugi.app.RestUi
import ai.kkaeugi.app.ui.REST_CO_NOTE
import ai.kkaeugi.app.ui.REST_LEAVE_BUTTON
import ai.kkaeugi.app.ui.components.KCountdownRing
import ai.kkaeugi.app.ui.components.KSecondaryButton
import ai.kkaeugi.app.ui.components.KPrimaryButton
import ai.kkaeugi.app.ui.countdownLeft
import ai.kkaeugi.app.ui.restLeaveNote
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KRadius
import ai.kkaeugi.app.ui.theme.KSize
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.bold
import ai.kkaeugi.engine.restStopDisplayName
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 쉼 모드. 차분한 전면 화면: 맨 위 한 문장, 가운데 큰 원형 타이머, 아래 한 줄 안내, 버튼 하나.
 * 바깥 공기 질문이 있으면(쉼 진입 때 내기순환이었을 때) 버튼 자리에 질문과 버튼 둘이 온다.
 * 버튼 "화면 닫기"는 이 화면만 닫고 홈으로 돌아간다. 쉼 모드는 기어를 D 로 넣으면 엔진이 끝낸다. 그래서 버튼 아래에 그렇게 적는다.
 * 맨 아래 고정 문구는 숨이 하는 추정의 범위를 밝힌다. 주석은 이 두 줄뿐이다
 */
@Composable
internal fun RestScreen(rest: RestUi, question: Proposal?, nowMs: Long, freshAir: Boolean, napTip: String?, onYes: () -> Unit, onNo: () -> Unit, onLeave: () -> Unit) {
    val c = K.colors
    val totalSec = (if (rest.demo) rest.targetMinutes else rest.targetMinutes * 60).coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) {}.padding(KSpace.lg)) {
        // 고리 안에 표제 숫자("14:52")가 여유 있게 들어가는 크기
        val ring = (maxHeight * 0.5f).coerceIn(300.dp, 360.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                DemoText.restLine(restStopDisplayName(rest.stopName), rest.targetMinutes, rest.demo, walk = rest.walk),
                style = K.type.title, color = c.ink, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 6.dp.toPx()
                    val arc = Size(size.width - w, size.height - w)
                    drawArc(c.line, 0f, 360f, false, topLeft = Offset(w / 2, w / 2), size = arc, style = Stroke(w))
                    val left = (rest.remainingSec / totalSec.toFloat()).coerceIn(0f, 1f)
                    if (left > 0f) drawArc(c.accent, -90f, 360f * left, false, topLeft = Offset(w / 2, w / 2), size = arc, style = Stroke(w, cap = StrokeCap.Round))
                }
                Text("${rest.remainingSec / 60}:${"%02d".format(rest.remainingSec % 60)}", style = K.type.display, color = c.ink, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            // 낮잠 안내(엔진 NapGuide). 큰 타이머가 그 타이머다. 연구가 있다는 데까지만 말한다
            if (napTip != null) {
                Text(napTip, style = K.type.body, color = c.sub, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(KSpace.sm))
            }
            if (question != null) {
                val remaining = countdownLeft(question.timeoutSec, question.countdownFrom, nowMs)
                Row(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(KRadius.surface)).padding(horizontal = KSpace.lg, vertical = KSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(question.text, style = K.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (remaining == null) "화면에서 눌러 주세요" else "${remaining}초 뒤 그대로 둬요 · 화면에서 눌러 주세요", style = K.type.body, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(KSpace.sm))
                    KCountdownRing(remaining, question.timeoutSec)
                    Spacer(Modifier.width(KSpace.md))
                    KPrimaryButton("응", onYes, Modifier.width(220.dp).height(KSize.touchMin))
                    Spacer(Modifier.width(KSpace.sm))
                    KSecondaryButton("괜찮아", onNo, Modifier.width(180.dp).height(KSize.touchMin))
                }
            } else {
                KSecondaryButton(REST_LEAVE_BUTTON, onLeave, Modifier.width(KSize.wideButton).height(KSize.touchMin), onBg = true)
                Spacer(Modifier.height(KSpace.xs))
                Text(restLeaveNote(freshAir), style = K.type.note, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(KSpace.sm))
            Text(REST_CO_NOTE, style = K.type.note, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 쉼이 끝난 직후 2초 동안 보여 주는 한 줄 */
@Composable
internal fun RestEndedToast(text: String) {
    val c = K.colors
    Box(Modifier.fillMaxSize().padding(KSpace.lg), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.background(c.ink, RoundedCornerShape(KRadius.control)).padding(horizontal = KSpace.lg, vertical = KSpace.sm)) {
            Text(text, style = K.type.body.bold, color = c.surface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

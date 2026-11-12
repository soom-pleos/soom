package ai.kkaeugi.app.ui.screens

import ai.kkaeugi.app.Proposal
import ai.kkaeugi.app.speech.announceHint
import ai.kkaeugi.app.speech.proposalHint
import ai.kkaeugi.app.ui.answerButtonLayout
import ai.kkaeugi.app.ui.components.KCountdownRing
import ai.kkaeugi.app.ui.components.KIcon
import ai.kkaeugi.app.ui.components.KIconView
import ai.kkaeugi.app.ui.components.KSecondaryButton
import ai.kkaeugi.app.ui.components.KPrimaryButton
import ai.kkaeugi.app.ui.countdownLeft
import ai.kkaeugi.app.ui.hintAnswerPart
import ai.kkaeugi.app.ui.keepWords
import ai.kkaeugi.app.ui.splitProposal
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KMotion
import ai.kkaeugi.app.ui.theme.KRadius
import ai.kkaeugi.app.ui.theme.KSize
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.LocalMotion
import ai.kkaeugi.engine.TriggerKind
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 아래에서 올라오는 넓은 시트의 틀. 높이는 내용에 맞춘다(가장 낮을 때의 높이만 정해 둔다).
 * 위 묶음(이유와 물음)은 시트 위쪽에, 아래 묶음(버튼과 안내)은 시트 아래쪽에 붙는다.
 * 그래서 물음이 한 줄이든 두 줄이든 버튼은 화면 아래에서 늘 같은 자리에 있고, 문장과 버튼 사이가 비지 않는다.
 * 전체를 어둡게 덮지 않는다. 바탕색을 엷게 올려 뒤를 흐리게만 한다. 시트 밖을 눌러도 아무 일도 없다(뒤 화면으로 새지 않게만 막는다).
 * 올라오는 데 250ms. 스크린샷과 프리뷰에서는 움직이지 않는다
 */
@Composable
internal fun BottomSheet(top: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit) {
    val c = K.colors
    val motion = LocalMotion.current
    val shown = remember { MutableTransitionState(!motion) }.apply { targetState = true }
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.6f)).pointerInput(Unit) {}) {
        AnimatedVisibility(
            visibleState = shown, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(KMotion.SHEET_MS)) { it } + fadeIn(tween(KMotion.SHEET_MS)),
        ) {
            val shape = RoundedCornerShape(topStart = KRadius.surface, topEnd = KRadius.surface)
            Column(
                Modifier.fillMaxWidth().heightIn(min = SheetMinHeight).clip(shape).background(c.surface).padding(KSpace.lg),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = KSpace.md), content = top)
                Column(Modifier.fillMaxWidth(), content = bottom)
            }
        }
    }
}

/** 시트의 가장 낮은 높이. 내용이 더 길면 그만큼 커진다 */
private val SheetMinHeight = 300.dp

/**
 * 제안 시트. 운전 중 2초 안에 읽혀야 한다.
 *  이유 한 줄(본문 크기, 보조 색) → 물음(제목, 두 줄까지) → 버튼 둘(높이 96dp, 가로 3:2. "응"은 파랑, "괜찮아"는 회색 면)
 *  → 마이크 아이콘과 말로 답하는 방법, 오른쪽에 남은 시간 원형 표시.
 * 읽는 동안은 "말하는 중...", 다 읽은 뒤(SpeechFinished)부터 카운트다운.
 * 조수석에 사람이 있으면(passengerMayAnswer) 버튼 묶음을 화면 오른쪽(조수석 쪽)에 붙이고 키운다
 */
@Composable
internal fun ProposalSheet(p: Proposal, nowMs: Long, voiceAnswer: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    val c = K.colors
    val remaining = countdownLeft(p.timeoutSec, p.countdownFrom, nowMs)
    val layout = answerButtonLayout(p.passengerMayAnswer)
    val parts = splitProposal(p.text)
    BottomSheet(
        top = {
            parts.reason?.let { reason ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.kind == TriggerKind.DROWSY) {
                        KIconView(KIcon.WARNING, c.limit, size = 24.dp)
                        Spacer(Modifier.width(KSpace.xs))
                    }
                    Text(reason, style = K.type.body, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // 줄 길이를 고르게 나눠 마지막 줄에 한두 글자만 남지 않게 한다
            Text(keepWords(parts.question), style = K.type.title.copy(lineBreak = LineBreak.Heading), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        bottom = {
            val align = if (layout.passengerSide) Alignment.End else Alignment.Start
            Row(Modifier.fillMaxWidth(layout.widthFraction).align(align), horizontalArrangement = Arrangement.spacedBy(KSpace.sm)) {
                KPrimaryButton("응", onYes, Modifier.weight(3f).height(layout.heightDp.dp))
                KSecondaryButton("괜찮아", onNo, Modifier.weight(2f).height(layout.heightDp.dp))
            }
            Spacer(Modifier.height(KSpace.sm))
            HintRow(proposalHint(remaining, voiceAnswer), voiceAnswer, remaining, p.timeoutSec, Modifier.fillMaxWidth(layout.widthFraction).align(align))
        },
    )
}

/**
 * 예고 시트(맡겨 둔 환기). 묻지 않고 예고한다: 문장, [취소] 하나, 5초 카운트다운.
 * "바로 해 줘"는 버튼이 없다. 말("응")로만 받는다. 아무 답이 없으면 카운트다운 뒤 엔진이 실행한다
 */
@Composable
internal fun AnnounceSheet(text: String, cancelWindowSec: Int, countdownFrom: Long?, nowMs: Long, voiceAnswer: Boolean, onCancel: () -> Unit) {
    val c = K.colors
    val remaining = countdownLeft(cancelWindowSec, countdownFrom, nowMs)
    val parts = splitProposal(text)
    BottomSheet(
        top = {
            Text("맡겨 둔 환기", style = K.type.body, color = c.sub, maxLines = 1)
            // 문장의 뒷부분("싫으면 …")은 적지 않는다. 같은 안내가 맨 아래 줄에 있다
            Text(keepWords(parts.question), style = K.type.title.copy(lineBreak = LineBreak.Heading), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        bottom = {
            KSecondaryButton("취소", onCancel, Modifier.fillMaxWidth(0.6f).height(KSize.answer))
            Spacer(Modifier.height(KSpace.sm))
            HintRow(announceHint(remaining, voiceAnswer), voiceAnswer, remaining, cancelWindowSec, Modifier.fillMaxWidth())
        },
    )
}

/** 시트 맨 아래 줄: 마이크 아이콘과 답하는 방법, 오른쪽 끝에 남은 시간 원형 표시 */
@Composable
internal fun HintRow(hint: String, voiceAnswer: Boolean, remaining: Int?, total: Int, modifier: Modifier = Modifier) {
    val c = K.colors
    Row(modifier.height(KSize.control), verticalAlignment = Alignment.CenterVertically) {
        if (voiceAnswer && remaining != null) {
            KIconView(KIcon.MIC, c.sub, size = 24.dp)
            Spacer(Modifier.width(KSpace.xs))
        }
        Text(hintAnswerPart(hint), style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(KSpace.sm))
        KCountdownRing(remaining, total)
    }
}

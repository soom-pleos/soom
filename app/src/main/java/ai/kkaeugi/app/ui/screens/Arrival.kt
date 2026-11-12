package ai.kkaeugi.app.ui.screens

import ai.kkaeugi.app.DemoText
import ai.kkaeugi.app.signals.OpenMeteoAir
import ai.kkaeugi.app.ui.BreathText
import ai.kkaeugi.app.ui.SeatText
import ai.kkaeugi.app.ui.arrivalLineCount
import ai.kkaeugi.app.ui.components.KHairline
import ai.kkaeugi.app.ui.keepWords
import ai.kkaeugi.app.ui.components.KMetric
import ai.kkaeugi.app.ui.components.KSecondaryButton
import ai.kkaeugi.app.ui.components.KPrimaryButton
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KRadius
import ai.kkaeugi.app.ui.theme.KSize
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.bold
import ai.kkaeugi.app.ui.trustAnswerLine
import ai.kkaeugi.engine.ArrivalSummary
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 도착 카드(전면 화면). v0.7.3 부터 주인공은 자리별 요약이다.
 *  맨 위: 한 문장("오늘 2시간 10분, 넷이 탔어요.")과 그 아래 환기 한 줄.
 *  가운데 면: 자리마다 한 줄(자리 이름과 설정, 탁한 공기에 있던 시간, 미세먼지 평균, 가장 오래 앉은 시간, 걷기 쉼).
 *    숫자는 고정폭이고 단위는 작고 흐리다. 자리별 값이 없는 주행은 "차 전체" 한 줄이다. 색은 쓰지 않는다.
 *  그 아래 한 줄: "내기만 탔다면 / 외기만 탔다면"(세 갈래 비교를 접은 것).
 *  맡기기 질문은 문장과 버튼 둘을 한 줄짜리 면에 담는다. [확인] 은 언제나 오른쪽 아래에 있고, 이 화면의 파랑은 주 버튼 하나다.
 *  맨 아래 주석 두 줄: 전부 추정이라는 것과 자료 출처.
 * 자리별 누적이 없는 옛 요약(cabin 이 null)은 수치 줄과 기록으로 그린다.
 * 문장과 숫자는 SeatText, DemoText 가 만든다(데모 속도면 한도 위 시간과 앉은 시간을 초로)
 */
@Composable
internal fun ArrivalScreen(card: ArrivalSummary, maxOccupants: Int, demoFast: Boolean, freshUnits: Int, trustAnswered: Boolean?, onTrust: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = K.colors
    val offer = card.trustOffer
    val threeWay = card.threeWay
    val rows = SeatText.rows(card, maxOccupants, demoFast)
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) {}) {
        // 면 밖의 글도 면 안의 글과 같은 왼쪽 선에 맞춘다
        val inset = Modifier.padding(horizontal = KSpace.lg)
        Column(Modifier.fillMaxSize().padding(KSpace.lg)) {
            Column(inset) {
                Text(if (demoFast) "오늘의 주행 (데모 속도: 분이 초로 흐름)" else "오늘의 주행", style = K.type.sub, color = c.sub, maxLines = 1)
                Text(SeatText.headline(card, demoFast), style = K.type.title, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // 제목이 자리별 문장이면 환기 한 줄(엔진의 첫 문장)을 그 아래에 적는다
                Text(
                    if (card.seatHeadline.isNotBlank()) DemoText.arrivalHeadline(card, demoFast) else DemoText.arrivalAirLine(card, maxOccupants, demoFast),
                    style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(KSpace.sm))

            if (rows.isNotEmpty()) {
                // 줄이 적으면 줄 높이만큼만, 많으면 남은 높이를 나눠 쓴다
                val rowHeight = if (offer != null) 46.dp else 60.dp
                Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(KRadius.surface)).padding(horizontal = KSpace.lg, vertical = KSpace.sm)) {
                    SeatHeader()
                    rows.forEachIndexed { i, row ->
                        KHairline()
                        SeatRow(row, Modifier.height(rowHeight))
                        if (i == rows.lastIndex) Spacer(Modifier.height(4.dp))
                    }
                }
                if (threeWay != null) {
                    Spacer(Modifier.height(KSpace.sm))
                    val (recirc, fresh) = SeatText.whatIf(threeWay)
                    Row(inset.fillMaxWidth()) {
                        Text(recirc, style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(KSpace.md))
                        Text(fresh, style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                card.infection?.let { inf ->
                    // 기침 자리(v0.8.0): 기침하지 않는 동승자 한 명의 추정 감염 위험. 내내 내기와 나란히 놓는다
                    val (now, ifRecirc) = SeatText.infectionLine(inf)
                    Spacer(Modifier.height(KSpace.sm))
                    Row(
                        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(KRadius.surface)).padding(horizontal = KSpace.lg, vertical = KSpace.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(now, style = K.type.body.bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(KSpace.md))
                        Text(ifRecirc, style = K.type.body, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                val lines = DemoText.arrivalLines(card, demoFast).take(arrivalLineCount(threeWay != null, offer != null))
                Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(KRadius.surface)).padding(KSpace.lg)) {
                    MetricRow(DemoText.arrivalMetrics(card, demoFast, freshUnits))
                    if (lines.isNotEmpty()) {
                        Spacer(Modifier.height(KSpace.md))
                        KHairline()
                        Spacer(Modifier.height(KSpace.md))
                        lines.forEach { Text(it, style = K.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }

            if (offer != null) {
                Spacer(Modifier.height(KSpace.sm))
                Row(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(KRadius.surface)).padding(horizontal = KSpace.lg, vertical = KSpace.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(keepWords(offer), style = K.type.body.bold, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (trustAnswered != null) Text(trustAnswerLine(trustAnswered), style = K.type.body, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (trustAnswered == null) {
                        Spacer(Modifier.width(KSpace.md))
                        KSecondaryButton("계속 물어봐 주세요", { onTrust(false) }, Modifier.width(248.dp).height(KSize.touchMin), style = K.type.body)
                        Spacer(Modifier.width(KSpace.sm))
                        KPrimaryButton("맡길게요", { onTrust(true) }, Modifier.width(200.dp).height(KSize.touchMin), style = K.type.body)
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).padding(start = KSpace.lg, end = KSpace.md)) {
                    // 주석 두 줄: 전부 추정이라는 것(자리별 값이 어떻게 나왔는지)과 자료 출처
                    if (rows.isNotEmpty()) Text(SeatText.footnote(card), style = K.type.note, color = c.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else if (threeWay != null) Text(BreathText.threeWayFootnote(threeWay), style = K.type.note, color = c.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (threeWay?.pmGrade != null) Text("${OpenMeteoAir.ATTRIBUTION}. ${OpenMeteoAir.GRID_NOTE}", style = K.type.note, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // 맡기기 질문에 아직 답하지 않았으면 주 버튼은 "맡길게요" 다. [확인] 은 회색으로 물러난다
                if (offer != null && trustAnswered == null) KSecondaryButton("확인", onDismiss, Modifier.width(KSize.wideButton).height(KSize.touchMin), onBg = true)
                else KPrimaryButton("확인", onDismiss, Modifier.width(KSize.wideButton).height(KSize.touchMin))
            }
        }
    }
}

/** 자리 칸과 숫자 칸의 너비 비. 머리 줄과 자리 줄이 같은 값을 써서 세로로 줄이 맞는다 */
private const val SEAT_NAME_WEIGHT = 1.7f
private const val SEAT_CELL_WEIGHT = 1f

/** 표 머리: 이름은 보조 색, 괄호 뜻("한도 위", "평균")은 주석 크기 흐림 색 */
@Composable
private fun SeatHeader() {
    val c = K.colors
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        SeatText.HEADERS.forEachIndexed { i, title ->
            Row(Modifier.weight(if (i == 0) SEAT_NAME_WEIGHT else SEAT_CELL_WEIGHT)) {
                Text(title, style = K.type.sub, color = c.sub, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                SeatText.HEADER_NOTES[i].takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = K.type.note, color = c.faint, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                }
            }
        }
    }
}

/** 자리 한 줄: 자리 이름(굵게)과 설정(보조), 그리고 숫자 네 칸. 단위는 숫자 기준선에 작고 흐리게 붙는다 */
@Composable
private fun SeatRow(row: SeatText.Row, modifier: Modifier = Modifier) {
    val c = K.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(SEAT_NAME_WEIGHT)) {
            Text(row.name, style = K.type.body.bold, color = c.ink, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
            if (row.tag.isNotEmpty()) {
                Spacer(Modifier.width(KSpace.xs))
                Text(row.tag, style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline())
            }
        }
        SeatCells(listOf(row.over), Modifier.weight(SEAT_CELL_WEIGHT))
        SeatCells(listOf(row.pm), Modifier.weight(SEAT_CELL_WEIGHT))
        SeatCells(row.sit, Modifier.weight(SEAT_CELL_WEIGHT))
        SeatCells(listOf(row.walk), Modifier.weight(SEAT_CELL_WEIGHT))
    }
}

/** 숫자와 단위 묶음("1" "시간" "12" "분"). 숫자가 아닌 말("없음", "값 없음")은 보조 색 보통 굵기로 적는다 */
@Composable
private fun SeatCells(cells: List<SeatText.Cell>, modifier: Modifier = Modifier) {
    val c = K.colors
    Row(modifier) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            val word = cell.unit.isEmpty()
            Text(cell.number, style = if (word) K.type.body else K.type.body.bold, color = if (word) c.faint else c.ink, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
            if (!word) {
                Spacer(Modifier.width(2.dp))
                Text(cell.unit, style = K.type.note, color = c.faint, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

/** 수치 줄. 칸마다 같은 너비라 이름과 값이 세로로 줄을 맞춘다 */
@Composable
private fun MetricRow(metrics: List<DemoText.Metric>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) { metrics.forEach { KMetric(it.label, it.value, Modifier.weight(1f)) } }
}

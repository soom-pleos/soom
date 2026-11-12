package ai.kkaeugi.app.ui.screens

import ai.kkaeugi.app.UiState
import ai.kkaeugi.app.ui.AirLevel
import ai.kkaeugi.app.ui.BreathText
import ai.kkaeugi.app.ui.FRESH_AIR_BUTTON
import ai.kkaeugi.app.ui.ScreenActions
import ai.kkaeugi.app.ui.SeatText
import ai.kkaeugi.app.ui.airLevel
import ai.kkaeugi.app.ui.airLineContext
import ai.kkaeugi.app.ui.airStatusLine
import ai.kkaeugi.app.ui.components.BreathCurve
import ai.kkaeugi.app.ui.components.KHairline
import ai.kkaeugi.app.ui.components.KIcon
import ai.kkaeugi.app.ui.components.KIconView
import ai.kkaeugi.app.ui.components.KNumberUnit
import ai.kkaeugi.app.ui.components.KPrimaryButton
import ai.kkaeugi.app.ui.components.KSecondaryButton
import ai.kkaeugi.app.ui.components.KStatusDot
import ai.kkaeugi.app.ui.components.KStatusWord
import ai.kkaeugi.app.ui.components.SeatMap
import ai.kkaeugi.app.ui.connectionWord
import ai.kkaeugi.app.ui.homeSecondLine
import ai.kkaeugi.app.ui.homeSentence
import ai.kkaeugi.app.ui.hvacNotice
import ai.kkaeugi.app.ui.idleTopLine
import ai.kkaeugi.app.ui.kmText
import ai.kkaeugi.app.ui.noRestStopText
import ai.kkaeugi.app.ui.numberAndUnit
import ai.kkaeugi.app.ui.ppmPerMinute
import ai.kkaeugi.app.ui.ppmText
import ai.kkaeugi.app.ui.rateText
import ai.kkaeugi.app.ui.runProgress
import ai.kkaeugi.app.ui.runningBarText
import ai.kkaeugi.app.ui.seatMapUi
import ai.kkaeugi.app.ui.showFreshAirButton
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KColors
import ai.kkaeugi.app.ui.theme.KMotion
import ai.kkaeugi.app.ui.theme.KRadius
import ai.kkaeugi.app.ui.theme.KSize
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.LocalMotion
import ai.kkaeugi.app.ui.theme.bold
import ai.kkaeugi.app.ui.topBarTripLine
import ai.kkaeugi.engine.TripState
import ai.kkaeugi.engine.restStopDisplayName
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 상태 한 단어 앞에 찍는 점의 색. 글자는 언제나 본문 색 굵은 글자다.
 * 쾌적과 보통은 파랑, 탁함은 진한 회색, 한도만 빨강
 */
internal fun levelDot(level: AirLevel, c: KColors): Color = when (level) {
    AirLevel.FRESH, AirLevel.NORMAL -> c.accent
    AirLevel.STALE -> c.sub
    AirLevel.LIMIT -> c.limit
}

/**
 * 주행 중 홈. 주인공은 숨 곡선 하나다.
 * 왼쪽(면 하나): 상태 한 단어, 추정 ppm 큰 숫자, 그 아래 넓은 곡선, 곡선 아래 두 줄.
 *   상태 점, 큰 숫자, 곡선, 아래 문장의 왼쪽 끝이 같은 세로선에 선다.
 * 오른쪽(면 없음, 가는 선으로 나눈 세 행): 좌석 지도, 바깥 공기, 다음 쉼터.
 * 글자 크기는 넷(표제 숫자, 제목, 본문, 보조), 색을 입힌 글자는 곡선의 "한도 2,000" 하나다.
 * 스크롤과 입력은 없다. 누를 수 있는 것은 조용 모드에서 한도를 넘었을 때의 "바깥 공기로" 하나뿐이다
 */
@Composable
internal fun DrivingHome(ui: UiState, actions: ScreenActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val railWidth = (maxWidth * 0.29f).coerceIn(280.dp, 400.dp)
        Column(Modifier.fillMaxSize().padding(start = KSpace.lg, end = KSpace.lg, top = KSpace.xs, bottom = KSpace.lg)) {
            TopBar(ui)
            RunningBand(ui, actions)
            Spacer(Modifier.height(KSpace.xs))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                BreathHero(ui, actions, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(KSpace.lg))
                InfoRail(ui, Modifier.width(railWidth).fillMaxHeight())
            }
        }
    }
}

// ---------------- 위 막대 ----------------

/**
 * 위 막대. 왼쪽: 앱 이름과 주행 한 줄("주행 13분", 꼬리표가 있으면 "· 야간 장거리"). 오른쪽: 작은 점 하나와 "연결".
 * 그 밖의 말은 싣지 않는다(마지막으로 한 말은 곡선 아래 둘째 줄에만 나온다).
 * 정차 화면에서는 상태("대기", "도착")와 기어를 적는다
 */
@Composable
internal fun TopBar(ui: UiState) {
    val c = K.colors
    val driving = ui.tripState == TripState.DRIVING
    Row(Modifier.fillMaxWidth().height(KSize.topBar), verticalAlignment = Alignment.CenterVertically) {
        Text("숨", style = K.type.body.bold, color = c.ink, maxLines = 1)
        Spacer(Modifier.width(KSpace.sm))
        Text(
            if (driving) topBarTripLine(ui) else idleTopLine(ui),
            style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(KSpace.sm))
        if (ui.permissionFailures.isNotEmpty()) {
            KStatusDot(c.limit, size = 8.dp)
            Spacer(Modifier.width(KSpace.xs))
            Text("권한 거부 ${ui.permissionFailures.size}", style = K.type.sub, color = c.sub, maxLines = 1)
            Spacer(Modifier.width(KSpace.md))
        }
        KStatusDot(if (ui.pleosAvailable) c.accent else c.outline, size = 8.dp)
        Spacer(Modifier.width(KSpace.xs))
        Text(connectionWord(ui), style = K.type.sub, color = c.sub, maxLines = 1)
    }
}

// ---------------- 실행 중 띠 ----------------

/**
 * 실행 중 띠. 위 막대 바로 아래 한 줄과 진행선 하나. 곡선과 나머지 화면은 그대로 둔다.
 * 예고로 시작했거나 직접 요청한 환기는 유지 시간 내내 [취소] 를 받는다(주행 중 터치 대상이라 76dp)
 */
@Composable
internal fun RunningBand(ui: UiState, actions: ScreenActions) {
    val run = ui.running ?: return
    val c = K.colors
    var seenMax by remember(run.kind, run.label) { mutableIntStateOf(run.remainingSec) }
    if (run.remainingSec > seenMax) seenMax = run.remainingSec
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(if (ui.runCancellable) KSize.touchMin + KSpace.sm else KSize.topBar), verticalAlignment = Alignment.CenterVertically) {
            KIconView(KIcon.FRESH_AIR, c.accent, size = 28.dp)
            Spacer(Modifier.width(KSpace.xs))
            Text(runningBarText(run.remainingSec, run.label), style = K.type.body.bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (ui.runCancellable) {
                Spacer(Modifier.width(KSpace.sm))
                KSecondaryButton("취소", actions.cancelRun, Modifier.width(220.dp).height(KSize.touchMin), onBg = true)
            }
        }
        if (run.remainingSec > 0) Box(Modifier.fillMaxWidth().height(4.dp).background(c.line, CircleShape)) {
            Box(Modifier.fillMaxWidth(runProgress(run.remainingSec, seenMax)).fillMaxHeight().background(c.accent, CircleShape))
        }
        Spacer(Modifier.height(KSpace.xs))
    }
}

// ---------------- 주인공: 숫자와 숨 곡선 ----------------

@Composable
private fun BreathHero(ui: UiState, actions: ScreenActions, modifier: Modifier) {
    val c = K.colors
    val air = ui.air
    val design = ui.breath
    val limit = ui.co2LimitPpm
    Column(modifier.background(c.surface, RoundedCornerShape(KRadius.surface)).padding(KSpace.lg)) {
        // 첫 줄: 상태 한 단어(점과 굵은 글자)와 분당 변화. 오른쪽 끝에는 지금 공조가 내기인지 외기인지
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (air != null) {
                val level = airLevel(air.ppm, limit)
                KStatusWord(level.label, levelDot(level, c))
                rateText(design?.let { ppmPerMinute(it.trace) }, ui.demoFast)?.let {
                    Spacer(Modifier.width(KSpace.sm))
                    Text(it, style = K.type.body, color = c.sub, maxLines = 1)
                }
            } else Text(" ", style = K.type.body, maxLines = 1)
            Spacer(Modifier.weight(1f))
            HvacMode(ui)
        }
        // 둘째 줄: 큰 숫자와 단위. 단위는 두 단계 작은 크기, 흐림 색으로 기준선에 붙인다
        Row {
            BigNumber(air?.let { ppmText(it.ppm) } ?: "--", Modifier.alignByBaseline())
            Spacer(Modifier.width(KSpace.xs))
            Text("ppm 추정", style = K.type.body, color = c.faint, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        }
        if (design == null) {
            // 추정 전에는 곡선 자리를 비워 둔다. 아래 문장이 "추정 준비 중" 이라고 말한다
            Spacer(Modifier.fillMaxWidth().weight(1f))
        } else {
            // 지나온 구간이 짧으면 "지금"을 왼쪽으로 당겨 예보 구간을 넓게 쓴다
            val chart = remember(design.trace, design.forecast, limit, ui.outdoorPpm) {
                BreathText.chart(design.trace, design.forecast.points, limit, ui.outdoorPpm, BreathText.pastWindowMin(design.trace))
            }
            val last = design.trace.lastOrNull()
            // 그림자가 실제와 눈에 띄게 벌어졌을 때만 이름과 끝점 값을 붙인다
            val shadowLabel = last?.takeIf { it.recircOnlyPpm - it.ppm >= limit / 20 }?.let { "내기만 ${ppmText(it.recircOnlyPpm)}" }
            BreathCurve(
                chart, limitLabel = "한도 ${ppmText(limit)}", shadowLabel = shadowLabel, axisUnit = if (ui.demoFast) "초" else "분",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        Spacer(Modifier.height(KSpace.sm))
        // 곡선 아래 두 줄: 다음에 무슨 일이 생기는지(굵게), 그리고 마지막으로 한 말이나 예보 한 줄
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    homeSentence(airStatusLine(air, ui.occupants, ui.demoFast, airLineContext(ui))),
                    style = K.type.body.bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                homeSecondLine(ui)?.let { Text(it, style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            // 묻지 않는 중에도 운전자가 직접 바꿀 수 있게 큰 버튼 하나(한 번 누르면 2분 환기 뒤 원복)
            if (showFreshAirButton(ui)) {
                Spacer(Modifier.width(KSpace.md))
                KPrimaryButton(FRESH_AIR_BUTTON, actions.requestFreshAir, Modifier.width(KSize.wideButton).height(KSize.touchMin))
            }
        }
    }
}

/**
 * 표제 숫자. 값이 바뀌면 짧게 흐려졌다 돌아온다(고정폭 숫자라 자리는 흔들리지 않는다).
 * Text 를 감싸지 않고 투명도만 바꾼다. 감싸면 옆 글자와의 기준선 정렬이 풀린다
 */
@Composable
private fun BigNumber(text: String, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(1f) }
    if (LocalMotion.current) LaunchedEffect(text) {
        alpha.snapTo(0.45f)
        alpha.animateTo(1f, tween(KMotion.NUMBER_MS))
    }
    // 큰 숫자는 글자 왼쪽에 빈 자리가 있다. 그만큼 왼쪽으로 당겨 위의 점, 아래 곡선과 같은 세로선에 세운다
    Text(text, style = K.type.display, color = K.colors.ink, maxLines = 1, softWrap = false, modifier = modifier.offset(x = (-4).dp).graphicsLayer { this.alpha = alpha.value })
}

/** 지금 공조 상태: 내기순환인지 바깥 공기인지. 엔진이 잠깐 바꿔 둔 중이면 바깥 공기다 */
@Composable
private fun HvacMode(ui: UiState, modifier: Modifier = Modifier) {
    val c = K.colors
    val notice = hvacNotice(ui)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (notice != null) {
            Text(notice, style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            KIconView(if (ui.recirculating) KIcon.RECIRC else KIcon.FRESH_AIR, c.sub, size = 22.dp)
            Spacer(Modifier.width(KSpace.xs))
            Text(if (ui.recirculating) "내기순환" else "바깥 공기", style = K.type.sub, color = c.sub, maxLines = 1)
        }
    }
}

// ---------------- 오른쪽 정보 레일 ----------------

/** 세로 정보 레일. 카드가 아니다. 바탕 위에 가는 선으로 나눈 세 행: 좌석 지도, 바깥 공기, 다음 쉼터 */
@Composable
private fun InfoRail(ui: UiState, modifier: Modifier) {
    Column(modifier) {
        SeatRow(ui, Modifier.fillMaxWidth().weight(1.3f))
        KHairline()
        OutdoorRow(ui, Modifier.fillMaxWidth().weight(1f))
        KHairline()
        RestStopRow(ui, Modifier.fillMaxWidth().weight(1f))
    }
}

/**
 * ① 좌석 지도와 "2명". 차가 자리별 값을 주면 그 값 그대로 그리고 "좌석 센서", 아니면 인원 수로 순서대로 채우고 "직접 입력".
 * 그 아래 두 줄(v0.7.3): 이번 주행에서 살피는 것("미세먼지 먼저 · 90분마다 쉬기")과 앉은 시간("앉은 지 1시간 12분")
 */
@Composable
private fun SeatRow(ui: UiState, modifier: Modifier) {
    val count = ui.air?.occupants ?: ui.occupants
    val seat = seatMapUi(ui)
    Row(modifier.padding(bottom = KSpace.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            RailLabel(KIcon.PERSON, seat.label)
            KNumberUnit(count.toString(), "명")
            Spacer(Modifier.height(KSpace.xs))
            // 이번 주행에서 살피는 것(탄 자리의 설정에서 나온다)과 가장 오래 앉은 자리의 앉은 시간
            Text(SeatText.careLine(ui.care, ui.demoFast), style = K.type.sub.bold, color = K.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(SeatText.sitLine(ui.care, ui.demoFast), style = K.type.sub, color = K.colors.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SeatMap(seat.seats, Modifier.fillMaxHeight().widthIn(max = 132.dp).aspectRatio(0.54f), marked = SeatText.marked(ui.seatProfiles))
    }
}

/** ② 바깥 공기: "미세먼지 나쁨 41" 과 그 등급으로 낸 환기 설계 한 줄. 값이 없으면 "정보 없음" */
@Composable
private fun OutdoorRow(ui: UiState, modifier: Modifier) {
    val c = K.colors
    val design = ui.breath
    val outdoor = design?.outdoor
    Column(modifier, verticalArrangement = Arrangement.Center) {
        RailLabel(KIcon.FRESH_AIR, "바깥 공기")
        // 등급은 글자로만 알린다. 색은 쓰지 않는다
        Text(
            if (outdoor != null) "미세먼지 ${outdoor.grade.label} ${Math.round(outdoor.pm25)}" else "정보 없음",
            style = K.type.body.bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (design != null) {
            // 엔진이 잠깐 바꿔 둔 중(실행 띠가 떠 있는 동안)은 내기순환으로 달리는 것으로 본다
            val recirc = ui.recirculating || ui.running != null
            val cadence = BreathText.cadence(design.plan, ui.demoFast)
            val hypothetical = !recirc && design.plan.intervalMin != null && !design.plan.keepFresh
            Text(if (hypothetical) "내기순환이면 $cadence" else cadence, style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** ③ 다음 쉼터: 이름과 남은 거리. 경유지로 넣은 쉼터면 그렇게 적는다 */
@Composable
private fun RestStopRow(ui: UiState, modifier: Modifier) {
    val c = K.colors
    val wp = ui.waypointStop
    val stop = wp ?: ui.nextRestStop
    Column(modifier.padding(top = KSpace.xs), verticalArrangement = Arrangement.Center) {
        RailLabel(KIcon.REST_STOP, when { wp != null -> "다음 쉼터 · 경유지"; ui.usingDemoLocation -> "다음 쉼터 · 모의 위치"; else -> "다음 쉼터" })
        if (stop == null) {
            Text(noRestStopText(ui), style = K.type.sub, color = c.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(restStopDisplayName(stop.name), style = K.type.body.bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stop.routeLabel, style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(KSpace.xs))
                val km = if (wp != null) ui.proximityKm ?: stop.distanceKm else stop.distanceKm
                val (number, unit) = numberAndUnit(kmText(km))
                KNumberUnit(number, unit)
            }
        }
    }
}

@Composable
private fun RailLabel(icon: KIcon, text: String) {
    val c = K.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        KIconView(icon, c.sub, size = 20.dp)
        Spacer(Modifier.width(KSpace.xs))
        Text(text, style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

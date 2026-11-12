package ai.kkaeugi.app.ui.screens

import ai.kkaeugi.app.UiState
import ai.kkaeugi.app.signals.OpenMeteoAir
import ai.kkaeugi.app.signals.PmChoice
import ai.kkaeugi.app.ui.ScreenActions
import ai.kkaeugi.app.ui.components.KChip
import ai.kkaeugi.app.ui.components.KHairline
import ai.kkaeugi.app.ui.components.KNumberUnit
import ai.kkaeugi.app.ui.components.KSecondaryButton
import ai.kkaeugi.app.ui.components.KSegmented
import ai.kkaeugi.app.ui.components.KSettingRow
import ai.kkaeugi.app.ui.components.KStatusDot
import ai.kkaeugi.app.ui.components.KTextButton
import ai.kkaeugi.app.ui.components.KToggleRow
import ai.kkaeugi.app.ui.components.SeatMap
import ai.kkaeugi.app.ui.developerSummary
import ai.kkaeugi.app.ui.gearLabel
import ai.kkaeugi.app.ui.hvacNotice
import ai.kkaeugi.app.ui.keepWords
import ai.kkaeugi.app.ui.pmSettingsLine
import ai.kkaeugi.app.ui.ppmText
import ai.kkaeugi.app.ui.RECENT_TRIP_ROWS
import ai.kkaeugi.app.ui.SEAT_LABEL_MANUAL
import ai.kkaeugi.app.ui.SeatText
import ai.kkaeugi.app.ui.seatMapUi
import ai.kkaeugi.app.ui.tripRow
import ai.kkaeugi.app.ui.splitLabel
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KRadius
import ai.kkaeugi.app.ui.theme.KSize
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.bold
import ai.kkaeugi.app.ui.theme.semi
import ai.kkaeugi.app.vehicle.OccupantInput
import ai.kkaeugi.engine.TripState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 정차 화면(대기, 도착). 왼쪽: 좌석 지도(자리를 누르면 그 자리 설정)와 고른 설정, 인원, 한 문장. 오른쪽: 설정 목록(면 하나, 행 높이 72dp 이상).
 * 글자 크기는 넷(제목, 본문, 보조, 주석), 색을 입힌 글자는 "펼치기" 하나다.
 * 로그, 자가 진단, 제어 검증, 시연 시나리오는 기본 화면에 없다. 설정 목록 맨 아래 "개발자" 행을 펼치면 나온다.
 * 정차 중이라 오른쪽 목록은 스크롤할 수 있다
 */
@Composable
internal fun IdleHome(ui: UiState, actions: ScreenActions) {
    Column(Modifier.fillMaxSize().padding(start = KSpace.lg, end = KSpace.lg, top = KSpace.xs, bottom = KSpace.lg)) {
        TopBar(ui)
        RunningBand(ui, actions)
        Spacer(Modifier.height(KSpace.xs))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            CabinColumn(ui, actions, Modifier.weight(0.38f).fillMaxHeight())
            Spacer(Modifier.width(KSpace.lg))
            SettingsList(ui, actions, Modifier.weight(0.62f).fillMaxHeight())
        }
    }
}

// ---------------- 왼쪽: 좌석 지도와 한 문장 ----------------

@Composable
private fun CabinColumn(ui: UiState, actions: ScreenActions, modifier: Modifier) {
    val c = K.colors
    Column(modifier) {
        // 좌석 지도(누르면 그 자리 설정)와 고른 설정 목록. 지도, 인원, 문장을 왼쪽 선 하나에 세운다
        Row(Modifier.fillMaxWidth().weight(1f)) {
            SeatMap(
                seatMapUi(ui.seats, ui.occupants, ui.frontPassenger, ui.seatSensorLive).seats, Modifier.fillMaxHeight().width(176.dp),
                marked = SeatText.marked(ui.seatProfiles), selected = ui.profileSheetSeat, onSeatTap = actions.openProfileSheet,
            )
            Spacer(Modifier.width(KSpace.md))
            SeatProfileList(ui, actions, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(KSpace.sm))
        OccupantControl(ui, actions, Modifier.fillMaxWidth())
        Spacer(Modifier.height(KSpace.sm))
        Text("출발하면 차 안 공기를 살펴요", style = K.type.title, color = c.ink, maxLines = 2)
        Spacer(Modifier.height(KSpace.xs))
        Text(
            keepWords("사람 수와 환기 상태로 계산한 추정값이에요(센서 값 아님). 한도 ${ppmText(ui.co2LimitPpm)}ppm"),
            style = K.type.sub, color = c.sub, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        hvacNotice(ui)?.let {
            Spacer(Modifier.height(KSpace.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KStatusDot(c.limit, size = 8.dp)
                Spacer(Modifier.width(KSpace.xs))
                Text(it, style = K.type.sub.bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(KSpace.sm))
        KHairline()
        Spacer(Modifier.height(KSpace.sm))
        // 자리 설정이 무엇인지 두 줄: 설정일 뿐이라는 것, 사람이 아니라 자리에 붙는다는 것
        // 두 문장을 한 줄씩 적는다(좁은 칸에서 문장 가운데가 끊기지 않게)
        Text(SeatText.PROFILE_NOTE.replace(". ", ".\n"), style = K.type.sub, color = c.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(KSpace.xs))
        Text(
            if (ui.seats == null) "${SeatText.STICKY_NOTE} ${SeatText.MANUAL_NOTE}" else SeatText.STICKY_NOTE,
            style = K.type.note, color = c.faint, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 좌석 지도 옆: 프로필을 고른 자리와 그 이름("조수석 임산부"). 고른 것이 없으면 누르는 방법 한 줄.
 * 고른 것이 있을 때만 [전부 지우기] 가 나온다. 지도에는 점만 찍고 이름은 여기에 적는다
 */
@Composable
private fun SeatProfileList(ui: UiState, actions: ScreenActions, modifier: Modifier) {
    val c = K.colors
    val chosen = SeatText.chosen(ui.seatProfiles)
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(SeatText.LIST_TITLE, style = K.type.sub, color = c.sub, maxLines = 1)
        Spacer(Modifier.height(KSpace.xs))
        if (chosen.isEmpty()) {
            Text(keepWords(SeatText.HINT), style = K.type.sub, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
            chosen.forEach { (seat, label) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(seat, style = K.type.sub, color = c.sub, maxLines = 1, modifier = Modifier.width(104.dp).alignByBaseline())
                    Text(label, style = K.type.sub.bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline())
                }
            }
            Spacer(Modifier.height(KSpace.sm))
            // 화면 바탕 위라 한 단계 진한 회색 면을 쓴다
            KSecondaryButton(SeatText.CLEAR_ALL, actions.clearSeatProfiles, Modifier.height(KSize.control), style = K.type.sub, onBg = true)
        }
    }
}

/**
 * 탑승 인원. 좌석 값(점유 또는 벨트)이 오고 있으면 "N명"과 "좌석 센서 기준"만 보여 주고, 안 오면 스테퍼(1~5)로 직접 넣는다.
 * 넣은 값은 저장한다. 다음에 정차했을 때는 묻지 않고 "N명 그대로"만 보여 준다
 */
@Composable
private fun OccupantControl(ui: UiState, actions: ScreenActions, modifier: Modifier) {
    val c = K.colors
    val manual = OccupantInput.mode(ui.seatSensorLive, ui.tripState == TripState.DRIVING) != OccupantInput.Mode.SENSOR
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        KNumberUnit((if (manual) ui.manualOccupants else ui.occupants).toString(), "명", Modifier.alignByBaseline())
        Spacer(Modifier.width(KSpace.xs))
        Text(
            when {
                // "좌석 센서 기준 1명" 에서 숫자는 앞에 적었으니 출처만 남긴다
                !manual -> OccupantInput.sensorLabel(ui.occupants).removeSuffix(" ${ui.occupants}명")
                // 숫자는 앞에 있으니 지난번 값을 그대로 쓴다는 것만 말한다
                ui.occupantsCarried -> "$SEAT_LABEL_MANUAL · 지난번 그대로"
                else -> SEAT_LABEL_MANUAL
            },
            style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline(),
        )
        if (manual) {
            Spacer(Modifier.width(KSpace.xs))
            KSecondaryButton("−", { actions.stepOccupants(-1) }, Modifier.size(width = 80.dp, height = KSize.control), enabled = ui.manualOccupants > OccupantInput.MIN, onBg = true)
            Spacer(Modifier.width(KSpace.xs))
            KSecondaryButton("+", { actions.stepOccupants(1) }, Modifier.size(width = 80.dp, height = KSize.control), enabled = ui.manualOccupants < OccupantInput.MAX, onBg = true)
        }
    }
}

// ---------------- 오른쪽: 설정 목록 ----------------

/** 사용자 설정은 셋이다: 숨 켜기, 2분 환기 맡기기, 바깥 미세먼지 참고(와 등급). 그 아래 최근 주행 기록 두 건, 맨 아래 "개발자" */
@Composable
private fun SettingsList(ui: UiState, actions: ScreenActions, modifier: Modifier) {
    val c = K.colors
    val inset = Modifier.padding(horizontal = KSpace.lg)
    Column(modifier.clip(RoundedCornerShape(KRadius.surface)).background(c.surface).verticalScroll(rememberScrollState()).padding(vertical = KSpace.xs)) {
        KToggleRow("숨 켜기", ui.autoIntervene, actions.setAutoIntervene)
        KHairline(inset)
        val (trustTitle, trustDetail) = splitLabel("2분 환기 맡기기(묻지 않고 말만 하고 바꿔요. 5초 안에 취소할 수 있어요)")
        KToggleRow(trustTitle, ui.trustDelegated, actions.setTrustDelegated, detail = trustDetail)
        KHairline(inset)
        val (pmTitle, pmDetail) = splitLabel("바깥 미세먼지 참고(나쁜 날에는 환기를 늦추고 짧게 해요)")
        KToggleRow(pmTitle, ui.pmEnabled, actions.setPmEnabled, detail = pmDetail)
        // 자동은 Open-Meteo 에서 받는다. 등급을 직접 고르는 것은 네트워크가 없는 에뮬레이터와 시연용이다
        Column(Modifier.fillMaxWidth().padding(start = KSpace.lg, end = KSpace.lg, bottom = KSpace.sm)) {
            KSegmented(PmChoice.entries.toList(), ui.pmChoice, { it.label }, actions.setPmChoice, Modifier.fillMaxWidth(), enabled = ui.pmEnabled)
            Spacer(Modifier.height(KSpace.xs))
            Text(pmSettingsLine(ui), style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 출처 주석은 여기 한 곳에만 적는다
            Text("${OpenMeteoAir.ATTRIBUTION}. ${OpenMeteoAir.GRID_NOTE}", style = K.type.note, color = c.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        KHairline(inset)
        RecentTrips(ui.recentTrips)
        KHairline(inset)
        KSettingRow("개발자", detail = developerSummary(ui), onClick = actions.toggleDeveloper) {
            if (ui.permissionFailures.isNotEmpty()) { KStatusDot(c.limit, size = 8.dp); Spacer(Modifier.width(KSpace.xs)) }
            Text(if (ui.showDeveloper) "접기" else "펼치기", style = K.type.sub, color = c.accent, maxLines = 1)
        }
        if (ui.showDeveloper) DeveloperSection(ui, actions)
    }
}

/** 최근 주행 기록. 한 건이 두 줄(날짜, 요약)인 목록 행이고 두 건까지만 보여 준다 */
@Composable
private fun RecentTrips(lines: List<String>) {
    val c = K.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = KSpace.lg, vertical = KSpace.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("최근 주행 기록", style = K.type.body.semi, color = c.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Text("기기 안에만 저장", style = K.type.sub, color = c.faint, maxLines = 1)
        }
        if (lines.isEmpty()) Text("아직 없음", style = K.type.sub, color = c.sub)
        lines.take(RECENT_TRIP_ROWS).forEach { line ->
            val row = tripRow(line)
            Spacer(Modifier.height(KSpace.xs))
            if (row.date.isNotEmpty()) Text(row.date, style = K.type.sub, color = c.faint, maxLines = 1)
            Text(row.summary, style = K.type.sub, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- 개발자(펼쳤을 때만) ----------------

/**
 * 펼친 "개발자" 영역: 자가 진단, 데모 콘솔(시연 시나리오), 연결 정보, 개발자 토글 여섯, 제어 검증, 마지막 안내와 신호·이벤트 로그.
 * 기본 화면에서는 보이지 않는다
 */
@Composable
private fun DeveloperSection(ui: UiState, actions: ScreenActions) {
    val c = K.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = KSpace.lg, vertical = KSpace.xs), horizontalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            // 한 번 눌러 읽기 전용 호출을 전부 돌리고 한 화면 보고서로 본다
            KSecondaryButton("자가 진단", actions.runDiagnosis, Modifier.weight(1f).height(KSize.control), style = K.type.sub)
            KSecondaryButton(if (ui.showDemoConsole) "데모 콘솔 닫기" else "데모 콘솔 열기", actions.toggleDemoConsole, Modifier.weight(1f).height(KSize.control), style = K.type.sub)
        }
        Text(
            buildString {
                append(ui.connectionDetail)
                if (ui.sources.isNotEmpty()) append("  ·  ").append(ui.sources.joinToString(", "))
                append("  ·  ").append(ui.speakerName).append(" / ").append(ui.listenerName)
                append("  ·  기어 ").append(gearLabel(ui.gear))
            },
            style = K.type.note, color = c.sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = KSpace.lg, vertical = KSpace.xs),
        )
        KToggleRow("데모 속도", ui.demoFast, actions.setDemoFast, detail = "분→초")
        KToggleRow("증거 모드", ui.evidenceMode, actions.setEvidenceMode, detail = "주행 중 SDK 제어 호출 한 줄, 데모 탭")
        KToggleRow("창문 방향 반전", ui.windowInverted, actions.setWindowInverted, detail = "닫힌 창문이 max 로 읽히는 차")
        KToggleRow("핸들 보조 신호(실험)", ui.steeringAssist, actions.setSteeringAssist)
        KToggleRow("졸음 개입에 창문 포함", ui.allowWindow, actions.setAllowWindow, detail = "창문 방향 실측 뒤")
        KToggleRow("화면 밖에서도 유지(실험)", ui.keepAlive, actions.setKeepAlive)
        ui.keepAliveError?.let {
            Text("화면 밖 유지를 시작하지 못해 껐어요: $it", style = K.type.note, color = c.limit, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = KSpace.lg))
        }
        KHairline(Modifier.padding(horizontal = KSpace.lg))
        if (ui.showVerify) VerifyPanel(ui, actions) else KSettingRow(
            "제어 검증",
            detail = if (ui.permissionFailures.isNotEmpty()) "등록 실패 ${ui.permissionFailures.size}건" else if (ui.pleosAvailable) "연결됨" else "모의 모드",
            onClick = actions.toggleVerify,
        ) { Text("펼치기", style = K.type.sub, color = c.accent) }
        KHairline(Modifier.padding(horizontal = KSpace.lg))
        Column(Modifier.fillMaxWidth().padding(horizontal = KSpace.lg, vertical = KSpace.sm)) {
            ui.lastSaid?.let { Text("마지막 안내: $it", style = K.type.note.bold, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            ui.deferred?.let { Text("발화 보류: $it", style = K.type.note, color = c.sub, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(KSpace.xs))
            LogPanel("신호·이벤트 로그", ui.log, 150.dp)
        }
    }
}

/** 제어 검증 패널(개발자 영역 안, 기본 접힘). 차 값을 실제로 바꾸는 버튼들이다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerifyPanel(ui: UiState, actions: ScreenActions) {
    val c = K.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = KSpace.lg, vertical = KSpace.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("제어 검증", style = K.type.body.semi, color = c.ink)
                Text("기어 ${gearLabel(ui.gear)} 상태에서 눌러 주행 중 제어 가능 여부 확인", style = K.type.note, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            KTextButton("로그 지우기", actions.clearVerifyLog)
            KTextButton("접기", actions.toggleVerify)
        }
        Text("연결: ${ui.connectionDetail}  ·  트립시간 단위: ${ui.tripUnit}", style = K.type.note, color = c.sub)
        if (ui.permissionFailures.isNotEmpty()) {
            Text("리스너 등록 실패(퍼미션 거부 가능성):", style = K.type.note, color = c.limit)
            ui.permissionFailures.forEach { Text("  · $it", style = K.type.mono, color = c.limit) }
            Text("telltales 가 실패하면 차의 졸음 경고(DRIVER_ATTENTION)를 받지 못해 졸음 제안이 나오지 않아요. 환기 제안은 그대로 동작해요", style = K.type.note, color = c.sub)
        }
        Spacer(Modifier.height(KSpace.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            val v = actions.verify
            KChip("내기순환 읽기", v.readRecirculation)
            KChip("외기로 전환", v.switchToFreshAir)
            KChip("풍량 +1", v.fanPlusOne)
            KChip("온도 -2", v.tempMinusTwo)
            KChip("창문 15%", v.window15)
            KChip("경유지 추가", v.addTestWaypoint)
            KChip("경유지 삭제", v.removeTestWaypoint)
            KChip("트립시간 raw 읽기", v.readTripTime)
            KChip("단위: 초로 확정", { v.confirmTripUnit(true) })
            KChip("단위: 분으로 확정", { v.confirmTripUnit(false) })
            KChip("탑승 인원 읽기", v.readOccupants)
            KChip("핸들 각도 읽기", v.readSteeringAngle)
            KChip("핸들 각도 범위 읽기", v.readSteeringRange)
            KChip("창문 위치 읽기(raw/min/max)", v.readWindowPositions)
            KChip("검증 원복", v.revertAll)
        }
        Spacer(Modifier.height(KSpace.xs))
        LogPanel(
            null, ui.verifyLog, 170.dp, mono = true,
            emptyText = if (ui.pleosAvailable) "버튼을 누르면 결과가 여기와 Logcat(Kkaeugi.Verify)에 남아요" else "이 기기에는 Pleos Vehicle 서비스가 없어요. Pleos 에뮬레이터에서 확인하세요.",
        )
    }
}

/** 로그 상자. 새 줄이 오면 맨 아래로 따라간다 */
@Composable
internal fun LogPanel(title: String?, lines: List<String>, height: Dp, mono: Boolean = false, emptyText: String = "아직 없음") {
    val c = K.colors
    Column(Modifier.fillMaxWidth().background(c.fill, RoundedCornerShape(KRadius.control)).padding(KSpace.sm)) {
        title?.let { Text(it, style = K.type.note, color = c.sub) }
        val state = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1) }
        if (lines.isEmpty()) Text(emptyText, style = K.type.note, color = c.sub)
        LazyColumn(Modifier.height(height), state = state) {
            items(lines) { Text(it, style = if (mono) K.type.mono else K.type.note, color = c.ink) }
        }
    }
}

/**
 * 증거 모드: 마지막 SDK 제어 호출(set*, addWaypoint, removeWaypoint) 결과 한 줄. 화면 맨 아래, 스크롤 없음.
 * "setHvacRecirculation(false) → ok 0.3s" / "… → 실패 DRIVING_RESTRICTED". 모의 모드에서는 앞에 "모의"가 붙는다
 */
@Composable
internal fun EvidenceStrip(line: String, proximityKm: Double?) {
    val c = K.colors
    Column(Modifier.fillMaxWidth()) {
        KHairline()
        Text(
            "SDK  $line" + (proximityKm?.let { "   |   경유지까지 ${"%.1f".format(it)}km" } ?: ""),
            style = K.type.mono, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = KSpace.lg, vertical = KSpace.xs),
        )
    }
}

/** 주행 중 오른쪽 가장자리의 좁은 탭. 누르면 데모 드로어가 열린다(모의 모드, 증거 모드에서만 보인다) */
@Composable
internal fun DrawerTab(onClick: () -> Unit) {
    val c = K.colors
    Row(Modifier.width(40.dp).fillMaxHeight().clickable(onClick = onClick)) {
        Box(Modifier.width(KSize.hairline).fillMaxHeight().background(c.line))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            "데모".forEach { Text(it.toString(), style = K.type.sub, color = c.sub) }
        }
    }
}

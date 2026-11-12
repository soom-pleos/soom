package ai.kkaeugi.app.ui

import ai.kkaeugi.app.UiState
import ai.kkaeugi.app.WakeViewModel
import ai.kkaeugi.app.signals.PmChoice
import ai.kkaeugi.app.ui.components.KNumberUnit
import ai.kkaeugi.app.ui.components.KStatusWord
import ai.kkaeugi.app.ui.components.SeatMap
import ai.kkaeugi.app.ui.screens.AnnounceSheet
import ai.kkaeugi.app.ui.screens.ArrivalScreen
import ai.kkaeugi.app.ui.screens.DemoConsole
import ai.kkaeugi.app.ui.screens.DiagScreen
import ai.kkaeugi.app.ui.screens.DrawerTab
import ai.kkaeugi.app.ui.screens.DrivingHome
import ai.kkaeugi.app.ui.screens.EvidenceStrip
import ai.kkaeugi.app.ui.screens.IdleHome
import ai.kkaeugi.app.ui.screens.ProfileSheet
import ai.kkaeugi.app.ui.screens.ProposalSheet
import ai.kkaeugi.engine.SeatProfile
import ai.kkaeugi.app.ui.screens.RestEndedToast
import ai.kkaeugi.app.ui.screens.RestScreen
import ai.kkaeugi.app.ui.screens.levelDot
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KRadius
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.KWindowScaled
import ai.kkaeugi.app.ui.theme.bold
import ai.kkaeugi.app.ui.theme.KkaeugiTheme
import ai.kkaeugi.engine.TripState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * 화면의 들머리. 실제로 그리는 코드는 나눠 두었다:
 *  ui/theme/Tokens.kt        색, 글자 크기, 간격, 모서리(낮과 밤 두 테마)
 *  ui/components/            숨 곡선, 좌석 지도, 아이콘, 버튼과 스위치
 *  ui/screens/               주행 홈, 제안과 예고 시트, 쉼, 도착, 정차(설정), 자가 진단, 데모 콘솔
 *  ui/HomeText.kt            화면 글을 고르는 순수 함수(JVM 테스트 대상)
 */

/** SDK 검증 패널 버튼 콜백. 프리뷰/스크린샷에서는 기본값(아무것도 안 함) */
class VerifyActions(
    val readRecirculation: () -> Unit = {},
    val switchToFreshAir: () -> Unit = {},
    val fanPlusOne: () -> Unit = {},
    val tempMinusTwo: () -> Unit = {},
    val window15: () -> Unit = {},
    val addTestWaypoint: () -> Unit = {},
    val removeTestWaypoint: () -> Unit = {},
    val readTripTime: () -> Unit = {},
    val confirmTripUnit: (seconds: Boolean) -> Unit = {},
    val revertAll: () -> Unit = {},
    val readOccupants: () -> Unit = {},
    val readSteeringAngle: () -> Unit = {},
    val readWindowPositions: () -> Unit = {},
    val readSteeringRange: () -> Unit = {},
)

/** 화면이 ViewModel 에 돌려주는 콜백 묶음. 화면 자체는 UiState 만 보고 그린다 */
class ScreenActions(
    val answer: (yes: Boolean) -> Unit = {},
    /** 실행 중 띠의 [취소](예고로 시작한 환기, 직접 요청한 환기) */
    val cancelRun: () -> Unit = {},
    /** 주행 홈의 "바깥 공기로"(조용 모드에서 한도를 넘었을 때) */
    val requestFreshAir: () -> Unit = {},
    val dismissArrival: () -> Unit = {},
    val setAutoIntervene: (Boolean) -> Unit = {},
    val setDemoFast: (Boolean) -> Unit = {},
    val setEvidenceMode: (Boolean) -> Unit = {},
    val setWindowInverted: (Boolean) -> Unit = {},
    val answerTrust: (accept: Boolean) -> Unit = {},
    val setTrustDelegated: (Boolean) -> Unit = {},
    val setSteeringAssist: (Boolean) -> Unit = {},
    val setAllowWindow: (Boolean) -> Unit = {},
    val toggleDeveloper: () -> Unit = {},
    val stepOccupants: (delta: Int) -> Unit = {},
    val runDiagnosis: () -> Unit = {},
    val closeDiagnosis: () -> Unit = {},
    val setKeepAlive: (Boolean) -> Unit = {},
    /** 설정 "바깥 미세먼지 참고"와 수동 선택(자동/좋음/보통/나쁨/매우 나쁨) */
    val setPmEnabled: (Boolean) -> Unit = {},
    val setPmChoice: (PmChoice) -> Unit = {},
    /** 자리별 프로필(v0.7.3): 좌석 지도의 자리를 누르면 시트를 열고, 시트에서 고르거나 닫는다. [전부 지우기] */
    val openProfileSheet: (seat: Int) -> Unit = {},
    val closeProfileSheet: () -> Unit = {},
    val setSeatProfile: (seat: Int, profile: SeatProfile) -> Unit = { _, _ -> },
    val clearSeatProfiles: () -> Unit = {},
    val toggleVerify: () -> Unit = {},
    val toggleDemoConsole: () -> Unit = {},
    val clearVerifyLog: () -> Unit = {},
    val verify: VerifyActions = VerifyActions(),
)

@Composable
fun KkaeugiApp(vm: WakeViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        ScreenActions(
            answer = { vm.answer(it) },
            cancelRun = vm::cancelRun,
            requestFreshAir = vm::requestFreshAir,
            dismissArrival = vm::dismissArrival,
            setAutoIntervene = vm::setAutoIntervene,
            setDemoFast = vm::setDemoFast,
            setEvidenceMode = vm::setEvidenceMode,
            setWindowInverted = vm::setWindowInverted,
            answerTrust = vm::answerTrust,
            setTrustDelegated = vm::setTrustDelegated,
            setSteeringAssist = vm::setSteeringAssist,
            setAllowWindow = vm::setAllowWindow,
            toggleDeveloper = vm::toggleDeveloper,
            stepOccupants = vm::stepManualOccupants,
            runDiagnosis = { vm.runDiagnosis() },
            closeDiagnosis = vm::closeDiagnosis,
            setKeepAlive = vm::setKeepAlive,
            setPmEnabled = vm::setPmEnabled,
            setPmChoice = vm::setPmChoice,
            openProfileSheet = vm::openProfileSheet,
            closeProfileSheet = vm::closeProfileSheet,
            setSeatProfile = vm::setSeatProfile,
            clearSeatProfiles = vm::clearSeatProfiles,
            toggleVerify = vm::toggleVerify,
            toggleDemoConsole = vm::toggleDemoConsole,
            clearVerifyLog = vm::clearVerifyLog,
            verify = VerifyActions(
                readRecirculation = { vm.verifier.readRecirculation() },
                switchToFreshAir = { vm.verifier.switchToFreshAir() },
                fanPlusOne = { vm.verifier.fanPlusOne() },
                tempMinusTwo = { vm.verifier.tempMinusTwo() },
                window15 = { vm.verifier.window15() },
                addTestWaypoint = { vm.verifier.addTestWaypoint() },
                removeTestWaypoint = { vm.verifier.removeTestWaypoint() },
                readTripTime = { vm.verifier.readTripTime() },
                confirmTripUnit = { vm.verifier.confirmTripUnit(it) },
                revertAll = { vm.verifier.revertAll() },
                readOccupants = { vm.verifier.readOccupants() },
                readSteeringAngle = { vm.verifier.readSteeringAngle() },
                readWindowPositions = { vm.verifier.readWindowPositions() },
                readSteeringRange = { vm.verifier.readSteeringRange() },
            ),
        )
    }
    KkaeugiScreen(ui, actions, demoConsole = { modifier, onClose -> DemoConsole(vm, ui, modifier, onClose) })
}

/**
 * 전체 화면. UiState 와 콜백만 받는다(ViewModel 없음). 프리뷰와 JVM 스크린샷 테스트가 이걸 그린다.
 * demoConsole 이 null 이면 데모 콘솔/드로어를 그리지 않는다.
 * dark: 밤 테마. 주지 않으면 시스템 야간 모드를 따른다. motion: 움직임. 스크린샷과 프리뷰에서는 꺼진다.
 *
 * 주행 중 글자 크기 규칙: 보조 크기보다 작은 주석 크기는 출처와 "추정" 설명에만 쓴다. 이모지는 쓰지 않는다. 터치 대상은 76dp 이상
 */
@Composable
fun KkaeugiScreen(
    ui: UiState,
    actions: ScreenActions = ScreenActions(),
    demoConsole: (@Composable (modifier: Modifier, onClose: (() -> Unit)?) -> Unit)? = null,
    dark: Boolean = isSystemInDarkTheme(),
    motion: Boolean = !LocalInspectionMode.current,
) {
    val driving = ui.tripState == TripState.DRIVING
    // 주행 중 데모 콘솔: 오른쪽 좁은 드로어. 언제 보이는지는 showDrivingDrawer
    var drawerOpen by remember { mutableStateOf(false) }
    // 쉼 화면의 "화면 닫기": 큰 타이머 화면만 접는다. 쉼이 끝나거나 다른 쉼터에서 다시 쉬면 원래대로
    var restFolded by remember(ui.rest?.stopName) { mutableStateOf(false) }
    KkaeugiTheme(dark = dark, motion = motion) {
        Surface(Modifier.fillMaxSize(), color = K.colors.bg) {
            // 창이 1280x720 보다 크면 글자와 여백을 같은 비율로 키운다(상한 1.3 배)
            KWindowScaled {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    // 증거 띠는 홈 아래에 자리를 차지한다(홈을 가리지 않는다). 실행 중 띠는 홈의 위 막대 아래에 있다
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (driving) DrivingHome(ui, actions) else IdleHome(ui, actions)
                        }
                        if (ui.evidenceMode) ui.evidenceLine?.let { EvidenceStrip(it, ui.proximityKm) }
                    }
                    // 쉼 모드의 바깥 공기 질문은 시트로 띄우지 않고 쉼 화면 안 아래쪽에 그린다
                    ui.proposal?.takeIf { !it.restQuestion }?.let { p -> ProposalSheet(p, ui.nowMs, ui.voiceAnswer, onYes = { actions.answer(true) }, onNo = { actions.answer(false) }) }
                    ui.announce?.let { a -> AnnounceSheet(a.text, a.cancelWindowSec, a.countdownFrom, ui.nowMs, ui.voiceAnswer, onCancel = { actions.answer(false) }) }
                    val restQuestion = ui.proposal?.takeIf { it.restQuestion }
                    ui.rest?.takeIf { !restFolded || restQuestion != null }?.let {
                        RestScreen(it, restQuestion, ui.nowMs, freshAir = !ui.recirculating, napTip = ui.napTip, onYes = { actions.answer(true) }, onNo = { actions.answer(false) }, onLeave = { restFolded = true })
                    }
                    ui.restEndedToast?.let { RestEndedToast(it) }
                    // 자리 설정 시트는 정차 중에만 뜬다
                    if (!driving) ui.profileSheetSeat?.let { seat ->
                        ProfileSheet(seat, ui.seatProfiles.getOrNull(seat) ?: SeatProfile.DEFAULT, manual = ui.seats == null, onPick = { actions.setSeatProfile(seat, it) }, onClose = actions.closeProfileSheet)
                    }
                    if (!driving) ui.diag?.let { DiagScreen(it, onClose = actions.closeDiagnosis) }
                    ui.arrival?.let { ArrivalScreen(it, ui.todayMaxOccupants, ui.demoFast, ui.todayFreshAirUnits, ui.trustAnswered, onTrust = actions.answerTrust, onDismiss = actions.dismissArrival) }
                }
                if (demoConsole != null) when {
                    !driving && ui.showDemoConsole -> demoConsole(Modifier.width(330.dp).fillMaxHeight(), null)
                    showDrivingDrawer(ui) -> {
                        if (drawerOpen) demoConsole(Modifier.width(300.dp).fillMaxHeight()) { drawerOpen = false }
                        else DrawerTab(onClick = { drawerOpen = true })
                    }
                }
            }
            }
        }
    }
}

/**
 * 인원 비교(스크린샷·프리뷰용): 같은 차, 같은 내기순환 시간에 혼자일 때와 4명일 때를 나란히 놓는다.
 * 숨의 핵심 주장("몇 명이 숨 쉬는지에 따라 환기 시점이 달라진다")을 한 장으로 보여 준다.
 */
@Composable
fun OccupantCompare(alone: UiState, four: UiState, dark: Boolean = isSystemInDarkTheme()) {
    KkaeugiTheme(dark = dark, motion = false) {
        val c = K.colors
        Surface(Modifier.fillMaxSize(), color = c.bg) {
            KWindowScaled {
            Column(Modifier.fillMaxSize().padding(KSpace.lg)) {
                Column(Modifier.padding(horizontal = KSpace.lg)) {
                    Text("같은 차, 내기순환 3분쯤. 탄 사람 수만 달라요", style = K.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("숨 모델 추정값(센서 값 아님). 한도 ${ppmText(four.co2LimitPpm)}ppm 에 닿으면 환기를 물어요", style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(KSpace.sm))
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(KSpace.sm)) {
                    ComparePanel(alone, Modifier.weight(1f).fillMaxHeight())
                    ComparePanel(four, Modifier.weight(1f).fillMaxHeight())
                }
            }
            }
        }
    }
}

/** 인원 비교의 한 칸: 인원과 좌석 지도, 상태 한 단어, 추정 ppm, 한도까지의 가로 눈금, 한 문장 */
@Composable
private fun ComparePanel(ui: UiState, modifier: Modifier) {
    val c = K.colors
    val air = ui.air
    val limit = ui.co2LimitPpm
    val count = air?.occupants ?: ui.occupants
    Column(modifier.background(c.surface, RoundedCornerShape(KRadius.surface)).padding(KSpace.lg)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            KNumberUnit(count.toString(), "명")
            Spacer(Modifier.weight(1f))
            SeatMap(seatMapUi(ui).seats, Modifier.fillMaxHeight().width(132.dp))
        }
        if (air != null) {
            val level = airLevel(air.ppm, limit)
            KStatusWord(level.label, levelDot(level, c))
        }
        KNumberUnit(air?.let { ppmText(it.ppm) } ?: "--", "ppm 추정", numberStyle = K.type.display, unitStyle = K.type.body)
        Spacer(Modifier.height(KSpace.xs))
        // 가로 눈금: 바깥 공기에서 3,000ppm 까지. 한도 자리에 빨강 금 하나
        Canvas(Modifier.fillMaxWidth().height(KSpace.md)) {
            val y = size.height / 2
            drawLine(c.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            if (air != null) drawLine(c.accent, Offset(0f, y), Offset(size.width * gaugeFraction(air.ppm, ui.outdoorPpm), y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            val lx = size.width * gaugeFraction(limit, ui.outdoorPpm)
            drawLine(c.limit, Offset(lx, 0f), Offset(lx, size.height), strokeWidth = 2.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            Text(ppmText(ui.outdoorPpm), style = K.type.sub, color = c.faint, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("한도 ${ppmText(limit)}", style = K.type.sub, color = c.limit, maxLines = 1)
            Spacer(Modifier.weight(0.5f))
            Text(ppmText(GAUGE_MAX), style = K.type.sub, color = c.faint, maxLines = 1)
        }
        Spacer(Modifier.height(KSpace.sm))
        Text(
            homeSentence(airStatusLine(air, ui.occupants, ui.demoFast, airLineContext(ui))),
            style = K.type.body.bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

package ai.kkaeugi.app

import ai.kkaeugi.app.gleo.Gleo
import ai.kkaeugi.app.gleo.AdbCommand
import ai.kkaeugi.app.gleo.AdbReply
import ai.kkaeugi.app.gleo.AppScope
import ai.kkaeugi.app.gleo.GleoBridge
import ai.kkaeugi.app.gleo.ReportFiles
import ai.kkaeugi.app.gleo.StateReport
import ai.kkaeugi.app.gleo.VerifyHoldService
import ai.kkaeugi.app.gleo.VerifyReceiver
import ai.kkaeugi.app.gleo.GleoCommand
import ai.kkaeugi.app.gleo.GleoLogic
import ai.kkaeugi.app.gleo.GleoResponse
import ai.kkaeugi.app.gleo.GleoSchema
import ai.kkaeugi.app.gleo.GleoState
import ai.kkaeugi.app.vehicle.ControlVerifier
import ai.kkaeugi.app.vehicle.VehicleControls
import ai.kkaeugi.app.signals.MockSignalSource
import ai.kkaeugi.app.signals.MonoClock
import ai.kkaeugi.app.signals.OutdoorAirSource
import ai.kkaeugi.app.signals.PmChoice
import ai.kkaeugi.app.signals.PleosNavSource
import ai.kkaeugi.app.signals.PleosVehicleSource
import ai.kkaeugi.app.signals.SignalBus
import ai.kkaeugi.app.speech.AndroidSpeaker
import ai.kkaeugi.app.speech.AnswerListener
import ai.kkaeugi.app.speech.NoAnswerListener
import ai.kkaeugi.app.speech.PleosAnswerListener
import ai.kkaeugi.app.speech.PleosSpeaker
import ai.kkaeugi.app.speech.Speaker
import ai.kkaeugi.app.speech.announceText
import ai.kkaeugi.app.speech.speechText
import ai.kkaeugi.app.signals.DemoScenario
import ai.kkaeugi.app.vehicle.DemoLocation
import ai.kkaeugi.app.vehicle.EtaJudge
import ai.kkaeugi.app.vehicle.Outcome
import ai.kkaeugi.app.vehicle.DiagLine
import ai.kkaeugi.app.vehicle.DiagReport
import ai.kkaeugi.app.vehicle.SelfDiagnosis
import ai.kkaeugi.app.vehicle.EvidenceLine
import ai.kkaeugi.app.vehicle.FusedLocationCalls
import ai.kkaeugi.app.vehicle.GeoPoint
import ai.kkaeugi.app.vehicle.HeadingFallback
import ai.kkaeugi.app.vehicle.HeadingHold
import ai.kkaeugi.app.vehicle.NavCalls
import ai.kkaeugi.app.vehicle.OccupantInput
import ai.kkaeugi.app.vehicle.PleosCalls
import ai.kkaeugi.app.vehicle.RestStopTracker
import ai.kkaeugi.app.vehicle.RestStops
import ai.kkaeugi.app.vehicle.SdkVerifier
import ai.kkaeugi.app.vehicle.SpeedEstimator
import ai.kkaeugi.app.vehicle.VehicleActuator
import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.DestinationEta
import ai.kkaeugi.engine.DoorChanged
import ai.kkaeugi.engine.SeatCare
import ai.kkaeugi.engine.SeatOccupancyChanged
import ai.kkaeugi.engine.SeatProfile
import ai.kkaeugi.engine.SeatProfilesChanged
import ai.kkaeugi.engine.Seats
import ai.kkaeugi.engine.Telltale
import ai.kkaeugi.engine.TelltaleChanged
import ai.kkaeugi.engine.ActionResult
import ai.kkaeugi.engine.AppVisibility
import ai.kkaeugi.engine.ArrivalSummary
import ai.kkaeugi.engine.AutoClimateChanged
import ai.kkaeugi.engine.BreathDesign
import ai.kkaeugi.engine.LocalClock
import ai.kkaeugi.engine.OutdoorAir
import ai.kkaeugi.engine.FrontPassengerChanged
import ai.kkaeugi.engine.FanSpeedChanged
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.GearChanged
import ai.kkaeugi.engine.LocationUpdate
import ai.kkaeugi.engine.LowBeamChanged
import ai.kkaeugi.engine.OccupantsChanged
import ai.kkaeugi.engine.SteeringAngle
import ai.kkaeugi.engine.RecirculationChanged
import ai.kkaeugi.engine.RestStopCandidate
import ai.kkaeugi.engine.RestStopInfo
import ai.kkaeugi.engine.Signal
import ai.kkaeugi.engine.SpeechFinished
import ai.kkaeugi.engine.TriggerKind
import ai.kkaeugi.engine.TripState
import ai.kkaeugi.engine.TripTimeUpdate
import ai.kkaeugi.engine.TrustAnswer
import ai.kkaeugi.engine.TrustState
import ai.kkaeugi.engine.UserAnswer
import ai.kkaeugi.engine.WakeConfig
import ai.kkaeugi.engine.WakeEngine
import ai.kkaeugi.engine.WakeEvent
import ai.kkaeugi.engine.WindowOpenChanged
import ai.kkaeugi.engine.WiperChanged
import ai.kkaeugi.engine.restStopDisplayName
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 제안 오버레이 표시용. countdownFrom 은 TTS 가 끝난 시각(단조). 끝나기 전엔 null */
data class Proposal(
    val kind: TriggerKind, val text: String, val timeoutSec: Int, val countdownFrom: Long? = null,
    /** 조수석에 사람이 있다. 답 버튼을 조수석 쪽(오른쪽)에 크게 붙인다 */
    val passengerMayAnswer: Boolean = false,
    /** 쉼 모드의 "쉬는 동안 바깥 공기로 둘까요?". 전체 화면 오버레이가 아니라 쉼 카드 안 아래쪽에 그린다 */
    val restQuestion: Boolean = false,
)
/** 자가 진단 보고서 화면. running 이면 아직 도는 중(지금까지의 줄만 있다). saved: diag_latest.txt 저장 여부(끝나기 전엔 null) */
data class DiagUi(val header: String, val lines: List<DiagLine> = emptyList(), val running: Boolean = false, val saved: Boolean? = null) {
    val summary: String get() = DiagReport.summary(lines)
    /** 복사·저장용 전체 글 */
    val report: String get() = DiagReport.format(header, lines)
}
/** 맡겨 둔 환기의 예고 카드. countdownFrom 은 TTS 가 끝난 시각(단조). 끝나기 전엔 null */
data class AnnounceUi(val text: String, val cancelWindowSec: Int, val countdownFrom: Long? = null)
/** 실행 중 바 표시용 */
data class RunningBar(val kind: TriggerKind, val remainingSec: Int, val label: String)
/** 쉼 모드 카드 */
data class RestUi(
    val stopName: String, val targetMinutes: Int, val remainingSec: Int, /** 데모 속도: 목표가 초 단위 */ val demo: Boolean = false,
    /** 걷기 쉼으로 넣은 쉼터다(엔진 RestStarted.walk). "쉬고 가세요" 대신 "내려서 걸어요"로 안내한다 */
    val walk: Boolean = false,
)
/** 숨 모델 추정치(엔진 AirEstimate). 전부 추정값 */
data class AirUi(val ppm: Int, val occupants: Int, val minutesToLimit: Int?, val ventilating: Boolean, /** 직전 추정치보다 내려가는 중 */ val falling: Boolean = false)

data class UiState(
    /** 단조 시계(ms). 카운트다운 계산용 */
    val nowMs: Long = 0L,
    val tripState: TripState = TripState.IDLE,
    val gear: Gear = Gear.UNKNOWN,
    val recirculating: Boolean = false,
    /** 내기순환 게이지 기준 시각(엔진과 같은 규칙: D 진입 시 켜져 있으면 그때부터). 꺼져 있으면 null */
    val recircSinceMs: Long? = null,
    /** 트립 시간(분) = max(앱 타이머, Odometer) */
    val tripMinutes: Int = 0,
    /** 벽시계. 화면 표시(HH:mm)용 */
    val lastVentilationWallMs: Long? = null,
    val raining: Boolean = false,
    val autoIntervene: Boolean = true,
    val demoFast: Boolean = false,
    val evidenceMode: Boolean = false,
    val proposal: Proposal? = null,
    /** 맡겨 둔 환기의 예고(문장, 취소 버튼 하나, 5초 카운트다운) */
    val announce: AnnounceUi? = null,
    val running: RunningBar? = null,
    val rest: RestUi? = null,
    /** RestEnded 직후 2초간 보여 주는 토스트성 문구 */
    val restEndedToast: String? = null,
    val arrival: ArrivalSummary? = null,
    val lastSaid: String? = null,
    val deferred: String? = null,
    /** 전방 같은 방향 최근접 쉼터(30초마다 갱신) */
    val nextRestStop: RestStopInfo? = null,
    /** 경유지로 넣은 쉼터 */
    val waypointStop: RestStopInfo? = null,
    val usingDemoLocation: Boolean = false,
    /** 실제 위치는 있는데 주행 방향(heading)을 아직 모른다. 쉼터 후보를 내지 않고 "주행 방향을 알면 쉼터를 안내해요"를 보여 준다 */
    val headingUnknown: Boolean = false,
    /** 이 차에는 내기순환 영역이 없다(영역 탐색 결과). 홈에 "이 차에서는 내외기 상태를 읽을 수 없어요" */
    val recircUnsupported: Boolean = false,
    val log: List<String> = emptyList(),
    val verifyLog: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    /** 연결 판정 통과 여부. 실패/판정 전이면 false(모의 모드) */
    val pleosAvailable: Boolean = false,
    val probing: Boolean = false,
    /** 알약 옆 작은 글씨: "연결 gear=GEAR_PARK" / "모의: 응답 없음(4초)" / "퍼미션 거부 1" */
    val connectionDetail: String = "",
    val permissionFailures: List<String> = emptyList(),
    val tripUnit: String = "판정 전",
    val speakerName: String = "",
    val listenerName: String = "",
    val showVerify: Boolean = false,
    val showDemoConsole: Boolean = false,
    val demoStep: String? = null,
    /** 증거 모드 한 줄: 마지막 SDK 호출 결과 */
    val evidenceLine: String? = null,
    val recentTrips: List<String> = emptyList(),
    /** 현재 노선(최근접 쉼터의 노선). "다음 쉼터" 카드 라벨 */
    val currentRoute: String? = null,
    /** 추적 중인 경유지 쉼터까지 거리(km) */
    val proximityKm: Double? = null,
    /** 마이크 권한. 없으면 음성 답 없이 버튼만 */
    val micGranted: Boolean = false,
    /** 음성 인식기(Pleos STT)가 준비됐는가. 모의 모드에는 없다 */
    val sttAvailable: Boolean = false,
    /** 숨 모델 추정치. 엔진이 아직 안 보냈으면 null */
    val air: AirUi? = null,
    /** 탑승 인원(마지막 OccupantsChanged). 모르면 1 */
    val occupants: Int = 1,
    /** 야간(하향등 ON) */
    val night: Boolean = false,
    /** 야간 장거리 배지 기준(분) */
    val longTripMinutes: Int = 120,
    /** 환기 제안 한도(ppm). 엔진의 effectiveLimitPpm: 야간 장거리면 기본 한도보다 낮다 */
    val co2LimitPpm: Int = 2000,
    /** 바깥 공기 ppm(게이지 시작 눈금) */
    val outdoorPpm: Int = 420,
    /** 오늘(이번 세션): 환기 횟수, 바깥 공기로 달린 누적(분, 데모 속도면 초. 엔진 집계와 같은 기준), 추정 최고 ppm */
    val todayVentilations: Int = 0,
    val todayFreshAirUnits: Int = 0,
    val todayPeakPpm: Int = 0,
    /** 이번 세션 최대 탑승 인원(도착 카드용) */
    val todayMaxOccupants: Int = 1,
    /** 창문 열림 정도(0~100, 네 창 중 최댓값). 5 이상이면 바깥 공기가 들어오는 것으로 본다 */
    val windowPercent: Int = 0,
    /** 공조 풍량 단계와 최대 단계(마지막 FanSpeedChanged). 공조 전원이 꺼져 있으면 0. 아직 모르면 null */
    val fanLevel: Int? = null,
    val fanMax: Int = 0,
    /** 설정: 창문 방향 반전(max 가 닫힘인 차) */
    val windowInverted: Boolean = false,
    /** 환기 제안을 거절했거나 답이 없어서 다시 묻지 않는 시간의 끝(단조 ms). 없으면 null */
    val staleCooldownUntilMs: Long? = null,
    /** 이번 주행에서 환기 제안을 두 번 연속 거절해 더 묻지 않는 중(엔진 Quiet) */
    val quietThisTrip: Boolean = false,
    /** 환기 제안을 미루는 사유: "arriving"(도착 5분 전), "car-auto"(차가 내외기 제어 중). 없으면 null */
    val holdReason: String? = null,
    /** 내비 목적지까지 남은 분(마지막 DestinationEta). 목적지가 없거나 모르면 null */
    val etaMinutes: Int? = null,
    /** 설정 "2분 환기 맡기기"(엔진 TrustState.delegated) */
    val trustDelegated: Boolean = false,
    /** 도착 카드의 맡기기 질문에 한 답. 아직 안 했으면 null */
    val trustAnswered: Boolean? = null,
    /** 개발자 설정: 핸들 보조 신호(실험), 졸음 개입에 창문 포함 */
    val steeringAssist: Boolean = false,
    val allowWindow: Boolean = false,
    /** 개발자 묶음 펼침 */
    val showDeveloper: Boolean = false,
    /** 자가 진단 보고서. 닫혀 있으면 null */
    val diag: DiagUi? = null,
    /** 개발자 설정: 화면 밖에서도 유지(실험). 켜면 MainActivity 가 포그라운드 서비스를 시작한다 */
    val keepAlive: Boolean = false,
    /** 화면 밖 유지를 시작하지 못한 사유. 자가 진단에 적는다 */
    val keepAliveError: String? = null,
    /** 좌석 값(점유 또는 벨트)이 실제로 오고 있는가. 안 오면 정차 홈의 스테퍼 값을 쓴다 */
    val seatSensorLive: Boolean = false,
    /**
     * 자리별 탑승 값(운전석, 조수석, 뒷줄 왼쪽, 뒷줄 가운데, 뒷줄 오른쪽. SeatLayout 순서).
     * 한 칸은 true(탔다), false(비었다), null(그 자리 값을 못 읽었다). 차가 자리별 값을 주지 않으면(수동 입력, 모의) 전체가 null
     */
    val seats: List<Boolean?>? = null,
    /** 수동으로 넣은 탑승 인원(1~5, 저장됨) */
    val manualOccupants: Int = 1,
    /** 지난번에 넣은 인원을 그대로 쓰는 중("N명 그대로" 칩). 스테퍼를 누르면 꺼진다 */
    val occupantsCarried: Boolean = false,
    /** 조수석에 사람이 있다(좌석 값 기준. 수동 입력만으로는 true 가 되지 않는다) */
    val frontPassenger: Boolean = false,
    /** 차가 내외기를 스스로 제어하는 중 */
    val carAuto: Boolean = false,
    /** 지금 실행은 예고로 시작했거나 화면에서 직접 요청한 환기라 실행 중에도 [취소] 를 받는다(엔진 cancellableRun) */
    val runCancellable: Boolean = false,
    /** 숨 설계(엔진 breath): 환기 설계, 여정 예보, 지나온 곡선과 그림자, 졸리기 쉬운 시간대. 주행 중에만. 전부 추정 */
    val breath: BreathDesign? = null,
    /** 설정 "바깥 미세먼지 참고"(기본 켬)와 수동 선택(자동/좋음/보통/나쁨/매우 나쁨) */
    val pmEnabled: Boolean = true,
    val pmChoice: PmChoice = PmChoice.AUTO,
    /** 정차 홈에 보여 주는 바깥 공기 한 줄: 값, 등급, 출처, 나이 */
    val outdoorStatus: String = "",
    /** 쉼 카드의 낮잠 안내(엔진 NapGuide.text). 쉬는 중이 아니면 null */
    val napTip: String? = null,
    /** 자리별 프로필(Seats 순서 다섯 칸, 저장됨). 사용자가 정차 중에 고르는 설정이다 */
    val seatProfiles: List<SeatProfile> = SeatProfileCodec.NONE,
    /** 프로필 선택 시트가 열려 있는 자리. 닫혀 있으면 null */
    val profileSheetSeat: Int? = null,
    /** 이번 주행에서 살피는 것과 앉은 시간(엔진 care). 읽기 전용 */
    val care: SeatCare = SeatCare(),
    /** 문 신호를 받은 적이 있는가(자가 진단용). 없으면 일어섬을 기어 P 로 선 시간만으로 가린다 */
    val doorSignalSeen: Boolean = false,
) {
    /** 말로 답할 수 있는가: 음성 인식기가 있고 마이크 권한도 있을 때만 */
    val voiceAnswer: Boolean get() = sttAvailable && micGranted
}

/**
 * SignalBus → WakeEngine.accept/tick → WakeEvent 처리.
 * Propose → TTS(끝나면 SpeechFinished) + 오버레이 + STT, Execute → VehicleActuator, Running → 바, Done/Expired → 한마디,
 * RestStarted/Tick/Ended → 쉼 카드, ArrivalCard → 카드 + 기기 내 기록.
 * 앱에서 "판단"은 하지 않는다. 전부 엔진에 있다.
 */
class WakeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("kkaeugi", Context.MODE_PRIVATE)
    private val tripLog = TripLog(app)

    private val bus = SignalBus(onDrop = { s, kept ->
        Log.w("Kkaeugi", "신호 버퍼 초과: ${s::class.simpleName} ${if (kept) "보관했다가 다시 넣어요" else "버림"}")
    })
    /** 설정 "창문 방향 반전". 창문을 여는 쪽(액추에이터, 검증 버튼)과 열림을 읽는 쪽(창문 신호)이 같이 따른다 */
    @Volatile private var windowInverted = prefs.getBoolean(PREF_WINDOW_INVERT, false)
    val mock = MockSignalSource(bus, viewModelScope, windowInverted = { windowInverted }, onControlCall = { name -> onMockControlCall(name) })
    private val vehicleSource = PleosVehicleSource(app, bus, viewModelScope)
    private val navSource = PleosNavSource(app, bus)
    private val fused = FusedLocationCalls(app)
    private val restStops = RestStops(app)

    /** 차량 소스와 같은 호출 층을 쓴다. HVAC 영역 탐색 결과(읽기, 쓰기, 리스너가 같은 영역)를 한 곳에 둔다 */
    private val calls: PleosCalls = vehicleSource.calls
    private val navCalls = NavCalls { navSource.navi }
    private var lastBusPoint: GeoPoint? = null
    /** 버스로 온 위치(LocationUpdate)에는 heading 이 없다. 직전 두 위치의 방위로 채운다 */
    private val busHeading = HeadingHold()
    private val fusedSpeed = SpeedEstimator()
    /** 첫 경유지가 우리가 넣은 쉼터인지 확인된 경우에만 지운다 */
    private val waypointGuard = ai.kkaeugi.app.vehicle.WaypointGuard { MonoClock.now() }

    /** 실제 위치. 내비 → FusedLocation(연결 시) → 마지막 LocationUpdate. 한국 밖/없음 처리는 DemoLocation.resolve 가 한다 */
    private val locationProvider: suspend () -> GeoPoint? = {
        navSource.lastPoint
            ?: (if (vehicleSource.vehicle != null) fused.lastKnown()?.also { p ->
                // 내비 위치가 없을 때: FusedLocation 위치 차이로 속도를 추정해 엔진에도 알린다(추정 못 하면 null)
                val t = MonoClock.now()
                bus.emit(LocationUpdate(t, p.lat, p.lng, fusedSpeed.update(t, p.lat, p.lng)))
            } else null)
            ?: lastBusPoint
    }

    val tracker = RestStopTracker(
        bus, viewModelScope, restStops, locationProvider,
        removeWaypoint = {
            when {
                vehicleSource.vehicle == null -> { onMockControlCall("removeWaypoint(FIRST)"); Result.success(Unit) }
                waypointGuard.canRemove -> navCalls.removeFirstWaypoint().also { waypointGuard.clear() }
                else -> { appendLog("쉼터: 첫 경유지가 우리 것인지 확인되지 않아 지우지 않아요"); Result.success(Unit) }
            }
        },
        onLog = { appendLog("쉼터: $it") },
        onProximityKm = { km -> _ui.update { it.copy(proximityKm = km) } },
        onRouteChanged = { route -> _ui.update { it.copy(currentRoute = route) } },
    )

    val actuator = VehicleActuator(
        bus, viewModelScope, calls, navCalls,
        hasVehicle = { vehicleSource.vehicle != null },
        onLog = { line -> appendLog("제어: $line") },
        onRestStopAdded = { stop ->
            if (vehicleSource.vehicle != null) waypointGuard.onOurAdd()
            tracker.startTracking(stop); _ui.update { it.copy(waypointStop = stop) }
        },
        // Pleos 가 없으면 모의 차량의 값을 바꾼다. 화면의 내외기 상태와 풍량이 실제처럼 움직인다
        mockControls = mock.car,
        windowInverted = { windowInverted },
        onMockCall = { name -> onMockControlCall(name) },
    )
    val verifier = SdkVerifier(
        viewModelScope, calls, navCalls,
        hasVehicle = { vehicleSource.vehicle != null },
        currentLocation = { DemoLocation.resolve(locationProvider()) },
        onLog = { line -> _ui.update { it.copy(verifyLog = (it.verifyLog + line).takeLast(60)) } },
        onTripUnitConfirmed = { seconds -> vehicleSource.forceTripUnit(seconds) },
        windowInverted = { windowInverted },
    )

    private var speaker: Speaker = AndroidSpeaker(app)
    private var listener: AnswerListener = NoAnswerListener
    private var listenJob: Job? = null
    private var restToastJob: Job? = null
    /** 쉼 카드 안내 문장(읽는 말). 바로 뒤에 바깥 공기 질문이 오면 이어서 읽으려고 잠깐 들고 있는다 */
    private var restLineSpoken: String? = null
    private var appVisible = true
    /** 화면이 안 보이는 동안 제안이 연달아 만료된 횟수(엔진과 같은 규칙으로 홈의 "다시 묻기까지"를 맞춘다) */
    private var hiddenExpiries = 0
    /** 시연 시나리오가 도는 동안: 시작 전 값을 들고 있고, 그동안 맡기기 상태를 저장하지 않는다 */
    private val demoIsolation = DemoIsolation()
    /** Pleos TTS, STT 초기화 결과(자가 진단용): null = 콜백이 아직 없음, "" = 성공, 그 밖 = 실패 사유 */
    @Volatile private var ttsError: String? = null
    @Volatile private var sttError: String? = null
    private var diagJob: Job? = null
    private val diagnosis = SelfDiagnosis(app, calls, navSource, fused, outdoor = { outdoorDiagLine() }) {
        val st = _ui.value
        SelfDiagnosis.Info(
            connected = st.pleosAvailable, connectionDetail = st.connectionDetail.ifBlank { "판정 전" },
            registerFailures = vehicleSource.permissionFailures, tripUnit = tripUnitLabel(),
            ttsError = ttsError, sttError = sttError, micGranted = st.micGranted,
            keepAliveOn = st.keepAlive, keepAliveError = st.keepAliveError, windowInverted = windowInverted,
        )
    }
    private var lastEtaRequestMs = 0L

    /**
     * 바깥 미세먼지(Open-Meteo, CAMS 격자 모델값). 위치는 내비 위치 우선(없으면 FusedLocation, 버스 위치, 모의 위치 순).
     * 값을 못 받으면 조용히 null 이고 엔진은 v0.6.2 와 같이 움직인다
     */
    private val outdoor = OutdoorAirSource(viewModelScope, emit = { bus.emit(it) }, now = { MonoClock.now() }, location = { locationProvider() })
    /** 바깥 미세먼지와 지금 시각을 마지막으로 엔진에 알린 때. 1분마다 다시 알린다 */
    private var lastOutdoorRefreshMs = 0L
    /** 쉼 모드의 낮잠 타이머가 끝났다고 이미 말했다 */
    private var napDoneSaid = false
    /** 마지막으로 로그에 적은 바깥 공기 값(값이 바뀔 때만 적는다) */
    private var lastOutdoorLogged: String? = null

    private var config: WakeConfig = DemoSettings.build(
        prefs.getBoolean(PREF_DEMO_FAST, false), prefs.getBoolean(PREF_STEERING_ASSIST, false), prefs.getBoolean(PREF_ALLOW_WINDOW, false),
        walkBreak = true,
    )
    private val engine = WakeEngine(config)

    /** 수신기(GleoReceiver, VerifyReceiver)가 부르는 처리 함수. init 에서 GleoBridge 에 걸고 onCleared 에서 뗀다 */
    private val gleoHandler: (String, Map<String, String>) -> GleoResponse = { function, params -> onGleo(function, params) }
    private val adbHandler: (String, String?, AdbReply) -> Unit = { what, value, reply -> onAdb(what, value, reply) }

    /** 원복 없이 끝난 실행을 가려 그 스냅샷을 버린다 */
    private val execTracker = ExecutionTracker()
    /** 바깥 공기로 달린 시간(엔진 집계와 같은 기준) */
    private val freshAir = FreshAirTally()
    /** 제안마다 올리는 번호. 앞 제안의 TTS 완료 콜백이 새 제안의 것으로 둔갑하지 않게 한다 */
    private var proposalSeq = 0

    /** 앱 타이머용: DRIVING 진입 시각(단조). Odometer 가 없거나 단위 판정 전일 때 트립 시간의 기준 */
    private var tripStartedMs: Long? = null
    private var odometerMinutes = 0
    private var currentKind: TriggerKind? = null

    private val _ui = MutableStateFlow(
        UiState(
            nowMs = MonoClock.now(),
            speakerName = speaker.name, listenerName = listener.name,
            autoIntervene = prefs.getBoolean(PREF_AUTO, true),
            demoFast = prefs.getBoolean(PREF_DEMO_FAST, false),
            evidenceMode = prefs.getBoolean(PREF_EVIDENCE, false),
            windowInverted = prefs.getBoolean(PREF_WINDOW_INVERT, false),
            steeringAssist = prefs.getBoolean(PREF_STEERING_ASSIST, false),
            allowWindow = prefs.getBoolean(PREF_ALLOW_WINDOW, false),
            trustDelegated = TrustCodec.decode(prefs.getString(PREF_TRUST, null)).delegated,
            keepAlive = prefs.getBoolean(PREF_KEEP_ALIVE, false),
            manualOccupants = OccupantInput.clamp(prefs.getInt(PREF_MANUAL_OCCUPANTS, 1)),
            occupantsCarried = prefs.contains(PREF_MANUAL_OCCUPANTS),
            recentTrips = tripLog.recent(5).map { it.line() },
            pmEnabled = prefs.getBoolean(PREF_PM_ENABLED, true),
            pmChoice = PmChoice.of(prefs.getString(PREF_PM_CHOICE, null)),
            seatProfiles = SeatProfileCodec.decode(prefs.getString(PREF_SEAT_PROFILES, null)),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // 맡기기 상태는 엔진이 저장하지 않는다. 저장해 둔 값을 넣고, TrustChanged 가 올 때마다 다시 저장한다
        engine.setTrust(TrustCodec.decode(prefs.getString(PREF_TRUST, null)))
        // 저장해 둔 수동 인원은 처음 값을 내기 전에 잡아 둔다(처음 값 1명이 먼저 처리되면서 저장값을 덮지 않게)
        val savedOccupants = _ui.value.manualOccupants
        if (!_ui.value.autoIntervene) engine.setEnabled(false, MonoClock.now())
        _ui.update { it.copy(longTripMinutes = config.longTripMinutes, co2LimitPpm = engine.effectiveLimitPpm, outdoorPpm = config.outdoorPpm) }
        restoreRest()
        vehicleSource.windowInverted = windowInverted
        // 증거 띠: 제어 호출(set*, addWaypoint, removeWaypoint)의 결과 한 줄. 읽기 호출은 PleosCalls 가 걸러 낸다
        calls.onCall = { line -> _ui.update { it.copy(evidenceLine = line) } }
        navCalls.onCall = { line -> _ui.update { it.copy(evidenceLine = line) } }

        // 신호 구독을 먼저 건다. 버스에는 다시 보내기(replay)가 없어서, 구독 전에 낸 처음 값(혼자, 풍량 3/8, 창문 닫힘, 저장해 둔 인원)은 사라진다
        viewModelScope.launch { bus.signals.collect { s -> onSignal(s) } }

        val sources = mutableListOf<String>()
        vehicleSource.onInfo = { msg -> appendVerify(msg); appendLog("차량: $msg") }
        // 내기순환 영역이 없는 차: 홈에 "이 차에서는 내외기 상태를 읽을 수 없어요" 한 줄
        vehicleSource.onHvacResolved = { supported ->
            _ui.update { it.copy(recircUnsupported = !supported) }
            if (!supported) appendLog("차량: 이 차에서는 내외기 상태를 읽을 수 없어요(내기순환 영역 없음)")
        }
        vehicleSource.onProbe = { connected, detail ->
            _ui.update {
                it.copy(
                    pleosAvailable = connected, probing = false,
                    connectionDetail = if (connected) "연결 $detail" else "모의: $detail",
                    showDemoConsole = !connected,
                    sources = (if (connected) listOf(vehicleSource.name) else emptyList()) + it.sources.filter { s -> s != vehicleSource.name },
                )
            }
            appendLog(if (connected) "Pleos 연결: $detail" else "Pleos 없음, 모의 모드: $detail")
            appendVerify(if (connected) "Pleos Vehicle 연결 ($detail)" else "Pleos Vehicle 없음 → 모의 모드 ($detail)")
            // 등록 실패(퍼미션 거부) 목록은 register 직후 채워진다. 잠시 뒤 UI 에 반영
            viewModelScope.launch { delay(1_000); refreshPermissionFailures() }
        }
        val probing = vehicleSource.start()
        if (navSource.start()) sources += navSource.name
        mock.start(); sources += mock.name
        // 숨 모델 입력의 처음 값(지난번에 넣어 둔 인원, 풍량 3/8, 창문 닫힘). 엔진은 대기 중에도 기억한다.
        // 좌석 값이 오면 그 값이, Pleos 가 연결되면 실제 풍량과 창문이 덮어쓴다. 인원 저장은 스테퍼를 누를 때만 한다
        mock.emitCabinDefaults(occupants = savedOccupants)
        // 저장해 둔 자리별 프로필. 엔진은 저장하지 않는다
        bus.emit(SeatProfilesChanged(MonoClock.now(), _ui.value.seatProfiles))
        // 바깥 미세먼지와 지금 시각의 처음 값. 그 뒤로는 1분마다 다시 알린다(자동이면 30분마다 새로 받는다)
        outdoor.enabled = _ui.value.pmEnabled
        outdoor.choice = _ui.value.pmChoice
        refreshOutdoorAndClock(MonoClock.now())
        vehicleSource.onSeatSensor = { live ->
            _ui.update { it.copy(seatSensorLive = live, seats = if (live) it.seats else null) }
            // 좌석 값이 끊기면 수동 입력 값으로 돌아간다. 조수석은 모르는 것으로 둔다. 엔진도 자리별 값이 없는 것으로 본다
            if (!live) { mock.occupants(_ui.value.manualOccupants); mock.frontPassenger(false); bus.emit(SeatOccupancyChanged(MonoClock.now(), null)) }
        }
        // 자리별 값은 좌석 지도가 그리고, 엔진이 앉은 시간과 자리별 누적에 쓴다
        vehicleSource.onSeats = { seats -> bus.emit(SeatOccupancyChanged(MonoClock.now(), seats)) }
        _ui.update { it.copy(sources = sources, probing = probing, showDemoConsole = !probing, connectionDetail = if (probing) "연결 확인 중" else "모의: SDK 없음") }
        navSource.onWaypointEvent = { s -> appendVerify("내비 이벤트: $s"); appendLog("내비: $s") }
        navSource.onWaypointChangedRaw = { waypointGuard.onWaypointEvent() }

        PleosSpeaker.tryCreate(app, onReady = { p ->
            speaker.release(); speaker = p
            _ui.update { it.copy(speakerName = p.name) }
            ttsError = ""
        }, onFail = { reason -> ttsError = reason /* Android TTS 유지 */ })
        PleosAnswerListener.tryCreate(app, onReady = { l ->
            listener = l
            _ui.update { it.copy(listenerName = l.name, sttAvailable = true) }
            sttError = ""
        }, onFail = { reason -> sttError = reason /* 버튼만 */ })

        // Gleo AI 와 adb 가 부르는 수신기는 매니페스트에 있다. 화면이 살아 있는 동안 처리 함수를 걸어 둔다
        GleoBridge.handler = gleoHandler
        GleoBridge.adb = adbHandler

        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val now = MonoClock.now()
                tickRestoredRest()
                bus.flushPending()
                // 주행 중 1분마다(데모 속도면 5초마다) 목적지까지 남은 시간을 다시 묻는다(답은 리스너로 온다).
                // 엔진은 받은 지 10분(데모 10초)이 지난 값을 쓰지 않는다. 모의 모드에서는 데모 콘솔에 넣어 둔 값을 다시 알린다
                if (engine.tripState == TripState.DRIVING && now - lastEtaRequestMs >= etaRequestMs()) {
                    lastEtaRequestMs = now
                    if (vehicleSource.vehicle != null) navSource.requestDestination() else if (mock.etaMinutes != null) mock.republishEta()
                }
                // 시연 시나리오가 넣은 값(미세먼지, 시각)은 시나리오가 끝날 때까지 덮지 않는다
                if (now - lastOutdoorRefreshMs >= OUTDOOR_REFRESH_MS && !demoIsolation.active) refreshOutdoorAndClock(now)
                handle(engine.tick(now))
                // 숨 설계는 읽기 전용이다. 엔진이 계산한 것을 화면에 옮기기만 한다
                val design = if (engine.tripState == TripState.DRIVING) engine.breath(now) else null
                design?.nap?.let { nap ->
                    if (nap.done && !napDoneSaid && engine.isResting) {
                        napDoneSaid = true
                        say(DemoText.minutesToSeconds(nap.doneText, engine.config.demoFast))
                    }
                }
                _ui.update {
                    it.copy(
                        breath = design, outdoorStatus = outdoor.statusLine(now), care = engine.care(now),
                        nowMs = now, tripMinutes = displayTripMinutes(now), permissionFailures = vehicleSource.permissionFailures, tripUnit = tripUnitLabel(),
                        // 주행이 끝난 뒤에는 도착 카드가 쓰도록 마지막 값을 그대로 둔다
                        todayFreshAirUnits = if (it.tripState == TripState.DRIVING) freshAir.units(now, unitMs()) else it.todayFreshAirUnits,
                        // 야간 장거리면 엔진이 한도를 낮춘다. 게이지 한도선이 그 값을 따른다
                        co2LimitPpm = engine.effectiveLimitPpm,
                        staleCooldownUntilMs = it.staleCooldownUntilMs?.takeIf { until -> until > now },
                        // 엔진이 환기 제안을 미루는 동안만 홈에 사유를 보여 준다
                        holdReason = if (it.tripState == TripState.DRIVING) HomeHold.reason(engine.arrivingSoon, engine.carAutoActive) else null,
                    )
                }
            }
        }
    }

    private fun etaRequestMs(): Long = if (engine.config.demoFast) ETA_REQUEST_DEMO_MS else ETA_REQUEST_MS

    /** 지금 시각(자정부터 흐른 분). 벽시계는 여기서만 읽고 엔진에는 LocalClock 으로 넘긴다 */
    private fun minuteOfDay(): Int = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }

    /** 바깥 미세먼지와 지금 시각을 엔진에 다시 알린다. 시각은 데모 콘솔에서 바꿔 둔 값이 있으면 그 값을 쓴다 */
    private fun refreshOutdoorAndClock(now: Long) {
        lastOutdoorRefreshMs = now
        outdoor.refresh()
        bus.emit(LocalClock(now, mock.clockOverride ?: minuteOfDay()))
    }

    /** 설정 "바깥 미세먼지 참고". 끄면 값 없음을 넘겨 엔진이 CO2 추정만으로 판단한다. 저장됨 */
    fun setPmEnabled(on: Boolean) {
        prefs.edit().putBoolean(PREF_PM_ENABLED, on).apply()
        outdoor.enabled = on
        _ui.update { it.copy(pmEnabled = on) }
        refreshOutdoorAndClock(MonoClock.now())
        appendLog("설정 → 바깥 미세먼지 참고 ${if (on) "ON" else "OFF"}")
    }

    /** 바깥 미세먼지 선택: 자동(Open-Meteo) 또는 등급을 직접 고른다(네트워크가 없는 에뮬레이터와 시연용). 저장됨 */
    fun setPmChoice(choice: PmChoice) {
        prefs.edit().putString(PREF_PM_CHOICE, choice.name).apply()
        outdoor.choice = choice
        _ui.update { it.copy(pmChoice = choice) }
        refreshOutdoorAndClock(MonoClock.now())
        appendLog("설정 → 바깥 미세먼지 ${choice.label}")
    }

    /** 자가 진단의 "바깥 공기" 한 줄: 값, 출처, 나이. 자동이면 지금 한 번 받아 본다. 못 받아도 앱은 돌기 때문에 실패가 아니라 대체로 적는다 */
    private suspend fun outdoorDiagLine(): DiagLine {
        val name = "바깥 공기"
        if (!outdoor.enabled) return DiagLine.alt(name, outdoor.statusLine())
        if (outdoor.choice != PmChoice.AUTO) return DiagLine.ok(name, outdoor.statusLine())
        val r = outdoor.fetchNow()
        return if (r != null) DiagLine.ok(name, OutdoorAirSource.describe(r, MonoClock.now()))
        else DiagLine.alt(name, "값 없음(${outdoor.lastError ?: "응답 없음"}). 환기 판단은 CO2 추정만으로 해요. 설정에서 등급을 직접 고를 수 있어요")
    }

    private fun refreshPermissionFailures() = _ui.update { it.copy(permissionFailures = vehicleSource.permissionFailures) }

    /** 화면 단위 하나의 길이: 실제는 1분, 데모 속도면 1초 */
    private fun unitMs(): Long = if (engine.config.demoFast) 1_000L else 60_000L

    /** 모의 차량이 받은 제어 호출. 실제 SDK 호출이 아니라는 표시를 붙여 증거 띠에 쓴다 */
    private fun onMockControlCall(name: String) {
        EvidenceLine.format(name, ok = true)?.let { line -> _ui.update { it.copy(evidenceLine = "모의 $line") } }
    }
    private fun tripUnitLabel() = when (vehicleSource.tripUnitIsSeconds) { null -> "판정 전(앱 타이머)"; true -> "초"; false -> "분" }

    private fun displayTripMinutes(now: Long): Int {
        val appTimer = tripStartedMs?.let { ((now - it) / 60_000L).toInt() } ?: 0
        return maxOf(appTimer, odometerMinutes)
    }

    private fun onSignal(s: Signal) {
        // 화면에 보여 줄 차량 상태
        when (s) {
            is GearChanged -> _ui.update { it.copy(gear = s.gear) }
            is RecirculationChanged -> {
                actuator.onRecirculationObserved(s.recirculating)
                if (s.recirculating) {
                    // 엔진과 같은 규칙: 엔진이 환기를 유지하는 중이 아니면 바깥 공기 구간은 여기서 끝난다.
                    // 바깥 공기로 두기로 한 뒤 운전자가 직접 내기순환을 켠 것은 거절로 본다(같은 쿨다운)
                    val ventActive = _ui.value.running?.let { it.remainingSec > 0 } == true
                    val wasKeeping = freshAir.onRecirculationOn(s.t, unitMs(), ventActive)
                    if (wasKeeping) _ui.update { it.copy(staleCooldownUntilMs = s.t + engine.config.declineCooldownMinutes * unitMs()) }
                }
                _ui.update { it.copy(recirculating = s.recirculating, recircSinceMs = if (s.recirculating) (it.recircSinceMs ?: s.t) else null) }
            }
            is LowBeamChanged -> _ui.update { it.copy(night = s.on) }
            is OccupantsChanged -> {
                // 좌석 값이 안 올 때는 스테퍼와 데모 콘솔이 같은 값을 가리키게 한다. 저장은 스테퍼를 누를 때만 한다
                _ui.update {
                    it.copy(
                        occupants = s.count.coerceAtLeast(1), todayMaxOccupants = maxOf(it.todayMaxOccupants, s.count),
                        manualOccupants = if (it.seatSensorLive) it.manualOccupants else OccupantInput.clamp(s.count),
                    )
                }
            }
            is FrontPassengerChanged -> _ui.update { it.copy(frontPassenger = s.present) }
            // 자리별 값(차의 좌석 값, 시연 시나리오). 좌석 지도가 이 값을 그대로 그린다
            is SeatOccupancyChanged -> _ui.update { it.copy(seats = s.seats) }
            is SeatProfilesChanged -> _ui.update { it.copy(seatProfiles = SeatProfileCodec.normalize(s.profiles)) }
            // 문 열림 경고등이 오는 차면 그것을 문 신호로 쓴다(앉은 시간의 "일어섬"을 가리는 데만 쓴다)
            is TelltaleChanged -> if (s.type == Telltale.DOOR_OPEN) { _ui.update { it.copy(doorSignalSeen = true) }; bus.emit(DoorChanged(s.t, s.on)) }
            is AutoClimateChanged -> _ui.update { it.copy(carAuto = s.autoRecirculation) }
            is WindowOpenChanged -> _ui.update { it.copy(windowPercent = s.percent.coerceIn(0, 100)) }
            is FanSpeedChanged -> _ui.update { it.copy(fanLevel = s.level, fanMax = s.max) }
            is WiperChanged -> _ui.update { it.copy(raining = s.on) }
            is TripTimeUpdate -> { odometerMinutes = s.minutes; _ui.update { it.copy(tripMinutes = displayTripMinutes(s.t)) } }
            is LocationUpdate -> {
                // 버스 위치에는 heading 이 없다. 직전 두 위치의 방위로 채우고, 아직 모르면 앞의 값을 쓴다.
                // 모의 기본 heading 은 모의 위치일 때만 쓴다. 내비가 실제 위치를 주는 동안에는 지어내지 않는다(반대 방향 쉼터를 고르게 된다)
                val heading = busHeading.resolve(null, s.speedKph, s.lat, s.lng) ?: lastBusPoint?.heading
                    ?: HeadingFallback.mockHeading(pleosConnected = vehicleSource.vehicle != null, navHasLocation = navSource.lastPoint != null)
                lastBusPoint = GeoPoint(s.lat, s.lng, heading); tracker.onLocation(lastBusPoint!!)
            }
            is RestStopCandidate -> _ui.update {
                it.copy(nextRestStop = s.stop, usingDemoLocation = tracker.usingDemoLocation, headingUnknown = !tracker.usingDemoLocation && tracker.lastLocation?.let { p -> p.heading == null } == true)
            }
            is DestinationEta -> _ui.update { it.copy(etaMinutes = s.remainMinutes) }
            is ActionResult -> {
                // 쉼 모드의 바깥 공기는 환기 횟수와 "바깥 공기로 달린 시간"에 넣지 않는다(엔진과 같은 기준)
                if (s.action is Action.Ventilate && s.ok && !engine.isResting) {
                    freshAir.onVentilated(s.t)
                    _ui.update { it.copy(lastVentilationWallMs = System.currentTimeMillis(), todayVentilations = it.todayVentilations + 1) }
                }
                // 풍량을 올린 직후와 원복 직후의 실제 풍량을 엔진에 바로 알린다(숨 모델의 외기 유입량이 풍량에 비례한다).
                // 모의 차량은 값이 바뀌는 순간 스스로 신호를 낸다
                if (s.ok && (s.action is Action.Ventilate || s.action == Action.Revert)) vehicleSource.refreshFan()
            }
            else -> {}
        }
        if (s is OutdoorAir) {
            // 1분마다 오는 값이라 바뀔 때만 적는다
            val text = s.pm25UgM3?.let { "PM2.5 ${"%.0f".format(it)}µg/m³ (${s.source}, ${s.ageMin}분 전)" } ?: "값 없음 (${s.source})"
            val key = "${s.pm25UgM3}|${s.source}"
            if (key != lastOutdoorLogged) { lastOutdoorLogged = key; appendLog("바깥 공기: $text") }
        }
        if (s !is RestStopCandidate && s !is SteeringAngle && s !is LocationUpdate && s !is OutdoorAir && s !is LocalClock && s !is DoorChanged) appendLog("신호 " + s.toString().substringBefore('('))
        handle(engine.accept(s))
    }

    private fun handle(events: List<WakeEvent>) {
        handleEach(events)
        // 엔진 상태에서 바로 읽는 표시: 실행 중 취소 가능 여부, 조용 모드(쉬고 나면 풀린다)
        val cancellable = engine.cancellableRun
        val quiet = engine.quietMode
        _ui.update { st ->
            val q = if (st.tripState == TripState.DRIVING) quiet else st.quietThisTrip
            if (st.runCancellable == cancellable && st.quietThisTrip == q) st else st.copy(runCancellable = cancellable, quietThisTrip = q)
        }
    }

    private fun handleEach(events: List<WakeEvent>) {
        for (e in events) when (e) {
            is WakeEvent.Propose -> onPropose(e)
            is WakeEvent.Announce -> onAnnounce(e)
            is WakeEvent.Quiet -> {
                // 두 번 연속 거절. 이 주행에서는 환기를 더 묻지 않는다. 한 번만 읽고 홈에 표시한다.
                // 조용 모드 중 한도의 2배를 넘었다는 알림도 같은 이벤트로 온다(주행당 한 번)
                _ui.update { it.copy(quietThisTrip = true, staleCooldownUntilMs = null) }
                say(e.text)
            }
            is WakeEvent.TrustChanged -> {
                // 시연 시나리오가 만든 변화는 저장하지 않는다(끝나면 시작 전 값으로 되돌린다)
                if (!demoIsolation.active) saveTrust(e.state)
                _ui.update { it.copy(trustDelegated = e.state.delegated) }
                appendLog("맡기기: ${if (e.state.delegated) "켜짐" else "꺼짐"}, 연속 동의 ${e.state.consecutiveYes}, 취소 ${e.state.cancelCount}")
            }
            is WakeEvent.ProposalExpired -> {
                stopListening()
                _ui.update { it.copy(proposal = null, announce = null) }
                currentKind?.let { k ->
                    tripLog.appendAnswer(label(k), yes = false, via = if (appVisible) "무응답" else "화면 밖")
                    // 화면이 안 보이는 동안 넘어간 제안은 엔진이 거절로 세지 않고 5분만 쉰다. 두 번 연속부터는 거절과 같은 시간
                    if (!appVisible) hiddenExpiries++
                    if (k == TriggerKind.STALE_AIR) markStaleCooldown(
                        if (appVisible) engine.config.declineCooldownMinutes else hiddenExpiryCooldown(hiddenExpiries, engine.config.declineCooldownMinutes),
                    )
                }
                currentKind = null
                say(e.text)
            }
            is WakeEvent.Execute -> {
                stopListening()
                val isRevert = execTracker.onExecute(e.actions)
                _ui.update { st ->
                    st.copy(
                        proposal = null, announce = null,
                        // 동의 직후 Running 이 오기 전까지 화면이 비지 않게 바로 바를 띄운다.
                        // 쉼 카드가 떠 있는 동안과 쉼이 끝난 직후의 조용한 원복에는 바를 띄우지 않는다
                        running = if (engine.isResting || (isRevert && st.running == null)) st.running else RunningBar(e.kind, 0, if (isRevert) "되돌리는 중..." else "실행 중..."),
                    )
                }
                appendLog("실행: ${if (isRevert) "원복" else "실행 " + e.actions.joinToString { it::class.simpleName ?: "" }}")
                actuator.execute(e.actions)
            }
            is WakeEvent.Running -> _ui.update { it.copy(running = RunningBar(e.kind, e.remainingSec, e.label)) }
            is WakeEvent.Done -> {
                val now = MonoClock.now()
                // 원복 없이 끝난 실행(바깥 공기로 두기, 풍량 올리기): 그 실행의 스냅샷을 버려 나중의 원복이 되돌리지 않게 한다.
                // 문장으로 가리지 않는다. Execute 뒤 Execute([Revert]) 없이 Done 이 왔는지만 본다
                when (val end = execTracker.onDone(resting = engine.isResting)) {
                    is ExecEnd.Kept -> {
                        actuator.discardSnapshots(end.actions)
                        if (end.actions.any { it is Action.Ventilate }) freshAir.onKept(now)
                    }
                    ExecEnd.Reverted -> freshAir.onReverted(now, unitMs(), _ui.value.recirculating)
                    // 쉬는 동안 바깥 공기로 바꿔 둔 것. 쉼이 끝나면 Execute([Revert]) 가 오므로 스냅샷을 그대로 둔다
                    ExecEnd.HeldForRest -> {}
                    ExecEnd.None -> {}
                }
                stopListening()
                // 문장 없이 닫힌 제안(조건이 사라짐, 자동 개입 OFF 등): 읽던 문장을 멈춘다
                val closedSilently = e.text == null && (_ui.value.proposal != null || _ui.value.announce != null)
                currentKind = null
                _ui.update { st ->
                    st.copy(
                        proposal = null, announce = null, running = null,
                        // 환기가 끝나면 엔진처럼 내기순환 카운터를 0 부터
                        recircSinceMs = if (e.kind == TriggerKind.STALE_AIR && e.text != null && st.recirculating) now else st.recircSinceMs,
                    )
                }
                if (closedSilently) speaker.stop()
                // Gleo 가 넣은 쉼터 경유는 Gleo 가 답을 읽는다. 같은 말을 앱이 겹쳐 읽지 않고 화면에만 남긴다
                val muted = muteRestStopDone && e.kind == TriggerKind.WALK_BREAK
                if (e.kind == TriggerKind.WALK_BREAK) muteRestStopDone = false
                e.text?.let { if (muted) { appendLog("말(읽지 않음): $it"); _ui.update { st -> st.copy(lastSaid = it) } } else say(it) }
            }
            is WakeEvent.Deferred -> {
                appendLog("미룸(${e.reason})")
                // 도착 임박, 차의 자동 내외기는 홈 문구로 알린다. 하향등이 막 켜진 것은 표시하지 않는다
                _ui.update { it.copy(deferred = e.reason, holdReason = HomeHold.fromDeferred(e.reason) ?: it.holdReason) }
            }
            is WakeEvent.TripStateChanged -> onTripState(e.state)
            is WakeEvent.RestStarted -> {
                val demo = engine.config.demoFast
                val unitSec = if (demo) 1 else 60
                // 화면·음성·경유지 이름 모두 같은 쉼터 표시 이름
                val stopName = restStopDisplayName(e.stopName)
                execTracker.reset()
                freshAir.onRestStarted(MonoClock.now(), unitMs())
                // 낮잠 안내와 타이머는 엔진이 정한다. 데모 속도면 "15분"을 "15초"로 보여 준다
                napDoneSaid = false
                val napTip = engine.breath(MonoClock.now()).nap?.let { DemoText.minutesToSeconds(it.text, demo) }
                _ui.update { it.copy(rest = RestUi(stopName, e.targetMinutes, e.targetMinutes * unitSec, demo, walk = e.walk), proposal = null, announce = null, running = null, napTip = napTip) }
                saveRest(stopName, e.targetMinutes, e.targetMinutes * unitSec, demo)
                // 데모 속도에서는 실제로 초 단위라 "15초만" 으로 읽는다. 걷기 쉼이면 "내려서 5분 걸어요"
                // 바로 뒤에 바깥 공기 질문(Propose)이 오면 그쪽에서 이 문장과 이어서 읽는다
                restLineSpoken = DemoText.restLine(stopName, e.targetMinutes, demo, forSpeech = true, walk = e.walk)
                say(restLineSpoken!!)
            }
            is WakeEvent.RestTick -> _ui.update { st -> st.copy(rest = st.rest?.copy(remainingSec = e.remainingSec)) }
            is WakeEvent.RestEnded -> {
                tracker.onRestEnded()
                waypointGuard.clear()
                clearRest()
                freshAir.onRestEnded(MonoClock.now(), _ui.value.recirculating)
                // 데모 속도로 쉰 것은 "17분" 이 아니라 "17초"
                val text = DemoText.minutesToSeconds(e.text, _ui.value.rest?.demo ?: engine.config.demoFast)
                _ui.update { st ->
                    st.copy(
                        rest = null, restEndedToast = text, waypointStop = null, napTip = null,
                        recircSinceMs = if (st.recirculating) MonoClock.now() else null,
                    )
                }
                say(text)
                restToastJob?.cancel()
                restToastJob = viewModelScope.launch { delay(2_000); _ui.update { it.copy(restEndedToast = null) } }
            }
            is WakeEvent.AirEstimate -> _ui.update {
                // 화면의 "약 N분 뒤"는 반올림해서 보여 준다(엔진 값은 올림한 정수)
                val limit = engine.effectiveLimitPpm
                val minutes = AirMath.roundedMinutesToLimit(e.minutesToLimit, e.ventilating, e.ppm, e.occupants, limit, engine.config)
                it.copy(
                    air = AirUi(e.ppm, e.occupants, minutes, e.ventilating, falling = it.air?.let { prev -> e.ppm < prev.ppm || (e.ppm == prev.ppm && prev.falling) } ?: false),
                    co2LimitPpm = limit,
                    todayPeakPpm = maxOf(it.todayPeakPpm, e.ppm), todayMaxOccupants = maxOf(it.todayMaxOccupants, e.occupants),
                )
            }
            is WakeEvent.ArrivalCard -> {
                clearRest(); clearSession()
                _ui.update { it.copy(arrival = e.card, proposal = null, announce = null, running = null, rest = null, trustAnswered = null, napTip = null, breath = null) }
                // 데모 속도면 문장 안의 분도 초로 읽는다
                say(DemoText.arrivalHeadline(e.card, engine.config.demoFast))
                tripLog.appendSummary(e.card)
                _ui.update { it.copy(recentTrips = tripLog.recent(5).map { t -> t.line() }) }
            }
        }
    }

    /** 환기 제안을 거절했거나 답이 없었다. 엔진이 같은 시간 동안 다시 묻지 않으니 홈 문구도 그렇게 알린다 */
    private fun markStaleCooldown(units: Int = engine.config.declineCooldownMinutes) {
        val until = MonoClock.now() + units * unitMs()
        _ui.update { it.copy(staleCooldownUntilMs = until) }
    }

    private fun onTripState(state: TripState) {
        appendLog("상태: $state")
        when (state) {
            TripState.DRIVING -> {
                val now = MonoClock.now()
                // 달리기 시작하면 자가 진단 화면은 닫는다(주행 화면을 가리지 않게)
                closeDiagnosis()
                hiddenExpiries = 0
                // 프로세스가 정리됐다 다시 뜬 경우: 저장해 둔 세션 시작 시각으로 트립 시간을 이어 센다(30분 안에 다시 떴을 때만)
                val carriedMin = restoredSessionMinutes()
                execTracker.reset()
                freshAir.reset()
                tripStartedMs = now - carriedMin * 60_000L
                odometerMinutes = 0
                mock.resetTripMinutes()
                clearRest()
                if (carriedMin > 0) {
                    appendLog("이전 세션 이어서: 트립 ${carriedMin}분부터")
                    mock.addTripMinutes(carriedMin) // 엔진에도 TripTimeUpdate 로 알린다(야간 장거리 판정)
                } else saveSessionStart()
                tracker.onDrivingChanged(true)
                // 새 주행마다 목적지까지 남은 분을 다시 알린다. 목적지가 없으면 null
                lastEtaRequestMs = now
                if (vehicleSource.vehicle != null) navSource.onNewTrip() else mock.republishEta()
                _ui.update {
                    it.copy(
                        tripState = state, deferred = null, lastSaid = null,
                        // D 재진입: 도착 카드는 자동으로 닫는다(engine.reset 은 부르지 않는다)
                        arrival = null, rest = null, restEndedToast = null, waypointStop = null,
                        tripMinutes = carriedMin, proximityKm = null,
                        todayVentilations = 0, todayFreshAirUnits = 0, todayPeakPpm = 0, todayMaxOccupants = it.occupants, air = null,
                        recircSinceMs = if (it.recirculating) now else null, staleCooldownUntilMs = null,
                        quietThisTrip = false, holdReason = null, announce = null, trustAnswered = null,
                        // 달리기 시작하면 자리 설정 시트는 닫는다(정차 중에만 고른다)
                        profileSheetSeat = null,
                    )
                }
            }
            TripState.ENDED -> {
                tracker.onDrivingChanged(false)
                waypointGuard.clear()
                clearRest(); clearSession()
                val now = MonoClock.now()
                execTracker.reset()
                freshAir.onTripEnded(now, unitMs())
                // 도착 카드가 쓰도록 바깥 공기 누적의 마지막 값을 남긴다
                _ui.update {
                    it.copy(
                        tripState = state, deferred = null, rest = null, todayFreshAirUnits = freshAir.units(now, unitMs()), staleCooldownUntilMs = null,
                        quietThisTrip = false, holdReason = null, announce = null,
                        // 정차하면 묻지 않고 지난 인원을 그대로 둔다. 칩으로만 알린다
                        occupantsCarried = !it.seatSensorLive,
                    )
                }
            }
            TripState.IDLE -> _ui.update { it.copy(tripState = state) }
        }
    }

    private fun onPropose(e: WakeEvent.Propose) {
        // 쉼 모드의 바깥 공기 질문은 쉼 카드 안에 그리고, 어디에도 세지 않는다(엔진과 같다)
        val restQuestion = engine.isResting
        currentKind = if (restQuestion) null else e.kind
        _ui.update { it.copy(proposal = Proposal(e.kind, e.text, e.timeoutSec, passengerMayAnswer = e.passengerMayAnswer, restQuestion = restQuestion), announce = null, deferred = null) }
        appendLog("말: ${e.text}")
        _ui.update { it.copy(lastSaid = e.text) }
        // 쉼 카드 안내("15분만 쉬고 가세요")를 막 읽기 시작했으면 끊지 않고 질문을 이어서 읽는다
        val spoken = if (restQuestion) listOfNotNull(restLineSpoken, e.text).joinToString(" ") else e.text
        restLineSpoken = null
        speakThenListen(spoken, isOpen = { _ui.value.proposal != null }) { now ->
            _ui.update { st -> st.copy(proposal = st.proposal?.copy(countdownFrom = now)) }
        }
    }

    /** 맡겨 둔 환기의 예고. 문장을 읽고(끝나면 SpeechFinished) 취소 창을 센다. "아니/괜찮아/취소" 는 취소, "응" 은 바로 실행 */
    private fun onAnnounce(e: WakeEvent.Announce) {
        currentKind = e.kind
        // 말로 답할 수 없는 환경이면 "아니라고 하세요" 대신 "취소를 누르세요"로 읽고 보여 준다
        val text = announceText(e.text, _ui.value.voiceAnswer)
        _ui.update { it.copy(announce = AnnounceUi(text, e.cancelWindowSec), proposal = null, deferred = null, lastSaid = text) }
        appendLog("예고: $text")
        speakThenListen(text, isOpen = { _ui.value.announce != null }) { now ->
            _ui.update { st -> st.copy(announce = st.announce?.copy(countdownFrom = now)) }
        }
    }

    /**
     * 화면에는 괄호(추정 수치)까지 보여 주고, 읽을 때는 괄호를 뺀다.
     * TTS 가 끝나면 SpeechFinished → 엔진이 그때부터 대기 시간을 센다. STT 도 그때 켠다(자기 목소리를 듣지 않게).
     */
    private fun speakThenListen(text: String, isOpen: () -> Boolean, onRead: (Long) -> Unit) {
        val seq = ++proposalSeq
        speaker.speak(speechText(text)) {
            val now = MonoClock.now()
            // 이미 닫혔거나 다른 제안으로 바뀌었으면 이 완료는 버린다
            if (seq != proposalSeq || !isOpen()) return@speak
            onRead(now)
            bus.emit(SpeechFinished(now))
            listenJob?.cancel()
            listenJob = viewModelScope.launch {
                delay(150)
                if (seq != proposalSeq || !isOpen()) return@launch
                // 마이크 권한이 없으면 듣지 않는다(화면도 버튼으로 답하라고 안내한다)
                if (!_ui.value.voiceAnswer) return@launch
                listener.start { yes -> appendLog("음성 답: ${if (yes) "동의" else "거부"}"); answer(yes, via = "음성") }
            }
        }
    }

    private fun stopListening() { listenJob?.cancel(); listenJob = null; listener.stop() }

    /** 오버레이 버튼 또는 STT 에서 */
    fun answer(yes: Boolean, via: String = "버튼") {
        val st = _ui.value
        if (st.proposal == null && st.announce == null) return
        stopListening()
        speaker.stop()
        currentKind?.let { k ->
            tripLog.appendAnswer(if (st.announce != null) "환기 예고" else label(k), yes, via)
            if (!yes && k == TriggerKind.STALE_AIR) markStaleCooldown()
        }
        bus.emit(UserAnswer(MonoClock.now(), yes))
    }

    /**
     * 실행 중 바의 [취소]. 예고로 시작한 환기와 화면에서 직접 요청한 환기는 유지 시간 내내 취소할 수 있다.
     * 엔진이 바로 원복을 시작한다(맡긴 환기였으면 취소 1회로 센다)
     */
    fun cancelRun() {
        if (!engine.cancellableRun) return
        speaker.stop()
        tripLog.appendAnswer("환기 실행 중", yes = false, via = "버튼")
        appendLog("실행 중 취소")
        // 맡긴 환기를 취소하면 엔진이 거절과 같은 시간 동안 다시 꺼내지 않는다
        if (currentKind == TriggerKind.STALE_AIR) markStaleCooldown()
        bus.emit(UserAnswer(MonoClock.now(), false))
    }

    /** 주행 홈의 "바깥 공기로"(조용 모드에서 한도를 넘었을 때만 보인다). 묻지 않고 바로 2분 환기하고 원복한다 */
    fun requestFreshAir() {
        val events = engine.requestVentilation(MonoClock.now())
        if (events.isEmpty()) return
        appendLog("화면에서 직접 바깥 공기로")
        handle(events)
    }

    /** 도착 카드의 맡기기 질문: [맡길게요] / [계속 물어봐 주세요]. 엔진이 TrustChanged 로 답하면 그때 저장한다 */
    fun answerTrust(accept: Boolean) {
        if (_ui.value.arrival?.trustOffer == null || _ui.value.trustAnswered != null) return
        _ui.update { it.copy(trustAnswered = accept) }
        appendLog("맡기기 질문 답: ${if (accept) "맡길게요" else "계속 물어봐 주세요"}")
        bus.emit(TrustAnswer(MonoClock.now(), accept))
    }

    /** 설정 "2분 환기 맡기기". 켜고 끌 때마다 엔진에 넣고 저장한다 */
    fun setTrustDelegated(on: Boolean) {
        val next = TrustCodec.toggled(engine.trust, on)
        // 끄는 순간 예고가 떠 있으면 엔진이 닫는다(Done). 켜면 그 주행의 "다시 묻기" 상태가 풀린다
        val events = engine.setTrust(next, MonoClock.now())
        if (!demoIsolation.active) saveTrust(next)
        _ui.update { it.copy(trustDelegated = next.delegated) }
        handle(events)
        appendLog("설정 → 2분 환기 맡기기 ${if (on) "ON" else "OFF"}")
    }

    private fun saveTrust(state: TrustState) = prefs.edit().putString(PREF_TRUST, TrustCodec.encode(state)).apply()

    /** 개발자 설정: 핸들 보조 신호(실험). 저장됨 */
    fun setSteeringAssist(on: Boolean) {
        prefs.edit().putBoolean(PREF_STEERING_ASSIST, on).apply()
        _ui.update { it.copy(steeringAssist = on) }
        applyConfig()
        appendLog("설정 → 핸들 보조 신호 ${if (on) "ON" else "OFF"}")
    }

    /** 개발자 설정: 졸음 개입에 창문 포함. 창문 방향을 실제 차에서 확인한 뒤에만 켠다. 저장됨 */
    fun setAllowWindow(on: Boolean) {
        prefs.edit().putBoolean(PREF_ALLOW_WINDOW, on).apply()
        _ui.update { it.copy(allowWindow = on) }
        applyConfig()
        appendLog("설정 → 졸음 개입에 창문 포함 ${if (on) "ON" else "OFF"}")
    }

    /** 지금 화면의 토글 값으로 엔진 설정을 다시 만든다(엔진은 새로 만들지 않는다) */
    private fun applyConfig() {
        val st = _ui.value
        config = DemoSettings.build(st.demoFast, st.steeringAssist, st.allowWindow, walkBreak = true)
        engine.updateConfig(config, MonoClock.now())
        _ui.update { it.copy(longTripMinutes = config.longTripMinutes, co2LimitPpm = engine.effectiveLimitPpm, outdoorPpm = config.outdoorPpm) }
    }

    fun toggleDeveloper() = _ui.update { it.copy(showDeveloper = !it.showDeveloper) }

    /**
     * 정차 홈의 탑승 인원 스테퍼(1~5). 좌석 값이 오는 중이거나 주행 중이면 바꾸지 않는다.
     * 수동 입력만으로는 조수석에 사람이 있는지 모르므로 FrontPassengerChanged 는 보내지 않는다
     */
    fun stepManualOccupants(delta: Int) {
        val st = _ui.value
        val next = OccupantInput.step(st.manualOccupants, delta, st.seatSensorLive, st.tripState == TripState.DRIVING)
        _ui.update { it.copy(occupantsCarried = false) }
        if (next == st.manualOccupants) return
        prefs.edit().putInt(PREF_MANUAL_OCCUPANTS, next).apply()
        _ui.update { it.copy(manualOccupants = next) }
        mock.occupants(next)
        appendLog("탑승 인원 수동 입력: ${next}명")
    }

    // ---------- Gleo Interface(음성 비서가 부르는 함수)와 adb 제어 검증 ----------

    /** Gleo 가 넣은 쉼터 경유의 끝말은 앱이 한 번 더 읽지 않는다(Gleo 가 답을 읽는다) */
    private var muteRestStopDone = false
    private var verifyJob: Job? = null

    /** 지금 상태에서 Gleo 답에 필요한 것만 뽑는다. 판단은 엔진이 한다 */
    private fun gleoState(): GleoState {
        val st = _ui.value
        val now = MonoClock.now()
        val driving = engine.tripState == TripState.DRIVING
        val care = engine.care(now)
        val outdoor = if (driving) engine.breath(now).outdoor else null
        val stop = engine.requestableRestStop
        val air = st.air?.takeIf { driving }
        return GleoState(
            driving = driving, resting = engine.isResting, busy = engine.phaseName != "IDLE",
            recirculating = st.recirculating, carAuto = engine.carAutoActive, enabled = st.autoIntervene,
            ppm = air?.ppm, level = air?.let { ai.kkaeugi.app.ui.airLevel(it.ppm, st.co2LimitPpm).label },
            minutesToLimit = air?.minutesToLimit, ventilating = air?.ventilating ?: false,
            pmGrade = (outdoor?.grade ?: engine.outdoorGrade)?.label, pm25 = outdoor?.pm25?.let { Math.round(it).toInt() },
            sitMinutes = care.sitMinutes, breakMinutes = care.breakMinutes,
            restStopName = stop?.let { restStopDisplayName(it.name) }, restStopKm = stop?.let { Math.round(it.distanceKm).toInt() },
            waypointName = engine.currentWaypoint?.let { restStopDisplayName(it.name) },
            headingUnknown = st.headingUnknown, ventSeconds = engine.config.ventilateSeconds, demo = engine.config.demoFast,
        )
    }

    /**
     * Gleo(또는 adb)가 함수를 불렀다. 메인 스레드에서 온다. 답을 정하고, 부탁할 일이 있으면 엔진의 직접 요청 경로로 넘긴다
     * (환기는 requestVentilation: 묻지 않고 바꾸고 되돌린다. 쉼터는 requestRestStop: 경유지만 넣는다).
     * 엔진이 받지 않으면(그 사이 상태가 바뀌었다) 못 했다고 답한다
     */
    private fun onGleo(function: String, params: Map<String, String>): GleoResponse {
        val outcome = GleoLogic.handle(function, params, gleoState())
        val now = MonoClock.now()
        val events = when (outcome.command) {
            GleoCommand.VENTILATE -> engine.requestVentilation(now)
            GleoCommand.ADD_REST_STOP -> engine.requestRestStop(now).also { if (it.isNotEmpty()) muteRestStopDone = true }
            null -> null
        }
        if (events != null && events.isNotEmpty()) handle(events)
        val response = if (events != null && events.isEmpty()) GleoLogic.refused(function) else outcome.response
        appendLog("Gleo: $function $params → ${response.status.wire} ${response.korean}")
        return response
    }

    /** 자가 진단의 "Gleo" 절: 스키마의 함수 수와 문서 제약, 수신기 등록, 마지막 호출과 답 */
    private fun gleoDiagLines(): List<DiagLine> {
        val app = getApplication<Application>()
        val schema = runCatching { app.assets.open("schema.json").bufferedReader().use { it.readText() } }
        val schemaLine = schema.fold(
            onSuccess = { text ->
                val s = GleoSchema.inspect(text, app.packageName)
                if (s.problems.isEmpty()) DiagLine.ok("schema.json", "함수 ${s.functions.size}개(상한 ${Gleo.MAX_FUNCTIONS}): ${s.functions.joinToString(", ")}")
                else DiagLine.fail("schema.json", s.problems.joinToString("; "))
            },
            onFailure = { DiagLine.fail("schema.json", "assets 에서 못 읽음: ${it.javaClass.simpleName}") },
        )
        val registered = Gleo.FUNCTIONS.count { fn ->
            runCatching { app.packageManager.queryBroadcastReceivers(android.content.Intent(fn).setPackage(app.packageName), 0).isNotEmpty() }.getOrDefault(false)
        }
        return listOf(
            DiagLine.section("Gleo"),
            schemaLine,
            if (registered == Gleo.FUNCTIONS.size) DiagLine.ok("Gleo 수신기", "함수 action ${registered}개 등록됨")
            else DiagLine.fail("Gleo 수신기", "함수 action ${Gleo.FUNCTIONS.size}개 가운데 ${registered}개만 등록됨"),
            GleoBridge.lastCall?.let { DiagLine.ok("마지막 호출", "$it (모두 ${GleoBridge.calls}번)") }
                ?: DiagLine.alt("마지막 호출", "아직 없음(adb 로 부르는 법은 README)"),
            GleoBridge.lastResponse?.let { DiagLine.ok("마지막 응답", it) } ?: DiagLine.alt("마지막 응답", "아직 없음"),
            DiagLine.alt("Gleo 승인", "Gleo 검토 뒤에 음성으로 불려요. 그 전에는 adb 로 확인해요"),
        )
    }

    private fun appVersion(): String = runCatching { getApplication<Application>().let { it.packageManager.getPackageInfo(it.packageName, 0).versionName } }.getOrNull() ?: "?"
    private fun stamp(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())

    /** 수신기(VerifyReceiver)가 넘긴 adb 명령. 메인 스레드에서 온다. reply.finish 를 한 번 부른다 */
    private fun onAdb(what: String, value: String?, reply: AdbReply) {
        when (what) {
            in AdbCommand.VERIFY -> startVerify(what, reply)
            AdbCommand.DIAG -> startDiagFromAdb(reply)
            AdbCommand.STATE -> dumpState(reply)
            AdbCommand.SEATS -> adbSeats(value, reply)
            else -> reply.finish(AdbCommand.help(getApplication<Application>().packageName), AdbReply.CODE_REFUSED)
        }
    }

    /**
     * adb 로 부르는 제어 검증(실측용). 내외기, 풍량, 창문을 하나씩 "읽기 → 명령 → 확인 → 2초 유지 → 원복 → 확인"하고
     * 결과를 방송 결과, Logcat(Kkaeugi.Verify), verify_latest.txt(앱 내부와 앱 전용 외부 저장소)에 남긴다.
     * Pleos 가 없으면 모의 차량으로 같은 순서를 돈다.
     *
     * 수명(v0.7.4): 화면의 스코프가 아니라 앱 프로세스의 스코프(AppScope)에서 돈다. 방송 수신기가 끝나거나 화면이 닫혀도
     * 원복까지 끝낸다. 도는 동안 VerifyHoldService 로 프로세스를 붙든다.
     * 엔진이 환기를 실행하거나 묻는 중이면 시작하지 않는다(같은 값을 두 곳에서 바꾸게 된다)
     */
    private fun startVerify(what: String, reply: AdbReply) {
        val app = getApplication<Application>()
        if (verifyJob?.isActive == true) { reply.finish("제어 검증이 이미 도는 중이에요. 끝난 뒤 --es what result 로 결과를 읽어 주세요", AdbReply.CODE_REFUSED); return }
        if (engine.phaseName != "IDLE" || engine.isResting) {
            val text = "숨이 지금 제안이나 환기를 진행 중이라 제어 검증을 시작하지 않았어요(${engine.phaseName}). 끝난 뒤 다시 보내 주세요"
            Log.i(VerifyReceiver.TAG, text); reply.finish(text, AdbReply.CODE_REFUSED); return
        }
        val connected = vehicleSource.vehicle != null
        val controls: VehicleControls = if (connected) calls else mock.car
        val expected = ControlVerifier.expectedSeconds(what)
        val hold = VerifyHoldService.begin(app)
        val header = "숨 제어 검증 v${appVersion()} ${stamp()} what=$what ${if (connected) "Pleos 연결" else "모의 차량(Pleos 없음)"} 기어=${ai.kkaeugi.app.ui.gearLabel(_ui.value.gear)}" +
            if (hold == null) "" else " (화면이 뒤에 있어 유지 서비스를 못 띄움: $hold. 숨 화면을 앞에 둔 채 다시 돌리면 더 확실해요)"
        val seen = mutableListOf<String>()
        fun line(text: String) { Log.i(VerifyReceiver.TAG, text); appendVerify(text); seen += text }
        reply.interim = "$header\n아직 도는 중이에요(약 ${expected}초). 끝나면 Logcat ${VerifyReceiver.TAG} 와 파일에 남아요. 다시 읽기: ${AdbCommand.line(app.packageName, "--es what result")}"
        verifyJob = AppScope.scope.launch {
            try {
                line(header)
                val result = ControlVerifier(controls, windowInverted = { windowInverted }, onLine = { text -> line(text); reply.interim = (seen + "아직 도는 중이에요. 다시 읽기: --es what result").joinToString("\n") }).run(what)
                if (result == null) { reply.finish("모르는 what=$what", AdbReply.CODE_REFUSED); return@launch }
                val summary = ControlVerifier.summary(result)
                val report = ControlVerifier.report(header, result)
                val saved = ReportFiles.write(app, ControlVerifier.FILE_NAME, report)
                line("$summary. ${saved.text()}")
                appendLog("제어 검증(adb, $what): $summary")
                // 제어 직후의 실제 풍량을 엔진에 바로 알린다
                if (connected) vehicleSource.refreshFan()
                reply.finish(report + saved.text())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val text = "제어 검증 중 예외 ${e.javaClass.simpleName}: ${e.message}"
                line(text)
                ReportFiles.write(app, ControlVerifier.FILE_NAME, (seen + text).joinToString("\n"))
                reply.finish((seen + text).joinToString("\n"), AdbReply.CODE_REFUSED)
            } finally {
                VerifyHoldService.end(app)
            }
        }
    }

    /** adb 로 부른 자가 진단. 화면의 [자가 진단]과 같은 것을 돌리고 끝나면 결과를 돌려준다. 주행 중에는 시작하지 않는다 */
    private fun startDiagFromAdb(reply: AdbReply) {
        val app = getApplication<Application>()
        if (diagJob?.isActive == true) { reply.finish("자가 진단이 이미 도는 중이에요. 끝난 뒤 --es what diagresult 로 읽어 주세요", AdbReply.CODE_REFUSED); return }
        if (_ui.value.tripState == TripState.DRIVING) {
            reply.finish("주행 중에는 자가 진단을 시작하지 않아요(주행 화면을 가려요). 기어 P 에서 다시 보내거나 --es what state 로 지금 값을 읽어 주세요", AdbReply.CODE_REFUSED); return
        }
        reply.interim = "자가 진단을 시작했어요. 끝나면 Logcat ${DiagReport.TAG} 와 ${DiagReport.FILE_NAME} 에 남아요. 다시 읽기: ${AdbCommand.line(app.packageName, "--es what diagresult")}"
        Log.i(VerifyReceiver.TAG, "[adb 자가 진단] 시작")
        runDiagnosis { done, where ->
            Log.i(VerifyReceiver.TAG, "[adb 자가 진단] ${done.summary}. $where")
            reply.finish(done.report + "\n" + where)
        }
    }

    private fun <T> Outcome<T>.show(f: (T) -> String = { it.toString() }): String = when (this) {
        is Outcome.Ok -> f(value)
        is Outcome.Fail -> "못 읽음(${error.take(90)})"
    }

    /**
     * adb 로 부른 상태 덤프(읽기 전용). 기어, 내기순환, 자동 내기순환, 공조 전원, 풍량, 설정 온도, 창문 네 곳, 좌석 다섯 자리, 띠 다섯 자리,
     * 와이퍼, 하향등, 조향각, 내비 남은 시간을 지금 한 번씩 읽어 적는다. 그 아래에 앱이 지금 쓰고 있는 값(인원, 자리, 추정치)을 붙인다.
     * 읽기는 한꺼번에 보내고 항목마다 STATE_READ_MS 안에 답이 없으면 못 읽음으로 적는다. Pleos 가 없으면 모의 차량의 값을 적는다
     */
    private fun dumpState(reply: AdbReply) {
        val app = getApplication<Application>()
        val connected = vehicleSource.vehicle != null
        val header = "숨 상태 v${appVersion()} ${stamp()} ${if (connected) "Pleos 연결" else "모의 차량(Pleos 없음)"}"
        AppScope.scope.launch {
            val rows = runCatching { if (connected) readVehicleState() else mockStateRows() }
                .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; listOf("읽기" to "예외 ${e.javaClass.simpleName}: ${e.message}") }
            val text = StateReport.format(header, rows + appStateRows())
            text.lines().forEach { Log.i(VerifyReceiver.TAG, it) }
            val saved = ReportFiles.write(app, AdbCommand.STATE_FILE, text)
            reply.finish(text + "\n" + saved.text())
        }
    }

    private suspend fun readVehicleState(): List<Pair<String, String>> = kotlinx.coroutines.coroutineScope {
        val jobs = mutableListOf<Pair<String, kotlinx.coroutines.Deferred<String>>>()
        fun row(name: String, block: suspend () -> String) {
            jobs += name to async {
                try { kotlinx.coroutines.withTimeoutOrNull(STATE_READ_MS) { block() } ?: "못 읽음(응답 없음 ${STATE_READ_MS / 1000}초)" }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { "못 읽음(${PleosCalls.describe(e).take(90)})" }
            }
        }
        row("기어") { calls.getGear().show { it.name } }
        row("내기순환") { calls.getRecirculation().show { if (it) "켜짐(내기)" else "꺼짐(외기)" } }
        row("자동 내기순환") { calls.getAutoRecirculation().show { if (it) "켜짐" else "꺼짐" } }
        row("공조 전원") { calls.getHvacPower().show { if (it) "켜짐" else "꺼짐" } }
        row("풍량") { val max = calls.fanMaxText(); calls.getFanSpeed().show { "$it / 최대 $max" } }
        row("설정 온도") { calls.getTemperature().show { "${it}C" } }
        for (w in PleosCalls.SIDE_WINDOWS) row("창문 ${w.name}") { calls.getWindowAt(w).show { "raw $it" } }
        for (seat in PleosCalls.OCCUPANT_SEATS) row("좌석 ${seat.name}") { calls.getSeatOccupancy(seat).show { if (it) "있음" else "없음" } }
        for (seat in PleosCalls.OCCUPANT_SEATS) row("띠 ${seat.name}") { calls.getSeatBelt(seat).show { if (it) "체결" else "풀림" } }
        row("와이퍼") { calls.getWiper().show { it.name } }
        row("하향등") { calls.getLowBeam().show { it.name } }
        row("조향각") { calls.getSteeringAngle().show { "${it}도" } }
        row("내비 남은 시간") {
            val asked = MonoClock.now()
            navSource.requestDestination()
            val until = asked + STATE_NAV_WAIT_MS
            while (navSource.lastDestinationAt < asked && MonoClock.now() < until) delay(100)
            val raw = navSource.lastDestinationRaw
            when {
                raw == null -> "목적지 없음 또는 응답 없음"
                else -> EtaJudge.describe(raw.first, raw.second, navSource.etaMinutes) + if (navSource.lastDestinationAt >= asked) "" else " (이전 값)"
            }
        }
        jobs.map { (name, d) -> name to d.await() }
    }

    /** Pleos 가 없을 때: 모의 차량과 데모 콘솔이 들고 있는 값 */
    private fun mockStateRows(): List<Pair<String, String>> {
        val st = _ui.value
        val car = mock.car
        return listOf(
            "기어" to ai.kkaeugi.app.ui.gearLabel(st.gear),
            "내기순환" to if (car.recirculating) "켜짐(내기)" else "꺼짐(외기)",
            "자동 내기순환" to if (mock.autoClimateOn) "켜짐" else "꺼짐",
            "공조 전원" to if (car.hvacPower) "켜짐" else "꺼짐",
            "풍량" to "${car.fanLevel} / 최대 ${car.fanMax}",
            "설정 온도" to "${car.temperatureC}C",
            "창문" to "운전석 raw ${car.windowPosition} (열림 ${car.windowPercent}%). 모의 차량은 운전석 창문만 있어요",
            "좌석" to (st.seats?.let { AdbCommand.seatsText(it) + " (운전석, 조수석, 뒷줄 왼쪽, 가운데, 오른쪽)" } ?: "자리별 값 없음(인원 ${st.occupants}명)"),
            "띠" to "모의 차량에는 없음",
            "와이퍼" to if (st.raining) "켜짐" else "꺼짐",
            "하향등" to if (st.night) "켜짐" else "꺼짐",
            "조향각" to "모의 차량에는 없음",
            "내비 남은 시간" to (mock.etaMinutes?.let { "${it}분(데모 콘솔 값)" } ?: "목적지 없음"),
        )
    }

    /** 앱이 지금 쓰고 있는 값(호출 없이 아는 것). 차에서 읽은 값과 앱이 쓰는 값이 다를 때 여기서 드러난다 */
    private fun appStateRows(): List<Pair<String, String>> {
        val st = _ui.value
        return listOf(
            "앱 상태" to "${st.tripState} / 엔진 ${engine.phaseName}${if (engine.isResting) " / 쉼" else ""} / 숨 ${if (st.autoIntervene) "켬" else "끔"} / 맡기기 ${if (st.trustDelegated) "켬" else "끔"}",
            "앱이 쓰는 인원" to "${st.occupants}명 (${seatInputLabel(st)})",
            "앱이 쓰는 자리" to (st.seats?.let { AdbCommand.seatsText(it) } ?: "자리별 값 없음"),
            "앱이 본 내외기와 풍량" to "${if (st.recirculating) "내기" else "외기"}, 풍량 ${st.fanLevel ?: "모름"}/${st.fanMax}, 창문 ${st.windowPercent}%",
            "CO2 추정" to (st.air?.let { "약 ${it.ppm}ppm (한도 ${st.co2LimitPpm})" } ?: "주행 중이 아니라 없음"),
        )
    }

    /** 인원을 어디서 받는가 */
    private fun seatInputLabel(st: UiState): String = if (st.seatSensorLive) "좌석 센서" else "직접 입력"

    /** 좌석 직접 입력(adb). v0.7.4 의 4번 작업에서 채운다 */
    private fun adbSeats(value: String?, reply: AdbReply) {
        reply.finish("seats 명령은 아직 준비 중이에요", AdbReply.CODE_REFUSED)
    }

    // ---------- 자리별 프로필(v0.7.3). 정차 중에만 고른다. 기기 안에만 저장한다 ----------

    /** 좌석 지도의 자리를 눌렀다. 주행 중에는 열지 않는다 */
    fun openProfileSheet(seat: Int) {
        if (_ui.value.tripState == TripState.DRIVING || seat !in 0 until Seats.COUNT) return
        _ui.update { it.copy(profileSheetSeat = seat) }
    }

    fun closeProfileSheet() = _ui.update { it.copy(profileSheetSeat = null) }

    /** 한 자리의 프로필을 고른다. 고르면 시트를 닫는다. 시연 시나리오가 도는 동안에는 저장하지 않는다 */
    fun setSeatProfile(seat: Int, profile: SeatProfile) {
        if (_ui.value.tripState == TripState.DRIVING || seat !in 0 until Seats.COUNT) return
        applySeatProfiles(SeatProfileCodec.with(_ui.value.seatProfiles, seat, profile))
        _ui.update { it.copy(profileSheetSeat = null) }
        appendLog("자리 설정: ${Seats.name(seat)} → ${profile.label}")
    }

    /** [전부 지우기]: 다섯 자리를 모두 기본으로 */
    fun clearSeatProfiles() {
        if (_ui.value.tripState == TripState.DRIVING) return
        applySeatProfiles(SeatProfileCodec.NONE)
        appendLog("자리 설정: 전부 지움")
    }

    private fun applySeatProfiles(profiles: List<SeatProfile>, persist: Boolean = !demoIsolation.active) {
        if (persist) prefs.edit().putString(PREF_SEAT_PROFILES, SeatProfileCodec.encode(profiles)).apply()
        bus.emit(SeatProfilesChanged(MonoClock.now(), profiles))
    }

    /** 자가 진단의 "프로필과 앉은 시간" 절. 호출 없이 앱이 아는 값만 적는다 */
    private fun seatDiagLines(): List<DiagLine> {
        val st = _ui.value
        val care = engine.care(MonoClock.now())
        val chosen = st.seatProfiles.mapIndexedNotNull { i, p -> if (p == SeatProfile.DEFAULT) null else "${Seats.name(i)} ${p.label}" }
        return listOf(
            DiagLine.section("프로필과 앉은 시간"),
            DiagLine.ok("자리 설정", if (chosen.isEmpty()) "전부 기본(기기 안에만 저장)" else chosen.joinToString(", ") + " (기기 안에만 저장)"),
            if (st.seats != null) DiagLine.ok("자리별 값", st.seats.joinToString(" ") { when (it) { true -> "O"; false -> "X"; null -> "?" } } + " (운전석부터 뒷줄 오른쪽까지)")
            else DiagLine.alt("자리별 값", "없음. 도착 카드는 차 전체 한 줄로 적어요"),
            if (st.doorSignalSeen) DiagLine.ok("문 신호", "받음. 기어 P ${engine.config.sitParkResetMinutes}분 이상에 문이 열렸으면 일어선 것으로 봐요")
            else DiagLine.alt("문 신호", "받은 적 없음. 기어 P ${engine.config.sitParkResetMinutes}분 이상이면 일어선 것으로 봐요"),
            DiagLine.ok("살피는 것", ai.kkaeugi.app.ui.SeatText.careLine(care, engine.config.demoFast)),
            DiagLine.ok("앉은 시간", ai.kkaeugi.app.ui.SeatText.sitLine(care, engine.config.demoFast) + " (좌석과 기어 신호로 센 값)"),
        )
    }

    /**
     * 한 번에 자가 진단. 읽기 전용 호출만 순서대로 돌려 한 화면 보고서를 만든다(차 값은 바꾸지 않는다).
     * 같은 줄이 Logcat(Kkaeugi.Diag)과 diag_latest.txt(앱 내부와 앱 전용 외부 저장소)에도 남는다. 주행 중에는 시작하지 않는다.
     * onDone: 끝났을 때(보고서, 파일이 남은 곳 한 줄). adb 로 부른 쪽이 결과를 돌려주는 데 쓴다
     */
    fun runDiagnosis(onDone: ((DiagUi, String) -> Unit)? = null) {
        if (_ui.value.tripState == TripState.DRIVING || diagJob?.isActive == true) return
        val header = DiagReport.header(appVersion(), stamp())
        Log.i(DiagReport.TAG, header)
        _ui.update { it.copy(diag = DiagUi(header, running = true)) }
        diagJob = viewModelScope.launch {
            val probed = runCatching { diagnosis.run { progress -> _ui.update { st -> st.copy(diag = st.diag?.copy(lines = progress)) } } }
                .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; (_ui.value.diag?.lines ?: emptyList()) + DiagLine.fail("자가 진단", "중단됨: ${e.message}") }
            // 호출 없이 앱이 아는 값으로 적는 절(v0.7.3): 프로필과 앉은 시간, Gleo
            val lines = probed + seatDiagLines() + gleoDiagLines()
            val done = DiagUi(header, lines, running = false)
            Log.i(DiagReport.TAG, done.summary)
            val saved = diagnosis.save(done.report)
            // 진단이 HVAC 영역을 다시 찾았다. 바뀐 영역이 있으면 리스너를 옮긴다
            vehicleSource.resyncHvacListeners()
            _ui.update { it.copy(diag = done.copy(saved = saved.internal || saved.externalPath != null)) }
            appendLog("자가 진단: ${done.summary}")
            onDone?.invoke(done, saved.text())
        }
    }

    fun closeDiagnosis() { diagJob?.cancel(); diagJob = null; _ui.update { it.copy(diag = null) } }

    /** 개발자 설정: 화면 밖에서도 유지(실험). 여기서는 값만 바꾼다. 서비스는 MainActivity 가 화면이 보이는 동안 시작하고 멈춘다 */
    fun setKeepAlive(on: Boolean) {
        prefs.edit().putBoolean(PREF_KEEP_ALIVE, on).apply()
        _ui.update { it.copy(keepAlive = on, keepAliveError = if (on) null else it.keepAliveError) }
        appendLog("설정 → 화면 밖에서도 유지 ${if (on) "ON" else "OFF"}")
    }

    /** 화면 밖 유지를 시작하지 못했다. 토글을 끄고 사유를 남긴다(자가 진단에 보인다) */
    fun onKeepAliveFailed(reason: String) {
        prefs.edit().putBoolean(PREF_KEEP_ALIVE, false).apply()
        _ui.update { it.copy(keepAlive = false, keepAliveError = reason) }
        appendLog("화면 밖 유지 실패, 토글을 껐어요: $reason")
    }

    /** 앱 화면이 보이는가(Activity onStart/onStop). 안 보이는 동안 넘어간 제안은 엔진이 거절로 세지 않는다 */
    fun onAppVisibility(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        if (visible) hiddenExpiries = 0
        bus.emit(AppVisibility(MonoClock.now(), visible))
    }

    /** 한마디. 화면(마지막 안내)에는 그대로, 읽을 때는 괄호를 뺀다 */
    private fun say(text: String) {
        speaker.speak(speechText(text))
        appendLog("말: $text")
        _ui.update { it.copy(lastSaid = text) }
    }

    /** 도착 카드 "확인". 이미 다시 달리는 중(DRIVING)이면 세션을 건드리지 않고 카드만 닫는다 */
    fun dismissArrival() {
        if (engine.tripState == TripState.DRIVING) {
            _ui.update { it.copy(arrival = null) }
            return
        }
        engine.reset()
        clearSession()
        tripStartedMs = null; odometerMinutes = 0
        _ui.update { it.copy(arrival = null, tripState = TripState.IDLE, lastSaid = null, deferred = null, demoStep = null, running = null, proposal = null, announce = null, tripMinutes = 0) }
    }

    fun setAutoIntervene(on: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO, on).apply()
        _ui.update { it.copy(autoIntervene = on) }
        handle(engine.setEnabled(on, MonoClock.now()))
        appendLog("설정 → 숨 ${if (on) "ON" else "OFF"}")
    }
    fun toggleVerify() = _ui.update { it.copy(showVerify = !it.showVerify) }
    fun toggleDemoConsole() = _ui.update { it.copy(showDemoConsole = !it.showDemoConsole) }
    fun clearVerifyLog() = _ui.update { it.copy(verifyLog = emptyList()) }

    /**
     * demoFast 토글. 엔진을 새로 만들지 않고 updateConfig 로 바꾼다(내기순환/와이퍼/하향등 상태 유지).
     * persist=true(설정 토글)일 때만 SharedPreferences 에 저장한다. 시연 시나리오가 켠 데모 속도는 저장하지 않아
     * 앱을 다시 켜면 실제(분) 임계로 돌아온다.
     */
    fun setDemoFast(on: Boolean, persist: Boolean = true) {
        if (persist) prefs.edit().putBoolean(PREF_DEMO_FAST, on).apply()
        _ui.update { it.copy(demoFast = on) }
        applyConfig()
        appendLog("설정 → ${if (on) "데모(분→초)" else "실제(분)"}")
    }

    /** 증거 모드. P(비주행)에서만 켜고 끈다. 저장됨 */
    fun setEvidenceMode(on: Boolean) {
        if (_ui.value.tripState == TripState.DRIVING) return
        prefs.edit().putBoolean(PREF_EVIDENCE, on).apply()
        _ui.update { it.copy(evidenceMode = on) }
    }

    /**
     * 창문 방향 반전(저장됨). 닫힌 창문의 위치가 max 로 읽히는 차에서 켠다(검증 패널 "창문 위치 읽기"로 확인).
     * 창문을 여는 쪽(액추에이터, 검증 버튼)과 열림 퍼센트를 읽는 쪽(창문 신호)이 함께 따른다. P(비주행)에서만 바꾼다
     */
    fun setWindowInverted(on: Boolean) {
        if (_ui.value.tripState == TripState.DRIVING) return
        windowInverted = on
        prefs.edit().putBoolean(PREF_WINDOW_INVERT, on).apply()
        vehicleSource.windowInverted = on
        if (vehicleSource.vehicle == null) mock.car.republishWindow()
        _ui.update { it.copy(windowInverted = on) }
        appendLog("설정 → 창문 방향 반전 ${if (on) "ON" else "OFF"}")
    }

    /** 시연 시나리오. 주행 중이면 시작하지 않는다 */
    fun runDemo() {
        if (engine.tripState == TripState.DRIVING) {
            val msg = "주행 중에는 시나리오를 시작할 수 없어요. 기어 P 로 바꾼 뒤 다시 누르세요"
            _ui.update { it.copy(demoStep = msg) }
            appendLog(msg)
            return
        }
        // 시나리오가 건드리는 실제 상태를 잡아 둔다. 끝나거나 중단되면 되돌리고, 그동안은 저장하지 않는다
        demoIsolation.begin(DemoSnapshot(engine.trust, _ui.value.manualOccupants, mock.autoClimateOn, mock.etaMinutes, _ui.value.seatProfiles))
        if (!_ui.value.demoFast) setDemoFast(true, persist = false)
        val c = config
        mock.runDemoScript(
            ventilateSec = c.ventilateSeconds, drowsySec = c.drowsySeconds, restSec = c.restMinutes,
            // 맡기기를 켜 둔 채 돌리면 첫 제안이 예고로 나온다. 시나리오는 그것도 제안처럼 본다
            view = {
                _ui.value.let {
                    DemoScenario.View(
                        proposalOpen = it.proposal != null || it.announce != null, countdownFrom = it.proposal?.countdownFrom ?: it.announce?.countdownFrom,
                        running = it.running != null, restRemainingSec = it.rest?.remainingSec,
                    )
                }
            },
            onRestWithin = { tracker.mockWithin() },
            onAnswerYes = { appendLog("시나리오: 답이 없어 대신 \"응\""); answer(true, via = "시나리오") },
            onFinished = { restoreAfterDemo() },
        ) { step -> _ui.update { it.copy(demoStep = step) } }
    }

    /** 시연 시나리오가 끝났거나 중단됐다. 맡기기 상태, 수동 인원, 데모 콘솔의 차 자동 공조와 도착 예정을 시작 전 값으로 되돌린다 */
    private fun restoreAfterDemo() {
        val before = demoIsolation.end() ?: return
        handle(engine.setTrust(before.trust, MonoClock.now()))
        _ui.update {
            it.copy(
                trustDelegated = before.trust.delegated,
                // 시나리오 주행에서 나온 맡기기 질문은 실제 집계가 아니라 카드에서 뺀다
                arrival = it.arrival?.copy(trustOffer = null),
            )
        }
        if (!_ui.value.seatSensorLive) { mock.occupants(before.manualOccupants); mock.seats(null) }
        // 시나리오가 넣은 가족의 자리 설정을 걷고 저장해 둔 설정으로 되돌린다
        applySeatProfiles(SeatProfileCodec.normalize(before.profiles), persist = false)
        mock.autoClimate(before.carAuto)
        mock.eta(before.etaMinutes)
        // 시나리오가 넣은 바깥 미세먼지와 시각을 걷고 설정대로 다시 알린다
        mock.clock(null)
        refreshOutdoorAndClock(MonoClock.now())
        appendLog("시나리오 뒤 원래 상태로: 맡기기 ${if (before.trust.delegated) "켜짐" else "꺼짐"}, ${before.manualOccupants}명")
    }

    /** 데모 콘솔: 핸들 급보정 패턴 3회. 엔진이 "오래 무조작 뒤 급보정"으로 세는 꼴 그대로 넣는다 */
    fun demoSteering() {
        if (!engine.config.steeringAssist) {
            val msg = "핸들 보조 신호가 꺼져 있어요. 개발자 설정에서 켠 뒤 다시 누르세요"
            _ui.update { it.copy(demoStep = msg) }
            appendLog(msg)
            return
        }
        val quiet = engine.config.steerQuietSec
        appendLog("데모: 핸들 급보정 3회 시작(무조작 ${quiet}초 뒤 12도)")
        mock.steeringJerks(times = 3, quietSec = quiet) { step -> _ui.update { it.copy(demoStep = step) } }
    }

    fun stopDemo() { mock.cancelScript(); restoreAfterDemo(); _ui.update { it.copy(demoStep = "시나리오 중단") } }

    val staleAirLimit: Int get() = engine.config.staleAirMinutes

    /** Pleos 런타임 퍼미션 요청 결과. 거부돼도 앱은 돈다 */
    fun onPleosPermissions(result: Map<String, Boolean>) {
        for ((p, granted) in result) appendLog("퍼미션 ${DiagReport.shortPermission(p)}: ${if (granted) "허용" else "거부"}")
    }

    fun onMicPermission(granted: Boolean) {
        _ui.update { it.copy(micGranted = granted) }
        appendLog(if (granted) "마이크 권한 있음" else "마이크 권한 없음: 음성 답 대신 화면 버튼으로만 답해요")
    }

    // ---------- 프로세스가 정리돼도 남기는 것: 세션 시작 시각, 쉼 모드 ----------
    // 엔진 상태 전체를 되살리지는 못한다(엔진에 복원 API 가 없다). 트립 시간과 쉼 카운트다운만 이어 보여 준다.

    private fun saveSessionStart() = prefs.edit().putLong(PREF_SESSION_START_WALL, System.currentTimeMillis()).apply()
    private fun clearSession() = prefs.edit().remove(PREF_SESSION_START_WALL).apply()

    /** 저장된 세션이 SessionRestore.KEEP_MS(30분) 안이면 그때부터 흐른 분. 없거나 오래됐으면 0(새 주행) */
    private fun restoredSessionMinutes(): Int =
        SessionRestore.carriedMinutes(prefs.getLong(PREF_SESSION_START_WALL, 0L), System.currentTimeMillis())

    private fun saveRest(stopName: String, target: Int, totalSec: Int, demo: Boolean) = prefs.edit()
        .putString(PREF_REST_STOP, stopName).putInt(PREF_REST_TARGET, target).putBoolean(PREF_REST_DEMO, demo)
        .putLong(PREF_REST_END_WALL, System.currentTimeMillis() + totalSec * 1000L).apply()
    private fun clearRest() = prefs.edit().remove(PREF_REST_STOP).remove(PREF_REST_TARGET).remove(PREF_REST_DEMO).remove(PREF_REST_END_WALL).apply()

    /** 앱 시작 시: 쉬는 중에 프로세스가 정리됐으면 남은 시간으로 쉼 카드를 다시 띄운다. 기어 D 가 오면 닫힌다 */
    private fun restoreRest() {
        val name = prefs.getString(PREF_REST_STOP, null) ?: return
        val remaining = ((prefs.getLong(PREF_REST_END_WALL, 0L) - System.currentTimeMillis()) / 1000L).toInt()
        if (remaining <= 0) { clearRest(); return }
        _ui.update { it.copy(rest = RestUi(name, prefs.getInt(PREF_REST_TARGET, 15), remaining, prefs.getBoolean(PREF_REST_DEMO, false))) }
        appendLog("쉼 모드 복원: $name, ${remaining}초 남음")
    }

    /** 복원한 쉼 카드는 엔진의 RestTick 이 없으니 벽시계로 직접 줄인다 */
    private fun tickRestoredRest() {
        if (engine.isResting || _ui.value.rest == null) return
        val remaining = ((prefs.getLong(PREF_REST_END_WALL, 0L) - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
        _ui.update { st -> st.copy(rest = st.rest?.copy(remainingSec = remaining)) }
    }

    private fun label(kind: TriggerKind) = when (kind) { TriggerKind.STALE_AIR -> "환기"; TriggerKind.DROWSY -> "졸음"; TriggerKind.WALK_BREAK -> "걷기 쉼" }

    private fun appendLog(line: String) {
        _ui.update { it.copy(log = (it.log + line).takeLast(60)) }
        Log.d("Kkaeugi", line)
    }
    private fun appendVerify(line: String) = _ui.update { it.copy(verifyLog = (it.verifyLog + line).takeLast(60)) }

    override fun onCleared() {
        if (GleoBridge.handler === gleoHandler) GleoBridge.handler = null
        if (GleoBridge.adb === adbHandler) GleoBridge.adb = null
        stopListening()
        tracker.stop()
        navSource.stop(); mock.stop(); speaker.release(); listener.release()
        // 제어 검증이 도는 중이면 원복이 끝날 때까지 차량 연결을 놓지 않는다(검증은 앱 프로세스의 스코프에서 돈다).
        // 환기나 졸음 개입이 유지 중일 때 화면이 닫히면(되돌릴 값이 남아 있으면) 닫기 전에 원복한다. 화면의 스코프는 이미 끝났으므로 앱 프로세스의 스코프에서 한다
        val verifying = verifyJob?.takeIf { it.isActive }
        val pendingRevert = actuator.hasSnapshots
        if (verifying == null && !pendingRevert) { vehicleSource.stop(); return }
        AppScope.scope.launch {
            verifying?.let { kotlinx.coroutines.withTimeoutOrNull(VERIFY_RELEASE_WAIT_MS) { it.join() } }
            if (pendingRevert) kotlinx.coroutines.withTimeoutOrNull(REVERT_ON_CLOSE_MS) {
                actuator.run(listOf(Action.Revert)) { r -> Log.i(VehicleActuator.TAG, "화면이 닫혀 원복: ${if (r.ok) "완료" else "실패"} ${r.detail ?: ""}") }
            }
            vehicleSource.stop()
        }
    }

    companion object {
        const val PREF_AUTO = "autoIntervene"
        const val PREF_DEMO_FAST = "demoFast"
        const val PREF_EVIDENCE = "evidenceMode"
        const val PREF_WINDOW_INVERT = "windowInverted"
        const val PREF_TRUST = "trustState"
        const val PREF_MANUAL_OCCUPANTS = "manualOccupants"
        const val PREF_KEEP_ALIVE = "keepAlive"
        const val PREF_PM_ENABLED = "pmEnabled"
        const val PREF_PM_CHOICE = "pmChoice"
        const val PREF_SEAT_PROFILES = "seatProfiles"
        /** 바깥 미세먼지와 지금 시각을 엔진에 다시 알리는 간격 */
        const val OUTDOOR_REFRESH_MS = 60_000L
        /** 주행 중 목적지 남은 시간을 다시 묻는 간격 */
        const val ETA_REQUEST_MS = 60_000L
        const val ETA_REQUEST_DEMO_MS = 5_000L
        const val PREF_STEERING_ASSIST = "steeringAssist"
        const val PREF_ALLOW_WINDOW = "allowWindow"
        const val PREF_SESSION_START_WALL = "sessionStartWall"
        const val PREF_REST_STOP = "restStop"
        const val PREF_REST_TARGET = "restTarget"
        const val PREF_REST_DEMO = "restDemo"
        const val PREF_REST_END_WALL = "restEndWall"
        /** 상태 덤프의 읽기 한 번, 내비 답을 기다리는 시간. 수신기가 붙들 수 있는 시간 안에 끝나야 한다 */
        const val STATE_READ_MS = 3_000L
        const val STATE_NAV_WAIT_MS = 1_500L
        /** 화면이 닫힐 때 도는 중인 제어 검증을 기다려 주는 시간 */
        const val VERIFY_RELEASE_WAIT_MS = 60_000L
        /** 화면이 닫힐 때 남은 원복에 주는 시간 */
        const val REVERT_ON_CLOSE_MS = 20_000L
        /** 시연용 설정. DemoSettings.config 참고 */
        fun demoConfig() = DemoSettings.config()
    }
}

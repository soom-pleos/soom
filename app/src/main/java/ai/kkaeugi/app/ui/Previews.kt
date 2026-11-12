package ai.kkaeugi.app.ui

import ai.kkaeugi.app.AirUi
import ai.kkaeugi.app.AnnounceUi
import ai.kkaeugi.app.DiagUi
import ai.kkaeugi.app.Proposal
import ai.kkaeugi.app.RestUi
import ai.kkaeugi.app.RunningBar
import ai.kkaeugi.app.UiState
import ai.kkaeugi.app.vehicle.DiagLine
import ai.kkaeugi.app.vehicle.DiagReport
import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.ActionResult
import ai.kkaeugi.engine.ArrivalSummary
import ai.kkaeugi.engine.BreathDesign
import ai.kkaeugi.engine.FanSpeedChanged
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.GearChanged
import ai.kkaeugi.engine.LocalClock
import ai.kkaeugi.engine.OccupantsChanged
import ai.kkaeugi.engine.OutdoorAir
import ai.kkaeugi.engine.RecirculationChanged
import ai.kkaeugi.engine.RestStopCandidate
import ai.kkaeugi.engine.RestStopProximity
import ai.kkaeugi.engine.SeatCare
import ai.kkaeugi.engine.SeatOccupancyChanged
import ai.kkaeugi.engine.SeatProfile
import ai.kkaeugi.engine.SeatProfilesChanged
import ai.kkaeugi.engine.SeatStats
import ai.kkaeugi.engine.Seats
import ai.kkaeugi.engine.Signal
import ai.kkaeugi.engine.SpeechFinished
import ai.kkaeugi.engine.UserAnswer
import ai.kkaeugi.engine.WakeConfig
import ai.kkaeugi.engine.WakeEngine
import ai.kkaeugi.engine.WakeEvent
import ai.kkaeugi.engine.RestStopInfo
import ai.kkaeugi.engine.TriggerKind
import ai.kkaeugi.engine.TripState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * 숨 설계 표본을 만드는 작은 주행기. 손으로 적은 숫자가 아니라 실제 엔진(기본 설정)을 1초씩 굴려서 얻는다.
 * 차는 풍량 3/8, 내기순환으로 출발한다. 제안이 오면 3초 뒤 다 읽은 것으로 하고 answer 가 있으면 6초 뒤 그렇게 답한다.
 * Execute 가 오면 모의 차량처럼 내외기와 풍량을 바꾸고 결과를 돌려준다
 */
internal class BreathSampleDrive(
    occupants: Int, pm25: Double?, minuteOfDay: Int, private val answer: Boolean? = true,
    /** v0.7.3: 자리별 값과 자리 설정, 엔진 설정(걷기 쉼을 켠 표본), 전방 쉼터 후보. 주지 않으면 v0.7.2 표본과 똑같이 굴러간다 */
    seats: List<Boolean?>? = null, profiles: List<SeatProfile> = emptyList(), config: WakeConfig = WakeConfig(), candidate: RestStopInfo? = null,
    /** 바깥 미세먼지 값을 10분마다 다시 넣는다(앱은 1분마다 다시 알린다). 한 시간이 넘는 표본에서 값이 묵지 않게 한다 */
    private val refreshPm: Double? = null,
) {
    val engine = WakeEngine(config)
    var now = 0L
        private set
    var lastAir: WakeEvent.AirEstimate? = null
        private set
    var lastProposal: String? = null
        private set
    var ventilations = 0
        private set
    var arrival: ArrivalSummary? = null
        private set
    /** 첫 환기를 실행한 시각. 아직이면 null */
    var firstVentAt: Long? = null
        private set
    private var speechAt: Long? = null
    private var answerAt: Long? = null
    /** 차의 풍량 단(출발 때 3/8). 환기는 엔진이 낸 만큼(Ventilate.fanDelta) 바꾸고 원복은 3 으로 돌린다 */
    private var fan = 3

    init {
        send(FanSpeedChanged(now, 3, 8)); send(OccupantsChanged(now, occupants)); send(RecirculationChanged(now, true))
        send(OutdoorAir(now, pm25, "Open-Meteo(CAMS 격자 모델값)")); send(LocalClock(now, minuteOfDay))
        if (seats != null) send(SeatOccupancyChanged(now, seats))
        if (profiles.isNotEmpty()) send(SeatProfilesChanged(now, profiles))
        if (candidate != null) send(RestStopCandidate(now, candidate))
        send(GearChanged(now, Gear.DRIVE))
    }

    private fun send(s: Signal) = handle(engine.accept(s))

    /** 표본 주행 도중에 신호를 넣는다(중간에 탄 사람, 쉼터 도착, 기어). 시각은 지금 시각으로 맞춰 넣는다 */
    fun signal(make: (Long) -> Signal): BreathSampleDrive { send(make(now)); return this }

    private fun handle(events: List<WakeEvent>) {
        for (e in events) when (e) {
            is WakeEvent.AirEstimate -> lastAir = e
            is WakeEvent.Propose, is WakeEvent.Announce -> {
                lastProposal = (e as? WakeEvent.Propose)?.text ?: (e as WakeEvent.Announce).text
                speechAt = now + 3_000
                if (answer != null) answerAt = now + 6_000
            }
            is WakeEvent.Execute -> {
                if (e.actions == listOf<Action>(Action.Revert)) {
                    fan = 3
                    send(RecirculationChanged(now, true)); send(FanSpeedChanged(now, fan, 8)); send(ActionResult(now, Action.Revert, ok = true))
                } else for (a in e.actions) {
                    if (a is Action.Ventilate) {
                        ventilations++
                        if (firstVentAt == null) firstVentAt = now
                        // 풍량은 엔진이 낸 만큼 바꾼다: 보통은 한 단 올리고, 약한 바람으로 계속 들이기(v0.7.4)면 내린다
                        fan = (fan + a.fanDelta).coerceIn(1, 8)
                        send(RecirculationChanged(now, false)); send(FanSpeedChanged(now, fan, 8))
                    }
                    send(ActionResult(now, a, ok = true))
                }
            }
            is WakeEvent.ArrivalCard -> arrival = e.card
            else -> {}
        }
    }

    /** stop 이 참이 되거나 maxSeconds 가 될 때까지 1초씩 달린다 */
    fun run(maxSeconds: Int, stop: (BreathSampleDrive) -> Boolean = { false }): BreathSampleDrive {
        repeat(maxSeconds) {
            if (stop(this)) return this
            now += 1_000
            if (refreshPm != null && now % 600_000L == 0L) send(OutdoorAir(now, refreshPm, "Open-Meteo(CAMS 격자 모델값)"))
            handle(engine.tick(now))
            speechAt?.let { if (now >= it) { speechAt = null; send(SpeechFinished(now)) } }
            answerAt?.let { if (now >= it) { answerAt = null; send(UserAnswer(now, answer!!)) } }
        }
        return this
    }

    fun park(): ArrivalSummary { send(GearChanged(now, Gear.PARK)); return arrival!! }

    fun design(): BreathDesign = engine.breath(now)

    fun airUi(falling: Boolean = false): AirUi = lastAir!!.let { AirUi(it.ppm, it.occupants, it.minutesToLimit, it.ventilating, falling) }
}

/**
 * 화면 상태 표본. Android Studio 프리뷰와 JVM 스크린샷 테스트(ScreenshotTest)가 같이 쓴다.
 * 시각은 단조 시계 기준 nowMs = 10시간 지점으로 잡고, 벽시계 값(lastVentilationWallMs)은 KST 21:42 로 둔다.
 *
 * 숫자는 엔진 기본 설정의 숨 모델로 계산한 값이다(PreviewStatesTest 가 CabinAirModel 로 다시 계산해 맞는지 본다).
 * 두 가지 주행을 따라간다.
 *  - 가족 4명: 출발해서 내기순환 2.7분에 추정 1,646ppm(01, 08 오른쪽) → 3.7분에 2,000ppm, "바꿔 둘까요?"(02)
 *    → 동의하고 바깥 공기로 유지(풍량 4/8 에서 약 1,000ppm 에 머문다) → 밤길 2시간 54분에 졸음 경고(03) → 쉼터(05) → 도착(06)
 *  - 혼자: 내기순환 20분에 2,000ppm, "2분만 바꿀까요?"(09) → 동의하고 1분째 1,282ppm 까지 내려감(04)
 */
object PreviewStates {
    private const val NOW = 36_000_000L
    private val seokjeok = RestStopInfo(name = "석적", routeLabel = "경부선 서울방향", distanceKm = 12.0, lat = 36.0516, lng = 128.4081)
    private val defaults = ai.kkaeugi.engine.WakeConfig()
    /** 야간 장거리일 때 엔진이 쓰는 한도(기본 한도의 0.8배) */
    const val NIGHT_LIMIT = 1600
    private val connected = UiState(
        nowMs = NOW,
        pleosAvailable = true,
        connectionDetail = "연결 gear=GEAR_DRIVE",
        sources = listOf("Pleos Vehicle SDK", "Pleos NaviHelper SDK"),
        speakerName = "Pleos TTS", listenerName = "Pleos STT",
        tripUnit = "분",
        autoIntervene = true, micGranted = true, sttAvailable = true,
        // 좌석 값(점유 또는 벨트)이 오는 차. 주행 홈의 좌석 지도가 자리별 값 그대로 그려진다(기본은 운전석만)
        seatSensorLive = true, seats = listOf(true, false, false, false, false),
        // 게이지 시작 눈금과 한도는 엔진 기본 설정에서
        outdoorPpm = defaults.outdoorPpm, co2LimitPpm = defaults.co2LimitPpm, longTripMinutes = defaults.longTripMinutes,
    )
    /**
     * 주행 표본에 "살피는 것"과 앉은 시간을 채운다(v0.7.3). 따로 정해 둔 표본(21, 22)은 그대로 둔다.
     * 자리 설정이 없는 주행이라 기본 설정, 120분마다 쉬기이고 앉은 시간은 주행 시간과 같다
     */
    private fun seated(s: UiState): UiState =
        if (s.tripState == TripState.DRIVING && s.care == SeatCare()) s.copy(care = SeatCare(breakMinutes = 120, sitMinutes = s.tripMinutes, sitSeat = Seats.DRIVER)) else s
    private fun seated(list: List<Pair<String, UiState>>): List<Pair<String, UiState>> = list.map { (name, s) -> name to seated(s) }

    /** 표본의 자리 설정: 조수석 임산부, 뒷줄 왼쪽 어린이 */
    val FAMILY_PROFILES = listOf(SeatProfile.DEFAULT, SeatProfile.PREGNANT, SeatProfile.CHILD, SeatProfile.DEFAULT, SeatProfile.DEFAULT)
    /** 2026-11-09 21:42 KST */
    private const val VENT_WALL_MS = 1_794_228_120_000L

    /** 표본에서 쓰는 내기순환 경과(분). 01, 08 */
    const val RECIRC_MINUTES = 2.7

    /** 1. 주행 중 홈: 4명(운전석과 뒷줄 셋), 출발해서 내기순환 2.7분, 추정 1,646ppm, 약 1분 뒤 환기 제안, 다음 쉼터 12km */
    val drivingHome = connected.copy(
        tripState = TripState.DRIVING, gear = Gear.DRIVE,
        tripMinutes = 3, night = true,
        recirculating = true, recircSinceMs = NOW - (RECIRC_MINUTES * 60_000L).toLong(),
        occupants = 4, todayMaxOccupants = 4, seats = listOf(true, false, true, true, true),
        air = AirUi(ppm = 1646, occupants = 4, minutesToLimit = 1, ventilating = false),
        todayVentilations = 0, todayFreshAirUnits = 0, todayPeakPpm = 1646,
        nextRestStop = seokjeok, currentRoute = "경부선",
        // 숨 설계 카드: 같은 조건(4명, 내기순환 2.7분, 바깥 미세먼지 값 없음)으로 엔진을 굴린 값. 밤 9시 40분
        breath = BreathSampleDrive(4, pm25 = null, minuteOfDay = 21 * 60 + 40).run((RECIRC_MINUTES * 60).toInt()).design(),
    )

    const val KEEP_TEXT = "4명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?"
    const val SOLO_TEXT = "차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?"
    /** 졸음 개입의 기본 문장(창문 제외가 기본 설정) */
    const val DROWSY_TEXT = "졸음 경고가 떴어요. 찬 바람 넣고, 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?"
    /** 맡겨 둔 환기의 예고, 쉼 모드의 바깥 공기 질문, 도착 카드의 맡기기 질문. 엔진 문장과 같다(PreviewStatesTest 가 견준다) */
    const val ANNOUNCE_TEXT = "2분만 바깥 공기로 바꿀게요. 싫으면 '아니'라고 하세요."
    const val REST_AIR_TEXT = "쉬는 동안 바깥 공기로 둘까요?"
    const val TRUST_OFFER_TEXT = "오늘까지 환기 제안 3번에 모두 '응' 하셨어요. 다음부터 2분 환기는 말만 하고 바꿀까요?"
    const val PASSENGER_TEXT = "4명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요? 옆자리에서 눌러 주셔도 돼요."

    /** 2. 환기 제안(4명, 원복 없는 "바꿔 둘까요?"). 출발 3.7분에 2,000ppm. 다 읽고 2초 지남(카운트다운 6초) */
    val proposalStale = drivingHome.copy(
        recircSinceMs = NOW - 222_000L,
        air = AirUi(ppm = 2003, occupants = 4, minutesToLimit = 0, ventilating = false),
        todayPeakPpm = 2003,
        proposal = Proposal(TriggerKind.STALE_AIR, KEEP_TEXT, timeoutSec = 8, countdownFrom = NOW - 2_000L),
        lastSaid = KEEP_TEXT,
    )

    /** 가족 4명이 "바꿔 둘까요?"에 동의한 뒤: 바깥 공기 유지(풍량 4/8), 추정 1,001ppm 에 머문다. 밤길 장거리라 한도선은 1,600 */
    private val familyKeeping = drivingHome.copy(
        tripMinutes = 174, co2LimitPpm = NIGHT_LIMIT,
        recirculating = false, recircSinceMs = null,
        air = AirUi(ppm = 1001, occupants = 4, minutesToLimit = null, ventilating = true),
        todayVentilations = 1, todayFreshAirUnits = 170, todayPeakPpm = 2050,
        lastVentilationWallMs = VENT_WALL_MS,
    )

    /** 3. 졸음 제안(쉼터 경유지 포함): 가족 4명, 밤길 2시간 54분. 다 읽고 1초 지남(카운트다운 7초) */
    val proposalDrowsy = familyKeeping.copy(
        proposal = Proposal(TriggerKind.DROWSY, DROWSY_TEXT, timeoutSec = 8, countdownFrom = NOW - 1_000L),
        lastSaid = DROWSY_TEXT,
    )

    /** 혼자 탈 때: 출발해서 내기순환 20분에 2,000ppm */
    private val soloAtLimit = connected.copy(
        tripState = TripState.DRIVING, gear = Gear.DRIVE,
        tripMinutes = 20, night = false,
        recirculating = true, recircSinceMs = NOW - 20 * 60_000L - 10_000L,
        occupants = 1, todayMaxOccupants = 1,
        air = AirUi(ppm = 2001, occupants = 1, minutesToLimit = 0, ventilating = false),
        todayVentilations = 0, todayFreshAirUnits = 0, todayPeakPpm = 2001,
        nextRestStop = seokjeok, currentRoute = "경부선",
    )

    /** 9. 환기 제안(혼자, "2분만" 바꾸고 원복). 다 읽고 2초 지남 */
    val proposalSolo = soloAtLimit.copy(
        proposal = Proposal(TriggerKind.STALE_AIR, SOLO_TEXT, timeoutSec = 8, countdownFrom = NOW - 2_000L),
        lastSaid = SOLO_TEXT,
    )

    /** 4. 환기 실행 중(혼자): 바깥 공기 1분째, 추정 2,000 → 1,282ppm 으로 내려가는 중. 하단 바(1:00 남음) + 증거 띠 */
    val running = soloAtLimit.copy(
        tripMinutes = 21,
        recirculating = false, recircSinceMs = null,
        air = AirUi(ppm = 1282, occupants = 1, minutesToLimit = null, ventilating = true, falling = true),
        todayVentilations = 1, todayFreshAirUnits = 1, todayPeakPpm = 2010,
        lastVentilationWallMs = VENT_WALL_MS,
        running = RunningBar(TriggerKind.STALE_AIR, remainingSec = 60, label = "바깥 공기로 바꾸는 중"),
        evidenceMode = true,
        evidenceLine = "setHvacFanSpeed(4) → ok 0.2s",
        lastSaid = SOLO_TEXT,
        // 숨 설계 카드: 혼자 달려 첫 환기를 실행하고 1분 지난 때
        breath = BreathSampleDrive(1, pm25 = null, minuteOfDay = 14 * 60).run(3_000) { d -> d.firstVentAt?.let { d.now - it >= 60_000 } ?: false }.design(),
    )

    /**
     * 5. 쉼 모드: 석적 졸음쉼터, 15분 중 14:52 남음. 내기순환인 채로 들어와 "쉬는 동안 바깥 공기로 둘까요?" 를 카드 안 아래쪽에서 묻는다.
     * 다 읽고 3초 지남(20초 중 17초 남음). 맨 아래는 고정 문구
     */
    val resting = familyKeeping.copy(
        gear = Gear.PARK, tripMinutes = 185,
        recirculating = true,
        todayVentilations = 2, todayFreshAirUnits = 182,
        waypointStop = seokjeok.copy(distanceKm = 0.2), proximityKm = 0.2,
        rest = RestUi(stopName = "석적 졸음쉼터", targetMinutes = 15, remainingSec = 14 * 60 + 52),
        proposal = Proposal(TriggerKind.STALE_AIR, REST_AIR_TEXT, timeoutSec = 20, countdownFrom = NOW - 3_000L, passengerMayAnswer = true, restQuestion = true),
        lastSaid = REST_AIR_TEXT,
        // 낮잠 안내(엔진 문장 그대로). 큰 카운트다운이 그 15분 타이머다
        napTip = WakeEngine.napTip(),
    )

    /**
     * 가족 4명 밤길 주행 요약: 주행 3시간 20분(쉼 12분은 뺀 값). 환기 2회("바꿔 둘까요?" 1회 + 졸음 개입 안의 환기 1회),
     * 출발 4분쯤부터 바깥 공기(197분), 졸음 경고 1회, 쉼터 1회 12분. 최고 추정 2,050ppm(제안을 읽고 답하는 사이 조금 더 오른 값)
     */
    val arrivalSummary = ArrivalSummary(
        headline = "오늘 환기 2번, 쉼터에서 12분 쉬었어요.",
        tripMinutes = 200, ventilations = 2, freshAirMinutes = 197, drowsySignals = 1,
        restStopsAdded = 1, restStopsVisited = 1, restMinutes = 12, restStopsPassed = 0, declined = 0,
        lines = listOf(
            "출발 3분 뒤 바깥 공기로 계속 두기",
            "출발 174분 뒤 졸음 경고(운전자 주의 경고)",
            "출발 174분 뒤 석적 졸음쉼터 경유지 추가",
            "출발 197분 뒤 석적 졸음쉼터에서 12분 휴식",
        ),
        peakPpm = 2050, minutesOverLimit = 0,
        // 자리별 누적(v0.7.3). 손으로 적은 표본이다: 넷이 처음부터 탔고 바깥 미세먼지 값은 받지 못한 밤길.
        // 가장 긴 착석은 쉼터에 서기 전까지의 197분, 쉼터에서 12분 서 있었으니 걷기 쉼 1회
        seatHeadline = "오늘 3시간 20분, 넷이 탔어요.",
        seats = listOf(Seats.DRIVER, Seats.REAR_LEFT, Seats.REAR_CENTER, Seats.REAR_RIGHT).map {
            SeatStats(seat = it, minutesAboard = 200, minutesOverLimit = 0, averagePpm = 1020, longestSitMinutes = 197, walkBreaks = 1)
        },
        cabin = SeatStats(seat = Seats.CABIN, minutesAboard = 200, minutesOverLimit = 0, averagePpm = 1020, longestSitMinutes = 197, walkBreaks = 1),
        walkBreaks = 1,
    )

    /** 6. 도착 카드: 한 문장, 자리마다 한 줄(넷), 주석 */
    val arrival = connected.copy(
        tripState = TripState.ENDED, gear = Gear.PARK,
        connectionDetail = "연결 gear=GEAR_PARK",
        tripMinutes = 200, occupants = 4, todayMaxOccupants = 4, night = true,
        todayVentilations = 2, todayFreshAirUnits = 197, todayPeakPpm = 2050,
        lastVentilationWallMs = VENT_WALL_MS,
        arrival = arrivalSummary,
        lastSaid = arrivalSummary.headline,
    )

    /** 10. 예고(맡겨 둔 환기): 혼자, 내기순환 20분에 2,000ppm. 묻지 않고 예고한 뒤 5초 취소 창. 다 읽고 2초 지남(3초 남음) */
    val announce = soloAtLimit.copy(
        trustDelegated = true,
        announce = AnnounceUi(ANNOUNCE_TEXT, cancelWindowSec = 5, countdownFrom = NOW - 2_000L),
        lastSaid = ANNOUNCE_TEXT,
    )

    /** 13. 환기 제안(4명, 조수석에 사람이 있음): 답 버튼 묶음이 조수석 쪽(오른쪽)에 크게 붙는다 */
    val proposalPassenger = proposalStale.copy(
        frontPassenger = true, seats = listOf(true, true, true, false, true),
        proposal = Proposal(TriggerKind.STALE_AIR, PASSENGER_TEXT, timeoutSec = 8, countdownFrom = NOW - 2_000L, passengerMayAnswer = true),
        lastSaid = PASSENGER_TEXT,
    )

    /**
     * 혼자 출퇴근 주행 요약: 42분, "2분만" 환기 2회에 모두 동의. 지난 주행까지 합쳐 연속 3번 동의해서 맡길지 묻는다.
     * 바깥 공기 4분(2분 두 번), 최고 추정 2,010ppm
     */
    val trustSummary = ArrivalSummary(
        headline = "오늘 환기 2번, 추정 2,000ppm 을 넘긴 건 1분이었어요.",
        tripMinutes = 42, ventilations = 2, freshAirMinutes = 4, drowsySignals = 0,
        restStopsAdded = 0, restStopsVisited = 0, restMinutes = 0, restStopsPassed = 0, declined = 0,
        lines = listOf("출발 20분 뒤 바깥 공기로 환기", "출발 41분 뒤 바깥 공기로 환기"),
        peakPpm = 2010, minutesOverLimit = 1,
        trustOffer = TRUST_OFFER_TEXT,
        // 자리별 누적(v0.7.3). 손으로 적은 표본: 혼자 42분
        seatHeadline = "오늘 42분, 혼자 탔어요.",
        seats = listOf(SeatStats(seat = Seats.DRIVER, minutesAboard = 42, minutesOverLimit = 1, averagePpm = 1410, longestSitMinutes = 42)),
        cabin = SeatStats(seat = Seats.CABIN, minutesAboard = 42, minutesOverLimit = 1, averagePpm = 1410, longestSitMinutes = 42),
    )

    /** 11. 도착 카드(맡기기 질문): 문장과 [맡길게요] [계속 물어봐 주세요] */
    val arrivalTrust = connected.copy(
        tripState = TripState.ENDED, gear = Gear.PARK,
        connectionDetail = "연결 gear=GEAR_PARK",
        tripMinutes = 42, occupants = 1, todayMaxOccupants = 1,
        todayVentilations = 2, todayFreshAirUnits = 4, todayPeakPpm = 2010,
        lastVentilationWallMs = VENT_WALL_MS,
        arrival = trustSummary,
        lastSaid = trustSummary.headline,
    )

    /**
     * 자가 진단 표본 보고서. Pleos 에뮬레이터에서 나올 법한 모양을 손으로 적은 것이다(실측 값이 아니다).
     * 실패 줄의 모양(퍼미션 거부, 응답 없음)도 보이게 몇 줄을 실패로 넣었다
     */
    val diagLines: List<DiagLine> = buildList {
        add(DiagLine.ok("연결 판정", "연결 gear=GEAR_PARK"))
        for (p in listOf("CAR_DRIVE", "CAR_SEATS", "CAR_LIGHTS", "CAR_WINDOWS", "CAR_SAFETY", "CAR_CLIMATE")) add(DiagLine.ok("퍼미션 $p", "허용"))
        add(DiagLine.fail("퍼미션 PRIVATE_CAR_SAFETY", "거부"))
        for (p in listOf("CONTROL_CAR_CLIMATE", "CONTROL_CAR_WINDOWS", "NAVI_ROUTE_SEARCH", "NAVI_ROUTE", "FUSED_LOCATION", "TTS_SERVICE", "STT_SERVICE")) add(DiagLine.ok("퍼미션 $p", "허용"))
        add(DiagLine.ok("기어", "GEAR_PARK"))
        add(DiagLine.ok("내기순환", "켜짐(내기)"))
        add(DiagLine.ok("자동 내기순환", "꺼짐"))
        add(DiagLine.ok("풍량", "현재 3 / 최대 8"))
        add(DiagLine.ok("설정 온도", "22.0C (범위 17.0~27.0)"))
        for (w in listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT")) add(DiagLine.ok("창문 $w", "raw 0 (min 0, max 100, 열림 0%)"))
        add(DiagLine.ok("좌석 점유 ROW_1_LEFT", "있음"))
        add(DiagLine.ok("좌석 점유 ROW_1_RIGHT", "없음"))
        for (s in listOf("ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")) add(DiagLine.fail("좌석 점유 $s", "ERROR_NOT_AVAILABLE(3): not supported"))
        add(DiagLine.ok("벨트 ROW_1_LEFT", "체결"))
        for (s in listOf("ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")) add(DiagLine.ok("벨트 $s", "풀림"))
        add(DiagLine.ok("핸들 각도", "-1.5도"))
        add(DiagLine.ok("핸들 각도 범위", "-540.0~540.0"))
        add(DiagLine.ok("하향등", "OFF"))
        add(DiagLine.ok("와이퍼", "OFF"))
        add(DiagLine.fail("텔테일 등록", "ERROR_SECURITY_PERMISSION(2): permission denied"))
        add(DiagLine.ok("트립 시간 raw", "0 (단위: 판정 전(앱 타이머))"))
        add(DiagLine.ok("내비 초기화", "준비됨"))
        add(DiagLine.ok("내비 현재 위치", "37.39, 127.11 (heading 92)"))
        add(DiagLine.fail("목적지 남은 시간·거리", "응답 없음. 내비에 목적지를 넣은 뒤 다시 눌러 주세요"))
        add(DiagLine.ok("FusedLocation", "37.39, 127.11 (heading 92). 속도는 SDK 값이 없어 위치 차이로 추정"))
        add(DiagLine.ok("Pleos TTS 초기화", "성공"))
        add(DiagLine.ok("Pleos STT 초기화", "성공"))
        add(DiagLine.ok("마이크 권한", "허용"))
        add(DiagLine.ok("알림 권한", "아직 요청 안 함(화면 밖 유지를 켤 때만 물어요)"))
        add(DiagLine.ok("화면 밖 유지(실험)", "꺼짐"))
    }

    /** v0.7.3 에서 자가 진단 끝에 붙는 두 절의 표본(프로필과 앉은 시간, Gleo). 호출 없이 앱이 아는 값으로 적는 줄이다 */
    val seatDiagLines: List<DiagLine> = buildList {
        add(DiagLine.section("프로필과 앉은 시간"))
        add(DiagLine.ok("자리 설정", "조수석 임산부, 뒷줄 왼쪽 어린이 (기기 안에만 저장)"))
        add(DiagLine.ok("자리별 값", "O X ? ? ? (운전석부터 뒷줄 오른쪽까지)"))
        add(DiagLine.alt("문 신호", "받은 적 없음. 기어 P 5분 이상이면 일어선 것으로 봐요"))
        add(DiagLine.ok("살피는 것", "기본 설정 · 120분마다 쉬기"))
        add(DiagLine.ok("앉은 시간", "방금 앉았어요 (좌석과 기어 신호로 센 값)"))
        add(DiagLine.section("Gleo"))
        add(DiagLine.ok("schema.json", "함수 3개(상한 3): VENTILATE_CABIN, CHECK_CABIN_STATUS, ADD_REST_STOP"))
        add(DiagLine.ok("Gleo 수신기", "함수 action 3개 등록됨"))
        add(DiagLine.ok("마지막 호출", "CHECK_CABIN_STATUS {topic=air} (모두 1번)"))
        add(DiagLine.ok("마지막 응답", "success: 아직 추정값이 없어요. 출발하면 추정을 시작해요."))
        add(DiagLine.alt("Gleo 승인", "Gleo 검토 뒤에 음성으로 불려요. 그 전에는 adb 로 확인해요"))
    }

    /** 7. 정차(IDLE): 맨 위 [자가 진단], 탑승 인원(좌석 센서 기준), 설정 둘(숨 켜기, 2분 환기 맡기기), 최근 주행 기록 2줄, 개발자 묶음 접힘 */
    val idle = connected.copy(
        tripState = TripState.IDLE, gear = Gear.PARK,
        connectionDetail = "연결 gear=GEAR_PARK",
        // 앞자리는 점유 값이 오고 뒷줄은 값을 못 읽는 차(자가 진단 표본과 같다). 못 읽은 자리는 점선으로 그린다
        seatSensorLive = true, seats = listOf(true, false, null, null, null),
        // 자리 설정을 고른 상태(v0.7.3): 조수석 임산부, 뒷줄 왼쪽 어린이. 빈자리여도 설정은 자리에 붙어 있다
        seatProfiles = FAMILY_PROFILES,
        recentTrips = listOf(
            "11/09 02:40  200분 주행, 환기 2회, 졸음 1회, 쉼 1회, 거절 0회, 최고 2,050ppm 추정",
            "11/07 19:12  85분 주행, 환기 4회, 졸음 0회, 쉼 0회, 거절 0회, 최고 2,010ppm 추정",
        ),
        log = listOf("Pleos 연결: gear=GEAR_PARK", "신호 GearChanged", "신호 RecirculationChanged", "차량: 탑승 인원 1명 (좌석 점유 값: ROW_1_LEFT=O ROW_1_RIGHT=X)", "차량: 트립시간 단위: 분"),
    )

    /** 12. 자가 진단: 표본 보고서(세 단), 맨 위 "성공 N / 실패 M" */
    val diagnosis = idle.copy(
        diag = DiagUi(DiagReport.header("0.7.3", "2026-11-12 09:12:30"), diagLines, running = false, saved = true),
    )

    /** 8-왼쪽. 혼자 탈 때: 같은 내기순환 2.7분인데 추정 871ppm, 한도까지 약 17분 */
    val aloneHome = drivingHome.copy(
        occupants = 1, todayMaxOccupants = 1, seats = listOf(true, false, false, false, false),
        air = AirUi(ppm = 871, occupants = 1, minutesToLimit = 17, ventilating = false),
        todayPeakPpm = 871,
    )
    /** 8-오른쪽. 4명 탈 때: 같은 시간에 추정 1,646ppm, 한도까지 약 1분 */
    val fourHome = drivingHome

    /**
     * 14. 조용 모드에서 한도 초과: 4명, 환기 제안을 두 번 거절해 더 묻지 않는 중. 내기순환 그대로라 추정 2,600ppm.
     * 게이지 아래에 한도 초과 문구가 먼저 나오고, 그 아래 큰 "바깥 공기로" 버튼 하나
     */
    val quietOverLimit = drivingHome.copy(
        tripMinutes = 27, recircSinceMs = NOW - 27 * 60_000L,
        air = AirUi(ppm = 2600, occupants = 4, minutesToLimit = 0, ventilating = false),
        todayPeakPpm = 2600, quietThisTrip = true,
        lastSaid = "이번 주행에서는 더 묻지 않을게요. 화면에만 표시할게요.",
        // 숨 설계 카드: 4명이 제안을 넘기고 내기순환으로 2,600ppm 까지 오른 곡선
        breath = BreathSampleDrive(4, pm25 = null, minuteOfDay = 21 * 60 + 40, answer = false).run(3_000) { it.engine.estimatedPpm >= 2600 }.design(),
    )

    // ---------- v0.7.0 숨 설계 ----------

    /** 바깥 초미세먼지 표본 값(µg/m³, 나쁨). 실제 값이 아니라 화면 표본이다 */
    const val SAMPLE_PM = 41.0

    /** 2명, 밖 미세먼지 나쁨(41), 오후 2시 10분에 출발해 13분 달린 때: 한도에 닿아 1분 30초 환기를 한 번 하고 다시 오르는 중 */
    private val breathDrive = BreathSampleDrive(2, SAMPLE_PM, minuteOfDay = 14 * 60 + 10).run(13 * 60)

    /** 15. 숨 설계 홈: 곡선(실제, 그림자 내기만, 한도선, 예보 점선)과 "밖 미세먼지 나쁨(41) · 2명 · 약 6분마다 1분 30초" */
    val breathHome = connected.copy(
        tripState = TripState.DRIVING, gear = Gear.DRIVE,
        tripMinutes = 13, night = false,
        recirculating = true, recircSinceMs = NOW - 3 * 60_000L,
        // 자리별 값 그대로: 운전석과 뒷줄 왼쪽(아이를 뒤에 태운 2명)
        occupants = 2, todayMaxOccupants = 2, seats = listOf(true, false, true, false, false),
        air = breathDrive.airUi(),
        todayVentilations = breathDrive.ventilations, todayFreshAirUnits = 2, todayPeakPpm = 2030,
        lastVentilationWallMs = VENT_WALL_MS,
        nextRestStop = seokjeok, currentRoute = "경부선",
        breath = breathDrive.design(),
        outdoorStatus = "PM2.5 41µg/m³(나쁨) · Open-Meteo(CAMS 격자 모델값) · 12분 전",
        lastSaid = "추정 1,200ppm까지 내려갔어요. 원래대로 돌렸어요.",
    )

    /** 같은 조건으로 한 시간을 달리고 도착한 요약: 세 갈래 비교가 실려 있다 */
    /** 맡기기 질문은 뺀다(이 표본은 세 갈래 비교와 그 아래 작은 글씨를 보여 주는 장이다) */
    val threeWaySummary: ArrivalSummary = BreathSampleDrive(2, SAMPLE_PM, minuteOfDay = 14 * 60 + 10).run(60 * 60).park().copy(trustOffer = null)

    /** 16. 도착 카드(세 갈래 비교): CO2 최고값과 미세먼지 노출을 내기만, 외기만, 이번 주행으로 나란히 */
    val arrivalThreeWay = connected.copy(
        tripState = TripState.ENDED, gear = Gear.PARK,
        connectionDetail = "연결 gear=GEAR_PARK",
        tripMinutes = threeWaySummary.tripMinutes, occupants = 2, todayMaxOccupants = 2,
        todayVentilations = threeWaySummary.ventilations, todayFreshAirUnits = threeWaySummary.freshAirMinutes, todayPeakPpm = threeWaySummary.peakPpm,
        lastVentilationWallMs = VENT_WALL_MS,
        arrival = threeWaySummary,
        lastSaid = threeWaySummary.headline,
    )

    // ---------- v0.7.3 자리별로 줄인다 ----------

    /** 20. 자리 설정 시트: 정차 화면에서 조수석을 눌렀다. 지금 고른 것은 임산부 */
    val profileSheet: UiState by lazy { idle.copy(profileSheetSeat = Seats.FRONT_PASSENGER) }

    /** 걷기 쉼을 켠 엔진 설정(앱이 쓰는 값) */
    private val walkConfig = WakeConfig(walkBreak = true)
    /** 표본 바깥 초미세먼지(µg/m³, 보통) */
    const val SAMPLE_PM_MODERATE = 25.0

    /**
     * 둘이 탄 주행(운전석, 조수석). 조수석은 심혈관 주의: 미세먼지 먼저(보통인 날을 나쁨처럼), 90분마다 쉬기.
     * 오후 2시 10분에 출발해 1시간 12분 달린 때. 제안에는 모두 동의했다
     */
    private val careDrive by lazy {
        BreathSampleDrive(
            2, SAMPLE_PM_MODERATE, minuteOfDay = 14 * 60 + 10,
            seats = listOf(true, true, false, false, false), profiles = listOf(SeatProfile.DEFAULT, SeatProfile.CARDIO), config = walkConfig,
            refreshPm = SAMPLE_PM_MODERATE,
        ).run(72 * 60).run(600) { d -> d.engine.phaseName == "IDLE" && d.lastAir?.let { !it.ventilating && it.ppm in 1500..1900 } == true }
    }

    /** 21. 주행 홈(살피는 것): 오른쪽 레일에 "미세먼지 먼저 · 90분마다 쉬기"와 "앉은 지 1시간 12분" */
    val careHome: UiState by lazy {
        connected.copy(
            tripState = TripState.DRIVING, gear = Gear.DRIVE,
            tripMinutes = (careDrive.now / 60_000L).toInt(), night = false,
            recirculating = true, recircSinceMs = NOW - 3 * 60_000L,
            occupants = 2, todayMaxOccupants = 2, frontPassenger = true,
            seats = listOf(true, true, false, false, false), seatProfiles = listOf(SeatProfile.DEFAULT, SeatProfile.CARDIO, SeatProfile.DEFAULT, SeatProfile.DEFAULT, SeatProfile.DEFAULT),
            air = careDrive.airUi(),
            todayVentilations = careDrive.ventilations, todayFreshAirUnits = careDrive.ventilations * 2, todayPeakPpm = 2030,
            lastVentilationWallMs = VENT_WALL_MS,
            nextRestStop = seokjeok, currentRoute = "경부선",
            breath = careDrive.design(), care = careDrive.engine.care(careDrive.now),
            lastSaid = "추정 1,300ppm까지 내려갔어요. 원래대로 돌렸어요.",
            outdoorStatus = "PM2.5 25µg/m³(보통) · Open-Meteo(CAMS 격자 모델값) · 12분 전",
        )
    }

    /** 걷기 쉼 제안 문장. 엔진 문장과 같다(SeatTextTest 가 견준다) */
    const val WALK_TEXT = "한 시간 반째 앉아 있어요. 12km 앞 경부선 석적 졸음쉼터에서 내려서 5분 걸을까요?"

    /** 22. 걷기 쉼 제안: 같은 주행이 90분에 닿았다. 조수석에 사람이 있어 버튼이 조수석 쪽에 붙는다 */
    val proposalWalk: UiState by lazy {
        careHome.copy(
            tripMinutes = 90,
            care = SeatCare(pmFirst = true, wideMargin = false, breakMinutes = 90, sitMinutes = 90, sitSeat = Seats.DRIVER),
            proposal = Proposal(TriggerKind.WALK_BREAK, WALK_TEXT, timeoutSec = 8, countdownFrom = NOW - 2_000L, passengerMayAnswer = true),
            lastSaid = WALK_TEXT,
        )
    }

    /**
     * 가족 주행 요약(엔진을 굴린 값): 오전 9시에 셋이 출발(운전석, 조수석 임산부, 뒷줄 왼쪽 어린이), 밖 미세먼지 나쁨(41).
     * 40분 뒤 뒷줄 오른쪽에 한 명이 더 탔다. 90분에 걷기 쉼에 동의해 석적 졸음쉼터에서 6분 서 있었고, 모두 2시간 10분을 달렸다
     */
    val seatSummary: ArrivalSummary by lazy {
        val d = BreathSampleDrive(
            3, SAMPLE_PM, minuteOfDay = 9 * 60,
            seats = listOf(true, true, true, false, false), profiles = FAMILY_PROFILES, config = walkConfig, candidate = seokjeok,
            refreshPm = SAMPLE_PM,
        )
        d.run(40 * 60)
        d.signal { SeatOccupancyChanged(it, listOf(true, true, true, false, true)) }.signal { OccupantsChanged(it, 4) }
        d.run(66 * 60)
        d.signal { RestStopProximity(it, within = true) }.signal { GearChanged(it, Gear.PARK) }
        d.run(6 * 60)
        d.signal { GearChanged(it, Gear.DRIVE) }
        d.run(24 * 60)
        d.park().copy(trustOffer = null)
    }

    /** 23. 도착 카드(자리별): 한 문장, 자리마다 한 줄, 접은 세 갈래 한 줄, 주석 두 줄 */
    val arrivalSeats: UiState by lazy {
        connected.copy(
            tripState = TripState.ENDED, gear = Gear.PARK,
            connectionDetail = "연결 gear=GEAR_PARK",
            tripMinutes = seatSummary.tripMinutes, occupants = 4, todayMaxOccupants = 4,
            seats = listOf(true, true, true, false, true), seatProfiles = FAMILY_PROFILES,
            todayVentilations = seatSummary.ventilations, todayFreshAirUnits = seatSummary.freshAirMinutes, todayPeakPpm = seatSummary.peakPpm,
            lastVentilationWallMs = VENT_WALL_MS,
            arrival = seatSummary,
            lastSaid = seatSummary.headline,
        )
    }

    /** 24. 자가 진단(v0.7.3): 표본 보고서 끝에 "프로필과 앉은 시간", "Gleo" 두 절이 붙는다 */
    val diagnosisSeat: UiState by lazy { idle.copy(diag = DiagUi(DiagReport.header("0.7.3", "2026-11-12 09:12:30"), diagLines + seatDiagLines, running = false, saved = true)) }

    // ---------- v0.8.0 기침 자리 ----------
    /** 운전석, 조수석 임산부, 뒷줄 왼쪽 어린이(기침), 뒷줄 오른쪽 어르신 */
    val COUGH_PROFILES = listOf(SeatProfile.DEFAULT, SeatProfile.PREGNANT, SeatProfile.COUGH, SeatProfile.DEFAULT, SeatProfile.SENIOR)
    private val COUGH_SEATS = listOf(true, true, true, false, true)

    /** 25. 자리 설정 시트: 뒷줄 왼쪽을 "기침해요"로 */
    val coughSheet: UiState by lazy { idle.copy(seats = COUGH_SEATS, occupants = 4, seatProfiles = COUGH_PROFILES, profileSheetSeat = Seats.REAR_LEFT) }

    /** 26. 기침 자리 제안: 넷이 타고 출발 24초 뒤(엔진을 굴리면 이때 묻는다). 한도를 기다리지 않는다 */
    val coughProposal: UiState by lazy {
        drivingHome.copy(
            seats = COUGH_SEATS, seatProfiles = COUGH_PROFILES,
            recircSinceMs = NOW - 24_000L,
            air = AirUi(ppm = 960, occupants = 4, minutesToLimit = 4, ventilating = false),
            todayPeakPpm = 960,
            proposal = Proposal(TriggerKind.STALE_AIR, WakeEngine.COUGH_KEEP_ASK, timeoutSec = 8, countdownFrom = NOW - 2_000L),
            lastSaid = WakeEngine.COUGH_KEEP_ASK,
        )
    }

    /** 기침 자리 주행 요약(엔진을 굴린 값): 넷이 90분, 바깥 미세먼지 보통(22), 제안에 모두 동의 */
    val coughSummary: ArrivalSummary by lazy {
        val d = BreathSampleDrive(4, 22.0, minuteOfDay = 10 * 60, seats = COUGH_SEATS, profiles = COUGH_PROFILES, refreshPm = 22.0)
        d.run(90 * 60)
        d.park().copy(trustOffer = null)
    }

    /** 27. 도착 카드(기침 자리): 자리별 줄 아래에 추정 감염 위험 한 줄 */
    val coughArrival: UiState by lazy {
        connected.copy(
            tripState = TripState.ENDED, gear = Gear.PARK,
            connectionDetail = "연결 gear=GEAR_PARK",
            tripMinutes = coughSummary.tripMinutes, occupants = 4, todayMaxOccupants = 4,
            seats = COUGH_SEATS, seatProfiles = COUGH_PROFILES,
            todayVentilations = coughSummary.ventilations, todayFreshAirUnits = coughSummary.freshAirMinutes, todayPeakPpm = coughSummary.peakPpm,
            lastVentilationWallMs = VENT_WALL_MS,
            arrival = coughSummary,
            lastSaid = coughSummary.headline,
        )
    }

    val coughShots: List<Pair<String, UiState>> by lazy {
        listOf("25_자리설정_기침" to coughSheet, "26_제안_기침" to coughProposal, "27_도착카드_기침" to coughArrival)
    }

    /** v0.7.3 에서 더한 스크린샷 */
    val seatShots: List<Pair<String, UiState>> by lazy {
        listOf(
            "20_자리설정_시트" to profileSheet,
            "21_주행중_살피는것" to careHome,
            "22_제안_걷기쉼" to proposalWalk,
            "23_도착카드_자리별" to arrivalSeats,
            "24_자가진단_프로필_Gleo" to diagnosisSeat,
        )
    }

    /** v0.7.0 에서 더한 스크린샷. 앞의 13장(all)과 따로 둔다 */
    val breathShots: List<Pair<String, UiState>> = seated(listOf(
        "15_숨설계_홈" to breathHome,
        "16_도착카드_세갈래" to arrivalThreeWay,
    ))

    /** 밤 테마로 한 번 더 찍는 장: 주행 홈, 제안, 실행 중, 숨 곡선 홈, 도착 카드. 파일 이름 끝에 "_밤" 이 붙는다 */
    val nightShots: List<Pair<String, UiState>> by lazy {
        seated(listOf(
            "01_주행중_홈_밤" to drivingHome,
            "02_제안_환기_밤" to proposalStale,
            "04_실행중_밤" to running,
            "15_숨설계_홈_밤" to breathHome,
            "16_도착카드_세갈래_밤" to arrivalThreeWay,
            // v0.7.3 의 밤 두 장
            "21_주행중_살피는것_밤" to careHome,
            "23_도착카드_자리별_밤" to arrivalSeats,
        ))
    }

    /** 쉼 모드에서 바깥 공기 질문이 지나간 뒤: 큰 타이머와 "화면 닫기" 버튼 하나 */
    val restingQuiet: UiState by lazy { resting.copy(recirculating = false, proposal = null, rest = RestUi(stopName = "석적 졸음쉼터", targetMinutes = 15, remainingSec = 9 * 60 + 30)) }

    /** v0.7.1 에서 더한 장 */
    val extraShots: List<Pair<String, UiState>> by lazy { seated(listOf("19_쉼모드_화면닫기" to restingQuiet)) }

    /**
     * 점검용 표본. 문서에는 싣지 않고 스크린샷 테스트가 빌드 폴더(app/build/check-shots)에만 그린다.
     * 평소 표본에 없는 갈래를 눈으로 확인한다: 개발자 영역, 직접 넣는 인원, 모의 모드, 말로 답할 수 없을 때, 긴 문장, 밤 테마
     */
    val checkShots: List<Pair<String, UiState>> by lazy {
        val mock = idle.copy(pleosAvailable = false, seatSensorLive = false, seats = null, manualOccupants = 3, occupants = 3, occupantsCarried = true, micGranted = false, sttAvailable = false, connectionDetail = "모의: 응답 없음(4초)", sources = listOf("모의 신호"))
        seated(listOf(
            "정차_개발자" to idle.copy(showDeveloper = true, lastSaid = "추정 1,200ppm까지 내려갔어요. 원래대로 돌렸어요.", permissionFailures = listOf("registerTelltales: ERROR_SECURITY_PERMISSION(2)")),
            "정차_개발자_검증" to idle.copy(showDeveloper = true, showVerify = true, verifyLog = listOf("12:01:03 getHvacRecirculation → true 0.1s", "12:01:09 setHvacFanSpeed(4) → ok 0.2s")),
            "정차_직접입력_모의" to mock,
            "정차_밤" to idle,
            "정차_내외기모름" to idle.copy(recircUnsupported = true, nextRestStop = seokjeok, lastVentilationWallMs = VENT_WALL_MS, pmChoice = ai.kkaeugi.app.signals.PmChoice.BAD, outdoorStatus = "PM2.5 41µg/m³(나쁨) · 직접 고름"),
            "제안_졸음_밤" to proposalDrowsy,
            "제안_버튼만" to proposalSolo.copy(micGranted = false),
            "제안_읽는중" to proposalStale.copy(proposal = proposalStale.proposal!!.copy(countdownFrom = null)),
            "예고_버튼만_밤" to announce.copy(micGranted = false, announce = AnnounceUi(ai.kkaeugi.app.speech.announceText(ANNOUNCE_TEXT, false), cancelWindowSec = 5, countdownFrom = NOW - 1_000L)),
            "실행중_취소가능" to running.copy(runCancellable = true, evidenceMode = false, running = RunningBar(TriggerKind.STALE_AIR, remainingSec = 95, label = "바깥 공기로 바꾸는 중")),
            "홈_모의_꼬리표" to familyKeeping.copy(pleosAvailable = false, raining = true, usingDemoLocation = true, permissionFailures = listOf("x"), breath = breathHome.breath, lastSaid = "바깥 공기로 바꿔 뒀어요."),
            "홈_추정전" to drivingHome.copy(air = null, breath = null, nextRestStop = null, headingUnknown = true),
            "홈_경유지_데모속도" to breathHome.copy(demoFast = true, waypointStop = seokjeok.copy(distanceKm = 0.4), proximityKm = 0.4, recirculating = false),
            "도착_세갈래_맡기기" to arrivalThreeWay.copy(arrival = threeWaySummary.copy(trustOffer = TRUST_OFFER_TEXT)),
            "도착_맡기기_답함_밤" to arrivalTrust.copy(trustAnswered = true),
            "쉼_밤" to resting,
            "자가진단_밤" to diagnosis,
            "홈_움직임켬" to breathHome,
            // v0.7.3: 자리 설정 시트의 밤 테마와 직접 입력 차, 자리 설정이 없는 정차 화면, 다섯 줄에 맡기기 질문까지 있는 도착 카드, 걷기 쉼 화면
            "자리설정_시트_밤" to profileSheet.copy(profileSheetSeat = Seats.REAR_LEFT),
            "자리설정_시트_직접입력" to mock.copy(seatProfiles = FAMILY_PROFILES, profileSheetSeat = Seats.DRIVER),
            "정차_자리설정없음" to idle.copy(seatProfiles = List(Seats.COUNT) { SeatProfile.DEFAULT }),
            "정차_자리설정_다섯" to idle.copy(seats = listOf(true, true, true, true, true), occupants = 5, seatProfiles = listOf(SeatProfile.SENIOR, SeatProfile.PREGNANT, SeatProfile.CHILD, SeatProfile.RESPIRATORY, SeatProfile.CARDIO)),
            "도착_자리별_맡기기" to arrivalSeats.copy(arrival = seatSummary.copy(trustOffer = TRUST_OFFER_TEXT)),
            "도착_자리별_데모속도" to arrivalSeats.copy(demoFast = true),
            "쉼_걷기" to restingQuiet.copy(rest = RestUi(stopName = "석적 졸음쉼터", targetMinutes = 5, remainingSec = 3 * 60 + 40, walk = true), napTip = WakeEngine.walkTip(5)),
            "홈_살피는것_기본" to breathHome.copy(care = SeatCare(breakMinutes = 120, sitMinutes = 13, sitSeat = Seats.DRIVER)),
        ))
    }

    /** 넓은 창 표본. Pleos Connect 에서 앱 창은 1280x720 보다 넓거나 높게 뜰 수 있다 */
    data class WideShot(val name: String, val widthDp: Int, val heightDp: Int, val state: UiState)

    val wideShots: List<WideShot> by lazy {
        listOf(
            WideShot("17_넓은창_홈", 1700, 900, seated(breathHome)),
            WideShot("18_넓은창_제안", 1350, 1000, seated(proposalStale)),
        )
    }

    /** 스크린샷 파일명 순서대로 */
    val all: List<Pair<String, UiState>> = seated(listOf(
        "01_주행중_홈" to drivingHome,
        "02_제안_환기" to proposalStale,
        "03_제안_졸음" to proposalDrowsy,
        "04_실행중" to running,
        "05_쉼모드" to resting,
        "06_도착카드" to arrival,
        "07_정차_설정" to idle,
        "09_제안_혼자" to proposalSolo,
        "10_예고_맡기기" to announce,
        "11_도착카드_맡기기질문" to arrivalTrust,
        "12_자가진단" to diagnosis,
        "13_제안_동승자" to proposalPassenger,
        "14_조용_한도초과" to quietOverLimit,
    ))
    const val COMPARE_NAME = "08_인원비교"
}

private const val W = 1280
private const val H = 720

@Preview(name = "01 주행중 홈", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewDrivingHome() = KkaeugiScreen(PreviewStates.drivingHome, dark = false)

@Preview(name = "02 제안 환기", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewProposalStale() = KkaeugiScreen(PreviewStates.proposalStale, dark = false)

@Preview(name = "03 제안 졸음", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewProposalDrowsy() = KkaeugiScreen(PreviewStates.proposalDrowsy, dark = false)

@Preview(name = "04 실행중", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewRunning() = KkaeugiScreen(PreviewStates.running, dark = false)

@Preview(name = "05 쉼모드", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewResting() = KkaeugiScreen(PreviewStates.resting, dark = false)

@Preview(name = "06 도착카드", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewArrival() = KkaeugiScreen(PreviewStates.arrival, dark = false)

@Preview(name = "07 정차 설정", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewIdle() = KkaeugiScreen(PreviewStates.idle, dark = false)

@Preview(name = "08 인원 비교", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewOccupantCompare() = OccupantCompare(PreviewStates.aloneHome, PreviewStates.fourHome, dark = false)

@Preview(name = "09 제안 혼자", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewProposalSolo() = KkaeugiScreen(PreviewStates.proposalSolo, dark = false)

@Preview(name = "10 예고 맡기기", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewAnnounce() = KkaeugiScreen(PreviewStates.announce, dark = false)

@Preview(name = "11 도착카드 맡기기 질문", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewArrivalTrust() = KkaeugiScreen(PreviewStates.arrivalTrust, dark = false)

@Preview(name = "12 자가 진단", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewDiagnosis() = KkaeugiScreen(PreviewStates.diagnosis, dark = false)

@Preview(name = "13 제안 동승자", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewProposalPassenger() = KkaeugiScreen(PreviewStates.proposalPassenger, dark = false)

@Preview(name = "14 조용 한도초과", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewQuietOverLimit() = KkaeugiScreen(PreviewStates.quietOverLimit, dark = false)

@Preview(name = "15 숨 설계 홈", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewBreathHome() = KkaeugiScreen(PreviewStates.breathHome, dark = false)

@Preview(name = "16 도착카드 세 갈래", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewArrivalThreeWay() = KkaeugiScreen(PreviewStates.arrivalThreeWay, dark = false)

@Preview(name = "15 숨 설계 홈 (밤)", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewBreathHomeNight() = KkaeugiScreen(PreviewStates.breathHome, dark = true)

@Preview(name = "02 제안 환기 (밤)", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewProposalNight() = KkaeugiScreen(PreviewStates.proposalStale, dark = true)

@Preview(name = "19 쉼 모드 화면 닫기", widthDp = W, heightDp = H, showBackground = true)
@Composable
private fun PreviewRestingQuiet() = KkaeugiScreen(PreviewStates.restingQuiet, dark = false)

@Preview(name = "17 넓은 창 홈 1700x900", widthDp = 1700, heightDp = 900, showBackground = true)
@Composable
private fun PreviewWideHome() = KkaeugiScreen(PreviewStates.breathHome, dark = false)

@Preview(name = "18 넓은 창 제안 1350x1000", widthDp = 1350, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewWideProposal() = KkaeugiScreen(PreviewStates.proposalStale, dark = false)

package ai.kkaeugi.app.signals

import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.SeatProfile

/**
 * 내장 시연 시나리오(순수 로직, JVM 테스트 대상). 데모 속도(분→초, 숨 모델 60배속) 기준. 졸음 개입에 창문은 넣지 않는다(기본 설정).
 *
 * 순서
 *  기어 P, 혼자, 밖 미세먼지 나쁨 → D → 내기순환 ON → 약 20초 뒤 첫 제안("밖 미세먼지가 나쁨이라 10초만 짧게 바꿀까요?") → 응 → 10초 유지 → 원복
 *  → 밖 공기가 좋은 날로 바꿈(좋음) → 가족 3명이 더 탐(조수석 1, 뒷좌석 2. 모두 4명) → 몇 초 뒤 "4명이 타서… 바깥 공기로 바꿔 둘까요?"(버튼이 조수석 쪽으로) → 응 → 원복 없이 유지
 *  → 밤길 장거리: 하향등 ON, 트립 +120분 → 운전자가 내기순환을 다시 켬, 운전자 주의 경고 ON
 *  → "졸음 경고가 떴어요. 찬 바람 넣고, 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?" → 응 → 30초 유지 → 원복
 *  → 쉼터 300m → P → 쉼 카드(15초)와 "쉬는 동안 바깥 공기로 둘까요?" → 응 → 다 쉼
 *  → D(쉼 끝, 바깥 공기 원복), 목적지까지 4분(환기는 묻지 않음) → P(쉼터 밖) → 도착 카드
 *
 * 답은 시나리오가 바로 누르지 않는다. 캡션으로 "응을 누르세요"라고 알리고, 문장을 다 읽은 뒤 ANSWER_GRACE_MS 가 지나도록
 * 아무도 안 누르면 시나리오가 대신 누른다(엔진의 대기 8초가 끝나기 전에).
 * 맡기기를 켜 둔 채 돌리면 첫 제안이 예고("20초만 바깥 공기로 바꿀게요")로 나온다. 그대로 두면 5초 뒤 실행되고 순서는 같다.
 * 시나리오가 도는 동안 바뀐 맡기기 상태, 탑승 인원, 데모 콘솔의 차 자동 공조와 도착 예정은 저장하지 않고,
 * 끝나거나 중단되면 앱이 시작 전 값으로 되돌린다(DemoSnapshot).
 *
 * 길이: 아무도 누르지 않으면 약 2분 45초(163초), 제안마다 바로 누르면 약 2분 27초(147초). ScenarioTest 가 실제 엔진과 함께 돌려서 잰 값이고 TTS 시간은 글자 수로 추정했다.
 * 이 값은 바깥 미세먼지 없이 잰 것이다. 앱에서는 첫 환기가 10초로 줄어 10초쯤 짧다(v0.7.0).
 * 미세먼지와 시각(outdoorPm, localClock)은 기본 구현이 비어 있다. 넣지 않는 쪽(ScenarioTest)에서는 엔진이 v0.6.2 와 같이 움직인다.
 * 시간으로만 넘어가지 않고 화면 상태(제안이 떴는가, 실행 바가 사라졌는가, 쉼 카운트다운이 끝났는가)를 보고 넘어가므로 TTS 속도가 달라도 순서가 어긋나지 않는다.
 *
 * v0.7.3 부터 앱의 [시연 시나리오]는 familySteps(가족 시나리오)를 돌린다. 위의 steps 는 졸음 경고 장면이 있는 v0.7.2 시나리오로,
 * 회귀 테스트(ScenarioTest)가 그대로 돌린다.
 *
 * 가족 시나리오(familySteps). 자리별 프로필에 따라 묻는 시점과 문장이 달라지는 것, 걷기 쉼, 자리별 도착 카드를 보여 준다
 *  기어 P, 자리 설정(조수석 임산부, 뒷줄 왼쪽 어린이), 혼자, 밖 미세먼지 나쁨 → D → 내기순환 ON
 *  → 약 20초 뒤 첫 제안: 빈자리의 설정은 쓰지 않으니 한도(2,000)에서 "밖 미세먼지가 나쁨이라 10초만 짧게 바꿀까요?" → 응 → 원복
 *  → 밖 공기가 좋은 날로 바꿈 → 조수석, 뒷줄 왼쪽, 뒷줄 오른쪽에 탐(4명)
 *  → 한도의 90%(1,800)에서 "어린이 자리가 있어 조금 일찍 물어요. 4명이 타서 … 바깥 공기로 바꿔 둘까요?" → 응 → 원복 없이 유지
 *  → 트립 +90분(운전석에 앉은 지 90분) → "한 시간 반째 앉아 있어요. 12km 앞 경부선 석적 졸음쉼터에서 내려서 5분 걸을까요?" → 응 → 경유지만 넣음
 *  → 쉼터 300m → P → 걷기 쉼(5초 타이머) → D(앉은 시간은 0 부터, 걷기 쉼 1회) → 목적지 4분 → P → 자리별 도착 카드
 */
object DemoScenario {
    /** 다 읽고 이만큼 지나도록 답이 없으면 시나리오가 "응"을 누른다. 엔진 대기(8초)보다 짧아야 한다 */
    const val ANSWER_GRACE_MS = 6_000L
    /** 버튼 라벨과 문서에 쓰는 길이 표기 */
    const val LENGTH_LABEL = "약 2분 45초"

    /** 시나리오가 보는 화면 상태 */
    data class View(
        val proposalOpen: Boolean = false,
        /** 제안 문장을 다 읽은 시각(단조). 읽는 중이면 null */
        val countdownFrom: Long? = null,
        /** 실행 중 바가 떠 있는가(되돌리는 중 포함) */
        val running: Boolean = false,
        /** 쉼 카드의 남은 초. 쉼 모드가 아니면 null */
        val restRemainingSec: Int? = null,
    )

    /** 시나리오가 누르는 것들. 앱에서는 데모 콘솔(MockSignalSource)과 화면 버튼이 받는다 */
    interface Hands {
        fun gear(g: Gear)
        fun recirculation(on: Boolean)
        fun occupants(n: Int)
        fun fanSpeed(level: Int, max: Int)
        fun windowOpen(percent: Int)
        fun lowBeam(on: Boolean)
        fun addTripMinutes(m: Int)
        fun driverAttention(on: Boolean)
        /** 경유지로 넣은 쉼터 300m 안에 들어옴 */
        fun restWithin()
        /** 제안에 "응" */
        fun answerYes()
        /** 조수석에 사람이 있는가 */
        fun frontPassenger(present: Boolean)
        /** 목적지까지 남은 분. null = 목적지 없음 */
        fun eta(minutes: Int?)
        /** 차가 내외기를 스스로 제어하는가 */
        fun autoClimate(on: Boolean)
        /** 바깥 PM2.5(µg/m³). null = 값 없음. 기본 구현은 아무것도 하지 않는다(넣지 않으면 엔진은 v0.6.2 와 같다) */
        fun outdoorPm(pm25: Double?) {}
        /** 시연용 시각(자정부터 흐른 분). null = 실제 시각. 기본 구현은 아무것도 하지 않는다 */
        fun localClock(minuteOfDay: Int?) {}
        /** 자리별 탑승 값(운전석, 조수석, 뒷줄 왼쪽, 가운데, 오른쪽). null = 자리별 값 없음. 기본 구현은 아무것도 하지 않는다 */
        fun seats(values: List<Boolean?>?) {}
        /** 자리별 프로필(같은 순서). 기본 구현은 아무것도 하지 않는다 */
        fun seatProfiles(profiles: List<SeatProfile>) {}
    }

    sealed interface Wait {
        data class Fixed(val ms: Long) : Wait
        /** 제안이 뜰 때까지. timeoutMs 가 지나면 그냥 넘어간다 */
        data class UntilProposal(val timeoutMs: Long) : Wait
        /** 제안이 닫힐 때까지. 다 읽은 뒤 graceMs, 또는 뜬 뒤 maxMs 가 지나면 시나리오가 "응"을 누른다 */
        data class Answer(val graceMs: Long = ANSWER_GRACE_MS, val maxMs: Long = 30_000L) : Wait
        /** 실행 바가 떴다가 사라질 때까지(원복 끝). 바가 startMs 안에 안 뜨면(거절 등) 넘어간다 */
        data class UntilRunningDone(val timeoutMs: Long, val startMs: Long = 3_000L) : Wait
        /** 쉼 카드의 카운트다운이 0 이 될 때까지. 쉼 카드가 없거나 timeoutMs 가 지나면 넘어간다 */
        data class UntilRestDone(val timeoutMs: Long) : Wait
    }

    class Step(val caption: String, val wait: Wait, val act: (Hands) -> Unit = {})

    fun steps(ventilateSec: Int, drowsySec: Int, restSec: Int): List<Step> = listOf(
        Step("기어 P, 혼자 탑승. 밖 미세먼지는 나쁨(${PM_BAD.toInt()})", Wait.Fixed(1_000)) {
            // 데모 콘솔에서 켜 둔 차 자동 공조와 도착 예정이 남아 있으면 환기 제안이 나오지 않는다. 먼저 끈다
            it.autoClimate(false); it.eta(null); it.frontPassenger(false)
            // 첫 장면은 미세먼지가 나쁜 낮이다: 한도에 닿을 때까지 기다렸다가 짧게 바꾼다
            it.localClock(DAY_MINUTE); it.outdoorPm(PM_BAD)
            it.occupants(1); it.fanSpeed(3, 8); it.windowOpen(0); it.lowBeam(false); it.driverAttention(false); it.recirculation(false); it.gear(Gear.PARK)
        },
        Step("기어 D. 혼자 출발해요", Wait.Fixed(1_500)) { it.gear(Gear.DRIVE) },
        Step("내기순환 ON. 혼자일 때 추정치는 천천히 올라요(약 20초)", Wait.UntilProposal(45_000)) { it.recirculation(true) },
        Step("첫 제안이에요. 미세먼지가 나빠 짧게 바꾸자고 해요. \"응\"을 누르세요(안 누르면 대신 눌러요)", Wait.Answer()),
        Step("바깥 공기로 짧게(${ventilateSec / 2}초). 끝나면 원래대로 돌려요", Wait.UntilRunningDone((ventilateSec + 15) * 1000L)),
        // 원복 문장을 읽을 시간과 환기 직후 쿨다운(데모 5초)이 지난 뒤에 가족을 태운다
        Step("원래대로 돌렸어요. 이번에는 밖 공기가 좋은 날(${PM_GOOD.toInt()})이에요. 곧 가족 3명이 타요", Wait.Fixed(7_000)) { it.outdoorPm(PM_GOOD) },
        Step("조수석 1명, 뒷좌석 2명이 탔어요(4명). 같은 차인데 추정치가 빨리 올라요", Wait.UntilProposal(45_000)) { it.frontPassenger(true); it.occupants(4) },
        Step("이번에는 \"바꿔 둘까요?\"예요. 버튼이 조수석 쪽에 있어요. \"응\"을 누르세요", Wait.Answer()),
        Step("바깥 공기로 둬요. 원복은 없어요", Wait.Fixed(4_000)),
        // 바깥 공기로 추정치가 충분히 내려가고, 앞 문장을 읽은 지 10초가 지난 뒤에 졸음 경고를 넣는다
        Step("밤길 장거리: 새벽 3시, 하향등 ON, 트립 +120분(졸리기 쉬운 시간대)", Wait.Fixed(6_500)) { it.localClock(NIGHT_MINUTE); it.lowBeam(true); it.addTripMinutes(120) },
        Step("운전자가 내기순환을 다시 켰어요. 운전자 주의 경고 ON → 졸음 제안", Wait.UntilProposal(30_000)) { it.recirculation(true); it.driverAttention(true) },
        Step("졸음 제안이에요. \"응\"을 누르세요", Wait.Answer()) { it.driverAttention(false) },
        Step("찬 바람, 쉼터 경유지(창문은 열지 않아요). ${drowsySec}초 뒤 원래대로", Wait.UntilRunningDone((drowsySec + 15) * 1000L)),
        Step("쉼터 도착(300m 안)", Wait.Fixed(1_500)) { it.restWithin() },
        Step("기어 P → 쉼 모드(${restSec}초). 내기순환이라 바깥 공기로 둘지 물어요", Wait.UntilProposal(4_000)) { it.gear(Gear.PARK) },
        Step("쉬는 동안 바깥 공기로 둘까요? \"응\"을 누르세요", Wait.Answer()),
        Step("쉬는 중이에요. 추정은 잠시 멈춰요", Wait.UntilRestDone((restSec + 10) * 1000L)),
        Step("다 쉬었어요", Wait.Fixed(2_000)),
        Step("기어 D → 쉼 끝, 바깥 공기는 원래대로. 목적지까지 4분", Wait.Fixed(4_500)) { it.gear(Gear.DRIVE); it.eta(4) },
        Step("도착 4분 전이라 환기는 묻지 않아요. 기어 P → 도착 카드", Wait.Fixed(500)) { it.gear(Gear.PARK); it.eta(null); it.frontPassenger(false) },
    )

    /** 가족 시나리오의 자리 설정: 조수석 임산부, 뒷줄 왼쪽 어린이. 나머지는 기본 */
    val FAMILY_PROFILES = listOf(SeatProfile.DEFAULT, SeatProfile.PREGNANT, SeatProfile.CHILD, SeatProfile.DEFAULT, SeatProfile.DEFAULT)
    /** 혼자 탄 자리 값과 가족이 탄 자리 값(운전석, 조수석, 뒷줄 왼쪽, 가운데, 오른쪽) */
    val SEATS_SOLO = listOf<Boolean?>(true, false, false, false, false)
    val SEATS_FAMILY = listOf<Boolean?>(true, true, true, false, true)
    /** 가족 시나리오에서 한 번에 넘기는 트립 시간(분). 임산부 자리의 쉬는 간격(90분)에 닿게 한다 */
    const val FAMILY_TRIP_JUMP_MIN = 90
    /** 가족 시나리오의 길이 표기(FamilyScenarioTest 가 잰 값) */
    const val FAMILY_LENGTH_LABEL = "약 2분"

    /**
     * v0.7.3 가족 시나리오. 데모 속도 기준이고 walkBreak 가 켜져 있어야 걷기 쉼 장면이 나온다(앱은 켜 둔다).
     * 프로필은 설정이다. 이 시나리오가 보여 주는 것은 "설정에 따라 묻는 순서와 여유가 달라진다"는 것뿐이다
     */
    fun familySteps(ventilateSec: Int): List<Step> = listOf(
        Step("기어 P. 자리 설정: 조수석 임산부, 뒷줄 왼쪽 어린이. 지금은 혼자, 밖 미세먼지 나쁨(${PM_BAD.toInt()})", Wait.Fixed(1_500)) {
            it.autoClimate(false); it.eta(null); it.frontPassenger(false)
            it.localClock(DAY_MINUTE); it.outdoorPm(PM_BAD)
            it.seatProfiles(FAMILY_PROFILES); it.seats(SEATS_SOLO)
            it.occupants(1); it.fanSpeed(3, 8); it.windowOpen(0); it.lowBeam(false); it.driverAttention(false); it.recirculation(false); it.gear(Gear.PARK)
        },
        Step("기어 D. 혼자 출발해요. 빈자리의 설정은 쓰지 않아요", Wait.Fixed(1_500)) { it.gear(Gear.DRIVE) },
        Step("내기순환 ON. 혼자일 때는 한도(2,000)에 닿아야 물어요(약 20초)", Wait.UntilProposal(45_000)) { it.recirculation(true) },
        Step("첫 제안이에요. 미세먼지가 나빠 짧게 바꾸자고 해요. \"응\"을 누르세요(안 누르면 대신 눌러요)", Wait.Answer()),
        Step("바깥 공기로 짧게(${ventilateSec / 2}초). 끝나면 원래대로 돌려요", Wait.UntilRunningDone((ventilateSec + 15) * 1000L)),
        Step("원래대로 돌렸어요. 이번에는 밖 공기가 좋은 날(${PM_GOOD.toInt()})이에요. 곧 가족이 타요", Wait.Fixed(7_000)) { it.outdoorPm(PM_GOOD) },
        Step("조수석(임산부), 뒷줄 왼쪽(어린이), 뒷줄 오른쪽에 탔어요(4명). 이제 한도의 90%에서 물어요", Wait.UntilProposal(45_000)) {
            it.frontPassenger(true); it.seats(SEATS_FAMILY); it.occupants(4)
        },
        Step("평소보다 일찍(한도의 90%) 물었어요. 버튼이 조수석 쪽에 있어요. \"응\"을 누르세요", Wait.Answer()),
        Step("바깥 공기로 둬요. 원복은 없어요", Wait.Fixed(4_000)),
        Step("장거리: 운전석에 앉은 지 ${FAMILY_TRIP_JUMP_MIN}분(트립 +${FAMILY_TRIP_JUMP_MIN}분). 임산부 자리가 있어 ${FAMILY_TRIP_JUMP_MIN}분마다 쉬어요", Wait.UntilProposal(40_000)) {
            it.addTripMinutes(FAMILY_TRIP_JUMP_MIN)
        },
        Step("걷기 쉼 제안이에요. \"응\"을 누르면 쉼터를 경유지에 넣어요(공조는 건드리지 않아요)", Wait.Answer()),
        Step("쉼터를 경유지에 넣었어요", Wait.Fixed(4_000)),
        Step("쉼터 도착(300m 안)", Wait.Fixed(1_500)) { it.restWithin() },
        Step("기어 P → 걷기 쉼. 차에서 내려 걸어요(데모 속도라 5초)", Wait.UntilRestDone(20_000)) { it.gear(Gear.PARK) },
        Step("다 걸었어요. 5분 넘게 서 있었으니 걷기 쉼 1회로 적어요", Wait.Fixed(2_500)),
        Step("기어 D → 다시 출발. 앉은 시간은 0 부터 다시 세요. 목적지까지 4분", Wait.Fixed(4_500)) { it.gear(Gear.DRIVE); it.eta(4) },
        Step("기어 P → 도착 카드. 자리마다 한 줄씩 적어요", Wait.Fixed(500)) { it.gear(Gear.PARK); it.eta(null); it.frontPassenger(false) },
    )

    /**
     * 단계 진행기. tick(now, view) 를 0.2초쯤마다 부른다. 단계가 바뀌면 그 단계의 캡션을 돌려준다(안 바뀌면 null).
     * 끝나면 finished = true 가 되고 END_CAPTION 을 한 번 돌려준다.
     */
    class Runner(private val steps: List<Step>, private val hands: Hands) {
        var index = -1
            private set
        var finished = false
            private set
        private var stepStartedAt = 0L
        private var sawRunning = false
        private var pressed = false

        fun tick(now: Long, view: View): String? {
            if (finished) return null
            if (index < 0) return enter(0, now)
            val elapsed = now - stepStartedAt
            val done = when (val w = steps[index].wait) {
                is Wait.Fixed -> elapsed >= w.ms
                is Wait.UntilProposal -> view.proposalOpen || elapsed >= w.timeoutMs
                is Wait.Answer -> {
                    if (!view.proposalOpen) true
                    else {
                        val read = view.countdownFrom
                        if (!pressed && ((read != null && now - read >= w.graceMs) || elapsed >= w.maxMs)) { pressed = true; hands.answerYes() }
                        false
                    }
                }
                is Wait.UntilRestDone -> view.restRemainingSec == null || view.restRemainingSec <= 0 || elapsed >= w.timeoutMs
                is Wait.UntilRunningDone -> {
                    if (view.running) sawRunning = true
                    (sawRunning && !view.running) || (!sawRunning && elapsed >= w.startMs) || elapsed >= w.timeoutMs
                }
            }
            return if (done) enter(index + 1, now) else null
        }

        private fun enter(i: Int, now: Long): String {
            if (i >= steps.size) { finished = true; index = steps.size; return END_CAPTION }
            index = i
            stepStartedAt = now
            sawRunning = false
            pressed = false
            steps[i].act(hands)
            return steps[i].caption
        }
    }

    const val END_CAPTION = "시나리오 끝"
    /** 시나리오가 넣는 바깥 PM2.5(µg/m³): 첫 장면은 나쁨, 가족이 탈 때는 좋음. 실제 값이 아니라 장면용이다 */
    const val PM_BAD = 41.0
    const val PM_GOOD = 9.0
    /** 시나리오가 넣는 시각(자정부터 흐른 분): 낮 2시가 아닌 오전 11시(졸리기 쉬운 시간대가 아님), 밤길 장면은 새벽 3시 */
    const val DAY_MINUTE = 11 * 60
    const val NIGHT_MINUTE = 3 * 60
}

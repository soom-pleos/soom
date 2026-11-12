package ai.kkaeugi.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 쉼터 표시 이름. 이름이 "쉼터"나 "휴게소"로 끝나지 않으면 " 졸음쉼터"를 붙인다.
 * 엔진 문장, RestStarted.stopName, 도착 카드 로그가 이 이름을 쓴다. 앱도 화면과 TTS, 경유지 이름에 같은 함수를 쓴다.
 */
fun restStopDisplayName(name: String): String {
    val n = name.trim()
    if (n.isEmpty()) return "졸음쉼터"
    return if (n.endsWith("쉼터") || n.endsWith("휴게소")) n else "$n 졸음쉼터"
}

/**
 * 숨 개입 상태기계. 순수 Kotlin, Android 의존 없음. 계약은 Contract.kt(v4).
 *
 * 흐름: 트리거 조건 성립 → 큐 → (발화 게이트 통과) Propose → (SpeechFinished) 동의 대기 → Execute → ActionResult 대기
 *       → Running 카운트다운 → Execute(Revert) → ActionResult(Revert) → Done
 *       제어가 전부 실패한 경우에도 Execute(Revert) 를 먼저 내고(늦게 성공한 제어가 남지 않게) Done 으로 끝낸다.
 *       원복 없는 변형(바깥 공기로 두기, 풍량 올리기)은 Execute → ActionResult → Done 으로 끝난다(Running, Revert 없음).
 *
 * 앱은 신호를 accept() 로 넣고, 1초마다 tick() 을 부른다. 두 함수가 돌려주는 WakeEvent 를 순서대로 처리하면 된다.
 * 한 번에 하나의 제안/실행만 진행한다. 그 사이 들어온 트리거는 큐에 쌓였다가 Done 뒤에 나간다.
 * 한 배치에 운전자에게 들리는 문장(Propose/Done/ProposalExpired)은 하나만 나간다.
 * Done, RestEnded, ProposalExpired 처럼 문장이 있는 이벤트를 낸 뒤에는 그 문장을 읽을 시간(글자당 0.2초, 최소 2초) 동안 새 제안을 내지 않는다.
 *
 * 시계: Signal.t 와 tick(now) 는 같은 단조 시계. 뒤로 가도 터지지 않게 모든 차이는 0 이하를 0 으로 자른다.
 *
 * 쉼 모드: 경유지로 넣은 쉼터(waypoint) 300m 안(RestStopProximity.within)에서 기어 P 가 오면 세션을 끝내지 않고 쉰다.
 *          RestStarted → 매 tick RestTick → 기어 D/R 에 RestEnded. TripState 는 DRIVING 그대로다(앱 호환).
 *          쉬고 나면 그 쉼터는 끝(waypoint = null). 다음 쉼은 새 AddRestStop 이 성공한 뒤에만 가능하다.
 *          쉬는 동안 숨 모델은 멈춘다(전진, 최고치, 한도 초과 누적, AirEstimate 모두). 쉼이 끝나면 문을 열고 내렸다고 보고 처음 값으로 되돌린다.
 *
 * 숨 모델(CabinAirModel): 탑승 인원, 내기순환, 풍량, 창문으로 실내 CO2 를 추정한다. 주행 중 accept/tick 마다 전진한다.
 *          값은 전부 추정이고 AirEstimate 로 앱에 알린다. demoFast 면 60배로 흐르되, 동의 대기와 실행 결과 대기 중에는 실제 속도로 흐른다. 한도는 co2LimitPpm, 야간 장거리(하향등이 켜진 지 3분 이상이거나 출발 때 이미 켜져 있었음, 트립 longTripMinutes 이상)면 그 0.8배다.
 *          환기 제안(STALE_AIR)의 변형은 제안하는 순간에 고른다:
 *            FAN_UP         외기인데 창문이 닫혀 있고 추정치가 한도 위(지금 풍량으로는 내려가지 않는다). 풍량을 올릴지 묻는다. 원복 없음
 *            KEEP_AFTER_TWO 2명 이상이 "2분만" 환기를 두 번 끝냈다. 계속 바깥 공기로 둘지 묻는다. 원복 없음
 *            KEEP           내기순환에서 한도에 닿았고, 2분 환기 뒤 내기순환으로 돌아가면 5분 안에 다시 닿는다고 계산된다. 원복 없음
 *            KEEP_LOW       (v0.7.4) 바깥 미세먼지가 나쁨 이상인데 3명 이상이 타서, 짧게 바꾸고 되돌려도 5분 안에 다시 한도에 닿는다고 계산된다.
 *                           "짧게 여러 번" 대신 약한 바람으로 바깥 공기를 계속 들일지 한 번 묻는다. 원복 없음
 *            REVERT         내기순환에서 한도에 닿았다. "2분만" 바꾸고 원복한다
 *            TIME_CAP       내기순환 시간 상한(창문이 닫혀 있을 때만). "2분만" 바꾸고 원복한다
 *          바깥 공기로 두기를 거절하면 다음 제안은 다시 "2분만" 이다. 동의한 뒤 운전자가 직접 내기순환을 켜면 거절로 본다.
 *
 * 맡기기(v4): 운전자가 맡겨 두면(TrustState.delegated) "2분만" 환기(REVERT 변형)는 묻지 않고 Announce 로 예고한다.
 *          SpeechFinished 부터 5초 안에 거절(UserAnswer(false))이 없으면 Execute 로 넘어가고 그 뒤는 같다. 예고 창의 "응" 은 바로 실행이다.
 *          원복이 없는 변형(KEEP, KEEP_AFTER_TWO, KEEP_LOW, FAN_UP), 시간 상한, 졸음 개입은 맡겨도 항상 묻는다.
 *          예고를 취소하거나 맡긴 환기 중에 운전자가 직접 내기순환을 켜면 취소 1회다. 그 주행은 다시 묻고, 취소가 2회 쌓이면 맡기기가 꺼진다.
 *          묻는 방식의 "2분만" 에 동의해 원복까지 끝낸 횟수가 연속 3회면 도착 카드(ArrivalSummary.trustOffer)에서 맡길지 묻는다.
 *          상태는 엔진이 저장하지 않는다. 앱이 setTrust 로 넣고 TrustChanged 를 받아 저장한다.
 *          v0.6.1: 예고는 앱 화면이 보일 때만 한다(안 보이면 맡겼어도 Propose 로 묻는다). 예고 창이 끝나 실행하려는 순간
 *          보류 사유(차 자동 공조, 도착 임박, 하향등), 발화 게이트의 차단 사유(깜빡이, 경고등, 회전 임박), 조용 모드, 화면 상태를 다시 보고
 *          하나라도 걸리면 실행하지 않고 Done(null) 로 닫는다(취소로 세지 않음, 5분 쉼). 예고 문장을 읽기에 너무 이른 SpeechFinished 는
 *          읽기 실패로 보고 Propose 로 다시 묻는다. 예고로 시작한 실행 중의 UserAnswer(false) 는 즉시 원복 + 취소 1회다.
 *          예고나 예고로 시작한 환기 중에 졸음 경고가 오면 예고는 닫고(환기는 조용히 원복하고) 졸음 제안을 바로 낸다.
 *
 * 묻지 않는 때(v4): 한 주행에서 환기 제안을 두 번 연속 거절하면 Quiet 를 한 번 내고 그 주행에서는 환기 제안을 내지 않는다(졸음 제안은 낸다).
 *          목적지까지 5분 이하, 차가 내외기를 스스로 제어하는 중, 주행 중 하향등이 켜진 지 3분 안이면 환기 제안(예고 포함)을 미룬다(Deferred 는 사유마다 한 번).
 *          앱 화면이 안 보이는 동안 만료된 제안은 거절로도 무응답으로도 세지 않고 5분만 쉰다. 두 번 연속이면 거절과 같은 쿨다운을 건다.
 *          조용 모드는 쉼터에서 쉬고 나면 풀린다. 조용 모드 중 추정치가 유효 한도의 2배를 넘으면 Quiet 로 한 번만 알린다(주행당 1회).
 *          운전자가 화면에서 직접 누른 환기는 requestVentilation 으로 받는다(묻지 않고 Execute → Running → Revert).
 *
 * 쉼 모드의 바깥 공기 질문(v4): 내기순환인 채로(엔진이 바꿔 둔 중이었으면 바꾸기 전이 내기순환일 때만) 쉼 모드에 들어가면 RestStarted 뒤에 Propose 로 한 번 묻는다(답 대기 20초, 화면에서 누른다).
 *          동의하면 Execute(Ventilate) 를 내고 결과 뒤 Done(null) 로 화면만 닫는다. Running 없이 쉬는 동안 유지하고,
 *          쉼이 끝나면 RestEnded 다음에 Execute(Revert), Done(null) 을 조용히 낸다. 거절과 무응답은 Done(null) 뿐이고 어디에도 세지 않는다.
 *
 * 핸들 신호: WakeConfig.steeringAssist 가 켜져 있을 때만 본다(기본은 끔). "오래 무조작 뒤 급보정" 횟수만 센다. 속도를 알 때는 60km/h 이상에서만, 무조작 구간이 직진(5도 안쪽)일 때만 센다.
 *          보조 신호일 뿐이라 문장에서 졸음이라고 단정하지 않고 졸음 경고 횟수에도 넣지 않는다.
 *
 * 숨 설계(v0.7.0): CO2 와 바깥 미세먼지를 저울질한다. 바깥 값(OutdoorAir)이 없거나 60분 넘게 묵었으면 아래는 전부 꺼지고 v0.6.2 와 같다.
 *          한도를 정하는 순서: 기본 한도(co2LimitPpm) → 야간 장거리면 0.8배(일찍 묻는 여유) → 바깥 미세먼지가 나쁨 이상이면 그 여유를 없애고 기본 한도로.
 *          기본 한도 자체는 어떤 등급에서도 넘기지 않는다.
 *            좋음       2분 환기 뒤 10분 안에 다시 닿으면(평소는 5분) 바깥 공기로 둘지 묻는다
 *            보통       v0.6.2 와 같다
 *            나쁨       일찍 묻지 않는다(야간 0.8배, 야간 시간 상한 20분을 쓰지 않는다). 바깥 공기로 두자는 제안(KEEP, KEEP_AFTER_TWO)을 하지 않는다.
 *                       "2분만" 대신 필요한 길이(기본 길이의 절반, 4분의 3, 전부 = 1분, 1분 30초, 2분)를 계산해 그 길이를 말하고,
 *                       추정치가 목표(한도의 60%)까지 내려오면 말한 시간이 안 됐어도 되돌린다(가장 짧은 길이는 지킨다)
 *                       v0.7.4: 3명 이상이고 짧게 바꾼 뒤 다시 한도에 닿기까지가 환기 뒤 쉬는 시간(5분)보다 짧으면, 되돌리는 쪽으로는
 *                       한도를 지킬 수 없다(4명이면 약 2분 만에 다시 닿아 주행의 절반 넘게 한도 위에 머문다). 그때는 KEEP_LOW 로 한 번 묻는다:
 *                       바깥 공기로 두되 풍량을 한도의 KEEP_LOW_STEADY_FRACTION(75%) 아래로 유지되는 가장 약한 단으로 맞춘다
 *            매우 나쁨  나쁨과 같고, 추정치가 한도 아래면 내기순환 시간 상한으로도 묻지 않고 기다린다
 *          졸음 개입, 풍량 올리기, 쉼 모드의 바깥 공기 질문, 화면에서 직접 누른 환기는 등급과 무관하게 그대로다.
 *          같은 입력으로 "내내 내기만", "내내 외기만" 두 그림자를 함께 굴려 도착 카드(ArrivalSummary.threeWay)에 싣는다.
 *          설계, 예보, 그림자, 낮잠 안내는 breath(now) 로 읽는다(새 이벤트는 없다).
 *          졸리기 쉬운 시간대 가중(LocalClock 과 연속 주행 시간)은 표시와, 졸음 제안에서 쉼터 경유를 먼저 실행하는 순서에만 쓴다.
 *          한도를 낮추는 규칙은 "야간 장거리 0.8배" 그대로다. 졸음을 알아내는 기능이 아니다.
 *
 * 자리별로 줄인다(v0.7.3): 몸을 재지 않는다. 차 안에서 쌓이는 것(탁한 공기, 미세먼지, 앉아 있는 시간)을 자리별로 적고 줄인다.
 *          아래는 전부 설정이다. 프로필이 전부 기본이고 WakeConfig.walkBreak 가 꺼져 있으면(기본값) 이벤트는 v0.7.2 와 하나도 다르지 않다.
 *          자리별 프로필(SeatProfilesChanged): 탄 자리의 프로필만 쓴다(SeatPolicy).
 *            미세먼지 먼저(호흡기 주의, 심혈관 주의)  바깥 등급을 한 단계 나쁘게 보고 위의 등급별 규칙을 고른다(보통이면 나쁨처럼 늦추고 짧게)
 *            공기 여유 넓게(어린이, 임산부)        환기를 묻는 한도를 기본 한도의 airMarginFraction(90%)로 당긴다
 *            더 자주 쉬기(임산부, 어르신, 심혈관 주의) 쉬는 간격을 sitBreakMinutesShort(90분)로 줄인다
 *          한도 순서: 기본 한도 × min(야간 장거리 0.8(미세먼지 규칙이 "늦추고 짧게"면 없앤다), 공기 여유 넓게 0.9(언제나 남는다)).
 *          어떤 조합에서도 기본 한도보다 높아지지 않는다.
 *          앉은 시간(SitClock): 자리마다 연속 착석을 센다. 운전석은 주행을 시작하면 앉은 것으로 보고 좌석 값은 쓰지 않는다(운전석 값이 비어 오는 차가 있다).
 *            그 자리가 sitEmptyResetMinutes(2분) 이상 비었거나, 기어 P 로 sitParkResetMinutes(5분) 이상 서 있었으면 일어선 것으로 보고 0 부터 다시 센다.
 *            문 신호(DoorChanged)가 한 번이라도 온 차에서는 기어 P 로 서 있는 동안 문이 열렸다 닫혔을 때만 일어선 것으로 본다.
 *            기어 P 로 잠깐 섰다 다시 출발하면(새 세션) 앉은 시간은 이어진다.
 *          걷기 쉼(WALK_BREAK, walkBreak 를 켰을 때만): 가장 오래 앉은 자리가 쉬는 간격에 닿고 전방에 새로 넣을 쉼터가 있으면
 *            "두 시간째 앉아 있어요. 12km 앞 … 졸음쉼터에서 내려서 5분 걸을까요?" 를 묻는다. 동의하면 Execute(AddRestStop) → 결과 → Done 으로 끝난다(유지 구간과 원복이 없다).
 *            쉼터가 없거나 목적지가 더 가까우면 묻지 않고 기다린다. 한 번 앉은 동안 MAX_WALK_ASKS_PER_SIT 번까지만 묻는다.
 *            졸음 제안과 겹치면 하나로 합친다: 졸음 제안 문장에 "두 시간째 앉아 있기도 해요." 를 넣고 걷기 쉼은 따로 묻지 않는다.
 *            걷기 쉼을 묻던 중에 졸음 경고가 오면 그 질문을 조용히 닫고 합친 졸음 제안을 바로 낸다.
 *            걷기 쉼으로 넣은 쉼터에서는 쉼 타이머가 walkBreakMinutes(5분)이고 RestStarted.walk 가 true 다.
 *            쉼터에서 일어섰으면(위 규칙) 걷기 쉼 1회로 적는다. 실제로 걸었는지는 알 수 없다.
 *          자리별 누적(SeatLedger): 탄 자리마다 한도 위 시간, 평균 CO2, 실내 PM2.5 노출, 가장 긴 연속 착석, 걷기 쉼을 쌓아 도착 요약(ArrivalSummary.seats)에 싣는다.
 *            자리별 값이 없는 주행은 차 전체 한 줄(ArrivalSummary.cabin)만 쓴다. 지금 살피는 것과 앉은 시간은 care(now) 로 읽는다.
 *
 * 설정 교체(updateConfig): 진행 중인 타이머(동의 대기 만료, Running 종료, 쉼 목표)는 그대로 두고 새 값은 다음 단계부터 쓴다.
 */
class WakeEngine(config: WakeConfig) {

    var config: WakeConfig = config
        private set

    // ---- 개입 단계 ----
    private enum class Phase { IDLE, AWAITING_ANSWER, ANNOUNCING, EXECUTING, RUNNING, REVERTING }

    /** 쉼 모드에서 바깥 공기로 둘지 묻는 흐름. 주행 중 개입 단계(Phase)와 따로 간다 */
    private enum class RestAir { NONE, ASKING, EXECUTING, HOLDING }

    /** 환기 제안의 변형. 이름이 그대로 lastProposalVariant 로 나간다 */
    private enum class StaleVariant { REVERT, KEEP, KEEP_AFTER_TWO, KEEP_LOW, FAN_UP, TIME_CAP }

    /** 졸음 제안이 어디서 왔는가. 문장 첫머리와 졸음 경고 집계가 달라진다 */
    private enum class DrowsySource { TELLTALE, STEER }

    private val session = TripSession()
    private val gate = SpeechGate()

    private var phase = Phase.IDLE
    private var currentKind: TriggerKind? = null
    private var currentActions: List<Action> = emptyList()
    private var phaseSince = 0L
    /** 동의 대기: 제안 시각, TTS 끝난 시각, 제안 시점에 고정한 대기 시간과 여유 */
    private var proposedAt = 0L
    private var speechFinishedAt: Long? = null
    private var answerTimeoutMs = 0L
    private var speechGraceMs = 0L
    /** 실행 결과(액션 클래스별) */
    private val results = mutableMapOf<String, ActionResult>()
    /** 실행 결과 대기 상한(Execute 시점에 고정) */
    private var executeTimeoutAtStartMs = 0L
    /** 실행 중 유지되고 있는(성공한) 제어 액션 */
    private var runningControls: List<Action> = emptyList()
    private var runEndsAt = 0L
    /** 원복이 끝났을 때 쓸 Done 문장. null 이면 원복 결과에 따라 고른다 */
    private var pendingDoneText: String? = null
    /** 실행 결과가 모이는 중에 자동 개입이 꺼졌다. 결과가 모이면 유지하지 않고 바로 원복 */
    private var revertWhenResolved = false

    /** 아직 말하지 못한 제안. 종류별로 하나만 들어간다 */
    private val queue = mutableListOf<TriggerKind>()
    private var deferredKind: TriggerKind? = null
    /** 큐에 들어간 DROWSY 의 출처와, 지금 제안/실행 중인 DROWSY 의 출처. 텔테일이 오면 텔테일이 우선이다 */
    private var queuedDrowsySource = DrowsySource.TELLTALE
    private var currentDrowsySource = DrowsySource.TELLTALE

    // ---- 설정/모드 ----
    private var enabledFlag = true
    /** accept/tick 에서 마지막으로 본 시각. now 를 받지 않는 공개 API(setEnabled, updateConfig)가 쓴다 */
    private var lastSeenNow = 0L

    // ---- 차량 상태 ----
    private var recirculating = false
    /** 밀폐 구간(내기순환, 창문 닫힘)이 시작된 시각. 시간 상한이 여기서부터 센다 */
    private var recircOnSince: Long? = null
    private var staleTriggered = false
    private var wiperOn = false
    private var lowBeamOn = false
    private var tripTimeReportedMs = 0L
    /** 마지막 LocationUpdate 의 속도. 모르면 null */
    private var lastSpeedKph: Double? = null

    // ---- 계약 v4: 맡기기, 조용 모드 ----
    private var trustState = TrustState()
    /** 이번 주행에서 예고나 실행을 한 번 취소했다. 남은 주행은 다시 묻는다 */
    private var delegationSuspended = false
    /** 이번 주행에서 취소가 쌓여 맡기기가 꺼졌다. 도착 카드 맨 앞 줄에 알린다 */
    private var trustTurnedOff = false
    /** 지금 개입이 예고(Announce)로 시작됐다 */
    private var announced = false
    /** 지금 환기 유지 중에 운전자가 직접 내기순환을 켰다 */
    private var driverOverrode = false
    /** 이번 주행에서 환기 제안을 연달아 거절한 횟수(무응답은 세지도 끊지도 않는다) */
    private var staleDeclineStreak = 0
    /** 이번 주행에서는 환기 제안을 내지 않는다 */
    private var quiet = false
    /** 앱 화면이 운전자에게 보이는가 */
    private var appVisible = true
    /** 화면이 안 보이는 동안 제안이 연달아 만료된 횟수. 화면이 다시 보이면 0 */
    private var hiddenExpiryStreak = 0
    /** 조용 모드에 들어간 시각 */
    private var quietSince = 0L
    /** 조용 모드에서 추정치가 한도의 2배를 넘었다고 이미 알렸다(주행당 한 번) */
    private var quietOverLimitSaid = false
    /** 예고로 시작한 실행(또는 직접 요청한 환기)을 운전자가 취소했다. 원복이 끝나면 취소 문장으로 닫는다 */
    private var runCancelled = false
    /** 지금 원복이 끝나면 말없이 닫는다(졸음 경고에 자리를 내준 환기) */
    private var silentDone = false
    /** 지금 환기는 운전자가 화면에서 직접 요청했다. 맡기기 집계에 넣지 않는다 */
    private var userRequested = false
    /** 말없이 닫은 직후라 같은 배치에서 바로 다음 제안을 내도 된다(졸음 제안) */
    private var urgentPropose = false
    /** 실행을 시작할 때 내기순환이었는가. 쉼 진입 질문은 개입 전이 내기순환일 때만 한다 */
    private var recircAtExecute = false
    /** 이번 주행에서 "2분만" 연속 동의가 늘었다. 도착 카드의 맡기기 질문은 이때만 싣는다 */
    private var yesGainedThisTrip = false
    /** 쉼 모드의 바깥 공기 질문 단계와 결과 대기 시작 시각 */
    private var restAir = RestAir.NONE
    private var restAirSince = 0L

    // ---- 계약 v4: 도착 예정, 동승자, 차의 자동 내외기, 하향등 ----
    /** 내비 목적지까지 남은 분. 목적지가 없거나 모르면 null */
    private var etaRaw: Int? = null
    /** 남은 분을 받은 시각. ETA_STALE_MINUTES 가 지나면 낡은 값으로 보고 쓰지 않는다 */
    private var etaAt = 0L
    private val etaMinutes: Int?
        get() = etaRaw?.takeIf { since(etaAt, lastSeenNow) <= unitMs(ETA_STALE_MINUTES) }
    private var passengerPresent = false
    /** 이번 주행에서 "옆자리에서 눌러 주셔도 돼요" 를 이미 붙였다 */
    private var passengerHintSaid = false
    /** 차가 내외기를 스스로 제어하는 중 */
    private var carAuto = false
    /** 하향등이 켜진 시각. 꺼져 있으면 null */
    private var lowBeamSince: Long? = null
    /** 주행을 시작할 때(또는 쉬고 나올 때) 이미 켜져 있었다. 켜진 지 얼마 안 됐어도 야간으로 보고 제안을 미루지 않는다 */
    private var lowBeamSettled = false
    /** 이번 주행에서 이미 알린 환기 제안 보류 사유(Deferred 는 사유마다 한 번) */
    private val holdsSaid = mutableSetOf<String>()

    // ---- 숨 모델 ----
    private val air = CabinAirModel(config)
    private var occupants = 1
    private var fanLevel = 0
    private var fanMax = 0
    private var windowPercent = 0
    /** 세션 중 추정 최고 ppm, 기본 한도 위에 머문 모델 시간(ms). 쉬는 동안은 쌓지 않는다 */
    private var peakPpm = 0.0
    private var overLimitModelMs = 0.0
    private var lastAirAdvanceAt: Long? = null
    /** 마지막으로 낸 AirEstimate 와 그 시각. null 이면 이번 세션에서 아직 안 냈다 */
    private var lastAirEvent: WakeEvent.AirEstimate? = null
    private var lastAirEventAt = 0L
    /** 쉼이 끝난 직후처럼 값이 한 번에 바뀌었을 때, 다음 기회에 AirEstimate 를 바로 낸다 */
    private var forceAirEmit = false
    /** 직전 세션이 끝난 시각. 곧바로 다시 출발하면 추정치를 이어 간다 */
    private var lastEndedAt: Long? = null
    /** 환기 유지 구간을 시작할 때와 원복을 시작할 때의 추정 ppm. 원복 문장의 수치는 원복 시작 시점 값이다 */
    private var ventStartPpm = 0.0
    private var revertStartPpm = 0.0
    /** 이번 세션에서 유지 구간까지 간 STALE_AIR 환기 횟수(원복까지 끝난 것만). 바깥 공기로 두기를 거절하면 0 으로 돌아간다 */
    private var staleVentsFinished = 0
    /** 지금 개입이 STALE_AIR 환기 유지 구간에 들어갔다. 원복이 끝나면 staleVentsFinished 로 센다 */
    private var heldStaleVent = false
    /** 지금 환기 제안의 변형. STALE_AIR 가 아니면 null */
    private var currentVariant: StaleVariant? = null
    /** 운전자가 동의해서 바깥 공기로 두는 중. 내기순환으로 다시 바뀌면 풀린다 */
    private var keepingFresh = false
    /** 엔진의 개입(바깥 공기로 두기, 풍량 올리기) 뒤로 외기 상태가 이어지는 중. 바깥 공기 분을 여기서 센다 */
    private var freshOpen = false
    /** 바깥 공기로 두기를 거절했다. 다음 환기 제안 한 번은 재도달 예측을 건너뛰고 "2분만" 으로 묻는다 */
    private var skipRecurrenceOnce = false
    /** 풍량 올리기를 실행했는데 차가 알려 준 풍량이 아직 그대로다. 풍량이 바뀌기 전에는 같은 제안을 다시 하지 않는다 */
    private var fanUpAwaitingChange = false
    /** 실행을 시작할 때의 풍량(풍량 올리기 뒤에 실제로 바뀌었는지 본다) */
    private var fanLevelAtExecute = 0
    private var fanMaxAtExecute = 0

    // ---- 숨 설계(v0.7.0) ----
    /** 마지막으로 받은 바깥 PM2.5(µg/m³)와 출처, 받은 시각, 그때의 나이(분). 없으면 null */
    private var outdoorPm: Double? = null
    private var outdoorSource = ""
    private var outdoorAt = 0L
    private var outdoorAgeMin = 0
    /** 이번 주행에서 마지막으로 쓴 바깥 값(도착 카드용) */
    private var lastOutdoorUsed: Double? = null
    /** 실내 PM2.5 추정(실제 주행) */
    private val pm = CabinPmModel(config)
    /** 그림자 두 갈래(내내 내기만, 내내 외기만) */
    private val shadow = Counterfactual(config)
    /** 기침 자리 감염 위험(v0.8.0, 실제 주행). 기침하지 않는 동승자 한 명 기준 */
    private val bio = InfectionModel(config)
    /** 이번 환기 질문이 기침 자리 때문인가(문장 고르기용) */
    private var coughAsk = false
    /** 이번 주행에서 기침하지 않는 동승자가 가장 많았던 수 */
    private var coughOthersMax = 0
    /** 지나온 곡선과, 주행을 시작하고 흐른 모델 시간(분, 쉼 제외) */
    private val trace = BreathTrace()
    private var modelMinutes = 0.0
    /** 지금 시각(자정부터 흐른 분)과 그 값을 받은 때. 모르면 null */
    private var clockMinuteOfDay: Int? = null
    private var clockAt = 0L
    /** 마지막으로 쉼이 끝난 시각. 연속 주행 시간을 여기서부터 센다 */
    private var lastRestEndedAt: Long? = null
    /** 지금 환기 제안의 "필요한 만큼" 길이(초)와 목표 ppm. 미세먼지 나쁨 이상일 때만 있다. null 이면 기본 길이 */
    private var plannedVentSec: Int? = null
    private var ventTargetPpm: Double? = null
    /** 지금 환기 제안을 낼 때의 미세먼지 등급(문장용). 나쁨 이상일 때만 */
    private var proposalPmGrade: PmGrade? = null

    // ---- 자리별 프로필, 앉은 시간, 자리별 누적(v0.7.3) ----
    /** 자리별 프로필(Seats 순서). 사용자가 고른 설정이다 */
    private var seatProfiles: List<SeatProfile> = SeatPolicy.normalize(emptyList())
    /** 차가 준 자리별 탑승 값. 자리별 값이 없으면(수동 입력) null */
    private var seatValues: List<Boolean?>? = null
    private val sit = SitClock()
    private val ledger = SeatLedger()
    /** 마지막으로 본 기어와, 기어 P 로 서기 시작한 시각 */
    private var gearNow = Gear.UNKNOWN
    private var parkedSince: Long? = null
    /** 문 신호가 한 번이라도 왔는가, 지금 열려 있는가, 이번 정차에서 열린 적이 있는가(연 채로 P 에 넣은 것도 센다) */
    private var doorKnown = false
    private var doorOpen = false
    private var doorOpenedWhileParked = false
    /** 지금 앉은 동안 걷기 쉼을 물은 횟수. 일어서면 0 */
    private var walkAsks = 0
    /** 지금 경유지는 걷기 쉼으로 넣은 쉼터다. 지금 쉼은 걷기 쉼이다 */
    private var waypointForWalk = false
    private var restIsWalk = false
    /** 이번 주행에서 쉼터에서 일어선 횟수 */
    private var walkBreaks = 0
    /** 지금 쉼터 경유는 사용자가 직접 요청했다(requestRestStop). 걷기 쉼 집계와 쿨다운에 넣지 않는다 */
    private var restStopRequested = false
    /** 이번 주행에서 "… 자리가 있어 조금 일찍 물어요" 를 이미 붙였다 */
    private var marginPrefixSaid = false
    /** 지금 환기 제안이 "미세먼지 먼저" 설정 때문에 짧아졌다면 그 자리 이름("호흡기 주의"). 아니면 null */
    private var proposalPmFirstLabel: String? = null
    /** 이번 주행의 가장 많았던 탑승 인원 */
    private var tripMaxOccupants = 0

    // ---- 핸들 신호 ----
    private var turnSignalOn = false
    private var lastTurnSignalAt: Long? = null
    /** 무조작 구간: 시작 시각, 각도 범위, 마지막 표본 */
    private var steerQuietSince: Long? = null
    private var steerQuietMin = 0f
    private var steerQuietMax = 0f
    private var steerLastQuietDeg = 0f
    private var steerLastQuietAt = 0L
    /** 급보정 판정 창: 무조작이 깨진 시각과 그 직전 각도. null 이면 판정 중이 아니다 */
    private var steerJerkSince: Long? = null
    private var steerJerkBase = 0f
    private val steerEventTimes = ArrayDeque<Long>()
    private var steerDrowsyFired = 0

    // ---- 쉼터 ----
    private var lastCandidate: RestStopInfo? = null
    /** 경유지로 넣은 쉼터. 지나치거나 들르면 null */
    private var waypoint: RestStopInfo? = null
    private var restWithin = false
    private var resting = false
    private var restStartedAt = 0L
    private var restStopName = ""
    /** 쉼 진입 시점에 고정한 목표 시간과 표시 단위(ms) */
    private var restTargetMs = 0L
    private var restUnitMs = 60_000L
    /** 이번 세션에서 쉰 시간 합(ms). 주행 시간에서 뺀다 */
    private var restTotalMs = 0L

    // ---- 쿨다운, 횟수 ----
    private val cooldownUntil = mutableMapOf<TriggerKind, Long>()
    private var drowsyProposals = 0
    private var nightPrefixSaid = false

    // ---- 도착 카드 재료 ----
    private var ventilations = 0
    /** 차의 졸음 경고(운전자 주의 경고등)가 켜진 횟수. 핸들 경로는 세지 않는다 */
    private var drowsySignals = 0
    private var restStopsAdded = 0
    private var restStopsVisited = 0
    private var restMinutesTotal = 0
    private var restStopsPassed = 0
    private var declined = 0
    private var freshAirMinutes = 0
    private var ventOnSince: Long? = null
    private val log = mutableListOf<Pair<Long, String>>()

    // ---- 읽기 프로퍼티 (디버그/표시용) ----
    val tripState: TripState get() = session.state
    val enabled: Boolean get() = enabledFlag
    /** 쉼 모드 여부. TripState 는 DRIVING 그대로다 */
    val isResting: Boolean get() = resting
    var lastProposalText: String? = null
        private set
    val phaseName: String get() = phase.name
    val currentWaypoint: RestStopInfo? get() = waypoint
    /** 숨 모델 추정 ppm(디버그/표시용) */
    val estimatedPpm: Int get() = air.ppm()
    /** 최근 창(10분, demoFast 면 60초) 안의 핸들 급보정 횟수(디버그용) */
    val steerEvents: Int get() = steerEventTimes.count { since(it, lastSeenNow) <= steerWindowMs() }
    /** 지금 쓰는 환기 제안 한도(ppm). 기본은 co2LimitPpm, 야간 장거리면 NIGHT_LIMIT_FACTOR 배 */
    val effectiveLimitPpm: Int get() = effectiveLimit(lastSeenNow).roundToInt()
    /** 목적지까지 ARRIVING_SOON_MINUTES 분 이하로 남았다. 이 동안 환기 제안은 내지 않는다 */
    val arrivingSoon: Boolean get() = etaMinutes?.let { it <= ARRIVING_SOON_MINUTES } ?: false
    /** 차가 내외기를 스스로 제어하는 중이다. 이 동안 환기 제안은 내지 않고 졸음 개입에서도 내외기는 건드리지 않는다 */
    val carAutoActive: Boolean get() = carAuto
    /** 마지막 환기 제안 때 계산한 재도달 예측(분, 모델 시간). 풍량 올리기 제안이었거나 다시 닿지 않으면 null */
    var lastRecurrenceMinutes: Double? = null
        private set
    /** 맡기기 상태. 바뀌면 TrustChanged 로도 알린다 */
    val trust: TrustState get() = trustState
    /** 이번 주행에서 환기 제안을 두 번 연속 거절해 더 묻지 않는 중 */
    val quietMode: Boolean get() = quiet
    /** 조용 모드라 묻지 않는 중인데 내기순환인 채로 추정치가 유효 한도 이상이다. 앱은 홈에 한도 초과 문구와 "바깥 공기로" 버튼을 보여 준다.
     *  바깥 공기가 들어오는 중(외기 모드, 엔진이 환기를 유지하는 중)이면 false */
    val overLimitWhileQuiet: Boolean
        get() = quiet && session.state == TripState.DRIVING && !resting && effectiveRecirculating() &&
            air.ppmExact() >= effectiveLimit(lastSeenNow)
    /** 지금 개입이 예고로 시작했거나 운전자가 직접 요청한 환기라 실행 중에도 취소(UserAnswer(false))를 받는다 */
    val cancellableRun: Boolean
        get() = (announced || userRequested) && !runCancelled && (phase == Phase.EXECUTING || phase == Phase.RUNNING)
    /** 마지막 제안의 변형: "REVERT", "KEEP", "KEEP_AFTER_TWO", "KEEP_LOW", "FAN_UP", "TIME_CAP". 졸음 제안이면 "DROWSY",
     *  맡겨 둔 환기의 예고면 "ANNOUNCE", 쉼 모드의 바깥 공기 질문이면 "REST", 아직 없으면 null */
    var lastProposalVariant: String? = null
        private set

    /** 지금 쓰고 있는 바깥 미세먼지 등급. 값이 없거나 묵었으면 null(이때 엔진은 v0.6.2 와 같다) */
    val outdoorGrade: PmGrade? get() = pmGrade(lastSeenNow)
    /** 마지막 환기 제안(예고 포함) 때의 설계. durationSec 가 그때 말한 길이다. 아직 없으면 null */
    var lastVentPlan: VentPlan? = null
        private set

    // ---------- 공개 API ----------

    /**
     * 숨 설계 한 묶음을 읽는다(v0.7.0). 읽기 전용이고 엔진 상태를 바꾸지 않는다. 앱은 tick 뒤에 불러 화면에 쓴다.
     * 전부 추정이다: 환기 설계("이 인원이면 N분마다 M초"), 여정 예보(목적지까지, 없으면 60분 창), 지나온 곡선과 그림자,
     * 졸리기 쉬운 시간대 가중, 쉬는 중이면 낮잠 안내
     */
    fun breath(now: Long = lastSeenNow): BreathDesign {
        val limit = effectiveLimit(now)
        val venting = ventActive()
        val baseFan = if (venting) fanLevelAtExecute else fanLevel
        val cooldown = POST_VENT_COOLDOWN_MINUTES.toDouble()
        val plan = ventPlan(now)
        val eta = etaMinutes
        val horizon = eta ?: VentPlanner.FORECAST_WINDOW_MIN
        val forecast = if (session.state == TripState.DRIVING && !resting) {
            val leftMs = if (venting) (runEndsAt - now).coerceAtLeast(0L) else 0L
            VentPlanner.forecast(
                air, config, limit, plan, horizon, toArrival = eta != null,
                recirculating = if (venting) recircAtExecute else recirculating,
                cooldownMin = cooldown, arrivingSoonMin = ARRIVING_SOON_MINUTES,
                ventRemainingMin = leftMs.toDouble() / unitMs(1), baseFanLevel = baseFan,
            )
        } else Forecast(horizon, toArrival = eta != null, ventCount = 0, firstVentInMin = null)
        val outdoor = validOutdoor(now)?.let {
            OutdoorView(it, VentPlanner.grade(it), outdoorSource, outdoorAgeMin + (since(outdoorAt, now) / 60_000L).toInt(), pm.pm())
        }
        // 곡선은 30초마다 한 점이라 마지막 점이 지금보다 조금 늦다. 지금 값을 끝에 붙여 곡선이 게이지 숫자에서 끝나게 한다
        val sampled = trace.snapshot()
        val driving = session.state == TripState.DRIVING && !resting
        val points = if (driving && (sampled.isEmpty() || modelMinutes > sampled.last().minute + 1e-9)) {
            sampled + TracePoint(modelMinutes, air.ppm(), shadow.recircOnly.air.ppm())
        } else sampled
        return BreathDesign(
            outdoor = outdoor, plan = plan, forecast = forecast, trace = points,
            shadowRecircPpm = shadow.recircOnly.air.ppm(), shadowFreshPpm = shadow.freshOnly.air.ppm(),
            alertness = alertness(now), nap = napGuide(now),
        )
    }

    /**
     * 환기 설계만 계산한다: 지금 인원과 풍량(엔진이 바꿔 둔 중이면 바꾸기 전 풍량), 지금 한도, 바깥 미세먼지 등급으로 낸 듀티 사이클.
     * 환기 직후 POST_VENT_COOLDOWN_MINUTES 는 다시 묻지 않는 규칙도 간격에 들어 있다
     */
    fun ventPlan(now: Long = lastSeenNow): VentPlan {
        val grade = policyGrade(now)
        val planning = air.copy().also { it.fanLevel = if (ventActive()) fanLevelAtExecute else fanLevel }
        val plan = VentPlanner.plan(planning, config, effectiveLimit(now), grade, keepThresholdMinutes(grade), POST_VENT_COOLDOWN_MINUTES.toDouble())
        // "미세먼지 먼저" 설정 때문에 짧게 바꾸는 것이면 이유를 그렇게 적는다(바깥 등급 자체는 나쁨이 아니다)
        val shifted = VentPlanner.shortMode(grade) && !VentPlanner.shortMode(pmGrade(now)) && plan.intervalMin != null && !plan.keepFresh
        return if (shifted) plan.copy(reason = VentPlanner.REASON_PM_FIRST) else plan
    }

    /**
     * 이번 주행에서 살피는 것(v0.7.3). 읽기 전용. 탄 자리의 프로필에서 나온 설정과, 지금 가장 오래 앉아 있는 자리의 연속 착석이다.
     * 몸을 잰 값이 아니다. 좌석 값과 기어, 문 신호로 센 시간이다
     */
    fun care(now: Long = lastSeenNow): SeatCare {
        val aboard = aboardProfiles()
        val longest = sit.longest(now)
        return SeatCare(
            pmFirst = SeatPolicy.pmFirst(aboard),
            wideMargin = SeatPolicy.wideMargin(aboard),
            breakMinutes = if (config.walkBreak) SeatPolicy.breakMinutes(aboard, config) else null,
            sitMinutes = longest?.let { (it.second / unitMs(1)).toInt() } ?: 0,
            sitSeat = longest?.first,
        )
    }

    /** 지금 넣어 둔 자리별 프로필(Seats 순서) */
    val profiles: List<SeatProfile> get() = seatProfiles

    /** 졸리기 쉬운 시간대 가중. 시각(LocalClock)을 못 받았으면 하향등으로만 어림한다. 졸음을 알아낸 값이 아니다 */
    fun alertness(now: Long = lastSeenNow): Alertness =
        AlertnessWeight.weigh(minuteOfDay(now), continuousDriveMinutes(now), config.longTripMinutes, nightHint = isNight(now))

    /**
     * 앱이 저장해 둔 맡기기 상태를 넣는다(앱 시작 때, 설정 화면에서 바꿨을 때). TrustChanged 는 내지 않는다.
     * 맡기기를 끄면 진행 중인 예고를 닫는다(Done(null), 조건이 남아 있으면 곧 Propose 로 묻는다).
     * 맡기기를 켜면 그 주행의 "다시 묻기" 상태를 푼다. 돌려준 이벤트를 앱이 처리해야 한다
     */
    fun setTrust(state: TrustState, now: Long = lastSeenNow): List<WakeEvent> {
        val out = mutableListOf<WakeEvent>()
        val was = trustState.delegated
        trustState = state.copy(
            consecutiveYes = state.consecutiveYes.coerceAtLeast(0),
            cancelCount = state.cancelCount.coerceAtLeast(0),
        )
        if (was && !trustState.delegated && phase == Phase.ANNOUNCING) {
            out += WakeEvent.Done(currentKind!!, null)
            staleTriggered = false
            clearPhase()
            gate.clearCooldown()
        }
        if (!was && trustState.delegated) delegationSuspended = false
        return out
    }

    /**
     * 운전자가 화면에서 직접 "바깥 공기로" 를 눌렀다(조용 모드에서 한도 초과일 때 보이는 버튼).
     * 묻지 않고 Execute(Ventilate) 를 내고, 그 뒤는 동의한 "2분만" 환기와 같다(Running → Revert → Done).
     * 주행 중이 아니거나, 쉬는 중이거나, 다른 개입이 진행 중이거나, 이미 바깥 공기면 아무것도 하지 않는다.
     * 맡기기 집계와 거절 집계에는 넣지 않는다
     */
    fun requestVentilation(now: Long = lastSeenNow): List<WakeEvent> {
        val out = mutableListOf<WakeEvent>()
        lastSeenNow = maxOf(lastSeenNow, now)
        if (session.state != TripState.DRIVING || resting || phase != Phase.IDLE || !effectiveRecirculating()) return out
        advanceAir(now)
        queue.remove(TriggerKind.STALE_AIR)
        staleTriggered = false
        currentKind = TriggerKind.STALE_AIR
        currentVariant = StaleVariant.REVERT
        currentActions = listOf(Action.Ventilate())
        userRequested = true
        beginExecute(now, out)
        syncAir()
        emitAir(now, out, timed = false)
        return out
    }

    /**
     * 사용자가 직접 "쉬었다 가자"고 했다(v0.7.3. 음성 비서나 화면에서 온 요청). 묻지 않고 전방 쉼터를 경유지로 넣는다:
     * Execute(WALK_BREAK, AddRestStop) → 결과 → Done. 유지 구간과 원복이 없다.
     * 주행 중이 아니거나, 쉬는 중이거나, 다른 개입이 진행 중이거나, 새로 넣을 쉼터가 없으면(후보 없음, 이미 경유지, 목적지가 더 가까움)
     * 아무것도 하지 않는다. 걷기 쉼을 물은 횟수에는 넣지 않고, 이렇게 넣은 쉼터의 쉼 타이머는 보통 쉼(restMinutes)이다
     */
    fun requestRestStop(now: Long = lastSeenNow): List<WakeEvent> {
        val out = mutableListOf<WakeEvent>()
        lastSeenNow = maxOf(lastSeenNow, now)
        if (session.state != TripState.DRIVING || resting || phase != Phase.IDLE) return out
        val stop = drowsyStop() ?: return out
        queue.remove(TriggerKind.WALK_BREAK)
        currentKind = TriggerKind.WALK_BREAK
        currentVariant = null
        currentActions = listOf(Action.AddRestStop(stop))
        restStopRequested = true
        beginExecute(now, out)
        return out
    }

    /** 지금 직접 요청으로 넣을 수 있는 전방 쉼터. 없으면 null(후보 없음, 이미 경유지, 목적지가 더 가까움) */
    val requestableRestStop: RestStopInfo? get() = drowsyStop()

    /**
     * 자동 개입 ON/OFF. OFF 로 바꾸는 순간:
     *  - 동의 대기 중이면 조용히 닫는다(Done(kind, null))
     *  - Running 중이면 즉시 Execute(Revert) 로 원복을 시작한다
     *  - 결과 대기(EXECUTING) 중이면 결과가 모인 뒤 유지하지 않고 바로 원복한다
     * 돌려준 이벤트를 앱이 처리해야 한다. now 를 생략하면 마지막으로 본 시각을 쓴다
     */
    fun setEnabled(on: Boolean, now: Long = lastSeenNow): List<WakeEvent> {
        val out = mutableListOf<WakeEvent>()
        if (enabledFlag == on) return out
        enabledFlag = on
        if (!on) {
            queue.clear()
            deferredKind = null
            queuedDrowsySource = DrowsySource.TELLTALE
            if (resting && restAir == RestAir.ASKING) {
                restAir = RestAir.NONE
                out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
            }
            when (phase) {
                Phase.AWAITING_ANSWER, Phase.ANNOUNCING -> {
                    out += WakeEvent.Done(currentKind!!, null)
                    if (currentKind == TriggerKind.STALE_AIR) staleTriggered = false
                    clearPhase()
                }
                Phase.RUNNING -> startRevert(now, out)
                Phase.EXECUTING -> revertWhenResolved = true
                else -> {}
            }
        }
        return out
    }

    /**
     * 엔진 재생성 없이 설정 교체. 내기순환/와이퍼/하향등/세션 상태는 유지된다.
     * 진행 중인 타이머는 그대로 두고 새 설정은 다음 단계부터 쓴다.
     * 실제 → 데모 전환이면 내기순환 카운터를 지금부터 다시 세서 전환 즉시 환기 제안이 나가는 것을 막는다
     */
    fun updateConfig(c: WakeConfig, now: Long = lastSeenNow) {
        val toDemo = !config.demoFast && c.demoFast
        if (session.state == TripState.DRIVING && !resting) advanceAir(now)
        config = c
        air.config = c
        pm.config = c
        shadow.config = c
        bio.config = c
        if (toDemo && recirculating) recircOnSince = now
    }

    fun accept(s: Signal): List<WakeEvent> {
        val out = mutableListOf<WakeEvent>()
        lastSeenNow = maxOf(lastSeenNow, s.t)
        // 입력이 바뀌기 전에 숨 모델을 이 시각까지 전진시킨다. 쉬는 동안은 멈춘다
        if (session.state == TripState.DRIVING && !resting) advanceAir(s.t)
        acceptInner(s, out)
        syncAir()
        syncSeats(s.t)
        // 신호 쪽에서는 인원/환기 상태가 바뀐 때만 알린다. 주기 알림은 tick 이 맡는다
        emitAir(s.t, out, timed = false)
        noteSpoken(s.t, out)
        return out
    }

    private fun acceptInner(s: Signal, out: MutableList<WakeEvent>) {
        val wasDriving = session.state == TripState.DRIVING

        // 앉은 시간: 기어 P 로 오래 서 있다가 움직이면 일어섰던 것으로 본다. 세션과 쉼 모드보다 먼저 가린다
        val stood = s is GearChanged && onGearForSit(s)

        // 쉼 모드 진입/종료는 세션보다 먼저 본다
        if (s is GearChanged && wasDriving) {
            if (!resting && s.gear == Gear.PARK && restWithin && waypoint != null) {
                enterRest(s.t, out)
                return
            }
            if (resting) {
                if (s.gear == Gear.DRIVE || s.gear == Gear.REVERSE) {
                    exitRest(s.t, out, stood)
                    session.accept(s)
                }
                // 쉼 중 N/P 등은 세션에 넘기지 않는다(기어 D/R 로만 나간다)
                return
            }
        }
        // 쉼 중 주차 브레이크도 세션 종료 조건으로 쓰지 않는다
        val parkingBrakeWhileResting = resting && s is TelltaleChanged && s.type == Telltale.PARKING_BRAKE
        if (!parkingBrakeWhileResting) session.accept(s)?.let { onTripStateChanged(it, s.t, out) }

        // 게이트 입력은 세션과 무관하게 항상 반영
        when (s) {
            is TurnSignalChanged -> {
                gate.onTurnSignal(s.state != TurnSignalState.OFF)
                turnSignalOn = s.state != TurnSignalState.OFF
                lastTurnSignalAt = s.t
            }
            is TelltaleChanged -> gate.onTelltale(s.type, s.on, s.blink)
            is NavTurnAhead -> gate.onTurnAhead(s.turn, s.distanceM)
            else -> {}
        }

        // 차량 상태 추적(IDLE 에서도 기억해 두었다가 세션 시작 때 쓴다)
        when (s) {
            is RecirculationChanged -> onRecirculation(s, out)
            is WiperChanged -> wiperOn = s.on
            is LowBeamChanged -> onLowBeam(s)
            is DestinationEta -> { etaRaw = s.remainMinutes?.coerceAtLeast(0); etaAt = s.t }
            is FrontPassengerChanged -> passengerPresent = s.present
            is AutoClimateChanged -> carAuto = s.autoRecirculation
            is AppVisibility -> { appVisible = s.visible; if (s.visible) hiddenExpiryStreak = 0 }
            is TrustAnswer -> onTrustAnswer(s, out)
            is TripTimeUpdate -> {
                tripTimeReportedMs = s.minutes * 60_000L
                // 앱이 늦게 켜졌거나 다시 떴을 때: 차가 알려 준 주행 시간만큼은 운전석에 앉아 있었다고 본다(일어선 뒤로는 당기지 않는다)
                if (session.state == TripState.DRIVING && !resting) {
                    syncSeats(s.t)
                    sit.backdate(Seats.DRIVER, s.t - unitMs(s.minutes.coerceAtLeast(0)))
                }
            }
            is SeatOccupancyChanged -> seatValues = s.seats?.let { v -> List(Seats.COUNT) { v.getOrNull(it) } }
            is SeatProfilesChanged -> seatProfiles = SeatPolicy.normalize(s.profiles)
            is DoorChanged -> onDoor(s)
            is RestStopCandidate -> lastCandidate = s.stop
            is RestStopProximity -> onProximity(s)
            is OccupantsChanged -> occupants = s.count.coerceIn(1, CabinAirModel.MAX_OCCUPANTS)
            is FanSpeedChanged -> onFanSpeed(s)
            is WindowOpenChanged -> onWindow(s)
            is LocationUpdate -> lastSpeedKph = s.speedKph
            is OutdoorAir -> {
                outdoorPm = s.pm25UgM3?.takeIf { it >= 0.0 && it < PM_MAX_PLAUSIBLE }
                outdoorSource = s.source
                outdoorAt = s.t
                outdoorAgeMin = s.ageMin.coerceAtLeast(0)
            }
            is LocalClock -> { clockMinuteOfDay = s.minuteOfDay; clockAt = s.t }
            else -> {}
        }

        if (resting) {
            // 쉬는 동안은 바깥 공기 질문의 답과 결과만 본다
            when (s) {
                is UserAnswer -> onRestAnswer(s, out)
                is SpeechFinished -> if (restAir == RestAir.ASKING && speechFinishedAt == null && s.t >= proposedAt + SPEECH_ECHO_MS) speechFinishedAt = s.t
                is ActionResult -> onRestResult(s, out)
                else -> {}
            }
        }
        if (session.state == TripState.DRIVING && wasDriving && !resting) {
            when (s) {
                is TelltaleChanged -> onTelltaleDriving(s, out)
                is SteeringAngle -> if (config.steeringAssist) onSteering(s)
                is UserAnswer -> onAnswer(s, out)
                is SpeechFinished -> onSpeechFinished(s, out)
                is ActionResult -> onActionResult(s, out)
                else -> {}
            }
            // 트리거가 모델의 정상상태와 복제본을 보므로, 이 신호로 바뀐 차량 상태를 먼저 모델 입력에 옮긴다
            syncAir()
            checkTriggers(s.t)
            // 이 신호로 이미 할 말(Done 등)이 생겼으면 새 제안은 다음 tick 으로 미룬다. 한 번에 한 문장.
            // 졸음 경고에 자리를 내주느라 말없이 닫은 경우만 같은 배치에서 바로 묻는다
            if (out.isEmpty() || urgentPropose) tryPropose(s.t, out)
            urgentPropose = false
        }
    }

    fun tick(now: Long): List<WakeEvent> {
        val out = mutableListOf<WakeEvent>()
        lastSeenNow = maxOf(lastSeenNow, now)
        // 쉬는 동안은 숨 모델을 멈춘다(문이 열렸는지, 몇 명이 남았는지 모른다)
        if (session.state == TripState.DRIVING && !resting) advanceAir(now)
        tickInner(now, out)
        syncAir()
        syncSeats(now)
        emitAir(now, out, timed = true)
        noteSpoken(now, out)
        return out
    }

    private fun tickInner(now: Long, out: MutableList<WakeEvent>) {
        if (resting) {
            out += WakeEvent.RestTick(restRemainingSec(now))
            when (restAir) {
                // 답이 없으면 아무것도 하지 않고 조용히 닫는다
                RestAir.ASKING -> if (answerExpired(now)) {
                    restAir = RestAir.NONE
                    out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
                }
                // 결과가 안 오면 늦게 성공한 제어가 남지 않게 원복을 내고 닫는다
                RestAir.EXECUTING -> if (since(restAirSince, now) >= executeTimeoutAtStartMs) {
                    restAir = RestAir.NONE
                    out += WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert))
                    out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
                }
                else -> {}
            }
            return
        }
        session.tick(now)?.let { onTripStateChanged(it, now, out) }
        if (session.state != TripState.DRIVING) return

        when (phase) {
            Phase.AWAITING_ANSWER -> if (answerExpired(now)) {
                val kind = currentKind!!
                out += WakeEvent.ProposalExpired(EXPIRED_TEXT)
                if (appVisible) {
                    // 거절과 같은 쿨다운. 쿨다운이 끝나면 조건이 남아 있을 때 다시 묻는다
                    cooldownUntil[kind] = now + unitMs(config.declineCooldownMinutes)
                    if (kind == TriggerKind.STALE_AIR) {
                        onStaleNotTaken()
                        if (currentVariant == StaleVariant.REVERT) resetConsecutiveYes(out)
                    }
                    log += now to "제안 무응답(${label(kind)})"
                } else {
                    // 화면이 안 보여서 답할 수 없었다. 거절로도 무시로도 세지 않고 짧게만 쉰다.
                    // 두 번 연속이면 5분마다 되풀이하지 않게 거절과 같은 쿨다운을 건다
                    hiddenExpiryStreak++
                    val minutes = if (hiddenExpiryStreak >= HIDDEN_EXPIRY_LONG_AFTER) config.declineCooldownMinutes else HIDDEN_EXPIRY_COOLDOWN_MINUTES
                    cooldownUntil[kind] = now + unitMs(minutes)
                    if (kind == TriggerKind.STALE_AIR) staleTriggered = false
                }
                clearPhase()
            }
            // 예고 뒤 취소가 없었다. 실행하려는 지금 다시 본다: 화면이 가려졌거나, 차가 내외기를 스스로 제어하거나,
            // 도착이 가깝거나, 깜빡이·경고등·회전 임박이거나, 조용 모드면 실행하지 않고 조용히 닫는다(취소로 세지 않는다)
            Phase.ANNOUNCING -> if (answerExpired(now)) {
                if (announceBlocked(now)) {
                    val kind = currentKind!!
                    out += WakeEvent.Done(kind, null)
                    cooldownUntil[kind] = now + unitMs(ANNOUNCE_SKIP_COOLDOWN_MINUTES)
                    staleTriggered = false
                    log += now to "예고한 환기를 실행하지 않음"
                    clearPhase()
                } else {
                    beginExecute(now, out)
                }
            }
            Phase.EXECUTING -> if (since(phaseSince, now) >= executeTimeoutAtStartMs) {
                // 못 받은 결과는 실패로 간주
                for (a in currentActions) {
                    results.getOrPut(key(a)) { ActionResult(now, a, ok = false, detail = "TIMEOUT") }
                }
                resolveExecution(now, out)
            }
            Phase.RUNNING -> {
                val remaining = ((runEndsAt - now + 999) / 1000).toInt().coerceAtLeast(0)
                if (remaining > 0 && !ventTargetReached(now)) {
                    out += WakeEvent.Running(currentKind!!, remaining, runningLabel())
                } else {
                    startRevert(now, out)
                }
            }
            Phase.REVERTING -> if (since(phaseSince, now) >= REVERT_TIMEOUT_MS) {
                finish(now, "원복을 확인하지 못했어요.", out)
            }
            else -> {}
        }
        syncAir()
        checkTriggers(now)
        // Done/ProposalExpired 와 같은 배치에 Propose 를 넣지 않는다
        if (out.isEmpty() || urgentPropose) tryPropose(now, out)
        urgentPropose = false
        quietOverLimitNotice(now, out)
    }

    /**
     * 조용 모드라 묻지 않는 동안 추정치가 유효 한도의 QUIET_NOTICE_FACTOR 배를 넘었다. 주행당 한 번만 알린다.
     * 묻는 것이 아니라 알리는 것이라 답을 기다리지 않는다. 말해도 되는 때(발화 게이트, 보류 사유 없음)에만 내고,
     * 조용 모드에 들어간 지 QUIET_NOTICE_MIN_GAP_MINUTES 가 지난 뒤에만 낸다
     */
    private fun quietOverLimitNotice(now: Long, out: MutableList<WakeEvent>) {
        if (!quiet || quietOverLimitSaid || phase != Phase.IDLE || !enabledFlag) return
        // "더 묻지 않을게요" 를 말한 직후에는 바로 다시 말을 걸지 않는다
        if (since(quietSince, now) < unitMs(QUIET_NOTICE_MIN_GAP_MINUTES)) return
        if (out.any { it !is WakeEvent.AirEstimate }) return
        if (!effectiveRecirculating() || air.ppmExact() < effectiveLimit(now) * QUIET_NOTICE_FACTOR) return
        if (staleHoldReason(now) != null || gate.blockedReason(now) != null) return
        quietOverLimitSaid = true
        gate.markSpoken(now)
        log += now to "조용 모드에서 한도의 2배를 넘어 한 번 알림"
        out += WakeEvent.Quiet("차 안 공기가 많이 탁해요(추정 ${ppmText(air.ppm())}ppm). 화면에서 바깥 공기로 바꿀 수 있어요.")
    }

    /** 예고한 환기를 지금 실행하면 안 되는가 */
    private fun announceBlocked(now: Long): Boolean =
        !appVisible || quiet || staleHoldReason(now) != null || gate.hazardReason() != null

    /**
     * 도착 카드를 닫은 뒤. 세션 상태를 비우고 IDLE 로.
     * 숨 모델 추정치와 세션 종료 시각은 남긴다(RESTART_KEEP_MINUTES 안에 다시 출발하면 이어 간다)
     */
    fun reset() {
        session.reset()
        resetSessionFacts()
    }

    /**
     * 재도달 예측. 모델 복제본으로 계산하고 실제 모델은 건드리지 않는다:
     * 지금부터 ventilateSeconds 동안 외기(풍량 +1)로 두었다가 내기순환으로 돌아가면, 지금의 유효 한도에 다시 닿기까지 걸리는 분(모델 시간).
     * 환기가 끝난 시점에도 한도 위면 0.0, 다시 닿지 않으면 null
     */
    fun predictRecurrenceMinutes(): Double? = predictRecurrenceMinutes(effectiveLimit(lastSeenNow))

    private fun predictRecurrenceMinutes(limit: Double): Double? {
        val m = air.copy()
        m.recirculating = false
        m.fanLevel = if (fanMax > 0) minOf(fanLevel + 1, fanMax) else fanLevel + 1
        m.advanceBy(config.ventilateSeconds * 1000L)
        m.recirculating = true
        m.fanLevel = fanLevel
        return m.minutesUntilAbove(limit)
    }

    /**
     * 미세먼지가 나쁨 이상일 때의 같은 계산: "필요한 만큼만" 짧게 바꾸고(목표에 닿으면 되돌린다. 가장 짧은 길이는 지킨다)
     * 내기순환으로 돌아가면 한도에 다시 닿기까지 걸리는 분(모델 시간). 다시 닿지 않으면 null
     */
    private fun predictShortRecurrenceMinutes(limit: Double): Double? {
        val m = VentPlanner.ventingCopy(air)
        val planned = VentPlanner.modelMinutes(VentPlanner.shortVentSeconds(air, config, limit), config)
        val shortest = VentPlanner.modelMinutes(config.ventilateSeconds, config) * VentPlanner.MIN_DURATION_FRACTION
        val toTarget = if (config.demoFast) null else m.minutesUntilBelow(VentPlanner.targetPpm(config, limit))
        m.advanceModelMinutes(toTarget?.coerceIn(shortest, maxOf(shortest, planned)) ?: planned)
        m.recirculating = true
        m.fanLevel = fanLevel
        return m.minutesUntilAbove(limit)
    }

    /** 약한 바람으로 바깥 공기를 계속 들일 때 맞출 풍량 단. 최대 단을 모르면 null(풍량은 그대로 둔다) */
    private fun keepLowFanLevel(now: Long): Int? = VentPlanner.lowFanLevel(air, config, effectiveLimit(now))

    // ---------- 세션 ----------

    private fun onTripStateChanged(state: TripState, now: Long, out: MutableList<WakeEvent>) {
        when (state) {
            TripState.DRIVING -> {
                // 카드를 닫지 않고 다시 출발한 경우도 새 세션으로 시작
                resetSessionFacts()
                // 지난 주행의 남은 시간은 버린다. 앱이 다시 알려 준다
                etaRaw = null
                recircOnSince = if (recirculating) now else null
                // 출발할 때 이미 켜져 있던 하향등은 밤이라서 켠 것으로 본다
                lowBeamSettled = lowBeamOn
                syncAir()
                // 방금 끝난 세션에 곧바로 이어서 출발하면 차 안 공기는 그대로라고 보고 추정치를 이어 간다. 그보다 길면 처음 값으로
                val carryOver = lastEndedAt?.let { since(it, now) <= unitMs(RESTART_KEEP_MINUTES) } ?: false
                if (carryOver) air.rebase(now) else air.reset(now)
                lastAirAdvanceAt = now
                peakPpm = air.ppmExact()
                // 그림자 두 갈래와 실내 미세먼지 추정도 같은 값에서 시작한다
                shadow.start(air)
                pm.reset()
                bio.reset()
                coughOthersMax = 0
                out += WakeEvent.TripStateChanged(state)
            }
            TripState.ENDED -> {
                abortIntervention(now, out)
                // 바깥 공기로 두는 중이었으면 그 구간도 여기서 닫는다
                closeFreshAir(now)
                queue.clear()
                lastEndedAt = now
                // 도착했으니 남은 시간은 버린다. 다음 주행에서 앱이 다시 알려 준다
                etaRaw = null
                out += WakeEvent.TripStateChanged(state)
                out += WakeEvent.ArrivalCard(buildSummary(now))
            }
            TripState.IDLE -> out += WakeEvent.TripStateChanged(state)
        }
    }

    /** 진행 중인 개입을 정리한다(세션 종료, 쉼 진입). EXECUTING 이면 Execute(Revert) 로 남은 액션 취소까지 앱에 맡긴다 */
    private fun abortIntervention(now: Long, out: MutableList<WakeEvent>) {
        when (phase) {
            Phase.EXECUTING, Phase.RUNNING -> {
                closeFreshAir(now)
                out += WakeEvent.Execute(currentKind!!, listOf(Action.Revert))
                out += WakeEvent.Done(currentKind!!, null)
            }
            Phase.AWAITING_ANSWER, Phase.ANNOUNCING, Phase.REVERTING -> {
                closeFreshAir(now)
                out += WakeEvent.Done(currentKind!!, null)
            }
            Phase.IDLE -> {}
        }
        clearPhase()
    }

    private fun resetSessionFacts() {
        clearPhase()
        queue.clear()
        queuedDrowsySource = DrowsySource.TELLTALE
        currentDrowsySource = DrowsySource.TELLTALE
        steerQuietSince = null
        steerJerkSince = null
        steerEventTimes.clear()
        steerDrowsyFired = 0
        peakPpm = 0.0
        overLimitModelMs = 0.0
        trace.clear()
        modelMinutes = 0.0
        lastRestEndedAt = null
        lastOutdoorUsed = null
        lastVentPlan = null
        lastAirAdvanceAt = null
        lastAirEvent = null
        forceAirEmit = false
        // 걷기 쉼을 넘긴 뒤 잠깐 섰다 다시 출발한 것이면(앉은 시간이 이어진다) 그 쿨다운은 남긴다
        val walkCooldown = cooldownUntil[TriggerKind.WALK_BREAK]
        cooldownUntil.clear()
        walkCooldown?.let { cooldownUntil[TriggerKind.WALK_BREAK] = it }
        ledger.reset()
        tripMaxOccupants = 0
        walkBreaks = 0
        marginPrefixSaid = false
        waypointForWalk = false
        restIsWalk = false
        drowsyProposals = 0
        nightPrefixSaid = false
        passengerHintSaid = false
        holdsSaid.clear()
        delegationSuspended = false
        trustTurnedOff = false
        staleDeclineStreak = 0
        quiet = false
        hiddenExpiryStreak = 0
        quietOverLimitSaid = false
        yesGainedThisTrip = false
        urgentPropose = false
        restAir = RestAir.NONE
        staleTriggered = false
        staleVentsFinished = 0
        keepingFresh = false
        freshOpen = false
        skipRecurrenceOnce = false
        fanUpAwaitingChange = false
        lastRecurrenceMinutes = null
        lastProposalVariant = null
        tripTimeReportedMs = 0L
        waypoint = null
        restWithin = false
        resting = false
        restTotalMs = 0L
        ventilations = 0; drowsySignals = 0; restStopsAdded = 0; declined = 0
        restStopsVisited = 0; restMinutesTotal = 0; restStopsPassed = 0
        freshAirMinutes = 0; ventOnSince = null
        log.clear()
    }

    // ---------- 쉼 모드 ----------

    private fun enterRest(now: Long, out: MutableList<WakeEvent>) {
        // 엔진이 바깥 공기로 바꿔 둔 중이었으면 곧 원복되어 내기순환으로 돌아간다
        // 개입 전에 이미 바깥 공기였으면(외기로 달리던 중의 졸음 개입 등) 원복해도 바깥 공기라 물을 것이 없다
        val ventBeingReverted = (phase == Phase.EXECUTING || phase == Phase.RUNNING) &&
            currentActions.any { it is Action.Ventilate } && recircAtExecute
        abortIntervention(now, out)
        queue.clear()
        queuedDrowsySource = DrowsySource.TELLTALE
        steerQuietSince = null
        steerJerkSince = null
        // 바깥 공기로 두는 중이었어도 쉬는 시간은 "바깥 공기로 달린 분" 에 넣지 않는다
        closeFreshAir(now)
        // 숨 모델은 여기서 멈춘다. accept/tick 이 쉬는 동안 전진, 최고치, 한도 초과 누적을 하지 않는다
        resting = true
        restStartedAt = now
        restStopName = waypoint?.let { restStopDisplayName(it.name) } ?: "졸음쉼터"
        // 쉬는 동안 설정이 바뀌어도 목표와 단위는 진입 시점 것을 쓴다.
        // 걷기 쉼으로 넣은 쉼터면 타이머는 걷기 길이(5분)다
        restIsWalk = waypointForWalk
        val targetMinutes = if (restIsWalk) config.walkBreakMinutes else config.restMinutes
        restTargetMs = unitMs(targetMinutes)
        restUnitMs = unitMs(1)
        out += WakeEvent.RestStarted(restStopName, targetMinutes, walk = restIsWalk)
        // 내기순환으로 쉬게 되면 쉬는 동안 바깥 공기로 둘지 한 번 묻는다. 서 있으니 화면에서 눌러 답한다.
        // 차가 내외기를 스스로 제어하는 중이면 묻지 않는다
        restAir = RestAir.NONE
        if (enabledFlag && !carAuto && (recirculating || ventBeingReverted)) {
            restAir = RestAir.ASKING
            proposedAt = now
            speechFinishedAt = null
            answerTimeoutMs = REST_AIR_TIMEOUT_SEC * 1000L
            speechGraceMs = speechGraceSec(REST_AIR_PROPOSAL) * 1000L
            lastProposalText = REST_AIR_PROPOSAL
            lastProposalVariant = "REST"
            out += WakeEvent.Propose(
                TriggerKind.STALE_AIR, REST_AIR_PROPOSAL, listOf(Action.Ventilate(fanDelta = 0)), REST_AIR_TIMEOUT_SEC,
                passengerMayAnswer = passengerPresent,
            )
        }
    }

    /** 쉼 모드의 바깥 공기 질문에 답했다. 거절은 아무것도 하지 않고 세지도 않는다 */
    private fun onRestAnswer(s: UserAnswer, out: MutableList<WakeEvent>) {
        if (restAir != RestAir.ASKING) return
        if (s.yes) {
            restAir = RestAir.EXECUTING
            restAirSince = s.t
            executeTimeoutAtStartMs = executeTimeoutMs()
            log += s.t to "쉬는 동안 바깥 공기로 두기"
            // 서 있는 동안이라 풍량은 올리지 않는다
            out += WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Ventilate(fanDelta = 0)))
        } else {
            restAir = RestAir.NONE
            out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
        }
    }

    /** 쉼 모드에서 바깥 공기로 바꾼 결과. 성공하면 쉼이 끝날 때까지 그대로 둔다(Running 없음). 어느 쪽이든 조용히 닫는다 */
    private fun onRestResult(s: ActionResult, out: MutableList<WakeEvent>) {
        if (restAir != RestAir.EXECUTING || s.action !is Action.Ventilate) return
        restAir = if (s.ok) RestAir.HOLDING else RestAir.NONE
        out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
    }

    /** stood: 이번 쉼에서 일어선 것으로 본다(기어 P 로 오래 서 있었고, 문 신호가 있는 차면 문이 열렸다 닫혔다) */
    private fun exitRest(now: Long, out: MutableList<WakeEvent>, stood: Boolean = false) {
        resting = false
        // 쉼터에서 일어섰으면 걷기 쉼 1회로 적는다(실제로 걸었는지는 알 수 없다). 기록 줄은 더하지 않는다
        if (stood) {
            walkBreaks++
            ledger.noteWalkBreak(SeatPolicy.occupied(seatValues, driving = true))
        }
        restIsWalk = false
        // 쉬고 나면 조용 모드를 푼다. 쉬기 전의 거절은 지난 일로 본다
        quiet = false
        staleDeclineStreak = 0
        val restAirWas = restAir
        restAir = RestAir.NONE
        // 아직 묻는 중이었으면 화면만 닫는다
        if (restAirWas == RestAir.ASKING) out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
        val restedMs = since(restStartedAt, now)
        val restedUnits = (restedMs.toDouble() / restUnitMs).roundToInt()
        val reached = restedMs >= restTargetMs
        val text = when {
            reached -> "${restedUnits}분 푹 쉬셨어요. 조심히 가요"
            restedUnits <= 0 -> "잠깐 쉬셨어요. 다음 쉼터에서 더 쉬어도 좋아요"
            else -> "${restedUnits}분 쉬셨어요. 다음 쉼터에서 더 쉬어도 좋아요"
        }
        restStopsVisited++
        restMinutesTotal += restedUnits.coerceAtLeast(0)
        restTotalMs += restedMs
        // 들른 쉼터는 끝. 다음 쉼은 새 경유지가 들어간 뒤에만. 앱의 within=false 가 안 와도 다음 P 는 도착이다
        waypoint = null
        waypointForWalk = false
        restWithin = false
        // 쉬는 동안 켠 하향등은 출발할 때 켜져 있던 것과 같이 본다
        if (lowBeamOn) lowBeamSettled = true
        // 문을 열고 내렸다고 본다. 추정치는 처음 값으로, 내기순환 카운터도 0 부터
        air.reset(now)
        lastAirAdvanceAt = now
        // 그림자와 실내 미세먼지 추정도 값만 처음으로(누적은 이어 간다). 연속 주행 시간은 여기서부터 다시 센다
        shadow.reopen(air)
        pm.reopen()
        bio.reopen()
        lastRestEndedAt = now
        forceAirEmit = true
        resetStaleCounter(now)
        // 바깥 공기로 두는 중이었으면 구간을 다시 연다
        if (freshOpen && !recirculating) ventOnSince = now
        log += now to "${restStopName}에서 ${restedUnits.coerceAtLeast(0)}분 휴식"
        out += WakeEvent.RestEnded(restedUnits.coerceAtLeast(0), text)
        // 쉬는 동안 바깥 공기로 두었으면 조용히 되돌린다. 들리는 말은 RestEnded 하나다
        if (restAirWas == RestAir.HOLDING || restAirWas == RestAir.EXECUTING) {
            out += WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert))
            out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
        }
    }

    private fun restRemainingSec(now: Long): Int {
        val remainingMs = restTargetMs - since(restStartedAt, now)
        return ((remainingMs + 999) / 1000).toInt().coerceAtLeast(0)
    }

    private fun onProximity(s: RestStopProximity) {
        // 경유지가 없으면 근접 신호는 의미가 없다(앱이 다른 쉼터를 보고 보낸 것일 수 있다)
        val wp = waypoint ?: return
        restWithin = s.within
        // 쉬는 중에 들어온 passed 는 GPS 흔들림으로 본다
        if (s.passed && !resting) {
            restStopsPassed++
            log += s.t to "${restStopDisplayName(wp.name)} 지나침"
            waypoint = null
            waypointForWalk = false
            restWithin = false
        }
    }

    /** 같은 쉼터인가. 이름이 같거나 좌표가 SAME_STOP_RADIUS_M 안이면 같다고 본다 */
    private fun sameStop(a: RestStopInfo, b: RestStopInfo): Boolean {
        if (a.name == b.name) return true
        val dLat = (a.lat - b.lat) * METERS_PER_DEGREE
        val dLng = (a.lng - b.lng) * METERS_PER_DEGREE * cos((a.lat + b.lat) / 2 * DEG_TO_RAD)
        return hypot(dLat, dLng) <= SAME_STOP_RADIUS_M
    }

    // ---------- 트리거 ----------

    private fun onRecirculation(s: RecirculationChanged, out: MutableList<WakeEvent>) {
        if (s.recirculating == recirculating) return
        recirculating = s.recirculating
        if (resting) {
            // 쉬는 동안 운전자가 직접 바꿨다. 묻던 중이면 닫고, 바깥 공기로 두던 중이면 손을 뗀다(나갈 때 되돌리지 않는다)
            if (!recirculating && restAir == RestAir.ASKING) {
                restAir = RestAir.NONE
                out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
            } else if (recirculating && restAir == RestAir.HOLDING) {
                restAir = RestAir.NONE
            }
        }
        if (recirculating) {
            // 엔진이 "2분만" 바깥 공기로 바꿔 둔 중에 운전자가 직접 내기순환을 켰다
            if (ventActive() && currentKind == TriggerKind.STALE_AIR) {
                driverOverrode = true
                log += s.t to "환기 중 내기순환으로 직접 바꿈"
                if (announced) noteCancel(out) else if (currentVariant == StaleVariant.REVERT) resetConsecutiveYes(out)
            }
            recircOnSince = s.t
            // 엔진이 환기를 유지하는 중이 아니면 바깥 공기 구간은 여기서 끝난다
            if (!ventActive()) closeFreshAir(s.t)
            freshOpen = false
            if (keepingFresh) {
                // 바깥 공기로 두기로 한 뒤 운전자가 직접 내기순환을 켰다. 거절로 보고 같은 쿨다운을 건다.
                // 다음 환기 제안은 다시 "2분만" 으로 묻는다
                keepingFresh = false
                cooldownUntil[TriggerKind.STALE_AIR] = maxOf(
                    cooldownUntil[TriggerKind.STALE_AIR] ?: Long.MIN_VALUE,
                    s.t + unitMs(config.declineCooldownMinutes),
                )
                staleVentsFinished = 0
                skipRecurrenceOnce = true
                log += s.t to "바깥 공기로 두던 중 내기순환으로 바뀜"
            }
            // 풍량을 올릴지 묻던 중이면 전제(외기)가 사라졌으니 조용히 닫는다
            if (phase == Phase.AWAITING_ANSWER && currentVariant == StaleVariant.FAN_UP) {
                out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
                staleTriggered = false
                clearPhase()
            }
        } else {
            // 내기순환이 꺼지면 카운터 리셋, 아직 말하지 않은 환기 제안은 버린다
            recircOnSince = null
            staleTriggered = false
            queue.remove(TriggerKind.STALE_AIR)
            // 동의 대기 중인 환기 제안은 조용히 닫는다
            if ((phase == Phase.AWAITING_ANSWER || phase == Phase.ANNOUNCING) && currentKind == TriggerKind.STALE_AIR) {
                out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
                clearPhase()
            }
        }
    }

    private fun onFanSpeed(s: FanSpeedChanged) {
        val level = s.level.coerceAtLeast(0)
        val max = s.max.coerceAtLeast(0)
        // 차가 알려 준 풍량이 바뀌었다. 풍량 올리기를 다시 물을 수 있다
        if (level != fanLevel || max != fanMax) fanUpAwaitingChange = false
        fanLevel = level
        fanMax = max
    }

    private fun onWindow(s: WindowOpenChanged) {
        val wasOpen = windowOpen()
        windowPercent = s.percent.coerceIn(0, 100)
        // 창문을 열어 두었다가 닫았다. 그동안 환기됐다고 보고 시간 상한은 닫은 때부터 다시 센다
        if (wasOpen && !windowOpen() && recirculating) recircOnSince = s.t
    }

    private fun onTelltaleDriving(s: TelltaleChanged, out: MutableList<WakeEvent>) {
        if (!s.on || s.type != Telltale.DRIVER_ATTENTION) return
        if (!enabledFlag) return
        // 경고 자체는 쿨다운/횟수 제한과 무관하게 센다. 같은 경고로 이미 제안이 대기/진행 중이면 한 번으로 본다.
        // 핸들 경로로 나간 제안은 졸음 경고로 센 적이 없으니 여기서 센다
        val telltaleInProgress =
            (TriggerKind.DROWSY in queue && queuedDrowsySource == DrowsySource.TELLTALE) ||
                (currentKind == TriggerKind.DROWSY && currentDrowsySource == DrowsySource.TELLTALE)
        if (!telltaleInProgress) {
            drowsySignals++
            log += s.t to "졸음 경고(운전자 주의 경고)"
        }
        // 텔테일이 보조 트리거보다 우선. 큐에 있던 보조 제안은 차의 경고 문장으로 나간다
        queuedDrowsySource = DrowsySource.TELLTALE
        enqueue(TriggerKind.DROWSY, s.t)
        // 걷기 쉼을 묻던 중이면 그 질문은 조용히 닫고, 앉은 시간 이야기를 합친 졸음 제안을 바로 낸다(같은 쉼터를 두 번 묻지 않는다)
        if (TriggerKind.DROWSY in queue && currentKind == TriggerKind.WALK_BREAK && phase == Phase.AWAITING_ANSWER) {
            out += WakeEvent.Done(TriggerKind.WALK_BREAK, null)
            walkAsks = (walkAsks - 1).coerceAtLeast(0)
            clearPhase()
            gate.clearCooldown()
            urgentPropose = true
        }
        // 맡긴 환기의 예고나 실행이 졸음 제안을 막지 않게 한다. 예고는 닫고, 진행 중인 환기는 조용히 원복한 뒤 바로 묻는다
        if (TriggerKind.DROWSY in queue && announced && currentKind == TriggerKind.STALE_AIR) {
            when (phase) {
                Phase.ANNOUNCING -> {
                    out += WakeEvent.Done(TriggerKind.STALE_AIR, null)
                    staleTriggered = false
                    clearPhase()
                    gate.clearCooldown()
                    urgentPropose = true
                }
                Phase.EXECUTING, Phase.RUNNING -> {
                    silentDone = true
                    startRevert(s.t, out)
                }
                else -> {}
            }
        }
    }

    private fun onLowBeam(s: LowBeamChanged) {
        if (s.on == lowBeamOn) return
        lowBeamOn = s.on
        lowBeamSince = if (s.on) s.t else null
        lowBeamSettled = false
    }

    /** 야간인가. 하향등이 켜진 지 LIGHTS_SETTLE_MINUTES 가 지났거나, 출발할 때 이미 켜져 있었다. 잠깐 켠 것은 야간으로 보지 않는다 */
    private fun isNight(now: Long): Boolean {
        if (!lowBeamOn) return false
        if (lowBeamSettled) return true
        return lowBeamSince?.let { since(it, now) >= unitMs(LIGHTS_SETTLE_MINUTES) } ?: true
    }

    /** 주행 중에 하향등이 막 켜졌다. 왜 켰는지 모르니 LIGHTS_SETTLE_MINUTES 동안 환기 제안을 미룬다 */
    private fun lightsJustOn(now: Long): Boolean = lowBeamOn && !isNight(now)

    /** 지금 환기 제안(예고 포함)을 내지 않는 사유. 없으면 null. 졸음 제안에는 적용하지 않는다 */
    private fun staleHoldReason(now: Long): String? = when {
        carAuto -> HOLD_CAR_AUTO
        arrivingSoon -> HOLD_ARRIVING
        lightsJustOn(now) -> HOLD_LIGHTS
        else -> null
    }

    private fun isNightLongTrip(now: Long): Boolean =
        isNight(now) && tripElapsedMs(now) >= unitMs(config.longTripMinutes)

    /** 내기순환 시간 상한(분). 야간 장거리면 짧게 잡지만, 바깥 미세먼지가 나쁨 이상이면 일찍 묻지 않으려고 평소 값을 쓴다 */
    private fun staleThresholdMinutes(now: Long): Int =
        if (isNightLongTrip(now) && !pmShort(now)) config.staleAirMinutesNight else config.staleAirMinutes

    /**
     * 지금 쓰는 환기 제안 한도. 순서를 고정한다(BreathEngineTest 가 지킨다):
     *  1) 기본 한도 co2LimitPpm
     *  2) 야간 장거리면 NIGHT_LIMIT_FACTOR 배로 낮춘다(조금 일찍 묻는 여유)
     *  3) 바깥 미세먼지가 나쁨 이상이면 2)의 여유를 없애고 기본 한도로 돌린다(CO2 가 높을 때 바꿔야 같은 미세먼지로 더 많이 빠진다)
     *  4) 어린이나 임산부 자리에 사람이 탔으면 airMarginFraction(0.9)배. 이 여유는 3)에서도 남는다(v0.7.3, SeatPolicy.limitFactor)
     * 어떤 경우에도 기본 한도보다 높아지지 않는다
     */
    private fun effectiveLimit(now: Long): Double =
        config.co2LimitPpm * SeatPolicy.limitFactor(SeatPolicy.wideMargin(aboardProfiles()), isNightLongTrip(now), pmShort(now), config)

    // ---------- 자리별 프로필과 앉은 시간(v0.7.3) ----------

    /** 지금 탄 자리의 프로필(기본은 뺀다). 사용자가 고른 설정이고, 빈자리의 프로필은 쓰지 않는다 */
    /**
     * 기침 자리와 같이 탄 다른 사람 수(v0.8.0). 기침 자리가 없으면 0.
     * 자리별 값이 있으면 탄 자리 가운데 기침이 아닌 자리를 센다. 없으면(수동 입력) 인원에서 기침 프로필 수를 뺀다
     */
    private fun coughOthers(): Int {
        val p = SeatPolicy.normalize(seatProfiles)
        val driving = session.state == TripState.DRIVING
        val sv = seatValues
        return if (sv == null) {
            val cough = SeatPolicy.aboard(seatProfiles, null, occupants, driving).count { it == SeatProfile.COUGH }
            if (cough == 0) 0 else (occupants - cough).coerceAtLeast(0)
        } else {
            val on = SeatPolicy.occupied(sv, driving)
            val cough = p.indices.count { on[it] && p[it] == SeatProfile.COUGH }
            if (cough == 0) 0 else p.indices.count { on[it] && p[it] != SeatProfile.COUGH }
        }
    }

    /** 기침 자리와 다른 사람이 같이 탔다 */
    private fun coughMode(): Boolean = coughOthers() > 0

    private fun aboardProfiles(): List<SeatProfile> =
        SeatPolicy.aboard(seatProfiles, seatValues, occupants, driving = session.state == TripState.DRIVING)

    /** 환기 규칙을 고를 때 쓰는 바깥 미세먼지 등급. "미세먼지 먼저" 자리에 사람이 탔으면 한 단계 나쁘게 본다. 값이 없으면 null */
    private fun policyGrade(now: Long): PmGrade? = SeatPolicy.policyGrade(pmGrade(now), SeatPolicy.pmFirst(aboardProfiles()))

    /**
     * 기어가 바뀔 때마다. 기어 P 로 서기 시작한 시각을 적어 두고, P 에서 벗어날 때 오래 서 있었는지 본다.
     * 오래(sitParkResetMinutes) 서 있었고, 문 신호가 있는 차면 그동안 문이 열렸다 닫혔으면 모든 자리가 일어선 것으로 본다. 그때 true
     */
    private fun onGearForSit(s: GearChanged): Boolean {
        val prev = gearNow
        gearNow = s.gear
        if (s.gear == Gear.PARK) {
            if (prev != Gear.PARK) {
                parkedSince = s.t
                // 문을 연 채로 P 에 넣은 것도 이번 정차에서 문이 열린 것이다
                doorOpenedWhileParked = doorOpen
            }
            return false
        }
        val from = parkedSince ?: return false
        parkedSince = null
        // 다시 움직일 때 문은 닫혀 있다. 그래서 "열렸다 닫혔다"는 서 있는 동안 열린 적이 있는지만 보면 된다(닫힘 신호를 놓쳐도 된다)
        val stood = since(from, s.t) >= unitMs(config.sitParkResetMinutes) && (!doorKnown || doorOpenedWhileParked)
        if (stood) {
            sit.standAll(s.t)
            // 새로 앉은 것이니 걷기 쉼은 처음부터 다시 센다
            walkAsks = 0
            cooldownUntil.remove(TriggerKind.WALK_BREAK)
            queue.remove(TriggerKind.WALK_BREAK)
        }
        return stood
    }

    private fun onDoor(s: DoorChanged) {
        doorKnown = true
        doorOpen = s.anyOpen
        if (s.anyOpen && parkedSince != null) doorOpenedWhileParked = true
    }

    /**
     * 자리별 탑승 값을 앉은 시간에 옮긴다. accept 와 tick 끝에서 부른다.
     * 운전석은 좌석 값을 쓰지 않는다(운전석 값이 비어 오는 차가 있다). 달리는 동안은 앉아 있는 것으로 보고, 일어서는 것은 기어 P 규칙으로만 가린다.
     * 나머지 자리는 차가 준 값 그대로다. 못 읽은 자리와 자리별 값이 없는 차는 앉지 않은 것으로 둔다
     */
    private fun syncSeats(now: Long) {
        val emptyReset = unitMs(config.sitEmptyResetMinutes)
        if (session.state == TripState.DRIVING && !resting) sit.update(Seats.DRIVER, true, now, emptyReset)
        val values = seatValues
        for (i in 0 until Seats.COUNT) {
            if (i == Seats.DRIVER) continue
            sit.update(i, values?.getOrNull(i) == true, now, emptyReset)
        }
    }

    /** 가장 오래 앉은 자리의 연속 착석(분, demoFast 면 초). 앉은 사람이 없으면 0 */
    private fun longestSitMinutes(now: Long): Int = sit.longest(now)?.let { (it.second / unitMs(1)).toInt() } ?: 0

    /** 쉬는 간격에 닿았는가. 걷기 쉼을 켰을 때만 본다 */
    private fun sitDue(now: Long): Boolean {
        if (!config.walkBreak) return false
        val longest = sit.longest(now) ?: return false
        return longest.second >= unitMs(SeatPolicy.breakMinutes(aboardProfiles(), config))
    }

    /**
     * 트리거 3: 오래 앉아 있다. 경유지로 넣은 쉼터가 이미 있으면 묻지 않는다(거기서 내리면 된다).
     * 졸음 제안이 기다리거나 진행 중이면 넣지 않는다. 졸음 제안이 나갈 때 앉은 시간 이야기를 합친다
     */
    private fun checkWalkBreak(now: Long) {
        if (!config.walkBreak) return
        val due = sitDue(now) && waypoint == null && walkAsks < MAX_WALK_ASKS_PER_SIT
        if (!due) {
            queue.remove(TriggerKind.WALK_BREAK)
            return
        }
        if (TriggerKind.DROWSY in queue || currentKind == TriggerKind.DROWSY) return
        enqueue(TriggerKind.WALK_BREAK, now)
    }

    /** 걷기 쉼 제안 문장. 질문으로 끝난다. 차에서 본 것(앉은 시간)만 말한다 */
    private fun walkText(stop: RestStopInfo, now: Long): String =
        "${SeatPolicy.sitPhrase(longestSitMinutes(now))} 앉아 있어요. ${stopAheadText(stop)}에서 내려서 ${config.walkBreakMinutes}분 걸을까요?"

    // ---------- 숨 설계(v0.7.0) ----------

    /** 지금 믿고 쓰는 바깥 PM2.5. 값이 없거나 pmStaleMinutes(실제 분)보다 묵었으면 null */
    private fun validOutdoor(now: Long): Double? {
        val v = outdoorPm ?: return null
        val ageMin = outdoorAgeMin + since(outdoorAt, now) / 60_000L
        return if (ageMin <= config.pmStaleMinutes) v else null
    }

    private fun pmGrade(now: Long): PmGrade? = validOutdoor(now)?.let { VentPlanner.grade(it) }

    /** 바깥 미세먼지가 나쁨 이상이라 "늦추고 짧게" 규칙을 쓰는가 */
    private fun pmShort(now: Long): Boolean = VentPlanner.shortMode(policyGrade(now))

    /** 재도달 예측이 이 값(분) 미만이면 바깥 공기로 둘지 묻는다. 바깥 공기가 좋음이면 더 넉넉하게 잡는다 */
    private fun keepThresholdMinutes(grade: PmGrade?): Double =
        if (grade == PmGrade.GOOD) RECURRENCE_KEEP_MINUTES_GOOD else RECURRENCE_KEEP_MINUTES

    /**
     * 환기 제안을 내기 직전에 부른다. 미세먼지가 나쁨 이상이고 "N분만" 바꾸고 되돌리는 변형이면 필요한 길이와 목표를 정해 둔다.
     * 그 밖에는 비워서 기본 길이를 쓰게 한다
     */
    private fun planShortVent(variant: StaleVariant, now: Long) {
        val actual = pmGrade(now)
        val grade = policyGrade(now)
        val revertible = variant == StaleVariant.REVERT || variant == StaleVariant.TIME_CAP
        if (revertible && VentPlanner.shortMode(grade)) {
            val limit = effectiveLimit(now)
            plannedVentSec = VentPlanner.shortVentSeconds(air, config, limit)
            ventTargetPpm = VentPlanner.targetPpm(config, limit)
            // 문장에는 받은 등급 그대로 말한다. 설정 때문에 짧아진 것이면 그 자리 이름을 함께 말한다
            proposalPmGrade = actual ?: grade
            proposalPmFirstLabel = if (VentPlanner.shortMode(actual)) null
            else SeatPolicy.firstLabel(aboardProfiles(), listOf(SeatProfile.RESPIRATORY, SeatProfile.CARDIO))
        } else {
            plannedVentSec = null
            ventTargetPpm = null
            proposalPmGrade = null
            proposalPmFirstLabel = null
        }
        lastVentPlan = ventPlan(now).copy(durationSec = plannedVentSec ?: config.ventilateSeconds)
    }

    /**
     * "필요한 만큼만": 미세먼지 나쁨 이상에서 시작한 환기가 목표까지 내려왔다. 말한 시간이 안 됐어도 되돌린다.
     * 가장 짧은 길이(기본 길이의 절반)는 지킨다. 데모 속도에서는 숨 모델이 60배로 흘러 곧바로 닿으므로 말한 시간을 그대로 쓴다
     */
    private fun ventTargetReached(now: Long): Boolean {
        val target = ventTargetPpm ?: return false
        if (config.demoFast || !heldStaleVent) return false
        val minHoldMs = (config.ventilateSeconds * 1000L * VentPlanner.MIN_DURATION_FRACTION).toLong()
        return since(phaseSince, now) >= minHoldMs && air.ppmExact() <= target
    }

    /** 지금 시각(자정부터 흐른 분). 받은 값에 그 뒤로 흐른 실제 시간을 더한다. 모르면 null */
    private fun minuteOfDay(now: Long): Int? =
        clockMinuteOfDay?.let { ((it + since(clockAt, now) / 60_000L) % MINUTES_PER_DAY).toInt() }

    /** 연속 주행 시간(분, demoFast 면 초). 쉬고 나왔으면 그때부터, 아니면 트립 시간 */
    private fun continuousDriveMinutes(now: Long): Double {
        if (session.state != TripState.DRIVING || resting) return 0.0
        val ms = lastRestEndedAt?.let { since(it, now) } ?: tripElapsedMs(now)
        return ms.toDouble() / unitMs(1)
    }

    /** 쉼 모드의 낮잠 안내. 쉬는 중이 아니면 null. 타이머는 쉼 목표와 NAP_MINUTES 가운데 짧은 쪽이다 */
    private fun napGuide(now: Long): NapGuide? {
        if (!resting) return null
        if (restIsWalk) {
            // 걷기 쉼: 낮잠 대신 내려서 걷자고 안내한다. 타이머는 쉼 목표(걷기 길이)와 같다
            val walkMinutes = (restTargetMs / restUnitMs).toInt().coerceAtLeast(1)
            val left = ((restTargetMs - since(restStartedAt, now) + 999) / 1000).toInt().coerceAtLeast(0)
            return NapGuide(walkTip(walkMinutes), left, done = left == 0, doneText = walkDoneText(walkMinutes))
        }
        val minutes = minOf(config.restMinutes, NAP_MINUTES).coerceAtLeast(1)
        val targetMs = minOf(restTargetMs, minutes * restUnitMs)
        val remaining = ((targetMs - since(restStartedAt, now) + 999) / 1000).toInt().coerceAtLeast(0)
        return NapGuide(napTip(minutes), remaining, done = remaining == 0, doneText = napDoneText(minutes))
    }

    /** 시간이 흘러서 성립하는 조건들. accept 와 tick 양쪽에서 부른다 */
    private fun checkTriggers(now: Long) {
        // 트리거 1: 환기. 내기순환에서 추정치가 한도에 닿았거나, 내기순환 시간 상한에 걸렸거나, 외기인데도 한도 위다.
        // 어떤 문장으로 물을지는 말하는 순간에 고른다(chooseStaleVariant)
        // 이번 주행에서 두 번 연속 거절했으면 더 묻지 않는다(추정치는 계속 알린다)
        val hit = !quiet && (staleByModel(now) || staleByTime(now) || staleByWeakFan(now))
        if (!staleTriggered && hit) {
            if (enqueue(TriggerKind.STALE_AIR, now)) staleTriggered = true
        } else if (staleTriggered && !hit && TriggerKind.STALE_AIR in queue) {
            // 아직 말하지 못한 사이 공기가 나아졌다(창문을 열었다 등). 제안을 버린다
            queue.remove(TriggerKind.STALE_AIR)
            staleTriggered = false
        }
        // 트리거 2c: 핸들 급보정이 잦다(보조). 트립이 충분히 길 때만, 세션당 MAX_STEER_DROWSY_PER_TRIP 회
        pruneSteerEvents(now)
        if (steerDrowsyFired < MAX_STEER_DROWSY_PER_TRIP &&
            steerEventTimes.size >= config.steerEventsThreshold.coerceAtLeast(1) &&
            tripElapsedMs(now) >= unitMs(STEER_MIN_TRIP_MINUTES)
        ) {
            val before = TriggerKind.DROWSY in queue || currentKind == TriggerKind.DROWSY
            if (enqueue(TriggerKind.DROWSY, now)) {
                steerDrowsyFired++
                // 다음 번은 새로 쌓인 횟수로만 본다
                steerEventTimes.clear()
                // 텔테일 등으로 이미 졸음 제안이 대기/진행 중이면 그쪽이 우선. 문장을 바꾸지 않는다
                if (!before) {
                    queuedDrowsySource = DrowsySource.STEER
                    log += now to "핸들 보정이 잦아져 쉬어 가기 제안"
                }
            }
        }
        // 트리거 3(v0.7.3): 오래 앉아 있다. 설정으로 켰을 때만
        checkWalkBreak(now)
    }

    private fun effectiveRecirculating(): Boolean = recirculating && !ventActive()

    /** 엔진이 실행한 환기가 유지되는 중인가. 운전자가 그 사이 직접 내기순환을 켰으면 아니다 */
    private fun ventActive(): Boolean =
        phase == Phase.RUNNING && !driverOverrode && runningControls.any { it is Action.Ventilate }

    private fun windowOpen(): Boolean = windowPercent >= CabinAirModel.WINDOW_OPEN_MIN_PERCENT

    /** 내기순환에서 추정치가 한도에 닿았다 */
    private fun staleByModel(now: Long): Boolean = effectiveRecirculating() &&
        (air.ppmExact() >= effectiveLimit(now) || (coughMode() && air.ppmExact() >= config.outdoorPpm + CabinAirModel.START_OFFSET_PPM + COUGH_ASK_RISE_PPM))

    /** 내기순환 시간 상한. 창문이 열려 있으면(환기 중이면) 걸지 않는다 */
    private fun staleByTime(now: Long): Boolean {
        if (!effectiveRecirculating() || windowOpen()) return false
        // 바깥 미세먼지가 매우 나쁨이면 추정치가 한도에 닿을 때까지 기다린다(시간만으로는 묻지 않는다)
        if (policyGrade(now) == PmGrade.VERY_BAD) return false
        return recircOnSince?.let { since(it, now) >= unitMs(staleThresholdMinutes(now)) } ?: false
    }

    /**
     * 외기인데도 추정치가 한도 위이고, 지금 풍량으로는 한도 아래로 내려가지 않는다(정상상태 값도 한도 위).
     * 내기순환에서 막 외기로 바꿔 내려가는 중이면 바람이 약한 것이 아니므로 걸지 않는다.
     * 창문이 닫혀 있고 풍량을 더 올릴 수 있을 때만
     */
    private fun staleByWeakFan(now: Long): Boolean {
        if (recirculating || ventActive() || windowOpen() || !fanCanRise()) return false
        val limit = effectiveLimit(now)
        return air.ppmExact() >= limit && air.steadyStatePpmExact() >= limit
    }

    /** 풍량을 더 올릴 여지가 있는가. 이미 최대 단이거나, 올려 달라고 한 뒤 차의 풍량이 그대로면 다시 묻지 않는다 */
    private fun fanCanRise(): Boolean = !fanUpAwaitingChange && (fanMax <= 0 || fanLevel < fanMax)

    /** 큐에 넣는다. OFF, 쿨다운 중, 횟수 제한이면 false. 이미 대기/진행 중이면 true */
    private fun enqueue(kind: TriggerKind, now: Long): Boolean {
        if (!enabledFlag) return false
        if (now < (cooldownUntil[kind] ?: Long.MIN_VALUE)) return false
        if (kind in queue || currentKind == kind) return true
        if (kind == TriggerKind.DROWSY) {
            if (drowsyProposals >= MAX_DROWSY_PER_TRIP) return false
            drowsyProposals++
        }
        queue += kind
        return true
    }

    // ---------- 제안 ----------

    private fun tryPropose(now: Long, out: MutableList<WakeEvent>) {
        if (phase != Phase.IDLE || queue.isEmpty() || resting) return
        // DROWSY 가 STALE_AIR 보다 먼저. 걷기 쉼은 새로 넣을 쉼터가 있을 때만 꺼낸다(없으면 큐에 둔 채 기다린다)
        val kind = if (TriggerKind.DROWSY in queue) TriggerKind.DROWSY
        else queue.firstOrNull { it != TriggerKind.WALK_BREAK || drowsyStop() != null } ?: return

        if (kind == TriggerKind.STALE_AIR) {
            // 도착이 가깝거나, 차가 내외기를 스스로 제어하거나, 하향등이 막 켜졌으면 묻지 않고 기다린다. 사유마다 한 번만 알린다
            val hold = staleHoldReason(now)
            if (hold != null) {
                if (holdsSaid.add(hold)) out += WakeEvent.Deferred(kind, hold)
                return
            }
        }
        val blocked = gate.blockedReason(now)
        if (blocked != null) {
            if (deferredKind != kind) {
                out += WakeEvent.Deferred(kind, blocked)
                deferredKind = kind
            }
            return
        }
        queue.remove(kind)
        deferredKind = null

        val variant: StaleVariant?
        var text: String
        if (kind == TriggerKind.STALE_AIR) {
            variant = chooseStaleVariant(now)
            if (variant == null) {
                // 말하려는 순간 조건이 사라졌다. 조용히 버린다
                staleTriggered = false
                return
            }
            // 미세먼지가 나쁨 이상이면 필요한 길이를 여기서 정한다(문장과 유지 시간이 이 값을 쓴다)
            planShortVent(variant, now)
            // 예고는 화면이 보일 때만. 안 보이면(내비 화면 등) 취소할 길이 없으니 맡겼어도 묻는다
            if (variant == StaleVariant.REVERT && trustState.delegated && !delegationSuspended && appVisible) {
                announce(now, out)
                return
            }
            text = staleText(variant, now)
        } else if (kind == TriggerKind.WALK_BREAK) {
            variant = null
            val stop = drowsyStop()
            if (stop == null) return
            walkAsks++
            log += now to "오래 앉아 걷기 쉼 제안"
            text = walkText(stop, now)
        } else {
            variant = null
            currentDrowsySource = queuedDrowsySource
            queuedDrowsySource = DrowsySource.TELLTALE
            // 쉬는 간격에도 닿아 있으면 한 번에 묻는다. 걷기 쉼은 따로 묻지 않고, 거절하면 같은 시간 동안 다시 꺼내지 않는다
            val sitClause = if (sitDue(now)) "${SeatPolicy.sitPhrase(longestSitMinutes(now))} 앉아 있기도 해요." else null
            if (sitClause != null) {
                queue.remove(TriggerKind.WALK_BREAK)
                walkAsks++
                cooldownUntil[TriggerKind.WALK_BREAK] = now + unitMs(config.declineCooldownMinutes)
            }
            text = drowsyText(currentDrowsySource, sitClause)
        }
        val actions = actionsFor(kind, variant)
        // 조수석에 사람이 있으면 이번 주행의 첫 제안에 한 번만 알려 준다
        if (passengerPresent && !passengerHintSaid) {
            passengerHintSaid = true
            text += PASSENGER_HINT
        }
        startProposal(kind, variant, text, actions, now, out)
    }

    /** Propose 를 내고 동의 대기로 들어간다 */
    private fun startProposal(
        kind: TriggerKind, variant: StaleVariant?, text: String, actions: List<Action>, now: Long, out: MutableList<WakeEvent>,
    ) {
        currentKind = kind
        currentVariant = variant
        currentActions = actions
        announced = false
        phase = Phase.AWAITING_ANSWER
        phaseSince = now
        proposedAt = now
        speechFinishedAt = null
        // 대기 시간과 TTS 여유는 제안 시점에 고정한다(설정이 바뀌어도 이 제안은 그대로)
        answerTimeoutMs = config.answerTimeoutSec * 1000L
        speechGraceMs = speechGraceSec(text) * 1000L
        lastProposalText = text
        lastProposalVariant = variant?.name ?: if (kind == TriggerKind.WALK_BREAK) "WALK_BREAK" else "DROWSY"
        gate.markSpoken(now)
        out += WakeEvent.Propose(kind, text, actions, config.answerTimeoutSec, passengerMayAnswer = passengerPresent)
    }

    /**
     * 맡겨 둔 "2분만" 환기. 묻지 않고 짧게 예고한 뒤 취소 창을 둔다.
     * 취소 창은 SpeechFinished 부터 ANNOUNCE_CANCEL_SEC 초(안 오면 제안과 같은 여유 규칙). 그 안에 거절이 없으면 tick 이 실행으로 넘긴다
     */
    private fun announce(now: Long, out: MutableList<WakeEvent>) {
        val text = (pmAnnounceLead() ?: "${ventilateHoldText()} 바깥 공기로 바꿀게요.") + " 싫으면 '아니'라고 하세요."
        val actions = actionsFor(TriggerKind.STALE_AIR, StaleVariant.REVERT)
        currentKind = TriggerKind.STALE_AIR
        currentVariant = StaleVariant.REVERT
        currentActions = actions
        announced = true
        phase = Phase.ANNOUNCING
        phaseSince = now
        proposedAt = now
        speechFinishedAt = null
        answerTimeoutMs = ANNOUNCE_CANCEL_SEC * 1000L
        speechGraceMs = speechGraceSec(text) * 1000L
        lastProposalText = text
        lastProposalVariant = "ANNOUNCE"
        gate.markSpoken(now)
        out += WakeEvent.Announce(TriggerKind.STALE_AIR, text, actions, ANNOUNCE_CANCEL_SEC)
    }

    /**
     * 환기 제안을 어떤 변형으로 물을지 고른다. 조건이 하나도 남아 있지 않으면 null.
     * 여럿이 타면 "2분만" 환기를 끝낼 때마다 금방 다시 차서 같은 제안이 되풀이된다.
     * 그래서 2분 환기 뒤 다시 한도에 닿기까지를 미리 계산해 보고, 짧으면 처음부터 바깥 공기로 둘지 묻는다
     */
    private fun chooseStaleVariant(now: Long): StaleVariant? {
        if (staleByWeakFan(now)) {
            lastRecurrenceMinutes = null
            return StaleVariant.FAN_UP
        }
        val byModel = staleByModel(now)
        if (!byModel && !staleByTime(now)) return null
        val recurrence = predictRecurrenceMinutes(effectiveLimit(now))
        lastRecurrenceMinutes = recurrence
        // 바깥 미세먼지가 나쁨 이상이면 바깥 공기로 두자고 하지 않는다. 짧게 바꾸고 되돌리는 쪽만 쓴다
        val grade = policyGrade(now)
        val short = VentPlanner.shortMode(grade)
        if (!short && occupants >= KEEP_FRESH_MIN_OCCUPANTS && staleVentsFinished >= KEEP_FRESH_AFTER_VENTS) {
            return StaleVariant.KEEP_AFTER_TWO
        }
        if (skipRecurrenceOnce) {
            // 바깥 공기로 두기를 거절한 다음 한 번은 "2분만" 으로 묻는다
            skipRecurrenceOnce = false
            return if (byModel) StaleVariant.REVERT else StaleVariant.TIME_CAP
        }
        if (coughMode()) {
            // 기침 자리(v0.8.0): 짧게 바꾸고 되돌리면 입자가 다시 쌓인다. 처음부터 바깥 공기로 계속 둘지 묻는다.
            // 미세먼지가 나쁨 이상이면 약한 바람으로 계속 들이는 쪽을 묻는다(풍량은 KEEP_LOW 와 같은 계산)
            coughAsk = true
            return if (short) StaleVariant.KEEP_LOW else StaleVariant.KEEP
        }
        if (!byModel) return StaleVariant.TIME_CAP
        if (short) {
            // 나쁨 이상(v0.7.4): 3명 이상이고 짧게 바꾼 뒤 다시 닿기까지가 환기 뒤 쉬는 시간보다 짧으면, 되돌리는 쪽으로는 한도를 지킬 수 없다.
            // 그때는 "짧게 여러 번" 대신 약한 바람으로 바깥 공기를 계속 들일지 한 번 묻는다
            val shortRecurrence = predictShortRecurrenceMinutes(effectiveLimit(now))
            lastRecurrenceMinutes = shortRecurrence
            val keepLow = occupants >= VentPlanner.KEEP_LOW_MIN_OCCUPANTS && shortRecurrence != null && shortRecurrence < POST_VENT_COOLDOWN_MINUTES
            return if (keepLow) StaleVariant.KEEP_LOW else StaleVariant.REVERT
        }
        return if (recurrence != null && recurrence < keepThresholdMinutes(grade)) StaleVariant.KEEP else StaleVariant.REVERT
    }

    /** 이미 경유지로 들어가 있는 쉼터가 다시 후보로 왔는가 */
    private fun candidateIsWaypoint(): Boolean {
        val c = lastCandidate ?: return false
        val wp = waypoint ?: return false
        return sameStop(c, wp)
    }

    /** 목적지에 닿는 것이 쉼터에 닿는 것보다 빠른가. 쉼터까지는 REST_STOP_SPEED_KPH 로 달린다고 본다. 목적지가 없으면 false */
    private fun arrivesBeforeStop(stop: RestStopInfo): Boolean {
        val eta = etaMinutes ?: return false
        return eta < stop.distanceKm * 60.0 / REST_STOP_SPEED_KPH
    }

    /** 졸음 제안에 넣을 새 쉼터. 후보가 없거나, 이미 경유지거나, 목적지가 더 가까우면 null */
    private fun drowsyStop(): RestStopInfo? {
        val c = lastCandidate ?: return null
        if (candidateIsWaypoint() || arrivesBeforeStop(c)) return null
        return c
    }

    /** 액션 순서: 가장 중요한 환기와 경유지를 먼저. 창문은 막힐 수 있어 맨 뒤 */
    private fun actionsFor(kind: TriggerKind, variant: StaleVariant?): List<Action> = when (kind) {
        TriggerKind.STALE_AIR -> when (variant) {
            StaleVariant.FAN_UP -> listOf(Action.Ventilate(fanDelta = FAN_UP_DELTA))
            // 약한 바람: 지금 풍량에서 맞출 단까지의 차이(내릴 수도 올릴 수도 있다). 최대 단을 모르면 풍량은 그대로 둔다
            StaleVariant.KEEP_LOW -> listOf(Action.Ventilate(fanDelta = keepLowFanLevel(lastSeenNow)?.let { it - fanLevel } ?: 0))
            else -> listOf(Action.Ventilate())
        }
        TriggerKind.DROWSY -> buildList {
            // 전방에 쉼터가 없거나, 이미 경유지로 넣은 쉼터거나, 목적지가 쉼터보다 가까우면 경유지는 뺀다
            val stop = drowsyStop()
            // 졸리기 쉬운 시간대(시각을 받았고 가중이 기준 이상)면 쉼터 경유를 맨 먼저 실행한다.
            // 찬 공기는 잠깐만 듣고, 졸음과 싸우는 단계에서 안전한 대책은 멈추고 쉬는 것이라는 연구에 맞춘 순서다(I 절)
            val stopFirst = stop != null && alertness().let { it.clockKnown && it.sleepyHours }
            if (stopFirst) add(Action.AddRestStop(stop!!))
            // 차가 내외기를 스스로 제어하는 중이면 내외기는 건드리지 않는다
            if (!carAuto) add(Action.Ventilate())
            if (!stopFirst) stop?.let { add(Action.AddRestStop(it)) }
            add(Action.CoolDown())
            if (windowAllowed()) add(Action.OpenWindow()) // 설정으로 꺼져 있거나 비가 오면 창문을 열지 않는다
        }
        // 걷기 쉼: 차의 공조는 건드리지 않는다. 쉼터 경유지만 넣는다
        TriggerKind.WALK_BREAK -> listOfNotNull(drowsyStop()?.let { Action.AddRestStop(it) })
    }

    /** 졸음 개입에서 창문을 열어도 되는가. 설정으로 켜져 있고 비가 오지 않을 때만 */
    private fun windowAllowed(): Boolean = config.allowWindow && !wiperOn

    /** "2분만" / "20초만". 환기 유지 시간이 60초 배수면 분, 아니면 초로 읽는다 */
    private fun ventilateHoldText(): String {
        val s = plannedVentSec ?: config.ventilateSeconds
        return when {
            s >= 60 && s % 60 == 0 -> "${s / 60}분만"
            // 계산한 길이(1분 30초 등)는 분과 초로 읽는다. 기본 길이는 v0.6.2 그대로 초로 읽는다
            plannedVentSec != null && s > 60 -> "${s / 60}분 ${s % 60}초만"
            else -> "${s}초만"
        }
    }

    /** 계산한 길이가 기본 길이보다 짧은가("짧게"를 붙일지) */
    private fun ventShortened(): Boolean = plannedVentSec?.let { it < config.ventilateSeconds } ?: false

    /** 미세먼지가 나쁨 이상일 때 환기 제안의 묻는 부분. 실제로 계산한 길이를 말한다. 아니면 null */
    private fun pmAsk(): String? = proposalPmGrade?.let { g ->
        "${pmWhy(g)} ${ventilateHoldText()}${if (ventShortened()) " 짧게" else ""} 바꿀까요?"
    }

    /** "밖 미세먼지가 나쁨이라". 바깥 등급은 나쁨이 아닌데 "미세먼지 먼저" 설정 때문에 짧게 바꾸는 것이면 "밖 미세먼지가 보통이지만 호흡기 주의 자리가 있어" */
    private fun pmWhy(g: PmGrade): String =
        proposalPmFirstLabel?.let { "밖 미세먼지가 ${g.label}이지만 $it 자리가 있어" } ?: "밖 미세먼지가 ${g.label}이라"

    /** 미세먼지가 나쁨 이상일 때 맡겨 둔 환기의 예고 첫 문장. 아니면 null */
    private fun pmAnnounceLead(): String? = proposalPmGrade?.let { g ->
        "${pmWhy(g)} ${ventilateHoldText()}${if (ventShortened()) " 짧게" else ""} 바깥 공기로 바꿀게요."
    }

    /** 밤이 아니었어도 지금 물었을 조건인가(기본 한도, 주간 시간 상한 기준) */
    private fun wouldAskByDay(now: Long): Boolean {
        if (air.ppmExact() >= config.co2LimitPpm) return true
        if (!effectiveRecirculating() || windowOpen()) return false
        return recircOnSince?.let { since(it, now) >= unitMs(config.staleAirMinutes) } ?: false
    }

    /** 야간 장거리라서 평소보다 일찍 묻게 된 첫 환기 제안에만 붙이는 접두 */
    private fun nightPrefix(now: Long): String {
        if (nightPrefixSaid || !isNightLongTrip(now) || wouldAskByDay(now)) return ""
        nightPrefixSaid = true
        return NIGHT_PREFIX
    }

    /** 환기 제안 문장. 전부 질문으로 끝난다. 괄호 안 추정 수치는 화면용이다(앱 TTS 는 괄호를 읽지 않는다) */
    private fun staleText(variant: StaleVariant, now: Long): String {
        if (coughAsk && (variant == StaleVariant.KEEP || variant == StaleVariant.KEEP_LOW)) {
            coughAsk = false
            return if (variant == StaleVariant.KEEP) COUGH_KEEP_ASK else COUGH_KEEP_LOW_ASK
        }
        val estimate = "추정 ${ppmText(air.ppm())}ppm"
        val hold = ventilateHoldText()
        val body = when (variant) {
            StaleVariant.REVERT -> {
                val ask = pmAsk() ?: "$hold 바깥 공기로 바꿀까요?"
                if (occupants >= 2) "${occupants}명이 타서 공기가 빨리 탁해져요($estimate). $ask"
                else "차 안 공기가 탁해진 것 같아요($estimate). $ask"
            }
            StaleVariant.KEEP ->
                if (occupants >= 2) "${occupants}명이 타서 공기가 빨리 탁해져요($estimate). 바깥 공기로 바꿔 둘까요?"
                else "차 안 공기가 빨리 탁해져요($estimate). 바깥 공기로 바꿔 둘까요?"
            StaleVariant.KEEP_AFTER_TWO -> KEEP_FRESH_PROPOSAL
            StaleVariant.KEEP_LOW -> "${SeatPolicy.countWord(occupants)} 타서 금방 다시 탁해져요($estimate). $KEEP_LOW_ASK"
            StaleVariant.FAN_UP -> "바깥 공기인데 바람이 약해요($estimate). 풍량을 올릴까요?"
            StaleVariant.TIME_CAP -> {
                val minutes = if (config.demoFast) staleThresholdMinutes(now)
                else (since(recircOnSince ?: now, now) / 60_000L).toInt()
                "내기순환으로 ${minutes}분째예요. " + (pmAsk() ?: "$hold 바깥 공기로 바꿀까요?")
            }
        }
        return earlyPrefix(now) + body
    }

    /**
     * 평소보다 일찍 묻게 된 첫 환기 제안에만 붙이는 접두. 야간 장거리 때문이면 그 말을, 어린이나 임산부 자리 설정 때문이면 그 자리 이름을 말한다.
     * 미세먼지 규칙이 "늦추고 짧게"면 야간의 여유는 없으므로 자리 설정 쪽만 본다
     */
    private fun earlyPrefix(now: Long): String =
        if (isNightLongTrip(now) && !pmShort(now)) nightPrefix(now) else marginPrefix(now)

    /** "어린이 자리가 있어 조금 일찍 물어요. " 주행당 한 번. 기본 한도였어도 물었을 조건이면 붙이지 않는다 */
    private fun marginPrefix(now: Long): String {
        if (marginPrefixSaid || wouldAskByDay(now)) return ""
        val who = SeatPolicy.firstLabel(aboardProfiles(), listOf(SeatProfile.CHILD, SeatProfile.PREGNANT)) ?: return ""
        marginPrefixSaid = true
        return "$who 자리가 있어 조금 일찍 물어요. "
    }

    /** "{km}km 앞 {노선} {쉼터 표시 이름}". 노선은 routeLabel 의 첫 단어, km 는 반올림 정수 */
    private fun stopAheadText(stop: RestStopInfo): String {
        val route = stop.routeLabel.trim().split(' ').firstOrNull { it.isNotEmpty() }
        val name = restStopDisplayName(stop.name)
        val where = if (route != null) "$route $name" else name
        return "${stop.distanceKm.roundToInt()}km 앞 $where"
    }

    /**
     * 졸음 개입 제안 문장. 전부 질문으로 끝난다.
     * 첫머리는 출처에 따라 다르다. 차의 경고가 아니면(핸들 경로) 졸음이라고 말하지 않고 본 것만 말한다
     */
    private fun drowsyText(source: DrowsySource, sitClause: String? = null): String {
        val first = when (source) {
            DrowsySource.TELLTALE -> DROWSY_LEAD
            DrowsySource.STEER -> STEER_LEAD
        }
        // 쉬는 간격에도 닿아 있으면 앉은 시간을 한 문장 덧붙인다(걷기 쉼을 따로 묻지 않는다)
        val lead = if (sitClause != null) "$first $sitClause" else first
        val wp = waypoint
        val stop = drowsyStop()
        if (stop != null) {
            val ahead = stopAheadText(stop)
            return when {
                !config.allowWindow -> "$lead 찬 바람 넣고, $ahead 경유까지 할까요?"
                wiperOn -> "$lead 비가 와서 창문은 두고, 찬 바람과 $ahead 경유까지 할까요?"
                else -> "$lead 찬 바람과 창문 조금, $ahead 경유까지 할까요?"
            }
        }
        val ask = coolAskText()
        // 쉼터 후보는 있는데 목적지가 더 가깝다. 쉼터 대신 곧 도착한다는 사실을 말한다
        val candidate = lastCandidate
        if (candidate != null && !candidateIsWaypoint() && arrivesBeforeStop(candidate)) {
            val eta = etaMinutes ?: 0
            val arrive = if (eta >= 1) "${eta}분 뒤 도착이에요." else "곧 도착이에요."
            return "$lead $arrive $ask"
        }
        // 이미 경유지에 넣은 쉼터가 있으면 그 사실을 먼저 알리고 질문으로 끝낸다.
        // 표시 이름은 "쉼터"나 "휴게소"로 끝나므로 조사는 "는" 이다
        return if (wp != null) "$lead ${restStopDisplayName(wp.name)}는 경유지에 있어요. $ask" else "$lead $ask"
    }

    /** 쉼터 경유 없이 묻는 졸음 개입 질문. 창문을 쓰지 않는 설정이면 찬 바람만 */
    private fun coolAskText(): String = when {
        !config.allowWindow -> "찬 바람을 넣을까요?"
        wiperOn -> "비가 와서 창문은 두고 찬 바람만 넣을까요?"
        else -> "찬 바람 넣고 창문 조금 열까요?"
    }

    // ---------- 동의, 실행 ----------

    private fun answerExpired(now: Long): Boolean {
        val finished = speechFinishedAt
        return if (finished != null) since(finished, now) >= answerTimeoutMs
        else since(proposedAt, now) >= answerTimeoutMs + speechGraceMs
    }

    /**
     * TTS 종료. 현재 제안의 것만 인정한다:
     *  - 동의 대기 중이 아니면 무시(만료된 제안의 늦은 종료)
     *  - 이미 받았으면 무시
     *  - 제안이 나간 뒤 SPEECH_ECHO_MS 안에 온 것은 앞 문장의 잔재로 보고 무시(제안 전 시각도 포함)
     */
    private fun onSpeechFinished(s: SpeechFinished, out: MutableList<WakeEvent>) {
        if (phase != Phase.AWAITING_ANSWER && phase != Phase.ANNOUNCING) return
        if (speechFinishedAt != null) return
        // 예고 문장을 다 읽기에는 너무 이르다(TTS 실패로 본다). 듣지 못한 예고를 실행하지 않고 물어본다
        if (phase == Phase.ANNOUNCING && since(proposedAt, s.t) < (lastProposalText?.length ?: 0) * ANNOUNCE_MIN_READ_MS_PER_CHAR) {
            log += s.t to "예고를 읽지 못해 다시 물음"
            val text = staleText(StaleVariant.REVERT, s.t)
            startProposal(TriggerKind.STALE_AIR, StaleVariant.REVERT, text, currentActions, s.t, out)
            return
        }
        if (s.t < proposedAt + SPEECH_ECHO_MS) return
        speechFinishedAt = s.t
    }

    /** 환기 제안을 받지 않았다(거절, 무응답). 바깥 공기로 두자는 제안이었으면 다음에는 다시 "2분만" 으로 묻는다 */
    private fun onStaleNotTaken() {
        staleTriggered = false
        if (currentVariant == StaleVariant.KEEP || currentVariant == StaleVariant.KEEP_AFTER_TWO || currentVariant == StaleVariant.KEEP_LOW) {
            staleVentsFinished = 0
            skipRecurrenceOnce = true
        }
    }

    private fun onAnswer(s: UserAnswer, out: MutableList<WakeEvent>) {
        // 예고로 시작한 실행(또는 직접 요청한 환기) 중의 "아니". 바로 원복을 시작한다
        if (!s.yes && cancellableRun) {
            cancelRun(s.t, out)
            return
        }
        if (phase == Phase.ANNOUNCING) {
            // 예고 창 안의 답. "응" 이면 기다리지 않고 바로, "아니" 면 취소
            if (s.yes) beginExecute(s.t, out) else cancelAnnounce(s.t, out)
            return
        }
        // 첫 답만 유효. 그 뒤 답은 무시
        if (phase != Phase.AWAITING_ANSWER) return
        val kind = currentKind!!
        if (s.yes) {
            if (kind == TriggerKind.STALE_AIR) staleDeclineStreak = 0
            beginExecute(s.t, out)
        } else {
            declined++
            cooldownUntil[kind] = s.t + unitMs(config.declineCooldownMinutes)
            val variant = currentVariant
            if (kind == TriggerKind.STALE_AIR) onStaleNotTaken()
            log += s.t to "제안 거절(${label(kind)})"
            // 풍량 올리기는 다른 질문이라 연속 거절로 세지 않는다
            if (kind == TriggerKind.STALE_AIR && variant != StaleVariant.FAN_UP) staleDeclineStreak++
            if (kind == TriggerKind.STALE_AIR && !quiet && staleDeclineStreak >= QUIET_AFTER_DECLINES) {
                // 두 번 연속 거절. 이번 주행에서는 더 묻지 않는다고 한 번만 말한다
                quiet = true
                quietSince = s.t
                queue.remove(TriggerKind.STALE_AIR)
                log += s.t to "환기 제안을 두 번 거절해 더 묻지 않음"
                out += WakeEvent.Done(kind, null)
                out += WakeEvent.Quiet(QUIET_TEXT)
            } else {
                val unit = if (config.demoFast) "초" else "분"
                out += WakeEvent.Done(kind, "알겠어요. ${config.declineCooldownMinutes}${unit} 뒤에 다시 볼게요.")
            }
            if (variant == StaleVariant.REVERT) resetConsecutiveYes(out)
            clearPhase()
        }
    }

    /** 동의(또는 맡긴 환기의 취소 창 통과). Execute 를 내고 결과를 기다린다 */
    private fun beginExecute(now: Long, out: MutableList<WakeEvent>) {
        val kind = currentKind!!
        phase = Phase.EXECUTING
        phaseSince = now
        results.clear()
        executeTimeoutAtStartMs = executeTimeoutMs()
        fanLevelAtExecute = fanLevel
        fanMaxAtExecute = fanMax
        recircAtExecute = recirculating
        log += now to when (kind) {
            TriggerKind.STALE_AIR -> when {
                userRequested -> "화면에서 직접 바깥 공기로"
                announced -> "맡겨 둔 환기 실행"
                currentVariant == StaleVariant.KEEP || currentVariant == StaleVariant.KEEP_AFTER_TWO -> "바깥 공기로 계속 두기"
                currentVariant == StaleVariant.KEEP_LOW -> "약한 바람으로 바깥 공기 계속 들이기"
                currentVariant == StaleVariant.FAN_UP -> "풍량 올리기"
                else -> "바깥 공기로 환기"
            }
            // 차의 경고에서 온 것이 아니면 로그에도 졸음이라고 적지 않는다
            TriggerKind.DROWSY -> if (currentDrowsySource == DrowsySource.TELLTALE) "졸음 개입 실행" else "쉬어 가기 실행"
            TriggerKind.WALK_BREAK -> if (restStopRequested) "직접 요청한 쉼터 경유" else "걷기 쉼 쉼터를 경유지로"
        }
        out += WakeEvent.Execute(kind, currentActions)
    }

    /** 예고한 환기를 취소했다. 거절과 같은 쿨다운을 걸고, 남은 주행은 다시 묻는다 */
    private fun cancelAnnounce(now: Long, out: MutableList<WakeEvent>) {
        val kind = currentKind!!
        declined++
        cooldownUntil[kind] = now + unitMs(config.declineCooldownMinutes)
        staleTriggered = false
        log += now to "예고한 환기 취소"
        out += WakeEvent.Done(kind, ANNOUNCE_CANCELLED_TEXT)
        noteCancel(out)
        clearPhase()
    }

    /**
     * 예고로 시작한 실행이나 유지 중에 운전자가 취소했다. 결과를 기다리지 않고 바로 원복을 낸다.
     * 맡긴 환기였으면 취소 1회로 세고 거절과 같은 쿨다운을 건다. 직접 요청한 환기였으면 세지 않는다
     */
    private fun cancelRun(now: Long, out: MutableList<WakeEvent>) {
        val kind = currentKind!!
        runCancelled = true
        heldStaleVent = false
        if (announced) {
            declined++
            cooldownUntil[kind] = now + unitMs(config.declineCooldownMinutes)
            log += now to "맡긴 환기 실행 중 취소"
            noteCancel(out)
        } else {
            log += now to "직접 요청한 환기 취소"
        }
        startRevert(now, out)
    }

    // ---------- 맡기기 ----------

    private fun updateTrust(next: TrustState, out: MutableList<WakeEvent>) {
        if (next == trustState) return
        trustState = next
        out += WakeEvent.TrustChanged(next)
    }

    private fun resetConsecutiveYes(out: MutableList<WakeEvent>) = updateTrust(trustState.copy(consecutiveYes = 0), out)

    /** 맡긴 환기를 취소했다(예고 창에서 거절, 실행 중 직접 내기순환). 이번 주행은 다시 묻고, 취소가 쌓이면 맡기기를 끈다 */
    private fun noteCancel(out: MutableList<WakeEvent>) {
        delegationSuspended = true
        var next = trustState.copy(cancelCount = trustState.cancelCount + 1)
        if (next.delegated && next.cancelCount >= TRUST_OFF_CANCELS) {
            next = next.copy(delegated = false, consecutiveYes = 0)
            trustTurnedOff = true
        }
        updateTrust(next, out)
    }

    /** 도착 카드의 맡기기 질문에 답했다. 세션 상태와 무관하게 받는다 */
    private fun onTrustAnswer(s: TrustAnswer, out: MutableList<WakeEvent>) {
        if (s.accept) updateTrust(trustState.copy(delegated = true, cancelCount = 0), out)
        // 한동안 다시 권하지 않는다. 세 번을 처음부터 다시 센다
        else resetConsecutiveYes(out)
    }

    private fun onActionResult(s: ActionResult, out: MutableList<WakeEvent>) {
        if (currentKind == null) return
        when (phase) {
            Phase.EXECUTING -> {
                if (currentActions.none { key(it) == key(s.action) }) return
                results[key(s.action)] = s
                if (currentActions.all { key(it) in results }) resolveExecution(s.t, out)
            }
            // 원복 중에 늦게 온 제어 결과는 무시한다. 원복 결과만 본다
            Phase.REVERTING -> if (s.action == Action.Revert) {
                val okText = if (runCancelled) RUN_CANCELLED_TEXT else revertedText()
                finish(s.t, if (s.ok) okText else "일부는 못 돌렸어요. 직접 확인해 주세요.", out)
            }
            else -> {}
        }
    }

    /**
     * 실행 결과가 다 모였거나 타임아웃. 성공한 것만으로 Running.
     * 제어가 전부 실패해도 Execute(Revert) 를 먼저 낸다(타임아웃 뒤 늦게 성공한 제어가 남지 않게). Done 문장은 실패 문장.
     * 결과를 기다리는 동안 자동 개입이 꺼졌으면 유지하지 않고 바로 원복
     */
    private fun resolveExecution(now: Long, out: MutableList<WakeEvent>) {
        val kind = currentKind!!
        val okActions = currentActions.filter { results[key(it)]?.ok == true }
        val failed = currentActions.filter { results[key(it)]?.ok != true }

        val addedStop = okActions.filterIsInstance<Action.AddRestStop>().firstOrNull()
        if (addedStop != null) {
            restStopsAdded++
            waypoint = addedStop.stop
            waypointForWalk = kind == TriggerKind.WALK_BREAK && !restStopRequested
            restWithin = false
            log += now to "${restStopDisplayName(addedStop.stop.name)} 경유지 추가"
        }
        if (kind == TriggerKind.WALK_BREAK) {
            // 걷기 쉼은 경유지만 넣고 끝난다. 유지 구간도 원복도 없다(차의 공조를 건드리지 않았다)
            if (addedStop != null) {
                out += WakeEvent.Done(kind, if (restStopRequested) REST_STOP_ADDED_TEXT else WALK_ADDED_TEXT)
            } else {
                if (!restStopRequested) {
                    cooldownUntil[kind] = now + unitMs(FAIL_COOLDOWN_MINUTES)
                    walkAsks = (walkAsks - 1).coerceAtLeast(0)
                }
                log += now to "실행 실패(${label(kind)})"
                out += WakeEvent.Done(kind, WALK_FAILED_TEXT)
            }
            clearPhase()
            return
        }
        val controls = okActions.filter { it !is Action.AddRestStop }
        if (controls.isEmpty()) {
            // 차를 못 바꿨다. 짧은 쿨다운 뒤 다시 본다. 원복은 늦게 성공한 제어를 걷어내기 위해 낸다
            val restricted = failed.any { results[key(it)]?.detail == "DRIVING_RESTRICTED" }
            var text = if (restricted) "주행 중엔 공조를 못 바꿔요. 조금 뒤에 다시 볼게요."
            else "지금은 차를 바꿀 수 없어요. 조금 뒤에 다시 볼게요."
            if (addedStop != null) text += " 쉼터 경유지는 넣었어요."
            cooldownUntil[kind] = now + unitMs(FAIL_COOLDOWN_MINUTES)
            if (kind == TriggerKind.STALE_AIR) staleTriggered = false
            log += now to "실행 실패(${label(kind)})"
            pendingDoneText = text
            startRevert(now, out)
            return
        }
        runningControls = controls
        val ventilated = controls.any { it is Action.Ventilate }
        // 원복 문장이 "얼마나 내려갔는지" 를 이 값과 견준다. 바로 원복하는 경우에도 지난 환기의 값이 남지 않게 여기서 잡는다
        if (ventilated) ventStartPpm = air.ppmExact()
        if (revertWhenResolved) {
            // 자동 개입 OFF: 유지 구간 없이 바로 원복
            startRevert(now, out)
            return
        }
        if (ventilated) {
            ventilations++
            // 바깥 공기로 두는 중에 다른 개입이 겹치면 이미 열려 있는 구간을 그대로 잇는다
            if (ventOnSince == null) ventOnSince = now
        }
        val variant = currentVariant
        if (ventilated && (variant == StaleVariant.KEEP || variant == StaleVariant.KEEP_AFTER_TWO || variant == StaleVariant.KEEP_LOW)) {
            // 유지 구간도 원복도 없다. 차는 외기 상태로 남는다
            keepingFresh = true
            freshOpen = true
            recirculating = false
            recircOnSince = null
            staleTriggered = false
            queue.remove(TriggerKind.STALE_AIR)
            // 내기순환 신호가 늦게 한 번 더 와도, 외기로 막 바꾼 직후 추정치가 아직 높아도 곧바로 다시 묻지 않는다
            cooldownUntil[kind] = maxOf(cooldownUntil[kind] ?: Long.MIN_VALUE, now + unitMs(POST_VENT_COOLDOWN_MINUTES))
            out += WakeEvent.Done(kind, if (variant == StaleVariant.KEEP_LOW) KEEP_LOW_DONE else KEEP_FRESH_DONE)
            clearPhase()
            return
        }
        if (ventilated && variant == StaleVariant.FAN_UP) {
            // 풍량만 올리고 끝낸다. 유지 구간도 원복도 없다
            freshOpen = true
            staleTriggered = false
            queue.remove(TriggerKind.STALE_AIR)
            // 올려 달라고 했는데 차가 알려 준 풍량이 그대로면, 풍량이 바뀌기 전에는 같은 제안을 되풀이하지 않는다
            fanUpAwaitingChange = fanLevel == fanLevelAtExecute && fanMax == fanMaxAtExecute
            cooldownUntil[kind] = maxOf(cooldownUntil[kind] ?: Long.MIN_VALUE, now + unitMs(FAN_UP_COOLDOWN_MINUTES))
            out += WakeEvent.Done(kind, FAN_UP_DONE)
            clearPhase()
            return
        }
        heldStaleVent = kind == TriggerKind.STALE_AIR && ventilated
        phase = Phase.RUNNING
        phaseSince = now
        // 말한 시간만 유지한다. 추정치가 덜 내려갔어도 늘리지 않는다
        // 미세먼지 나쁨 이상에서 계산해 말한 길이가 있으면 그 길이다
        val seconds = if (kind == TriggerKind.STALE_AIR) plannedVentSec ?: config.ventilateSeconds else config.drowsySeconds
        runEndsAt = now + seconds * 1000L
        val remaining = ((runEndsAt - now + 999) / 1000).toInt().coerceAtLeast(0)
        out += WakeEvent.Running(kind, remaining, runningLabel())
    }

    /** 원복 성공 문장. 환기 제안이었고 추정치가 눈에 띄게 내려갔으면 원복을 시작할 때의 수치를 앞에 붙인다 */
    private fun revertedText(): String {
        val ventilated = currentKind == TriggerKind.STALE_AIR && runningControls.any { it is Action.Ventilate }
        val dropped = ventStartPpm - revertStartPpm >= AIR_EVENT_PPM_STEP
        return if (ventilated && dropped) "추정 ${ppmText(revertStartPpm.roundToInt())}ppm까지 내려갔어요. $REVERTED_TEXT"
        else REVERTED_TEXT
    }

    /** Execute(Revert) 를 내고 원복 결과를 기다린다. 이 시점의 추정치를 적어 둔다(원복 결과를 기다리는 동안 다시 오르기 때문) */
    private fun startRevert(now: Long, out: MutableList<WakeEvent>) {
        revertStartPpm = air.ppmExact()
        out += WakeEvent.Execute(currentKind!!, listOf(Action.Revert))
        phase = Phase.REVERTING
        phaseSince = now
    }

    private fun runningLabel(): String {
        val kind = currentKind ?: return ""
        val expected = currentActions.filter { it !is Action.AddRestStop }
        val full = expected.all { e -> runningControls.any { key(it) == key(e) } }
        if (full) {
            return when (kind) {
                TriggerKind.STALE_AIR -> "바깥 공기로 바꾸는 중"
                TriggerKind.DROWSY -> if (expected.none { it is Action.OpenWindow }) "찬 바람 넣는 중" else "찬 바람과 창문 열어 둔 상태"
                // 걷기 쉼은 유지 구간이 없다(경유지만 넣고 끝난다)
                TriggerKind.WALK_BREAK -> ""
            }
        }
        // 일부 실패: 성공한 것만 나열
        val names = runningControls.mapNotNull {
            when (it) {
                is Action.Ventilate -> "바깥 공기"
                is Action.CoolDown -> "찬 바람"
                is Action.OpenWindow -> "창문"
                else -> null
            }
        }
        return names.joinToString(", ") + " 유지 중"
    }

    /** 원복까지 끝난 개입을 마무리한다. 실패 경로에서 정해 둔 문장이 있으면 그걸 쓴다 */
    private fun finish(now: Long, text: String?, out: MutableList<WakeEvent>) {
        val kind = currentKind!!
        val ventilated = runningControls.any { it is Action.Ventilate }
        if (heldStaleVent) staleVentsFinished++
        // 물어서 동의받은 "2분만" 환기를 원복까지 끝냈다. 맡기기를 권할지 볼 때 쓰는 연속 동의로 센다
        val countsAsYes = heldStaleVent && currentVariant == StaleVariant.REVERT && !announced && !driverOverrode && !userRequested
        // 맡긴 환기를 취소 없이 원복까지 끝냈다. 쌓여 있던 취소 횟수를 지운다
        val delegatedClean = heldStaleVent && announced && !driverOverrode && !runCancelled && !silentDone
        val silent = silentDone
        closeFreshAir(now)
        // 바깥 공기로 두는 중이었으면(졸음 개입이 겹친 경우) 구간을 다시 연다
        if (freshOpen && !recirculating) ventOnSince = now
        out += WakeEvent.Done(kind, if (silent) null else pendingDoneText ?: text)
        if (ventilated) {
            resetStaleCounter(now)
            // 환기를 막 끝냈으면 추정치가 여전히 높아도 바로 다시 묻지 않는다(인원이 많고 풍량이 약할 때)
            if (kind == TriggerKind.STALE_AIR) {
                cooldownUntil[kind] = maxOf(cooldownUntil[kind] ?: Long.MIN_VALUE, now + unitMs(POST_VENT_COOLDOWN_MINUTES))
            }
        }
        if (countsAsYes) {
            yesGainedThisTrip = true
            updateTrust(trustState.copy(consecutiveYes = trustState.consecutiveYes + 1), out)
        }
        if (delegatedClean && trustState.cancelCount != 0) updateTrust(trustState.copy(cancelCount = 0), out)
        clearPhase()
        if (silent) {
            // 졸음 경고에 자리를 내준 환기였다. 기다리던 졸음 제안을 같은 배치에서 바로 낸다
            gate.clearCooldown()
            urgentPropose = TriggerKind.DROWSY in queue
        }
    }

    /** 바깥 공기가 들어왔으니 내기순환 카운터는 0 부터 다시(DROWSY 안의 Ventilate, 쉼 포함). 큐에 남은 환기 제안도 버린다 */
    private fun resetStaleCounter(now: Long) {
        staleTriggered = false
        queue.remove(TriggerKind.STALE_AIR)
        if (recirculating) recircOnSince = now
    }

    /** 바깥 공기 구간을 닫고 분으로 더한다(반올림, 최소 1) */
    private fun closeFreshAir(now: Long) {
        ventOnSince?.let { since ->
            freshAirMinutes += (since(since, now).toDouble() / 60_000.0).roundToInt().coerceAtLeast(1)
        }
        ventOnSince = null
    }

    private fun clearPhase() {
        phase = Phase.IDLE
        currentKind = null
        currentActions = emptyList()
        runningControls = emptyList()
        results.clear()
        speechFinishedAt = null
        deferredKind = null
        pendingDoneText = null
        revertWhenResolved = false
        heldStaleVent = false
        currentVariant = null
        announced = false
        driverOverrode = false
        runCancelled = false
        silentDone = false
        userRequested = false
        plannedVentSec = null
        ventTargetPpm = null
        proposalPmGrade = null
        proposalPmFirstLabel = null
        restStopRequested = false
    }

    /**
     * 문장이 있는 이벤트(Done, ProposalExpired, RestEnded)를 발화로 기록한다.
     * 그 문장을 읽을 시간 동안은 발화 게이트가 새 제안을 막는다(읽던 문장이 끊기지 않게)
     */
    private fun noteSpoken(now: Long, events: List<WakeEvent>) {
        for (e in events) {
            val text = when (e) {
                is WakeEvent.Done -> e.text
                is WakeEvent.ProposalExpired -> e.text
                is WakeEvent.RestEnded -> e.text
                is WakeEvent.Quiet -> e.text
                else -> null
            } ?: continue
            gate.markUtterance(now, speechHoldMs(text))
        }
    }

    // ---------- 숨 모델 ----------

    /** 숨 모델을 now 까지 전진시키고 최고치와 한도 초과 시간을 쌓는다. 주행 중에만 부른다(쉬는 동안은 부르지 않는다) */
    private fun advanceAir(now: Long) {
        val last = lastAirAdvanceAt
        // 데모(60배)에서도 제안을 읽고 답을 기다리는 동안과 제어 결과를 기다리는 동안은 실제 속도로 흐른다.
        // 이 구간은 사람과 차가 실제 시간으로 움직여서, 60배로 돌리면 몇 초 사이에 추정치가 수천 ppm 부풀려진다
        val realTime = config.demoFast && (phase == Phase.AWAITING_ANSWER || phase == Phase.ANNOUNCING || phase == Phase.EXECUTING)
        air.realTimeInDemo = realTime
        air.advance(now)
        if (last == null) { lastAirAdvanceAt = now; return }
        val dt = now - last
        if (dt <= 0) return
        lastAirAdvanceAt = now
        val c = air.ppmExact()
        if (c > peakPpm) peakPpm = c
        // 한도 위에 머문 시간은 모델 시간으로 쌓는다(demoFast 면 실제 1초 = 1분). 도착 카드용이라 기본 한도를 기준으로 한다
        val modelMs = dt * (if (config.demoFast && !realTime) CabinAirModel.DEMO_TIME_SCALE else 1.0)
        if (c >= config.co2LimitPpm) overLimitModelMs += modelMs
        // 숨 설계: 실내 미세먼지 추정과 그림자 두 갈래를 같은 모델 시간만큼 전진시키고 곡선에 한 점을 남긴다.
        // 입력(내외기, 창문, 바깥 값)은 직전 syncAir 가 옮겨 둔 것이다
        val minutes = modelMs / 60_000.0
        val pmExposureBefore = pm.exposure
        val pmMinutesBefore = pm.minutes
        pm.advanceModelMinutes(minutes)
        // 기침 자리(v0.8.0): 같이 탄 동안만 입자를 만든다. 공기 교환은 이 구간의 실제 상태(내외기, 풍량, 창문)다
        val coughOthers = coughOthers()
        val coughNow = coughOthers > 0
        if (coughOthers > coughOthersMax) coughOthersMax = coughOthers
        bio.advance(minutes, air.ach(), coughNow)
        shadow.setCough(coughNow)
        shadow.advance(minutes)
        modelMinutes += minutes
        trace.add(modelMinutes, air.ppm(), shadow.recircOnly.air.ppm())
        // 자리별 누적(v0.7.3): 같은 추정값을 그 자리에 사람이 있던 시간만큼만 쌓는다. 입력(누가 탔는가)은 이 구간이 시작될 때의 값이다
        ledger.advance(minutes, SeatPolicy.occupied(seatValues, driving = true), c, c >= config.co2LimitPpm, pm.exposure - pmExposureBefore, pm.minutes - pmMinutesBefore)
        noteSits(now)
        if (occupants > tripMaxOccupants) tripMaxOccupants = occupants
    }

    /** 자리마다 지금의 연속 착석을 누적에 알린다(가장 길었던 값만 남는다). 차 전체 줄에는 운전석 값을 적는다 */
    private fun noteSits(now: Long) {
        val unit = unitMs(1).toDouble()
        for (i in 0 until Seats.COUNT) ledger.noteSit(i, sit.seatedMs(i, now) / unit)
        ledger.noteSit(Seats.CABIN, sit.seatedMs(Seats.DRIVER, now) / unit)
    }

    /** 엔진이 아는 차량 상태를 모델 입력으로 옮긴다. 엔진이 환기를 유지하는 동안은 외기로 본다 */
    private fun syncAir() {
        air.occupants = occupants
        air.recirculating = effectiveRecirculating()
        air.fanLevel = fanLevel
        air.fanMax = fanMax
        air.windowPercent = windowPercent
        // 숨 설계: 바깥 값(묵었으면 null)과 같은 차량 상태를 실내 미세먼지 추정과 그림자에 옮긴다
        val out = validOutdoor(lastSeenNow)
        if (out != null && session.state == TripState.DRIVING) lastOutdoorUsed = out
        pm.outdoor = out
        pm.recirculating = air.recirculating
        pm.windowOpen = windowOpen()
        pm.airAch = air.ach()
        shadow.sync(occupants, fanLevel, fanMax, windowPercent, out)
    }

    /** 바깥 공기가 들어오는 상태인가(외기 모드, 엔진 환기 유지 중, 창문 열림) */
    private fun airVentilating(): Boolean = !effectiveRecirculating() || windowOpen()

    /**
     * AirEstimate 를 낼지 정한다. 주행 중에만(쉬는 동안은 내지 않는다).
     * timed(tick): 첫 번째, 5초 경과, ppm 이 50 이상 변함, 인원/환기 상태 변함
     * 신호 쪽: 이미 한 번 낸 뒤 인원/환기 상태가 변했을 때, 또는 쉼이 끝난 직후
     * minutesToLimit 은 지금의 유효 한도(야간 장거리면 낮춘 값) 기준이다
     */
    private fun emitAir(now: Long, out: MutableList<WakeEvent>, timed: Boolean) {
        if (session.state != TripState.DRIVING || resting) return
        val ppm = air.ppm()
        val ventilating = airVentilating()
        val prev = lastAirEvent
        val stateChanged = prev != null && (prev.occupants != occupants || prev.ventilating != ventilating)
        val due = timed && (prev == null ||
            since(lastAirEventAt, now) >= AIR_EVENT_INTERVAL_MS ||
            abs(ppm - prev.ppm) >= AIR_EVENT_PPM_STEP)
        if (!stateChanged && !due && !forceAirEmit) return
        forceAirEmit = false
        val e = WakeEvent.AirEstimate(ppm, occupants, air.minutesToLimit(effectiveLimit(now).roundToInt()), ventilating)
        lastAirEvent = e
        lastAirEventAt = now
        out += e
    }

    /** 100 단위 반올림, 천 단위 쉼표. 2,349 → "2,300" */
    private fun ppmText(ppm: Int): String {
        val rounded = ((ppm + 50) / 100) * 100
        return groupThousands(rounded)
    }

    /** 천 단위 쉼표. JVM 전용 String.format("%,d") 대신 순수 Kotlin 으로(엔진을 Kotlin/JS 로도 컴파일한다). 결과는 같다 */
    private fun groupThousands(n: Int): String {
        val digits = abs(n.toLong()).toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return if (n < 0) "-$sb" else sb.toString()
    }

    // ---------- 핸들 신호 ----------

    /**
     * "steerQuietSec 초 이상 변화폭 1도 안쪽 → 1초 안에 steerJerkDeg 이상 변화" 를 1회로 센다.
     * 세지 않는 경우:
     *  - 깜빡이가 켜져 있거나 직전 3초 안에 깜빡이 변화가 있었다(차로 변경)
     *  - 속도를 아는데 STEER_MIN_SPEED_KPH 미만이다(시내, 램프, 출차). 속도를 모르면 센다
     *  - 무조작 구간의 각도 절댓값이 STEER_STRAIGHT_MAX_DEG 를 넘는다(선회 중에 핸들을 잡고 있던 것)
     */
    private fun onSteering(s: SteeringAngle) {
        val deg = s.degrees
        // 급보정 판정 창이 열려 있으면 먼저 본다
        steerJerkSince?.let { start ->
            if (since(start, s.t) > STEER_JERK_WINDOW_MS) {
                steerJerkSince = null
            } else if (abs(deg - steerJerkBase) >= config.steerJerkDeg) {
                steerJerkSince = null
                countSteerEvent(s.t)
            }
        }
        val quietSince = steerQuietSince
        if (quietSince == null) {
            startSteerQuiet(s.t, deg)
            return
        }
        val lo = minOf(steerQuietMin, deg)
        val hi = maxOf(steerQuietMax, deg)
        if (hi - lo <= STEER_QUIET_BAND_DEG) {
            steerQuietMin = lo; steerQuietMax = hi
            steerLastQuietDeg = deg
            steerLastQuietAt = s.t
            return
        }
        // 무조작이 깨졌다. 직진으로 충분히 길게 가만히 있었으면 여기서부터 1초 동안 급보정인지 본다
        val straight = maxOf(abs(steerQuietMin), abs(steerQuietMax)) <= STEER_STRAIGHT_MAX_DEG
        if (steerJerkSince == null && straight && since(quietSince, s.t) >= config.steerQuietSec * 1000L) {
            // 직전 표본이 1초 안이면 그 사이 변화량을 그대로 믿는다.
            // 표본이 한참 끊겼다 왔으면 얼마나 빨리 꺾었는지 모르니 세지 않고, 지금 각도에서 1초를 새로 본다
            val fresh = since(steerLastQuietAt, s.t) <= STEER_JERK_WINDOW_MS
            if (fresh && abs(deg - steerLastQuietDeg) >= config.steerJerkDeg) {
                countSteerEvent(s.t)
            } else {
                steerJerkSince = s.t
                steerJerkBase = if (fresh) steerLastQuietDeg else deg
            }
        }
        startSteerQuiet(s.t, deg)
    }

    private fun startSteerQuiet(t: Long, deg: Float) {
        steerQuietSince = t
        steerQuietMin = deg; steerQuietMax = deg
        steerLastQuietDeg = deg
        steerLastQuietAt = t
    }

    private fun countSteerEvent(t: Long) {
        val recentTurn = lastTurnSignalAt?.let { since(it, t) <= STEER_TURN_SIGNAL_GUARD_MS } ?: false
        if (turnSignalOn || recentTurn) return
        // 속도를 알 때는 고속 주행에서만 센다. 모르면 센다
        val speed = lastSpeedKph
        if (speed != null && speed < STEER_MIN_SPEED_KPH) return
        steerEventTimes.addLast(t)
    }

    private fun pruneSteerEvents(now: Long) {
        val window = steerWindowMs()
        while (steerEventTimes.isNotEmpty() && since(steerEventTimes.first(), now) > window) steerEventTimes.removeFirst()
    }

    /** 핸들 급보정을 모으는 창. 10분, demoFast 면 60초 */
    private fun steerWindowMs(): Long = if (config.demoFast) 60_000L else STEER_WINDOW_MINUTES * 60_000L

    // ---------- 도착 카드 ----------

    private fun buildSummary(now: Long): ArrivalSummary {
        val tripMinutes = (tripElapsedMs(now) / 60_000L).toInt()
        val peak = peakPpm.roundToInt()
        val overMinutes = (overLimitModelMs / 60_000.0).roundToInt()
        val limitText = "추정 ${ppmText(config.co2LimitPpm)}ppm"
        val headline = when {
            ventilations > 0 && restStopsVisited > 0 -> "오늘 환기 ${ventilations}번, 쉼터에서 ${restMinutesTotal}분 쉬었어요."
            restStopsVisited > 0 -> "오늘 쉼터에서 ${restMinutesTotal}분 쉬었어요."
            ventilations > 0 && drowsySignals > 0 -> "오늘 환기 ${ventilations}번, 졸음 경고 ${drowsySignals}번이 있었어요."
            ventilations > 0 && peak < config.co2LimitPpm -> "오늘 차 안 공기를 $limitText 아래로 지켰어요."
            ventilations > 0 && overMinutes > 0 -> "오늘 환기 ${ventilations}번, $limitText 을 넘긴 건 ${overMinutes}분이었어요."
            ventilations > 0 -> "오늘 환기 ${ventilations}번 했어요."
            drowsySignals > 0 -> "오늘 졸음 경고 ${drowsySignals}번이 있었어요."
            declined > 0 && overMinutes > 0 -> "오늘은 $limitText 위로 ${overMinutes}분 달렸어요. 무사히 도착했어요."
            declined > 0 -> "오늘은 제안 ${declined}번을 넘기셨어요. 그래도 무사히 도착했어요."
            else -> "오늘은 개입 없이 무사히 도착했어요."
        }
        // 공기 줄은 넣지 않는다. 앱이 peakPpm 으로 한 줄만 보여 준다. 로그는 마지막 4줄까지
        val recent = log.takeLast(4).map { (t, what) -> "${relative(t)} $what" }
        // 이번 주행에서 맡기기가 꺼졌으면 그 사실을 맨 앞에 알린다
        val lines = if (trustTurnedOff) listOf(TRUST_OFF_LINE) + recent else recent
        // "2분만" 환기에 연달아 동의해 왔고 아직 맡기지 않았으면 카드에서 맡길지 묻는다
        // 이번 주행에서 연속 동의가 늘었을 때만 싣는다(환기가 없던 주행의 카드에 지난 일로 묻지 않는다)
        val trustOffer = if (!trustState.delegated && yesGainedThisTrip && trustState.consecutiveYes >= TRUST_OFFER_YES) TRUST_OFFER_TEXT else null
        // 자리별 누적(v0.7.3). 차가 자리별 값을 준 주행에만 자리마다 한 줄, 아니면 차 전체 한 줄만 쓴다
        noteSits(now)
        val seatRows = if (seatValues != null) ledger.seats(seatProfiles) else emptyList()
        val riders = if (seatRows.isNotEmpty()) seatRows.size else maxOf(tripMaxOccupants, occupants, 1)
        return ArrivalSummary(
            headline = headline,
            tripMinutes = tripMinutes,
            ventilations = ventilations,
            freshAirMinutes = freshAirMinutes,
            drowsySignals = drowsySignals,
            restStopsAdded = restStopsAdded,
            restStopsVisited = restStopsVisited,
            restMinutes = restMinutesTotal,
            restStopsPassed = restStopsPassed,
            declined = declined,
            lines = lines,
            peakPpm = peak,
            minutesOverLimit = overMinutes,
            trustOffer = trustOffer,
            // 세 갈래 비교: 같은 입력의 그림자 두 갈래와 이번 주행. 미세먼지 칸은 바깥 값을 받은 동안만 쌓인 값이다
            threeWay = ThreeWay(
                recircOnly = shadow.recircOnly.stats(),
                freshOnly = shadow.freshOnly.stats(),
                actual = WayStats(peak, overMinutes, pm.average()?.let { pm.exposure }, pm.average()),
                pmGrade = lastOutdoorUsed?.let { VentPlanner.grade(it) },
                pmOutdoor = lastOutdoorUsed?.roundToInt(),
            ),
            seats = seatRows,
            // 차 전체 줄의 미세먼지는 threeWay.actual 과 같은 값이라 여기에 한 번 더 싣지 않는다
            cabin = ledger.cabin()?.copy(pmAverage = null, pmExposure = null),
            seatHeadline = "오늘 ${tripLengthText(tripMinutes)}, ${SeatPolicy.countWord(riders)} 탔어요.",
            walkBreaks = walkBreaks,
            infection = if (bio.sharedMinutes > 0.0) InfectionStats(
                actualPercent = bio.riskPercent(),
                recircOnlyPercent = shadow.recircOnly.bio.riskPercent(),
                freshOnlyPercent = shadow.freshOnly.bio.riskPercent(),
                sharedMinutes = bio.sharedMinutes.roundToInt(),
                others = coughOthersMax,
            ) else null,
        )
    }

    /** "2시간 10분", "2시간", "45분". 1분이 안 되면 "잠깐" */
    private fun tripLengthText(minutes: Int): String = when {
        minutes < 1 -> "잠깐"
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0 -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }

    private fun relative(t: Long): String {
        val min = (since(session.startedAt, t) / 60_000L).toInt()
        return if (min < 1) "출발 직후" else "출발 ${min}분 뒤"
    }

    /** 로그에 적는 제안 이름. 차의 졸음 경고에서 온 것이 아니면(핸들 경로) 졸음이라고 적지 않는다 */
    private fun label(kind: TriggerKind) = when (kind) {
        TriggerKind.STALE_AIR -> "환기"
        TriggerKind.DROWSY -> if (currentDrowsySource == DrowsySource.TELLTALE) "졸음" else "쉬어 가기"
        TriggerKind.WALK_BREAK -> "걷기 쉼"
    }

    /** 액션 식별 키. 같은 종류의 액션은 파라미터가 달라도 하나로 본다 */
    private fun key(a: Action): String = a::class.simpleName ?: a.toString()

    // ---------- 시간 ----------

    /** 분 단위 설정값을 ms 로. demoFast 면 분을 초로 읽는다 */
    private fun unitMs(minutes: Int): Long = if (config.demoFast) minutes * 1000L else minutes * 60_000L

    /** 단조 시계 차이. 뒤로 가면 0 */
    private fun since(from: Long, now: Long): Long = (now - from).coerceAtLeast(0L)

    private fun executeTimeoutMs(): Long = if (config.demoFast) EXECUTE_TIMEOUT_DEMO_MS else EXECUTE_TIMEOUT_MS

    /** 쉼 시간 합(끝난 쉼 + 지금 쉬는 중이면 그것까지) */
    private fun restElapsedMs(now: Long): Long = restTotalMs + if (resting) since(restStartedAt, now) else 0L

    /** 트립 주행 시간(쉼 제외). 앱 타이머가 기준이고 TripTimeUpdate(주행 시간)는 보조(더 큰 쪽) */
    private fun tripElapsedMs(now: Long): Long =
        maxOf((session.elapsedMs(now) - restElapsedMs(now)).coerceAtLeast(0L), tripTimeReportedMs)

    companion object {
        const val MAX_DROWSY_PER_TRIP = 3
        /** 핸들 신호로 내는 보조 제안의 세션당 상한 */
        const val MAX_STEER_DROWSY_PER_TRIP = 2
        /** 핸들 신호를 보는 최소 트립 시간(분, demoFast 면 초) */
        const val STEER_MIN_TRIP_MINUTES = 30
        /** 핸들 급보정을 모으는 창(분). demoFast 면 60초 */
        const val STEER_WINDOW_MINUTES = 10
        /** 무조작으로 보는 각도 변화폭(도) */
        const val STEER_QUIET_BAND_DEG = 1.0f
        /** 무조작이 깨진 뒤 급보정으로 인정하는 시간 */
        const val STEER_JERK_WINDOW_MS = 1_000L
        /** 깜빡이 변화 뒤 이 시간 안의 급보정은 세지 않는다 */
        const val STEER_TURN_SIGNAL_GUARD_MS = 3_000L
        /** 속도를 알 때 핸들 급보정을 세는 최저 속도(km/h). 모르면 센다 */
        const val STEER_MIN_SPEED_KPH = 60.0
        /** 무조작 구간의 각도 절댓값이 이 값(도)을 넘으면 선회 중으로 보고 세지 않는다 */
        const val STEER_STRAIGHT_MAX_DEG = 5.0f
        /** 야간 장거리(하향등 ON, 트립 longTripMinutes 이상)일 때 환기 한도에 곱하는 값 */
        const val NIGHT_LIMIT_FACTOR = SeatPolicy.NIGHT_LIMIT_FACTOR
        /** 야간 장거리라 평소보다 일찍 묻게 된 첫 환기 제안에 붙이는 접두 */
        const val NIGHT_PREFIX = "오래 달려서 조금 일찍 물어요. "
        /** 재도달 예측이 이 값(분, 모델 시간) 미만이면 "2분만" 대신 바깥 공기로 둘지 묻는다 */
        const val RECURRENCE_KEEP_MINUTES = 5.0
        /** 바깥 미세먼지가 좋음일 때의 같은 기준(분). 바깥 공기로 두는 쪽으로 더 쉽게 기운다 */
        const val RECURRENCE_KEEP_MINUTES_GOOD = 10.0
        /** 이 값(µg/m³) 이상은 잘못 온 값으로 보고 버린다 */
        const val PM_MAX_PLAUSIBLE = 2000.0
        /** 쉼 모드의 낮잠 타이머(분, demoFast 면 초). 15분이 안 되는 낮잠과 카페인을 본 연구에 맞춘 길이다(I 절) */
        const val NAP_MINUTES = 15
        private const val MINUTES_PER_DAY = 1440L
        /** 환기를 끝낸 뒤 다음 환기 제안까지 최소 간격(분, demoFast 면 초) */
        const val POST_VENT_COOLDOWN_MINUTES = 5
        /** "계속 바깥 공기로 둘까요?" 로 바꾸는 조건: 탑승 인원과 이번 세션에서 끝낸 "2분만" 환기 횟수 */
        const val KEEP_FRESH_MIN_OCCUPANTS = 2
        /** 기침 자리(v0.8.0): CO2 추정이 처음 값보다 이만큼 오르면 바로 묻는다(넷이면 1분 안쪽) */
        const val COUGH_ASK_RISE_PPM = 150.0
        const val COUGH_KEEP_ASK = "기침하는 자리가 있어요. 다른 자리로 숨이 덜 가게 바깥 공기로 계속 둘까요?"
        const val COUGH_KEEP_LOW_ASK = "기침하는 자리가 있어요. 밖 미세먼지가 나빠서 약한 바람으로 바깥 공기를 계속 들일까요?"
        const val KEEP_FRESH_AFTER_VENTS = 2
        const val KEEP_FRESH_PROPOSAL = "여럿이 탈 땐 바깥 공기로 두는 게 좋아요. 계속 바깥 공기로 둘까요?"
        /** 바깥 공기로 두기(KEEP, KEEP_AFTER_TWO)에 동의한 뒤의 Done 문장. Running 과 원복이 없다 */
        const val KEEP_FRESH_DONE = "바깥 공기로 둘게요."
        /** 약한 바람으로 바깥 공기를 계속 들이기(KEEP_LOW, v0.7.4)의 질문 끝과 동의한 뒤의 Done 문장. Running 과 원복이 없다 */
        const val KEEP_LOW_ASK = "약한 바람으로 바깥 공기를 계속 들일까요?"
        const val KEEP_LOW_DONE = "약한 바람으로 바깥 공기를 들일게요."
        /** 풍량 올리기(FAN_UP): 올리는 단 수, 동의한 뒤의 Done 문장, 그 뒤 쿨다운(분, demoFast 면 초). Running 과 원복이 없다 */
        const val FAN_UP_DELTA = 2
        const val FAN_UP_DONE = "풍량을 올려 둘게요."
        const val FAN_UP_COOLDOWN_MINUTES = 10
        /** 맡기기를 권하는 연속 동의 횟수, 맡기기가 꺼지는 취소 횟수 */
        const val TRUST_OFFER_YES = 3
        const val TRUST_OFF_CANCELS = 2
        const val TRUST_OFFER_TEXT = "오늘까지 환기 제안 3번에 모두 '응' 하셨어요. 다음부터 2분 환기는 말만 하고 바꿀까요?"
        const val TRUST_OFF_LINE = "맡기기를 껐어요. 다시 물어볼게요."
        /** 맡겨 둔 환기를 예고한 뒤 취소를 기다리는 시간(초, SpeechFinished 이후), 취소했을 때의 Done 문장 */
        const val ANNOUNCE_CANCEL_SEC = 5
        const val ANNOUNCE_CANCELLED_TEXT = "알겠어요. 그대로 둘게요."
        /** 예고로 시작한 실행 중에 취소해서 원복까지 끝났을 때의 Done 문장 */
        const val RUN_CANCELLED_TEXT = "알겠어요. 원래대로 돌렸어요."
        /** 예고 창이 끝났는데 실행하면 안 되는 상황이라 닫았을 때의 쿨다운(분, demoFast 면 초) */
        const val ANNOUNCE_SKIP_COOLDOWN_MINUTES = 5
        /** 예고 문장을 읽는 데 드는 최소 시간(글자당 ms). SpeechFinished 가 이보다 일찍 오면 읽지 못한 것으로 본다 */
        const val ANNOUNCE_MIN_READ_MS_PER_CHAR = 100L
        /** 한 주행에서 환기 제안을 이만큼 연속으로 거절하면 그 주행에서는 더 묻지 않는다 */
        const val QUIET_AFTER_DECLINES = 2
        const val QUIET_TEXT = "이번 주행에서는 더 묻지 않을게요. 화면에만 표시할게요."
        /** 앱 화면이 안 보이는 동안 만료된 제안 뒤의 쿨다운(분, demoFast 면 초) */
        const val HIDDEN_EXPIRY_COOLDOWN_MINUTES = 5
        /** 화면 밖 만료가 이만큼 연속이면 거절과 같은 쿨다운(declineCooldownMinutes)을 건다 */
        const val HIDDEN_EXPIRY_LONG_AFTER = 2
        /** 조용 모드에서 추정치가 유효 한도의 이 배수를 넘으면 한 번 알린다 */
        const val QUIET_NOTICE_FACTOR = 2.0
        /** 조용 모드에 들어간 뒤 이 시간(분, demoFast 면 초) 안에는 한도 초과 알림을 내지 않는다 */
        const val QUIET_NOTICE_MIN_GAP_MINUTES = 5
        /** 받은 지 이 시간(분, demoFast 면 초)이 지난 도착 예정은 쓰지 않는다 */
        const val ETA_STALE_MINUTES = 10
        /** 쉼 모드에 들어갈 때의 바깥 공기 질문과 답을 기다리는 시간(초) */
        const val REST_AIR_PROPOSAL = "쉬는 동안 바깥 공기로 둘까요?"
        const val REST_AIR_TIMEOUT_SEC = 20
        /** 목적지까지 이 값(분) 이하로 남으면 환기 제안을 내지 않는다 */
        const val ARRIVING_SOON_MINUTES = 5
        /** 쉼터까지 걸리는 시간을 어림할 때 쓰는 속도(km/h) */
        const val REST_STOP_SPEED_KPH = 100.0
        /** 하향등이 켜진 뒤 야간으로 보기까지, 그리고 주행 중 막 켜졌을 때 환기 제안을 미루는 시간(분, demoFast 면 초) */
        const val LIGHTS_SETTLE_MINUTES = 3
        /** 환기 제안 보류 사유(Deferred.reason) */
        const val HOLD_ARRIVING = "arriving"
        const val HOLD_CAR_AUTO = "car-auto"
        const val HOLD_LIGHTS = "lights-just-on"
        /** 조수석에 사람이 있을 때 그 주행의 첫 제안 끝에 한 번 붙이는 말 */
        const val PASSENGER_HINT = " 옆자리에서 눌러 주셔도 돼요."
        /** 원복 성공 문장, 무응답 문장 */
        const val REVERTED_TEXT = "원래대로 돌렸어요."
        const val EXPIRED_TEXT = "다음에 다시 물을게요."
        /** 졸음 개입 문장의 첫머리: 차의 졸음 경고, 핸들 경로 */
        const val DROWSY_LEAD = "졸음 경고가 떴어요."
        const val STEER_LEAD = "핸들 보정이 잦아졌어요."
        /** 세션이 끝난 뒤 이 시간(분, demoFast 면 초) 안에 다시 출발하면 추정치를 이어 간다 */
        const val RESTART_KEEP_MINUTES = 3
        /** AirEstimate 주기와 값 변화 기준 */
        const val AIR_EVENT_INTERVAL_MS = 5_000L
        const val AIR_EVENT_PPM_STEP = 50
        /** 원복 결과 대기 상한 */
        const val REVERT_TIMEOUT_MS = 20_000L
        /** 실행 결과 대기 상한 */
        const val EXECUTE_TIMEOUT_MS = 60_000L
        const val EXECUTE_TIMEOUT_DEMO_MS = 30_000L
        /** SpeechFinished 가 안 올 때 Propose 기준으로 더해 주는 여유(초)의 최솟값. 실제 값은 speechGraceSec(text) */
        const val SPEECH_GRACE_MIN_SEC = 6
        /** 글자당 TTS 여유(초) */
        const val SPEECH_GRACE_PER_CHAR_SEC = 0.2
        /** 제안이 아닌 문장(Done, RestEnded, ProposalExpired)을 읽는 동안 새 제안을 막는 시간의 최솟값 */
        const val SPEECH_HOLD_MIN_MS = 2_000L
        /** Propose 직후 이 시간 안에 온 SpeechFinished 는 앞 문장의 잔재로 보고 무시 */
        const val SPEECH_ECHO_MS = 500L
        /** 실행 전부 실패 뒤 쿨다운(분, demoFast 면 초) */
        const val FAIL_COOLDOWN_MINUTES = 5
        /** 같은 쉼터로 보는 좌표 거리(m) */
        const val SAME_STOP_RADIUS_M = 50.0
        private const val METERS_PER_DEGREE = 111_320.0
        /** 도 → 라디안. java.lang.Math.toRadians 와 같은 상수(순수 Kotlin) */
        private const val DEG_TO_RAD = PI / 180.0

        /** 문장 길이 기반 TTS 여유(초). 최소 SPEECH_GRACE_MIN_SEC */
        fun speechGraceSec(text: String): Int =
            maxOf(SPEECH_GRACE_MIN_SEC.toDouble(), text.length * SPEECH_GRACE_PER_CHAR_SEC).toInt()

        /** 제안이 아닌 문장을 읽는 동안 새 제안을 막는 시간(ms). 글자당 0.2초, 최소 SPEECH_HOLD_MIN_MS */
        fun speechHoldMs(text: String): Long =
            maxOf(SPEECH_HOLD_MIN_MS, (text.length * SPEECH_GRACE_PER_CHAR_SEC * 1000.0).toLong())

        /** 쉼 카드에 보여 주는 낮잠 안내. 연구가 있다는 데까지만 말한다(효과를 단정하지 않는다) */
        fun napTip(minutes: Int = NAP_MINUTES): String = "커피 한 잔 뒤 ${minutes}분 안쪽으로 눈을 붙이면 도움이 된다는 연구가 있어요."

        /** 낮잠 타이머가 끝났을 때 한 번 알리는 말 */
        fun napDoneText(minutes: Int = NAP_MINUTES): String = "${minutes}분 지났어요. 몸을 풀고 출발해요."

        /** 한 번 앉은 동안 걷기 쉼을 묻는 횟수의 상한. 일어서면 다시 센다 */
        const val MAX_WALK_ASKS_PER_SIT = 2
        /** 걷기 쉼에 동의해 쉼터를 경유지로 넣었을 때, 넣지 못했을 때의 Done 문장 */
        const val WALK_ADDED_TEXT = "쉼터를 경유지에 넣었어요. 도착하면 내려서 걸어요."
        const val WALK_FAILED_TEXT = "지금은 경유지를 넣을 수 없어요. 조금 뒤에 다시 볼게요."
        /** 직접 요청한 쉼터 경유(requestRestStop)가 들어갔을 때의 Done 문장 */
        const val REST_STOP_ADDED_TEXT = "쉼터를 경유지에 넣었어요."

        /** 걷기 쉼의 쉼 카드 안내. 차에서 본 것(앉은 시간)과 권하는 것만 말한다. 효과를 약속하지 않는다 */
        fun walkTip(minutes: Int): String = "오래 앉아 있었어요. 차에서 내려 ${minutes}분쯤 걸어요."

        /** 걷기 쉼 타이머가 끝났을 때 한 번 알리는 말 */
        fun walkDoneText(minutes: Int): String = "${minutes}분 지났어요. 다 걸었으면 출발해요."

        /** 쉼터 표시 이름. 패키지 최상위 함수 restStopDisplayName 과 같다 */
        fun restStopDisplayName(name: String): String = ai.kkaeugi.engine.restStopDisplayName(name)
    }
}

package ai.kkaeugi.engine

/**
 * 엔진 ↔ 앱 계약 (v4). 엔진 모듈과 앱 모듈이 주고받는 신호, 행동, 이벤트, 설정을 이 파일 하나에 모아 둔다.
 * 계약을 바꾸면 엔진(WakeEngine)과 앱(WakeViewModel, VehicleActuator) 양쪽을 함께 고친다.
 *
 * v4 에서 바뀐 것:
 *  - 맡기기: TrustState, WakeEngine.setTrust / trust, 신호 TrustAnswer, 이벤트 Announce(예고 뒤 취소 창), TrustChanged(앱이 저장),
 *    ArrivalSummary.trustOffer(도착 카드에서 맡길지 묻는 문장)
 *  - 조용 모드: 이벤트 Quiet(한 주행에서 환기 제안을 두 번 연속 거절하면 한 번). 신호 AppVisibility(화면 밖 무응답은 세지 않음).
 *    야간 장거리에 환기 제안을 넘기면 졸음 제안을 내던 보조 트리거와 WakeConfig.ignoredVentilationsThreshold 는 없앴다
 *  - 신호 DestinationEta(도착 5분 전이면 환기 제안을 내지 않음), FrontPassengerChanged(Propose.passengerMayAnswer),
 *    AutoClimateChanged(차의 자동 내외기 제어 중에는 환기 제안을 내지 않음)
 *  - WakeConfig.steeringAssist(기본 false: SteeringAngle 무시), WakeConfig.allowWindow(기본 false: 졸음 개입에서 창문 제외)
 *  - 하향등이 켜진 지 3분이 안 됐으면 야간으로 보지 않고, 주행 중 막 켜졌으면 3분 동안 환기 제안을 미룬다
 *  - 쉼 모드에 들어갈 때 내기순환이면 "쉬는 동안 바깥 공기로 둘까요?" 를 한 번 묻는다(Propose, timeoutSec 20)
 *
 * v5(v0.7.0 "숨 설계")에서 더한 것. 전부 기본값이 있어서 바깥 미세먼지를 넣지 않으면 v4 와 똑같이 움직인다:
 *  - 신호 OutdoorAir(바깥 초미세먼지 PM2.5, 격자 모델값), LocalClock(지금 시각. 졸리기 쉬운 시간대 가중에만 쓴다)
 *  - 읽기 전용 결과: WakeEngine.breath(now) 가 BreathDesign(환기 설계 VentPlan, 여정 예보 Forecast, 그림자 곡선, 낮잠 안내)을 돌려준다.
 *    새 WakeEvent 는 만들지 않았다(이벤트 목록을 그대로 견주는 테스트와 시뮬레이터 다리가 있어서). 앱은 tick 마다 breath 를 읽는다
 *  - ArrivalSummary.threeWay(내기만 / 외기만 / 이번 주행 비교), WakeConfig 의 pm* 값
 *
 * v6(v0.7.3 "자리별로 줄인다")에서 더한 것. 프로필이 전부 기본이고 WakeConfig.walkBreak 가 꺼져 있으면(둘 다 기본값) v5 와 똑같이 움직인다:
 *  - 자리별 프로필 SeatProfile(자리 다섯 칸마다 하나), 신호 SeatProfilesChanged. 탄 자리의 프로필만 쓴다.
 *    프로필은 문헌에서 가져온 기준 숫자가 아니다. 무엇을 먼저 볼지와 여유를 얼마나 둘지를 바꾸는 설정이다
 *  - 신호 SeatOccupancyChanged(자리별 탑승 값), DoorChanged(문 열림). 앉은 시간(SitClock)과 자리별 누적(SeatLedger)에 쓴다
 *  - TriggerKind.WALK_BREAK: 오래 앉아 있으면 쉼터에서 내려 걷기를 묻는다(WakeConfig.walkBreak 를 켰을 때만). 실행은 AddRestStop 하나뿐이다
 *  - ArrivalSummary.seats / cabin / seatHeadline(자리별 누적), RestStarted.walk, 읽기 전용 WakeEngine.care(now)
 *
 * 엔진: 신호(Signal)를 받아 WakeEvent 를 낸다. Android 의존 없음.
 * 앱: WakeEvent.Execute 를 실제 차량 제어로 바꾸고, 운전자 동의를 UserAnswer 로 엔진에 돌려준다.
 *
 * 시계: Signal.t 와 tick(now) 는 같은 단조 시계(앱에서는 SystemClock.elapsedRealtime())를 쓴다.
 *       벽시계(System.currentTimeMillis)는 NTP 보정으로 튀므로 쓰지 않는다.
 */

// ---------- 신호 (앱 → 엔진). 기존 Signals.kt 의 Gear/Brake/TurnSignal/Wiper/LowBeam/Telltale/Nav* 도 그대로 쓴다 ----------

/** HVAC 내기순환 상태. true = 내기순환(밀폐). */
data class RecirculationChanged(override val t: Long, val recirculating: Boolean) : Signal
/** 트립 주행 시간(분). Odometer.registerTripCurrentDrivingTime 또는 앱 타이머. */
data class TripTimeUpdate(override val t: Long, val minutes: Int) : Signal
/** 운전자 답변. STT 또는 화면 버튼. */
data class UserAnswer(override val t: Long, val yes: Boolean) : Signal
/** 앱이 제어를 실제로 끝냈을 때(성공/실패). 엔진은 이걸 받아야 "실행 중" 상태로 넘어간다.
 *  detail 은 실패 사유 코드 문자열(예: "DRIVING_RESTRICTED", "PERMISSION", "TIMEOUT"). */
data class ActionResult(override val t: Long, val action: Action, val ok: Boolean, val detail: String? = null) : Signal
/** 앱의 TTS 가 제안 문장을 다 읽었을 때. 엔진은 이 시점부터 answerTimeoutSec 를 센다.
 *  (안 오면 Propose 시각 + answerTimeoutSec + 문장 길이에 따른 여유(글자당 0.2초, 최소 6초)를 상한으로 쓴다.) */
data class SpeechFinished(override val t: Long) : Signal
/** 앱이 주기적으로(주행 중 30초마다, 또는 1km 이상 이동했을 때) 보내는 "전방 같은 방향 최근접 졸음쉼터".
 *  stop == null 이면 전방 maxKm 안에 없음. 엔진은 DROWSY 제안 문장과 AddRestStop 포함 여부에 쓴다. */
data class RestStopCandidate(override val t: Long, val stop: RestStopInfo?) : Signal
/** 경유지로 넣은 쉼터와의 근접 상태. within = 300m 안. passed = 1km 안까지 갔다가 멀어짐(지나침). */
data class RestStopProximity(override val t: Long, val within: Boolean, val passed: Boolean = false) : Signal

// ---------- 계약 v3: 숨 모델(탑승 인원 기반 실내 CO2 추정)과 핸들 신호 ----------

/** 탑승 인원. 좌석별로 Seat.getCurrentSeatOccupancy, 값이 없으면 getCurrentSeatBeltBuckledState 로 센다(CAR_SEATS, 5초마다, 두 번 연속 같은 값일 때 발행). 모르면 1. */
data class OccupantsChanged(override val t: Long, val count: Int) : Signal
/** 공조 풍량 단계. max 는 차량이 알려준 최대 단계. 외기 유입량 추정에 쓴다. */
data class FanSpeedChanged(override val t: Long, val level: Int, val max: Int) : Signal
/** 창문 열림 정도(0~100, 네 창 중 최댓값). 5% 이상이면 환기되는 것으로 본다. */
data class WindowOpenChanged(override val t: Long, val percent: Int) : Signal
/** 핸들 각도(도). SteeringWheel.registerSteeringWheelAngle. 엔진은 "오래 무조작 뒤 급보정" 패턴만 센다. WakeConfig.steeringAssist 가 false 면 무시한다. */
data class SteeringAngle(override val t: Long, val degrees: Float) : Signal

/** 졸음쉼터 한 곳. routeLabel 은 "경부선 서울방향" 꼴. */
data class RestStopInfo(
    val name: String,
    val routeLabel: String,
    val distanceKm: Double,
    val lat: Double,
    val lng: Double,
)

// ---------- 계약 v4: 맡기기, 화면 상태, 도착 예정, 동승자, 차의 자동 내외기 ----------

/**
 * 맡기기 상태. 엔진에는 저장소가 없어서 앱이 시작할 때 WakeEngine.setTrust 로 넣고, WakeEvent.TrustChanged 가 올 때마다 저장한다.
 * delegated: "2분만" 환기(원복이 있는 변형)는 묻지 않고 예고만 하고 바꾼다.
 * consecutiveYes: "2분만" 환기 제안에 동의해서 원복까지 끝낸 연속 횟수(주행을 넘어 쌓인다).
 * cancelCount: 맡긴 뒤 예고나 실행을 취소한 횟수. 2 가 되면 맡기기가 꺼진다.
 */
data class TrustState(val delegated: Boolean = false, val consecutiveYes: Int = 0, val cancelCount: Int = 0)

/** 도착 카드의 맡기기 질문(ArrivalSummary.trustOffer)에 사용자가 답했다. accept = [맡길게요], false = [계속 물어봐 주세요].
 *  세션 상태와 무관하게 받는다. */
data class TrustAnswer(override val t: Long, val accept: Boolean) : Signal
/** 앱 화면(제안 버튼)이 운전자에게 보이는가. 다른 앱이 앞에 있으면 false. 보내지 않으면 보이는 것으로 본다. */
data class AppVisibility(override val t: Long, val visible: Boolean) : Signal
/** 내비 목적지까지 남은 분. null = 목적지 없음. 값이 바뀔 때마다(1분 단위면 충분) 보낸다. */
data class DestinationEta(override val t: Long, val remainMinutes: Int?) : Signal
/** 조수석에 사람이 있는가(좌석 점유 또는 벨트). */
data class FrontPassengerChanged(override val t: Long, val present: Boolean) : Signal
/** 차가 내외기를 스스로 제어하는 중인가(공조 AUTO 의 자동 내외기, 공기질 연동 등). */
data class AutoClimateChanged(override val t: Long, val autoRecirculation: Boolean) : Signal

// ---------- 계약 v5: 숨 설계(바깥 미세먼지, 지금 시각) ----------

/**
 * 바깥 초미세먼지(PM2.5, µg/m³). 차에 센서가 없어서 앱이 날씨 서비스의 격자 모델값(CAMS, 약 45km 격자)을 받아 넣는다. 도로변 실측이 아니다.
 * pm25UgM3 == null 이면 값 없음. ageMin 은 t 시점에 이 값이 얼마나 묵었는지(분).
 * 값이 없거나 WakeConfig.pmStaleMinutes(60분, 실제 시간)보다 묵었으면 엔진은 미세먼지 관련 로직을 전부 끈다(v0.6.2 와 같은 동작).
 */
data class OutdoorAir(override val t: Long, val pm25UgM3: Double?, val source: String = "", val ageMin: Int = 0) : Signal

/** 지금 시각(자정부터 흐른 분, 0~1439). null 이면 모름. "졸리기 쉬운 시간대" 가중(AlertnessWeight)에만 쓴다. 보내지 않으면 하향등으로만 어림한다 */
data class LocalClock(override val t: Long, val minuteOfDay: Int?) : Signal

/** 바깥 초미세먼지 등급(한국 예보 기준, PM2.5 µg/m³): 좋음 0~15, 보통 16~35, 나쁨 36~75, 매우 나쁨 76 이상 */
enum class PmGrade(val label: String) { GOOD("좋음"), MODERATE("보통"), BAD("나쁨"), VERY_BAD("매우 나쁨") }

/**
 * 환기 설계 결과. "이 인원이면 intervalMin 분마다 durationSec 초" 꼴의 듀티 사이클이다. 전부 추정.
 * intervalMin: 환기를 시작하고 다음 환기를 시작하기까지(모델 시간, 분. 데모 속도면 실제 초와 같다). 지금 조건으로 한도에 닿지 않으면 null.
 * durationSec: 한 번 바꿔 두는 길이(실제 초). reason: 왜 이렇게 골랐는지 한 줄. pmGrade: 바깥 미세먼지 등급(값이 없으면 null).
 * keepFresh: 짧게 바꿔도 금방 다시 차서 바깥 공기로 두는 쪽을 권한다(미세먼지 나쁨 이상에서는 3명 이상이고 금방 다시 찰 때만 권한다).
 * targetPpm: "필요한 만큼만" 환기할 때 내려가면 되돌리는 CO2 목표(나쁨 이상에서만. 아니면 null)
 * keepFanLevel: 약한 바람으로 바깥 공기를 계속 들이는 설계일 때 맞출 풍량 단(v0.7.4. 나쁨 이상, 3명 이상, 금방 다시 찰 때만. 아니면 null)
 */
data class VentPlan(
    val intervalMin: Double?,
    val durationSec: Int,
    val reason: String,
    val pmGrade: PmGrade?,
    val keepFresh: Boolean = false,
    val targetPpm: Int? = null,
    val keepFanLevel: Int? = null,
)

/** 여정 예보의 한 점. minute: 지금부터 흐른 분(모델 시간), venting: 그때 바깥 공기로 바꿔 둔 중인가 */
data class ForecastPoint(val minute: Double, val ppm: Int, val venting: Boolean = false)

/**
 * 여정 예보. 지금 인원과 상태가 이어지고 제안에 모두 동의한다고 가정한 앞으로의 CO2 곡선(추정).
 * horizonMin: 내다본 길이(분). toArrival 이면 목적지까지 남은 시간, 아니면 60분 창.
 * ventCount: 그동안 환기 횟수(약), firstVentInMin: 첫 환기까지 남은 분(없으면 null). points 는 그래프용(1분 간격, 앞쪽 일부)
 */
data class Forecast(
    val horizonMin: Int,
    val toArrival: Boolean,
    val ventCount: Int,
    val firstVentInMin: Int?,
    val points: List<ForecastPoint> = emptyList(),
    val keepFresh: Boolean = false,
)

/** 지나온 곡선의 한 점. minute: 주행을 시작하고 흐른 분(모델 시간, 쉼 제외). recircOnlyPpm: 같은 입력으로 내내 내기만 썼다면(그림자) */
data class TracePoint(val minute: Double, val ppm: Int, val recircOnlyPpm: Int)

/**
 * 졸리기 쉬운 시간대 가중(0~1). 졸음을 알아낸 값이 아니다. 하루 중 시각과 연속 주행 시간만으로 낸 어림이다.
 * circadian: 시각 성분, drive: 연속 주행 성분, clockKnown: 시각을 받았는가(못 받았으면 하향등으로 어림), sleepyHours: weight 가 기준 이상
 */
data class Alertness(val weight: Double, val circadian: Double, val drive: Double, val clockKnown: Boolean, val sleepyHours: Boolean)

/** 쉼 모드의 낮잠 안내. text 는 화면 문구, remainingSec 은 낮잠 타이머 남은 초, done 이면 doneText 를 한 번 알린다 */
data class NapGuide(val text: String, val remainingSec: Int, val done: Boolean, val doneText: String)

/** 엔진이 지금 쓰고 있는 바깥 공기 값. cabinPm 은 실내 PM2.5 추정(µg/m³) */
data class OutdoorView(val pm25: Double, val grade: PmGrade, val source: String, val ageMin: Int, val cabinPm: Double?)

/** 세 갈래 비교의 한 갈래. pmExposure: 실내 PM2.5 노출량(µg/m³·분), pmAverage: 그 평균(µg/m³). 바깥 값이 없었으면 null */
data class WayStats(val peakPpm: Int, val minutesOverLimit: Int, val pmExposure: Double? = null, val pmAverage: Double? = null)

/**
 * 기침 자리 감염 위험(v0.8.0, 전부 추정). 기침하는 자리와 다른 사람이 같이 탄 주행에만 있다.
 * 값은 기침하지 않는 사람 한 명의 추정 위험(%). Knibbs 외 2012 의 식과 인플루엔자 중앙값(q 67)으로 계산한다.
 * actual: 이번 주행, recircOnly: 내내 내기만 했다면, freshOnly: 내내 외기만 했다면. others: 기침하지 않는 동승자 수
 */
data class InfectionStats(
    val actualPercent: Double,
    val recircOnlyPercent: Double,
    val freshOnlyPercent: Double,
    val sharedMinutes: Int,
    val others: Int,
)

/**
 * 세 갈래 비교(전부 추정). 같은 인원, 같은 풍량, 같은 창문으로 "내내 내기만" 과 "내내 외기만" 을 그림자로 함께 계산한 값과 실제 주행.
 * 실제는 누가 내외기를 바꿨는지 가리지 않고 있는 그대로다. pmOutdoor: 주행 중 마지막으로 쓴 바깥 PM2.5(µg/m³)
 */
data class ThreeWay(
    val recircOnly: WayStats,
    val freshOnly: WayStats,
    val actual: WayStats,
    val pmGrade: PmGrade? = null,
    val pmOutdoor: Int? = null,
)

/**
 * 숨 설계 한 묶음(WakeEngine.breath). 전부 추정이고 읽기 전용이다.
 * outdoor: 쓰고 있는 바깥 공기 값(없으면 null). shadow*: 그림자 두 갈래의 지금 CO2. nap: 쉼 모드일 때만
 */
data class BreathDesign(
    val outdoor: OutdoorView?,
    val plan: VentPlan,
    val forecast: Forecast,
    val trace: List<TracePoint>,
    val shadowRecircPpm: Int,
    val shadowFreshPpm: Int,
    val alertness: Alertness,
    val nap: NapGuide?,
)

// ---------- 계약 v6: 자리별 프로필, 앉은 시간, 자리별 누적 ----------

/** 자리 순서와 이름. 좌석 지도, 신호, 도착 요약이 모두 이 순서를 쓴다: 운전석, 조수석, 뒷줄 왼쪽, 뒷줄 가운데, 뒷줄 오른쪽 */
object Seats {
    const val COUNT = 5
    const val DRIVER = 0
    const val FRONT_PASSENGER = 1
    const val REAR_LEFT = 2
    const val REAR_CENTER = 3
    const val REAR_RIGHT = 4
    /** 자리별 값이 없는 주행(수동 입력)의 "차 전체" 한 줄에 쓰는 번호 */
    const val CABIN = -1
    private val NAMES = listOf("운전석", "조수석", "뒷줄 왼쪽", "뒷줄 가운데", "뒷줄 오른쪽")
    fun name(seat: Int): String = NAMES.getOrNull(seat) ?: "차 전체"
}

/**
 * 자리별 프로필. 사용자가 정차 중에 자리마다 고른다. 사람이 자리를 바꿔도 프로필은 자리에 붙어 있다.
 *
 * 이것은 설정이다. 프로필별 숫자(90%, 90분)를 뒷받침하는 문헌은 확인하지 못했다(docs/EVIDENCE.md J 절).
 * 그래서 프로필은 무엇을 먼저 볼지(미세먼지 먼저), 여유를 얼마나 둘지(조금 일찍 묻기, 쉬는 간격)를 바꾸는 데만 쓴다.
 * 몸 상태를 알아내거나 판정하는 값이 아니고, 어떤 결과를 약속하지도 않는다.
 *   CHILD, PREGNANT       공기 여유 넓게: 환기를 묻는 시점을 한도의 WakeConfig.airMarginFraction(90%)로 당긴다
 *   RESPIRATORY, CARDIO   미세먼지 먼저: 바깥 미세먼지 등급을 한 단계 나쁘게 보고 환기를 늦추고 짧게 한다(CO2 기본 한도는 넘기지 않는다)
 *   PREGNANT, SENIOR, CARDIO  쉬는 간격을 WakeConfig.sitBreakMinutesShort(90분)로 줄인다(기본은 sitBreakMinutes, 120분)
 */
enum class SeatProfile(val label: String) {
    DEFAULT("기본"), CHILD("어린이"), PREGNANT("임산부"), SENIOR("어르신"), RESPIRATORY("호흡기 주의"), CARDIO("심혈관 주의"),
    /** v0.8.0: 감기, 독감으로 기침하는 사람. 다른 사람이 같이 타면 바깥 공기로 두자고 일찍 묻고, 도착 카드에 추정 감염 위험을 싣는다 */
    COUGH("기침해요")
}

/**
 * 자리별 탑승 값(Seats 순서 다섯 칸). 한 칸은 true(탔다), false(비었다), null(그 자리 값을 못 읽었다).
 * seats == null 이면 차가 자리별 값을 주지 않는다(수동 입력, 모의). 그때 엔진은 운전석만 따로 보고 나머지는 "차 전체"로 묶는다.
 * 주행 중 운전석은 값과 무관하게 탄 것으로 본다. 탑승 인원(숨 모델 입력)은 여전히 OccupantsChanged 로 받는다
 */
data class SeatOccupancyChanged(override val t: Long, val seats: List<Boolean?>?) : Signal

/** 자리별 프로필(Seats 순서 다섯 칸). 모자라는 칸은 기본으로 본다. 세션 상태와 무관하게 받고, 엔진은 저장하지 않는다(앱이 기기에 저장해 두었다가 다시 넣는다) */
data class SeatProfilesChanged(override val t: Long, val profiles: List<SeatProfile>) : Signal

/**
 * 문이 하나라도 열려 있는가. 한 번도 오지 않으면 엔진은 문 신호가 없는 차로 보고, 앉은 시간의 "일어섬"을 기어 P 로 선 시간만으로 가린다.
 * 한 번이라도 오면 기어 P 로 서 있는 동안 문이 열렸다 닫힌 경우에만 일어선 것으로 본다
 */
data class DoorChanged(override val t: Long, val anyOpen: Boolean) : Signal

/**
 * 한 자리가 이번 주행에서 쌓은 것(전부 추정). seat 는 Seats 순서, 차 전체 한 줄이면 Seats.CABIN.
 * minutesAboard: 탄 시간(분, 모델 시간. 쉬는 동안은 뺀다). 중간에 탄 사람은 탄 시점부터 센다(boardedMidTrip).
 * minutesOverLimit: 그동안 추정 CO2 가 기본 한도 위였던 시간(분). averagePpm: 그동안의 추정 CO2 평균.
 * pmAverage: 실내 PM2.5 추정 평균(µg/m³), pmExposure: 노출량(µg/m³·분). 바깥 값을 받은 동안만 쌓고, 한 번도 못 받았으면 null.
 * longestSitMinutes: 가장 길었던 연속 착석(분, 데모 속도면 초). walkBreaks: 쉼터에서 일어선 횟수(걷기 쉼)
 */
data class SeatStats(
    val seat: Int,
    val profile: SeatProfile = SeatProfile.DEFAULT,
    val minutesAboard: Int = 0,
    val minutesOverLimit: Int = 0,
    val averagePpm: Int = 0,
    val pmAverage: Double? = null,
    val pmExposure: Double? = null,
    val longestSitMinutes: Int = 0,
    val walkBreaks: Int = 0,
    val boardedMidTrip: Boolean = false,
)

/**
 * 이번 주행에서 살피는 것(WakeEngine.care). 탄 자리의 프로필에서 나온 설정과 앉은 시간이다. 읽기 전용.
 * pmFirst: 미세먼지 먼저(호흡기 주의, 심혈관 주의 자리에 사람이 탔다). wideMargin: 공기 여유 넓게(어린이, 임산부).
 * breakMinutes: 쉬는 간격(분). 걷기 쉼 제안이 꺼져 있으면 null. sitMinutes: 지금 가장 오래 앉아 있는 자리의 연속 착석(분, 데모 속도면 초),
 * sitSeat: 그 자리(Seats 순서). 앉은 사람이 없으면 0 과 null
 */
data class SeatCare(
    val pmFirst: Boolean = false,
    val wideMargin: Boolean = false,
    val breakMinutes: Int? = null,
    val sitMinutes: Int = 0,
    val sitSeat: Int? = null,
)

// ---------- 행동 (엔진 → 앱) ----------

sealed interface Action {
    /** 내기순환 → 외기. fanDelta 만큼 풍량 올림. 원복 시 이전 값으로. */
    data class Ventilate(val fanDelta: Int = 1) : Action
    /** 온도 celsiusDelta 만큼 낮춤(음수). */
    data class CoolDown(val celsiusDelta: Float = -2f) : Action
    /** 운전석 창문을 percent 만큼 연다(0~100, 앱이 15% 상한 강제). WakeConfig.allowWindow 가 false 이거나 비(와이퍼 ON)면 엔진이 아예 내지 않는다. */
    data class OpenWindow(val percent: Int = 15) : Action
    /** 지정한 졸음쉼터를 경유지로 추가. 엔진은 마지막 RestStopCandidate 의 stop 을 넣는다. */
    data class AddRestStop(val stop: RestStopInfo) : Action
    /** 앞서 실행한 제어를 전부 원복(경유지는 유지). 앱은 운전자가 그 사이 직접 바꾼 항목은 건드리지 않는다. */
    data object Revert : Action
}

/** WALK_BREAK(v6): 오래 앉아 있어서 쉼터에서 내려 걷기를 묻는다. 차의 공조는 건드리지 않고 쉼터 경유지만 넣는다 */
enum class TriggerKind { STALE_AIR, DROWSY, WALK_BREAK }

sealed interface WakeEvent {
    /** 주행 중 짧은 질문(전부 질문으로 끝난다. 괄호 안 추정 수치는 화면용이라 TTS 는 읽지 않는다) + 동의 대기 시작. 앱은 TTS 로 읽고(끝나면 SpeechFinished) 오버레이에 "응/괜찮아" 버튼과 카운트다운을 띄운다.
     *  timeoutSec 는 SpeechFinished 이후 기준.
     *  passengerMayAnswer: 조수석에 사람이 있다. 앱은 버튼을 조수석 쪽에서도 누르기 쉽게 보여 준다. */
    data class Propose(
        val kind: TriggerKind,
        val text: String,
        val actions: List<Action>,
        val timeoutSec: Int,
        val passengerMayAnswer: Boolean = false,
    ) : WakeEvent
    /** 맡기기가 켜져 있을 때 "2분만" 환기를 묻지 않고 예고한다. 앱은 text 를 TTS 로 읽고(끝나면 SpeechFinished) "취소" 버튼과
     *  cancelWindowSec 카운트다운을 띄운다. 취소는 UserAnswer(yes=false), 바로 하라는 답은 UserAnswer(yes=true).
     *  아무 답이 없으면 엔진이 SpeechFinished 뒤 cancelWindowSec 에 Execute(kind, actions) 를 낸다. 그 뒤는 Propose 에 동의한 것과 같다. */
    data class Announce(val kind: TriggerKind, val text: String, val actions: List<Action>, val cancelWindowSec: Int) : WakeEvent
    /** 한 주행에서 환기 제안을 두 번 연속 거절했다. 그 주행이 끝날 때까지 환기 제안을 내지 않는다. 앱은 text 를 한 번 읽는다. */
    data class Quiet(val text: String) : WakeEvent
    /** 맡기기 상태가 바뀌었다. 앱은 그대로 저장했다가 다음 실행 때 setTrust 로 돌려준다. */
    data class TrustChanged(val state: TrustState) : WakeEvent
    /** 동의 없이 timeout → 오버레이 닫고 짧은 한마디. */
    data class ProposalExpired(val text: String) : WakeEvent
    /** 동의 받음. 앱은 actions 를 순서대로 실행하고 각 결과를 ActionResult 로 돌려준다.
     *  actions 에 Revert 만 있으면 "원복". 앱은 아직 실행 안 한 남은 액션을 취소한다. */
    data class Execute(val kind: TriggerKind, val actions: List<Action>) : WakeEvent
    /** 실행 중 표시용. remainingSec 가 0 이 되면 엔진이 Revert 를 낸다. label 은 바에 쓸 짧은 문구(비 올 때 등 상황 반영). */
    data class Running(val kind: TriggerKind, val remainingSec: Int, val label: String) : WakeEvent
    /** 거부, 원복 완료, 또는 실행 실패 후 한마디 (nullable). text 가 null 이면 말없이 제안/실행 화면만 닫는다(차는 건드리지 않는다).
     *  쉼 모드의 바깥 공기 질문은 답, 무응답, 실행 결과 모두 Done(STALE_AIR, null) 로 닫는다. */
    data class Done(val kind: TriggerKind, val text: String?) : WakeEvent
    /** 제안을 미룸 (디버그 표시). reason 은 발화 게이트 사유("turn_signal", "cooldown" 등)이거나
     *  환기 제안 보류 사유 "arriving"(도착 5분 전), "car-auto"(차가 내외기 제어 중), "lights-just-on"(하향등이 막 켜짐). 보류 사유는 주행당 한 번씩만 나온다. */
    data class Deferred(val kind: TriggerKind, val reason: String) : WakeEvent
    /** 주행 상태 변화. */
    data class TripStateChanged(val state: TripState) : WakeEvent
    /** 경유지로 넣은 쉼터 300m 안에서 기어 P. 세션은 끝나지 않고 쉼 모드. 앱은 큰 카운트다운 한 장을 띄운다.
     *  walk: 걷기 쉼으로 넣은 쉼터다(targetMinutes 는 WakeConfig.walkBreakMinutes). 앱은 "쉬고 가세요" 대신 "내려서 걸어요"로 안내한다 */
    data class RestStarted(val stopName: String, val targetMinutes: Int, val walk: Boolean = false) : WakeEvent
    /** 쉼 모드 카운트다운(매 tick). */
    data class RestTick(val remainingSec: Int) : WakeEvent
    /** 쉼 모드에서 기어 D. text 는 "12분 쉬셨어요" 류 한마디. 세션은 계속된다. */
    data class RestEnded(val restedMinutes: Int, val text: String) : WakeEvent
    /** 숨 모델 추정치(5초마다, 값이 바뀔 때). 전부 추정이며 화면에 "추정" 을 붙인다.
     *  minutesToLimit: 지금 상태가 이어지면 유효 한도(co2LimitPpm, 야간 장거리면 그 0.8배)에 닿기까지 남은 분(모델 시간, 올림).
     *  이미 넘었으면 0, 지금 조건의 평형값이 한도에 못 미치면 null. */
    data class AirEstimate(val ppm: Int, val occupants: Int, val minutesToLimit: Int?, val ventilating: Boolean) : WakeEvent
    /** 기어 P(쉼터 밖). 오늘 요약 카드. */
    data class ArrivalCard(val card: ArrivalSummary) : WakeEvent
}

data class ArrivalSummary(
    val headline: String,
    val tripMinutes: Int,
    /** 환기 실행 횟수(STALE_AIR + DROWSY 안의 Ventilate 포함) */
    val ventilations: Int,
    /** 바깥 공기로 달린 누적 분(실행 구간 합) */
    val freshAirMinutes: Int,
    val drowsySignals: Int,
    val restStopsAdded: Int,
    /** 쉼터에서 실제로 쉰 횟수 / 누적 분 */
    val restStopsVisited: Int,
    val restMinutes: Int,
    /** 경유지로 넣었지만 지나친 횟수 */
    val restStopsPassed: Int,
    val declined: Int,
    val lines: List<String>,
    /** 숨 모델: 세션 중 추정 최고 ppm, 한도(co2LimitPpm) 위에 머문 누적 분 */
    val peakPpm: Int = 0,
    val minutesOverLimit: Int = 0,
    /** 맡기기 질문. null 이 아니면 앱은 카드에 이 문장과 [맡길게요] / [계속 물어봐 주세요] 를 보여 주고 답을 TrustAnswer 로 보낸다 */
    val trustOffer: String? = null,
    /** 세 갈래 비교(내기만 / 외기만 / 이번 주행). 전부 추정. 앱은 막대 두 줄(CO2 최고값, 미세먼지 노출)로 보여 준다 */
    val threeWay: ThreeWay? = null,
    /** 자리별 누적(v6). 차가 자리별 값을 준 주행에만 있다(탄 적 있는 자리마다 한 줄, Seats 순서). 없으면 빈 목록이고 앱은 cabin 한 줄을 쓴다 */
    val seats: List<SeatStats> = emptyList(),
    /** 차 전체로 본 누적(seat = Seats.CABIN). 앉은 시간은 운전석 기준이다. 주행을 시작한 적이 없으면 null.
     *  미세먼지 칸은 비워 둔다. 차 전체의 미세먼지 평균과 노출량은 threeWay.actual 에 있다(같은 값을 두 번 싣지 않는다) */
    val cabin: SeatStats? = null,
    /** 자리별 요약의 제목 한 문장("오늘 2시간 10분, 넷이 탔어요."). headline 은 그대로 두고 새 화면만 이 문장을 쓴다 */
    val seatHeadline: String = "",
    /** 쉼터에서 일어선 횟수(걷기 쉼). 5분 이상 서 있었고, 문 신호가 있는 차면 문이 열렸다 닫힌 경우만 센다 */
    val walkBreaks: Int = 0,
    /** 기침 자리 감염 위험(v0.8.0). 기침하는 자리와 다른 사람이 같이 탄 주행에만 있다 */
    val infection: InfectionStats? = null,
)

/** 엔진 설정. 데모에서는 분 단위를 초 단위로 줄여서 쓴다. */
data class WakeConfig(
    /** 내기순환 연속 시간 상한(분). 창문이 닫혀 있을 때만 건다 */
    val staleAirMinutes: Int = 30,
    /** 야간(하향등이 켜진 지 3분 이상) + 장거리(longTripMinutes 이상)일 때의 환기 임계(분) */
    val staleAirMinutesNight: Int = 20,
    /** 환기 실행 유지 시간(초). 연장하지 않고 이 시간 뒤 원복한다(원복 없는 변형은 제외) */
    val ventilateSeconds: Int = 120,
    /** 졸음 개입 실행 유지 시간(초) */
    val drowsySeconds: Int = 120,
    /** 동의 대기 시간(초). SpeechFinished 이후부터 */
    val answerTimeoutSec: Int = 8,
    /** 거부 후 같은 종류 제안을 미루는 시간(분) */
    val declineCooldownMinutes: Int = 20,
    /** 야간 장거리 판정: 트립 시간이 이 값(분) 이상 */
    val longTripMinutes: Int = 120,
    /** 쉼 모드 목표 시간(분) */
    val restMinutes: Int = 15,
    /** 데모 모드: 모든 분 단위를 초 단위로 해석(restMinutes 포함). 숨 모델은 시간을 60배로 돌리되,
     *  제안에 답을 기다리는 동안과 제어 결과를 기다리는 동안은 실제 속도로 흐른다 */
    val demoFast: Boolean = false,
    // ----- 숨 모델(질량 수지). 값의 출처는 docs/EVIDENCE.md 의 "숨 모델" 절. 전부 추정용 기본값 -----
    /** 환기를 묻는 한도(ppm, 위험 기준이 아님). 추정치가 여기에 닿으면 STALE_AIR. 야간 장거리(하향등 ON, 트립 longTripMinutes 이상)면 0.8배.
     *  staleAirMinutes 는 모델 계수가 실제보다 낮게 잡힌 경우를 대비한 상한으로 남는다(창문이 닫혀 있을 때만) */
    val co2LimitPpm: Int = 2000,
    /** 바깥 공기 ppm */
    val outdoorPpm: Int = 430,
    /** 실내 공기 부피(L) */
    val cabinVolumeL: Int = 3000,
    /** 한 사람이 앉아서 내쉬는 CO2(L/시간) */
    val co2PerPersonLph: Double = 18.0,
    /** 내기순환일 때 새는 공기 교환(회/시간) */
    val leakAch: Double = 2.0,
    /** 외기 모드, 풍량 최대 단일 때 공기 교환(회/시간). 1단은 약 25회/시간이고 최대 단까지 선형. 풍량 0 이거나 최대 단계를 모르면 이 값의 0.15 */
    val freshAchMax: Double = 60.0,
    /** 기침 자리 감염 위험 모델(v0.8.0). 인플루엔자 감염 입자 생성률(quanta/h, Knibbs 외 2012 중앙값 67, 범위 15~128)과 호흡량(m³/h) */
    val coughQuantaPerHour: Double = 67.0,
    val breathingM3PerHour: Double = 0.6,
    /** 창문이 열렸을 때 더해지는 공기 교환(회/시간, 15% 열림 기준) */
    val windowAch: Double = 15.0,
    /** 핸들 신호: 이 시간(초) 이상 1도 안쪽으로만 움직이다가 */
    val steerQuietSec: Int = 6,
    /** 1초 안에 이 각도(도) 이상 꺾으면 1회로 센다. 10분 안에 steerEventsThreshold 회면 졸음 보조 신호 */
    val steerJerkDeg: Float = 8f,
    val steerEventsThreshold: Int = 3,
    /** 핸들 신호를 쓸지. false 면 SteeringAngle 을 무시한다(실차 검증 전이라 기본은 끔) */
    val steeringAssist: Boolean = false,
    /** 졸음 개입에서 창문을 열지. false 면 OpenWindow 를 내지 않고 문장도 창문 없는 쪽을 쓴다 */
    val allowWindow: Boolean = false,
    // ----- 숨 설계(v0.7.0). 값의 출처는 docs/EVIDENCE.md 의 "I. 숨 설계 근거" 절. 전부 추정용 기본값이고 차마다, 필터 상태마다 다르다 -----
    /** 내기순환일 때 실내/실외 PM2.5 비. 창문 닫은 승용차의 내기 범위 0~0.4, 최근 실주행 0.10~0.13 (I 절) 안의 값 */
    val pmIoRecirc: Double = 0.15,
    /** 외기 모드일 때 실내/실외 PM2.5 비. 외기 범위 0.6~1.0 의 아래 끝이자 오래된 필터 0.60 (I 절). 새 필터(0.20)면 이보다 낮다 */
    val pmIoFresh: Double = 0.6,
    /** 창문이 열렸을 때 실내/실외 PM2.5 비. 필터를 거치지 않으니 바깥과 같다고 본다 */
    val pmIoWindow: Double = 1.0,
    /** 내기순환일 때 실내 PM2.5 가 평형으로 가는 속도(회/시간). 실내 공기가 필터를 거쳐 도는 것을 가정한 고정값이다(자체 가정, 실측 아님) */
    val pmRecircAch: Double = 20.0,
    /** 바깥 미세먼지 값을 믿는 시간(분, 실제 시간). 이보다 묵으면 값이 없는 것으로 본다 */
    val pmStaleMinutes: Int = 60,
    /** 미세먼지 나쁨 이상일 때 "필요한 만큼만" 환기의 CO2 목표: 한도의 이 비율까지 내려오면 되돌린다 */
    val pmVentTargetFraction: Double = 0.6,
    // ----- 자리별 프로필과 앉은 시간(v0.7.3). 전부 설정값이다. 문헌에서 가져온 기준이 아니고, 사용자가 고른 프로필에 따라 순서와 여유만 바꾼다(EVIDENCE J 절) -----
    /** 오래 앉아 있으면 쉼터에서 내려 걷기를 물을지. 기본은 끔(끄면 v0.7.2 와 같다). 앱은 켜서 쓴다 */
    val walkBreak: Boolean = false,
    /** 쉬는 간격(분): 한 자리의 연속 착석이 이 값에 닿으면 걷기 쉼을 묻는다. 한국도로공사의 "2시간 운전 뒤 휴식" 권고에 맞춘 값(EVIDENCE A5) */
    val sitBreakMinutes: Int = 120,
    /** 임산부, 어르신, 심혈관 주의 자리에 사람이 탔을 때의 쉬는 간격(분). 근거 문헌이 있는 숫자가 아니라 더 자주 쉬자는 설정이다 */
    val sitBreakMinutesShort: Int = 90,
    /** 자리가 이 시간(분) 이상 비어 있었으면 내렸다 탄 것으로 보고 앉은 시간을 0 부터 다시 센다 */
    val sitEmptyResetMinutes: Int = 2,
    /** 기어 P 로 이 시간(분) 이상 서 있었으면(문 신호가 있는 차면 그동안 문이 열렸다 닫혔을 때만) 일어선 것으로 본다 */
    val sitParkResetMinutes: Int = 5,
    /** 걷기 쉼에서 권하는 길이(분). 걷기 쉼으로 넣은 쉼터의 쉼 타이머도 이 값이다 */
    val walkBreakMinutes: Int = 5,
    /** 어린이, 임산부 자리에 사람이 탔을 때 환기를 묻는 시점: 한도의 이 비율. 1.0 이하로만 쓴다(한도를 올리지 않는다) */
    val airMarginFraction: Double = 0.9,
)

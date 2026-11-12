package ai.kkaeugi.app.ui

import ai.kkaeugi.app.AirUi
import ai.kkaeugi.app.DiagUi
import ai.kkaeugi.app.HomeHold
import ai.kkaeugi.app.UiState
import ai.kkaeugi.app.vehicle.DiagReport
import ai.kkaeugi.app.vehicle.SeatLayout
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.TracePoint
import ai.kkaeugi.engine.TripState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 화면에 적는 글과 숫자를 고르는 순수 함수 모음(JVM 테스트 대상). 그리는 코드는 ui/screens, ui/components 에 있다.
 * 여기서는 판단하지 않는다. 엔진과 ViewModel 이 낸 상태를 화면 말로 바꾸기만 한다.
 */

/** 눈금 범위(ppm). 바깥 공기(엔진 설정 outdoorPpm, 모르면 420)부터 3,000 까지. 인원 비교 화면의 가로 눈금에 쓴다 */
internal const val GAUGE_MIN = 420
internal const val GAUGE_MAX = 3000

/** 눈금 위치(0~1). 420~3,000ppm 을 한 줄에 편다 */
internal fun gaugeFraction(ppm: Int, min: Int = GAUGE_MIN): Float = ((ppm - min) / (GAUGE_MAX - min).toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)

internal fun ppmText(ppm: Int): String = String.format(Locale.US, "%,d", ppm)

/** 주행 홈의 상태 한 단어. 큰 숫자 옆에 적는다 */
internal enum class AirLevel(val label: String) { FRESH("쾌적"), NORMAL("보통"), STALE("탁함"), LIMIT("한도") }

/**
 * 추정 ppm 을 상태 한 단어로(순수 함수). 1,000 미만 쾌적, 한도의 80% 미만 보통, 한도 미만 탁함, 한도 이상 한도.
 * 한도는 엔진이 지금 쓰는 값(야간 장거리면 더 낮다)이다
 */
internal fun airLevel(ppm: Int, limit: Int): AirLevel = when {
    ppm >= limit -> AirLevel.LIMIT
    ppm >= limit * 0.8 -> AirLevel.STALE
    ppm >= 1000 -> AirLevel.NORMAL
    else -> AirLevel.FRESH
}

/**
 * 분당 변화(ppm, 10 단위 반올림). 지나온 곡선의 마지막 점과 그보다 1분 이상 앞선 점의 기울기다. 점이 모자라면 null.
 * 데모 속도에서는 모델의 1분이 실제 1초로 흐른다
 */
internal fun ppmPerMinute(trace: List<TracePoint>): Int? {
    val last = trace.lastOrNull() ?: return null
    val prev = trace.lastOrNull { last.minute - it.minute >= 1.0 } ?: trace.firstOrNull()?.takeIf { last.minute - it.minute >= 0.4 } ?: return null
    val slope = (last.ppm - prev.ppm) / (last.minute - prev.minute)
    return ((slope / 10.0).roundToInt() * 10)
}

/** "분당 +130" / "분당 −360" / "변화 거의 없음". 데모 속도면 "초당" */
internal fun rateText(rate: Int?, demoFast: Boolean): String? = when {
    rate == null -> null
    abs(rate) < 10 -> "변화 거의 없음"
    else -> "${if (demoFast) "초당" else "분당"} ${if (rate > 0) "+" else "−"}${ppmText(abs(rate))}"
}

/** 한 줄을 고르는 데 필요한 주변 상태 */
internal data class AirLineContext(
    /** 설정 "숨 켜기". 꺼져 있으면 엔진이 묻지 않는다 */
    val autoIntervene: Boolean = true,
    /** 창문 열림(0~100). 5 이상이면 열린 것으로 본다(엔진과 같은 기준) */
    val windowPercent: Int = 0,
    /** 환기 제안 한도(ppm) */
    val limit: Int = 2000,
    /** 쉼 모드 */
    val resting: Boolean = false,
    /** 거절(무응답) 뒤 다시 묻기까지 남은 시간(분, 데모 속도면 초). 없으면 null */
    val cooldownLeft: Int? = null,
    /** 이번 주행에서 환기 제안을 두 번 연속 거절해 더 묻지 않는 중 */
    val quiet: Boolean = false,
    /** 환기 제안을 미루는 사유의 홈 문구("도착 4분 전이라 묻지 않아요", "차가 내외기를 자동 조절 중이에요"). 없으면 null */
    val holdLine: String? = null,
)

internal const val WINDOW_OPEN_MIN_PERCENT = 5

/** "바깥 공기인데 바람이 약해요": 외기(창문 닫힘)인데 추정치가 한도 위에서 내려오지 않는 상태 */
internal fun weakFreshAir(air: AirUi?, ctx: AirLineContext): Boolean =
    air != null && air.ventilating && ctx.windowPercent < WINDOW_OPEN_MIN_PERCENT && air.ppm >= ctx.limit && !air.falling

/**
 * 곡선 아래 한 줄(순수 함수). 위에서부터 먼저 맞는 것:
 *  쉼 모드 → 추정 전 → 창문 열림 → 외기인데 한도 위("바람이 약해요") → 외기 → 숨 꺼짐
 *  → 묻지 않는 중인데 한도 이상("한도를 넘었어요(추정). 묻지 않는 중이에요") → 이번 주행은 묻지 않음(두 번 거절)
 *  → 미루는 사유(도착 임박, 차의 자동 내외기) → 거절 뒤 쿨다운 → 한도까지 남은 시간.
 * "약 N분 뒤"의 N 은 반올림 값(AirMath.roundedMinutesToLimit)이다. 데모 속도면 단위가 초
 */
internal fun airStatusLine(air: AirUi?, occupants: Int, demoFast: Boolean, ctx: AirLineContext = AirLineContext()): String {
    if (ctx.resting) return "쉬는 중 · 추정은 잠시 멈춰요"
    if (air == null) return "${occupants}명 탑승 · 추정 준비 중"
    val unit = if (demoFast) "초" else "분"
    return when {
        air.ventilating && ctx.windowPercent >= WINDOW_OPEN_MIN_PERCENT -> if (air.falling) "창문 열림 · 내려가는 중" else "창문 열림 · 바깥 공기 들어오는 중"
        weakFreshAir(air, ctx) -> "바깥 공기인데 바람이 약해요(추정)"
        air.ventilating -> if (air.falling) "바깥 공기 들어오는 중 · 내려가는 중" else "바깥 공기 들어오는 중"
        !ctx.autoIntervene -> "${air.occupants}명 탑승 · 숨 꺼짐"
        quietOverLimit(air, ctx) -> "${air.occupants}명 탑승 · $QUIET_OVER_LIMIT_LINE"
        ctx.quiet -> "${air.occupants}명 탑승 · $QUIET_HOME_LINE"
        ctx.holdLine != null -> "${air.occupants}명 탑승 · ${ctx.holdLine}"
        ctx.cooldownLeft != null && ctx.cooldownLeft > 0 -> "${air.occupants}명 탑승 · ${ctx.cooldownLeft}$unit 뒤 다시 물을게요"
        air.minutesToLimit == null -> "${air.occupants}명 탑승 · 지금은 한도에 닿지 않아요"
        air.minutesToLimit <= 0 -> "${air.occupants}명 탑승 · 한도를 넘었어요(추정)"
        else -> "${air.occupants}명 탑승 · 약 ${air.minutesToLimit}$unit 뒤 환기 제안"
    }
}

private val OCCUPANT_PREFIX = Regex("^\\d+명 탑승 · ")

/**
 * 주행 홈 곡선 아래에 적는 문장. airStatusLine 과 같은 문장에서 앞의 "N명 탑승 · " 만 뗀다.
 * 탑승 인원은 오른쪽 좌석 지도가 보여 주기 때문에 두 번 적지 않는다
 */
internal fun homeSentence(statusLine: String): String = statusLine.replaceFirst(OCCUPANT_PREFIX, "")

/** 두 번 연속 거절한 주행의 홈 문구. 엔진은 "화면에만 표시할게요" 라고 한 번 말한다 */
internal const val QUIET_HOME_LINE = "이번 주행은 화면에만 표시해요"

/** 묻지 않는 중(조용 모드)인데 추정치가 한도 이상일 때의 홈 문구. "화면에만 표시해요" 보다 먼저 보여 준다 */
internal const val QUIET_OVER_LIMIT_LINE = "한도를 넘었어요(추정). 묻지 않는 중이에요"

/** 조용 모드, 내기순환(창문 닫힘)인 채로 추정치가 한도 이상인가 */
internal fun quietOverLimit(air: AirUi?, ctx: AirLineContext): Boolean =
    air != null && ctx.quiet && ctx.autoIntervene && !ctx.resting && !air.ventilating && air.ppm >= ctx.limit

/**
 * 주행 홈의 "바깥 공기로" 버튼을 보여 줄지(순수 함수). 조용 모드에서 한도를 넘었을 때만, 다른 개입이 떠 있지 않을 때만.
 * 누르면 묻지 않고 2분 환기한 뒤 원복한다(운전자가 직접 요청한 환기)
 */
internal fun showFreshAirButton(ui: UiState): Boolean =
    ui.tripState == TripState.DRIVING && ui.running == null && ui.proposal == null && ui.announce == null &&
        quietOverLimit(ui.air, airLineContext(ui))

internal const val FRESH_AIR_BUTTON = "바깥 공기로"

/** 화면 상태에서 한 줄의 주변 상태를 뽑는다 */
internal fun airLineContext(ui: UiState): AirLineContext {
    val unitMs = if (ui.demoFast) 1_000L else 60_000L
    val left = ui.staleCooldownUntilMs?.let { ((it - ui.nowMs + unitMs - 1) / unitMs).toInt() }?.takeIf { it > 0 }
    return AirLineContext(ui.autoIntervene, ui.windowPercent, ui.co2LimitPpm, ui.rest != null, left, ui.quietThisTrip, HomeHold.line(ui.holdReason, ui.etaMinutes))
}

internal enum class PillTone { WARN, ACCENT, MUTED }
internal data class TripPill(val text: String, val tone: PillTone)

internal const val SLEEPY_HOURS_PILL = "졸리기 쉬운 시간대"

/**
 * 위 막대의 주행 상태 꼬리표(순수 함수). 자리가 좁아 둘까지만 보여 준다. 앞에서부터: 쉬는 중, 졸리기 쉬운 시간대, 야간 장거리, 비.
 * "졸리기 쉬운 시간대"는 시각과 연속 주행 시간으로 낸 어림이다(엔진 Alertness.sleepyHours). 운전자를 보고 아는 것이 아니다
 */
internal fun tripPills(ui: UiState): List<TripPill> = buildList {
    if (ui.rest != null) add(TripPill("쉬는 중", PillTone.ACCENT))
    if (ui.breath?.alertness?.sleepyHours == true) add(TripPill(SLEEPY_HOURS_PILL, PillTone.WARN))
    if (isNightLongTrip(ui)) add(TripPill("야간 장거리", PillTone.WARN))
    if (ui.raining) add(TripPill("비: 창문은 닫아 둬요", PillTone.MUTED))
}.take(2)

/**
 * 위 막대의 주행 한 줄(순수 함수): "주행 13분" 뒤에 상태 꼬리표를 가운뎃점으로 잇는다("주행 2시간 54분 · 야간 장거리").
 * 쉼 모드면 "쉬는 중 12분". 색이나 점 없이 글자만 쓴다
 */
internal fun topBarTripLine(ui: UiState): String = buildList {
    add("${if (ui.rest != null) "쉬는 중" else "주행"} ${formatMinutes(ui.tripMinutes)}")
    tripPills(ui).filter { it.text != "쉬는 중" }.forEach { add(it.text) }
}.joinToString(" · ")

/** 정차 화면 위 막대의 한 줄(순수 함수): "대기 · 기어 P". 마이크 권한이 없으면 그 사실을 잇는다 */
internal fun idleTopLine(ui: UiState): String = buildList {
    add(if (ui.tripState == TripState.ENDED) "도착" else "대기")
    add("기어 ${gearLabel(ui.gear)}")
    if (!ui.micGranted) add("마이크 권한 없음(버튼으로 답)")
}.joinToString(" · ")

/** 위 막대 오른쪽의 연결 한 단어(순수 함수). 앞에 작은 점 하나가 붙는다 */
internal fun connectionWord(ui: UiState): String = when { ui.pleosAvailable -> "연결"; ui.probing -> "확인 중"; else -> "모의" }

/**
 * 주행 홈 곡선 아래 둘째 줄(순수 함수). 한 줄만 말한다. 위에서부터 먼저 맞는 것:
 *  숨 꺼짐 또는 이번 주행은 묻지 않는 중 → 그 사실("예보는 참고용이에요")
 *  → 마지막으로 한 말(제안, 예고, 실행 띠가 떠 있지 않을 때. 물음은 뺀다. 답할 버튼이 없는 물음을 남기지 않는다)
 *  → 예보 한 줄 → 없음
 */
internal fun homeSecondLine(ui: UiState): String? {
    val design = ui.breath
    if (design != null && (!ui.autoIntervene || ui.quietThisTrip)) return BreathText.bottomLine(design.forecast, ui.demoFast, ui.autoIntervene, ui.quietThisTrip)
    val calm = ui.proposal == null && ui.announce == null && ui.running == null
    if (calm) ui.lastSaid?.takeIf { it.isNotBlank() && '?' !in it }?.let { return it }
    return design?.let { BreathText.forecastLine(it.forecast, ui.demoFast) }
}

/** 정차 화면 "최근 주행 기록"의 한 행: 날짜 줄과 요약 줄 */
internal data class TripRow(val date: String, val summary: String)

private val ZERO_COUNT = Regex("^\\D+ 0회$")

/**
 * 기록 한 줄("11/09 02:40  200분 주행, 환기 2회, 졸음 0회, 쉼 1회, 거절 0회, 최고 2,050ppm 추정")을 날짜와 요약으로 나눈다(순수 함수).
 * 요약에서는 0회인 항목을 빼고 가운뎃점으로 잇는다("200분 주행 · 환기 2회 · 쉼 1회 · 최고 2,050ppm 추정").
 * 날짜를 못 찾으면 줄 전체가 요약이다
 */
internal fun tripRow(line: String): TripRow {
    val cut = line.indexOf("  ")
    if (cut <= 0) return TripRow("", line.trim())
    val items = line.substring(cut).trim().split(", ").map { it.trim() }.filter { it.isNotEmpty() && !ZERO_COUNT.matches(it) }
    return TripRow(line.substring(0, cut).trim(), items.joinToString(" · "))
}

/** 정차 화면에 보여 주는 최근 주행 기록의 건수 */
internal const val RECENT_TRIP_ROWS = 2

/** 오늘 한 줄(순수 함수): "환기 1회 · 바깥 공기 2분 · 최고 2,100ppm 추정" */
internal fun todayCompactLine(ui: UiState): String =
    todayLine(ui.todayVentilations, ui.todayFreshAirUnits, ui.demoFast) + if (ui.todayPeakPpm > 0) " · 최고 ${ppmText(ui.todayPeakPpm)}ppm 추정" else ""

/** 야간(하향등 ON) + 장거리(longTripMinutes 이상). 엔진이 한도를 낮춰 조금 일찍 묻는 조건과 같다 */
internal fun isNightLongTrip(ui: UiState) = ui.night && ui.tripMinutes >= ui.longTripMinutes

/** "다음 쉼터" 칸에 후보가 없을 때의 글(순수 함수). 주행 방향을 모르면 모의 방향으로 고르지 않고 그 사실을 말한다 */
internal fun noRestStopText(ui: UiState): String =
    if (ui.headingUnknown) "주행 방향을 알면 쉼터를 안내해요" else "전방 80km 안에 같은 방향 쉼터 없음"

/** 홈의 공조 안내 한 줄(순수 함수). 내기순환 영역이 없는 차에서만 나온다 */
internal fun hvacNotice(ui: UiState): String? =
    if (ui.pleosAvailable && ui.recircUnsupported) "이 차에서는 내외기 상태를 읽을 수 없어요" else null

/** 오늘 첫 줄(순수 함수): "환기 1회 · 바깥 공기 2분". 바깥 공기 누적은 엔진 집계와 같은 기준으로 센 값(데모 속도면 초) */
internal fun todayLine(ventilations: Int, freshUnits: Int, demoFast: Boolean): String =
    "환기 ${ventilations}회 · 바깥 공기 ${freshUnits}${if (demoFast) "초" else "분"}"

/** 남은 거리 글자: 1km 미만이면 100m 단위("200m"), 아니면 "12km" */
internal fun kmText(km: Double): String = if (km < 1.0) "${(km * 1000).toInt() / 100 * 100}m" else "${"%.0f".format(km)}km"

/** "12km" 를 숫자 "12" 와 단위 "km" 로 나눈다(순수 함수). 단위는 숫자보다 작고 흐리게 따로 그린다. 숫자로 시작하지 않으면 전체가 숫자 자리다 */
internal fun numberAndUnit(text: String): Pair<String, String> {
    val cut = text.indexOfFirst { !it.isDigit() && it != ',' && it != '.' }
    return if (cut <= 0) text to "" else text.substring(0, cut) to text.substring(cut)
}

/**
 * 좌석 지도에 채울 자리(순수 함수). 순서는 운전석, 조수석, 뒷줄 왼쪽, 뒷줄 가운데, 뒷줄 오른쪽.
 * 화면 상태에는 탄 사람 수와 조수석 여부만 있다(자리별 값은 ViewModel 이 넘기지 않는다). 그래서 이렇게 편다:
 *  - 한 명 이상이면 운전석.
 *  - 좌석 값이 오고 있으면(sensorLive) 조수석은 그 값대로, 나머지는 뒷줄(오른쪽, 왼쪽, 가운데 순).
 *  - 좌석 값이 안 와서 직접 넣은 인원이면 자리를 모른다. 흔한 순서(조수석, 뒷줄 오른쪽, 왼쪽, 가운데)로 채운다
 */
internal fun seatFill(occupants: Int, frontPassenger: Boolean, sensorLive: Boolean): List<Boolean> {
    val seats = BooleanArray(5)
    var left = occupants.coerceIn(0, 5)
    if (left > 0) { seats[0] = true; left-- }
    if (left > 0 && (frontPassenger || !sensorLive)) { seats[1] = true; left-- }
    for (i in listOf(4, 2, 3)) if (left > 0) { seats[i] = true; left-- }
    // 좌석 값으로는 조수석이 비어 있는데 뒷줄이 모자라면(5명) 조수석까지 채운다
    if (left > 0 && !seats[1]) seats[1] = true
    return seats.toList()
}

/** 좌석 지도에 그릴 것: 다섯 자리의 값(true 탔다, false 비었다, null 모름)과 그 값이 차의 자리별 값인지 */
internal data class SeatMapUi(val seats: List<Boolean?>, val fromSensor: Boolean) {
    /** 좌석 지도에 붙이는 출처 라벨 */
    val label: String get() = if (fromSensor) SEAT_LABEL_SENSOR else SEAT_LABEL_MANUAL
}

internal const val SEAT_LABEL_SENSOR = "좌석 센서"
internal const val SEAT_LABEL_MANUAL = "직접 입력"

/**
 * 좌석 지도 재료(순수 함수).
 *  - 차가 자리별 값을 줬고(seats) 그 값으로 센 인원이 화면에 적는 인원과 같으면 그 값을 그대로 그린다("좌석 센서").
 *    "운전석과 뒷줄 왼쪽"처럼 어떤 조합이든 그대로다
 *  - 자리별 값이 없거나(수동 입력, 모의) 인원과 어긋나면(데모 콘솔이 인원을 따로 넣은 때) seatFill 의 순서로 채우고 "직접 입력"이라고 적는다
 */
internal fun seatMapUi(seats: List<Boolean?>?, occupants: Int, frontPassenger: Boolean, sensorLive: Boolean): SeatMapUi {
    if (seats != null && seats.size == SeatLayout.ORDER.size && SeatLayout.count(seats) == occupants.coerceAtLeast(1)) return SeatMapUi(seats, fromSensor = true)
    return SeatMapUi(seatFill(occupants, frontPassenger, sensorLive), fromSensor = false)
}

/** 화면 상태에서 좌석 지도 재료를 뽑는다. 인원은 화면에 적는 값(숨 모델이 쓰는 인원, 없으면 마지막으로 알려진 인원) */
internal fun seatMapUi(ui: UiState): SeatMapUi = seatMapUi(ui.seats, ui.air?.occupants ?: ui.occupants, ui.frontPassenger, ui.seatSensorLive)

/** 제안 문장을 화면에서 둘로 나눈 것. reason: 이유(작게), question: 물음과 그 뒤 안내(크게) */
internal data class ProposalParts(val reason: String?, val question: String)

private val SENTENCE_END = Regex("(?<=[.?!])\\s+")

/**
 * 엔진의 제안 문장을 이유와 물음으로 나눈다(순수 함수). 문장은 바꾸지 않고 자르기만 한다.
 * "4명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?" → 이유 "4명이 … 탁해져요(추정 2,000ppm).", 물음 "바깥 공기로 바꿔 둘까요?"
 * 물음표로 끝나는 문장이 없으면(예고: "2분만 바깥 공기로 바꿀게요. 싫으면 …") 첫 문장이 크게, 나머지가 작게 간다.
 * 물음 뒤에 붙은 안내("옆자리에서 눌러 주셔도 돼요.")는 물음과 함께 둔다
 */
internal fun splitProposal(text: String): ProposalParts {
    val sentences = text.trim().split(SENTENCE_END).filter { it.isNotBlank() }
    if (sentences.size <= 1) return ProposalParts(null, text.trim())
    val q = sentences.indexOfFirst { it.endsWith("?") }
    return when {
        q > 0 -> ProposalParts(sentences.take(q).joinToString(" "), sentences.drop(q).joinToString(" "))
        q == 0 -> ProposalParts(null, text.trim())
        else -> ProposalParts(sentences.drop(1).joinToString(" "), sentences.first())
    }
}

/**
 * 안내 한 줄("6초 뒤 넘어가요  ·  "응" 또는 "괜찮아"라고 말해도 돼요")에서 답하는 방법만 뗀다.
 * 남은 초는 옆의 원형 표시가 보여 준다. 가운뎃점이 없으면("말하는 중...") 그대로 돌려준다
 */
internal fun hintAnswerPart(hint: String): String = hint.substringAfter("  ·  ", hint).trim()

/** 설정의 바깥 공기 한 줄(순수 함수): "바깥 공기: PM2.5 41µg/m³(나쁨) · 출처 · 12분 전" */
internal fun pmSettingsLine(ui: UiState): String = "바깥 공기: " + ui.outdoorStatus.ifBlank { if (ui.pmEnabled) "확인 중" else "꺼짐" }

/** 접힌 개발자 행의 요약(순수 함수) */
internal fun developerSummary(ui: UiState): String = buildList {
    if (ui.permissionFailures.isNotEmpty()) add("등록 실패 ${ui.permissionFailures.size}건")
    add(if (ui.pleosAvailable) "연결됨" else if (ui.probing) "연결 확인 중" else "모의 모드")
    if (ui.demoFast) add("데모 속도")
    if (ui.evidenceMode) add("증거 모드")
    if (ui.steeringAssist) add("핸들 보조")
    if (ui.allowWindow) add("창문 포함")
    if (ui.keepAlive) add("화면 밖 유지")
}.joinToString(" · ")

/** 실행 중 띠의 문장(순수 함수) */
internal fun runningBarText(remainingSec: Int, label: String): String =
    if (remainingSec > 0) "$label  ·  ${remainingSec / 60}:${"%02d".format(remainingSec % 60)} 뒤 원래대로" else label

/** 실행 중 띠의 진행선 눈금(초). 남은 시간이 이보다 길면 처음 본 남은 시간을 눈금으로 쓴다. 표시용일 뿐 판단에 쓰지 않는다 */
internal const val RUN_SCALE_SEC = 120

/** 실행 중 진행선(0~1): 지나간 몫. seenMax 는 이 실행에서 본 가장 긴 남은 시간 */
internal fun runProgress(remainingSec: Int, seenMaxSec: Int): Float {
    val scale = maxOf(RUN_SCALE_SEC, seenMaxSec, 1)
    return (1f - remainingSec / scale.toFloat()).coerceIn(0f, 1f)
}

/**
 * 큰 글씨 문장이 낱말 가운데에서 줄이 바뀌지 않게 한다("바 / 깥 공기"처럼 끊기는 것을 막는다).
 * 낱말 안의 글자 사이에 보이지 않는 낱말 이음표(U+2060)를 넣는다. 띄어쓰기에서는 그대로 줄이 바뀐다
 */
internal fun keepWords(text: String): String = text.split(' ').joinToString(" ") { word -> word.toList().joinToString("⁠") }

/** 다 읽은 뒤부터 센 남은 초. 아직 읽는 중이면 null */
internal fun countdownLeft(totalSec: Int, countdownFrom: Long?, nowMs: Long): Int? =
    countdownFrom?.let { (totalSec - ((nowMs - it) / 1000L).toInt()).coerceIn(0, totalSec) }

/**
 * 답 버튼 묶음의 배치. passengerSide: 화면 오른쪽(조수석 쪽)에 붙인다.
 * yesSp, noSp 는 예전 화면의 글자 크기다. 지금 화면은 글자 크기를 다섯 단계로만 쓰므로 높이와 폭만 따른다
 */
internal data class AnswerButtonLayout(val passengerSide: Boolean, val widthFraction: Float, val heightDp: Int, val yesSp: Int, val noSp: Int)

/** 조수석에 사람이 있으면 버튼을 오른쪽에 붙이고 키운다. 없으면 기본 배치(폭 전체, 96dp) */
internal fun answerButtonLayout(passengerMayAnswer: Boolean): AnswerButtonLayout =
    if (passengerMayAnswer) AnswerButtonLayout(passengerSide = true, widthFraction = 0.66f, heightDp = 124, yesSp = 52, noSp = 38)
    else AnswerButtonLayout(passengerSide = false, widthFraction = 1f, heightDp = 96, yesSp = 44, noSp = 34)

/** 쉼 화면 맨 아래 고정 문구. 숨이 하는 추정의 범위를 밝힌다 */
internal const val REST_CO_NOTE = "이 앱은 사람이 내쉰 숨(이산화탄소)만 추정해요. 배기가스(일산화탄소)는 알 수 없어요."

/** 쉼 화면의 버튼. 누르면 큰 타이머 화면만 닫고 홈으로 돌아간다. 쉼 모드 자체는 엔진이 기어 D 에서 끝낸다. 그래서 라벨도 하는 일 그대로다 */
internal const val REST_LEAVE_BUTTON = "화면 닫기"

/** 쉼 화면 버튼 아래 주석. 바깥 공기로 두는 중이면 그 사실을 앞에 붙인다 */
internal const val REST_LEAVE_NOTE = "기어를 D 로 넣으면 쉼이 끝나요"
internal fun restLeaveNote(freshAir: Boolean): String = if (freshAir) "바깥 공기로 두는 중 · $REST_LEAVE_NOTE" else REST_LEAVE_NOTE

/** 도착 카드 기록 줄 수(순수 함수): 기본 4줄, 맡기기 질문이나 세 갈래 비교가 있으면 2줄, 둘 다 있으면 0줄 */
internal fun arrivalLineCount(threeWay: Boolean, trustOffer: Boolean): Int = when {
    threeWay && trustOffer -> 0
    threeWay || trustOffer -> 2
    else -> 4
}

/** 도착 카드의 맡기기 질문에 답한 뒤 버튼 자리에 보여 주는 한 줄(순수 함수) */
internal fun trustAnswerLine(accepted: Boolean): String =
    if (accepted) "맡겼어요. 설정에서 언제든 끌 수 있어요" else "알겠어요. 계속 물어볼게요"

/** 데모 콘솔의 차 상태 한 줄(순수 함수): "내기순환 ON · 풍량 4/8 · 창문 0%" */
internal fun cabinStateLine(ui: UiState): String = buildString {
    append(if (ui.recirculating) "내기순환 ON" else "바깥 공기(내기순환 OFF)")
    append(" · 풍량 ").append(ui.fanLevel?.let { "$it/${ui.fanMax}" } ?: "모름")
    append(" · 창문 ${ui.windowPercent}%")
    if (ui.frontPassenger) append(" · 조수석 탑승")
    if (ui.carAuto) append(" · 차 자동 공조")
    ui.etaMinutes?.let { append(" · 도착 ${it}분 전") }
    if (ui.trustDelegated) append(" · 맡기기 켜짐")
}

/**
 * 주행 중 오른쪽 "데모" 탭(데모 드로어)을 보여 줄지(순수 함수).
 *  - 연결 판정 중에는 숨긴다(깜빡임 방지)
 *  - 모의 모드면 항상(영상 촬영용)
 *  - Pleos 연결이면 기어 P(쉼 모드 등)이거나 증거 모드가 켜져 있을 때만. 에뮬레이터에서 위치를 못 옮길 때 "쉼터 도착" 등을 누를 수 있게 한다
 */
internal fun showDrivingDrawer(ui: UiState): Boolean =
    ui.tripState == TripState.DRIVING && !ui.probing && (!ui.pleosAvailable || ui.gear == Gear.PARK || ui.evidenceMode)

/** 자가 진단 보고서를 몇 단으로 나눠 적는가 */
internal const val DIAG_COLUMNS = 3

/** 보고서 머리말 아래 한 줄(순수 함수): 도는 중인지, 어디에 남았는지 */
internal fun diagStatusLine(diag: DiagUi): String = when {
    diag.running -> "진단 중... ${diag.lines.size}줄째. 읽기 전용 호출만 돌려요(차 값은 바꾸지 않아요)"
    diag.saved == false -> "Logcat 태그 ${DiagReport.TAG} 에 같은 줄이 있어요. 파일 저장은 실패했어요"
    else -> "Logcat 태그 ${DiagReport.TAG}, 파일 ${DiagReport.FILE_NAME} 에 같은 줄이 있어요. 글자를 길게 눌러 고르거나 [복사]를 누르세요"
}

internal fun gearLabel(g: Gear) = when (g) { Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "?" }

internal fun formatMinutes(m: Int) = if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"

/** 설정 문구 "제목(설명)" 을 제목과 설명으로 나눈다. 괄호가 없으면 설명은 null */
internal fun splitLabel(label: String): Pair<String, String?> {
    val open = label.indexOf('(')
    if (open <= 0 || !label.endsWith(")")) return label to null
    return label.substring(0, open).trim() to label.substring(open + 1, label.length - 1)
}

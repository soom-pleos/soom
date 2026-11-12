package ai.kkaeugi.app.ui

import ai.kkaeugi.engine.BreathDesign
import ai.kkaeugi.engine.Forecast
import ai.kkaeugi.engine.ForecastPoint
import ai.kkaeugi.engine.OutdoorView
import ai.kkaeugi.engine.ThreeWay
import ai.kkaeugi.engine.TracePoint
import ai.kkaeugi.engine.VentPlan
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "숨 설계" 카드와 도착 카드의 세 갈래 비교에 쓰는 문구와 숫자(순수 함수, JVM 테스트 대상).
 * 판단은 하지 않는다. 엔진이 낸 설계(BreathDesign)를 화면 말로 바꾸기만 한다. 전부 추정이라 그렇게 적는다.
 */
object BreathText {
    const val CARD_TITLE = "숨 설계 (추정)"
    /** 덩어리 안에서 줄이 바뀌지 않게 하는 글자: 줄 바꿈 없는 공백과, 글자 사이에 넣는 보이지 않는 낱말 이음표 */
    private const val NBSP = '\u00A0'
    private const val JOINER = "\u2060"
    private const val SEP = " · "

    /** 한 덩어리를 통째로 한 줄에 둔다. 한글은 글자 사이 어디서나 줄이 바뀔 수 있어서 글자마다 이음표를 넣는다 */
    private fun unbreakable(chunk: String): String = chunk.replace(' ', NBSP).toList().joinToString(JOINER)

    /** "1분 30초", "2분", "45초". 데모 속도면 초 그대로("10초") */
    fun duration(seconds: Int, demo: Boolean): String = when {
        demo || seconds < 60 -> "${seconds}초"
        seconds % 60 == 0 -> "${seconds / 60}분"
        else -> "${seconds / 60}분 ${seconds % 60}초"
    }

    /** "밖 미세먼지 나쁨(41)". 값이 없으면 null */
    fun outdoorLabel(o: OutdoorView?): String? = o?.let { "밖 미세먼지 ${it.grade.label}(${it.pm25.roundToInt()})" }

    /** "약 9분마다 1분 30초" / "바깥 공기로 두는 쪽이 나아요" / "약한 바람으로 바깥 공기를 들이는 쪽이 나아요" / "지금은 한도에 닿지 않아요". 간격의 단위는 데모 속도면 초 */
    fun cadence(plan: VentPlan, demo: Boolean): String {
        val interval = plan.intervalMin
        return when {
            interval == null -> "지금은 한도에 닿지 않아요"
            // 미세먼지가 나쁜 날 셋 이상이면 약한 바람으로 계속 들이는 쪽을 권한다(v0.7.4)
            plan.keepFresh && plan.keepFanLevel != null -> "약한 바람으로 바깥 공기를 들이는 쪽이 나아요"
            plan.keepFresh -> "바깥 공기로 두는 쪽이 나아요"
            else -> "약 ${interval.roundToInt().coerceAtLeast(1)}${if (demo) "초" else "분"}마다 ${duration(plan.durationSec, demo)}"
        }
    }

    /**
     * 카드 아래 한 줄: "밖 미세먼지 나쁨(41) · 4명 · 약 9분마다 1분 30초". 미세먼지 값이 없으면 "4명 · 약 6분마다 2분".
     * 지금 외기로 달리는 중이면 설계는 가정이라 "내기순환이면"을 붙인다.
     * 덩어리 안에서는 줄이 바뀌지 않게 한다(두 줄로 넘어가면 " · " 에서만 끊긴다)
     */
    fun planLine(design: BreathDesign, occupants: Int, recirculating: Boolean, demo: Boolean): String {
        val cadence = cadence(design.plan, demo)
        val hypothetical = !recirculating && design.plan.intervalMin != null && !design.plan.keepFresh
        return listOfNotNull(
            outdoorLabel(design.outdoor),
            "${occupants.coerceAtLeast(1)}명",
            if (hypothetical) "내기순환이면 $cadence" else cadence,
        ).joinToString(SEP) { unbreakable(it) }
    }

    /**
     * 예보 한 줄: "도착까지 환기 약 3번 · 첫 번째는 4분 뒤" / "앞으로 1시간 환기 약 3번 · 첫 번째는 곧".
     * 환기가 없으면 "도착까지 환기 없이 갈 수 있어요". 데모 속도면 분이 초로 흐른다
     */
    fun forecastLine(f: Forecast, demo: Boolean): String {
        val unit = if (demo) "초" else "분"
        val head = when {
            f.toArrival -> "도착까지"
            !demo && f.horizonMin == 60 -> "앞으로 1시간"
            else -> "앞으로 ${f.horizonMin}$unit"
        }
        if (f.ventCount <= 0) return "$head 환기 없이 갈 수 있어요"
        val first = f.firstVentInMin?.let { if (it <= 0) "곧" else "$it$unit 뒤" } ?: "곧"
        return if (f.keepFresh) "첫 환기는 $first, 그 뒤로는 바깥 공기로" else "$head 환기 약 ${f.ventCount}번${SEP}첫 번째는 $first"
    }

    /** 묻지 않는 중일 때 예보 자리에 적는 말. 예보는 "제안에 모두 동의하면"을 가정하기 때문이다 */
    const val NOTE_QUIET = "이번 주행은 묻지 않아요. 예보는 참고용이에요"
    const val NOTE_OFF = "숨이 꺼져 있어요. 예보는 참고용이에요"

    /** 카드 맨 아래 줄: 평소에는 예보 한 줄, 숨이 꺼져 있거나 이번 주행에서 묻지 않는 중이면 그 사실 */
    fun bottomLine(f: Forecast, demo: Boolean, autoIntervene: Boolean, quiet: Boolean): String = when {
        !autoIntervene -> NOTE_OFF
        quiet -> NOTE_QUIET
        else -> forecastLine(f, demo)
    }

    // ---------- 그래프 ----------

    /** 그래프에 그릴 지나온 구간과 내다보는 구간의 길이(분, 모델 시간) */
    const val CHART_PAST_MIN = 30.0
    const val CHART_AHEAD_MIN = 30.0

    /** 그래프 한 점: x 는 0(왼쪽 끝)~1(오른쪽 끝), y 는 0(바닥)~1(천장). 천장을 넘는 값은 1 로 자른다 */
    data class ChartPoint(val x: Float, val y: Float)

    /** 그래프 재료. now 는 "지금"의 x 위치 */
    data class Chart(
        val actual: List<ChartPoint>,
        val shadow: List<ChartPoint>,
        val forecast: List<ChartPoint>,
        val limitY: Float,
        val nowX: Float,
        /** 그림자(내기만)의 지금 값이 천장을 넘었는가(라벨에 숫자를 적는다) */
        val shadowClipped: Boolean,
    )

    /** 세로 눈금의 천장(ppm): 한도의 2배. 그림자 곡선은 이 위로 잘린다 */
    fun chartTop(limit: Int): Int = limit * 2

    /** 지나온 구간이 짧을 때 그래프 왼쪽에 잡는 가장 짧은 길이(분). 이때 "지금"은 왼쪽 4분의 1 지점이다 */
    const val CHART_PAST_SHORT_MIN = 10.0

    /**
     * 그래프 왼쪽(지나온 구간)에 잡을 길이(분, 순수 함수). 출발 직후에는 지나온 곡선이 짧아 왼쪽 절반이 비어 보인다.
     * 그래서 지나온 길이만큼만 잡는다: 10분 이하면 10분("지금"이 왼쪽 4분의 1), 30분 이상이면 30분("지금"이 한가운데), 그 사이는 지나온 길이 그대로
     */
    fun pastWindowMin(trace: List<TracePoint>): Double {
        val covered = if (trace.size >= 2) trace.last().minute - trace.first().minute else 0.0
        return covered.coerceIn(CHART_PAST_SHORT_MIN, CHART_PAST_MIN)
    }

    /**
     * 곡선 재료를 만든다. 가로는 지나온 pastMin 분과 앞으로 CHART_AHEAD_MIN 분. pastMin 을 주지 않으면 CHART_PAST_MIN 이라 "지금"이 한가운데다.
     * 주행 홈은 pastWindowMin(trace) 를 넘겨 지나온 구간이 짧을 때 예보 구간을 넓게 쓴다.
     * 세로는 바깥 공기(outdoorPpm)부터 한도의 2배까지
     */
    fun chart(trace: List<TracePoint>, forecast: List<ForecastPoint>, limit: Int, outdoorPpm: Int, pastMin: Double = CHART_PAST_MIN): Chart {
        val top = chartTop(limit).toFloat()
        val bottom = outdoorPpm.toFloat()
        val past = pastMin.coerceIn(1.0, CHART_PAST_MIN)
        val span = past + CHART_AHEAD_MIN
        val nowMinute = trace.lastOrNull()?.minute ?: 0.0
        fun y(ppm: Int): Float = ((ppm - bottom) / (top - bottom).coerceAtLeast(1f)).coerceIn(0f, 1f)
        fun x(offsetMin: Double): Float = ((offsetMin + past) / span).toFloat().coerceIn(0f, 1f)
        val recent = trace.filter { it.minute >= nowMinute - past }
        return Chart(
            actual = recent.map { ChartPoint(x(it.minute - nowMinute), y(it.ppm)) },
            shadow = recent.map { ChartPoint(x(it.minute - nowMinute), y(it.recircOnlyPpm)) },
            forecast = forecast.filter { it.minute <= CHART_AHEAD_MIN }.map { ChartPoint(x(it.minute), y(it.ppm)) },
            limitY = y(limit),
            nowX = x(0.0),
            shadowClipped = (trace.lastOrNull()?.recircOnlyPpm ?: 0) > top,
        )
    }

    // ---------- 도착 카드: 세 갈래 비교 ----------

    /** 막대 하나: 이름, 값 글자, 길이(0~1, 세 갈래 중 가장 큰 값이 1) */
    data class Bar(val label: String, val value: String, val fraction: Float, val mine: Boolean = false)

    /** 막대 한 줄 */
    data class BarRow(val title: String, val unit: String, val bars: List<Bar>)

    const val WAY_RECIRC = "내기만"
    const val WAY_FRESH = "외기만"
    const val WAY_ACTUAL = "이번 주행"
    /** 세 갈래 아래 주석의 첫 문장. 내기만, 외기만 값은 가정(같은 인원과 풍량으로 내내 그렇게 달렸다)에 따라 크게 흔들린다. 그 사실을 먼저 말한다 */
    const val THREE_WAY_NOTE = "가정에 따라 크게 달라지는 추정이에요. 같은 인원과 풍량으로 내내 내기만, 내내 외기만 달렸다고 보고 계산한 값이에요"
    const val THREE_WAY_NO_PM = "바깥 미세먼지 값이 없어 CO2 만 비교해요"

    /**
     * 가정으로 낸 갈래(내기만, 외기만)의 CO2 값 글자: 백 단위로 반올림하고 앞에 "약"을 붙인다("약 5,600").
     * 엔진이 낸 숫자는 그대로 두고 화면에 적을 때만 정밀도를 낮춘다. 한 자리까지 맞는 값처럼 보이지 않게 하려는 것이다
     */
    fun approxPpm(ppm: Int): String = "약 " + String.format(Locale.US, "%,d", (ppm / 100.0).roundToInt() * 100)

    /** 가정으로 낸 갈래의 미세먼지 평균 글자: 정수로 반올림하고 "약"을 붙인다("약 7"). 1 보다 작으면 "1 미만" */
    fun approxPm(average: Double): String = if (average < 1.0) "1 미만" else "약 ${average.roundToInt()}"

    /**
     * 막대 두 줄: CO2 최고값(ppm), 미세먼지 노출(실내 PM2.5 평균, µg/m³). 미세먼지 값이 없었으면 CO2 한 줄만.
     * 순서는 내기만, 외기만, 이번 주행. 내기만과 외기만은 가정으로 낸 값이라 어림으로 적는다(approxPpm, approxPm).
     * 막대 길이는 어림하기 전의 값으로 잰다
     */
    fun threeWayRows(w: ThreeWay): List<BarRow> {
        val ways = listOf(WAY_RECIRC to w.recircOnly, WAY_FRESH to w.freshOnly, WAY_ACTUAL to w.actual)
        val maxPpm = ways.maxOf { it.second.peakPpm }.coerceAtLeast(1)
        val co2 = BarRow("CO2 최고(추정)", "ppm", ways.map { (name, s) ->
            val mine = name == WAY_ACTUAL
            Bar(name, if (mine) String.format(Locale.US, "%,d", s.peakPpm) else approxPpm(s.peakPpm), s.peakPpm / maxPpm.toFloat(), mine = mine)
        })
        val averages = ways.map { it.second.pmAverage }
        if (averages.any { it == null }) return listOf(co2)
        val maxPm = averages.maxOf { it!! }.coerceAtLeast(0.1)
        val pm = BarRow("미세먼지 노출(추정)", "평균 µg/m³", ways.map { (name, s) ->
            val v = s.pmAverage!!
            val mine = name == WAY_ACTUAL
            val exact = if (v < 10) String.format(Locale.US, "%.1f", v) else v.roundToInt().toString()
            Bar(name, if (mine) exact else approxPm(v), (v / maxPm).toFloat(), mine = mine)
        })
        return listOf(co2, pm)
    }

    /** 세 갈래 아래 작은 글씨. 미세먼지 값이 있었으면 등급과 출처까지 */
    fun threeWayFootnote(w: ThreeWay): String =
        if (w.pmGrade != null && w.pmOutdoor != null) "$THREE_WAY_NOTE. 밖 미세먼지 ${w.pmGrade!!.label}(${w.pmOutdoor})"
        else "$THREE_WAY_NOTE. $THREE_WAY_NO_PM"
}

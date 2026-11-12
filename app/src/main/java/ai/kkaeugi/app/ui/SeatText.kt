package ai.kkaeugi.app.ui

import ai.kkaeugi.app.DemoText
import ai.kkaeugi.engine.ArrivalSummary
import ai.kkaeugi.engine.SeatCare
import ai.kkaeugi.engine.InfectionStats
import ai.kkaeugi.engine.SeatProfile
import ai.kkaeugi.engine.SeatStats
import ai.kkaeugi.engine.Seats
import ai.kkaeugi.engine.ThreeWay
import ai.kkaeugi.engine.WakeConfig
import kotlin.math.roundToInt

/**
 * 자리별 설정과 자리별 요약의 화면 글(순수 함수, JVM 테스트 대상). v0.7.3.
 * 여기서는 판단하지 않는다. 엔진이 낸 값(SeatCare, SeatStats)을 화면 말로 바꾸기만 한다.
 * 프로필은 설정이다. 그래서 "무엇이 바뀌는지"만 적고, 몸에 어떤 일이 생긴다는 말은 하지 않는다
 */
internal object SeatText {
    /** 정차 화면과 선택 시트에 적는 한 줄. 프로필이 무엇인지 밝힌다 */
    const val PROFILE_NOTE = "이 자리 설정으로 환기와 쉼의 순서가 바뀌어요. 의학적 조언이 아니에요."
    /** 프로필은 사람이 아니라 자리에 붙는다 */
    const val STICKY_NOTE = "사람이 자리를 바꿔도 설정은 자리에 붙어 있어요. 기기 안에만 저장해요."
    const val HINT = "자리를 누르면 그 자리 설정을 고를 수 있어요"
    /** 자리별 값이 없는 차(직접 입력)에서 덧붙이는 말. 엔진의 규칙 그대로다 */
    const val MANUAL_NOTE = "자리 값을 못 읽는 차라 둘 이상 타면 고른 설정을 모두 써요."
    const val CLEAR_ALL = "전부 지우기"
    const val LIST_TITLE = "자리 설정"

    /** 선택 시트의 제목: "조수석 설정" */
    fun sheetTitle(seat: Int): String = "${Seats.name(seat)} 설정"

    /** 선택 시트에서 프로필 이름 아래에 적는 한 줄: 이 설정이 무엇을 바꾸는지 */
    fun effect(p: SeatProfile, config: WakeConfig = WakeConfig()): String {
        val rest = "${config.sitBreakMinutesShort}분마다 쉬어요"
        return when (p) {
            SeatProfile.DEFAULT -> "바꾸는 것이 없어요"
            SeatProfile.CHILD -> "환기를 조금 일찍 물어요"
            SeatProfile.PREGNANT -> "환기를 조금 일찍 묻고, $rest"
            SeatProfile.SENIOR -> rest
            SeatProfile.RESPIRATORY -> "미세먼지를 먼저 봐요"
            SeatProfile.CARDIO -> "미세먼지를 먼저 보고, $rest"
            SeatProfile.COUGH -> "바깥 공기로 계속 두자고 일찍 물어요"
        }
    }

    /** 도착 카드의 기침 자리 한 줄(v0.8.0, 전부 추정): "기침 자리와 같이 탄 3명, 추정 감염 위험 24%" 와 "내내 내기였다면 98%" */
    fun infectionLine(i: InfectionStats): Pair<String, String> =
        "기침 자리와 같이 탄 ${i.others}명, 추정 감염 위험 ${pct(i.actualPercent)}" to "내내 내기였다면 ${pct(i.recircOnlyPercent)}"

    private fun pct(v: Double): String = when {
        v >= 99.5 || v < 10 -> "${"%.1f".format(v)}%"
        else -> "${Math.round(v)}%"
    }

    /** 정차 화면의 목록: 프로필을 고른 자리만 ("조수석" to "임산부") */
    fun chosen(profiles: List<SeatProfile>): List<Pair<String, String>> =
        profiles.take(Seats.COUNT).mapIndexedNotNull { i, p -> if (p == SeatProfile.DEFAULT) null else Seats.name(i) to p.label }

    /** 좌석 지도에 표시할 자리(프로필을 고른 자리) */
    fun marked(profiles: List<SeatProfile>): List<Boolean> = List(Seats.COUNT) { (profiles.getOrNull(it) ?: SeatProfile.DEFAULT) != SeatProfile.DEFAULT }

    /**
     * 주행 홈의 "이번 주행에서 살피는 것" 한 줄: "미세먼지 먼저 · 90분마다 쉬기". 데모 속도면 분이 초다.
     * 걷기 쉼이 꺼져 있으면 쉬는 간격은 적지 않는다
     */
    fun careLine(care: SeatCare, demo: Boolean = false): String {
        val air = when {
            care.pmFirst && care.wideMargin -> "미세먼지 먼저, 공기 여유 넓게"
            care.pmFirst -> "미세먼지 먼저"
            care.wideMargin -> "공기 여유 넓게"
            else -> "기본 설정"
        }
        val rest = care.breakMinutes?.let { DemoText.minutesToSeconds("${it}분마다 쉬기", demo) }
        return listOfNotNull(air, rest).joinToString(" · ")
    }

    /** 주행 홈의 앉은 시간 한 줄: "앉은 지 1시간 12분". 좌석과 기어 신호로 센 시간이다 */
    fun sitLine(care: SeatCare, demo: Boolean = false): String = when {
        care.sitMinutes < 1 -> "방금 앉았어요"
        demo -> "앉은 지 ${care.sitMinutes}초"
        else -> "앉은 지 ${formatMinutes(care.sitMinutes)}"
    }

    // ---------- 도착 카드: 자리별 요약 ----------

    /** 숫자와 단위 한 쌍. 단위는 작고 흐리게 그린다. 숫자가 아닌 말("없음")이면 단위는 비운다 */
    data class Cell(val number: String, val unit: String = "")

    /** 자리 한 줄 */
    data class Row(
        val name: String,
        /** 이름 옆 작은 글: 프로필, 중간에 탔는지, 차 전체 줄이면 탑승 인원 */
        val tag: String,
        val over: Cell, val pm: Cell, val sit: List<Cell>, val walk: Cell,
    )

    /** 표 머리: 자리, 탁한 공기(한도 위), 미세먼지(평균), 가장 오래 앉음, 걷기 쉼 */
    val HEADERS = listOf("자리", "탁한 공기", "미세먼지", "가장 오래 앉음", "걷기 쉼")
    val HEADER_NOTES = listOf("", "한도 위", "평균", "", "")

    private fun unit(demo: Boolean) = if (demo) "초" else "분"

    private fun pmCell(average: Double?): Cell = when {
        average == null -> Cell("값 없음")
        average < 1.0 -> Cell("1 미만", "µg/m³")
        else -> Cell(average.roundToInt().toString(), "µg/m³")
    }

    private fun sitCells(minutes: Int, demo: Boolean): List<Cell> = when {
        demo -> listOf(Cell(minutes.toString(), "초"))
        minutes >= 60 && minutes % 60 == 0 -> listOf(Cell((minutes / 60).toString(), "시간"))
        minutes >= 60 -> listOf(Cell((minutes / 60).toString(), "시간"), Cell((minutes % 60).toString(), "분"))
        else -> listOf(Cell(minutes.toString(), "분"))
    }

    private fun walkCell(count: Int): Cell = if (count > 0) Cell(count.toString(), "회") else Cell("없음")

    private fun row(s: SeatStats, name: String, tag: String, pmAverage: Double?, demo: Boolean) = Row(
        name = name, tag = tag,
        over = Cell(s.minutesOverLimit.toString(), unit(demo)),
        pm = pmCell(pmAverage),
        sit = sitCells(s.longestSitMinutes, demo),
        walk = walkCell(s.walkBreaks),
    )

    /**
     * 도착 카드의 자리별 줄. 차가 자리별 값을 준 주행이면 탄 자리마다 한 줄, 아니면 "차 전체" 한 줄.
     * 데모 속도면 한도 위 시간과 앉은 시간은 초다. 요약이 비어 있으면(주행한 적 없음) 빈 목록
     */
    fun rows(card: ArrivalSummary, maxOccupants: Int, demo: Boolean): List<Row> {
        if (card.seats.isNotEmpty()) return card.seats.map { s ->
            val tag = listOfNotNull(s.profile.label.takeIf { s.profile != SeatProfile.DEFAULT }, "중간에 탐".takeIf { s.boardedMidTrip }).joinToString(" · ")
            row(s, Seats.name(s.seat), tag, s.pmAverage, demo)
        }
        val cabin = card.cabin ?: return emptyList()
        // 차 전체 줄의 미세먼지는 세 갈래 비교의 "이번 주행" 값이다
        return listOf(row(cabin, Seats.name(Seats.CABIN), "${maxOccupants.coerceAtLeast(1)}명 탑승", card.threeWay?.actual?.pmAverage, demo))
    }

    /** 도착 카드 제목. 엔진이 만든 한 문장("오늘 2시간 10분, 넷이 탔어요."). 없으면(옛 요약) 기존 첫 문장 */
    fun headline(card: ArrivalSummary, demo: Boolean): String = card.seatHeadline.ifBlank { DemoText.arrivalHeadline(card, demo) }

    /**
     * 맨 아래 한 줄로 접은 세 갈래 비교: 같은 조건으로 내내 내기만, 내내 외기만 탔다면. 가정에 따라 크게 흔들리는 값이라 어림으로 적는다.
     * 반환: (내기만 문장, 외기만 문장)
     */
    fun whatIf(w: ThreeWay): Pair<String, String> {
        fun one(label: String, peak: Int, pm: Double?) =
            "$label CO2 최고 ${BreathText.approxPpm(peak)}ppm" + (pm?.let { " · 미세먼지 ${BreathText.approxPm(it)}µg/m³" } ?: "")
        return one("내기만 탔다면", w.recircOnly.peakPpm, w.recircOnly.pmAverage) to one("외기만 탔다면", w.freshOnly.peakPpm, w.freshOnly.pmAverage)
    }

    /** 도착 카드 주석 첫 줄: 전부 추정이라는 것과 자리별 값이 어떻게 나왔는지 */
    const val FOOTNOTE = "전부 추정이에요(센서 값 아님). 차 안 공기는 한 덩어리로 계산해 자리마다 탄 시간만큼 적었고, 앉은 시간은 좌석과 기어 신호로 셌어요."
    const val FOOTNOTE_CABIN = "전부 추정이에요(센서 값 아님). 이 차는 자리별 값을 주지 않아 차 전체로 적었고, 앉은 시간은 운전석 기준이에요."

    fun footnote(card: ArrivalSummary): String = if (card.seats.isNotEmpty()) FOOTNOTE else FOOTNOTE_CABIN
}

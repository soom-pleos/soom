package ai.kkaeugi.app

import ai.kkaeugi.engine.ArrivalSummary

/**
 * 데모 속도(분→초)일 때 화면·발화 문구의 단위를 맞춘다(순수 함수, JVM 테스트 대상).
 * 엔진은 데모에서도 "15분", "17분 쉬셨어요" 로 말하지만 실제로 흐른 시간은 초라서, 앱이 보여 주고 읽기 직전에 초로 바꾼다.
 */
object DemoText {
    /** 쉼 카드 제목과 발화. 데모면 "석적졸음쉼터. 15초만 쉬고 가세요(데모)". 걷기 쉼(walk)이면 "내려서 5분 걸어요" */
    fun restLine(stopName: String, target: Int, demo: Boolean, forSpeech: Boolean = false, walk: Boolean = false): String {
        val unit = if (demo) "초" else "분"
        val body = if (walk) "내려서 $target$unit 걸어요" else "$target${unit}만 쉬고 가세요"
        return "$stopName. $body" + (if (demo && !forSpeech) "(데모)" else "")
    }

    /** "17분 푹 쉬셨어요" → "17초 푹 쉬셨어요". 숫자 뒤의 "분"만 바꾼다("분명", "기분" 은 그대로) */
    fun minutesToSeconds(text: String, demo: Boolean): String =
        if (demo) text.replace(Regex("(\\d+)분"), "$1초") else text

    // ---- 도착 카드 ----
    // 엔진 요약은 데모 속도에서도 "분"으로 적혀 있다. 쉼 시간, 한도 초과 시간은 실제로는 초였으니 화면과 발화에서 초로 바꾼다.

    /** 도착 카드 숫자 한 칸 */
    data class Metric(val label: String, val value: String)

    /** 도착 카드 첫 문장. 데모 속도면 "쉼터에서 15분" → "쉼터에서 15초" */
    fun arrivalHeadline(card: ArrivalSummary, demo: Boolean): String = minutesToSeconds(card.headline, demo)

    /**
     * 도착 카드 숫자 줄. freshUnits 는 앱이 엔진과 같은 기준으로 센 바깥 공기 누적(데모 속도면 초).
     * 엔진의 freshAirMinutes 는 실제 분이라 데모 속도에서는 구간마다 1 로 뭉개진다. 그래서 데모 속도에서는 앱이 센 초를 쓴다.
     * 주행 시간은 바꾸지 않는다(엔진이 실제 분으로 센 값. 데모 콘솔의 "트립 +120분"도 분이다)
     */
    fun arrivalMetrics(card: ArrivalSummary, demo: Boolean, freshUnits: Int): List<Metric> {
        val unit = if (demo) "초" else "분"
        return buildList {
            add(Metric("주행", formatMinutes(card.tripMinutes)))
            add(Metric("환기", "${card.ventilations}회"))
            add(Metric("바깥 공기", "${if (demo) freshUnits else card.freshAirMinutes}$unit"))
            add(Metric("졸음 경고", "${card.drowsySignals}회"))
            add(Metric("쉼터", "${card.restStopsVisited}회 ${card.restMinutes}$unit"))
            if (card.restStopsPassed > 0) add(Metric("지나침", "${card.restStopsPassed}회"))
            add(Metric("거절", "${card.declined}회"))
        }
    }

    /**
     * 도착 카드 공기 줄(한 번만): "최고 2,300ppm 추정  ·  한도 위 4분(추정)  ·  4명 탑승". 추정치가 없으면(0) 인원만.
     * 한도 위 시간은 모델 시간(분)이다. 데모 속도에서는 실제 초와 같아 "초"로 적는다
     */
    fun arrivalAirLine(card: ArrivalSummary, maxOccupants: Int, demo: Boolean): String = buildString {
        if (card.peakPpm > 0) append("최고 ${"%,d".format(java.util.Locale.US, card.peakPpm)}ppm 추정  ·  한도 위 ${card.minutesOverLimit}${if (demo) "초" else "분"}(추정)  ·  ")
        append("${maxOccupants.coerceAtLeast(1)}명 탑승")
    }

    /**
     * 도착 카드 아래 기록 줄(최대 4줄). 공기 줄은 위에서 한 번 보여 주므로, 엔진 줄에 공기 줄이 섞여 있으면 뺀다.
     * 데모 속도면 "출발 N분 뒤" 머리말(실제 분)은 그대로 두고 그 뒤의 분(쉼 시간 등)만 초로 바꾼다
     */
    fun arrivalLines(card: ArrivalSummary, demo: Boolean): List<String> = card.lines
        .filterNot { it.contains("최고 추정") }
        .take(4)
        .map { line ->
            if (!demo) return@map line
            val head = LINE_HEAD.find(line)?.value ?: ""
            head + minutesToSeconds(line.removePrefix(head), true)
        }

    private val LINE_HEAD = Regex("^(출발 직후|출발 \\d+분 뒤) ")

    private fun formatMinutes(m: Int) = if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

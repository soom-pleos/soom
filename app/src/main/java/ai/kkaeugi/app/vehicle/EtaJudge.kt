package ai.kkaeugi.app.vehicle

import kotlin.math.ceil

/**
 * 내비 목적지의 남은 시간(DestinationInfo.remainTime)을 분으로 바꾼다(순수 로직, JVM 테스트 대상).
 *
 * SDK 문서에 remainTime 의 단위가 없다. 그래서 남은 거리(remainDistance, m)와 함께 받아
 * "거리 / 시간" 이 시속 20~150km 가 되는 단위(초 또는 분)를 고른다. 두 후보는 60배 차이라 둘 다 범위에 드는 일은 없다.
 * 어느 쪽도 범위에 들지 않으면(정체, 값 이상) 이번 값으로는 판정하지 않고, 같은 경로에서 앞서 판정한 단위가 있으면 그것을 쓴다.
 * 한 번도 판정하지 못했으면 null(엔진은 목적지를 모르는 것으로 본다. 도착 임박 보류만 빠진다).
 *
 * 이미 정한 단위는 표본 하나로 뒤집지 않는다. 반대 판정이 두 번 연속일 때만 바꾼다(정체 구간의 한 표본이 60배 틀린 분을 만들지 않게).
 * 남은 거리가 MIN_SAMPLE_M 미만인 표본은 판정에 쓰지 않는다(도착 직전에는 거리와 시간이 모두 작아 속도가 아무 값이나 나온다).
 */
class EtaJudge {
    /** 마지막으로 판정한 단위. true = 초, false = 분, null = 아직 모름 */
    var unitSeconds: Boolean? = null
        private set
    /** 지금 단위와 반대로 판정된 표본이 연달아 온 횟수 */
    private var oppositeStreak = 0

    /** 경로가 바뀌거나 끝났을 때. 단위는 차가 바뀌지 않는 한 같으므로 지우지 않는다 */
    fun minutes(remainTime: Int, remainDistanceM: Int): Int? {
        if (remainTime < 0) return null
        val judged = if (remainDistanceM >= MIN_SAMPLE_M) unitOf(remainTime, remainDistanceM) else null
        when {
            judged == null -> {}
            unitSeconds == null -> { unitSeconds = judged; oppositeStreak = 0 }
            judged == unitSeconds -> oppositeStreak = 0
            else -> if (++oppositeStreak >= SWITCH_AFTER) { unitSeconds = judged; oppositeStreak = 0 }
        }
        val seconds = unitSeconds ?: return null
        return if (seconds) ceil(remainTime / 60.0).toInt() else remainTime
    }

    companion object {
        const val MIN_KPH = 20.0
        const val MAX_KPH = 150.0
        /** 남은 거리가 이 값(m) 미만이면 단위 판정에 쓰지 않는다 */
        const val MIN_SAMPLE_M = 300
        /** 반대 판정이 이만큼 연속이면 단위를 바꾼다 */
        const val SWITCH_AFTER = 2

        /** 평균 속도(km/h). 시간 단위가 초라고 볼 때와 분이라고 볼 때 */
        fun kphIfSeconds(remainTime: Int, remainDistanceM: Int): Double = remainDistanceM / 1000.0 / (remainTime / 3600.0)
        fun kphIfMinutes(remainTime: Int, remainDistanceM: Int): Double = remainDistanceM / 1000.0 / (remainTime / 60.0)

        /** true = 초, false = 분, null = 판정 불가(값이 0 이하이거나 어느 쪽도 20~150km/h 가 아님) */
        fun unitOf(remainTime: Int, remainDistanceM: Int): Boolean? {
            if (remainTime <= 0 || remainDistanceM <= 0) return null
            val sec = kphIfSeconds(remainTime, remainDistanceM) in MIN_KPH..MAX_KPH
            val min = kphIfMinutes(remainTime, remainDistanceM) in MIN_KPH..MAX_KPH
            return when {
                sec && !min -> true
                min && !sec -> false
                else -> null
            }
        }

        /** 자가 진단용 한 줄: "남은 시간 raw 1260, 거리 raw 28000 → 초(80km/h), 21분" */
        fun describe(remainTime: Int, remainDistanceM: Int, minutes: Int?): String {
            val unit = unitOf(remainTime, remainDistanceM)
            val judged = when (unit) {
                true -> "초(${kphIfSeconds(remainTime, remainDistanceM).toInt()}km/h)"
                false -> "분(${kphIfMinutes(remainTime, remainDistanceM).toInt()}km/h)"
                null -> "단위 판정 불가"
            }
            return "남은 시간 raw $remainTime, 거리 raw $remainDistanceM → $judged" + (minutes?.let { ", ${it}분" } ?: "")
        }
    }
}

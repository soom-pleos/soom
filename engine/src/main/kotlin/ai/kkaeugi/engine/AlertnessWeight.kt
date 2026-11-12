package ai.kkaeugi.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * 졸리기 쉬운 시간대 가중(0~1). 순수 Kotlin, 상태 없음.
 *
 * 무엇인가: 하루 중 시각과 연속 주행 시간만으로 낸 어림이다. 운전자를 보거나 재는 것이 없다. 졸음을 알아내는 값이 아니다.
 *
 * 어디서 왔나(docs/EVIDENCE.md I 절):
 *  - Åkerstedt·Folkard 의 3과정 각성 모델은 각성도를 일주기 성분 C(사인파)와 깨어 있던 시간 성분 S 의 합으로 예측한다.
 *    항공 검증(Ingre 외 2014)은 S 와 C 두 과정만으로 줄일 수 있다고 봤다.
 *  - Horne·Reyner(1995)는 졸음 관련 사고가 하루 중 02시, 06시, 16시 무렵에 봉우리를 보인다고 보고했다.
 *
 * 여기서 한 단순화(모델을 그대로 옮긴 것이 아니다. 계수도 논문 값이 아니라 자체 설정이다):
 *  - C: 새벽 04시 부근이 가장 졸리고(높이 1), 오후 15시 부근에 작은 봉우리(높이 0.35)가 있는 곡선 하나로 줄였다
 *  - S: 운전자가 몇 시에 일어났는지, 얼마나 잤는지 모른다. 그래서 깨어 있던 시간 대신 연속 주행 시간으로만 어림한다.
 *    밤새 깨어 있다가 막 출발한 사람은 이 값이 낮게 나온다. 이 한계 때문에 한도나 제안 시점을 이 값으로 바꾸지 않는다
 *  - 합: CIRCADIAN_SHARE × C + (1 − CIRCADIAN_SHARE) × S
 *
 * 쓰는 곳: 화면 표시("졸리기 쉬운 시간대")와, 졸음 제안에서 쉼터 경유를 먼저 실행하는 순서뿐이다.
 * 환기 한도를 낮추는 규칙은 v0.6.2 의 "야간 장거리 0.8배"를 그대로 둔다.
 */
object AlertnessWeight {
    /** 가장 졸린 시각(시)과 그 봉우리의 반폭(시간). 23시부터 09시까지 걸친다 */
    const val NIGHT_PEAK_HOUR = 4.0
    const val NIGHT_HALF_WIDTH_H = 5.0
    /** 오후의 작은 봉우리: 15시, 반폭 2.5시간(12시 30분부터 17시 30분), 높이 0.35 */
    const val AFTERNOON_PEAK_HOUR = 15.0
    const val AFTERNOON_HALF_WIDTH_H = 2.5
    const val AFTERNOON_HEIGHT = 0.35
    /** 시각 성분이 차지하는 몫. 나머지는 연속 주행 성분 */
    const val CIRCADIAN_SHARE = 0.7
    /** 이 값 이상이면 "졸리기 쉬운 시간대"라고 표시한다 */
    const val SLEEPY_THRESHOLD = 0.5
    /** 시각을 모를 때 하향등이 켜져 있으면(밤으로 보면) 쓰는 시각 성분. 모르는 만큼 봉우리보다 낮게 잡는다 */
    const val NIGHT_HINT_CIRCADIAN = 0.6
    private const val MINUTES_PER_DAY = 1440

    /** 시각 성분(0~1). minuteOfDay: 자정부터 흐른 분 */
    fun circadian(minuteOfDay: Int): Double {
        val h = (((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY) / 60.0
        val night = bump(h, NIGHT_PEAK_HOUR, NIGHT_HALF_WIDTH_H)
        val afternoon = AFTERNOON_HEIGHT * bump(h, AFTERNOON_PEAK_HOUR, AFTERNOON_HALF_WIDTH_H)
        return maxOf(night, afternoon)
    }

    /** 연속 주행 성분(0~1). longTripMinutes(기본 120분)에서 1 이 되게 곧게 올린다 */
    fun drive(driveMinutes: Double, longTripMinutes: Int): Double =
        (driveMinutes / longTripMinutes.coerceAtLeast(1)).coerceIn(0.0, 1.0)

    /**
     * 가중. minuteOfDay 가 null(시각 모름)이면 nightHint(하향등으로 본 밤)로만 어림한다.
     * 돌려주는 Alertness.sleepyHours 가 화면의 "졸리기 쉬운 시간대" 표시다
     */
    fun weigh(minuteOfDay: Int?, driveMinutes: Double, longTripMinutes: Int, nightHint: Boolean = false): Alertness {
        val c = when {
            minuteOfDay != null -> circadian(minuteOfDay)
            nightHint -> NIGHT_HINT_CIRCADIAN
            else -> 0.0
        }
        val s = drive(driveMinutes, longTripMinutes)
        val w = (CIRCADIAN_SHARE * c + (1 - CIRCADIAN_SHARE) * s).coerceIn(0.0, 1.0)
        return Alertness(w, c, s, clockKnown = minuteOfDay != null, sleepyHours = w >= SLEEPY_THRESHOLD)
    }

    /** center 에서 1, 반폭 밖에서 0 인 매끈한 봉우리. 자정을 넘는 거리도 센다 */
    private fun bump(hour: Double, center: Double, halfWidth: Double): Double {
        var d = abs(hour - center)
        if (d > 12.0) d = 24.0 - d
        return if (d >= halfWidth) 0.0 else 0.5 * (1 + cos(PI * d / halfWidth))
    }
}

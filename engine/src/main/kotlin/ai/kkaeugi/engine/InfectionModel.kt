package ai.kkaeugi.engine

import kotlin.math.exp

/**
 * 기침 자리 감염 위험 추정(v0.8.0). 순수 Kotlin. 전부 추정이다.
 *
 * Knibbs, Morawska, Bell (2012) "The risk of airborne influenza transmission in passenger cars", Epidemiol. Infect. 140(3) 이
 * 실제 승용차 두 대의 공기 교환율로 쓴 Gammaitoni-Nucci 형 Wells-Riley 식을 그대로 쓴다(docs/EVIDENCE.md K 절).
 *   dn/dt = q·I/V − N·n          n: 실내 감염 입자 농도(quanta/m³), N: 공기 교환(회/시간)
 *   위험  = 1 − exp(−p·∫n dt)      p: 감염되지 않은 사람의 호흡량(m³/h)
 * 기본값은 논문과 같다: q = 67 quanta/h(인플루엔자 중앙값, 범위 15~128), p = 0.6 m³/h, 감염자 I = 1.
 * 공기가 고루 섞인다고 본다(자리마다 다르지 않다). 그래서 이 값은 "기침하는 사람과 같이 탄 다른 사람 한 명"의 위험이다.
 *
 * 쉼터에서 문을 열고 내리면 차 안 입자는 빠진다고 보고 농도만 0 으로 돌린다. 들이마신 양(dose)은 이어 간다.
 */
class InfectionModel(var config: WakeConfig) {

    /** 실내 감염 입자 농도(quanta/m³) */
    var concentration = 0.0
        private set
    /** 지금까지 한 사람이 들이마신 양(quanta) */
    var dose = 0.0
        private set
    /** 기침하는 사람과 다른 사람이 같이 탄 시간(분) */
    var sharedMinutes = 0.0
        private set

    fun reset() { concentration = 0.0; dose = 0.0; sharedMinutes = 0.0 }

    /** 쉼에서 돌아왔다: 차 안 입자는 빠졌다고 본다. 들이마신 양은 남는다 */
    fun reopen() { concentration = 0.0 }

    /**
     * 모델 시간으로 minutes 분 전진. ach: 이 구간의 공기 교환(회/시간). active: 기침하는 자리와 다른 사람이 같이 타 있는가.
     * 같이 타 있지 않으면 입자를 새로 만들지 않고 빠지기만 한다
     */
    fun advance(minutes: Double, ach: Double, active: Boolean) {
        if (minutes <= 0) return
        val v = (config.cabinVolumeL / 1000.0).coerceAtLeast(0.5)
        val src = if (active) config.coughQuantaPerHour * INFECTORS / v else 0.0
        val n0 = concentration
        val h = minutes / 60.0
        val a = ach.coerceAtLeast(MIN_ACH)
        // 정확한 해: n(t) = s/a + (n0 − s/a)e^(−a t). 구간 적분도 닫힌 꼴로 쌓는다
        val ss = src / a
        val e = exp(-a * h)
        val n1 = ss + (n0 - ss) * e
        val integral = ss * h + (n0 - ss) * (1 - e) / a
        if (active) {
            dose += config.breathingM3PerHour * integral
            sharedMinutes += minutes
        }
        concentration = n1
    }

    /** 추정 감염 위험(0~1) */
    fun risk(): Double = 1.0 - exp(-dose)

    /** 퍼센트(소수 첫째 자리) */
    fun riskPercent(): Double = Math.round(risk() * 1000.0) / 10.0

    companion object {
        const val INFECTORS = 1
        /** 공기 교환이 0 에 가까울 때 나누기를 막는 값(회/시간) */
        const val MIN_ACH = 1e-3
    }
}

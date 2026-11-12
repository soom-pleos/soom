package ai.kkaeugi.engine

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 숨 모델: 탑승 인원과 환기 상태로 실내 CO2(ppm)를 추정한다. 순수 Kotlin, 센서 값이 아니라 전부 추정이다.
 *
 * 질량 수지: dC/dt = (N × g × 1e6 / V) − ach × (C − C_out)
 *   C ppm, N 탑승 인원, g = co2PerPersonLph(L/h), V = cabinVolumeL(L), ach 공기 교환(회/시간)
 *   ach = 내기순환이면 leakAch
 *         외기면 leakAch + freshAchMax × 풍량 비율
 *           풍량 0 또는 최대 단계 모름: 0.15(주행풍으로 들어온다고 본다)
 *           팬이 켜져 있으면 1단 25/60, 최대 단 1.0 을 선형으로 잇는다
 *         창문 열림 p% ≥ 5 면 + windowAch × min(p/15, 3)
 *
 * 전진은 해석해(지수)로 한다. dt 가 커도 안정이고, 큰 스텝 한 번과 작은 스텝 여러 번의 결과가 같다.
 *   C(t) = Css + (C0 − Css) × e^(−ach × t),  Css = C_out + 생성량 / ach
 *
 * 쓰는 법: 입력(occupants, recirculating, fan, window)을 바꾸기 전에 advance(now) 로 그 시각까지 먼저 전진시킨다.
 * demoFast 면 시간이 60배로 흐른다(실제 1초 = 모델 1분).
 */
class CabinAirModel(var config: WakeConfig) {

    /** 탑승 인원. 모르면 1. 1 미만은 1 로 본다(운전자) */
    var occupants: Int = 1
        set(value) { field = value.coerceIn(1, MAX_OCCUPANTS) }
    /** true = 내기순환(밀폐) */
    var recirculating: Boolean = false
    /** 공조 풍량 단계와 최대 단계. 최대 단계를 모르면(0) 풍량 0 으로 본다 */
    var fanLevel: Int = 0
    var fanMax: Int = 0
    /** 창문 열림(0~100) */
    var windowPercent: Int = 0
        set(value) { field = value.coerceIn(0, 100) }

    /**
     * demoFast 에서도 실제 속도(1배)로 흐르게 한다. 엔진이 제안을 읽고 답을 기다리는 동안과 제어 결과를 기다리는 동안 켠다
     * (사람과 차가 실제 시간으로 움직이는 구간이라 60배로 돌리면 추정치가 부풀려진다). 복제본에는 옮기지 않는다
     */
    var realTimeInDemo: Boolean = false

    private var c: Double = startPpm()
    private var lastMs: Long? = null

    /** 세션 시작, 또는 문을 열고 내렸다고 볼 때(쉼 모드 종료). C = 바깥 + 200 */
    fun reset(nowMs: Long) {
        c = startPpm()
        lastMs = nowMs
    }

    /** 값은 그대로 두고 기준 시각만 nowMs 로 옮긴다. 그 사이 시간은 흐르지 않은 것으로 본다(곧바로 다시 출발한 경우) */
    fun rebase(nowMs: Long) {
        lastMs = nowMs
    }

    /** nowMs 까지 전진. 첫 호출은 기준 시각만 잡는다. 시계가 뒤로 가면(음수 dt) 무시한다 */
    fun advance(nowMs: Long) {
        val last = lastMs
        if (last == null) { lastMs = nowMs; return }
        val dtMs = nowMs - last
        if (dtMs <= 0) return
        lastMs = nowMs
        step(dtMs)
    }

    /** 기준 시각과 무관하게 실제 시간 dtMs 만큼 전진한다(demoFast 반영). 복제본으로 앞일을 계산할 때 쓴다 */
    fun advanceBy(dtMs: Long) {
        if (dtMs > 0) step(dtMs)
    }

    /** 같은 설정, 같은 입력, 같은 값의 복제본. 복제본을 전진시켜도 원본은 그대로다 */
    fun copy(): CabinAirModel {
        val m = CabinAirModel(config)
        m.occupants = occupants
        m.recirculating = recirculating
        m.fanLevel = fanLevel
        m.fanMax = fanMax
        m.windowPercent = windowPercent
        m.c = c
        m.lastMs = lastMs
        return m
    }

    /**
     * 모델 시간으로 minutes 분만큼 전진한다. 데모 속도(60배)와 기준 시각을 거치지 않는다.
     * 그림자 계산과 여정 예보처럼 "모델 안의 몇 분 뒤"를 볼 때 쓴다(v0.7.0)
     */
    fun advanceModelMinutes(minutes: Double) {
        if (minutes > 0) stepHours(minutes / 60.0)
    }

    /** 같은 설정과 입력에 값만 ppm 인 복제본. 환기 설계(VentPlanner)가 "한도에 막 닿은 차"를 만들 때 쓴다 */
    fun copyAt(ppm: Double): CabinAirModel = copy().also { it.c = ppm }

    private fun step(dtMs: Long) = stepHours(dtMs * timeScale() / MS_PER_HOUR)

    private fun stepHours(hours: Double) {
        val a = ach()
        c = if (a < EPS) c + generationPpmPerHour() * hours
        else {
            val css = steadyStatePpmExact()
            css + (c - css) * exp(-a * hours)
        }
    }

    /** 현재 추정 ppm(반올림) */
    fun ppm(): Int = c.roundToInt()

    /** 현재 추정 ppm(소수). 임계 비교용 */
    fun ppmExact(): Double = c

    /** 현재 조건의 공기 교환(회/시간) */
    fun ach(): Double {
        var a = config.leakAch.coerceAtLeast(0.0)
        if (!recirculating) a += config.freshAchMax * fanFraction()
        if (windowPercent >= WINDOW_OPEN_MIN_PERCENT) {
            a += config.windowAch * (windowPercent / 15.0).coerceAtMost(3.0)
        }
        return a
    }

    /** 현재 조건이 끝없이 이어질 때 수렴하는 ppm. 공기 교환이 0 이면 Int.MAX_VALUE */
    fun steadyStatePpm(): Int {
        val v = steadyStatePpmExact()
        return if (v >= Int.MAX_VALUE) Int.MAX_VALUE else v.roundToInt()
    }

    /**
     * 현재 조건이 이어질 때 limit 에 닿기까지 남은 분(모델 시간, 올림).
     * 이미 넘었으면 0, 정상상태 값이 한도에 못 미치면 null
     */
    fun minutesToLimit(limit: Int): Int? {
        val m = minutesUntilAbove(limit.toDouble()) ?: return null
        return ceil(m - 1e-9).toInt().coerceAtLeast(0)
    }

    /**
     * 현재 조건이 이어질 때 limit 에 닿기까지 남은 분(모델 시간, 소수).
     * 이미 넘었으면 0.0, 정상상태 값이 한도에 못 미치면 null
     */
    fun minutesUntilAbove(limit: Double): Double? = hoursUntilAbove(limit)?.let { it * 60.0 }

    /**
     * 현재 조건이 이어질 때 target 까지 내려가는 데 걸리는 분(모델 시간, 소수).
     * 이미 그 아래면 0.0, 지금 조건의 정상상태 값이 target 보다 높으면(끝내 못 내려가면) null
     */
    fun minutesUntilBelow(target: Double): Double? {
        if (c <= target) return 0.0
        val a = ach()
        if (a < EPS) return null
        val css = steadyStatePpmExact()
        if (css >= target) return null
        return ln((c - css) / (target - css)) / a * 60.0
    }

    private fun hoursUntilAbove(limit: Double): Double? {
        if (c >= limit) return 0.0
        val gen = generationPpmPerHour()
        val a = ach()
        if (a < EPS) return if (gen > 0) (limit - c) / gen else null
        val css = steadyStatePpmExact()
        if (css <= limit) return null
        return ln((css - c) / (css - limit)) / a
    }

    /** 현재 조건이 끝없이 이어질 때 수렴하는 ppm(소수). 공기 교환이 0 이고 사람이 있으면 Double.MAX_VALUE */
    fun steadyStatePpmExact(): Double {
        val a = ach()
        val gen = generationPpmPerHour()
        if (a < EPS) return if (gen > 0) Double.MAX_VALUE else c
        return config.outdoorPpm + gen / a
    }

    /** 사람이 내쉬는 CO2 로 오르는 속도(ppm/시간) */
    private fun generationPpmPerHour(): Double =
        occupants * config.co2PerPersonLph * 1e6 / config.cabinVolumeL.coerceAtLeast(1)

    /** 풍량 비율. 풍량 0 이거나 최대 단계를 모르면 FAN_OFF_FRACTION(주행풍).
     *  팬이 켜져 있으면 1단이 FAN_MIN_FRACTION(약 25회/시간), 최대 단이 1.0 이 되게 선형으로 잇는다.
     *  외기 최저 풍량 실측이 약 29~58회/시간이라 0부터 비례시키면 외기인데도 한도를 넘는다(docs/EVIDENCE.md F절). */
    private fun fanFraction(): Double {
        if (fanMax <= 0 || fanLevel <= 0) return FAN_OFF_FRACTION
        if (fanMax == 1) return 1.0
        val step = (fanLevel - 1).coerceIn(0, fanMax - 1).toDouble() / (fanMax - 1)
        return FAN_MIN_FRACTION + (1.0 - FAN_MIN_FRACTION) * step
    }

    private fun startPpm(): Double = config.outdoorPpm + START_OFFSET_PPM

    private fun timeScale(): Double = if (config.demoFast && !realTimeInDemo) DEMO_TIME_SCALE else 1.0

    companion object {
        /** 세션 시작 시 바깥보다 이만큼 높게 잡는다(타기 전부터 조금 차 있음) */
        const val START_OFFSET_PPM = 200.0
        /** 풍량 0 일 때 가정하는 외기 유입 비율 */
        const val FAN_OFF_FRACTION = 0.15
        /** 팬 1단일 때의 외기 유입 비율(freshAchMax 60 기준 약 25회/시간) */
        const val FAN_MIN_FRACTION = 25.0 / 60.0
        const val WINDOW_OPEN_MIN_PERCENT = 5
        const val DEMO_TIME_SCALE = 60.0
        const val MAX_OCCUPANTS = 9
        private const val MS_PER_HOUR = 3_600_000.0
        private const val EPS = 1e-9
    }
}

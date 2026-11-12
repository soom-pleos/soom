package ai.kkaeugi.engine

import kotlin.math.exp

/**
 * 실내 초미세먼지(PM2.5) 추정. 순수 Kotlin. 센서 값이 아니라 전부 추정이다.
 *
 * 1차 완화 모델: dP/dt = k × (P_out × IO(mode) − P)
 *   P 실내 PM2.5(µg/m³), P_out 바깥 PM2.5, IO 는 그 모드에서 오래 두면 닿는 실내/실외 비, k 는 그 값으로 다가가는 속도(회/시간)
 *   내기순환: IO = pmIoRecirc(0.15), k = pmRecircAch(20). 실내 공기가 필터를 거쳐 돈다고 가정한 고정값
 *   외기:     IO = pmIoFresh(0.6),   k = 숨 모델의 공기 교환(CabinAirModel.ach)
 *   창문 열림: IO = pmIoWindow(1.0),  k = 숨 모델의 공기 교환(창문 몫 포함)
 *
 * 기본값이 문헌 범위 안이라는 것까지만 말할 수 있다(docs/EVIDENCE.md I 절):
 *   창문 닫은 승용차의 실내/실외 비는 내기 0~0.4, 외기 0.6~1.0. 최근 실주행 연구의 내기 값은 0.10~0.13.
 *   외기 모드 값은 필터가 새것이면 0.20, 오래되면 0.60 으로 크게 달랐다. 그래서 0.6 은 "필터가 오래된 차" 쪽으로 잡은 값이다.
 *   이 차의 필터 상태, 차종, 속도는 모른다. 그래서 화면에는 늘 "추정" 을 붙인다.
 *
 * 전진은 해석해(지수)로 한다. 노출량(µg/m³·분)도 같은 식을 적분해서 쌓는다:
 *   ∫P dt = P_ss × t + (P0 − P_ss) × (1 − e^(−k t)) / k
 *
 * 바깥 값(outdoor)이 null 이면 멈춘다(값도 노출량도 그대로). 처음 값이 들어오는 순간 실내는 "외기 모드로 달리던 차"라고 보고
 * P = P_out × pmIoFresh 에서 시작한다(타기 전 상태는 모른다)
 */
class CabinPmModel(var config: WakeConfig) {

    /** 바깥 PM2.5(µg/m³). null 이면 모름: 모델이 멈춘다 */
    var outdoor: Double? = null
        set(value) {
            field = value?.coerceAtLeast(0.0)
            if (field != null && p == null) p = field!! * config.pmIoFresh
        }
    /** true = 내기순환 */
    var recirculating: Boolean = false
    /** 창문이 열려 있는가(5% 이상) */
    var windowOpen: Boolean = false
    /** 숨 모델이 계산한 지금의 공기 교환(회/시간). 외기와 창문 열림일 때 k 로 쓴다 */
    var airAch: Double = 0.0

    private var p: Double? = null
    /** 쌓인 노출량(µg/m³·분)과 그 값을 쌓은 시간(분, 모델 시간) */
    var exposure: Double = 0.0
        private set
    var minutes: Double = 0.0
        private set

    /** 지금 추정 실내 PM2.5(µg/m³). 바깥 값을 한 번도 못 받았으면 null */
    fun pm(): Double? = p

    /** 노출 평균(µg/m³). 쌓은 시간이 없으면 null */
    fun average(): Double? = if (minutes > 0) exposure / minutes else null

    /** 지금 모드의 실내/실외 비 */
    fun io(): Double = when {
        windowOpen -> config.pmIoWindow
        recirculating -> config.pmIoRecirc
        else -> config.pmIoFresh
    }.coerceIn(0.0, 1.0)

    /** 지금 모드에서 평형으로 가는 속도(회/시간) */
    fun rate(): Double = when {
        windowOpen -> airAch
        recirculating -> config.pmRecircAch
        else -> airAch
    }.coerceAtLeast(0.0)

    /** 지금 조건이 이어질 때 닿는 값(µg/m³). 바깥 값을 모르면 null */
    fun steadyState(): Double? = outdoor?.let { it * io() }

    /** 모델 시간으로 minutes 분 전진하고 노출량을 쌓는다. 바깥 값을 모르면 아무것도 하지 않는다 */
    fun advanceModelMinutes(minutes: Double) {
        if (minutes <= 0) return
        val out = outdoor ?: return
        val p0 = p ?: (out * config.pmIoFresh)
        val pss = out * io()
        val k = rate()
        val hours = minutes / 60.0
        if (k < EPS) {
            // 공기가 전혀 바뀌지 않는다: 값은 그대로, 노출만 쌓인다
            exposure += p0 * minutes
        } else {
            val decay = exp(-k * hours)
            p = pss + (p0 - pss) * decay
            // 적분은 시간(h) 단위로 나오므로 분으로 바꾼다
            exposure += (pss * hours + (p0 - pss) * (1 - decay) / k) * 60.0
        }
        if (p == null) p = p0
        this.minutes += minutes
    }

    /** 세션 시작, 또는 문을 열고 내렸다고 볼 때. 값과 노출량을 비운다. 바깥 값은 그대로 두고 처음 값에서 다시 시작한다 */
    fun reset() {
        p = outdoor?.let { it * config.pmIoFresh }
        exposure = 0.0
        minutes = 0.0
    }

    /** 문을 열고 내렸다 탔다(쉼 모드 종료). 노출량은 이어 쌓고 실내 값만 처음 값으로 */
    fun reopen() {
        p = outdoor?.let { it * config.pmIoFresh }
    }

    /** 같은 설정, 같은 입력, 같은 값의 복제본 */
    fun copy(): CabinPmModel {
        val m = CabinPmModel(config)
        m.p = p
        m.outdoor = outdoor
        m.recirculating = recirculating
        m.windowOpen = windowOpen
        m.airAch = airAch
        m.exposure = exposure
        m.minutes = minutes
        return m
    }

    companion object {
        private const val EPS = 1e-9
    }
}

package ai.kkaeugi.engine

import kotlin.math.roundToInt

/**
 * 세 갈래 그림자 계산(v0.7.0). 실제 곡선 옆에서 같은 입력(인원, 풍량, 창문, 바깥 미세먼지)으로 두 가지 가상 주행을 함께 굴린다.
 *   내기만(recircOnly): 처음부터 끝까지 내기순환
 *   외기만(freshOnly):  처음부터 끝까지 외기
 * 갈래마다 CO2 최고값, 한도 위에 머문 시간, 실내 PM2.5 노출량을 쌓는다. 전부 추정이고, 실제로 그렇게 달렸다는 뜻이 아니다.
 * 도착 요약(ArrivalSummary.threeWay)에서 "이번 주행"과 나란히 놓아 맞교환을 보여 준다:
 * 내기만은 CO2 가 높고 미세먼지가 낮다. 외기만은 그 반대다.
 *
 * 실제 주행과 같은 시간만큼만 전진한다(쉬는 동안은 멈춘다). 창문은 실제와 같이 따라간다(창문을 열면 세 갈래 모두 열린 것으로 본다).
 */
class Counterfactual(config: WakeConfig) {

    /** 한 갈래 */
    class Way internal constructor(config: WakeConfig, private val forceRecirc: Boolean) {
        var air = CabinAirModel(config).also { it.recirculating = forceRecirc }
            internal set
        val pm = CabinPmModel(config).also { it.recirculating = forceRecirc }
        /** 기침 자리 감염 위험(v0.8.0). 같은 공기 교환으로 굴린다 */
        val bio = InfectionModel(config)
        internal var coughActive = false
        var peakPpm = 0.0
            internal set
        /** 기본 한도 위에 머문 시간(분, 모델 시간) */
        var overLimitMinutes = 0.0
            internal set

        internal fun restart(from: CabinAirModel, clearTotals: Boolean) {
            air = from.copy().also { it.recirculating = forceRecirc }
            if (clearTotals) {
                peakPpm = air.ppmExact()
                overLimitMinutes = 0.0
                pm.reset()
                bio.reset()
            } else { pm.reopen(); bio.reopen() }
        }

        internal fun sync(occupants: Int, fanLevel: Int, fanMax: Int, windowPercent: Int, outdoorPm: Double?) {
            air.occupants = occupants
            air.fanLevel = fanLevel
            air.fanMax = fanMax
            air.windowPercent = windowPercent
            air.recirculating = forceRecirc
            pm.outdoor = outdoorPm
            pm.recirculating = forceRecirc
            pm.windowOpen = windowPercent >= CabinAirModel.WINDOW_OPEN_MIN_PERCENT
            pm.airAch = air.ach()
        }

        internal fun advance(modelMinutes: Double, limit: Int) {
            val ach = air.ach()
            air.advanceModelMinutes(modelMinutes)
            pm.advanceModelMinutes(modelMinutes)
            bio.advance(modelMinutes, ach, coughActive)
            val c = air.ppmExact()
            if (c > peakPpm) peakPpm = c
            if (c >= limit) overLimitMinutes += modelMinutes
        }

        fun stats(): WayStats = WayStats(peakPpm.roundToInt(), overLimitMinutes.roundToInt(), pm.average()?.let { pm.exposure }, pm.average())
    }

    var config: WakeConfig = config
        set(value) {
            field = value
            for (w in ways) { w.air.config = value; w.pm.config = value; w.bio.config = value }
        }

    val recircOnly = Way(config, forceRecirc = true)
    val freshOnly = Way(config, forceRecirc = false)
    private val ways get() = listOf(recircOnly, freshOnly)

    /** 주행 시작: 두 갈래를 실제 모델의 지금 값에서 시작하고 누적을 비운다 */
    fun start(from: CabinAirModel) {
        for (w in ways) { w.air.config = config; w.restart(from, clearTotals = true) }
    }

    /** 쉼이 끝났다(문을 열고 내렸다고 본다): 값만 실제 모델의 처음 값으로, 누적은 이어 간다 */
    fun reopen(from: CabinAirModel) {
        for (w in ways) w.restart(from, clearTotals = false)
    }

    /** 실제와 같은 입력을 옮긴다. 내외기만 갈래마다 고정이다 */
    fun sync(occupants: Int, fanLevel: Int, fanMax: Int, windowPercent: Int, outdoorPm: Double?) {
        for (w in ways) w.sync(occupants, fanLevel, fanMax, windowPercent, outdoorPm)
    }

    /** 기침하는 자리와 다른 사람이 같이 타 있는가(v0.8.0) */
    fun setCough(active: Boolean) { for (w in ways) w.coughActive = active }

    /** 모델 시간으로 minutes 분 전진 */
    fun advance(modelMinutes: Double) {
        if (modelMinutes <= 0) return
        for (w in ways) w.advance(modelMinutes, config.co2LimitPpm)
    }
}

/**
 * 지나온 곡선(홈 화면 "숨 설계" 그래프용). 모델 시간 stepMin 분마다 한 점씩, 최근 capacity 점만 들고 있다.
 * 기본은 30초마다 60점(최근 30분). 데모 속도에서는 1초가 모델 1분이라 1초에 한 점씩 쌓인다(최근 60초, 모델 60분)
 */
class BreathTrace(private val capacity: Int = 60, private val stepMin: Double = 0.5) {
    private val points = ArrayDeque<TracePoint>()
    private var lastMinute = Double.NEGATIVE_INFINITY

    fun clear() { points.clear(); lastMinute = Double.NEGATIVE_INFINITY }

    /** minute: 주행을 시작하고 흐른 분(모델 시간). 직전 점에서 stepMin 이 지났을 때만 담는다 */
    fun add(minute: Double, ppm: Int, recircOnlyPpm: Int) {
        if (minute - lastMinute < stepMin - 1e-9) return
        lastMinute = minute
        points.addLast(TracePoint(minute, ppm, recircOnlyPpm))
        while (points.size > capacity) points.removeFirst()
    }

    fun snapshot(): List<TracePoint> = points.toList()
}

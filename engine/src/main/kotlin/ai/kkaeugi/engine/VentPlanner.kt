package ai.kkaeugi.engine

import kotlin.math.roundToInt

/**
 * 환기 설계기(v0.7.0 "숨 설계"). 순수 Kotlin, 상태 없음. 전부 추정이다.
 *
 * 맞교환: 내기만 쓰면 CO2 가 쌓이고, 외기만 쓰면 미세먼지가 들어온다(Jung 외 2017 이 실차로 보인 것. docs/EVIDENCE.md I 절).
 * 숨은 둘을 섞지 못한다(SDK 로는 내기와 외기를 켜고 끄는 것만 된다). 그래서 시간을 나눈다:
 * 내기로 달리다가 CO2 가 한도에 닿으면 잠깐 외기로 바꾸고 되돌린다. 같은 논문이 선행 연구로 소개한, 완전 내기와 완전 외기를
 * 몇 분 간격으로 번갈아 쓰는 제어(Mathur 2008, 2009)와 같은 계열의 시간 분할이다.
 *
 * 질량 수지에서 나오는 성질 하나를 쓴다. 바깥 공기를 같은 양 들일 때 빠져나가는 CO2 는 (실내 농도 − 바깥 농도)에 비례한다.
 * 그래서 CO2 가 높을 때 바꾸는 것이 같은 미세먼지 값으로 CO2 를 가장 많이 뺀다. 바깥 미세먼지가 나쁜 날에는
 *  - 일찍 묻지 않고 한도에 닿을 때까지 기다린다(앞당기는 여유를 없앤다. 한도 자체를 넘기지는 않는다)
 *  - CO2 가 목표(한도의 pmVentTargetFraction, 기본 60%)까지 내려오면 2분이 안 됐어도 되돌린다("필요한 만큼만")
 *  - 다만 3명 이상이 타면 짧게 바꾼 뒤 2~3분 만에 다시 한도에 닿는다(환기 뒤 5분은 다시 묻지 않으므로 그동안 한도 위에 머문다).
 *    4명 2시간을 굴려 보면 환기 17번에 한도 위 약 80분이 나온다. 그래서 그 조건에서는 시간 분할을 그만두고
 *    약한 바람으로 바깥 공기를 계속 들이는 쪽을 한 번 묻는다(v0.7.4, KEEP_LOW). 미세먼지 노출은 "내내 외기"와 같아진다(모델에서는 풍량과 무관)
 * 가장 좋은 답을 찾는 계산이 아니다. 둘 사이에서 덜 손해 보는 때와 길이를 고르는 규칙이다.
 */
object VentPlanner {

    /** 등급 경계(PM2.5 µg/m³, 반올림한 정수 기준). 한국 예보 등급: 좋음 0~15, 보통 16~35, 나쁨 36~75, 매우 나쁨 76 이상 */
    const val GOOD_MAX = 15
    const val MODERATE_MAX = 35
    const val BAD_MAX = 75

    /** 데모 속도에서 "기본 환기 길이"가 뜻하는 모델 시간(분). 데모의 20초는 실제의 2분을 줄여 보여 주는 것이다 */
    const val DEMO_REF_VENT_MIN = 2.0
    /** 환기 길이를 고르는 단위: 기본 길이의 4분의 1(2분이면 30초). 가장 짧은 길이는 기본 길이의 절반(2분이면 1분) */
    const val DURATION_STEPS = 4
    const val MIN_DURATION_FRACTION = 0.5

    /**
     * 약한 바람으로 바깥 공기를 계속 들이기(v0.7.4). 미세먼지가 나쁨 이상이어도 이 인원 이상이고 금방 다시 차면 "짧게 여러 번"을 그만둔다.
     * 맞출 풍량은 외기로 계속 두었을 때 CO2 가 한도의 KEEP_LOW_STEADY_FRACTION 아래에 머무는 가장 약한 단이다(둘 다 자체 설정값)
     */
    const val KEEP_LOW_MIN_OCCUPANTS = 3
    const val KEEP_LOW_STEADY_FRACTION = 0.75

    /** 예보를 계산하는 걸음(분, 모델 시간)과 가장 멀리 내다보는 길이(분) */
    const val FORECAST_STEP_MIN = 1.0 / 6.0
    const val FORECAST_MAX_MIN = 360
    /** 목적지를 모를 때 내다보는 창(분) */
    const val FORECAST_WINDOW_MIN = 60
    /** 예보 곡선으로 돌려주는 점의 수(1분 간격) */
    const val FORECAST_POINTS = 30

    fun grade(pm25: Double): PmGrade {
        val v = pm25.coerceAtLeast(0.0).roundToInt()
        return when {
            v <= GOOD_MAX -> PmGrade.GOOD
            v <= MODERATE_MAX -> PmGrade.MODERATE
            v <= BAD_MAX -> PmGrade.BAD
            else -> PmGrade.VERY_BAD
        }
    }

    /** 나쁨 이상인가. 이때만 "늦추고 짧게" 규칙을 쓴다 */
    fun shortMode(grade: PmGrade?): Boolean = grade == PmGrade.BAD || grade == PmGrade.VERY_BAD

    /** "필요한 만큼만" 환기의 CO2 목표(ppm). 바깥 공기보다 낮게 잡히지 않게 한다 */
    fun targetPpm(config: WakeConfig, limit: Double): Double =
        maxOf(limit * config.pmVentTargetFraction, config.outdoorPpm + CabinAirModel.START_OFFSET_PPM)

    /** 모델 1분이 실제로 몇 초인가. 데모 속도면 1초, 아니면 60초 */
    private fun secondsPerModelMinute(config: WakeConfig): Double =
        if (config.demoFast) 60.0 / CabinAirModel.DEMO_TIME_SCALE else 60.0

    /**
     * 필요한 환기 길이(실제 초). 지금 값에서 외기(풍량 +1)로 목표까지 내려가는 시간을 기본 길이의 4분의 1 단위로 반올림한다.
     * 가장 짧게는 기본 길이의 절반, 가장 길게는 기본 길이(config.ventilateSeconds)다. 기본값이면 1분, 1분 30초, 2분 가운데 하나.
     * 끝내 목표까지 못 내려가는 조건이면 기본 길이를 다 쓴다
     */
    fun shortVentSeconds(air: CabinAirModel, config: WakeConfig, limit: Double): Int {
        val full = config.ventilateSeconds.coerceAtLeast(1)
        val need = ventingCopy(air).minutesUntilBelow(targetPpm(config, limit)) ?: return full
        val refMin = if (config.demoFast) DEMO_REF_VENT_MIN else full / 60.0
        val steps = (need / refMin * DURATION_STEPS).roundToInt()
        val fraction = (steps.toDouble() / DURATION_STEPS).coerceIn(MIN_DURATION_FRACTION, 1.0)
        return (full * fraction).roundToInt().coerceAtLeast(1)
    }

    /** 환기 중일 때의 복제본: 외기, 풍량 +1(엔진의 Ventilate 기본 동작과 같다) */
    fun ventingCopy(air: CabinAirModel): CabinAirModel = air.copy().also {
        it.recirculating = false
        it.fanLevel = if (it.fanMax > 0) minOf(it.fanLevel + 1, it.fanMax) else it.fanLevel + 1
    }

    /**
     * 바깥 공기로 계속 둘 때 맞출 가장 약한 풍량 단: 창문을 닫고 외기로 두었을 때 수렴하는 CO2 가 limit × KEEP_LOW_STEADY_FRACTION 이하인 첫 단.
     * 어느 단으로도 안 되면 최대 단. 최대 단을 모르면(0) null
     */
    fun lowFanLevel(air: CabinAirModel, config: WakeConfig, limit: Double): Int? {
        if (air.fanMax <= 0) return null
        val m = air.copy().also { it.recirculating = false; it.windowPercent = 0 }
        for (level in 1..air.fanMax) {
            m.fanLevel = level
            if (m.steadyStatePpmExact() <= limit * KEEP_LOW_STEADY_FRACTION) return level
        }
        return air.fanMax
    }

    /** 실제 초 → 모델 분 */
    fun modelMinutes(seconds: Int, config: WakeConfig): Double = seconds / secondsPerModelMinute(config)

    /**
     * 듀티 사이클: "이 인원이면 N분마다 M초". 한도에 닿은 순간에서 시작해 한 바퀴를 돌려 본다.
     *   한도 → 외기로 durationSec → 내기로 다시 한도까지. 환기 직후 cooldownMin 동안은 다시 묻지 않는 규칙도 넣는다.
     * air 는 인원, 풍량, 창문만 본다(값과 내외기 상태는 쓰지 않는다). 창문은 닫힌 것으로 본다.
     * keepThresholdMin: 환기 뒤 이 시간(분) 안에 다시 한도에 닿으면 바깥 공기로 두는 쪽을 권한다(나쁨 이상에서는 권하지 않는다)
     */
    fun plan(
        air: CabinAirModel, config: WakeConfig, limit: Double, grade: PmGrade?,
        keepThresholdMin: Double, cooldownMin: Double,
    ): VentPlan {
        val short = shortMode(grade)
        val full = config.ventilateSeconds.coerceAtLeast(1)
        // 한도에 막 닿은 내기순환 상태
        val atLimit = air.copy().also { it.recirculating = true; it.windowPercent = 0 }
        val neverReaches = atLimit.steadyStatePpmExact() <= limit
        if (neverReaches) return VentPlan(null, full, REASON_NEVER, grade)
        val start = atLimit.copyAt(limit)
        val target = targetPpm(config, limit)
        val seconds = if (short) shortVentSeconds(start, config, limit) else full
        // 환기 구간. 나쁨 이상이고 실제 속도면 목표에 닿는 순간 되돌린다
        val vent = ventingCopy(start)
        var ventMin = modelMinutes(seconds, config)
        if (short && !config.demoFast) vent.minutesUntilBelow(target)?.let { ventMin = minOf(ventMin, it) }
        vent.advanceModelMinutes(ventMin)
        // 내기로 돌아와 다시 한도까지
        val back = vent.copy().also { it.recirculating = true; it.fanLevel = air.fanLevel }
        val refill = back.minutesUntilAbove(limit) ?: return VentPlan(null, seconds, REASON_NEVER, grade)
        // 나쁨 이상이어도 3명 이상이고 다시 닿기까지가 환기 뒤 쉬는 시간보다 짧으면 약한 바람으로 바깥 공기를 계속 들이는 쪽을 권한다
        val keepLow = short && air.occupants >= KEEP_LOW_MIN_OCCUPANTS && refill < cooldownMin
        val keep = keepLow || (!short && refill < keepThresholdMin)
        val interval = ventMin + maxOf(refill, cooldownMin)
        val reason = when {
            keepLow -> REASON_KEEP_LOW
            keep -> REASON_KEEP
            grade == PmGrade.VERY_BAD -> REASON_VERY_BAD
            grade == PmGrade.BAD -> REASON_BAD
            grade == PmGrade.GOOD -> REASON_GOOD
            else -> REASON_DEFAULT
        }
        return VentPlan(
            interval, seconds, reason, grade, keepFresh = keep, targetPpm = if (short) target.roundToInt() else null,
            keepFanLevel = if (keepLow) lowFanLevel(air, config, limit) else null,
        )
    }

    /**
     * 여정 예보. 지금 상태(air)에서 horizonMin 분 앞까지, 제안에 모두 동의한다고 보고 굴려 본다.
     * 규칙은 엔진을 어림한 것이다: 내기순환에서 한도에 닿으면 plan.durationSec 만큼 외기(풍량 +1)로 바꾸고 되돌린다.
     * 환기 직후 cooldownMin 은 다시 바꾸지 않는다. plan.keepFresh 면 첫 환기 뒤 바깥 공기로 둔다.
     * toArrival 이면 도착 arrivingSoonMin 분 전부터는 새로 바꾸지 않는다(엔진이 묻지 않는 구간).
     * 지금 외기로 달리는 중이면(recirculating=false) 환기는 0번이고 곡선만 낸다.
     * ventRemainingMin: 엔진이 이미 바꿔 둔 중이면 남은 시간(분, 모델 시간). 아니면 0. 그때 recirculating 은 "되돌리면 내기순환인가",
     * baseFanLevel 은 바꾸기 전 풍량이다
     */
    fun forecast(
        air: CabinAirModel, config: WakeConfig, limit: Double, plan: VentPlan,
        horizonMin: Int, toArrival: Boolean, recirculating: Boolean,
        cooldownMin: Double, arrivingSoonMin: Int, ventRemainingMin: Double = 0.0, baseFanLevel: Int = air.fanLevel,
    ): Forecast {
        val horizon = horizonMin.coerceIn(0, FORECAST_MAX_MIN)
        val m = air.copy()
        val baseFan = baseFanLevel
        val ventFan = if (m.fanMax > 0) minOf(baseFan + 1, m.fanMax) else baseFan + 1
        val short = shortMode(plan.pmGrade)
        val target = plan.targetPpm?.toDouble()
        val ventLen = modelMinutes(plan.durationSec, config)
        var venting = ventRemainingMin > 0
        var ventLeft = ventRemainingMin
        var kept = false
        var sinceVentEnd = Double.MAX_VALUE
        var count = 0
        var first: Int? = null
        val points = mutableListOf(ForecastPoint(0.0, m.ppm(), venting))
        m.recirculating = recirculating && !venting
        if (venting) m.fanLevel = ventFan
        var t = 0.0
        var nextPoint = 1.0
        while (t < horizon - 1e-9) {
            val canStart = recirculating && !venting && !kept && m.ppmExact() >= limit && sinceVentEnd >= cooldownMin &&
                (!toArrival || horizon - t > arrivingSoonMin)
            if (canStart) {
                venting = true
                ventLeft = ventLen
                count++
                if (first == null) first = t.roundToInt()
                m.recirculating = false
                m.fanLevel = ventFan
            }
            m.advanceModelMinutes(FORECAST_STEP_MIN)
            t += FORECAST_STEP_MIN
            if (venting) {
                ventLeft -= FORECAST_STEP_MIN
                val reached = short && !config.demoFast && target != null && m.ppmExact() <= target
                if (ventLeft <= 1e-9 || reached) {
                    venting = false
                    sinceVentEnd = 0.0
                    // 바깥 공기로 두는 설계면 되돌리지 않는다. 약한 바람 설계면 풍량을 그 단으로 맞춘다
                    if (plan.keepFresh) { kept = true; plan.keepFanLevel?.let { m.fanLevel = it } } else { m.recirculating = true; m.fanLevel = baseFan }
                }
            } else if (sinceVentEnd != Double.MAX_VALUE) sinceVentEnd += FORECAST_STEP_MIN
            if (t >= nextPoint - 1e-9 && points.size <= FORECAST_POINTS) {
                points += ForecastPoint(nextPoint, m.ppm(), venting || kept)
                nextPoint += 1.0
            }
        }
        return Forecast(horizon, toArrival, count, first, points, keepFresh = kept)
    }

    /** VentPlan.reason 문구. 화면에 그대로 쓸 수 있는 짧은 말 */
    const val REASON_DEFAULT = "한도에 닿으면 잠깐 바꿔요"
    const val REASON_GOOD = "밖 공기가 좋아 넉넉하게 바꿔요"
    const val REASON_BAD = "밖 미세먼지가 나빠 필요한 만큼만 바꿔요"
    const val REASON_VERY_BAD = "밖 미세먼지가 매우 나빠 한도까지 기다렸다가 짧게 바꿔요"
    const val REASON_KEEP = "금방 다시 차서 바깥 공기로 두는 쪽이 나아요"
    /** 미세먼지가 나쁨 이상인데 3명 이상이라 짧게 바꿔서는 한도를 지킬 수 없을 때(v0.7.4) */
    const val REASON_KEEP_LOW = "금방 다시 차서 약한 바람으로 바깥 공기를 들이는 쪽이 나아요"
    /** 바깥 등급은 나쁨이 아니지만 "미세먼지 먼저" 자리 설정 때문에 짧게 바꿀 때(v0.7.3) */
    const val REASON_PM_FIRST = "미세먼지를 먼저 보는 자리가 있어 필요한 만큼만 바꿔요"
    const val REASON_NEVER = "지금은 한도에 닿지 않아요"
}

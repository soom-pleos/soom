package ai.kkaeugi.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.7.0 "숨 설계"의 모델 단독 테스트: 실내 미세먼지 추정(CabinPmModel), 환기 설계기(VentPlanner), 졸리기 쉬운 시간대 가중(AlertnessWeight),
 * 세 갈래 그림자 계산(Counterfactual), 지나온 곡선(BreathTrace), 그리고 숨 모델에 더한 함수들.
 * 엔진과 이어 붙인 동작은 BreathEngineTest 가 본다.
 */
class BreathModelTest {

    private val cfg = WakeConfig()
    private val demo = WakeConfig(ventilateSeconds = 20, demoFast = true)

    private fun near(expected: Double, actual: Double, tol: Double = 1e-6, msg: String? = null) =
        assertTrue(abs(expected - actual) <= tol, "${msg ?: ""} 기대 $expected, 실제 $actual")

    private fun cabin(occupants: Int = 1, recirc: Boolean = true, fan: Int = 3, c: WakeConfig = cfg) =
        CabinAirModel(c).apply { this.occupants = occupants; recirculating = recirc; fanLevel = fan; fanMax = 8; reset(0L) }

    private fun pm(out: Double? = 40.0, recirc: Boolean = true, window: Boolean = false, ach: Double = 42.0, c: WakeConfig = cfg) =
        CabinPmModel(c).apply { recirculating = recirc; windowOpen = window; airAch = ach; outdoor = out }

    // ---------- 숨 모델에 더한 함수 ----------

    @Test
    fun `모델 시간 전진은 실제 속도의 전진과 같고 데모 배속을 타지 않는다`() {
        val a = cabin(4); val b = cabin(4)
        a.advance(10 * 60_000L)
        b.advanceModelMinutes(10.0)
        near(a.ppmExact(), b.ppmExact(), 1e-6)
        val fast = cabin(4, c = demo)
        fast.advanceModelMinutes(10.0)
        near(a.ppmExact(), fast.ppmExact(), 1e-6, "데모 설정이어도 모델 10분은 10분이다")
        val before = fast.ppmExact()
        fast.advanceModelMinutes(0.0); fast.advanceModelMinutes(-3.0)
        assertEquals(before, fast.ppmExact())
    }

    @Test
    fun `목표까지 내려가는 시간은 실제로 굴려 본 값과 같다`() {
        val m = cabin(1).copyAt(2000.0)
        assertNull(m.minutesUntilBelow(1200.0), "내기순환으로는 내려가지 않는다")
        m.recirculating = false
        val need = assertNotNull(m.minutesUntilBelow(1200.0))
        m.advanceModelMinutes(need)
        near(1200.0, m.ppmExact(), 1e-6)
        assertEquals(0.0, m.minutesUntilBelow(1500.0), "이미 아래면 0")
        // 4명이 외기 3단이면 정상상태가 1,000ppm 을 넘는다. 그 아래로는 못 내려간다
        val four = cabin(4, recirc = false).copyAt(2000.0)
        assertTrue(four.steadyStatePpmExact() > 1000.0)
        assertNull(four.minutesUntilBelow(1000.0))
    }

    @Test
    fun `값만 바꾼 복제본은 입력이 같고 원본을 건드리지 않는다`() {
        val m = cabin(3, recirc = false, fan = 5)
        val c = m.copyAt(1800.0)
        assertEquals(1800.0, c.ppmExact())
        assertEquals(3, c.occupants); assertFalse(c.recirculating); assertEquals(5, c.fanLevel)
        near(cfg.outdoorPpm + CabinAirModel.START_OFFSET_PPM, m.ppmExact())
    }

    // ---------- 실내 미세먼지 추정 ----------

    @Test
    fun `바깥 값이 없으면 멈춰 있다`() {
        val m = pm(out = null)
        m.advanceModelMinutes(30.0)
        assertNull(m.pm()); assertNull(m.steadyState()); assertNull(m.average())
        assertEquals(0.0, m.exposure); assertEquals(0.0, m.minutes)
    }

    @Test
    fun `기본값은 문헌 범위 안이다`() {
        // 창문 닫은 승용차의 실내 대 실외 비: 내기 0~0.4, 외기 0.6~1.0. 최근 실주행 내기 0.10~0.13, 외기는 새 필터 0.20, 오래된 필터 0.60
        assertTrue(cfg.pmIoRecirc in 0.0..0.4)
        assertTrue(cfg.pmIoRecirc >= 0.10, "실주행 값보다 낮게(좋게) 잡지 않는다")
        assertTrue(cfg.pmIoFresh in 0.6..1.0)
        assertEquals(0.6, cfg.pmIoFresh, "오래된 필터 쪽 값")
        assertEquals(1.0, cfg.pmIoWindow)
        assertTrue(cfg.pmIoRecirc < cfg.pmIoFresh && cfg.pmIoFresh <= cfg.pmIoWindow)
        assertEquals(60, cfg.pmStaleMinutes)
    }

    @Test
    fun `모드마다 닿는 값이 다르다 - 내기 0점15배, 외기 0점6배, 창문 1배`() {
        for ((m, io) in listOf(pm(recirc = true) to 0.15, pm(recirc = false) to 0.6, pm(recirc = true, window = true) to 1.0, pm(recirc = false, window = true) to 1.0)) {
            near(40.0 * io, m.steadyState()!!)
            m.advanceModelMinutes(600.0)
            near(40.0 * io, m.pm()!!, 1e-3)
        }
    }

    @Test
    fun `처음 값은 외기 모드 값이고 내기순환이면 3분에 e분의 1 만큼 남는다`() {
        val m = pm(out = 100.0, recirc = true)
        near(60.0, m.pm()!!)
        m.advanceModelMinutes(3.0)   // k 20회/시간 × 3분 = 1
        near(15.0 + (60.0 - 15.0) * exp(-1.0), m.pm()!!)
    }

    @Test
    fun `큰 걸음 한 번과 작은 걸음 여러 번의 값과 노출량이 같다`() {
        val big = pm(out = 80.0, recirc = true)
        val small = pm(out = 80.0, recirc = true)
        big.advanceModelMinutes(12.0)
        repeat(720) { small.advanceModelMinutes(1.0 / 60) }
        near(big.pm()!!, small.pm()!!, 1e-6)
        near(big.exposure, small.exposure, 1e-6)
        near(12.0, small.minutes, 1e-6)
    }

    @Test
    fun `노출량은 농도를 시간으로 적분한 값이다`() {
        // 평형에 있으면 농도 × 분
        val steady = pm(out = 50.0, recirc = false)
        steady.advanceModelMinutes(20.0)
        near(30.0 * 20.0, steady.exposure, 1e-6)
        near(30.0, steady.average()!!, 1e-9)
        // 내려가는 중이면 처음 값과 끝 값 사이
        val falling = pm(out = 50.0, recirc = true)
        falling.advanceModelMinutes(10.0)
        val avg = falling.average()!!
        assertTrue(avg < 30.0 && avg > falling.pm()!!, "평균 $avg")
        // 공기가 전혀 안 바뀌면 값은 그대로, 노출만 쌓인다
        val sealed = pm(out = 50.0, recirc = false, ach = 0.0)
        sealed.advanceModelMinutes(10.0)
        near(30.0, sealed.pm()!!); near(300.0, sealed.exposure)
    }

    @Test
    fun `바깥 값이 바뀌면 이어서 새 값으로 다가간다`() {
        val m = pm(out = 20.0, recirc = false)
        m.advanceModelMinutes(60.0)
        near(12.0, m.pm()!!, 1e-6)
        m.outdoor = 80.0
        near(12.0, m.pm()!!, 1e-6, "값은 튀지 않는다")
        m.advanceModelMinutes(60.0)
        near(48.0, m.pm()!!, 1e-6)
        // 값이 사라지면 멈춘다
        m.outdoor = null
        val exposure = m.exposure
        m.advanceModelMinutes(30.0)
        assertEquals(exposure, m.exposure)
    }

    @Test
    fun `설정으로 계수를 바꿀 수 있고 복제와 초기화가 된다`() {
        val newFilter = pm(recirc = false, c = cfg.copy(pmIoFresh = 0.2))
        near(8.0, newFilter.steadyState()!!)
        val m = pm(out = 40.0, recirc = true)
        m.advanceModelMinutes(5.0)
        val c = m.copy()
        c.advanceModelMinutes(5.0)
        assertTrue(c.exposure > m.exposure)
        near(5.0, m.minutes)
        m.reopen()
        near(24.0, m.pm()!!); assertTrue(m.exposure > 0.0, "쉬고 나와도 노출량은 이어 쌓는다")
        m.reset()
        near(24.0, m.pm()!!); assertEquals(0.0, m.exposure); assertEquals(0.0, m.minutes)
    }

    // ---------- 환기 설계기 ----------

    @Test
    fun `등급 경계 - 좋음 15, 보통 35, 나쁨 75, 그 위는 매우 나쁨`() {
        val expect = listOf(
            -3.0 to PmGrade.GOOD, 0.0 to PmGrade.GOOD, 15.0 to PmGrade.GOOD, 15.4 to PmGrade.GOOD,
            15.6 to PmGrade.MODERATE, 16.0 to PmGrade.MODERATE, 35.0 to PmGrade.MODERATE,
            36.0 to PmGrade.BAD, 41.0 to PmGrade.BAD, 75.0 to PmGrade.BAD,
            76.0 to PmGrade.VERY_BAD, 300.0 to PmGrade.VERY_BAD,
        )
        for ((v, g) in expect) assertEquals(g, VentPlanner.grade(v), "$v")
        assertEquals(listOf("좋음", "보통", "나쁨", "매우 나쁨"), PmGrade.entries.map { it.label })
        assertFalse(VentPlanner.shortMode(null)); assertFalse(VentPlanner.shortMode(PmGrade.GOOD)); assertFalse(VentPlanner.shortMode(PmGrade.MODERATE))
        assertTrue(VentPlanner.shortMode(PmGrade.BAD)); assertTrue(VentPlanner.shortMode(PmGrade.VERY_BAD))
    }

    @Test
    fun `질량 수지 - 같은 1분을 바꿔도 CO2 가 높을 때 더 많이 빠진다`() {
        fun dropInOneMinute(from: Double): Double {
            val m = VentPlanner.ventingCopy(cabin(1).copyAt(from))
            m.advanceModelMinutes(1.0)
            return from - m.ppmExact()
        }
        val high = dropInOneMinute(2000.0)
        val mid = dropInOneMinute(1600.0)
        val low = dropInOneMinute(1200.0)
        assertTrue(high > mid && mid > low, "2,000 에서 $high, 1,600 에서 $mid, 1,200 에서 $low")
        // 빠지는 양은 (실내 − 그 조건의 평형값)에 비례한다
        val css = VentPlanner.ventingCopy(cabin(1)).steadyStatePpmExact()
        near((2000.0 - css) / (1200.0 - css), high / low, 1e-9)
    }

    @Test
    fun `필요한 길이 - 30초 단위, 최소 1분, 최대 2분`() {
        val limit = cfg.co2LimitPpm.toDouble()
        assertEquals(1200.0, VentPlanner.targetPpm(cfg, limit), "목표는 한도의 60%")
        assertEquals(60, VentPlanner.shortVentSeconds(cabin(1).copyAt(limit), cfg, limit), "혼자: 약 1.2분이 필요해 1분")
        assertEquals(90, VentPlanner.shortVentSeconds(cabin(2).copyAt(limit), cfg, limit), "2명: 약 1.4분이 필요해 1분 30초")
        assertEquals(120, VentPlanner.shortVentSeconds(cabin(4).copyAt(limit), cfg, limit), "4명: 2분으로도 모자라 2분")
        assertEquals(60, VentPlanner.shortVentSeconds(cabin(1).copyAt(900.0), cfg, limit), "이미 목표 아래여도 1분")
        // 풍량이 0 이라 끝내 목표까지 못 내려가면 기본 길이를 다 쓴다
        assertEquals(120, VentPlanner.shortVentSeconds(CabinAirModel(cfg).apply { occupants = 4 }.copyAt(limit), cfg, limit))
        for (n in 1..5) assertTrue(VentPlanner.shortVentSeconds(cabin(n).copyAt(limit), cfg, limit) in setOf(60, 90, 120))
    }

    @Test
    fun `필요한 길이 - 데모 속도에서는 20초의 절반, 4분의 3, 전부`() {
        val limit = demo.co2LimitPpm.toDouble()
        assertEquals(10, VentPlanner.shortVentSeconds(cabin(1, c = demo).copyAt(limit), demo, limit))
        assertEquals(15, VentPlanner.shortVentSeconds(cabin(2, c = demo).copyAt(limit), demo, limit))
        assertEquals(20, VentPlanner.shortVentSeconds(cabin(4, c = demo).copyAt(limit), demo, limit))
        near(10.0, VentPlanner.modelMinutes(10, demo)); near(2.0, VentPlanner.modelMinutes(120, cfg))
    }

    private fun plan(n: Int, grade: PmGrade?, keep: Double = 5.0, c: WakeConfig = cfg) =
        VentPlanner.plan(cabin(n, c = c), c, c.co2LimitPpm.toDouble(), grade, keepThresholdMin = keep, cooldownMin = 5.0)

    @Test
    fun `설계 - 미세먼지 값이 없거나 보통이면 2분, 혼자는 약 19분마다`() {
        for (g in listOf(null, PmGrade.MODERATE)) {
            val p = plan(1, g)
            assertEquals(120, p.durationSec); assertFalse(p.keepFresh); assertNull(p.targetPpm); assertEquals(g, p.pmGrade)
            assertEquals(VentPlanner.REASON_DEFAULT, p.reason)
            // 한 바퀴: 2분 환기 뒤(약 925ppm) 내기로 다시 2,000 까지 약 17분
            val vent = VentPlanner.ventingCopy(cabin(1).copyAt(2000.0)).also { it.advanceModelMinutes(2.0) }
            val back = vent.copy().also { it.recirculating = true; it.fanLevel = 3 }
            near(2.0 + back.minutesUntilAbove(2000.0)!!, p.intervalMin!!, 1e-6)
            assertTrue(p.intervalMin!! in 18.0..20.0, "간격 ${p.intervalMin}")
        }
    }

    @Test
    fun `설계 - 여럿이면 금방 다시 차서 바깥 공기로 두는 쪽을 권한다, 나쁨 이상에서는 셋부터 약한 바람으로 권한다`() {
        val four = plan(4, null)
        assertTrue(four.keepFresh); assertEquals(VentPlanner.REASON_KEEP, four.reason); assertNull(four.keepFanLevel)
        // v0.7.4 에서 기대값이 바뀌었다. 전에는 나쁨 이상이면 4명도 "2분 + 5분" 되풀이였다(keepFresh=false).
        // 그 설계는 환기 뒤 5분을 쉬는 동안 한도 위에 머문다. 셋부터는 약한 바람으로 바깥 공기를 계속 들이는 쪽을 권한다
        for (g in listOf(PmGrade.BAD, PmGrade.VERY_BAD)) for (n in listOf(3, 4)) {
            val p = plan(n, g)
            assertTrue(p.keepFresh, "${n}명, $g")
            assertEquals(VentPlanner.REASON_KEEP_LOW, p.reason)
            assertEquals(1, p.keepFanLevel)
            assertEquals(1200, p.targetPpm)
            assertTrue(p.intervalMin!! <= 7.0 + 1e-6, "되풀이했다면 간격은 7분 이하: ${p.intervalMin}")
        }
        // 둘은 되풀이로 한도를 지킬 수 있어 그대로다(다시 닿기까지 약 5분)
        for (g in listOf(PmGrade.BAD, PmGrade.VERY_BAD)) {
            val two = plan(2, g)
            assertFalse(two.keepFresh, "둘은 미세먼지가 나쁜 날에 열어 두자고 하지 않는다"); assertNull(two.keepFanLevel)
        }
        assertEquals(VentPlanner.REASON_BAD, plan(2, PmGrade.BAD).reason)
        assertEquals(VentPlanner.REASON_VERY_BAD, plan(2, PmGrade.VERY_BAD).reason)
    }

    @Test
    fun `설계 - 나쁨이면 필요한 만큼만, 2명은 약 7분마다 1분 30초`() {
        val p = plan(2, PmGrade.BAD)
        assertEquals(90, p.durationSec)
        assertTrue(p.intervalMin!! in 6.0..7.5, "간격 ${p.intervalMin}")
        val solo = plan(1, PmGrade.BAD)
        assertEquals(60, solo.durationSec)
        // 짧게 바꾸면 덜 내려가니 다음 환기는 더 일찍 온다. 그래도 바깥 공기를 들이는 시간의 몫은 줄어든다
        val base = plan(1, null)
        assertTrue(solo.intervalMin!! < base.intervalMin!!)
        assertTrue(solo.durationSec / 60.0 / solo.intervalMin!! < base.durationSec / 60.0 / base.intervalMin!!, "외기 시간 몫")
    }

    @Test
    fun `설계 - 좋음이면 바깥 공기로 두는 쪽으로 더 쉽게 기운다`() {
        // 2명: 2분 환기 뒤 다시 한도까지 5분은 넘고 10분은 안 된다
        assertFalse(plan(2, PmGrade.MODERATE, keep = 5.0).keepFresh)
        val good = plan(2, PmGrade.GOOD, keep = 10.0)
        assertTrue(good.keepFresh)
        assertEquals(VentPlanner.REASON_GOOD, plan(1, PmGrade.GOOD, keep = 10.0).reason)
    }

    @Test
    fun `설계 - 한도에 닿지 않는 조건이면 간격이 없다`() {
        val leaky = cfg.copy(leakAch = 10.0)   // 혼자, 누설 10회/시간이면 평형 1,030ppm
        val p = plan(1, PmGrade.BAD, c = leaky)
        assertNull(p.intervalMin); assertEquals(VentPlanner.REASON_NEVER, p.reason)
    }

    @Test
    fun `설계 - 데모 속도에서는 간격이 초 단위로 읽힌다`() {
        val p = plan(1, PmGrade.BAD, c = demo)
        assertEquals(10, p.durationSec)
        // 데모의 10초는 모델 10분이라 평형까지 내려간다. 다시 한도까지 약 21분(= 실제 21초)
        assertTrue(p.intervalMin!! in 28.0..34.0, "간격 ${p.intervalMin}")
    }

    // ---------- 여정 예보 ----------

    private fun forecast(n: Int, grade: PmGrade?, horizon: Int = 60, toArrival: Boolean = false, recirc: Boolean = true, from: Double? = null): Forecast {
        val air = cabin(n, recirc = recirc).let { if (from != null) it.copyAt(from) else it }
        return VentPlanner.forecast(air, cfg, cfg.co2LimitPpm.toDouble(), plan(n, grade), horizon, toArrival, recirc, cooldownMin = 5.0, arrivingSoonMin = 5)
    }

    @Test
    fun `예보 - 혼자 내기순환이면 한 시간에 약 3번, 첫 번째는 약 20분 뒤`() {
        val f = forecast(1, null)
        assertEquals(60, f.horizonMin); assertFalse(f.toArrival)
        assertEquals(3, f.ventCount)
        assertEquals(20, f.firstVentInMin)
        assertEquals(VentPlanner.FORECAST_POINTS + 1, f.points.size)
        assertEquals(0.0, f.points.first().minute)
        assertTrue(f.points.all { it.ppm <= cfg.co2LimitPpm + 80 }, "제안에 동의하면 한도 근처에서 꺾인다")
        assertTrue(f.points.any { it.venting })
    }

    @Test
    fun `예보 - 나쁨이면 더 자주 더 짧게, 바깥 공기를 들이는 시간은 줄어든다`() {
        val base = forecast(1, null, horizon = 120)
        val bad = forecast(1, PmGrade.BAD, horizon = 120)
        assertTrue(bad.ventCount > base.ventCount, "나쁨 ${bad.ventCount}번, 기본 ${base.ventCount}번")
        assertTrue(bad.ventCount * 60 < base.ventCount * 120, "바깥 공기를 들이는 시간의 합")
        assertEquals(base.firstVentInMin, bad.firstVentInMin, "첫 환기 시점은 같다(한도에 닿을 때)")
    }

    @Test
    fun `예보 - 도착 5분 전부터는 새로 바꾸지 않는다`() {
        // 22분 뒤 도착: 20분쯤 한도에 닿지만 그때는 도착 2분 전이다
        val f = forecast(1, null, horizon = 22, toArrival = true)
        assertEquals(0, f.ventCount); assertNull(f.firstVentInMin); assertTrue(f.toArrival)
        // 같은 22분이어도 목적지를 모르면 바꾼다
        assertEquals(1, forecast(1, null, horizon = 22).ventCount)
    }

    @Test
    fun `예보 - 외기로 달리는 중이면 0번, 여럿이면 한 번 바꾸고 그대로 둔다`() {
        val fresh = forecast(4, null, recirc = false)
        assertEquals(0, fresh.ventCount); assertNull(fresh.firstVentInMin)
        val family = forecast(4, null)
        assertEquals(1, family.ventCount); assertTrue(family.keepFresh)
        assertTrue(family.points.last().venting)
        // 나쁨이어도 둘은 그대로 두지 않고 되풀이한다
        val bad = forecast(2, PmGrade.BAD)
        assertFalse(bad.keepFresh); assertTrue(bad.ventCount >= 7, "횟수 ${bad.ventCount}")
        // v0.7.4: 나쁨에 넷이면 되풀이로는 한도를 지킬 수 없어(전에는 한 시간에 8번 넘게 나왔다) 한 번 바꾸고 약한 바람으로 둔다
        val badFamily = forecast(4, PmGrade.BAD)
        assertTrue(badFamily.keepFresh); assertEquals(1, badFamily.ventCount)
        assertTrue(badFamily.points.drop(5).all { it.ppm < 1500 }, "약한 바람(1단)으로 수렴하는 값은 한도의 75% 아래")
        // 이미 한도 위면 첫 환기는 지금
        assertEquals(0, forecast(1, null, from = 2100.0).firstVentInMin)
    }

    @Test
    fun `예보 - 이미 바꿔 둔 중이면 남은 시간 뒤 내기로 돌아간다`() {
        val air = VentPlanner.ventingCopy(cabin(1).copyAt(1500.0))
        val f = VentPlanner.forecast(
            air, cfg, 2000.0, plan(1, null), 60, false, recirculating = true,
            cooldownMin = 5.0, arrivingSoonMin = 5, ventRemainingMin = 1.0, baseFanLevel = 3,
        )
        assertTrue(f.points.first().venting)
        assertFalse(f.points[3].venting)
        assertTrue(f.points[10].ppm > f.points[2].ppm, "되돌린 뒤 다시 오른다")
        // 내다보는 길이는 6시간까지
        assertEquals(VentPlanner.FORECAST_MAX_MIN, VentPlanner.forecast(air, cfg, 2000.0, plan(1, null), 900, true, true, 5.0, 5).horizonMin)
    }

    // ---------- 졸리기 쉬운 시간대 가중 ----------

    @Test
    fun `시각 성분 - 새벽 4시에 가장 높고 오후 3시에 작은 봉우리`() {
        near(1.0, AlertnessWeight.circadian(4 * 60))
        near(AlertnessWeight.AFTERNOON_HEIGHT, AlertnessWeight.circadian(15 * 60))
        for (h in listOf(10, 11, 12, 18, 20, 22)) near(0.0, AlertnessWeight.circadian(h * 60), msg = "${h}시")
        for (minute in 0 until 1440) {
            val v = AlertnessWeight.circadian(minute)
            assertTrue(v in 0.0..1.0)
            assertTrue(v <= AlertnessWeight.circadian(4 * 60))
        }
        // 4시를 가운데로 대칭이고 자정을 넘어 이어진다
        near(AlertnessWeight.circadian(2 * 60), AlertnessWeight.circadian(6 * 60))
        assertTrue(AlertnessWeight.circadian(23 * 60 + 30) > 0.0)
        near(AlertnessWeight.circadian(23 * 60 + 30), AlertnessWeight.circadian(23 * 60 + 30 + 1440), msg = "하루를 넘는 값도 같은 시각으로 읽는다")
        near(AlertnessWeight.circadian(1439), AlertnessWeight.circadian(-1))
        // 졸음 관련 사고의 봉우리로 보고된 02시, 06시, 16시 무렵은 낮 한가운데(11시)보다 높다
        for (h in listOf(2, 6, 16)) assertTrue(AlertnessWeight.circadian(h * 60) > AlertnessWeight.circadian(11 * 60), "${h}시")
        // 오후 봉우리는 새벽보다 작다
        assertTrue(AlertnessWeight.circadian(15 * 60) < AlertnessWeight.circadian(2 * 60))
    }

    @Test
    fun `가중 - 시각과 연속 주행의 합, 0점5 이상이면 졸리기 쉬운 시간대`() {
        val dawn = AlertnessWeight.weigh(4 * 60, 0.0, 120)
        near(0.7, dawn.weight); assertTrue(dawn.sleepyHours); assertTrue(dawn.clockKnown)
        near(1.0, AlertnessWeight.weigh(4 * 60, 120.0, 120).weight)
        val morning = AlertnessWeight.weigh(10 * 60, 240.0, 120)
        near(0.3, morning.weight); assertFalse(morning.sleepyHours)
        val afternoon = AlertnessWeight.weigh(15 * 60, 120.0, 120)
        near(0.7 * 0.35 + 0.3, afternoon.weight); assertTrue(afternoon.sleepyHours)
        assertFalse(AlertnessWeight.weigh(15 * 60, 0.0, 120).sleepyHours, "오후 봉우리만으로는 표시하지 않는다")
        // 오래 달릴수록 올라가고 1 을 넘지 않는다
        var prev = -1.0
        for (m in 0..300 step 10) {
            val w = AlertnessWeight.weigh(1 * 60, m.toDouble(), 120).weight
            assertTrue(w >= prev && w <= 1.0); prev = w
        }
        near(1.0, AlertnessWeight.drive(500.0, 120)); near(0.5, AlertnessWeight.drive(60.0, 120)); near(0.0, AlertnessWeight.drive(-5.0, 120))
    }

    @Test
    fun `가중 - 시각을 모르면 하향등으로만 어림한다`() {
        val unknown = AlertnessWeight.weigh(null, 240.0, 120)
        assertFalse(unknown.clockKnown); near(0.3, unknown.weight); assertFalse(unknown.sleepyHours)
        val night = AlertnessWeight.weigh(null, 120.0, 120, nightHint = true)
        near(0.7 * AlertnessWeight.NIGHT_HINT_CIRCADIAN + 0.3, night.weight); assertTrue(night.sleepyHours)
        assertFalse(AlertnessWeight.weigh(null, 0.0, 120, nightHint = true).sleepyHours)
        // 시각을 알면 하향등 어림은 쓰지 않는다
        near(0.0, AlertnessWeight.weigh(11 * 60, 0.0, 120, nightHint = true).weight)
    }

    // ---------- 세 갈래 그림자 계산, 지나온 곡선 ----------

    @Test
    fun `그림자 - 내기만은 CO2 가 높고 미세먼지가 낮다, 외기만은 그 반대다`() {
        val actual = cabin(2)
        val cf = Counterfactual(cfg)
        cf.start(actual)
        cf.sync(occupants = 2, fanLevel = 3, fanMax = 8, windowPercent = 0, outdoorPm = 50.0)
        near(actual.ppmExact(), cf.recircOnly.air.ppmExact()); near(actual.ppmExact(), cf.freshOnly.air.ppmExact())
        repeat(60) { cf.advance(0.5) }
        val r = cf.recircOnly.stats(); val f = cf.freshOnly.stats()
        assertTrue(r.peakPpm > 4000 && f.peakPpm < 1000, "내기만 ${r.peakPpm}, 외기만 ${f.peakPpm}")
        assertTrue(r.minutesOverLimit > 15); assertEquals(0, f.minutesOverLimit)
        assertTrue(r.pmAverage!! < f.pmAverage!!, "내기만 ${r.pmAverage}, 외기만 ${f.pmAverage}")
        near(30.0, f.pmAverage!!, 1e-6)            // 외기만: 50 × 0.6 그대로
        assertTrue(r.pmAverage!! in 7.5..12.0)       // 내기만: 30 에서 7.5 로 내려간 평균
        near(f.pmAverage!! * 30.0, f.pmExposure!!, 1e-6)
        // 한 걸음에 전진해도 같은 값이다
        val one = Counterfactual(cfg).also { it.start(actual); it.sync(2, 3, 8, 0, 50.0); it.advance(30.0) }
        near(cf.recircOnly.air.ppmExact(), one.recircOnly.air.ppmExact(), 1e-6)
    }

    @Test
    fun `그림자 - 바깥 값이 없으면 미세먼지는 비고, 쉬고 나오면 값만 다시 시작한다`() {
        val actual = cabin(1)
        val cf = Counterfactual(cfg)
        cf.start(actual)
        cf.sync(1, 3, 8, 0, null)
        cf.advance(30.0)
        assertNull(cf.recircOnly.stats().pmExposure); assertNull(cf.freshOnly.stats().pmAverage)
        val peak = cf.recircOnly.peakPpm
        cf.reopen(actual)
        near(actual.ppmExact(), cf.recircOnly.air.ppmExact())
        assertEquals(peak, cf.recircOnly.peakPpm, "최고값은 남는다")
        cf.start(actual)
        near(actual.ppmExact(), cf.recircOnly.peakPpm, msg = "새 주행은 처음부터")
        // 창문을 열면 두 갈래 모두 바깥과 같아진다고 본다
        cf.sync(1, 3, 8, 30, 40.0)
        near(40.0, cf.recircOnly.pm.steadyState()!!); near(40.0, cf.freshOnly.pm.steadyState()!!)
    }

    @Test
    fun `지나온 곡선은 30초마다 한 점, 최근 60점만 남긴다`() {
        val t = BreathTrace()
        t.add(0.0, 630, 630)
        t.add(0.2, 640, 640)      // 30초가 안 됐다
        t.add(0.5, 660, 660)
        assertEquals(listOf(0.0, 0.5), t.snapshot().map { it.minute })
        for (i in 2..100) t.add(i * 0.5, 700 + i, 800 + i)
        val s = t.snapshot()
        assertEquals(60, s.size)
        assertEquals(50.0, s.last().minute); assertEquals(20.5, s.first().minute)
        assertEquals(900, s.last().recircOnlyPpm)
        t.clear()
        assertTrue(t.snapshot().isEmpty())
        t.add(0.0, 1, 1)
        assertEquals(1, t.snapshot().size)
    }
}

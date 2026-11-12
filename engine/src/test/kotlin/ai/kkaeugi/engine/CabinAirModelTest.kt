package ai.kkaeugi.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 숨 모델 단독 테스트. 기대값은 숫자를 박아 두지 않고 설정값(WakeConfig)에서 질량 수지 해석해를 따로 계산해 맞춘다.
 * 계수가 바뀌어도 식이 맞으면 통과하고, 설계 근거가 되는 값(1단 약 25회/시간 등)만 숫자로 고정한다.
 */
class CabinAirModelTest {

    private val cfg = WakeConfig()
    private val t0 = 1_000_000L
    private val min = 60_000L

    private val outdoor = cfg.outdoorPpm.toDouble()
    private val start = outdoor + CabinAirModel.START_OFFSET_PPM
    private val limit = cfg.co2LimitPpm
    /** 한 사람이 올리는 속도(ppm/시간) */
    private val genPerPerson = cfg.co2PerPersonLph * 1e6 / cfg.cabinVolumeL

    private fun model(c: WakeConfig = cfg, occupants: Int = 1, recirc: Boolean = true) =
        CabinAirModel(c).apply {
            this.occupants = occupants
            recirculating = recirc
            reset(t0)
        }

    /** 정상상태 ppm: 바깥 + 생성량 / 교환 */
    private fun steady(n: Int, ach: Double): Double = outdoor + n * genPerPerson / ach

    /** 해석해: 교환 ach 로 hours 뒤의 ppm */
    private fun solve(c0: Double, n: Int, ach: Double, hours: Double): Double {
        val css = steady(n, ach)
        return css + (c0 - css) * exp(-ach * hours)
    }

    /** 해석해: 내기순환에서 시작값 → 한도까지 걸리는 분 */
    private fun minutesToLimit(n: Int): Double {
        val css = steady(n, cfg.leakAch)
        return ln((css - start) / (css - limit)) / cfg.leakAch * 60.0
    }

    /** 외기 모드의 교환(회/시간). 팬 1단이 FAN_MIN_FRACTION, 최대 단이 1.0 인 선형. 풍량 0 이나 모름은 FAN_OFF_FRACTION */
    private fun freshAch(level: Int, max: Int): Double {
        val fraction = when {
            level <= 0 || max <= 0 -> CabinAirModel.FAN_OFF_FRACTION
            max == 1 -> 1.0
            else -> CabinAirModel.FAN_MIN_FRACTION + (1.0 - CabinAirModel.FAN_MIN_FRACTION) * (level - 1) / (max - 1)
        }
        return cfg.leakAch + cfg.freshAchMax * fraction
    }

    private fun assertNear(expected: Double, actual: Double, tol: Double = 0.5) =
        assertTrue(abs(expected - actual) <= tol, "기대 $expected, 실제 $actual")

    @Test
    fun `세션 시작은 바깥보다 200 높고 1명 내기순환 30분 뒤는 식대로 약 2400ppm`() {
        val m = model()
        assertEquals(start.roundToInt(), m.ppm())
        assertEquals(cfg.outdoorPpm + 200, m.ppm())
        m.advance(t0 + 30 * min)
        val expected = solve(start, 1, cfg.leakAch, 0.5)
        assertNear(expected, m.ppmExact(), 1e-6)
        assertEquals(expected.roundToInt(), m.ppm())
        assertNear(2400.0, m.ppmExact(), 5.0)
        assertEquals(steady(1, cfg.leakAch).roundToInt(), m.steadyStatePpm())
    }

    @Test
    fun `한도 도달 분은 인원이 늘수록 짧아지고 4명은 1명보다 4배 넘게 빠르다`() {
        val one = model(occupants = 1).minutesToLimit(limit)!!
        val two = model(occupants = 2).minutesToLimit(limit)!!
        val four = model(occupants = 4).minutesToLimit(limit)!!
        // 해석해를 올림한 값과 같다(기본값에서 약 20.2분, 8.1분, 3.7분)
        assertEquals(ceil(minutesToLimit(1)).toInt(), one)
        assertEquals(ceil(minutesToLimit(2)).toInt(), two)
        assertEquals(ceil(minutesToLimit(4)).toInt(), four)
        assertTrue(four < two && two < one)
        assertNear(20.2, minutesToLimit(1), 0.1)
        assertNear(8.1, minutesToLimit(2), 0.1)
        assertNear(3.7, minutesToLimit(4), 0.1)
        assertTrue(minutesToLimit(1) / minutesToLimit(4) > 4.0)

        // 예측한 시각에 실제로 한도에 닿는다
        val m = model(occupants = 4)
        m.advance(t0 + (minutesToLimit(4) * min).toLong() - 1_000)
        assertTrue(m.ppmExact() < limit)
        m.advance(t0 + (minutesToLimit(4) * min).toLong() + 1_000)
        assertTrue(m.ppmExact() >= limit)
        assertEquals(0, m.minutesToLimit(limit))
    }

    @Test
    fun `외기로 바꾸면 2분 만에 식대로 내려가고 풍량이 셀수록 더 내려간다`() {
        fun afterTwoMinutes(level: Int, max: Int): Pair<Double, Double> {
            val m = model()
            m.advance(t0 + 21 * min)
            val before = m.ppmExact()
            m.recirculating = false
            m.fanLevel = level; m.fanMax = max
            m.advance(t0 + 23 * min)
            return before to m.ppmExact()
        }
        // 풍량을 모르면 주행풍만 들어온다고 본다
        val (b0, unknown) = afterTwoMinutes(0, 0)
        assertNear(solve(b0, 1, freshAch(0, 0), 2.0 / 60), unknown, 1e-6)
        // 8단 중 4단
        val (b1, half) = afterTwoMinutes(4, 8)
        assertNear(solve(b1, 1, freshAch(4, 8), 2.0 / 60), half, 1e-6)
        // 최대
        val (b2, full) = afterTwoMinutes(8, 8)
        assertNear(solve(b2, 1, freshAch(8, 8), 2.0 / 60), full, 1e-6)
        assertTrue(full < half && half < unknown && unknown < b0)
        // 4단이면 2분 안에 1,000ppm 아래로 내려간다
        assertTrue(half < 1000, "4/8 단 2분 뒤: $half")
    }

    @Test
    fun `외기 상태는 정상상태가 한도 아래라 도달 시간이 없다`() {
        val m = model(recirc = false)
        m.fanLevel = 4; m.fanMax = 8
        val css = steady(1, freshAch(4, 8))
        assertTrue(css < limit)
        assertEquals(css.roundToInt(), m.steadyStatePpm())
        assertNull(m.minutesToLimit(limit))
        m.advance(t0 + 120 * min)
        assertEquals(m.steadyStatePpm(), m.ppm())
    }

    @Test
    fun `팬 단수는 1단 약 27회에서 최대 62회까지 선형이고 1단이 팬 0보다 많이 들어온다`() {
        val m = model(recirc = false)
        fun ach(level: Int, max: Int): Double { m.fanLevel = level; m.fanMax = max; return m.ach() }

        // 팬 0 은 주행풍(0.15), 1단은 약 25회/시간 + 누설
        val off = ach(0, 8)
        val first = ach(1, 8)
        assertNear(cfg.leakAch + cfg.freshAchMax * CabinAirModel.FAN_OFF_FRACTION, off, 1e-9)
        assertNear(cfg.leakAch + 25.0, first, 1e-9)
        assertTrue(first > off, "1단 $first, 팬 0 $off")
        // 최대 단은 freshAchMax 전부
        assertNear(cfg.leakAch + cfg.freshAchMax, ach(8, 8), 1e-9)
        // 사이는 같은 간격으로 오른다
        val steps = (1..8).map { ach(it, 8) }
        val gaps = steps.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { abs(it - gaps.first()) < 1e-9 && it > 0 }, "간격 $gaps")
        for (level in 1..8) assertNear(freshAch(level, 8), steps[level - 1], 1e-9)
        // 최대 단을 넘는 값이 와도 최대로 본다
        assertNear(ach(8, 8), ach(12, 8), 1e-9)
        // 최대 단계를 모르면 팬 0 과 같다
        assertNear(off, ach(3, 0), 1e-9)
        // 내기순환이면 팬은 바깥 공기를 들이지 않는다
        m.recirculating = true
        assertNear(cfg.leakAch, ach(8, 8), 1e-9)
    }

    @Test
    fun `fanMax 가 1 이면 켜면 최대 끄면 주행풍이다`() {
        val m = model(recirc = false)
        m.fanMax = 1
        m.fanLevel = 1
        assertNear(cfg.leakAch + cfg.freshAchMax, m.ach(), 1e-9)
        assertEquals(steady(1, cfg.leakAch + cfg.freshAchMax).roundToInt(), m.steadyStatePpm())
        m.fanLevel = 0
        assertNear(cfg.leakAch + cfg.freshAchMax * CabinAirModel.FAN_OFF_FRACTION, m.ach(), 1e-9)
    }

    @Test
    fun `4명이 외기 팬 1단이면 정상상태가 한도 아래라 한도에 닿지 않는다`() {
        val m = model(occupants = 4, recirc = false)
        m.fanLevel = 1; m.fanMax = 8
        val css = steady(4, freshAch(1, 8))
        assertEquals(css.roundToInt(), m.steadyStatePpm())
        assertTrue(m.steadyStatePpm() < limit, "정상상태 ${m.steadyStatePpm()}")
        assertNull(m.minutesToLimit(limit))
        m.advance(t0 + 180 * min)
        assertTrue(m.ppmExact() < limit)
        // 팬을 끄면(주행풍만) 4명은 한도를 넘는다. 1단과 팬 0 의 차이가 여기서 갈린다
        m.fanLevel = 0
        assertTrue(m.steadyStatePpm() > limit, "팬 0 정상상태 ${m.steadyStatePpm()}")
    }

    @Test
    fun `창문은 5퍼센트부터 환기로 치고 15퍼센트 기준으로 3배까지만 센다`() {
        val m = model()
        val leak = cfg.leakAch
        assertNear(leak, m.ach(), 1e-9)
        m.windowPercent = 4
        assertNear(leak, m.ach(), 1e-9)
        m.windowPercent = 5
        assertNear(leak + cfg.windowAch * 5 / 15, m.ach(), 1e-9)
        m.windowPercent = 15
        assertNear(leak + cfg.windowAch, m.ach(), 1e-9)
        m.windowPercent = 100
        assertNear(leak + cfg.windowAch * 3, m.ach(), 1e-9)

        // 내기순환이어도 창문 15% 면 1명은 한도에 닿지 않는다
        m.windowPercent = 15
        val css = steady(1, leak + cfg.windowAch)
        assertTrue(css < limit)
        assertNull(m.minutesToLimit(limit))
        m.advance(t0 + 60 * min)
        assertEquals(css.roundToInt(), m.ppm())
    }

    @Test
    fun `큰 스텝 한 번과 작은 스텝 여러 번의 결과가 같고 dt 가 아주 커도 안정이다`() {
        val big = model(occupants = 3)
        big.advance(t0 + 45 * min)
        val small = model(occupants = 3)
        for (s in 1..45 * 60) small.advance(t0 + s * 1_000L)
        assertNear(big.ppmExact(), small.ppmExact(), 1e-6)

        // 30일을 한 번에 가도 정상상태로 수렴할 뿐 튀지 않는다
        big.advance(t0 + 30L * 24 * 60 * min)
        assertEquals(big.steadyStatePpm(), big.ppm())
        assertEquals(steady(3, cfg.leakAch).roundToInt(), big.ppm())
    }

    @Test
    fun `음수 dt 는 무시하고 시계가 돌아온 뒤에는 이어서 흐른다`() {
        val m = model()
        m.advance(t0 + 10 * min)
        val at10 = m.ppmExact()
        m.advance(t0 + 5 * min)
        m.advance(t0)
        assertEquals(at10, m.ppmExact())
        // 뒤로 간 만큼을 두 번 세지 않는다
        m.advance(t0 + 11 * min)
        assertNear(solve(start, 1, cfg.leakAch, 11.0 / 60), m.ppmExact(), 1e-6)
    }

    @Test
    fun `demoFast 면 시간이 60배로 흐른다`() {
        val demo = model(cfg.copy(demoFast = true))
        demo.advance(t0 + 30_000) // 30초
        val real = model()
        real.advance(t0 + 30 * min) // 30분
        assertNear(real.ppmExact(), demo.ppmExact(), 1e-6)
        assertEquals(solve(start, 1, cfg.leakAch, 0.5).roundToInt(), demo.ppm())
    }

    @Test
    fun `인원을 모르거나 0 이면 1명으로 본다`() {
        val m = model()
        m.occupants = 0
        assertEquals(1, m.occupants)
        assertEquals(steady(1, cfg.leakAch).roundToInt(), m.steadyStatePpm())
        m.occupants = 4
        assertEquals(steady(4, cfg.leakAch).roundToInt(), m.steadyStatePpm())
    }

    @Test
    fun `복제본을 전진시켜도 원본은 그대로이고 advanceBy 는 같은 시간만큼의 advance 와 같다`() {
        val m = model(occupants = 3)
        m.fanLevel = 3; m.fanMax = 8
        m.windowPercent = 10
        m.advance(t0 + 7 * min)
        val before = m.ppmExact()

        val clone = m.copy()
        assertEquals(before, clone.ppmExact())
        assertEquals(m.ach(), clone.ach())
        assertEquals(3, clone.occupants)
        clone.recirculating = false
        clone.advanceBy(2 * min)
        assertTrue(clone.ppmExact() < before)
        // 원본은 값도 입력도 그대로다
        assertEquals(before, m.ppmExact())
        assertTrue(m.recirculating)

        // advanceBy(dt) 는 기준 시각에서 dt 만큼 advance 한 것과 같다
        val a = m.copy().also { it.advanceBy(5 * min) }
        m.advance(t0 + 12 * min)
        assertNear(m.ppmExact(), a.ppmExact(), 1e-9)
        // 0 이나 음수는 아무 일도 하지 않는다
        val c = m.ppmExact()
        m.advanceBy(0); m.advanceBy(-5_000)
        assertEquals(c, m.ppmExact())
    }

    @Test
    fun `rebase 는 값을 그대로 두고 그 사이 시간을 흐르지 않은 것으로 본다`() {
        val m = model(occupants = 2)
        m.advance(t0 + 5 * min)
        val at5 = m.ppmExact()
        m.rebase(t0 + 60 * min) // 55분을 건너뛴다
        assertEquals(at5, m.ppmExact())
        m.advance(t0 + 61 * min)
        assertNear(solve(at5, 2, cfg.leakAch, 1.0 / 60), m.ppmExact(), 1e-6)
        // reset 은 처음 값으로 돌린다
        m.reset(t0 + 61 * min)
        assertEquals(start, m.ppmExact())
    }

    @Test
    fun `한도까지 남은 분은 소수로도 구할 수 있고 올림한 값이 minutesToLimit 이다`() {
        val m = model(occupants = 2)
        val exact = m.minutesUntilAbove(limit.toDouble())!!
        assertNear(minutesToLimit(2), exact, 1e-9)
        assertEquals(ceil(exact).toInt(), m.minutesToLimit(limit))
        // 낮춘 한도(야간 0.8배)면 더 빨리 닿는다
        assertTrue(m.minutesUntilAbove(limit * 0.8)!! < exact)
        // 이미 넘었으면 0(외기로 바꿔 내려가는 중이어도 지금 한도 위면 0)
        m.advance(t0 + 30 * min)
        assertEquals(0.0, m.minutesUntilAbove(limit.toDouble()))
        m.recirculating = false
        m.fanLevel = 8; m.fanMax = 8
        assertEquals(0.0, m.minutesUntilAbove(limit.toDouble()))
        // 한도 아래로 내려왔고 정상상태도 한도 아래면 null
        m.advance(t0 + 40 * min)
        assertTrue(m.ppmExact() < limit)
        assertNull(m.minutesUntilAbove(limit.toDouble()))
        assertNear(steady(2, freshAch(8, 8)), m.steadyStatePpmExact(), 1e-9)
    }
}

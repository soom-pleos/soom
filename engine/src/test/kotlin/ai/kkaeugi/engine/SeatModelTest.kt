package ai.kkaeugi.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.7.3 의 순수 부품 셋을 본다: 자리별 프로필에서 설정 뽑기(SeatPolicy), 앉은 시간(SitClock), 자리별 누적(SeatLedger).
 * 엔진에 이어 붙인 동작은 SeatEngineTest 가 본다
 */
class SeatModelTest {

    private val config = WakeConfig()
    private val D = SeatProfile.DEFAULT
    private fun profiles(vararg p: Pair<Int, SeatProfile>): List<SeatProfile> = MutableList(Seats.COUNT) { D }.also { l -> p.forEach { (i, v) -> l[i] = v } }
    private val min = 60_000L
    private val emptyReset = 2 * min

    // ---------- SeatPolicy: 탄 자리만 ----------

    @Test
    fun `빈자리에 붙여 둔 프로필은 쓰지 않는다`() {
        val p = profiles(Seats.REAR_LEFT to SeatProfile.CHILD, Seats.FRONT_PASSENGER to SeatProfile.CARDIO)
        // 운전석과 뒷줄 왼쪽만 탔다
        val aboard = SeatPolicy.aboard(p, listOf(true, false, true, false, false), occupants = 2, driving = true)
        assertEquals(listOf(SeatProfile.CHILD), aboard)
        assertTrue(SeatPolicy.wideMargin(aboard))
        assertFalse(SeatPolicy.pmFirst(aboard))
        assertEquals(120, SeatPolicy.breakMinutes(aboard, config))
    }

    @Test
    fun `주행 중 운전석은 값이 없어도 탄 것으로 본다`() {
        val p = profiles(Seats.DRIVER to SeatProfile.SENIOR)
        // 에뮬레이터 기본값: 운전석 없음, 나머지 있음
        val seats = listOf(false, true, true, true, true)
        assertEquals(listOf(SeatProfile.SENIOR), SeatPolicy.aboard(p, seats, occupants = 5, driving = true))
        assertEquals(emptyList(), SeatPolicy.aboard(p, seats, occupants = 4, driving = false))
        assertEquals(listOf(true, true, true, true, true), SeatPolicy.occupied(seats, driving = true))
        assertEquals(listOf(false, true, true, true, true), SeatPolicy.occupied(seats, driving = false))
    }

    @Test
    fun `못 읽은 자리는 타지 않은 것으로 본다`() {
        val p = profiles(Seats.REAR_CENTER to SeatProfile.RESPIRATORY)
        assertEquals(emptyList(), SeatPolicy.aboard(p, listOf(true, false, null, null, null), occupants = 1, driving = true))
    }

    @Test
    fun `자리별 값이 없으면 혼자일 때는 운전석 프로필만 둘 이상이면 전부 쓴다`() {
        val p = profiles(Seats.DRIVER to SeatProfile.SENIOR, Seats.REAR_RIGHT to SeatProfile.CHILD)
        assertEquals(listOf(SeatProfile.SENIOR), SeatPolicy.aboard(p, null, occupants = 1, driving = true))
        assertEquals(listOf(SeatProfile.SENIOR, SeatProfile.CHILD), SeatPolicy.aboard(p, null, occupants = 2, driving = true))
    }

    @Test
    fun `프로필이 전부 기본이면 아무 설정도 바뀌지 않는다`() {
        val aboard = SeatPolicy.aboard(profiles(), listOf(true, true, true, true, true), occupants = 5, driving = true)
        assertEquals(emptyList(), aboard)
        assertFalse(SeatPolicy.pmFirst(aboard)); assertFalse(SeatPolicy.wideMargin(aboard)); assertFalse(SeatPolicy.shortBreak(aboard))
        assertEquals(config.sitBreakMinutes, SeatPolicy.breakMinutes(aboard, config))
        for (g in PmGrade.entries) assertEquals(g, SeatPolicy.policyGrade(g, pmFirst = false))
        assertNull(SeatPolicy.policyGrade(null, pmFirst = false))
        assertEquals(1.0, SeatPolicy.limitFactor(wideMargin = false, nightLongTrip = false, pmShort = false, config = config))
    }

    @Test
    fun `모자라는 칸은 기본으로 채우고 넘치는 칸은 버린다`() {
        assertEquals(List(5) { D }, SeatPolicy.normalize(emptyList()))
        assertEquals(5, SeatPolicy.normalize(List(9) { SeatProfile.CHILD }).size)
    }

    // ---------- SeatPolicy: 프로필별 설정 ----------

    @Test
    fun `프로필별로 무엇이 바뀌는지`() {
        fun one(p: SeatProfile) = listOf(p)
        // 미세먼지 먼저: 호흡기 주의, 심혈관 주의
        assertEquals(listOf(SeatProfile.RESPIRATORY, SeatProfile.CARDIO), SeatProfile.entries.filter { SeatPolicy.pmFirst(one(it)) })
        // 공기 여유 넓게: 어린이, 임산부
        assertEquals(listOf(SeatProfile.CHILD, SeatProfile.PREGNANT), SeatProfile.entries.filter { SeatPolicy.wideMargin(one(it)) })
        // 더 자주 쉬기: 임산부, 어르신, 심혈관 주의
        assertEquals(listOf(SeatProfile.PREGNANT, SeatProfile.SENIOR, SeatProfile.CARDIO), SeatProfile.entries.filter { SeatPolicy.shortBreak(one(it)) })
        assertEquals(90, SeatPolicy.breakMinutes(one(SeatProfile.SENIOR), config))
        assertEquals(120, SeatPolicy.breakMinutes(one(SeatProfile.CHILD), config))
    }

    @Test
    fun `쉬는 간격은 설정값을 따르고 프로필 때문에 더 길어지지 않는다`() {
        val c = config.copy(sitBreakMinutes = 100, sitBreakMinutesShort = 70)
        assertEquals(100, SeatPolicy.breakMinutes(emptyList(), c))
        assertEquals(70, SeatPolicy.breakMinutes(listOf(SeatProfile.PREGNANT), c))
        // 줄인 값이 기본보다 길게 들어와 있으면 기본을 쓴다
        assertEquals(60, SeatPolicy.breakMinutes(listOf(SeatProfile.CARDIO), config.copy(sitBreakMinutes = 60, sitBreakMinutesShort = 90)))
    }

    @Test
    fun `미세먼지 먼저는 등급을 한 단계 나쁘게 본다`() {
        assertEquals(PmGrade.MODERATE, SeatPolicy.policyGrade(PmGrade.GOOD, pmFirst = true))
        assertEquals(PmGrade.BAD, SeatPolicy.policyGrade(PmGrade.MODERATE, pmFirst = true))
        assertEquals(PmGrade.VERY_BAD, SeatPolicy.policyGrade(PmGrade.BAD, pmFirst = true))
        assertEquals(PmGrade.VERY_BAD, SeatPolicy.policyGrade(PmGrade.VERY_BAD, pmFirst = true))
        // 바깥 값이 없으면 지어내지 않는다
        assertNull(SeatPolicy.policyGrade(null, pmFirst = true))
    }

    @Test
    fun `한도에 곱하는 값은 어떤 조합에서도 1 을 넘지 않는다`() {
        for (wide in listOf(false, true)) for (night in listOf(false, true)) for (short in listOf(false, true)) {
            for (fraction in listOf(0.9, 1.0, 1.5, 0.1)) {
                val f = SeatPolicy.limitFactor(wide, night, short, config.copy(airMarginFraction = fraction))
                assertTrue(f <= 1.0, "넘지 않는다: wide=$wide night=$night short=$short fraction=$fraction → $f")
                assertTrue(f >= SeatPolicy.MIN_MARGIN_FRACTION, "반 아래로 내려가지 않는다: $f")
            }
        }
    }

    @Test
    fun `둘이 같이 걸릴 때의 순서`() {
        fun f(wide: Boolean, night: Boolean, short: Boolean) = SeatPolicy.limitFactor(wide, night, short, config)
        // 공기 여유 넓게만: 0.9
        assertEquals(0.9, f(wide = true, night = false, short = false))
        // 야간 장거리만: 0.8. 미세먼지가 나쁘면 그 여유는 없앤다(v0.7.0 그대로)
        assertEquals(0.8, f(wide = false, night = true, short = false))
        assertEquals(1.0, f(wide = false, night = true, short = true))
        // 공기 여유 넓게는 미세먼지가 나빠도 남는다
        assertEquals(0.9, f(wide = true, night = false, short = true))
        assertEquals(0.9, f(wide = true, night = true, short = true))
        // 둘 다면 더 작은 쪽
        assertEquals(0.8, f(wide = true, night = true, short = false))
    }

    @Test
    fun `문장에 쓰는 말`() {
        assertEquals("두 시간째", SeatPolicy.sitPhrase(120))
        assertEquals("두 시간째", SeatPolicy.sitPhrase(149))
        assertEquals("한 시간 반째", SeatPolicy.sitPhrase(90))
        assertEquals("한 시간째", SeatPolicy.sitPhrase(65))
        assertEquals("두 시간 반째", SeatPolicy.sitPhrase(150))
        assertEquals("45분째", SeatPolicy.sitPhrase(45))
        assertEquals("7 시간째", SeatPolicy.sitPhrase(7 * 60))
        assertEquals(listOf("혼자", "둘이", "셋이", "넷이", "다섯이"), (1..5).map { SeatPolicy.countWord(it) })
        assertEquals("혼자", SeatPolicy.countWord(0))
        assertEquals("호흡기 주의", SeatPolicy.firstLabel(listOf(SeatProfile.CARDIO, SeatProfile.RESPIRATORY), listOf(SeatProfile.RESPIRATORY, SeatProfile.CARDIO)))
        assertNull(SeatPolicy.firstLabel(listOf(SeatProfile.SENIOR), listOf(SeatProfile.CHILD, SeatProfile.PREGNANT)))
        assertEquals("뒷줄 왼쪽", Seats.name(Seats.REAR_LEFT))
        assertEquals("차 전체", Seats.name(Seats.CABIN))
    }

    // ---------- SitClock ----------

    @Test
    fun `앉아 있는 동안 이어서 센다`() {
        val s = SitClock()
        s.update(Seats.DRIVER, true, 0, emptyReset)
        s.update(Seats.DRIVER, true, 30 * min, emptyReset)
        assertEquals(72 * min, s.seatedMs(Seats.DRIVER, 72 * min))
        assertTrue(s.isSeated(Seats.DRIVER))
        assertEquals(0, s.seatedMs(Seats.FRONT_PASSENGER, 72 * min))
        assertFalse(s.isSeated(Seats.FRONT_PASSENGER))
    }

    @Test
    fun `자리가 2분 이상 비었다 다시 앉으면 0 부터 다시 센다`() {
        val s = SitClock()
        s.update(Seats.REAR_LEFT, true, 0, emptyReset)
        s.update(Seats.REAR_LEFT, false, 50 * min, emptyReset)
        // 비어 있는 동안은 비기 직전 값에서 멈춘다
        assertEquals(50 * min, s.seatedMs(Seats.REAR_LEFT, 51 * min))
        s.update(Seats.REAR_LEFT, true, 53 * min, emptyReset)
        assertEquals(0, s.seatedMs(Seats.REAR_LEFT, 53 * min))
        assertEquals(7 * min, s.seatedMs(Seats.REAR_LEFT, 60 * min))
    }

    @Test
    fun `2분이 안 되게 빈 것은 자세를 고친 것으로 보고 이어 센다`() {
        val s = SitClock()
        s.update(Seats.REAR_LEFT, true, 0, emptyReset)
        s.update(Seats.REAR_LEFT, false, 50 * min, emptyReset)
        s.update(Seats.REAR_LEFT, true, 51 * min + 59_000, emptyReset)
        assertEquals(60 * min, s.seatedMs(Seats.REAR_LEFT, 60 * min))
    }

    @Test
    fun `오래 빈 채로 두면 앉지 않은 것으로 돌아간다`() {
        val s = SitClock()
        s.update(Seats.FRONT_PASSENGER, true, 0, emptyReset)
        s.update(Seats.FRONT_PASSENGER, false, 10 * min, emptyReset)
        s.update(Seats.FRONT_PASSENGER, false, 12 * min, emptyReset)
        assertFalse(s.isSeated(Seats.FRONT_PASSENGER))
        assertEquals(0, s.seatedMs(Seats.FRONT_PASSENGER, 13 * min))
        assertNull(s.longest(13 * min))
    }

    @Test
    fun `기어 P 로 오래 서 있었으면 모든 자리가 0 부터 다시 센다`() {
        val s = SitClock()
        for (i in 0 until Seats.COUNT) s.update(i, true, 0, emptyReset)
        s.standAll(100 * min)
        for (i in 0 until Seats.COUNT) assertEquals(0, s.seatedMs(i, 100 * min))
        for (i in 0 until Seats.COUNT) s.update(i, true, 100 * min, emptyReset)
        assertEquals(20 * min, s.seatedMs(Seats.REAR_RIGHT, 120 * min))
    }

    @Test
    fun `가장 오래 앉은 자리`() {
        val s = SitClock()
        assertNull(s.longest(0))
        s.update(Seats.DRIVER, true, 0, emptyReset)
        s.update(Seats.REAR_LEFT, true, 40 * min, emptyReset)
        assertEquals(Seats.DRIVER to 90 * min, s.longest(90 * min))
        // 같으면 앞 자리가 먼저다
        val t = SitClock()
        t.update(Seats.FRONT_PASSENGER, true, 0, emptyReset); t.update(Seats.DRIVER, true, 0, emptyReset)
        assertEquals(Seats.DRIVER, t.longest(min)!!.first)
    }

    @Test
    fun `차가 알려 준 주행 시간으로 운전석을 당기되 일어선 뒤로는 당기지 않는다`() {
        val s = SitClock()
        s.update(Seats.DRIVER, true, 100 * min, emptyReset)
        // 앱이 늦게 켜졌다: 차는 이미 40분 달렸다고 한다
        s.backdate(Seats.DRIVER, 60 * min)
        assertEquals(45 * min, s.seatedMs(Seats.DRIVER, 105 * min))
        // 더 짧게 알려 주면 줄이지 않는다
        s.backdate(Seats.DRIVER, 90 * min)
        assertEquals(45 * min, s.seatedMs(Seats.DRIVER, 105 * min))
        // 쉼터에서 일어섰다 다시 앉았다. 주행 시간이 그보다 길어도 일어선 때보다 앞으로는 가지 않는다
        s.standAll(200 * min)
        s.update(Seats.DRIVER, true, 210 * min, emptyReset)
        s.backdate(Seats.DRIVER, 60 * min)
        assertEquals(20 * min, s.seatedMs(Seats.DRIVER, 220 * min))
        // 앉아 있지 않은 자리는 당기지 않는다
        s.backdate(Seats.REAR_LEFT, 0)
        assertEquals(0, s.seatedMs(Seats.REAR_LEFT, 220 * min))
    }

    @Test
    fun `시계가 뒤로 가도 음수를 내지 않고 없는 자리는 무시한다`() {
        val s = SitClock()
        s.update(Seats.DRIVER, true, 10 * min, emptyReset)
        assertEquals(0, s.seatedMs(Seats.DRIVER, 5 * min))
        s.update(9, true, 0, emptyReset); s.backdate(-1, 0)
        assertEquals(0, s.seatedMs(9, min))
        s.reset()
        assertFalse(s.isSeated(Seats.DRIVER))
    }

    // ---------- SeatLedger ----------

    private val all = List(Seats.COUNT) { true }

    @Test
    fun `탄 자리만 쌓고 탄 적 없는 자리는 줄이 없다`() {
        val l = SeatLedger()
        l.advance(30.0, listOf(true, false, true, false, false), ppm = 1500.0, overLimit = false)
        val rows = l.seats(profiles(Seats.REAR_LEFT to SeatProfile.CHILD))
        assertEquals(listOf(Seats.DRIVER, Seats.REAR_LEFT), rows.map { it.seat })
        assertEquals(SeatProfile.CHILD, rows[1].profile)
        assertEquals(30, rows[0].minutesAboard)
        assertEquals(1500, rows[0].averagePpm)
        assertEquals(0, rows[0].minutesOverLimit)
        assertNull(rows[0].pmAverage)
    }

    @Test
    fun `한도 위 시간과 평균은 그 자리에 사람이 있던 동안만`() {
        val l = SeatLedger()
        val solo = listOf(true, false, false, false, false)
        val two = listOf(true, true, false, false, false)
        l.advance(20.0, solo, ppm = 1000.0, overLimit = false)
        // 20분 뒤 조수석에 탔다. 그 뒤 10분은 한도 위
        l.advance(10.0, two, ppm = 2200.0, overLimit = true)
        val (driver, passenger) = l.seats(profiles())
        assertEquals(30, driver.minutesAboard); assertEquals(10, driver.minutesOverLimit); assertEquals(1400, driver.averagePpm)
        assertEquals(10, passenger.minutesAboard); assertEquals(10, passenger.minutesOverLimit); assertEquals(2200, passenger.averagePpm)
        assertFalse(driver.boardedMidTrip)
        assertTrue(passenger.boardedMidTrip, "중간에 탄 사람은 탄 시점부터 센다")
    }

    @Test
    fun `내린 뒤에는 쌓지 않지만 줄은 남는다`() {
        val l = SeatLedger()
        l.advance(15.0, listOf(true, true, false, false, false), ppm = 1200.0, overLimit = false)
        l.advance(45.0, listOf(true, false, false, false, false), ppm = 1800.0, overLimit = false)
        val passenger = l.seats(profiles()).single { it.seat == Seats.FRONT_PASSENGER }
        assertEquals(15, passenger.minutesAboard)
        assertEquals(1200, passenger.averagePpm)
    }

    @Test
    fun `미세먼지 노출은 받은 동안만 쌓고 평균은 그 시간으로 나눈다`() {
        val l = SeatLedger()
        // 앞 10분은 바깥 값이 없었다
        l.advance(10.0, all, ppm = 900.0, overLimit = false)
        // 뒤 20분: 평균 12µg/m³ → 노출량 240
        l.advance(20.0, all, ppm = 900.0, overLimit = false, pmExposure = 240.0, pmMinutes = 20.0)
        val row = l.seats(profiles()).first()
        assertEquals(12.0, row.pmAverage!!, 1e-9)
        assertEquals(240.0, row.pmExposure!!, 1e-9)
        assertEquals(30, row.minutesAboard)
    }

    @Test
    fun `가장 길었던 연속 착석은 최댓값만 남긴다`() {
        val l = SeatLedger()
        l.advance(1.0, all, 800.0, false)
        l.noteSit(Seats.DRIVER, 40.0); l.noteSit(Seats.DRIVER, 95.9); l.noteSit(Seats.DRIVER, 12.0)
        l.noteSit(Seats.CABIN, 77.0)
        assertEquals(95, l.seats(profiles()).first().longestSitMinutes)
        assertEquals(77, l.cabin()!!.longestSitMinutes)
        // 없는 자리는 무시한다
        l.noteSit(42, 500.0)
    }

    @Test
    fun `걷기 쉼은 그때 타고 있던 자리와 차 전체에 센다`() {
        val l = SeatLedger()
        l.advance(60.0, listOf(true, true, false, false, false), 1000.0, false)
        l.noteWalkBreak(listOf(true, true, true, false, false))
        val rows = l.seats(profiles())
        assertEquals(listOf(1, 1), rows.map { it.walkBreaks })
        assertEquals(2, rows.size, "탄 적 없는 자리는 줄이 생기지 않는다")
        assertEquals(1, l.cabin()!!.walkBreaks)
    }

    @Test
    fun `차 전체 줄은 주행한 시간 전부를 쌓고 주행 전에는 없다`() {
        val l = SeatLedger()
        assertNull(l.cabin())
        assertEquals(emptyList(), l.seats(profiles()))
        l.advance(10.0, List(Seats.COUNT) { false }, ppm = 1000.0, overLimit = false)
        l.advance(10.0, List(Seats.COUNT) { false }, ppm = 2400.0, overLimit = true, pmExposure = 50.0, pmMinutes = 10.0)
        val cabin = assertNotNull(l.cabin())
        assertEquals(Seats.CABIN, cabin.seat)
        assertEquals(20, cabin.minutesAboard); assertEquals(10, cabin.minutesOverLimit); assertEquals(1700, cabin.averagePpm)
        assertEquals(5.0, cabin.pmAverage!!, 1e-9)
        assertEquals(emptyList(), l.seats(profiles()))
    }

    @Test
    fun `새 주행이면 전부 비운다`() {
        val l = SeatLedger()
        l.advance(10.0, all, 1000.0, false); l.noteWalkBreak(all)
        l.reset()
        assertNull(l.cabin())
        assertEquals(emptyList(), l.seats(profiles()))
        // 0 이하의 시간은 쌓지 않는다
        l.advance(0.0, all, 1000.0, false); l.advance(-3.0, all, 1000.0, false)
        assertNull(l.cabin())
    }
}

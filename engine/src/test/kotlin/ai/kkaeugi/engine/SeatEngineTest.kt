package ai.kkaeugi.engine

import ai.kkaeugi.engine.Round6Test.Car
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.7.3 "자리별로 줄인다"를 엔진에 이어 붙인 동작을 본다.
 *  - 프로필이 전부 기본이고 걷기 쉼이 꺼져 있으면 이벤트가 v0.7.2 와 하나도 다르지 않다
 *  - 탄 자리의 프로필만 쓴다. 공기 여유 넓게(90%), 미세먼지 먼저(한 단계 나쁘게), 둘이 같이 걸릴 때의 순서(기본 한도는 넘기지 않는다)
 *  - 앉은 시간: 자리가 비거나 기어 P 로 오래 서 있으면 0 부터. 문 신호가 있는 차와 없는 차
 *  - 걷기 쉼 제안, 졸음 제안과 합치기, 걷기 쉼 기록
 *  - 자리별 누적이 도착 요약에 실린다
 */
class SeatEngineTest {

    private val real = WakeConfig()
    private val walk = real.copy(walkBreak = true)
    private val demo = WakeConfig(
        staleAirMinutes = 30, staleAirMinutesNight = 20, ventilateSeconds = 20, drowsySeconds = 30,
        answerTimeoutSec = 8, declineCooldownMinutes = 20, longTripMinutes = 60, restMinutes = 15, demoFast = true,
    )
    private val stop = RestStopInfo("석적", "경부선 서울방향", 12.0, 36.0516, 128.4081)
    private val D = SeatProfile.DEFAULT

    private fun profiles(vararg p: Pair<Int, SeatProfile>): List<SeatProfile> = MutableList(Seats.COUNT) { D }.also { l -> p.forEach { (i, v) -> l[i] = v } }
    private fun Car.profiles(vararg p: Pair<Int, SeatProfile>) = send { SeatProfilesChanged(it, this@SeatEngineTest.profiles(*p)) }
    private fun Car.seats(vararg on: Boolean?) = send { SeatOccupancyChanged(it, on.toList()) }
    private fun Car.pm(value: Double?) = send { OutdoorAir(it, value, "테스트") }
    private fun Car.candidate(s: RestStopInfo? = stop) = send { RestStopCandidate(it, s) }
    private fun Car.gear(g: Gear) = send { GearChanged(it, g) }
    private fun Car.door(open: Boolean) = send { DoorChanged(it, open) }
    private fun Car.telltale(on: Boolean) = send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, on) }

    private fun car(config: WakeConfig = real, answer: Boolean? = true) = Car(config).also { it.autoAnswer = answer; it.speechSec = 3 }

    private fun Car.firstProposal(max: Int = 2400): WakeEvent.Propose {
        assertNotNull(runUntil<WakeEvent.Propose>(max), "제안이 나와야 한다")
        return last<WakeEvent.Propose>()!!
    }

    private fun Car.proposalsOf(kind: TriggerKind) = events.filterIsInstance<WakeEvent.Propose>().filter { it.kind == kind }

    // ---------- 기본이면 v0.7.2 와 같다 ----------

    @Test
    fun `프로필이 전부 기본이고 걷기 쉼이 꺼져 있으면 이벤트가 v0_7_2 와 같다`() {
        fun strip(e: WakeEvent) = if (e is WakeEvent.ArrivalCard) e.copy(card = e.card.copy(seats = emptyList(), cabin = null, seatHeadline = "", walkBreaks = 0)) else e
        fun trip(occupants: Int, config: WakeConfig, pm: Double?, feed: (Car) -> Unit): List<WakeEvent> {
            val c = car(config)
            feed(c)
            if (pm != null) c.pm(pm)
            c.candidate()
            c.drive(occupants = occupants)
            c.run(1500)
            c.telltale(true)
            c.run(600)
            feed(c)
            c.run(1500)
            c.arrive()
            return c.events.map(::strip)
        }
        for (config in listOf(real, demo)) for (n in listOf(1, 4)) for (pm in listOf(null, 41.0)) {
            val base = trip(n, config, pm) {}
            val variants: List<Pair<String, (Car) -> Unit>> = listOf(
                "자리별 값과 기본 프로필" to { c -> c.seats(true, true, true, true, false); c.profiles() },
                "문 신호" to { c -> c.door(true); c.door(false) },
                "빈자리에만 프로필" to { c -> c.seats(true, n >= 2, n >= 3, false, n >= 4); c.profiles(Seats.REAR_CENTER to SeatProfile.RESPIRATORY) },
                "못 읽은 자리의 프로필" to { c -> c.seats(false, null, null, null, null); c.profiles(Seats.FRONT_PASSENGER to SeatProfile.CHILD, Seats.REAR_LEFT to SeatProfile.CARDIO) },
            )
            for ((name, feed) in variants) assertEquals(base, trip(n, config, pm, feed), "$name, ${n}명, 데모=${config.demoFast}, 미세먼지=$pm")
            assertTrue(base.any { it is WakeEvent.Propose }, "비교할 제안이 있어야 한다")
            assertTrue(base.none { it is WakeEvent.Propose && it.kind == TriggerKind.WALK_BREAK })
        }
    }

    @Test
    fun `걷기 쉼이 꺼져 있으면 아무리 오래 앉아 있어도 묻지 않는다`() {
        val c = car(real)
        c.profiles(Seats.DRIVER to SeatProfile.SENIOR)
        c.candidate()
        c.drive(recirc = false)
        c.run(200 * 60)
        assertEquals(emptyList(), c.proposalsOf(TriggerKind.WALK_BREAK))
        // 앉은 시간은 세고 있다(읽기 전용)
        assertEquals(200, c.engine.care(c.now).sitMinutes)
        assertNull(c.engine.care(c.now).breakMinutes)
    }

    // ---------- 공기 여유 넓게 ----------

    @Test
    fun `어린이 자리에 사람이 타면 한도의 90퍼센트에서 묻고 이유를 한 번 말한다`() {
        val c = car(answer = false)
        c.seats(true, false, true, false, false)
        c.profiles(Seats.REAR_LEFT to SeatProfile.CHILD)
        c.drive(occupants = 2)
        assertEquals(1800, c.engine.effectiveLimitPpm)
        assertTrue(c.engine.care(c.now).wideMargin)
        val first = c.firstProposal()
        // 한도가 낮아진 만큼 2분 환기 뒤 금방 다시 닿는다. 그래서 둘이어도 바깥 공기로 둘지 묻는다
        assertEquals("어린이 자리가 있어 조금 일찍 물어요. 2명이 타서 공기가 빨리 탁해져요(추정 1,800ppm). 바깥 공기로 바꿔 둘까요?", first.text)
        assertTrue(c.peakSeen < 2000, "기본 한도에 닿기 전에 물었다: ${c.peakSeen}")
        // 같은 주행의 다음 제안에는 붙이지 않는다
        c.run(60)
        val second = c.firstProposal()
        assertFalse(second.text.contains("조금 일찍"), second.text)
        assertTrue(second.text.endsWith("2분만 바깥 공기로 바꿀까요?"), second.text)
    }

    @Test
    fun `임산부 자리도 같고 빈자리에 붙여 둔 프로필은 쓰지 않는다`() {
        val c = car()
        c.seats(true, true, false, false, false)
        c.profiles(Seats.FRONT_PASSENGER to SeatProfile.PREGNANT)
        c.drive(occupants = 2)
        assertTrue(c.firstProposal().text.startsWith("임산부 자리가 있어 조금 일찍 물어요. "))

        val empty = car()
        empty.seats(true, false, false, false, false)
        empty.profiles(Seats.FRONT_PASSENGER to SeatProfile.PREGNANT, Seats.REAR_LEFT to SeatProfile.CHILD)
        empty.drive()
        assertEquals(2000, empty.engine.effectiveLimitPpm)
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", empty.firstProposal().text)
    }

    @Test
    fun `주행 중에 사람이 타거나 내리면 그때부터 한도가 바뀐다`() {
        val c = car()
        c.profiles(Seats.REAR_RIGHT to SeatProfile.CHILD)
        c.seats(true, false, false, false, false)
        c.drive(recirc = false)
        assertEquals(2000, c.engine.effectiveLimitPpm)
        c.seats(true, false, false, false, true)
        assertEquals(1800, c.engine.effectiveLimitPpm)
        c.seats(true, false, false, false, false)
        assertEquals(2000, c.engine.effectiveLimitPpm)
    }

    @Test
    fun `자리별 값이 없으면 혼자일 때는 운전석 프로필만 둘 이상이면 고른 프로필을 전부 쓴다`() {
        val solo = car()
        solo.profiles(Seats.REAR_LEFT to SeatProfile.CHILD)
        solo.drive(occupants = 1, recirc = false)
        assertEquals(2000, solo.engine.effectiveLimitPpm)
        val two = car()
        two.profiles(Seats.REAR_LEFT to SeatProfile.CHILD)
        two.drive(occupants = 2, recirc = false)
        assertEquals(1800, two.engine.effectiveLimitPpm)
    }

    // ---------- 미세먼지 먼저 ----------

    @Test
    fun `호흡기 주의 자리에 사람이 타면 보통인 날에도 나쁨처럼 늦추고 짧게 바꾼다`() {
        val c = car()
        c.seats(true, true, false, false, false)
        c.profiles(Seats.FRONT_PASSENGER to SeatProfile.RESPIRATORY)
        c.pm(25.0)
        c.drive(occupants = 2)
        assertEquals(PmGrade.MODERATE, c.engine.outdoorGrade, "받은 등급은 그대로 보통이다")
        assertTrue(c.engine.care(c.now).pmFirst)
        val p = c.firstProposal()
        assertEquals("2명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 밖 미세먼지가 보통이지만 호흡기 주의 자리가 있어 1분 30초만 짧게 바꿀까요?", p.text)
        assertEquals(90, c.engine.lastVentPlan!!.durationSec)
        assertEquals(VentPlanner.REASON_PM_FIRST, c.engine.ventPlan(c.now).reason)
        assertEquals(PmGrade.BAD, c.engine.ventPlan(c.now).pmGrade, "설계는 한 단계 나쁘게 본 등급으로 한다")
        // 프로필이 없으면 같은 날 2분이다
        val plain = car()
        plain.pm(25.0)
        plain.drive(occupants = 2)
        assertEquals("2명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", plain.firstProposal().text)
    }

    @Test
    fun `심혈관 주의도 같고 받은 등급이 이미 나쁨이면 문장은 v0_7_0 그대로다`() {
        val c = car()
        c.seats(true, false, false, false, false)
        c.profiles(Seats.DRIVER to SeatProfile.CARDIO)
        c.pm(25.0)
        c.drive()
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 밖 미세먼지가 보통이지만 심혈관 주의 자리가 있어 1분만 짧게 바꿀까요?", c.firstProposal().text)

        val bad = car()
        bad.seats(true, false, false, false, false)
        bad.profiles(Seats.DRIVER to SeatProfile.CARDIO)
        bad.pm(41.0)
        bad.drive()
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 밖 미세먼지가 나쁨이라 1분만 짧게 바꿀까요?", bad.firstProposal().text)
    }

    @Test
    fun `미세먼지 먼저면 나쁨인 날을 매우 나쁨처럼 본다 한도 아래에서는 시간 상한으로 묻지 않는다`() {
        // 숨 모델이 오르지 않는 설정: 내기순환 시간 상한만 본다
        val timeOnly = real.copy(co2PerPersonLph = 0.0)
        val plain = car(timeOnly)
        plain.pm(41.0)
        plain.drive()
        plain.run(31 * 60)
        assertEquals(1, plain.count<WakeEvent.Propose>(), "나쁨이면 30분 상한으로 묻는다")
        val c = car(timeOnly)
        c.seats(true, false, false, false, false)
        c.profiles(Seats.DRIVER to SeatProfile.RESPIRATORY)
        c.pm(41.0)
        c.drive()
        c.run(60 * 60)
        assertEquals(0, c.count<WakeEvent.Propose>(), "한 단계 나쁘게 보면 매우 나쁨이라 한도에 닿을 때까지 기다린다")
    }

    @Test
    fun `바깥 값이 없으면 미세먼지 먼저 설정은 아무것도 바꾸지 않는다`() {
        fun trip(withProfile: Boolean): List<WakeEvent> {
            val c = car()
            c.seats(true, false, false, false, false)
            if (withProfile) c.profiles(Seats.DRIVER to SeatProfile.RESPIRATORY)
            c.drive()
            c.run(3000)
            return c.events
        }
        assertEquals(trip(false), trip(true))
    }

    // ---------- 둘이 같이 걸릴 때 ----------

    @Test
    fun `어린이와 호흡기 주의가 같이 타면 90퍼센트에서 묻고 짧게 바꾸고 바깥 공기로 두자고 하지 않는다`() {
        // v0.7.4 에서 인원을 4명에서 2명으로 바꿨다(운전석 호흡기 주의, 뒷줄 왼쪽 어린이). 미세먼지가 나쁜 날 3명 이상은
        // 짧게 바꿔서는 한도를 지킬 수 없어 KEEP_LOW 로 묻게 됐기 때문이다(아래 테스트). 이 테스트가 보려던 것(여유 90%, 짧게, KEEP 없음)은 그대로다
        val c = car()
        c.seats(true, false, true, false, false)
        c.profiles(Seats.REAR_LEFT to SeatProfile.CHILD, Seats.DRIVER to SeatProfile.RESPIRATORY)
        c.pm(41.0)
        c.drive(occupants = 2)
        assertEquals(1800, c.engine.effectiveLimitPpm, "미세먼지가 나빠도 어린이 자리의 여유는 남는다")
        val p = c.firstProposal()
        assertEquals("REVERT", c.engine.lastProposalVariant, "바깥 공기로 두자는 제안(KEEP)은 하지 않는다")
        assertTrue(p.text.startsWith("어린이 자리가 있어 조금 일찍 물어요. 2명이 타서 공기가 빨리 탁해져요(추정 1,800ppm). 밖 미세먼지가 나쁨이라 "), p.text)
        assertTrue(c.peakSeen < 2000)
        // "필요한 만큼만"의 목표도 낮춘 한도에서 계산한다
        assertEquals((1800 * real.pmVentTargetFraction).toInt(), c.engine.lastVentPlan!!.targetPpm)
    }

    @Test
    fun `어린이 자리가 있는 넷이 미세먼지 나쁜 날에 타면 90퍼센트에서 약한 바람으로 계속 들일지 묻는다`() {
        val c = car()
        c.seats(true, true, true, false, true)
        c.profiles(Seats.REAR_LEFT to SeatProfile.CHILD, Seats.FRONT_PASSENGER to SeatProfile.PREGNANT)
        c.pm(41.0)
        c.drive(occupants = 4)
        assertEquals(1800, c.engine.effectiveLimitPpm)
        val p = c.firstProposal()
        assertEquals("KEEP_LOW", c.engine.lastProposalVariant)
        assertTrue(p.text.startsWith("어린이 자리가 있어 조금 일찍 물어요. 넷이 타서 금방 다시 탁해져요(추정 1,800ppm). 약한 바람으로 바깥 공기를 계속 들일까요?"), p.text)
        // 동의한 뒤 2시간: 한 번만 물었고, 기본 한도(2,000) 위에 머문 시간이 없다
        repeat(12) { c.run(600); c.pm(41.0) }
        assertEquals(1, c.count<WakeEvent.Propose>())
        assertTrue(c.peakSeen < 2000, "최고 ${c.peakSeen}")
        val card = c.arrive()
        assertEquals(1, card.ventilations); assertEquals(0, card.minutesOverLimit)
        assertTrue(card.seats.all { it.minutesOverLimit == 0 })
    }

    @Test
    fun `어떤 프로필 조합과 등급과 밤낮에서도 한도는 기본 한도를 넘지 않는다`() {
        val grades = listOf(null, 9.0, 25.0, 41.0, 90.0)
        for (a in SeatProfile.entries) for (b in SeatProfile.entries) for (pm in grades) for (night in listOf(false, true)) {
            val c = Car(real.copy(longTripMinutes = 1))
            c.seats(true, true, false, false, false)
            c.profiles(Seats.DRIVER to a, Seats.FRONT_PASSENGER to b)
            if (pm != null) c.pm(pm)
            if (night) c.send { LowBeamChanged(it, true) }
            c.drive(occupants = 2, recirc = false)
            c.run(90)
            val limit = c.engine.effectiveLimitPpm
            assertTrue(limit <= real.co2LimitPpm, "$a + $b, pm=$pm, night=$night → $limit")
            val wide = SeatPolicy.wideMargin(listOf(a, b))
            val short = pm != null && VentPlanner.shortMode(SeatPolicy.policyGrade(VentPlanner.grade(pm), SeatPolicy.pmFirst(listOf(a, b))))
            val expected = when {
                night && !short -> 1600
                wide -> 1800
                else -> 2000
            }
            assertEquals(expected, limit, "$a + $b, pm=$pm, night=$night")
        }
    }

    @Test
    fun `야간 장거리의 접두와 자리 설정의 접두는 이유에 맞는 쪽 하나만 붙는다`() {
        // 밤길 장거리 + 어린이: 0.8배가 더 낮으니 밤 이유를 말한다
        val night = car(real.copy(longTripMinutes = 1))
        night.seats(true, false, true, false, false)
        night.profiles(Seats.REAR_LEFT to SeatProfile.CHILD)
        night.send { LowBeamChanged(it, true) }
        night.drive(occupants = 2)
        assertEquals(1600, night.engine.effectiveLimitPpm.also { night.run(61) }.let { night.engine.effectiveLimitPpm })
        assertTrue(night.firstProposal().text.startsWith(WakeEngine.NIGHT_PREFIX))
        // 밤길 장거리 + 어린이 + 미세먼지 나쁨: 밤의 여유는 없어지고 자리 설정의 여유만 남는다
        val both = car(real.copy(longTripMinutes = 1))
        both.seats(true, false, true, false, false)
        both.profiles(Seats.REAR_LEFT to SeatProfile.CHILD)
        both.send { LowBeamChanged(it, true) }
        both.pm(41.0)
        both.drive(occupants = 2)
        both.run(61)
        assertEquals(1800, both.engine.effectiveLimitPpm)
        assertTrue(both.firstProposal().text.startsWith("어린이 자리가 있어 조금 일찍 물어요. "))
    }

    // ---------- 앉은 시간 ----------

    @Test
    fun `운전석은 달리기 시작하면 앉은 것으로 세고 좌석 값은 쓰지 않는다`() {
        val c = car()
        // 에뮬레이터 기본값: 운전석 없음, 나머지 있음
        c.seats(false, true, true, true, true)
        c.drive(occupants = 5, recirc = false)
        c.run(30 * 60)
        val care = c.engine.care(c.now)
        assertEquals(30, care.sitMinutes)
        assertEquals(Seats.DRIVER, care.sitSeat)
    }

    @Test
    fun `자리가 2분 이상 비었다 다시 타면 그 자리만 0 부터 센다`() {
        val c = car()
        c.seats(true, true, false, false, false)
        c.drive(occupants = 2, recirc = false)
        c.run(40 * 60)
        c.seats(true, false, false, false, false)
        c.run(3 * 60)
        c.seats(true, true, false, false, false)
        c.run(10 * 60)
        val card = c.arrive()
        val (driver, passenger) = card.seats
        assertEquals(53, driver.longestSitMinutes)
        assertEquals(40, passenger.longestSitMinutes, "내렸다 탄 뒤의 10분보다 앞의 40분이 길다")
    }

    @Test
    fun `기어 P 로 잠깐 섰다 다시 출발하면 앉은 시간이 이어진다`() {
        val c = car()
        c.drive(recirc = false)
        c.run(50 * 60)
        c.arrive()
        c.run(3 * 60)
        c.gear(Gear.DRIVE)
        c.run(7 * 60)
        assertEquals(60, c.engine.care(c.now).sitMinutes, "3분 정차는 일어선 것이 아니다")
    }

    @Test
    fun `문 신호가 없는 차는 기어 P 로 5분 이상 서 있었으면 일어선 것으로 본다`() {
        val c = car()
        c.drive(recirc = false)
        c.run(50 * 60)
        c.arrive()
        c.run(5 * 60)
        c.gear(Gear.DRIVE)
        c.run(7 * 60)
        assertEquals(7, c.engine.care(c.now).sitMinutes)
    }

    @Test
    fun `문 신호가 있는 차는 서 있는 동안 문이 열린 적이 있을 때만 일어선 것으로 본다`() {
        // 문을 열지 않았다: 차 안에서 쉰 것이다
        val stay = car()
        stay.door(false)
        stay.drive(recirc = false)
        stay.run(50 * 60)
        stay.arrive()
        stay.run(10 * 60)
        stay.gear(Gear.DRIVE)
        stay.run(60)
        assertEquals(61, stay.engine.care(stay.now).sitMinutes)
        // 문을 열고 닫았다
        val out = car()
        out.door(false)
        out.drive(recirc = false)
        out.run(50 * 60)
        out.arrive()
        out.run(60); out.door(true); out.run(30); out.door(false)
        out.run(5 * 60)
        out.gear(Gear.DRIVE)
        out.run(60)
        assertEquals(1, out.engine.care(out.now).sitMinutes)
        // 문을 연 채로 P 에 넣고 닫은 것도 열렸다 닫힌 것이다
        val early = car()
        early.drive(recirc = false)
        early.run(50 * 60)
        early.door(true)
        early.arrive()
        early.door(false)
        early.run(6 * 60)
        early.gear(Gear.DRIVE)
        early.run(60)
        assertEquals(1, early.engine.care(early.now).sitMinutes)
    }

    @Test
    fun `차가 알려 준 주행 시간만큼은 운전석에 앉아 있었다고 본다`() {
        val c = car()
        c.drive(recirc = false)
        c.run(5 * 60)
        c.send { TripTimeUpdate(it, 70) }
        assertEquals(70, c.engine.care(c.now).sitMinutes)
        c.run(10 * 60)
        assertEquals(80, c.engine.care(c.now).sitMinutes)
    }

    // ---------- 걷기 쉼 ----------

    @Test
    fun `두 시간 앉아 있으면 쉼터에서 내려 걷기를 묻고 동의하면 경유지만 넣고 끝낸다`() {
        val c = car(walk, answer = null)
        c.candidate()
        c.drive(recirc = false)
        c.run(119 * 60)
        assertEquals(0, c.count<WakeEvent.Propose>())
        assertEquals(120, c.engine.care(c.now).breakMinutes)
        val p = c.firstProposal(120)
        assertEquals(TriggerKind.WALK_BREAK, p.kind)
        assertEquals("두 시간째 앉아 있어요. 12km 앞 경부선 석적 졸음쉼터에서 내려서 5분 걸을까요?", p.text)
        assertEquals(listOf<Action>(Action.AddRestStop(stop)), p.actions)
        assertEquals("WALK_BREAK", c.engine.lastProposalVariant)
        c.run(4)
        val got = c.yes()
        assertEquals(listOf(WakeEvent.Execute(TriggerKind.WALK_BREAK, listOf(Action.AddRestStop(stop))), WakeEvent.Done(TriggerKind.WALK_BREAK, WakeEngine.WALK_ADDED_TEXT)), got)
        assertEquals(stop, c.engine.currentWaypoint)
        assertEquals("IDLE", c.engine.phaseName)
        // 경유지가 있는 동안은 다시 묻지 않는다
        c.run(60 * 60)
        assertEquals(1, c.proposalsOf(TriggerKind.WALK_BREAK).size)
        assertEquals(0, c.events.count { it is WakeEvent.Execute && it.actions == listOf<Action>(Action.Revert) }, "차의 공조를 건드리지 않았으니 원복도 없다")
    }

    @Test
    fun `어르신 자리에 사람이 타면 90분에 묻는다`() {
        val c = car(walk, answer = null)
        c.seats(true, false, false, false, true)
        c.profiles(Seats.REAR_RIGHT to SeatProfile.SENIOR)
        c.candidate()
        c.drive(occupants = 2, recirc = false)
        assertEquals(90, c.engine.care(c.now).breakMinutes)
        c.run(89 * 60)
        assertEquals(0, c.count<WakeEvent.Propose>())
        assertEquals("한 시간 반째 앉아 있어요. 12km 앞 경부선 석적 졸음쉼터에서 내려서 5분 걸을까요?", c.firstProposal(120).text)
    }

    @Test
    fun `쉼터가 없으면 묻지 않고 기다리다가 쉼터가 보이면 묻는다 그동안 환기 제안은 막지 않는다`() {
        val c = car(walk, answer = true)
        c.drive()
        c.run(130 * 60)
        assertEquals(emptyList(), c.proposalsOf(TriggerKind.WALK_BREAK))
        assertTrue(c.proposalsOf(TriggerKind.STALE_AIR).size >= 3, "환기 제안은 그대로 나온다")
        c.candidate()
        c.run(10 * 60)
        assertEquals(1, c.proposalsOf(TriggerKind.WALK_BREAK).size)
        assertTrue(c.proposalsOf(TriggerKind.WALK_BREAK).single().text.startsWith("두 시간째 앉아 있어요."))
    }

    @Test
    fun `목적지가 쉼터보다 가까우면 묻지 않는다`() {
        val c = car(walk, answer = null)
        c.candidate()
        c.drive(recirc = false)
        c.run(115 * 60)
        repeat(20) { c.send { DestinationEta(it, 4) }; c.run(60) }
        assertEquals(emptyList(), c.proposalsOf(TriggerKind.WALK_BREAK))
    }

    @Test
    fun `거절하면 20분 뒤에 한 번 더 묻고 그 뒤로는 묻지 않는다`() {
        val c = car(walk, answer = false)
        c.candidate()
        c.drive(recirc = false)
        c.run(121 * 60)
        assertEquals(1, c.proposalsOf(TriggerKind.WALK_BREAK).size)
        assertEquals("알겠어요. 20분 뒤에 다시 볼게요.", c.last<WakeEvent.Done>()!!.text)
        c.run(19 * 60)
        assertEquals(1, c.proposalsOf(TriggerKind.WALK_BREAK).size)
        c.run(3 * 60)
        assertEquals(2, c.proposalsOf(TriggerKind.WALK_BREAK).size)
        c.run(120 * 60)
        assertEquals(2, c.proposalsOf(TriggerKind.WALK_BREAK).size, "한 번 앉은 동안 두 번까지만")
        assertEquals(2, c.arrive().declined)
    }

    @Test
    fun `경유지를 넣지 못하면 실패를 말하고 조금 뒤 다시 본다`() {
        val c = car(walk, answer = null)
        c.autoResult = false
        c.candidate()
        c.drive(recirc = false)
        c.firstProposal(121 * 60)
        c.run(4)
        c.yes()
        val got = c.send { ActionResult(it, Action.AddRestStop(stop), ok = false, detail = "NAVI") }
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.WALK_BREAK, WakeEngine.WALK_FAILED_TEXT)), got)
        assertNull(c.engine.currentWaypoint)
        c.run(4 * 60)
        assertEquals(1, c.proposalsOf(TriggerKind.WALK_BREAK).size)
        c.run(2 * 60)
        assertEquals(2, c.proposalsOf(TriggerKind.WALK_BREAK).size)
    }

    @Test
    fun `일어섰다 다시 앉으면 쉬는 간격을 처음부터 다시 센다`() {
        val c = car(walk, answer = false)
        c.candidate()
        c.drive(recirc = false)
        c.run(121 * 60)
        assertEquals(1, c.proposalsOf(TriggerKind.WALK_BREAK).size)
        c.arrive()
        c.run(6 * 60)
        c.gear(Gear.DRIVE)
        c.candidate()
        c.run(119 * 60)
        assertEquals(1, c.proposalsOf(TriggerKind.WALK_BREAK).size, "새로 앉은 뒤 119분에는 아직 묻지 않는다")
        c.run(3 * 60)
        assertEquals(2, c.proposalsOf(TriggerKind.WALK_BREAK).size)
    }

    // ---------- 졸음 제안과 합치기 ----------

    @Test
    fun `쉬는 간격에 닿은 채 졸음 경고가 오면 한 번에 묻고 걷기 쉼은 따로 묻지 않는다`() {
        // 쉼터가 아직 안 보여서 걷기 쉼을 묻지 못한 채 두 시간이 지났다. 깜빡이를 켠 동안(말하지 않는 때) 쉼터가 보이고 졸음 경고가 왔다
        val c = car(walk, answer = null)
        c.drive(recirc = false)
        c.run(125 * 60)
        c.send { TurnSignalChanged(it, TurnSignalState.LEFT) }
        c.candidate()
        c.telltale(true)
        assertEquals(0, c.count<WakeEvent.Propose>())
        val p = c.send { TurnSignalChanged(it, TurnSignalState.OFF) }.filterIsInstance<WakeEvent.Propose>().single()
        assertEquals(TriggerKind.DROWSY, p.kind)
        assertEquals("졸음 경고가 떴어요. 두 시간째 앉아 있기도 해요. 찬 바람 넣고, 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?", p.text)
        c.run(4)
        c.yes()
        c.run(10 * 60)
        assertEquals(emptyList(), c.proposalsOf(TriggerKind.WALK_BREAK))
        assertEquals(1, c.count<WakeEvent.Propose>())
    }

    @Test
    fun `쉬는 간격에 닿지 않았으면 졸음 제안 문장은 그대로다`() {
        val c = car(walk, answer = null)
        c.candidate()
        c.drive(recirc = false)
        c.run(30 * 60)
        val p = c.telltale(true).filterIsInstance<WakeEvent.Propose>().single()
        assertEquals("졸음 경고가 떴어요. 찬 바람 넣고, 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?", p.text)
    }

    @Test
    fun `걷기 쉼을 묻던 중에 졸음 경고가 오면 그 질문을 닫고 합친 제안을 바로 낸다`() {
        val c = car(walk, answer = null)
        c.candidate()
        c.drive(recirc = false)
        assertEquals(TriggerKind.WALK_BREAK, c.firstProposal(121 * 60).kind)
        c.run(4)
        val got = c.telltale(true)
        assertEquals(WakeEvent.Done(TriggerKind.WALK_BREAK, null), got.first())
        val p = got.filterIsInstance<WakeEvent.Propose>().single()
        assertEquals(TriggerKind.DROWSY, p.kind)
        assertTrue(p.text.startsWith("졸음 경고가 떴어요. 두 시간째 앉아 있기도 해요. "), p.text)
        // 합친 제안을 거절하면 걷기 쉼도 같은 시간 동안 다시 꺼내지 않는다
        c.run(4)
        c.no()
        c.run(19 * 60)
        assertEquals(1, c.proposalsOf(TriggerKind.WALK_BREAK).size)
    }

    @Test
    fun `졸음 제안으로 쉼터를 이미 넣었으면 걷기 쉼은 묻지 않는다`() {
        val c = car(walk, answer = true)
        c.candidate()
        c.drive(recirc = false)
        c.run(100 * 60)
        c.telltale(true)
        c.run(10 * 60)
        assertEquals(stop, c.engine.currentWaypoint)
        c.run(40 * 60)
        assertEquals(emptyList(), c.proposalsOf(TriggerKind.WALK_BREAK))
    }

    // ---------- 걷기 쉼 기록과 쉼 카드 ----------

    private fun Car.toWalkStop() {
        candidate()
        drive(recirc = false)
        assertEquals(TriggerKind.WALK_BREAK, firstProposal(121 * 60).kind)
        run(4); yes()
        send { RestStopProximity(it, within = true) }
    }

    @Test
    fun `걷기 쉼으로 넣은 쉼터에서는 타이머가 5분이고 내려서 걷자고 안내한다`() {
        val c = car(walk, answer = null)
        c.toWalkStop()
        val started = c.gear(Gear.PARK).filterIsInstance<WakeEvent.RestStarted>().single()
        assertEquals(WakeEvent.RestStarted("석적 졸음쉼터", 5, walk = true), started)
        val nap = assertNotNull(c.engine.breath(c.now).nap)
        assertEquals("오래 앉아 있었어요. 차에서 내려 5분쯤 걸어요.", nap.text)
        assertEquals(300, nap.remainingSec)
        c.run(300)
        assertTrue(c.engine.breath(c.now).nap!!.done)
        assertEquals("5분 지났어요. 다 걸었으면 출발해요.", c.engine.breath(c.now).nap!!.doneText)
    }

    @Test
    fun `쉼터에서 5분 넘게 서 있었으면 걷기 쉼 한 번으로 적고 앉은 시간을 다시 센다`() {
        val c = car(walk, answer = null)
        c.seats(true, true, false, false, false)
        c.toWalkStop()
        c.gear(Gear.PARK)
        c.run(6 * 60)
        val ended = c.gear(Gear.DRIVE).filterIsInstance<WakeEvent.RestEnded>().single()
        assertEquals("6분 푹 쉬셨어요. 조심히 가요", ended.text)
        c.run(10 * 60)
        assertEquals(10, c.engine.care(c.now).sitMinutes)
        val card = c.arrive()
        assertEquals(1, card.walkBreaks)
        assertEquals(listOf(1, 1), card.seats.map { it.walkBreaks })
        assertEquals(1, card.cabin!!.walkBreaks)
        assertEquals(120, card.seats.first().longestSitMinutes)
    }

    @Test
    fun `쉼터에서 5분이 안 되게 섰거나 문을 열지 않았으면 걷기 쉼으로 적지 않는다`() {
        val short = car(walk, answer = null)
        short.toWalkStop()
        short.gear(Gear.PARK)
        short.run(4 * 60)
        short.gear(Gear.DRIVE)
        assertEquals(0, short.arrive().walkBreaks)

        val stayed = car(walk, answer = null)
        stayed.door(false)
        stayed.toWalkStop()
        stayed.gear(Gear.PARK)
        stayed.run(8 * 60)
        stayed.gear(Gear.DRIVE)
        assertEquals(128, stayed.engine.care(stayed.now).sitMinutes, "차 안에 있었으니 앉은 시간이 이어진다")
        assertEquals(0, stayed.arrive().walkBreaks)
    }

    @Test
    fun `졸음 제안으로 넣은 쉼터의 쉼 카드는 그대로 15분이다`() {
        val c = car(walk, answer = true)
        c.candidate()
        c.drive(recirc = false)
        c.run(10 * 60)
        c.telltale(true)
        c.run(10 * 60)
        c.send { RestStopProximity(it, within = true) }
        val started = c.gear(Gear.PARK).filterIsInstance<WakeEvent.RestStarted>().single()
        assertEquals(WakeEvent.RestStarted("석적 졸음쉼터", 15), started)
        assertEquals(WakeEngine.napTip(), c.engine.breath(c.now).nap!!.text)
    }

    @Test
    fun `직접 요청하면 묻지 않고 전방 쉼터를 경유지로 넣고 그 쉼은 보통 쉼이다`() {
        val c = car(walk, answer = null)
        c.drive(recirc = false)
        c.run(10 * 60)
        assertEquals(emptyList(), c.feed(c.engine.requestRestStop(c.now)), "쉼터 후보가 없으면 아무것도 하지 않는다")
        assertNull(c.engine.requestableRestStop)
        c.candidate()
        assertEquals(stop, c.engine.requestableRestStop)
        val got = c.feed(c.engine.requestRestStop(c.now))
        assertEquals(listOf(WakeEvent.Execute(TriggerKind.WALK_BREAK, listOf(Action.AddRestStop(stop))), WakeEvent.Done(TriggerKind.WALK_BREAK, WakeEngine.REST_STOP_ADDED_TEXT)), got)
        assertEquals(stop, c.engine.currentWaypoint)
        assertFalse(c.engine.cancellableRun)
        // 이미 경유지에 있으면 다시 넣지 않는다
        assertEquals(emptyList(), c.feed(c.engine.requestRestStop(c.now)))
        c.send { RestStopProximity(it, within = true) }
        assertEquals(WakeEvent.RestStarted("석적 졸음쉼터", 15), c.gear(Gear.PARK).filterIsInstance<WakeEvent.RestStarted>().single())
        // 주행 중이 아니면 아무것도 하지 않는다
        val idle = car(walk)
        idle.candidate()
        assertEquals(emptyList(), idle.engine.requestRestStop(idle.now))
    }

    // ---------- 자리별 누적 ----------

    @Test
    fun `도착 요약에 탄 자리마다 한 줄이 실리고 중간에 탄 사람은 탄 시점부터 센다`() {
        val c = car()
        c.seats(true, false, false, false, false)
        c.profiles(Seats.REAR_LEFT to SeatProfile.CHILD, Seats.FRONT_PASSENGER to SeatProfile.PREGNANT)
        c.pm(41.0)
        c.drive(occupants = 1)
        c.run(30 * 60)
        // 30분 뒤 둘이 더 탔다
        c.seats(true, true, true, false, false)
        c.send { OccupantsChanged(it, 3) }
        c.run(40 * 60)
        val card = c.arrive()
        assertEquals("오늘 1시간 10분, 셋이 탔어요.", card.seatHeadline)
        assertEquals(listOf(Seats.DRIVER, Seats.FRONT_PASSENGER, Seats.REAR_LEFT), card.seats.map { it.seat })
        val (driver, front, rear) = card.seats
        assertEquals(listOf(D, SeatProfile.PREGNANT, SeatProfile.CHILD), card.seats.map { it.profile })
        assertEquals(70, driver.minutesAboard)
        assertEquals(40, front.minutesAboard); assertEquals(40, rear.minutesAboard)
        assertFalse(driver.boardedMidTrip); assertTrue(front.boardedMidTrip); assertTrue(rear.boardedMidTrip)
        assertEquals(70, driver.longestSitMinutes); assertEquals(40, front.longestSitMinutes)
        // 운전석은 주행 전체, 차 전체 줄과 같은 값이다
        assertEquals(card.minutesOverLimit, driver.minutesOverLimit)
        assertEquals(card.cabin!!.averagePpm, driver.averagePpm)
        assertTrue(driver.averagePpm in 700..1900, "평균은 처음 값과 한도 사이다: ${driver.averagePpm}")
        // 미세먼지 평균: 운전석은 세 갈래 비교의 "이번 주행"과 같다
        assertEquals(card.threeWay!!.actual.pmAverage!!, driver.pmAverage!!, 1e-6)
        assertNotNull(front.pmAverage)
        assertTrue(front.pmExposure!! < driver.pmExposure!!, "늦게 탄 자리는 노출량이 적다")
        assertNull(card.cabin!!.pmAverage, "차 전체의 미세먼지는 세 갈래 비교에 있다")
    }

    @Test
    fun `자리별 값이 없는 주행은 차 전체 한 줄만 싣는다`() {
        val c = car()
        c.drive(occupants = 4)
        c.run(45 * 60)
        val card = c.arrive()
        assertEquals(emptyList(), card.seats)
        val cabin = assertNotNull(card.cabin)
        assertEquals(Seats.CABIN, cabin.seat)
        assertEquals(45, cabin.minutesAboard)
        assertEquals(45, cabin.longestSitMinutes)
        assertEquals(card.minutesOverLimit, cabin.minutesOverLimit)
        assertEquals("오늘 45분, 넷이 탔어요.", card.seatHeadline)
    }

    @Test
    fun `새 주행이면 누적을 비우고 요약 제목은 길이에 맞게 읽는다`() {
        val c = car()
        c.seats(true, true, false, false, false)
        c.drive(occupants = 2, recirc = false)
        c.run(120 * 60)
        assertEquals("오늘 2시간, 둘이 탔어요.", c.arrive().seatHeadline)
        c.seats(true, false, false, false, false)
        c.send { OccupantsChanged(it, 1) }
        c.gear(Gear.DRIVE)
        c.run(20)
        val card = c.arrive()
        assertEquals("오늘 잠깐, 혼자 탔어요.", card.seatHeadline)
        assertEquals(listOf(Seats.DRIVER), card.seats.map { it.seat })
        assertEquals(0, card.walkBreaks)
    }

    @Test
    fun `데모 속도에서는 쉬는 간격과 앉은 시간도 초로 흐른다`() {
        val c = car(demo.copy(walkBreak = true), answer = null)
        c.candidate()
        c.drive(recirc = false)
        c.run(119)
        assertEquals(0, c.count<WakeEvent.Propose>())
        assertEquals(119, c.engine.care(c.now).sitMinutes)
        assertEquals(TriggerKind.WALK_BREAK, c.firstProposal(5).kind)
    }
}

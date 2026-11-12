package ai.kkaeugi.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 숨 모델과 핸들 신호가 엔진에 붙은 경로를 본다.
 * 숨 모델 쪽은 실제 시간 기본 설정(WakeConfig())으로 1초 tick 을 돌린다. 핸들 쪽은 데모 설정(분을 초로)에 CO2 생성 0.
 * 하네스는 차를 흉내 내지 않는다(환기를 실행해도 풍량이나 내기순환 신호를 돌려보내지 않는다). 차까지 흉내 내는 경로는 Round5Test 가 본다.
 */
class AirEngineTest {

    private val real = WakeConfig(steeringAssist = true, allowWindow = true)
    private val steerDemo = WakeConfig(demoFast = true, co2PerPersonLph = 0.0, declineCooldownMinutes = 20, steeringAssist = true, allowWindow = true)
    private val stop = RestStopInfo("망향졸음쉼터", "경부선 서울방향", 12.3, 36.9, 127.1)

    private class Harness(config: WakeConfig, start: Long = 1_000_000L) {
        val engine = WakeEngine(config)
        var now = start
        /** AirEstimate 를 뺀 이벤트 */
        val events = mutableListOf<WakeEvent>()
        val airs = mutableListOf<WakeEvent.AirEstimate>()

        private fun split(all: List<WakeEvent>): List<WakeEvent> {
            airs += all.filterIsInstance<WakeEvent.AirEstimate>()
            return all.filter { it !is WakeEvent.AirEstimate }
        }

        fun send(make: (Long) -> Signal): List<WakeEvent> = split(engine.accept(make(now))).also { events += it }

        fun run(seconds: Int): List<WakeEvent> {
            val got = mutableListOf<WakeEvent>()
            repeat(seconds) {
                now += 1_000
                got += split(engine.tick(now))
            }
            events += got
            return got
        }

        /** T 이벤트가 나올 때까지 1초씩 돌리고 걸린 초를 돌려준다. max 초 안에 없으면 null */
        inline fun <reified T : WakeEvent> runUntil(max: Int): Int? {
            for (s in 1..max) if (run(1).any { it is T }) return s
            return null
        }

        fun drive(occupants: Int = 1, recirc: Boolean = true) {
            send { OccupantsChanged(it, occupants) }
            send { RecirculationChanged(it, recirc) }
            send { GearChanged(it, Gear.DRIVE) }
        }

        /** 제안에 "응" 하고 환기 성공까지. 첫 Running 을 돌려준다 */
        fun acceptVentilation(): WakeEvent.Running {
            send { UserAnswer(it, true) }
            return send { ActionResult(it, Action.Ventilate(), ok = true) }.filterIsInstance<WakeEvent.Running>().single()
        }

        /** 원복 지시가 나올 때까지 돌리고 원복 성공을 알린다. Done 문장을 돌려준다 */
        fun finishVentilation(max: Int = 600): String? {
            assertNotNull(runUntil<WakeEvent.Execute>(max), "원복 지시가 나와야 한다")
            assertEquals(listOf<Action>(Action.Revert), last<WakeEvent.Execute>()!!.actions)
            return send { ActionResult(it, Action.Revert, ok = true) }.filterIsInstance<WakeEvent.Done>().single().text
        }

        fun arrive(): ArrivalSummary =
            send { GearChanged(it, Gear.PARK) }.filterIsInstance<WakeEvent.ArrivalCard>().single().card

        inline fun <reified T : WakeEvent> last(): T? = events.filterIsInstance<T>().lastOrNull()
        inline fun <reified T : WakeEvent> count(): Int = events.count { it is T }
    }

    // ---------- 기대값 계산(설정값에서 해석해로). 숫자를 박아 두지 않는다 ----------

    private val startPpm = real.outdoorPpm + CabinAirModel.START_OFFSET_PPM
    private val limit = real.co2LimitPpm.toDouble()

    /** 정상상태 ppm: 바깥 + 생성량 / 교환 */
    private fun steady(n: Int, ach: Double): Double =
        real.outdoorPpm + n * real.co2PerPersonLph * 1e6 / real.cabinVolumeL / ach

    /** 해석해: c0 에서 교환 ach 로 hours 뒤의 ppm */
    private fun solve(c0: Double, n: Int, ach: Double, hours: Double): Double {
        val css = steady(n, ach)
        return css + (c0 - css) * exp(-ach * hours)
    }

    /** 외기 모드의 교환(회/시간). 팬 1단이 FAN_MIN_FRACTION, 최대 단이 1.0 인 선형 */
    private fun freshAch(level: Int, max: Int): Double =
        real.leakAch + real.freshAchMax *
            (CabinAirModel.FAN_MIN_FRACTION + (1.0 - CabinAirModel.FAN_MIN_FRACTION) * (level - 1) / (max - 1))

    /** 내기순환에서 from → 한도까지 걸리는 초 */
    private fun secondsToLimit(n: Int, from: Double = startPpm): Double {
        val css = steady(n, real.leakAch)
        return ln((css - from) / (css - limit)) / real.leakAch * 3600.0
    }

    /** 외기에서 from → target 아래로 내려가기까지 걸리는 초 */
    private fun secondsDownTo(from: Double, target: Double, n: Int, ach: Double): Double {
        val css = steady(n, ach)
        return ln((from - css) / (target - css)) / ach * 3600.0
    }

    private fun assertNear(expected: Double, actual: Double, tol: Double) =
        assertTrue(abs(expected - actual) <= tol, "기대 $expected, 실제 $actual")

    // ---------- 인원별 트리거 시점과 문장 ----------

    @Test
    fun `1명 내기순환은 약 20분에 추정치로 환기를 제안한다`() {
        val h = Harness(real)
        h.drive(occupants = 1)
        val sec = h.runUntil<WakeEvent.Propose>(1800)
        assertEquals(ceil(secondsToLimit(1)).toInt(), sec) // 약 1210초
        assertTrue(sec!! in 19 * 60..21 * 60, "약 20분: $sec 초")
        assertTrue(sec < real.staleAirMinutes * 60, "시간 상한 30분보다 먼저")
        val p = h.last<WakeEvent.Propose>()!!
        assertEquals(TriggerKind.STALE_AIR, p.kind)
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", p.text)
        assertEquals(listOf<Action>(Action.Ventilate()), p.actions)
        assertEquals("REVERT", h.engine.lastProposalVariant)
    }

    @Test
    fun `2명은 약 8분 4명은 약 4분에 제안하고 문장에 인원이 들어간다`() {
        // 풍량 3/8: 2명은 2분 환기 뒤 다시 닿기까지 약 6분이라 "2분만", 4명은 약 2분이라 처음부터 바깥 공기로 둘지 묻는다
        val two = Harness(real)
        two.send { FanSpeedChanged(it, 3, 8) }
        two.drive(occupants = 2)
        val twoSec = two.runUntil<WakeEvent.Propose>(1800)
        assertEquals(ceil(secondsToLimit(2)).toInt(), twoSec) // 약 486초
        assertTrue(twoSec!! in 7 * 60 + 30..8 * 60 + 30, "약 8분: $twoSec 초")
        assertEquals("2명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", two.last<WakeEvent.Propose>()!!.text)
        assertEquals("REVERT", two.engine.lastProposalVariant)

        val four = Harness(real)
        four.send { FanSpeedChanged(it, 3, 8) }
        four.drive(occupants = 4)
        val sec = four.runUntil<WakeEvent.Propose>(1800)
        assertEquals(ceil(secondsToLimit(4)).toInt(), sec) // 약 223초
        assertTrue(sec!! < 4 * 60)
        assertEquals("4명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?", four.last<WakeEvent.Propose>()!!.text)
        assertEquals("KEEP", four.engine.lastProposalVariant)
    }

    @Test
    fun `외기인데 풍량이 0 이라 한도를 넘으면 풍량을 올릴지 묻는다`() {
        // 4명, 외기, 풍량 0/8(주행풍만): 정상상태 약 2,600ppm 으로 한도 위
        val h = Harness(real)
        h.send { FanSpeedChanged(it, 0, 8) }
        h.drive(occupants = 4, recirc = false)
        val offAch = real.leakAch + real.freshAchMax * CabinAirModel.FAN_OFF_FRACTION
        val css = steady(4, offAch)
        assertTrue(css > limit, "정상상태 $css")
        val expected = ln((css - startPpm) / (css - limit)) / offAch * 3600.0 // 약 6분 반
        val sec = h.runUntil<WakeEvent.Propose>(3600)
        assertEquals(ceil(expected).toInt(), sec)
        val p = h.last<WakeEvent.Propose>()!!
        assertEquals(TriggerKind.STALE_AIR, p.kind)
        assertEquals("바깥 공기인데 바람이 약해요(추정 2,000ppm). 풍량을 올릴까요?", p.text)
        assertEquals(listOf<Action>(Action.Ventilate(fanDelta = 2)), p.actions)
        assertEquals("FAN_UP", h.engine.lastProposalVariant)
        assertNull(h.engine.lastRecurrenceMinutes)
    }

    @Test
    fun `4명이 외기 팬 1단으로 달리면 정상상태가 한도 아래라 환기 제안이 없다`() {
        val h = Harness(real)
        h.send { FanSpeedChanged(it, 1, 8) }
        h.drive(occupants = 4, recirc = false)
        h.run(3600)
        // 1시간이면 정상상태(약 1,320ppm)에 붙는다
        val css = steady(4, freshAch(1, 8))
        assertTrue(css < limit, "정상상태 $css")
        assertNear(css, h.engine.estimatedPpm.toDouble(), 1.0)
        assertEquals(0, h.count<WakeEvent.Propose>())
        assertEquals(0, h.count<WakeEvent.Deferred>())
        assertTrue(h.airs.all { it.ventilating && it.minutesToLimit == null })
        val card = h.arrive()
        assertEquals(0, card.minutesOverLimit)
        assertTrue(card.peakPpm < real.co2LimitPpm)
    }

    @Test
    fun `추정치 한도를 아주 높게 두면 시간 상한에서 기존 문장으로 제안한다`() {
        val h = Harness(real.copy(co2LimitPpm = 1_000_000))
        h.drive(occupants = 1)
        assertEquals(30 * 60, h.runUntil<WakeEvent.Propose>(3600))
        assertEquals("내기순환으로 30분째예요. 2분만 바깥 공기로 바꿀까요?", h.last<WakeEvent.Propose>()!!.text)
        assertEquals("TIME_CAP", h.engine.lastProposalVariant)
    }

    @Test
    fun `야간 장거리면 한도의 0,8배에서 묻고 추정치 경로에도 밤길 접두가 붙는다`() {
        val h = Harness(real.copy(longTripMinutes = 5))
        h.send { LowBeamChanged(it, true) }
        h.drive(occupants = 2)
        val sec = h.runUntil<WakeEvent.Propose>(1800)!!
        // 2명이 1,600ppm 에 닿는 시각(약 5분 반). 낮이었으면 약 8분
        val nightLimit = limit * WakeEngine.NIGHT_LIMIT_FACTOR
        val css = steady(2, real.leakAch)
        assertEquals(ceil(ln((css - startPpm) / (css - nightLimit)) / real.leakAch * 3600.0).toInt(), sec)
        assertTrue(sec < secondsToLimit(2))
        assertEquals(1600, h.engine.effectiveLimitPpm)
        assertEquals(
            "오래 달려서 조금 일찍 물어요. 2명이 타서 공기가 빨리 탁해져요(추정 1,600ppm). 바깥 공기로 바꿔 둘까요?",
            h.last<WakeEvent.Propose>()!!.text,
        )
    }

    @Test
    fun `말하기 전에 창문을 열어 공기가 나아지면 대기 중인 제안을 버린다`() {
        val h = Harness(real)
        h.drive(occupants = 4)
        h.send { NavTurnAhead(it, TurnDir.LEFT, 50.0) } // 회전 직전이라 말하지 못한다
        h.run(300)
        assertEquals(TriggerKind.STALE_AIR, h.last<WakeEvent.Deferred>()!!.kind)
        h.send { WindowOpenChanged(it, 45) }
        h.run(30)
        assertTrue(h.engine.estimatedPpm < 2000)
        h.send { NavTurnAhead(it, TurnDir.STRAIGHT, 500.0) }
        h.run(30)
        assertEquals(0, h.count<WakeEvent.Propose>())
    }

    // ---------- 환기 유지 시간과 결과 문장 ----------

    @Test
    fun `풍량이 충분하면 기본 2분만 유지하고 결과 수치를 말한다`() {
        val h = Harness(real)
        h.send { FanSpeedChanged(it, 8, 8) }
        h.drive(occupants = 1)
        assertNotNull(h.runUntil<WakeEvent.Propose>(1800))
        val running = h.acceptVentilation()
        assertEquals(WakeEvent.Running(TriggerKind.STALE_AIR, 120, "바깥 공기로 바꾸는 중"), running)
        val before = h.now
        val text = h.finishVentilation()
        assertEquals(120_000L, h.now - before)
        // 한도에서 최대 풍량으로 2분: 식으로는 약 713ppm, 문장은 100 단위
        assertNear(solve(limit, 1, freshAch(8, 8), 2.0 / 60), h.engine.estimatedPpm.toDouble(), 5.0)
        assertEquals("추정 700ppm까지 내려갔어요. 원래대로 돌렸어요.", text)
    }

    @Test
    fun `풍량이 약해 2분 뒤에도 1000ppm 위여도 말한 2분만 유지하고 원복한다`() {
        val h = Harness(real)
        // 팬 1단(약 27회/시간)이면 2분으로는 1,000 아래로 못 내려간다. 그래도 늘리지 않는다
        h.send { FanSpeedChanged(it, 1, 8) }
        h.drive(occupants = 1)
        assertNotNull(h.runUntil<WakeEvent.Propose>(1800))
        val running = h.acceptVentilation()
        assertEquals(WakeEvent.Running(TriggerKind.STALE_AIR, 120, "바깥 공기로 바꾸는 중"), running)
        val before = h.now
        // 남은 시간은 1초에 1씩 줄기만 한다
        val remaining = h.run(119).filterIsInstance<WakeEvent.Running>().map { it.remainingSec }
        assertEquals((119 downTo 1).toList(), remaining)
        val text = h.finishVentilation()
        assertEquals(120_000L, h.now - before)
        val after = solve(limit, 1, freshAch(1, 8), 2.0 / 60) // 약 1,200ppm
        assertTrue(after > 1000, "2분 뒤 $after")
        assertNear(after, h.engine.estimatedPpm.toDouble(), 5.0)
        assertEquals("추정 1,200ppm까지 내려갔어요. 원래대로 돌렸어요.", text)
    }

    @Test
    fun `풍량을 몰라도 2분 뒤에 원복한다`() {
        // 풍량 모름(주행풍만): 2분으로는 많이 못 내려간다. 그래도 말한 2분에서 끝낸다
        val h = Harness(real)
        h.drive(occupants = 1)
        assertNotNull(h.runUntil<WakeEvent.Propose>(1800))
        val running = h.acceptVentilation()
        assertEquals(120, running.remainingSec)
        val before = h.now
        val text = h.finishVentilation()
        assertEquals(120_000L, h.now - before)
        val unknownAch = real.leakAch + real.freshAchMax * CabinAirModel.FAN_OFF_FRACTION
        assertNear(solve(limit, 1, unknownAch, 2.0 / 60), h.engine.estimatedPpm.toDouble(), 5.0)
        assertEquals("추정 1,700ppm까지 내려갔어요. 원래대로 돌렸어요.", text)
    }

    @Test
    fun `환기를 끝낸 직후에는 추정치가 높아도 5분 동안 다시 묻지 않는다`() {
        // 4명, 풍량 모름: 처음에는 바깥 공기로 둘지 묻는다. 거절하면 20분 뒤에 "2분만" 으로 다시 묻는다
        val h = Harness(real)
        h.drive(occupants = 4)
        assertNotNull(h.runUntil<WakeEvent.Propose>(1800))
        assertEquals("KEEP", h.engine.lastProposalVariant)
        h.send { UserAnswer(it, false) }
        assertEquals(real.declineCooldownMinutes * 60, h.runUntil<WakeEvent.Propose>(1800))
        assertEquals("REVERT", h.engine.lastProposalVariant)
        assertTrue(h.last<WakeEvent.Propose>()!!.text.endsWith("2분만 바깥 공기로 바꿀까요?"))
        // 주행풍만으로는 외기 정상상태도 한도 위라 2분 환기 뒤에도 2,000 을 넘는다
        h.acceptVentilation()
        val text = h.finishVentilation()
        assertTrue(text!!.endsWith("ppm까지 내려갔어요. 원래대로 돌렸어요."), text)
        assertTrue(h.engine.estimatedPpm >= 2000)
        h.run(299)
        assertEquals(2, h.count<WakeEvent.Propose>())
        h.run(2)
        assertEquals(3, h.count<WakeEvent.Propose>())
        // "2분만" 은 한 번만. 그다음은 다시 재도달 예측을 따른다
        assertEquals("KEEP", h.engine.lastProposalVariant)
    }

    // ---------- 여럿이 탈 때: 세 번째부터는 바깥 공기로 계속 둘지 묻는다 ----------

    private val keepText = "여럿이 탈 땐 바깥 공기로 두는 게 좋아요. 계속 바깥 공기로 둘까요?"

    /** 풍량 최대로 "2분만" 환기 제안을 두 번 받아 원복까지 끝내고 세 번째 제안까지 간다 */
    private fun Harness.twoVentilationsThenThirdProposal(occupants: Int): WakeEvent.Propose {
        send { FanSpeedChanged(it, 8, 8) }
        drive(occupants = occupants)
        repeat(2) {
            assertNotNull(runUntil<WakeEvent.Propose>(1800))
            assertTrue(last<WakeEvent.Propose>()!!.text.endsWith("2분만 바깥 공기로 바꿀까요?"))
            assertEquals("REVERT", engine.lastProposalVariant)
            acceptVentilation()
            finishVentilation()
        }
        assertNotNull(runUntil<WakeEvent.Propose>(1800))
        return last<WakeEvent.Propose>()!!
    }

    @Test
    fun `2명이 환기를 두 번 끝내면 세 번째는 계속 바깥 공기로 둘지 묻고 동의하면 원복 없이 둔다`() {
        // 2명, 풍량 최대: 2분 환기 뒤 다시 닿기까지 약 7분이라 처음 두 번은 "2분만" 이다
        val h = Harness(real)
        val third = h.twoVentilationsThenThirdProposal(occupants = 2)
        assertEquals(TriggerKind.STALE_AIR, third.kind)
        assertEquals(keepText, third.text)
        assertEquals(listOf<Action>(Action.Ventilate()), third.actions)
        assertEquals("KEEP_AFTER_TWO", h.engine.lastProposalVariant)

        val executes = h.count<WakeEvent.Execute>()
        val runnings = h.count<WakeEvent.Running>()
        val exec = h.send { UserAnswer(it, true) }.filterIsInstance<WakeEvent.Execute>().single()
        assertEquals(listOf<Action>(Action.Ventilate()), exec.actions)
        val after = h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, "바깥 공기로 둘게요.")), after)
        assertEquals("IDLE", h.engine.phaseName)

        // 1시간을 달려도 Running, 원복, 새 제안이 없고 추정치는 외기 정상상태로 내려간다
        h.run(3600)
        assertEquals(executes + 1, h.count<WakeEvent.Execute>())
        assertEquals(runnings, h.count<WakeEvent.Running>())
        assertEquals(3, h.count<WakeEvent.Propose>())
        assertTrue(h.airs.last().ventilating)
        assertNull(h.airs.last().minutesToLimit)
        assertNear(steady(2, freshAch(8, 8)), h.engine.estimatedPpm.toDouble(), 1.0)

        val card = h.arrive()
        assertEquals(3, card.ventilations)
        assertTrue(card.freshAirMinutes >= 60, "바깥 공기 ${card.freshAirMinutes}분")
    }

    @Test
    fun `계속 바깥 공기로 둘지 물었을 때 거절하면 쿨다운 뒤에 다시 2분만으로 묻는다`() {
        val h = Harness(real)
        val third = h.twoVentilationsThenThirdProposal(occupants = 2)
        assertEquals(keepText, third.text)
        val executes = h.count<WakeEvent.Execute>()

        val done = h.send { UserAnswer(it, false) }
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 20분 뒤에 다시 볼게요.")), done)
        // 차는 건드리지 않았다. 쿨다운 20분 동안은 한도 위여도 조용하다
        h.run(real.declineCooldownMinutes * 60 - 1)
        assertEquals(executes, h.count<WakeEvent.Execute>())
        assertEquals(3, h.count<WakeEvent.Propose>())
        assertTrue(h.engine.estimatedPpm >= real.co2LimitPpm)
        h.run(2)
        assertEquals(4, h.count<WakeEvent.Propose>())
        // 끝낸 환기 횟수를 0 으로 되돌렸으니 같은 질문을 되풀이하지 않고 "2분만" 으로 돌아간다
        assertEquals("REVERT", h.engine.lastProposalVariant)
        val fourth = h.last<WakeEvent.Propose>()!!.text
        assertTrue(fourth.startsWith("2명이 타서 공기가 빨리 탁해져요(추정 "), fourth)
        assertTrue(fourth.endsWith("2분만 바깥 공기로 바꿀까요?"), fourth)
        assertEquals(WakeEvent.Running(TriggerKind.STALE_AIR, 120, "바깥 공기로 바꾸는 중"), h.acceptVentilation())
        h.finishVentilation()
        assertEquals(1, h.arrive().declined)
    }

    @Test
    fun `혼자 타면 세 번째 환기도 같은 문장으로 묻고 2분 뒤 원복한다`() {
        val h = Harness(real)
        val third = h.twoVentilationsThenThirdProposal(occupants = 1)
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", third.text)
        val running = h.acceptVentilation()
        assertEquals(WakeEvent.Running(TriggerKind.STALE_AIR, 120, "바깥 공기로 바꾸는 중"), running)
        assertEquals("추정 700ppm까지 내려갔어요. 원래대로 돌렸어요.", h.finishVentilation())

        // 도중에 한 명이 더 타면 그다음 제안부터 바뀐다
        h.send { OccupantsChanged(it, 2) }
        assertNotNull(h.runUntil<WakeEvent.Propose>(1800))
        assertEquals(keepText, h.last<WakeEvent.Propose>()!!.text)
    }

    // ---------- AirEstimate ----------

    @Test
    fun `AirEstimate 는 첫 tick 과 그 뒤 5초마다 나온다`() {
        val h = Harness(real)
        h.send { FanSpeedChanged(it, 4, 8) }
        h.drive(occupants = 1, recirc = false)
        assertTrue(h.airs.isEmpty(), "세션 시작 신호만으로는 내지 않는다")
        h.run(1)
        // 시작값에서 외기 4단으로 1초 흐른 값
        val first = solve(startPpm, 1, freshAch(4, 8), 1.0 / 3600).roundToInt()
        assertEquals(WakeEvent.AirEstimate(first, 1, null, true), h.airs.single())
        h.run(60)
        assertEquals(13, h.airs.size) // 1초, 6초, ... 61초
    }

    @Test
    fun `내기순환이면 한도까지 남은 분을 알리고 값이 50 이상 바뀌면 바로 알린다`() {
        // 데모(60배): 1초에 1분치가 흘러 매 tick 50ppm 넘게 변한다
        val h = Harness(real.copy(demoFast = true))
        h.drive(occupants = 1)
        h.run(10)
        assertEquals(10, h.airs.size)
        assertTrue(h.airs.all { !it.ventilating && it.occupants == 1 })
        val total = secondsToLimit(1) / 60.0 // 약 20.2분
        assertEquals((1..10).map { ceil(total - it).toInt() }, h.airs.map { it.minutesToLimit })
        assertTrue(h.airs.zipWithNext().all { (a, b) -> b.ppm - a.ppm >= 50 })
    }

    @Test
    fun `인원이나 환기 상태가 바뀌면 신호에서 바로 AirEstimate 를 낸다`() {
        val h = Harness(real)
        h.drive(occupants = 1)
        h.run(1)
        assertEquals(1, h.airs.size)
        assertEquals(ceil((secondsToLimit(1) - 1) / 60.0).toInt(), h.airs.last().minutesToLimit)

        h.send { OccupantsChanged(it, 4) }
        assertEquals(2, h.airs.size)
        assertEquals(4, h.airs.last().occupants)
        assertEquals(ceil(secondsToLimit(4, from = h.engine.estimatedPpm.toDouble()) / 60.0).toInt(), h.airs.last().minutesToLimit)

        h.send { RecirculationChanged(it, false) }
        assertEquals(3, h.airs.size)
        assertTrue(h.airs.last().ventilating)

        // 창문도 환기 상태다
        h.send { RecirculationChanged(it, true) }
        h.send { WindowOpenChanged(it, 20) }
        assertEquals(5, h.airs.size)
        assertTrue(h.airs.last().ventilating)

        // 같은 값이 다시 와도 내지 않는다
        h.send { OccupantsChanged(it, 4) }
        assertEquals(5, h.airs.size)
    }

    @Test
    fun `엔진이 환기를 유지하는 동안 ventilating 이고 세션 밖에서는 내지 않는다`() {
        val h = Harness(real)
        h.send { OccupantsChanged(it, 1) }
        h.send { RecirculationChanged(it, true) }
        h.run(30)
        assertTrue(h.airs.isEmpty(), "IDLE 에서는 내지 않는다")

        h.send { GearChanged(it, Gear.DRIVE) }
        assertNotNull(h.runUntil<WakeEvent.Propose>(1800))
        assertTrue(h.airs.none { it.ventilating })
        h.acceptVentilation()
        assertTrue(h.airs.last().ventilating)
        assertNull(h.airs.last().minutesToLimit.takeIf { it != 0 }) // 이미 한도 위라 0
        h.finishVentilation()
        assertTrue(!h.airs.last().ventilating)

        h.arrive()
        val n = h.airs.size
        h.run(30)
        assertEquals(n, h.airs.size)
    }

    // ---------- 인원 변경 ----------

    @Test
    fun `도중에 인원이 늘면 그때부터 빨리 차서 제안이 앞당겨진다`() {
        val h = Harness(real)
        h.drive(occupants = 1)
        h.run(300)
        val ppmAt5 = h.engine.estimatedPpm
        h.send { OccupantsChanged(it, 4) }
        val sec = h.runUntil<WakeEvent.Propose>(1800)!!
        // 5분에 약 1,060ppm 이었고, 4명이면 거기서 2분 반 남짓(1명 그대로면 15분 더)
        val at5 = solve(startPpm, 1, real.leakAch, 5.0 / 60)
        assertNear(at5, ppmAt5.toDouble(), 1.0)
        assertNear(secondsToLimit(4, from = at5), sec.toDouble(), 2.0)
        assertTrue(sec < (secondsToLimit(1) - 300) / 4, "1명 그대로일 때보다 훨씬 빠르다: $sec 초")
        assertEquals("4명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?", h.last<WakeEvent.Propose>()!!.text)
    }

    @Test
    fun `도중에 인원이 줄면 그만큼 늦게 찬다`() {
        val h = Harness(real)
        h.drive(occupants = 4)
        h.run(120)
        h.send { OccupantsChanged(it, 1) }
        // 4명 그대로였으면 약 223초에 제안. 1명으로 줄어 그 시점엔 조용하다
        h.run(200)
        assertEquals(0, h.count<WakeEvent.Propose>())
        assertEquals(1, h.airs.last().occupants)
        assertNotNull(h.runUntil<WakeEvent.Propose>(1800))
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", h.last<WakeEvent.Propose>()!!.text)
    }

    @Test
    fun `쉼 모드에서는 숨 모델이 멈추고 쉼이 끝나면 처음 값으로 돌아간다`() {
        val h = Harness(real)
        h.drive(occupants = 2)
        h.send { RestStopCandidate(it, stop) }
        val p = h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.filterIsInstance<WakeEvent.Propose>().single()
        h.send { UserAnswer(it, true) }
        for (a in p.actions) h.send { ActionResult(it, a, ok = true) }
        h.finishVentilation()
        h.run(120)
        h.send { RestStopProximity(it, within = true) }
        val rest = h.send { GearChanged(it, Gear.PARK) }
        // 내기순환으로 쉬게 되니 쉬는 동안 바깥 공기로 둘지 묻는다. 여기서는 거절한다(아무 일도 없다)
        assertEquals(listOf(WakeEvent.RestStarted("망향졸음쉼터", 15), WakeEvent.Propose(TriggerKind.STALE_AIR, "쉬는 동안 바깥 공기로 둘까요?", listOf(Action.Ventilate(fanDelta = 0)), 20)), rest)
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, null)), h.send { UserAnswer(it, false) })

        // 15분을 쉬어도 추정치는 그대로이고 AirEstimate 도 나오지 않는다
        val before = h.engine.estimatedPpm
        val airsBefore = h.airs.size
        val ticks = h.run(900)
        assertTrue(ticks.all { it is WakeEvent.RestTick })
        assertEquals(before, h.engine.estimatedPpm)
        assertEquals(airsBefore, h.airs.size)

        // 다시 출발하면 문을 열고 내렸다고 보고 처음 값에서 시작한다. 바뀐 값은 바로 알린다
        val ended = h.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(WakeEvent.RestEnded(15, "15분 푹 쉬셨어요. 조심히 가요"), ended.single())
        assertEquals(startPpm.roundToInt(), h.engine.estimatedPpm)
        assertEquals(airsBefore + 1, h.airs.size)
        assertEquals(startPpm.roundToInt(), h.airs.last().ppm)
        assertEquals(2, h.airs.last().occupants)
        // 한도까지 남은 분도 처음부터 다시 센다(2명 약 8분)
        assertEquals(ceil(secondsToLimit(2) / 60.0).toInt(), h.airs.last().minutesToLimit)
        h.run(60)
        assertNear(solve(startPpm, 2, real.leakAch, 1.0 / 60), h.engine.estimatedPpm.toDouble(), 1.0)
    }

    // ---------- 도착 카드 ----------

    @Test
    fun `도착 카드에 최고 추정치와 한도 초과 분이 들어가고 lines 에는 공기 줄이 없다`() {
        val h = Harness(real)
        h.drive(occupants = 1)
        val proposedAt = h.runUntil<WakeEvent.Propose>(1800)!! // 약 20.2분
        h.send { UserAnswer(it, false) }
        h.run(1800 - proposedAt) // 출발 30분까지
        val card = h.arrive()
        // 30분 내기순환의 해석해(약 2,400ppm)
        assertEquals(solve(startPpm, 1, real.leakAch, 0.5).roundToInt(), card.peakPpm)
        assertEquals(((1800 - secondsToLimit(1)) / 60.0).roundToInt(), card.minutesOverLimit) // 약 20.2분 ~ 30분
        // 공기 줄은 앱이 peakPpm 으로 한 번만 보여 준다. lines 에는 로그만 남는다
        assertEquals(listOf("출발 20분 뒤 제안 거절(환기)"), card.lines)
        assertEquals("오늘은 추정 2,000ppm 위로 10분 달렸어요. 무사히 도착했어요.", card.headline)
        assertEquals(1, card.declined)
    }

    @Test
    fun `환기를 받아 한도를 넘긴 시간이 짧으면 그 분을 말한다`() {
        val h = Harness(real)
        h.send { FanSpeedChanged(it, 8, 8) }
        h.drive(occupants = 2)
        // 회전 직전이라 한도에 닿고도 45초 동안 말하지 못했다
        h.send { NavTurnAhead(it, TurnDir.LEFT, 50.0) }
        assertNotNull(h.runUntil<WakeEvent.Deferred>(1800))
        h.run(45)
        h.send { NavTurnAhead(it, TurnDir.STRAIGHT, 500.0) }
        assertEquals(1, h.count<WakeEvent.Propose>())
        h.acceptVentilation()
        h.finishVentilation()
        val card = h.arrive()
        assertEquals(1, card.ventilations)
        assertEquals(1, card.minutesOverLimit)
        // 한도에서 내기순환으로 45초 더 간 값(약 2,100ppm)
        assertNear(solve(limit, 2, real.leakAch, 45.0 / 3600), card.peakPpm.toDouble(), 10.0)
        assertEquals("오늘 환기 1번, 추정 2,000ppm 을 넘긴 건 1분이었어요.", card.headline)
        assertEquals(listOf("출발 8분 뒤 바깥 공기로 환기"), card.lines)
    }

    @Test
    fun `한 번도 한도를 안 넘고 환기했으면 아래로 지켰다고 말한다`() {
        // 시간 상한 10분에 걸려 환기. 그때 추정치는 약 1,410ppm
        val h = Harness(real.copy(staleAirMinutes = 10))
        h.send { FanSpeedChanged(it, 8, 8) }
        h.drive(occupants = 1)
        assertEquals(600, h.runUntil<WakeEvent.Propose>(1800))
        assertEquals("내기순환으로 10분째예요. 2분만 바깥 공기로 바꿀까요?", h.last<WakeEvent.Propose>()!!.text)
        h.acceptVentilation()
        assertEquals("추정 600ppm까지 내려갔어요. 원래대로 돌렸어요.", h.finishVentilation())
        val card = h.arrive()
        assertEquals(0, card.minutesOverLimit)
        assertTrue(card.peakPpm in 1400..1430, "최고 ${card.peakPpm}")
        assertEquals("오늘 차 안 공기를 추정 2,000ppm 아래로 지켰어요.", card.headline)
    }

    @Test
    fun `3분 넘게 지나 다시 출발하면 추정치와 최고치를 처음부터 다시 센다`() {
        val h = Harness(real)
        h.drive(occupants = 4)
        h.run(200)
        assertTrue(h.arrive().peakPpm > 1800)
        h.run(WakeEngine.RESTART_KEEP_MINUTES * 60 + 1)
        h.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(startPpm.roundToInt(), h.engine.estimatedPpm)
        h.run(10)
        val card = h.arrive()
        assertTrue(card.peakPpm < 700, "최고 ${card.peakPpm}")
        assertTrue(card.lines.isEmpty())
    }

    // ---------- demoFast ----------

    @Test
    fun `demoFast 면 숨 모델이 60배로 흘러 1명은 21초 4명은 4초에 제안한다`() {
        val one = Harness(real.copy(demoFast = true))
        one.drive(occupants = 1)
        assertEquals(21, one.runUntil<WakeEvent.Propose>(120))
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", one.last<WakeEvent.Propose>()!!.text)

        val four = Harness(real.copy(demoFast = true))
        four.drive(occupants = 4)
        assertEquals(4, four.runUntil<WakeEvent.Propose>(120))
        assertEquals("4명이 타서 공기가 빨리 탁해져요(추정 2,100ppm). 바깥 공기로 바꿔 둘까요?", four.last<WakeEvent.Propose>()!!.text)
    }

    // ---------- 핸들 신호 ----------

    /** 0도 근처로 quietSec 초 가만히 있다가 jerk 도로 꺾는다. afterMs 는 무조작이 깨진 뒤 꺾기까지의 간격 */
    private fun Harness.quietThenJerk(jerk: Float = 9f, quietSec: Int = 7, viaDeg: Float? = null, afterMs: Long = 0) {
        send { SteeringAngle(it, 0f) }
        repeat(quietSec) { i ->
            run(1)
            send { SteeringAngle(it, if (i % 2 == 0) 0.4f else -0.3f) }
        }
        run(1)
        if (viaDeg != null) {
            send { SteeringAngle(it, viaDeg) }
            engine.accept(SteeringAngle(now + afterMs, jerk)).let { events += it.filter { e -> e !is WakeEvent.AirEstimate } }
        } else {
            send { SteeringAngle(it, jerk) }
        }
    }

    private fun steerHarness(): Harness = Harness(steerDemo).also {
        it.send { GearChanged(it, Gear.DRIVE) }
        it.run(30) // 데모에서는 트립 30초부터 본다
    }

    @Test
    fun `오래 무조작 뒤 급보정이 창 안에 3번이면 단정하지 않는 문장으로 쉬어 가기를 제안한다`() {
        val h = steerHarness()
        h.quietThenJerk()
        assertEquals(1, h.engine.steerEvents)
        h.quietThenJerk(jerk = -9f)
        assertEquals(2, h.engine.steerEvents)
        assertEquals(0, h.count<WakeEvent.Propose>())
        h.quietThenJerk()
        val p = h.last<WakeEvent.Propose>()
        assertNotNull(p)
        assertEquals(TriggerKind.DROWSY, p.kind)
        assertEquals("핸들 보정이 잦아졌어요. 찬 바람 넣고 창문 조금 열까요?", p.text)
        assertTrue(!p.text.contains("졸음"))
        // 제안을 내면 횟수는 다시 0 부터
        assertEquals(0, h.engine.steerEvents)
        // 핸들 신호는 졸음 경고 횟수로 세지 않고 로그에도 졸음이라고 적지 않는다
        h.send { UserAnswer(it, false) }
        val card = h.arrive()
        assertEquals(0, card.drowsySignals)
        assertTrue(card.lines.none { it.contains("졸음") }, card.lines.toString())
    }

    @Test
    fun `비가 오고 쉼터 후보가 있으면 핸들 문장에도 반영된다`() {
        val h = steerHarness()
        h.send { WiperChanged(it, true) }
        h.send { RestStopCandidate(it, stop) }
        repeat(3) { h.quietThenJerk() }
        assertEquals(
            "핸들 보정이 잦아졌어요. 비가 와서 창문은 두고, 찬 바람과 12km 앞 경부선 망향졸음쉼터 경유까지 할까요?",
            h.last<WakeEvent.Propose>()!!.text,
        )
    }

    @Test
    fun `무조작이 짧거나 꺾은 각도가 작거나 1초를 넘겨 꺾으면 세지 않는다`() {
        val h = steerHarness()
        h.quietThenJerk(quietSec = 3)          // 무조작 6초 미만
        h.quietThenJerk(jerk = 5f)             // 8도 미만
        h.quietThenJerk(viaDeg = 3f, afterMs = 2_000) // 천천히 꺾음
        assertEquals(0, h.engine.steerEvents)
        // 1초 안에 이어서 꺾으면 센다
        h.run(3)
        h.quietThenJerk(viaDeg = 3f, afterMs = 500)
        assertEquals(1, h.engine.steerEvents)
    }

    @Test
    fun `깜빡이가 켜져 있거나 끈 지 3초 안이면 차로 변경으로 보고 세지 않는다`() {
        val h = steerHarness()
        // 깜빡이 ON 중
        h.send { SteeringAngle(it, 0f) }
        h.run(7)
        h.send { TurnSignalChanged(it, TurnSignalState.LEFT) }
        h.run(1)
        h.send { SteeringAngle(it, 9f) }
        assertEquals(0, h.engine.steerEvents)
        // 끈 지 2초
        h.send { SteeringAngle(it, 0f) }
        h.run(7)
        h.send { TurnSignalChanged(it, TurnSignalState.OFF) }
        h.run(2)
        h.send { SteeringAngle(it, 9f) }
        assertEquals(0, h.engine.steerEvents)
        // 끈 지 한참 뒤에는 센다
        h.quietThenJerk()
        assertEquals(1, h.engine.steerEvents)
        assertEquals(0, h.count<WakeEvent.Propose>())
    }

    @Test
    fun `창을 벗어난 급보정은 빠지고 트립이 짧으면 기다린다`() {
        // 데모 창은 60초
        val h = steerHarness()
        h.quietThenJerk()
        h.quietThenJerk()
        assertEquals(2, h.engine.steerEvents)
        h.run(61)
        assertEquals(0, h.engine.steerEvents)
        h.quietThenJerk()
        assertEquals(0, h.count<WakeEvent.Propose>())

        // 트립 30초 전에 3번 쌓여도 제안은 30초가 되어서 나간다
        val early = Harness(steerDemo)
        early.send { GearChanged(it, Gear.DRIVE) }
        repeat(3) { early.quietThenJerk() } // 24초
        assertEquals(3, early.engine.steerEvents)
        early.run(5)
        assertEquals(0, early.count<WakeEvent.Propose>())
        early.run(1)
        assertEquals(1, early.count<WakeEvent.Propose>())
    }

    @Test
    fun `핸들 신호 제안은 세션당 2번까지만 낸다`() {
        val h = steerHarness()
        repeat(3) { h.quietThenJerk() }
        assertEquals(1, h.count<WakeEvent.Propose>())
        h.send { UserAnswer(it, false) }
        h.run(21) // 거절 쿨다운 20초
        repeat(3) { h.quietThenJerk() }
        assertEquals(2, h.count<WakeEvent.Propose>())
        h.send { UserAnswer(it, false) }
        h.run(21)
        repeat(4) { h.quietThenJerk() }
        h.run(30)
        assertEquals(2, h.count<WakeEvent.Propose>())
        // 텔테일은 여전히 통한다
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        h.run(1)
        assertEquals(3, h.count<WakeEvent.Propose>())
        assertTrue(h.last<WakeEvent.Propose>()!!.text.startsWith("졸음 경고가 떴어요."))
    }

    @Test
    fun `텔테일이 오면 그게 우선이고 핸들 문장은 버린다`() {
        val h = steerHarness()
        h.send { NavTurnAhead(it, TurnDir.LEFT, 50.0) } // 회전 직전이라 말하지 못한다
        repeat(3) { h.quietThenJerk() }
        assertEquals(TriggerKind.DROWSY, h.last<WakeEvent.Deferred>()!!.kind)
        assertEquals(0, h.count<WakeEvent.Propose>())

        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        val got = h.send { NavTurnAhead(it, TurnDir.STRAIGHT, 500.0) }
        val p = got.filterIsInstance<WakeEvent.Propose>().single()
        assertEquals("졸음 경고가 떴어요. 찬 바람 넣고 창문 조금 열까요?", p.text)
        h.send { UserAnswer(it, false) }
        assertEquals(1, h.arrive().drowsySignals)
    }

    @Test
    fun `실제 시간에서는 10분 창과 트립 30분을 쓴다`() {
        val h = Harness(real.copy(co2PerPersonLph = 0.0))
        h.send { GearChanged(it, Gear.DRIVE) }
        h.run(31 * 60)
        h.quietThenJerk()
        h.run(5 * 60)
        h.quietThenJerk()
        assertEquals(2, h.engine.steerEvents)
        // 첫 번째에서 10분이 지나면 빠진다
        h.run(5 * 60)
        assertEquals(1, h.engine.steerEvents)
        h.quietThenJerk()
        h.quietThenJerk()
        assertEquals(1, h.count<WakeEvent.Propose>())
        assertTrue(h.last<WakeEvent.Propose>()!!.text.startsWith("핸들 보정이 잦아졌어요."))
    }
}

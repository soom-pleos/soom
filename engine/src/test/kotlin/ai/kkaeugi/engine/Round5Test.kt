package ai.kkaeugi.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 5차(3차 리뷰 결정) 동작을 본다. 실제 시간 기본 설정(WakeConfig())으로 1초 tick 을 돌린다.
 * 하네스(Car)는 차를 흉내 낸다: Execute 를 받으면 내외기와 풍량을 바꾸고, 차가 알려 주는 신호와 ActionResult 를 엔진에 돌려준다.
 * 원복 없는 Done(바깥 공기로 두기, 풍량 올리기)이 오면 그 실행의 스냅샷을 버린다(앱과 같은 규칙).
 * 기대값은 가능한 한 설정값에서 질량 수지 해석해로 계산한다.
 */
class Round5Test {

    private val real = WakeConfig(steeringAssist = true, allowWindow = true)
    private val stop = RestStopInfo("망향", "경부선 서울방향", 12.3, 36.9, 127.1)

    /** 제안 한 건: 출발 뒤 초, 변형, 문장, 그때의 추정 ppm, 재도달 예측(분) */
    private data class Proposal(val atSec: Int, val variant: String, val text: String, val ppm: Int, val recurrence: Double?)

    private class Car(config: WakeConfig, start: Long = 1_000_000L) {
        val engine = WakeEngine(config)
        var now = start
        private var drivingSince = start
        /** AirEstimate 와 Running 을 뺀 이벤트 */
        val events = mutableListOf<WakeEvent>()
        val airs = mutableListOf<WakeEvent.AirEstimate>()
        val proposals = mutableListOf<Proposal>()

        // 차 상태
        var recirc = true
        var fan = 3
        var fanMax = 8
        /** 환기 실행 직전의 (내기순환, 풍량). 원복하면 이 값으로 돌아간다 */
        private var snapshot: Pair<Boolean, Int>? = null
        /** 풍량을 올리라는 요청을 차가 받아들이는가(공조 전원이 꺼져 있는 경우를 흉내 낼 때 false) */
        var fanResponds = true

        /** 제안에 자동으로 답한다. null 이면 답하지 않는다. answerDelaySec 뒤에 답한다 */
        var autoAnswer: Boolean? = null
        var answerDelaySec = 5
        private var answerAt: Long? = null
        /** 원복 결과를 이만큼 늦게 돌려준다(초) */
        var revertDelaySec = 0
        private var revertResultAt: Long? = null

        /** 주행 중 본 추정치 최고값, Running 이벤트 수 */
        var peakSeen = 0
        var runnings = 0

        private fun handle(all: List<WakeEvent>): List<WakeEvent> {
            val seen = mutableListOf<WakeEvent>()
            val pending = ArrayDeque(all)
            while (pending.isNotEmpty()) {
                val e = pending.removeFirst()
                when (e) {
                    is WakeEvent.AirEstimate -> { airs += e; continue }
                    is WakeEvent.Running -> runnings++
                    is WakeEvent.Propose -> {
                        proposals += Proposal(
                            ((now - drivingSince) / 1000).toInt(), engine.lastProposalVariant ?: "?", e.text,
                            engine.estimatedPpm, engine.lastRecurrenceMinutes,
                        )
                        if (autoAnswer != null) answerAt = now + answerDelaySec * 1000L
                    }
                    is WakeEvent.Execute -> {
                        if (e.actions == listOf<Action>(Action.Revert)) {
                            // 차는 바로 되돌아가고(리스너 신호도 바로 온다) 원복 결과만 늦게 올 수 있다
                            pending.addAll(restoreCar())
                            if (revertDelaySec > 0) revertResultAt = now + revertDelaySec * 1000L
                            else pending.addAll(engine.accept(ActionResult(now, Action.Revert, ok = true)))
                        } else {
                            for (a in e.actions) {
                                if (a is Action.Ventilate) {
                                    if (snapshot == null) snapshot = recirc to fan
                                    if (recirc) { recirc = false; pending.addAll(engine.accept(RecirculationChanged(now, false))) }
                                    val raised = if (fanResponds) minOf(fan + a.fanDelta, fanMax) else fan
                                    if (raised != fan) { fan = raised; pending.addAll(engine.accept(FanSpeedChanged(now, fan, fanMax))) }
                                }
                                pending.addAll(engine.accept(ActionResult(now, a, ok = true)))
                            }
                        }
                    }
                    // 원복 없이 끝났으면(바깥 공기로 두기, 풍량 올리기) 스냅샷을 버린다. 차는 바뀐 상태로 남는다
                    is WakeEvent.Done -> snapshot = null
                    else -> {}
                }
                seen += e
            }
            events += seen.filter { it !is WakeEvent.Running }
            return seen
        }

        /** 스냅샷으로 차 상태를 되돌리고 차가 알려 주는 신호를 엔진에 넣는다 */
        private fun restoreCar(): List<WakeEvent> {
            val out = mutableListOf<WakeEvent>()
            snapshot?.let { (r, f) ->
                if (recirc != r) { recirc = r; out += engine.accept(RecirculationChanged(now, r)) }
                if (fan != f) { fan = f; out += engine.accept(FanSpeedChanged(now, f, fanMax)) }
            }
            snapshot = null
            return out
        }

        fun send(make: (Long) -> Signal): List<WakeEvent> = handle(engine.accept(make(now)))

        /** 운전자가 직접 내외기를 바꾼다 */
        fun driverRecirc(on: Boolean): List<WakeEvent> {
            recirc = on
            return send { RecirculationChanged(it, on) }
        }

        fun run(seconds: Int): List<WakeEvent> {
            val got = mutableListOf<WakeEvent>()
            repeat(seconds) {
                now += 1_000
                got += handle(engine.tick(now))
                if (engine.tripState == TripState.DRIVING && !engine.isResting) peakSeen = maxOf(peakSeen, engine.estimatedPpm)
                answerAt?.let { at ->
                    if (now >= at) { answerAt = null; got += handle(engine.accept(UserAnswer(now, autoAnswer!!))) }
                }
                revertResultAt?.let { at ->
                    if (now >= at) { revertResultAt = null; got += handle(engine.accept(ActionResult(now, Action.Revert, ok = true))) }
                }
            }
            return got
        }

        inline fun <reified T : WakeEvent> runUntil(max: Int): Int? {
            for (s in 1..max) if (run(1).any { it is T }) return s
            return null
        }

        /** 차 상태(풍량, 인원, 내외기)를 알리고 출발한다 */
        fun drive(occupants: Int = 1, recirc: Boolean = true, fan: Int = 3, fanMax: Int = 8) {
            this.recirc = recirc; this.fan = fan; this.fanMax = fanMax
            send { FanSpeedChanged(it, fan, fanMax) }
            send { OccupantsChanged(it, occupants) }
            send { RecirculationChanged(it, recirc) }
            drivingSince = now
            send { GearChanged(it, Gear.DRIVE) }
        }

        fun yes(): List<WakeEvent> = send { UserAnswer(it, true) }
        fun no(): List<WakeEvent> = send { UserAnswer(it, false) }

        fun arrive(): ArrivalSummary =
            send { GearChanged(it, Gear.PARK) }.filterIsInstance<WakeEvent.ArrivalCard>().single().card

        inline fun <reified T : WakeEvent> last(): T? = events.filterIsInstance<T>().lastOrNull()
        inline fun <reified T : WakeEvent> count(): Int = events.count { it is T }
    }

    // ---------- 기대값 계산(설정값에서 해석해로) ----------

    private val startPpm = real.outdoorPpm + CabinAirModel.START_OFFSET_PPM
    private val limit = real.co2LimitPpm.toDouble()

    private fun steady(n: Int, ach: Double): Double =
        real.outdoorPpm + n * real.co2PerPersonLph * 1e6 / real.cabinVolumeL / ach

    private fun solve(c0: Double, n: Int, ach: Double, hours: Double): Double {
        val css = steady(n, ach)
        return css + (c0 - css) * exp(-ach * hours)
    }

    private fun fanFraction(level: Int, max: Int): Double = when {
        level <= 0 || max <= 0 -> CabinAirModel.FAN_OFF_FRACTION
        max == 1 -> 1.0
        else -> CabinAirModel.FAN_MIN_FRACTION + (1.0 - CabinAirModel.FAN_MIN_FRACTION) * (level - 1) / (max - 1)
    }

    private fun freshAch(level: Int, max: Int): Double = real.leakAch + real.freshAchMax * fanFraction(level, max)

    /** 내기순환에서 from → to 까지 걸리는 분 */
    private fun minutesUp(n: Int, from: Double, to: Double = limit): Double {
        val css = steady(n, real.leakAch)
        return ln((css - from) / (css - to)) / real.leakAch * 60.0
    }

    /** 재도달 예측의 해석해: from 에서 ventSec 동안 외기(ventAch) 뒤 내기순환으로 to 에 다시 닿기까지의 분 */
    private fun recurrence(n: Int, ventAch: Double, from: Double = limit, to: Double = limit, ventSec: Int = real.ventilateSeconds): Double {
        val after = solve(from, n, ventAch, ventSec / 3600.0)
        return if (after >= to) 0.0 else minutesUp(n, after, to)
    }

    private fun assertNear(expected: Double, actual: Double, tol: Double) =
        assertTrue(abs(expected - actual) <= tol, "기대 $expected, 실제 $actual")

    /** 졸음 경고등을 켜서 졸음 제안을 받는다(이미 켜져 있었으면 껐다 켠다) */
    private fun Car.drowsyProposal(): WakeEvent.Propose {
        send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        val p = send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.filterIsInstance<WakeEvent.Propose>().single()
        assertEquals(TriggerKind.DROWSY, p.kind)
        return p
    }

    /** 졸음 제안에 동의해 쉼터를 경유지로 넣고 원복까지 끝낸다 */
    private fun Car.addWaypointViaDrowsy(s: RestStopInfo = stop) {
        send { RestStopCandidate(it, s) }
        drowsyProposal()
        yes()
        assertNotNull(runUntil<WakeEvent.Done>(600), "졸음 개입이 원복까지 끝나야 한다")
        assertEquals(s, engine.currentWaypoint)
    }

    // ---------- 1. 쉼 모드 ----------

    @Test
    fun `쉼 모드에서는 추정치와 최고치와 한도 초과 누적이 멈추고 끝나면 처음 값으로 돌아간다`() {
        // 4명이 환기 제안을 거절하고 내기순환으로 달리다가 쉼터에서 15분 쉰다
        val car = Car(real)
        car.drive(occupants = 4)
        assertNotNull(car.runUntil<WakeEvent.Propose>(600))
        car.no()
        car.run(30)
        car.addWaypointViaDrowsy()
        car.run(200)
        val before = car.engine.estimatedPpm
        val peakBefore = car.peakSeen
        assertTrue(before > 2000, "쉼 직전 $before")
        car.send { RestStopProximity(it, within = true) }
        val started = car.send { GearChanged(it, Gear.PARK) }
        // 내기순환으로 쉬게 되니 쉬는 동안 바깥 공기로 둘지 묻는다. 여기서는 거절한다(아무 일도 없다)
        assertEquals(listOf(WakeEvent.RestStarted("망향 졸음쉼터", 15), WakeEvent.Propose(TriggerKind.STALE_AIR, "쉬는 동안 바깥 공기로 둘까요?", listOf(Action.Ventilate(fanDelta = 0)), 20)), started)
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, null)), car.no())

        val airsBefore = car.airs.size
        val ticks = car.run(15 * 60)
        assertTrue(ticks.all { it is WakeEvent.RestTick })
        // 밀폐로 15분을 더 갔다면 5,000ppm 을 넘었을 것이다. 쉬는 동안은 그대로다
        assertEquals(before, car.engine.estimatedPpm)
        assertEquals(airsBefore, car.airs.size)

        val ended = car.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(listOf<WakeEvent>(WakeEvent.RestEnded(15, "15분 푹 쉬셨어요. 조심히 가요")), ended)
        assertEquals(startPpm.roundToInt(), car.engine.estimatedPpm)
        assertEquals(startPpm.roundToInt(), car.airs.last().ppm)

        car.run(30)
        val card = car.arrive()
        // 최고치는 쉼 전에 본 값 그대로다(쉬는 동안의 값이 섞이지 않는다)
        assertTrue(abs(card.peakPpm - peakBefore) <= 1, "카드 ${card.peakPpm}, 쉼 전 $peakBefore")
        assertTrue(card.peakPpm < 3000, "최고 ${card.peakPpm}")
        assertTrue(card.minutesOverLimit <= 3, "한도 초과 ${card.minutesOverLimit}분")
        assertEquals(15, card.restMinutes)
        assertTrue(card.lines.any { it.endsWith("망향 졸음쉼터에서 15분 휴식") }, card.lines.toString())
    }

    @Test
    fun `바깥 공기로 두는 중에 쉬면 쉰 시간은 바깥 공기 분에 넣지 않는다`() {
        val car = Car(real)
        car.drive(occupants = 4)
        assertNotNull(car.runUntil<WakeEvent.Propose>(600))
        assertEquals("KEEP", car.engine.lastProposalVariant)
        car.yes()
        car.run(60)
        car.addWaypointViaDrowsy()
        // 바깥 공기로 둔 지 약 3분
        car.send { RestStopProximity(it, within = true) }
        car.send { GearChanged(it, Gear.PARK) }
        car.run(15 * 60)
        car.send { GearChanged(it, Gear.DRIVE) }
        car.run(5 * 60)
        val card = car.arrive()
        // 쉼 전 약 3분 + 쉼 뒤 5분. 쉰 15분이 들어갔다면 23분쯤이다
        assertTrue(card.freshAirMinutes in 7..10, "바깥 공기 ${card.freshAirMinutes}분")
        assertEquals(15, card.restMinutes)
    }

    // ---------- 2. 환기 연장 삭제, 원복 문장 ----------

    @Test
    fun `원복 문장의 수치는 원복을 시작할 때 값이고 데모 60배에서도 튀지 않는다`() {
        // 데모(1초 = 1분): 원복 결과가 2초 늦게 오면 그 사이 내기순환으로 2분치가 다시 오른다
        val car = Car(real.copy(demoFast = true, ventilateSeconds = 20))
        car.revertDelaySec = 2
        car.drive(occupants = 2)
        assertNotNull(car.runUntil<WakeEvent.Propose>(60))
        assertEquals("REVERT", car.engine.lastProposalVariant)
        assertTrue(car.last<WakeEvent.Propose>()!!.text.endsWith("20초만 바깥 공기로 바꿀까요?"))
        car.yes()
        assertNotNull(car.runUntil<WakeEvent.Execute>(30))
        assertEquals(listOf<Action>(Action.Revert), car.last<WakeEvent.Execute>()!!.actions)
        val atRevert = car.engine.estimatedPpm
        assertNotNull(car.runUntil<WakeEvent.Done>(10))
        val atDone = car.engine.estimatedPpm
        assertTrue(atDone - atRevert > 300, "원복 결과를 기다리는 동안 다시 올랐다: $atRevert → $atDone")
        val rounded = (atRevert + 50) / 100 * 100
        assertEquals("추정 ${rounded}ppm까지 내려갔어요. 원래대로 돌렸어요.", car.last<WakeEvent.Done>()!!.text)
    }

    @Test
    fun `환기해도 추정치가 50 넘게 내려가지 않았으면 수치 없이 원래대로 돌렸다고만 말한다`() {
        // 시간 상한 1분에 걸린 환기. 그때 추정치는 약 720ppm 이고 팬 1단 2분으로는 약 680ppm 까지만 내려간다
        val car = Car(real.copy(staleAirMinutes = 1))
        car.drive(occupants = 1, fan = 0)
        assertEquals(60, car.runUntil<WakeEvent.Propose>(120))
        assertEquals("TIME_CAP", car.engine.lastProposalVariant)
        assertEquals("내기순환으로 1분째예요. 2분만 바깥 공기로 바꿀까요?", car.last<WakeEvent.Propose>()!!.text)
        val before = car.engine.estimatedPpm
        car.yes()
        assertNotNull(car.runUntil<WakeEvent.Done>(200))
        assertTrue(before - car.engine.estimatedPpm in 1 until 50, "$before → ${car.engine.estimatedPpm}")
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "원래대로 돌렸어요."), car.last<WakeEvent.Done>())
        // 유지 구간은 말한 2분 그대로였다
        assertEquals(120, car.runnings)
    }

    // ---------- 3. 시간 상한은 환기 중이 아닐 때만 ----------

    @Test
    fun `창문이 조금 열려 있으면 내기순환이 길어져도 시간 상한 제안을 하지 않는다`() {
        // 1명, 내기순환, 창문 10%: 추정치는 약 930ppm 에서 멈춘다. 전에는 30분에 시간 상한으로 물었다
        val car = Car(real)
        car.drive(occupants = 1)
        car.send { WindowOpenChanged(it, 10) }
        car.run(3600)
        assertEquals(0, car.count<WakeEvent.Propose>())
        assertEquals(0, car.count<WakeEvent.Deferred>())
        assertTrue(car.engine.estimatedPpm < 1000, "추정 ${car.engine.estimatedPpm}")
        assertTrue(car.airs.last().ventilating)
    }

    @Test
    fun `창문을 닫으면 시간 상한은 닫은 때부터 다시 센다`() {
        // 추정치 한도를 아주 높게 두어 시간 상한만 본다
        val car = Car(real.copy(co2LimitPpm = 1_000_000))
        car.drive(occupants = 1)
        car.run(10 * 60)
        car.send { WindowOpenChanged(it, 20) }
        car.run(40 * 60) // 내기순환 50분째지만 창문이 열려 있다
        assertEquals(0, car.count<WakeEvent.Propose>())
        car.send { WindowOpenChanged(it, 0) }
        car.run(30 * 60 - 1)
        assertEquals(0, car.count<WakeEvent.Propose>())
        car.run(1)
        assertEquals(1, car.count<WakeEvent.Propose>())
        assertEquals("TIME_CAP", car.engine.lastProposalVariant)
        assertEquals("내기순환으로 30분째예요. 2분만 바깥 공기로 바꿀까요?", car.last<WakeEvent.Propose>()!!.text)
    }

    // ---------- 4. 야간 장거리 한도 ----------

    /** 야간 장거리 상태로 출발한다(하향등 ON, 트립 주행 시간 120분 보고) */
    private fun Car.driveAtNight(occupants: Int) {
        send { LowBeamChanged(it, true) }
        drive(occupants = occupants)
        send { TripTimeUpdate(it, 120) }
    }

    @Test
    fun `야간 장거리면 한도의 0,8배에서 묻고 접두는 첫 제안에만 붙는다`() {
        val car = Car(real)
        car.autoAnswer = true
        car.driveAtNight(occupants = 1)
        assertEquals(1600, car.engine.effectiveLimitPpm)
        assertEquals((limit * WakeEngine.NIGHT_LIMIT_FACTOR).roundToInt(), car.engine.effectiveLimitPpm)
        car.run(1)
        // 남은 분도 낮춘 한도 기준이다(1명 약 13분. 낮이면 약 20분)
        val toNight = minutesUp(1, startPpm, 1600.0)
        assertEquals(ceil(toNight - 1.0 / 60).toInt(), car.airs.first().minutesToLimit)
        car.run(3599)

        val first = car.proposals.first()
        assertEquals(ceil(toNight * 60).toInt(), first.atSec) // 12분 46초
        assertTrue(first.atSec < minutesUp(1, startPpm) * 60 - 400, "낮보다 7분쯤 빠르다: ${first.atSec}초")
        assertEquals("오래 달려서 조금 일찍 물어요. 차 안 공기가 탁해진 것 같아요(추정 1,600ppm). 2분만 바깥 공기로 바꿀까요?", first.text)
        // 두 번째부터는 접두가 없다. 옛 문장은 어디에도 없다
        assertTrue(car.proposals.size >= 3)
        for (p in car.proposals.drop(1)) {
            assertEquals("차 안 공기가 탁해진 것 같아요(추정 1,600ppm). 2분만 바깥 공기로 바꿀까요?", p.text)
        }
        assertTrue(car.proposals.none { it.text.contains("자주") })
        assertTrue(car.peakSeen < 1700, "최고 ${car.peakSeen}")
    }

    @Test
    fun `낮이었어도 물었을 상황이면 밤길 접두를 붙이지 않고 실제로 일찍 묻게 된 첫 제안에 붙인다`() {
        val car = Car(real)
        car.drive(occupants = 1)
        assertNotNull(car.runUntil<WakeEvent.Propose>(1800)) // 약 20분, 2,000ppm
        car.no()
        car.send { LowBeamChanged(it, true) }
        car.send { TripTimeUpdate(it, 120) }
        // 하향등이 켜진 지 3분이 안 됐으면 아직 야간으로 보지 않는다
        car.run(179)
        assertEquals(2000, car.engine.effectiveLimitPpm)
        car.run(121)
        assertEquals(1600, car.engine.effectiveLimitPpm)
        // 거절 쿨다운이 끝나면 다시 묻는다. 이미 2,000 을 넘었으니 밤이라서 일찍 묻는 것이 아니다
        assertNotNull(car.runUntil<WakeEvent.Propose>(1800))
        val second = car.last<WakeEvent.Propose>()!!.text
        assertTrue(second.startsWith("차 안 공기가 탁해진 것 같아요(추정 "), second)
        car.yes()
        assertNotNull(car.runUntil<WakeEvent.Done>(200))
        // 환기 뒤 1,600ppm 에서 다시 묻는다. 이번에는 낮보다 이른 제안이라 접두가 붙는다
        assertNotNull(car.runUntil<WakeEvent.Propose>(1800))
        assertEquals(
            "오래 달려서 조금 일찍 물어요. 차 안 공기가 탁해진 것 같아요(추정 1,600ppm). 2분만 바깥 공기로 바꿀까요?",
            car.last<WakeEvent.Propose>()!!.text,
        )
    }

    // ---------- 5. 재도달 예측 ----------

    @Test
    fun `재도달 예측값은 해석해와 같고 5분을 경계로 제안 변형이 갈린다`() {
        // 풍량 3/8 에서 환기하면 4/8 이 된다. 1명 약 16,8분, 2명 약 5,9분, 3명 약 3,3분, 4명 약 2,1분
        val expectedVariant = mapOf(1 to "REVERT", 2 to "REVERT", 3 to "KEEP", 4 to "KEEP")
        val predicted = mutableMapOf<Int, Double>()
        for (n in 1..4) {
            val car = Car(real)
            car.drive(occupants = n)
            assertNull(car.engine.lastRecurrenceMinutes)
            assertNull(car.engine.lastProposalVariant)
            assertNotNull(car.runUntil<WakeEvent.Propose>(1800))
            val p = car.proposals.single()
            val expected = recurrence(n, freshAch(4, 8), from = p.ppm.toDouble())
            assertNear(expected, p.recurrence!!, 0.05)
            assertEquals(p.recurrence, car.engine.predictRecurrenceMinutes())
            assertEquals(if (expected < WakeEngine.RECURRENCE_KEEP_MINUTES) "KEEP" else "REVERT", p.variant)
            assertEquals(expectedVariant[n], p.variant)
            predicted[n] = p.recurrence
        }
        assertNear(16.8, predicted[1]!!, 0.1)
        assertNear(5.9, predicted[2]!!, 0.1)
        assertNear(3.3, predicted[3]!!, 0.1)
        assertNear(2.1, predicted[4]!!, 0.1)
        println("재도달 예측(분, 풍량 3/8): " + predicted.entries.joinToString { "${it.key}명 ${(it.value * 10).roundToInt() / 10.0}" })
    }

    @Test
    fun `금방 다시 찰 것으로 계산되면 처음부터 바깥 공기로 둘지 묻고 동의하면 Running 과 원복 없이 끝난다`() {
        val car = Car(real)
        car.drive(occupants = 4)
        assertNotNull(car.runUntil<WakeEvent.Propose>(600))
        val p = car.last<WakeEvent.Propose>()!!
        assertEquals(TriggerKind.STALE_AIR, p.kind)
        assertEquals("4명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?", p.text)
        assertEquals(listOf<Action>(Action.Ventilate()), p.actions)

        val got = car.yes()
        assertEquals(
            listOf(
                WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Ventilate())),
                WakeEvent.Done(TriggerKind.STALE_AIR, WakeEngine.KEEP_FRESH_DONE),
            ),
            got,
        )
        assertEquals("바깥 공기로 둘게요.", WakeEngine.KEEP_FRESH_DONE)
        assertEquals("IDLE", car.engine.phaseName)
        // 차는 외기, 풍량 +1 상태로 남는다
        assertFalse(car.recirc)
        assertEquals(4, car.fan)

        car.run(3600)
        assertEquals(0, car.runnings)
        assertEquals(1, car.count<WakeEvent.Execute>())
        assertEquals(1, car.count<WakeEvent.Propose>())
        assertNear(steady(4, freshAch(4, 8)), car.engine.estimatedPpm.toDouble(), 1.0)
        assertFalse(car.recirc)
        val card = car.arrive()
        assertEquals(1, card.ventilations)
        assertTrue(card.freshAirMinutes >= 59, "바깥 공기 ${card.freshAirMinutes}분")
    }

    @Test
    fun `혼자여도 금방 다시 찰 조건이면 인원 없는 문장으로 바깥 공기로 둘지 묻는다`() {
        // 환기 20초, 팬 0 → 1단: 20초로는 거의 못 내려가 약 4분 만에 다시 닿는다
        val cfg = real.copy(ventilateSeconds = 20)
        val car = Car(cfg)
        car.drive(occupants = 1, fan = 0)
        assertNotNull(car.runUntil<WakeEvent.Propose>(1800))
        val p = car.proposals.single()
        assertNear(recurrence(1, freshAch(1, 8), from = p.ppm.toDouble(), ventSec = 20), p.recurrence!!, 0.05)
        assertTrue(p.recurrence < 5.0)
        assertEquals("KEEP", p.variant)
        assertEquals("차 안 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?", p.text)
    }

    // ---------- 6. 바깥 공기로 두기를 거절했을 때 ----------

    @Test
    fun `바깥 공기로 두자는 제안에 답이 없어도 다음은 2분만이고 그다음은 다시 예측을 따른다`() {
        val car = Car(real)
        car.drive(occupants = 4)
        assertNotNull(car.runUntil<WakeEvent.Propose>(600))
        assertEquals("KEEP", car.engine.lastProposalVariant)
        assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(60))
        assertEquals(WakeEvent.ProposalExpired("다음에 다시 물을게요."), car.last<WakeEvent.ProposalExpired>())

        // 쿨다운 20분 뒤: 예측은 여전히 5분 미만이지만 한 번은 "2분만" 으로 묻는다
        assertEquals(real.declineCooldownMinutes * 60, car.runUntil<WakeEvent.Propose>(1800))
        val second = car.proposals.last()
        assertEquals("REVERT", second.variant)
        assertTrue(second.recurrence!! < 5.0)
        assertTrue(second.text.startsWith("4명이 타서 공기가 빨리 탁해져요(추정 "), second.text)
        assertTrue(second.text.endsWith("2분만 바깥 공기로 바꿀까요?"), second.text)
        car.yes()
        assertNotNull(car.runUntil<WakeEvent.Done>(200))
        assertEquals(120, car.runnings)
        assertTrue(car.recirc, "원복으로 내기순환에 돌아왔다")

        // 그다음 제안은 다시 재도달 예측을 따른다
        assertNotNull(car.runUntil<WakeEvent.Propose>(1800))
        assertEquals("KEEP", car.proposals.last().variant)
    }

    @Test
    fun `바깥 공기로 두기로 한 뒤 운전자가 직접 내기순환을 켜면 거절로 보고 쿨다운 뒤 2분만으로 묻는다`() {
        val car = Car(real)
        car.drive(occupants = 4)
        assertNotNull(car.runUntil<WakeEvent.Propose>(600))
        car.yes()
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "바깥 공기로 둘게요."), car.last<WakeEvent.Done>())
        car.run(60)
        assertTrue(car.driverRecirc(true).isEmpty())
        // 4명이라 2분도 안 돼 다시 한도에 닿지만, 거절한 것과 같은 20분 동안은 묻지 않는다
        car.run(real.declineCooldownMinutes * 60 - 1)
        assertEquals(1, car.count<WakeEvent.Propose>())
        assertTrue(car.engine.estimatedPpm > 2000)
        car.run(1)
        assertEquals(2, car.count<WakeEvent.Propose>())
        val p = car.proposals.last()
        assertEquals("REVERT", p.variant)
        assertTrue(p.text.endsWith("2분만 바깥 공기로 바꿀까요?"), p.text)
        car.no()
        val card = car.arrive()
        assertEquals(1, card.declined) // 마지막 거절만 센다
        assertEquals(1, card.ventilations)
        assertTrue(card.lines.any { it.endsWith("바깥 공기로 두던 중 내기순환으로 바뀜") }, card.lines.toString())
    }

    // ---------- 7. 외기인데 바람이 약할 때 ----------

    @Test
    fun `풍량 올리기에 동의하면 Running 과 원복 없이 끝나고 풍량이 오르면 다시 묻지 않는다`() {
        val car = Car(real)
        car.drive(occupants = 4, recirc = false, fan = 0)
        assertNotNull(car.runUntil<WakeEvent.Propose>(1800))
        val p = car.last<WakeEvent.Propose>()!!
        assertEquals("바깥 공기인데 바람이 약해요(추정 2,000ppm). 풍량을 올릴까요?", p.text)
        assertEquals(listOf<Action>(Action.Ventilate(fanDelta = 2)), p.actions)
        assertEquals(2, WakeEngine.FAN_UP_DELTA)
        assertEquals("FAN_UP", car.engine.lastProposalVariant)

        val got = car.yes()
        assertEquals(
            listOf(
                WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Ventilate(fanDelta = 2))),
                WakeEvent.Done(TriggerKind.STALE_AIR, WakeEngine.FAN_UP_DONE),
            ),
            got,
        )
        assertEquals("풍량을 올려 둘게요.", WakeEngine.FAN_UP_DONE)
        assertEquals(2, car.fan)
        assertFalse(car.recirc)

        // 2단이면 4명도 한도 아래로 내려간다. 더 묻지 않는다
        car.run(3600)
        assertEquals(1, car.count<WakeEvent.Propose>())
        assertEquals(0, car.runnings)
        assertEquals(1, car.count<WakeEvent.Execute>())
        assertEquals(2, car.fan) // 원복하지 않았다
        assertNear(steady(4, freshAch(2, 8)), car.engine.estimatedPpm.toDouble(), 1.0)
        val card = car.arrive()
        assertEquals(1, card.ventilations)
        assertTrue(card.lines.any { it.endsWith("풍량 올리기") }, card.lines.toString())
    }

    @Test
    fun `풍량을 올려도 한도 위면 10분 뒤에 다시 묻는다`() {
        // 9명: 2단으로 올려도 정상상태가 약 2,100ppm 이라 한도 위에 머문다
        val car = Car(real)
        car.drive(occupants = 9, recirc = false, fan = 0)
        assertNotNull(car.runUntil<WakeEvent.Propose>(1800))
        car.yes()
        assertEquals(2, car.fan)
        assertTrue(steady(9, freshAch(2, 8)) > limit)
        assertEquals(WakeEngine.FAN_UP_COOLDOWN_MINUTES * 60, car.runUntil<WakeEvent.Propose>(1800))
        assertEquals("FAN_UP", car.engine.lastProposalVariant)
        car.yes()
        assertEquals(4, car.fan)
        // 4단이면 내려간다
        car.run(1800)
        assertEquals(2, car.count<WakeEvent.Propose>())
        assertTrue(car.engine.estimatedPpm < 2000)

        // 데모에서는 쿨다운이 10초다(제안 사이 발화 간격 20초가 더 길어서 20초 뒤에 묻는다)
        val demo = Car(real.copy(demoFast = true))
        demo.drive(occupants = 9, recirc = false, fan = 0)
        assertNotNull(demo.runUntil<WakeEvent.Propose>(120))
        demo.yes()
        assertEquals(20, demo.runUntil<WakeEvent.Propose>(120))
        assertEquals("FAN_UP", demo.engine.lastProposalVariant)
    }

    @Test
    fun `풍량 올리기를 거절하면 기존 쿨다운이고 올려 달라고 해도 풍량이 그대로면 되풀이하지 않는다`() {
        val car = Car(real)
        car.drive(occupants = 9, recirc = false, fan = 0)
        assertNotNull(car.runUntil<WakeEvent.Propose>(1800))
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 20분 뒤에 다시 볼게요.")), car.no())
        assertEquals(real.declineCooldownMinutes * 60, car.runUntil<WakeEvent.Propose>(1800))
        assertEquals("FAN_UP", car.engine.lastProposalVariant)

        // 공조가 꺼져 있어 풍량이 오르지 않았다. 차가 알려 준 풍량이 그대로면 같은 질문을 되풀이하지 않는다
        car.fanResponds = false
        car.yes()
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "풍량을 올려 둘게요."), car.last<WakeEvent.Done>())
        assertEquals(0, car.fan)
        car.run(3600)
        assertEquals(2, car.count<WakeEvent.Propose>())
        assertTrue(car.engine.estimatedPpm > 2000)

        // 운전자가 풍량을 1단으로 올렸다. 9명이면 1단으로도 한도 위라 그 자리에서 다시 묻는다
        car.fan = 1
        assertTrue(steady(9, freshAch(1, 8)) > limit)
        val again = car.send { FanSpeedChanged(it, 1, 8) }.filterIsInstance<WakeEvent.Propose>().single()
        assertEquals(listOf<Action>(Action.Ventilate(fanDelta = 2)), again.actions)
        assertEquals("FAN_UP", car.engine.lastProposalVariant)
        assertEquals(3, car.count<WakeEvent.Propose>())
        assertEquals(1, car.arrive().declined)
    }

    @Test
    fun `외기로 막 바꿔 내려가는 중이거나 창문이 열려 있거나 이미 최대 풍량이면 풍량을 묻지 않는다`() {
        // 내려가는 중: 4명이 내기순환 제안을 거절하고 5분 뒤 직접 외기로 바꿨다. 한도 위지만 이 풍량이면 내려간다
        val falling = Car(real)
        falling.drive(occupants = 4)
        assertNotNull(falling.runUntil<WakeEvent.Propose>(600))
        falling.no()
        falling.run(5 * 60)
        falling.driverRecirc(false)
        assertTrue(falling.engine.estimatedPpm > 3000)
        assertTrue(steady(4, freshAch(3, 8)) < limit)
        falling.run(3600)
        assertEquals(1, falling.count<WakeEvent.Propose>())

        // 창문 10%: 9명이면 그래도 한도 위지만 창문이 열려 있으면 묻지 않는다
        val window = Car(real)
        window.drive(occupants = 9, recirc = false, fan = 0)
        window.send { WindowOpenChanged(it, 10) }
        window.run(3600)
        assertTrue(window.engine.estimatedPpm > 2000, "추정 ${window.engine.estimatedPpm}")
        assertEquals(0, window.count<WakeEvent.Propose>())

        // 이미 최대 풍량(외기 유입이 아주 적은 차로 설정)
        val maxed = Car(real.copy(freshAchMax = 6.0))
        maxed.drive(occupants = 4, recirc = false, fan = 8)
        maxed.run(3600)
        assertTrue(maxed.engine.estimatedPpm > 2000, "추정 ${maxed.engine.estimatedPpm}")
        assertEquals(0, maxed.count<WakeEvent.Propose>())
    }

    // ---------- 8. 발화 간격 ----------

    @Test
    fun `문장을 읽을 시간은 글자당 0,2초이고 최소 2초다`() {
        assertEquals(2_000L, WakeEngine.speechHoldMs("네."))
        assertEquals(2_000L, WakeEngine.speechHoldMs("원래대로 돌렸어요."))
        assertEquals(4_000L, WakeEngine.speechHoldMs("알겠어요. 20분 뒤에 다시 볼게요."))
        assertEquals(5_800L, WakeEngine.speechHoldMs("추정 900ppm까지 내려갔어요. 원래대로 돌렸어요.")) // 29자

        val gate = SpeechGate()
        gate.markUtterance(10_000, 3_000)
        assertEquals("speaking", gate.blockedReason(12_999))
        assertNull(gate.blockedReason(13_000))
        // 더 일찍 끝나는 문장이 뒤에 와도 앞 문장이 끝날 때까지 막는다
        gate.markUtterance(20_000, 5_000)
        gate.markUtterance(21_000, 2_000)
        assertEquals("speaking", gate.blockedReason(24_999))
        assertNull(gate.blockedReason(25_000))
    }

    @Test
    fun `거절 뒤 Done 문장을 읽는 4초 동안은 대기 중인 다른 제안을 내지 않는다`() {
        // 답을 오래 기다리는 설정: 제안 사이 간격 20초가 지난 뒤에 거절해서 Done 문장의 영향만 본다
        val car = Car(real.copy(answerTimeoutSec = 30))
        car.drive(occupants = 2)
        assertNotNull(car.runUntil<WakeEvent.Propose>(1800))
        car.run(5)
        assertTrue(car.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.isEmpty()) // 동의 대기 중이라 큐에서 기다린다
        car.run(16)
        assertEquals("AWAITING_ANSWER", car.engine.phaseName)
        val done = car.no().single()
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 20분 뒤에 다시 볼게요."), done)
        assertEquals(4_000L, WakeEngine.speechHoldMs((done as WakeEvent.Done).text!!))
        // 전에는 바로 다음 tick(1초 뒤)에 졸음 제안이 나와 이 문장을 끊었다
        assertEquals(listOf<WakeEvent>(WakeEvent.Deferred(TriggerKind.DROWSY, "speaking")), car.run(1))
        assertTrue(car.run(2).isEmpty())
        assertEquals(TriggerKind.DROWSY, car.run(1).filterIsInstance<WakeEvent.Propose>().single().kind)
    }

    @Test
    fun `RestEnded 문장을 읽는 동안에는 새 제안을 내지 않는다`() {
        val car = Car(real)
        car.drive(occupants = 1)
        car.addWaypointViaDrowsy()
        car.send { RestStopProximity(it, within = true) }
        car.send { GearChanged(it, Gear.PARK) }
        car.run(15 * 60)
        val ended = car.send { GearChanged(it, Gear.DRIVE) }.filterIsInstance<WakeEvent.RestEnded>().single()
        assertEquals("15분 푹 쉬셨어요. 조심히 가요", ended.text)
        val holdSec = ((WakeEngine.speechHoldMs(ended.text) + 999) / 1000).toInt() // 17자 → 3.4초 → 4초째에 풀린다
        assertEquals(4, holdSec)

        car.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        val got = car.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        assertEquals(listOf<WakeEvent>(WakeEvent.Deferred(TriggerKind.DROWSY, "speaking")), got)
        assertTrue(car.run(holdSec - 1).none { it is WakeEvent.Propose })
        val p = car.run(1).filterIsInstance<WakeEvent.Propose>().single()
        assertEquals(TriggerKind.DROWSY, p.kind)
    }

    @Test
    fun `ProposalExpired 문장을 읽는 동안에도 다음 제안을 미룬다`() {
        // 시간 상한 경로만 보도록 CO2 생성을 0 으로 둔 데모 설정
        val demo = WakeConfig(
            staleAirMinutes = 40, ventilateSeconds = 20, declineCooldownMinutes = 20, answerTimeoutSec = 20,
            demoFast = true, co2PerPersonLph = 0.0,
        )
        val car = Car(demo)
        car.drive(occupants = 1)
        assertEquals(40, car.runUntil<WakeEvent.Propose>(60))
        car.run(1)
        assertTrue(car.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.isEmpty()) // 동의 대기 중이라 큐에서 기다린다
        // SpeechFinished 가 없으면 제안 뒤 20초 + 여유 6초에 만료된다(제안 사이 간격 20초는 이미 지났다)
        assertEquals(25, car.runUntil<WakeEvent.ProposalExpired>(40))
        assertEquals(WakeEvent.ProposalExpired(WakeEngine.EXPIRED_TEXT), car.last<WakeEvent.ProposalExpired>())
        // "다음에 다시 물을게요." 12자 → 2.4초. 3초째에 졸음 제안이 나간다
        val next = car.run(2)
        assertTrue(next.none { it is WakeEvent.Propose }, next.toString())
        assertEquals(WakeEvent.Deferred(TriggerKind.DROWSY, "speaking"), next.first())
        assertEquals(TriggerKind.DROWSY, car.run(1).filterIsInstance<WakeEvent.Propose>().single().kind)
    }

    // ---------- 9. 핸들 ----------

    private val steerDemo = WakeConfig(demoFast = true, co2PerPersonLph = 0.0, declineCooldownMinutes = 20, steeringAssist = true, allowWindow = true)

    private fun steerCar(): Car = Car(steerDemo).also {
        it.send { GearChanged(it, Gear.DRIVE) }
        it.run(30) // 데모에서는 트립 30초부터 본다
    }

    /** center 도 근처로 7초 가만히 있다가 center + jerk 도로 꺾는다 */
    private fun Car.quietThenJerk(center: Float = 0f, jerk: Float = 9f) {
        send { SteeringAngle(it, center) }
        repeat(7) { i ->
            run(1)
            send { SteeringAngle(it, center + if (i % 2 == 0) 0.4f else -0.3f) }
        }
        run(1)
        send { SteeringAngle(it, center + jerk) }
    }

    @Test
    fun `속도를 알면 60km 미만에서는 핸들 급보정을 세지 않고 모르면 센다`() {
        val car = steerCar()
        // 시내 속도: 세지 않는다
        car.send { LocationUpdate(it, 36.9, 127.1, 40.0) }
        car.quietThenJerk()
        car.quietThenJerk(jerk = -9f)
        assertEquals(0, car.engine.steerEvents)
        car.send { LocationUpdate(it, 36.9, 127.1, 59.9) }
        car.quietThenJerk()
        assertEquals(0, car.engine.steerEvents)
        // 60km/h 부터 센다
        car.send { LocationUpdate(it, 36.9, 127.1, 60.0) }
        car.quietThenJerk()
        assertEquals(1, car.engine.steerEvents)
        car.send { LocationUpdate(it, 36.9, 127.1, 100.0) }
        car.quietThenJerk()
        assertEquals(2, car.engine.steerEvents)
        // 속도를 모르는 위치 갱신이 오면 모르는 것으로 보고 센다
        car.send { LocationUpdate(it, 36.9, 127.1, null) }
        car.quietThenJerk()
        assertEquals(1, car.count<WakeEvent.Propose>())
        assertTrue(car.last<WakeEvent.Propose>()!!.text.startsWith("핸들 보정이 잦아졌어요. "))
    }

    @Test
    fun `무조작 구간의 각도가 5도를 넘으면 선회 중으로 보고 세지 않는다`() {
        val car = steerCar()
        // 램프나 긴 곡선: 8도로 잡고 있다가 더 꺾음
        car.quietThenJerk(center = 8f)
        car.quietThenJerk(center = -6f, jerk = -9f)
        assertEquals(0, car.engine.steerEvents)
        // 5도 안쪽은 직진으로 본다
        car.quietThenJerk(center = 4f)
        assertEquals(1, car.engine.steerEvents)
        car.quietThenJerk(center = -4f, jerk = -9f)
        assertEquals(2, car.engine.steerEvents)
        assertEquals(5.0f, WakeEngine.STEER_STRAIGHT_MAX_DEG)
        assertEquals(60.0, WakeEngine.STEER_MIN_SPEED_KPH)
    }

    // ---------- 10. 졸음 경고 집계 ----------

    @Test
    fun `핸들 경로 제안이 떠 있을 때 차의 졸음 경고가 오면 그때 처음으로 한 번 센다`() {
        val car = steerCar()
        repeat(3) { car.quietThenJerk() }
        assertEquals(1, car.count<WakeEvent.Propose>())
        assertEquals("핸들 보정이 잦아졌어요. 찬 바람 넣고 창문 조금 열까요?", car.last<WakeEvent.Propose>()!!.text)
        // 제안이 떠 있는 동안 경고등이 켜졌다. 새 제안은 없지만 졸음 경고로는 센다
        assertTrue(car.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.isEmpty())
        car.no()
        val card = car.arrive()
        assertEquals(1, card.drowsySignals)
        assertEquals("오늘 졸음 경고 1번이 있었어요.", card.headline)
        assertTrue(card.lines.any { it.endsWith("졸음 경고(운전자 주의 경고)") }, card.lines.toString())
        // 핸들 경로로 나간 제안은 로그에서도 졸음이라고 부르지 않는다
        assertTrue(card.lines.any { it.endsWith("제안 거절(쉬어 가기)") }, card.lines.toString())
    }

    // ---------- 11. 문장, 쉼터 표시 이름 ----------

    @Test
    fun `쉼터 표시 이름은 쉼터나 휴게소로 끝나지 않을 때만 졸음쉼터를 붙인다`() {
        assertEquals("망향 졸음쉼터", restStopDisplayName("망향"))
        assertEquals("망향졸음쉼터", restStopDisplayName("망향졸음쉼터"))
        assertEquals("망향 졸음쉼터", restStopDisplayName("망향 졸음쉼터"))
        assertEquals("안성휴게소", restStopDisplayName("안성휴게소"))
        assertEquals("죽전 휴게소", restStopDisplayName("죽전 휴게소"))
        assertEquals("옥산(부산방향) 졸음쉼터", restStopDisplayName(" 옥산(부산방향) "))
        assertEquals("졸음쉼터", restStopDisplayName(""))
        assertEquals("망향 졸음쉼터", WakeEngine.restStopDisplayName("망향"))
    }

    @Test
    fun `졸음 제안 문장은 반올림한 km 와 노선 첫 단어와 쉼터 표시 이름을 쓴다`() {
        val car = Car(real)
        car.drive(occupants = 1, recirc = false)
        car.send { RestStopCandidate(it, stop.copy(distanceKm = 12.5)) }
        val p = car.drowsyProposal()
        assertEquals("졸음 경고가 떴어요. 찬 바람과 창문 조금, 13km 앞 경부선 망향 졸음쉼터 경유까지 할까요?", p.text)
        assertEquals("DROWSY", car.engine.lastProposalVariant)
        car.no()

        // 비: 창문은 두고. 노선 이름이 비어 있으면 뺀다
        car.run(real.declineCooldownMinutes * 60)
        car.send { WiperChanged(it, true) }
        car.send { RestStopCandidate(it, RestStopInfo("안성휴게소", "", 7.4, 37.0, 127.2)) }
        val rain = car.drowsyProposal()
        assertEquals("졸음 경고가 떴어요. 비가 와서 창문은 두고, 찬 바람과 7km 앞 안성휴게소 경유까지 할까요?", rain.text)
        assertFalse(rain.actions.any { it is Action.OpenWindow })
        car.no()

        // 쉼터 없음 + 비
        car.run(real.declineCooldownMinutes * 60)
        car.send { RestStopCandidate(it, null) }
        assertEquals("졸음 경고가 떴어요. 비가 와서 창문은 두고 찬 바람만 넣을까요?", car.drowsyProposal().text)
    }

    @Test
    fun `경유지에 넣은 쉼터는 RestStarted 와 로그와 다음 제안 문장에서 같은 표시 이름으로 나온다`() {
        val car = Car(real)
        car.drive(occupants = 1, recirc = false)
        car.addWaypointViaDrowsy()
        // 이미 경유지에 있는 쉼터: 다시 넣지 않고 그 사실을 알린 뒤 질문으로 끝낸다
        car.run(30)
        val again = car.drowsyProposal()
        assertEquals("졸음 경고가 떴어요. 망향 졸음쉼터는 경유지에 있어요. 찬 바람 넣고 창문 조금 열까요?", again.text)
        assertFalse(again.actions.any { it is Action.AddRestStop })
        car.no()

        car.send { RestStopProximity(it, within = true) }
        val rest = car.send { GearChanged(it, Gear.PARK) }.filterIsInstance<WakeEvent.RestStarted>().single()
        assertEquals("망향 졸음쉼터", rest.stopName)
        assertEquals(restStopDisplayName(stop.name), rest.stopName)
        car.run(120)
        car.send { GearChanged(it, Gear.DRIVE) }
        val card = car.arrive()
        assertTrue(card.lines.any { it.endsWith("망향 졸음쉼터에서 2분 휴식") }, card.lines.toString())

        // 지나친 경우의 로그도 같은 이름
        val passed = Car(real)
        passed.drive(occupants = 1, recirc = false)
        passed.addWaypointViaDrowsy()
        passed.send { RestStopProximity(it, within = false, passed = true) }
        val lines = passed.arrive().lines
        assertTrue(lines.any { it.endsWith("망향 졸음쉼터 경유지 추가") }, lines.toString())
        assertTrue(lines.any { it.endsWith("망향 졸음쉼터 지나침") }, lines.toString())
    }

    @Test
    fun `모든 제안 문장은 질문으로 끝나고 수치에는 추정이 붙는다`() {
        val texts = mutableListOf<String>()
        // 환기 제안: 1~4명 한 시간(2분만, 바깥 공기로 두기, 두 번 뒤 유지), 야간, 풍량 올리기, 시간 상한
        for (n in 1..4) {
            val car = Car(real); car.autoAnswer = true; car.drive(occupants = n); car.run(3600)
            texts += car.proposals.map { it.text }
        }
        Car(real).also { it.autoAnswer = true; it.driveAtNight(occupants = 2); it.run(1200); texts += it.proposals.map { p -> p.text } }
        Car(real).also { it.autoAnswer = false; it.drive(occupants = 4, recirc = false, fan = 0); it.run(3600); texts += it.proposals.map { p -> p.text } }
        Car(real.copy(co2LimitPpm = 1_000_000)).also { it.drive(occupants = 1); it.run(1800); texts += it.proposals.map { p -> p.text } }
        Car(real.copy(ventilateSeconds = 20)).also { it.drive(occupants = 1, fan = 0); it.run(1500); texts += it.proposals.map { p -> p.text } }
        // 졸음 제안: 쉼터 있음/없음/이미 경유지 × 비
        for (rain in listOf(false, true)) {
            val car = Car(real)
            car.drive(occupants = 1, recirc = false)
            car.send { WiperChanged(it, rain) }
            texts += car.drowsyProposal().text // 쉼터 없음
            car.no(); car.run(real.declineCooldownMinutes * 60)
            car.send { RestStopCandidate(it, stop) }
            texts += car.drowsyProposal().text // 쉼터 있음
            car.yes(); assertNotNull(car.runUntil<WakeEvent.Done>(600)); car.run(30)
            texts += car.drowsyProposal().text // 이미 경유지
        }
        // 핸들 경로
        steerCar().also { c -> repeat(3) { c.quietThenJerk() }; texts += c.proposals.map { it.text } }

        assertTrue(texts.size >= 18, "모은 문장 ${texts.size}개")
        val variants = texts.map { it.substringAfterLast(". ") }.toSet()
        assertTrue(variants.size >= 8, variants.toString())
        for (t in texts) {
            assertTrue(t.endsWith("?"), t)
            // 통보하는 말투, 단정하는 낱말이 없다
            assertFalse(t.contains("게요"), t)
            assertFalse(t.contains("측정") || t.contains("감지") || t.contains("원인"), t)
            // ppm 수치는 괄호 안에 "추정" 과 함께만 나온다
            if (t.contains("ppm")) assertTrue(Regex("""\(추정 [0-9,]+ppm\)""").containsMatchIn(t), t)
        }
        assertTrue(texts.any { it.startsWith("오래 달려서 조금 일찍 물어요. ") })
        assertTrue(texts.any { it.startsWith("핸들 보정이 잦아졌어요. ") })
        assertTrue(texts.any { it.contains("풍량을 올릴까요?") })
        assertTrue(texts.any { it.startsWith("내기순환으로 30분째예요. ") })
        assertTrue(texts.any { it == WakeEngine.KEEP_FRESH_PROPOSAL })
    }

    // ---------- 12. 도착 카드 ----------

    @Test
    fun `도착 카드 문장은 환기했다 경고가 있었다고만 말하고 lines 에 공기 줄을 넣지 않는다`() {
        // 환기만: 지켰다고 하지 않고 했다고만 말한다
        val vent = Car(real)
        vent.autoAnswer = true
        vent.drive(occupants = 1)
        vent.run(30 * 60)
        val ventCard = vent.arrive()
        assertEquals(1, ventCard.ventilations)
        assertEquals(0, ventCard.minutesOverLimit)
        assertTrue(ventCard.peakPpm >= 2000)
        assertEquals("오늘 환기 1번 했어요.", ventCard.headline)
        assertEquals(listOf("출발 20분 뒤 바깥 공기로 환기"), ventCard.lines)

        // 졸음 경고만
        val drowsy = Car(real)
        drowsy.drive(occupants = 1, recirc = false)
        drowsy.drowsyProposal()
        drowsy.no()
        val drowsyCard = drowsy.arrive()
        assertEquals("오늘 졸음 경고 1번이 있었어요.", drowsyCard.headline)
        assertEquals(listOf("출발 직후 졸음 경고(운전자 주의 경고)", "출발 직후 제안 거절(졸음)"), drowsyCard.lines)

        // 환기와 졸음 경고. 최고치가 높아도 공기 줄은 없다(앱이 peakPpm 으로 한 줄만 보여 준다)
        val both = Car(real)
        both.autoAnswer = true
        both.drive(occupants = 4)
        both.run(10 * 60)
        both.autoAnswer = null
        both.drowsyProposal()
        both.yes()
        assertNotNull(both.runUntil<WakeEvent.Done>(600))
        val bothCard = both.arrive()
        assertEquals("오늘 환기 2번, 졸음 경고 1번이 있었어요.", bothCard.headline)
        assertTrue(bothCard.peakPpm > 1000)
        for (card in listOf(ventCard, drowsyCard, bothCard)) {
            assertTrue(card.lines.size <= 4)
            assertTrue(card.lines.none { it.contains("ppm") || it.contains("탑승") }, card.lines.toString())
            assertFalse(card.headline.contains("지켰") || card.headline.contains("넘겼어요") || card.headline.contains("신호"), card.headline)
        }
    }

    @Test
    fun `도착 카드에서 ppm 을 말하는 문장에는 추정이 붙는다`() {
        // 한도 위로 달린 경우와 한도 아래로 끝난 경우
        val over = Car(real)
        over.drive(occupants = 2)
        assertNotNull(over.runUntil<WakeEvent.Propose>(1800))
        over.no()
        over.run(10 * 60)
        val overCard = over.arrive()
        assertEquals("오늘은 추정 2,000ppm 위로 10분 달렸어요. 무사히 도착했어요.", overCard.headline)

        val under = Car(real.copy(staleAirMinutes = 10))
        under.autoAnswer = true
        under.drive(occupants = 1)
        under.run(15 * 60)
        val underCard = under.arrive()
        assertEquals("오늘 차 안 공기를 추정 2,000ppm 아래로 지켰어요.", underCard.headline)
        for (h in listOf(overCard.headline, underCard.headline)) {
            assertTrue(Regex("""추정 [0-9,]+ppm""").containsMatchIn(h), h)
        }
    }

    // ---------- 13. 세션 재시작 ----------

    @Test
    fun `P 뒤 3분 안에 다시 D 면 추정치를 이어 가고 카드를 닫았어도 같다`() {
        val car = Car(real)
        car.drive(occupants = 4)
        car.run(200)
        val atPark = car.engine.estimatedPpm
        assertTrue(atPark > 1800)
        car.arrive()
        car.run(90)
        car.engine.reset() // 도착 카드를 닫았다
        assertEquals(TripState.IDLE, car.engine.tripState)
        car.run(90) // P 뒤 정확히 3분
        car.send { GearChanged(it, Gear.DRIVE) }
        // 세워 둔 동안은 흐르지 않은 것으로 본다. 처음 값(630)으로 돌아가지 않는다
        assertEquals(atPark, car.engine.estimatedPpm)
        car.run(1)
        assertTrue(car.airs.last().ppm in atPark..atPark + 10)
        // 이어 간 값에서 바로 한도에 닿는다(처음부터였다면 3분 40초쯤 걸린다)
        val sec = car.runUntil<WakeEvent.Propose>(600)!!
        assertTrue(sec < 30, "다시 출발 뒤 ${sec}초")
        val card = car.arrive()
        assertTrue(card.peakPpm >= 2000)
        // 새 세션의 집계는 처음부터다
        assertEquals(0, card.ventilations)
        assertEquals(0, card.tripMinutes)
    }

    @Test
    fun `데모에서는 P 뒤 3초까지 이어 가고 넘으면 처음 값으로 돌아간다`() {
        val demo = real.copy(demoFast = true)
        val kept = Car(demo)
        kept.drive(occupants = 1)
        kept.run(10)
        val atPark = kept.engine.estimatedPpm
        kept.arrive()
        kept.run(3)
        kept.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(atPark, kept.engine.estimatedPpm)

        val fresh = Car(demo)
        fresh.drive(occupants = 1)
        fresh.run(10)
        fresh.arrive()
        fresh.run(4)
        fresh.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(startPpm.roundToInt(), fresh.engine.estimatedPpm)
        assertEquals(3, WakeEngine.RESTART_KEEP_MINUTES)
    }

    // ---------- 한 시간 주행(기본 설정, 풍량 3/8, 제안에는 5초 뒤 전부 동의) ----------

    private fun hourDrive(occupants: Int): Pair<Car, ArrivalSummary> {
        val car = Car(real)
        car.autoAnswer = true
        car.drive(occupants = occupants)
        car.run(3600)
        val card = car.arrive()
        fun mmss(sec: Int) = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
        println(
            "한 시간 ${occupants}명: 제안 ${car.proposals.size}회 [" +
                car.proposals.joinToString { "${mmss(it.atSec)} ${it.variant}" } +
                "] 최고 추정 ${car.peakSeen}ppm, 환기 ${card.ventilations}회, 바깥 공기 ${card.freshAirMinutes}분, 한도 위 ${card.minutesOverLimit}분",
        )
        // 공통: 최고치는 한도의 1.2배를 넘지 않고, 카드의 최고치와 같고, 풍량을 올리자는 제안은 없다
        assertTrue(car.peakSeen >= real.co2LimitPpm, "최고 ${car.peakSeen}")
        assertTrue(car.peakSeen <= real.co2LimitPpm * 1.2, "최고 ${car.peakSeen}")
        assertTrue(abs(card.peakPpm - car.peakSeen) <= 1)
        assertTrue(car.proposals.none { it.variant == "FAN_UP" || it.variant == "TIME_CAP" })
        assertEquals(car.proposals.size, card.ventilations)
        assertEquals(0, card.declined)
        return car to card
    }

    /** "2분만" 환기 한 바퀴(답 5초 + 환기 120초 + 다시 한도까지)의 초. 환기 중 풍량은 4/8 */
    private fun cycleSeconds(n: Int, from: Double): Double = 5 + 120 + recurrence(n, freshAch(4, 8), from = from) * 60

    @Test
    fun `한 시간 1명은 약 20분에 처음 묻고 2분만 환기를 세 번 한다`() {
        val (car, card) = hourDrive(1)
        assertEquals(listOf("REVERT", "REVERT", "REVERT"), car.proposals.map { it.variant })
        val first = ceil(minutesUp(1, startPpm) * 60).toInt()
        assertEquals(first, car.proposals[0].atSec) // 20분 10초
        // 그 뒤로는 약 19분마다(환기 2분 + 다시 차는 약 17분)
        for ((a, b) in car.proposals.zipWithNext()) {
            assertNear(cycleSeconds(1, a.ppm + 4.0), (b.atSec - a.atSec).toDouble(), 10.0)
            assertTrue(b.atSec - a.atSec in 18 * 60..20 * 60, "간격 ${b.atSec - a.atSec}초")
        }
        assertTrue(car.peakSeen <= 2010, "최고 ${car.peakSeen}")
        assertEquals(6, card.freshAirMinutes)
        assertEquals("오늘 환기 3번 했어요.", card.headline)
    }

    @Test
    fun `한 시간 2명은 2분만 두 번 뒤 세 번째에 계속 바깥 공기로 둘지 묻고 그 뒤로는 묻지 않는다`() {
        val (car, card) = hourDrive(2)
        assertEquals(listOf("REVERT", "REVERT", "KEEP_AFTER_TWO"), car.proposals.map { it.variant })
        assertEquals(ceil(minutesUp(2, startPpm) * 60).toInt(), car.proposals[0].atSec) // 8분 6초
        for ((a, b) in car.proposals.zipWithNext()) {
            assertNear(cycleSeconds(2, a.ppm + 12.0), (b.atSec - a.atSec).toDouble(), 10.0)
            assertTrue(b.atSec - a.atSec in 7 * 60..9 * 60, "간격 ${b.atSec - a.atSec}초")
        }
        assertEquals(WakeEngine.KEEP_FRESH_PROPOSAL, car.proposals[2].text)
        assertTrue(car.peakSeen <= 2030, "최고 ${car.peakSeen}")
        // 24분쯤부터 끝까지 바깥 공기(풍량 4/8)로 달렸다
        assertFalse(car.recirc)
        assertEquals(4, car.fan)
        assertTrue(card.freshAirMinutes in 38..41, "바깥 공기 ${card.freshAirMinutes}분")
        assertNear(steady(2, freshAch(4, 8)), car.engine.estimatedPpm.toDouble(), 1.0)
    }

    @Test
    fun `한 시간 3명은 약 5분에 바깥 공기로 둘지 한 번만 묻는다`() {
        val (car, card) = hourDrive(3)
        assertEquals(listOf("KEEP"), car.proposals.map { it.variant })
        assertEquals(ceil(minutesUp(3, startPpm) * 60).toInt(), car.proposals[0].atSec) // 5분 5초
        assertEquals("3명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?", car.proposals[0].text)
        assertTrue(car.peakSeen <= 2040, "최고 ${car.peakSeen}")
        assertTrue(card.freshAirMinutes in 54..56, "바깥 공기 ${card.freshAirMinutes}분")
        assertNear(steady(3, freshAch(4, 8)), car.engine.estimatedPpm.toDouble(), 1.0)
    }

    @Test
    fun `한 시간 4명은 첫 제안부터 바깥 공기로 둘지 묻고 최고치가 한도의 1,2배를 넘지 않는다`() {
        val (car, card) = hourDrive(4)
        assertEquals("KEEP", car.proposals.first().variant)
        assertEquals(1, car.proposals.size)
        assertEquals(ceil(minutesUp(4, startPpm) * 60).toInt(), car.proposals[0].atSec) // 3분 43초
        assertEquals("4명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?", car.proposals[0].text)
        // 답을 기다린 5초 동안 조금 더 오른 값이 최고치다. 전에는 두 번째 제안이 2,800ppm 에서 나왔다
        assertTrue(car.peakSeen <= 2060, "최고 ${car.peakSeen}")
        assertTrue(car.peakSeen <= real.co2LimitPpm * 1.2)
        assertEquals(0, card.minutesOverLimit)
        assertTrue(card.freshAirMinutes in 55..57, "바깥 공기 ${card.freshAirMinutes}분")
        assertNear(steady(4, freshAch(4, 8)), car.engine.estimatedPpm.toDouble(), 1.0)
    }

    @Test
    fun `야간 장거리 한도에서 첫 제안은 1명 약 13분 2명 약 5분 반 3명 약 3분 반 4명 약 2분 반이다`() {
        val firsts = mutableMapOf<Int, Int>()
        for (n in 1..4) {
            val car = Car(real)
            car.driveAtNight(occupants = n)
            val sec = car.runUntil<WakeEvent.Propose>(1800)!!
            assertEquals(ceil(minutesUp(n, startPpm, 1600.0) * 60).toInt(), sec)
            assertTrue(car.proposals.single().text.startsWith("오래 달려서 조금 일찍 물어요. "))
            assertTrue(car.proposals.single().text.contains("(추정 1,600ppm)"))
            firsts[n] = sec
        }
        assertTrue(firsts[1]!! in 12 * 60 + 30..13 * 60, firsts.toString())
        assertTrue(firsts[2]!! in 5 * 60 + 15..5 * 60 + 45, firsts.toString())
        assertTrue(firsts[3]!! in 3 * 60 + 15..3 * 60 + 45, firsts.toString())
        assertTrue(firsts[4]!! in 2 * 60 + 20..2 * 60 + 50, firsts.toString())
        println("야간 한도 첫 제안(초): $firsts")
    }

    // ---------- 데모(60배)에서 답을 기다리는 동안 ----------

    @Test
    fun `데모에서도 답과 제어 결과를 기다리는 동안은 실제 속도로 흘러 4명이 10초 뒤에 동의해도 최고치가 한도의 1,2배 이하다`() {
        val car = Car(real.copy(demoFast = true, ventilateSeconds = 20))
        car.autoAnswer = true
        car.answerDelaySec = 10
        car.drive(occupants = 4)
        assertNotNull(car.runUntil<WakeEvent.Propose>(30))
        val atProposal = car.engine.estimatedPpm
        assertEquals("KEEP", car.engine.lastProposalVariant)
        car.run(9)
        // 60배였다면 9초에 2,000ppm 넘게 더 올랐을 것이다. 실제 속도로는 50ppm 남짓
        assertEquals("AWAITING_ANSWER", car.engine.phaseName)
        assertTrue(car.engine.estimatedPpm - atProposal in 30..80, "$atProposal → ${car.engine.estimatedPpm}")
        car.run(1) // 10초 뒤 동의 → 바깥 공기로 둔다
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "바깥 공기로 둘게요."), car.last<WakeEvent.Done>())
        car.run(60)
        val card = car.arrive()
        assertTrue(car.peakSeen <= real.co2LimitPpm * 1.2, "최고 ${car.peakSeen}")
        assertTrue(card.peakPpm <= real.co2LimitPpm * 1.2, "카드 최고 ${card.peakPpm}")
        assertTrue(card.minutesOverLimit <= 2, "한도 위 ${card.minutesOverLimit}")
        println("데모 4명, 10초 뒤 동의: 제안 때 ${atProposal}ppm, 최고 ${card.peakPpm}ppm")

        // 실제 속도 설정은 그대로다: 같은 10초에 같은 만큼 오른다
        val realCar = Car(real)
        realCar.drive(occupants = 4)
        assertNotNull(realCar.runUntil<WakeEvent.Propose>(600))
        val before = realCar.engine.estimatedPpm
        realCar.run(9)
        assertTrue(realCar.engine.estimatedPpm - before in 30..80)
    }
}

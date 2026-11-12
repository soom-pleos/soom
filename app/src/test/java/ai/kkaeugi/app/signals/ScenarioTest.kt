package ai.kkaeugi.app.signals

import ai.kkaeugi.app.DemoSettings
import ai.kkaeugi.app.ExecEnd
import ai.kkaeugi.app.ExecutionTracker
import ai.kkaeugi.app.speech.speechEstimateMs
import ai.kkaeugi.app.vehicle.MockCar
import ai.kkaeugi.app.vehicle.VehicleActuator
import ai.kkaeugi.app.vehicle.VehicleActuatorTest
import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.ArrivalSummary
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.GearChanged
import ai.kkaeugi.engine.LowBeamChanged
import ai.kkaeugi.engine.OccupantsChanged
import ai.kkaeugi.engine.RestStopCandidate
import ai.kkaeugi.engine.RestStopInfo
import ai.kkaeugi.engine.RestStopProximity
import ai.kkaeugi.engine.Signal
import ai.kkaeugi.engine.SpeechFinished
import ai.kkaeugi.engine.Telltale
import ai.kkaeugi.engine.TelltaleChanged
import ai.kkaeugi.engine.TripState
import ai.kkaeugi.engine.TripTimeUpdate
import ai.kkaeugi.engine.TrustState
import ai.kkaeugi.engine.DestinationEta
import ai.kkaeugi.engine.FrontPassengerChanged
import ai.kkaeugi.app.DemoText
import ai.kkaeugi.app.HomeHold
import ai.kkaeugi.engine.UserAnswer
import ai.kkaeugi.engine.WakeEngine
import ai.kkaeugi.engine.WakeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 내장 시연 시나리오를 실제 엔진, 액추에이터(모의 차량)와 함께 가상 시계로 끝까지 돌린다.
 * 앱의 WakeViewModel 이 하는 일(이벤트 처리, TTS 완료, 1초 tick)을 작게 흉내 낸다. TTS 시간은 글자 수로 추정한다.
 * 확인하는 것: 제안 순서와 문장, 원복 없는 실행의 스냅샷 폐기, 쉼 모드와 도착 카드, 그리고 처음부터 끝까지 걸리는 시간.
 */
class ScenarioTest {

    /** pressAfterReadMs: 다 읽고 이만큼 뒤에 사람이 "응"을 누른다. null 이면 아무도 누르지 않는다(시나리오가 대신 누른다) */
    /** family: v0.7.3 가족 시나리오(familySteps)를 걷기 쉼을 켠 설정으로 돌린다. 아니면 v0.7.2 시나리오를 그때 설정 그대로 */
    private class Sim(private val pressAfterReadMs: Long?, delegated: Boolean = false, private val family: Boolean = false) : DemoScenario.Hands {
        private val config = DemoSettings.build(demoFast = true, walkBreak = family)
        val engine = WakeEngine(config).also { if (delegated) it.setTrust(TrustState(delegated = true)) }
        var now = 100_000L
        private val start = now
        private val pending = ArrayDeque<Signal>()
        val controlCalls = mutableListOf<String>()
        val car = MockCar(emit = { pending += it }, now = { now }, onControlCall = { controlCalls += it })
        private val actuator = VehicleActuator(
            SignalBus(), kotlinx.coroutines.GlobalScope, car, VehicleActuatorTest.FakeNav(), hasVehicle = { false },
            now = { now }, dispatcher = Dispatchers.Unconfined, mockControls = car,
        )
        private val tracker = ExecutionTracker()
        private val stop = RestStopInfo("석적", "경부선 서울방향", 12.0, 36.0516, 128.4081)

        // 화면 상태
        private var proposalOpen = false
        private var speechEndsAt: Long? = null
        private var countdownFrom: Long? = null
        private var running = false
        private var tripMinutes = 0

        // 기록
        val proposals = mutableListOf<String>()
        val proposalAt = mutableListOf<Long>()
        val said = mutableListOf<String>()
        val kept = mutableListOf<List<Action>>()
        val captions = mutableListOf<String>()
        var reverts = 0
        var expired = 0
        var autoPressed = 0
        var restStartedName: String? = null
        var restEndedText: String? = null
        var arrival: ArrivalSummary? = null
        var arrivalAt = 0L
        val runningLabels = mutableSetOf<String>()
        /** 쉼 모드 전까지 본 추정 최고치, 쉬는 동안 온 추정 이벤트 수, 쉼이 끝난 뒤 첫 추정치 */
        var peakBeforeRest = 0
        var estimatesWhileResting = 0
        var firstAfterRest: Int? = null
        private var resting = false
        private var rested = false
        private var restRemainingSec: Int? = null
        private var restLine: String? = null
        val announces = mutableListOf<String>()
        var restWalk = false
        val proposalKinds = mutableListOf<ai.kkaeugi.engine.TriggerKind>()
        val passengerFlags = mutableListOf<Boolean>()
        var heldForRest = 0
        var trust: TrustState? = null
        val deferred = mutableListOf<String>()

        fun elapsedSec(t: Long = now) = (t - start) / 1000.0

        private fun send(s: Signal) { pending += s; drain() }

        private fun drain() {
            while (pending.isNotEmpty()) handle(engine.accept(pending.removeFirst()))
        }

        private fun handle(events: List<WakeEvent>) {
            for (e in events) when (e) {
                is WakeEvent.Propose -> {
                    proposalOpen = true; countdownFrom = null
                    // 쉼 카드 안내를 읽던 중이면 앱은 그 문장에 질문을 이어서 읽는다
                    speechEndsAt = now + speechEstimateMs(listOfNotNull(restLine, e.text).joinToString(" "))
                    restLine = null
                    proposals += e.text; proposalAt += now; passengerFlags += e.passengerMayAnswer; proposalKinds += e.kind
                }
                is WakeEvent.Announce -> {
                    proposalOpen = true; countdownFrom = null
                    speechEndsAt = now + speechEstimateMs(e.text)
                    announces += e.text
                }
                is WakeEvent.TrustChanged -> trust = e.state
                is WakeEvent.Deferred -> deferred += e.reason
                is WakeEvent.RestTick -> restRemainingSec = e.remainingSec
                is WakeEvent.ProposalExpired -> { proposalOpen = false; expired++; said += e.text }
                is WakeEvent.Execute -> {
                    proposalOpen = false; running = true
                    if (tracker.onExecute(e.actions)) reverts++
                    val results = runBlocking { actuator.run(e.actions) {} }
                    pending += results
                }
                is WakeEvent.Running -> { running = true; runningLabels += e.label }
                is WakeEvent.Done -> {
                    when (val end = tracker.onDone(resting = engine.isResting)) {
                        is ExecEnd.Kept -> { actuator.discardSnapshots(end.actions); kept += end.actions }
                        ExecEnd.HeldForRest -> heldForRest++
                        else -> {}
                    }
                    proposalOpen = false; running = false
                    e.text?.let { said += it }
                }
                is WakeEvent.TripStateChanged -> if (e.state == TripState.DRIVING) pending += RestStopCandidate(now, stop)
                is WakeEvent.RestStarted -> {
                    restStartedName = e.stopName; proposalOpen = false; running = false; resting = true
                    restRemainingSec = e.targetMinutes
                    restWalk = e.walk
                    restLine = DemoText.restLine(e.stopName, e.targetMinutes, demo = true, forSpeech = true, walk = e.walk)
                }
                is WakeEvent.RestEnded -> { restEndedText = e.text; said += e.text; resting = false; rested = true; restRemainingSec = null }
                is WakeEvent.AirEstimate -> when {
                    resting -> estimatesWhileResting++
                    rested -> if (firstAfterRest == null) firstAfterRest = e.ppm
                    else -> peakBeforeRest = maxOf(peakBeforeRest, e.ppm)
                }
                is WakeEvent.ArrivalCard -> { arrival = e.card; arrivalAt = now }
                else -> {}
            }
        }

        // ---- 시나리오가 누르는 것 ----
        override fun gear(g: Gear) = send(GearChanged(now, g))
        override fun recirculation(on: Boolean) { car.userRecirculation(on); drain() }
        override fun occupants(n: Int) = send(OccupantsChanged(now, n))
        override fun fanSpeed(level: Int, max: Int) { car.userFan(level, max); drain() }
        override fun windowOpen(percent: Int) { car.userWindowPercent(percent); drain() }
        override fun lowBeam(on: Boolean) = send(LowBeamChanged(now, on))
        override fun addTripMinutes(m: Int) { tripMinutes += m; send(TripTimeUpdate(now, tripMinutes)) }
        override fun driverAttention(on: Boolean) = send(TelltaleChanged(now, Telltale.DRIVER_ATTENTION, on))
        override fun restWithin() = send(RestStopProximity(now, within = true))
        override fun answerYes() { autoPressed++; if (proposalOpen) send(UserAnswer(now, true)) }
        override fun frontPassenger(present: Boolean) = send(FrontPassengerChanged(now, present))
        override fun eta(minutes: Int?) = send(DestinationEta(now, minutes))
        override fun autoClimate(on: Boolean) = send(ai.kkaeugi.engine.AutoClimateChanged(now, on))
        override fun outdoorPm(pm25: Double?) { if (family) send(ai.kkaeugi.engine.OutdoorAir(now, pm25, "시연 시나리오")) }
        override fun localClock(minuteOfDay: Int?) { if (family) send(ai.kkaeugi.engine.LocalClock(now, minuteOfDay)) }
        override fun seats(values: List<Boolean?>?) = send(ai.kkaeugi.engine.SeatOccupancyChanged(now, values))
        override fun seatProfiles(profiles: List<ai.kkaeugi.engine.SeatProfile>) = send(ai.kkaeugi.engine.SeatProfilesChanged(now, profiles))

        /** 끝까지 돌린다. 반환: 시나리오가 끝난 시각까지 걸린 초 */
        fun run(maxSec: Int = 600): Double {
            val steps = if (family) DemoScenario.familySteps(config.ventilateSeconds) else DemoScenario.steps(config.ventilateSeconds, config.drowsySeconds, config.restMinutes)
            val runner = DemoScenario.Runner(steps, this)
            var step = 0L
            while (!runner.finished && elapsedSec() < maxSec) {
                now += STEP_MS; step += STEP_MS
                // TTS 가 끝났다
                val ends = speechEndsAt
                if (proposalOpen && ends != null && now >= ends) { speechEndsAt = null; countdownFrom = now; send(SpeechFinished(now)) }
                // 사람이 누른다
                val read = countdownFrom
                if (pressAfterReadMs != null && proposalOpen && read != null && now - read >= pressAfterReadMs) send(UserAnswer(now, true))
                if (step % 1_000L == 0L) { handle(engine.tick(now)); drain() }
                if (step % MockSignalSource.SCRIPT_TICK_MS == 0L) {
                    runner.tick(now, DemoScenario.View(proposalOpen, if (proposalOpen) countdownFrom else null, running && !resting, restRemainingSec))?.let { captions += it }
                }
            }
            assertTrue("시나리오가 ${maxSec}초 안에 끝나야 한다", runner.finished)
            return elapsedSec()
        }

        companion object { const val STEP_MS = 100L }
    }

    private fun assertFullSequence(s: Sim, delegated: Boolean = false) {
        // 제안 네 번: 혼자 "20초만", 4명 "바꿔 둘까요?", 졸음, 쉼 모드의 바깥 공기 질문.
        // 맡기기를 켜 둔 채면 첫 번째가 예고로 나와 제안은 세 번이다
        val proposals = if (delegated) listOf(s.announces.single()) + s.proposals else s.proposals
        assertEquals(proposals.joinToString("\n"), 4, proposals.size)
        val (first, second, third, fourth) = proposals
        if (delegated) assertEquals("20초만 바깥 공기로 바꿀게요. 싫으면 '아니'라고 하세요.", first)
        else {
            assertTrue(first, first.startsWith("차 안 공기가 탁해진 것 같아요(추정 2,0"))
            assertTrue(first, first.endsWith("20초만 바깥 공기로 바꿀까요?"))
        }
        assertTrue(second, second.startsWith("4명이 타서 공기가 빨리 탁해져요(추정 "))
        // 조수석에 사람이 탄 뒤 첫 제안에는 한마디가 붙고, 버튼이 조수석 쪽으로 간다
        assertTrue(second, second.endsWith("바깥 공기로 바꿔 둘까요? 옆자리에서 눌러 주셔도 돼요."))
        assertEquals("졸음 경고가 떴어요. 찬 바람 넣고, 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?", third)
        assertEquals("쉬는 동안 바깥 공기로 둘까요?", fourth)
        assertEquals(if (delegated) listOf(true, true, true) else listOf(false, true, true, true), s.passengerFlags)
        assertEquals("무응답으로 넘어간 제안이 없다", 0, s.expired)

        // 첫 제안은 내기순환을 켠 뒤 약 20초(혼자, 데모 60배속)
        if (!delegated) {
            val firstAfterRecirc = (s.proposalAt[0] - 100_000L) / 1000.0 - 2.5
            assertTrue("첫 제안까지 ${firstAfterRecirc}초", firstAfterRecirc in 19.0..23.0)
        }

        // 원복은 세 번(첫 환기, 졸음 개입, 쉼이 끝난 뒤의 바깥 공기). 4명 제안은 원복 없이 끝나고 그 실행의 스냅샷을 버린다.
        // 쉬는 동안 바깥 공기로 바꿔 둔 것은 스냅샷을 버리지 않는다(쉼이 끝나면 되돌려야 한다)
        assertEquals(3, s.reverts)
        assertEquals(1, s.kept.size)
        assertEquals(1, s.heldForRest)
        assertTrue(s.kept.single().single() is Action.Ventilate)
        assertTrue(s.said.toString(), s.said.contains("바깥 공기로 둘게요."))
        assertTrue("첫 환기는 유지 구간이 있다", s.runningLabels.contains("바깥 공기로 바꾸는 중"))
        assertTrue("졸음 개입은 창문 없이", s.runningLabels.contains("찬 바람 넣는 중"))

        // 모의 차량: 운전자가 다시 켠 내기순환으로 끝난다. 유지하기로 했던 풍량(3→4)은 남고,
        // 졸음 개입과 쉼 모드가 바꾼 것(외기, 풍량 +1, 온도)은 돌아왔다. 창문은 한 번도 건드리지 않았다
        assertTrue("쉼이 끝나면 내기순환으로 돌아온다", s.car.recirculating)
        assertEquals(4, s.car.fanLevel)
        assertEquals(24f, s.car.temperatureC)
        assertEquals(0, s.car.windowPercent)
        assertTrue("창문 제어 호출이 없다: ${s.controlCalls}", s.controlCalls.none { it.startsWith("setWindowPosition") })
        assertEquals(
            listOf("setHvacRecirculation(false)", "setHvacFanSpeed(4)", "setHvacRecirculation(true)", "setHvacFanSpeed(3)"),
            s.controlCalls.take(4),
        )
        // 쉬는 동안의 바깥 공기는 풍량을 올리지 않는다(fanDelta 0). 마지막 두 제어는 쉼 진입 때 외기로, 쉼이 끝난 뒤 내기순환으로
        assertEquals("마지막 제어는 쉼이 끝난 뒤의 원복", listOf("setHvacRecirculation(false)", "setHvacRecirculation(true)"), s.controlCalls.takeLast(2))

        // 쉼 모드와 도착 카드
        assertEquals("석적 졸음쉼터", s.restStartedName)
        assertNotNull(s.restEndedText)
        assertTrue("다 쉬고 나간다: ${s.restEndedText}", s.restEndedText!!.endsWith("푹 쉬셨어요. 조심히 가요"))
        // 쉼이 끝나고 목적지까지 4분: 환기는 묻지 않는다
        assertTrue("도착 임박 보류: ${s.deferred}", s.deferred.count { it == HomeHold.ARRIVING } <= 1)
        val card = s.arrival
        assertNotNull("도착 카드", card)
        // 쉬는 동안의 바깥 공기는 환기 횟수에 넣지 않는다
        assertEquals(3, card!!.ventilations)
        assertEquals(1, card.drowsySignals)
        assertEquals(1, card.restStopsAdded)
        assertEquals(1, card.restStopsVisited)
        assertEquals(0, card.declined)
        assertTrue("쉼 시간(데모 단위): ${card.restMinutes}", card.restMinutes in 15..24)
        // 쉬는 동안 숨 모델은 멈추고, 쉼이 끝나면 처음 값으로 돌아간다. 도착 카드의 최고치는 쉼 모드 전에 본 값이다
        assertEquals("쉬는 동안에는 추정 이벤트가 없다", 0, s.estimatesWhileResting)
        assertTrue("도착 카드 최고 ${card.peakPpm}, 쉼 전 최고 ${s.peakBeforeRest}", card.peakPpm >= 2000 && card.peakPpm <= s.peakBeforeRest + 400)
        // 데모 속도에서도 답을 기다리는 동안 추정치가 치솟지 않는다(엔진이 그동안은 실제 속도로 흘린다)
        assertTrue("도착 카드 최고 ${card.peakPpm}", card.peakPpm in 2000..2500)
        assertTrue("공기 줄은 엔진 줄에 없다", card.lines.none { it.contains("최고 추정") })
        assertEquals(DemoScenario.END_CAPTION, s.captions.last())
    }

    @Test fun `아무도 누르지 않으면 시나리오가 대신 누르고 끝까지 간다 - 길이는 라벨과 같다`() {
        val s = Sim(pressAfterReadMs = null)
        val sec = s.run()
        println("시연 시나리오 길이(아무도 안 누름): ${"%.1f".format(sec)}초, 제안 시각 ${s.proposalAt.map { "%.1f".format(s.elapsedSec(it)) }}, 도착 카드 최고 ${s.arrival?.peakPpm}ppm, 한도 위 ${s.arrival?.minutesOverLimit}")
        s.arrival?.let { println("  도착 카드: ${it.headline} / ${it.lines} / 맡기기 질문 ${it.trustOffer}") }
        println("  한마디: ${s.said}")
        s.proposals.forEach { println("  제안: $it") }
        println("  제어 호출: ${s.controlCalls}")
        assertFullSequence(s)
        assertEquals("네 번 다 시나리오가 눌렀다", 4, s.autoPressed)
        assertTrue("길이 ${sec}초", abs(sec - LABEL_SECONDS) <= 10.0)
        // 물어서 동의한 "20초만" 환기를 원복까지 끝냈다. 맡기기를 권할지 볼 때 쓰는 연속 동의가 1 이 된다
        assertEquals(TrustState(delegated = false, consecutiveYes = 1, cancelCount = 0), s.trust)
        assertNull("세 번이 되기 전에는 맡길지 묻지 않는다", s.arrival!!.trustOffer)
    }

    @Test fun `제안마다 바로 누르면 같은 순서로 더 빨리 끝난다`() {
        val s = Sim(pressAfterReadMs = 500)
        val sec = s.run()
        println("시연 시나리오 길이(바로 누름): ${"%.1f".format(sec)}초, 도착 카드 최고 ${s.arrival?.peakPpm}ppm, 한도 위 ${s.arrival?.minutesOverLimit}")
        assertFullSequence(s)
        assertEquals("시나리오가 대신 누를 일이 없다", 0, s.autoPressed)
        assertTrue("길이 ${sec}초", sec in 120.0..LABEL_SECONDS.toDouble())
    }

    @Test fun `맡기기를 켜 둔 채 돌리면 첫 제안이 예고로 나오고 같은 순서로 끝난다`() {
        val s = Sim(pressAfterReadMs = null, delegated = true)
        val sec = s.run()
        println("시연 시나리오 길이(맡기기 켬): ${"%.1f".format(sec)}초, 예고 ${s.announces}")
        assertFullSequence(s, delegated = true)
        assertTrue("길이 ${sec}초", abs(sec - LABEL_SECONDS) <= 12.0)
    }

    @Test fun `라벨의 길이 표기`() {
        assertEquals("약 ${LABEL_SECONDS / 60}분 ${LABEL_SECONDS % 60}초", DemoScenario.LENGTH_LABEL)
    }

    // ---------- 진행기 단위 동작 ----------

    private class CountingHands : DemoScenario.Hands {
        var yes = 0
        override fun gear(g: Gear) {}
        override fun recirculation(on: Boolean) {}
        override fun occupants(n: Int) {}
        override fun fanSpeed(level: Int, max: Int) {}
        override fun windowOpen(percent: Int) {}
        override fun lowBeam(on: Boolean) {}
        override fun addTripMinutes(m: Int) {}
        override fun driverAttention(on: Boolean) {}
        override fun restWithin() {}
        override fun answerYes() { yes++ }
        override fun frontPassenger(present: Boolean) {}
        override fun eta(minutes: Int?) {}
        var autoClimateCalls = mutableListOf<Boolean>()
        var etaCalls = 0
        override fun autoClimate(on: Boolean) { autoClimateCalls += on }
    }

    @Test fun `답 단계 - 다 읽고 6초가 지나야 대신 누른다, 한 번만`() {
        val hands = CountingHands()
        val r = DemoScenario.Runner(listOf(DemoScenario.Step("답", DemoScenario.Wait.Answer())), hands)
        assertEquals("답", r.tick(0, DemoScenario.View(proposalOpen = true)))
        assertNull("읽는 중", r.tick(5_000, DemoScenario.View(proposalOpen = true, countdownFrom = null)))
        assertNull(r.tick(9_000, DemoScenario.View(proposalOpen = true, countdownFrom = 4_000)))
        assertEquals(0, hands.yes)
        assertNull(r.tick(10_000, DemoScenario.View(proposalOpen = true, countdownFrom = 4_000)))
        assertEquals(1, hands.yes)
        assertNull(r.tick(10_200, DemoScenario.View(proposalOpen = true, countdownFrom = 4_000)))
        assertEquals("한 번만", 1, hands.yes)
        assertEquals("제안이 닫히면 다음으로", DemoScenario.END_CAPTION, r.tick(10_400, DemoScenario.View(proposalOpen = false)))
        assertTrue(r.finished)
    }

    @Test fun `답 단계 - 사람이 먼저 누르면 시나리오는 누르지 않는다`() {
        val hands = CountingHands()
        val r = DemoScenario.Runner(listOf(DemoScenario.Step("답", DemoScenario.Wait.Answer())), hands)
        r.tick(0, DemoScenario.View(proposalOpen = true))
        r.tick(3_000, DemoScenario.View(proposalOpen = true, countdownFrom = 2_500))
        assertEquals(DemoScenario.END_CAPTION, r.tick(3_200, DemoScenario.View(proposalOpen = false)))
        assertEquals(0, hands.yes)
    }

    @Test fun `대기 단계 - 제안이 뜨면 넘어가고, 실행 바는 떴다가 사라져야 넘어간다`() {
        val steps = listOf(
            DemoScenario.Step("제안 대기", DemoScenario.Wait.UntilProposal(45_000)),
            DemoScenario.Step("실행 대기", DemoScenario.Wait.UntilRunningDone(35_000)),
            DemoScenario.Step("끝", DemoScenario.Wait.Fixed(1_000)),
        )
        val r = DemoScenario.Runner(steps, CountingHands())
        r.tick(0, DemoScenario.View())
        assertNull(r.tick(20_000, DemoScenario.View()))
        assertEquals("실행 대기", r.tick(21_000, DemoScenario.View(proposalOpen = true)))
        assertNull(r.tick(22_000, DemoScenario.View(running = true)))
        assertNull(r.tick(40_000, DemoScenario.View(running = true)))
        assertEquals("끝", r.tick(42_000, DemoScenario.View(running = false)))
        assertNull(r.tick(42_500, DemoScenario.View()))
        assertEquals(DemoScenario.END_CAPTION, r.tick(43_000, DemoScenario.View()))
    }

    @Test fun `실행 바가 안 뜨면(거절) 3초 뒤 넘어간다`() {
        val r = DemoScenario.Runner(listOf(DemoScenario.Step("실행 대기", DemoScenario.Wait.UntilRunningDone(35_000))), CountingHands())
        r.tick(0, DemoScenario.View())
        assertNull(r.tick(2_900, DemoScenario.View()))
        assertEquals(DemoScenario.END_CAPTION, r.tick(3_000, DemoScenario.View()))
    }

    private companion object { const val LABEL_SECONDS = 165 }

    // ---------- v0.7.3 가족 시나리오 ----------

    private fun assertFamilySequence(s: Sim) {
        val p = s.proposals
        assertEquals(p.joinToString("\n"), 3, p.size)
        // 1) 혼자: 빈자리의 설정(임산부, 어린이)은 쓰지 않는다. 한도 2,000 에서 미세먼지가 나빠 짧게
        assertTrue(p[0], p[0].startsWith("차 안 공기가 탁해진 것 같아요(추정 2,0"))
        assertTrue(p[0], p[0].endsWith("밖 미세먼지가 나쁨이라 10초만 짧게 바꿀까요?"))
        // 2) 가족이 탄 뒤: 한도의 90%에서, 이유를 말하고, 버튼은 조수석 쪽
        // 데모 속도에서는 1초에 모델 1분이 흘러 1,800 을 조금 지난 값에서 묻는다(기본 한도 2,000 에는 닿기 전이다)
        assertTrue(p[1], Regex("어린이 자리가 있어 조금 일찍 물어요\\. 4명이 타서 공기가 빨리 탁해져요\\(추정 1,[89]00ppm\\)\\. 바깥 공기로 바꿔 둘까요\\? 옆자리에서 눌러 주셔도 돼요\\.").matches(p[1]))
        // 3) 걷기 쉼: 임산부 자리가 있어 90분에 묻는다
        assertEquals("한 시간 반째 앉아 있어요. 12km 앞 경부선 석적 졸음쉼터에서 내려서 5분 걸을까요?", p[2])
        assertEquals(listOf(ai.kkaeugi.engine.TriggerKind.STALE_AIR, ai.kkaeugi.engine.TriggerKind.STALE_AIR, ai.kkaeugi.engine.TriggerKind.WALK_BREAK), s.proposalKinds)
        assertEquals("무응답으로 넘어간 제안이 없다", 0, s.expired)
        // 원복은 첫 환기 한 번뿐. 걷기 쉼은 경유지만 넣고 공조를 건드리지 않는다
        assertEquals(1, s.reverts)
        assertEquals(listOf("setHvacRecirculation(false)", "setHvacFanSpeed(4)", "setHvacRecirculation(true)", "setHvacFanSpeed(3)", "setHvacRecirculation(false)", "setHvacFanSpeed(4)"), s.controlCalls.filter { it.startsWith("setHvac") })
        assertTrue(s.said.toString(), s.said.contains(WakeEngine.WALK_ADDED_TEXT))
        // 걷기 쉼 쉼터: 5초 타이머, 다 걷고 출발
        assertEquals("석적 졸음쉼터", s.restStartedName)
        assertTrue("걷기 쉼으로 넣은 쉼터다", s.restWalk)
        assertTrue(s.restEndedText, s.restEndedText!!.endsWith("푹 쉬셨어요. 조심히 가요"))
        // 도착 카드: 자리마다 한 줄
        val card = s.arrival!!
        assertTrue(card.seatHeadline, card.seatHeadline.endsWith("넷이 탔어요."))
        assertEquals(listOf(0, 1, 2, 4), card.seats.map { it.seat })
        assertEquals(listOf("기본", "임산부", "어린이", "기본"), card.seats.map { it.profile.label })
        assertEquals(listOf(false, true, true, true), card.seats.map { it.boardedMidTrip })
        assertEquals(1, card.walkBreaks)
        assertEquals(listOf(1, 1, 1, 1), card.seats.map { it.walkBreaks })
        assertTrue("운전석의 가장 긴 착석은 90 이상: ${card.seats[0].longestSitMinutes}", card.seats[0].longestSitMinutes >= DemoScenario.FAMILY_TRIP_JUMP_MIN)
        assertTrue("늦게 탄 자리는 더 짧다", card.seats[1].longestSitMinutes < card.seats[0].longestSitMinutes)
        assertEquals(2, card.ventilations)
        assertEquals(0, card.drowsySignals)
        assertEquals(1, card.restStopsVisited)
        assertNotNull(card.seats[0].pmAverage)
    }

    @Test
    fun `가족 시나리오를 아무도 누르지 않아도 끝까지 돈다`() {
        val s = Sim(pressAfterReadMs = null, family = true)
        val sec = s.run()
        assertFamilySequence(s)
        assertEquals("시나리오가 세 번 대신 눌렀다", 3, s.autoPressed)
        println("가족 시나리오(아무도 안 누름) ${sec}초")
        assertTrue("길이 ${sec}초", sec in 100.0..150.0)
    }

    @Test
    fun `가족 시나리오를 제안마다 바로 눌러도 같은 순서다`() {
        val s = Sim(pressAfterReadMs = 1_000, family = true)
        val sec = s.run()
        assertFamilySequence(s)
        assertEquals(0, s.autoPressed)
        println("가족 시나리오(바로 누름) ${sec}초")
        assertTrue("길이 ${sec}초", sec in 85.0..135.0)
    }
}

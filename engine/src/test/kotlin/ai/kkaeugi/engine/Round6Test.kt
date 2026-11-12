package ai.kkaeugi.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 6차(v0.6, 계약 v4) 동작을 본다: 맡기기, 조용 모드, 핸들·창문 기본 꺼짐, 도착 임박, 동승자, 차의 자동 내외기,
 * 하향등 디바운스, 쉼 모드 진입 질문.
 *
 * 상태기계만 보는 테스트는 데모 설정(분을 초로, CO2 생성 0 이라 환기 제안은 전부 시간 상한 변형)을 쓰고,
 * 숨 모델 경로(REVERT, KEEP 변형)는 실제 시간 기본 설정으로 차를 흉내 내는 Car 로 돌린다.
 */
class Round6Test {

    private val real = WakeConfig()
    private val demo = WakeConfig(
        staleAirMinutes = 40, staleAirMinutesNight = 20, ventilateSeconds = 20, drowsySeconds = 30,
        answerTimeoutSec = 8, declineCooldownMinutes = 20, longTripMinutes = 60, restMinutes = 15,
        demoFast = true, co2PerPersonLph = 0.0,
    )
    private val stop = RestStopInfo("망향", "경부선 서울방향", 12.3, 36.9, 127.1)

    internal data class Proposal(val atSec: Int, val variant: String, val text: String)

    /** 엔진과 차를 함께 흉내 낸다. Execute 를 받으면 차 상태를 바꾸고 차가 알려 주는 신호와 결과를 엔진에 돌려준다 */
    internal class Car(config: WakeConfig, trust: TrustState = TrustState()) {
        val engine = WakeEngine(config).also { it.setTrust(trust) }
        var now = 1_000_000L
        private var drivingSince = now
        /** AirEstimate, Running, RestTick 을 뺀 이벤트 */
        val events = mutableListOf<WakeEvent>()
        val proposals = mutableListOf<Proposal>()
        val announces = mutableListOf<Proposal>()

        var recirc = true
        var fan = 3
        var fanMax = 8
        private var snapshot: Pair<Boolean, Int>? = null
        /** false 면 Execute 에 결과를 돌려주지 않는다(테스트가 직접 넣는다) */
        var autoResult = true

        /** 제안에 자동으로 답한다(null 이면 답하지 않는다). 예고에는 답하지 않는다 */
        var autoAnswer: Boolean? = null
        var answerDelaySec = 5
        private var answerAt: Long? = null
        /** 제안이나 예고를 읽는 데 걸리는 시간(초). 지나면 SpeechFinished 를 보낸다. null 이면 보내지 않는다 */
        var speechSec: Int? = null
        private var speechAt: Long? = null
        var peakSeen = 0
        var airCount = 0

        private fun sec() = ((now - drivingSince) / 1000).toInt()

        private fun handle(all: List<WakeEvent>): List<WakeEvent> {
            val seen = mutableListOf<WakeEvent>()
            val pending = ArrayDeque(all)
            while (pending.isNotEmpty()) {
                val e = pending.removeFirst()
                when (e) {
                    is WakeEvent.AirEstimate -> { airCount++; continue }
                    is WakeEvent.Running, is WakeEvent.RestTick -> continue
                    is WakeEvent.Propose -> {
                        proposals += Proposal(sec(), engine.lastProposalVariant ?: "?", e.text)
                        if (autoAnswer != null) answerAt = now + answerDelaySec * 1000L
                        speechSec?.let { speechAt = now + it * 1000L }
                    }
                    is WakeEvent.Announce -> {
                        announces += Proposal(sec(), engine.lastProposalVariant ?: "?", e.text)
                        speechSec?.let { speechAt = now + it * 1000L }
                    }
                    is WakeEvent.Execute -> if (autoResult) {
                        if (e.actions == listOf<Action>(Action.Revert)) {
                            pending.addAll(restoreCar())
                            pending.addAll(engine.accept(ActionResult(now, Action.Revert, ok = true)))
                        } else {
                            for (a in e.actions) {
                                if (a is Action.Ventilate) {
                                    if (snapshot == null) snapshot = recirc to fan
                                    if (recirc) { recirc = false; pending.addAll(engine.accept(RecirculationChanged(now, false))) }
                                    val raised = minOf(fan + a.fanDelta, fanMax)
                                    if (raised != fan) { fan = raised; pending.addAll(engine.accept(FanSpeedChanged(now, fan, fanMax))) }
                                }
                                pending.addAll(engine.accept(ActionResult(now, a, ok = true)))
                            }
                        }
                    }
                    is WakeEvent.Done -> if (engine.phaseName == "IDLE" && !engine.isResting) snapshot = null
                    else -> {}
                }
                seen += e
            }
            events += seen
            return seen
        }

        private fun restoreCar(): List<WakeEvent> {
            val out = mutableListOf<WakeEvent>()
            snapshot?.let { (r, f) ->
                // 운전자가 그 사이 직접 바꾼 항목은 건드리지 않는다
                if (!recirc && r) { recirc = true; out += engine.accept(RecirculationChanged(now, true)) }
                if (fan != f) { fan = f; out += engine.accept(FanSpeedChanged(now, f, fanMax)) }
            }
            snapshot = null
            return out
        }

        fun send(make: (Long) -> Signal): List<WakeEvent> = handle(engine.accept(make(now)))

        /** 엔진의 공개 함수(setTrust, requestVentilation)가 돌려준 이벤트를 차에 흘린다 */
        fun feed(events: List<WakeEvent>): List<WakeEvent> = handle(events)

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
                speechAt?.let { at ->
                    if (now >= at) { speechAt = null; got += handle(engine.accept(SpeechFinished(now))) }
                }
                answerAt?.let { at ->
                    if (now >= at) { answerAt = null; got += handle(engine.accept(UserAnswer(now, autoAnswer!!))) }
                }
            }
            return got
        }

        inline fun <reified T : WakeEvent> runUntil(max: Int): Int? {
            for (s in 1..max) if (run(1).any { it is T }) return s
            return null
        }

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
        fun speechDone(): List<WakeEvent> = send { SpeechFinished(it) }

        fun arrive(): ArrivalSummary =
            send { GearChanged(it, Gear.PARK) }.filterIsInstance<WakeEvent.ArrivalCard>().single().card

        inline fun <reified T : WakeEvent> last(): T? = events.filterIsInstance<T>().lastOrNull()
        inline fun <reified T : WakeEvent> count(): Int = events.count { it is T }
        fun deferred(reason: String): Int = events.count { it is WakeEvent.Deferred && it.reason == reason }
    }

    /** 졸음 경고등을 켜서 졸음 제안을 받는다 */
    private fun Car.drowsyProposal(): WakeEvent.Propose {
        send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        val p = send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.filterIsInstance<WakeEvent.Propose>().single()
        assertEquals(TriggerKind.DROWSY, p.kind)
        return p
    }

    // ---------- 3. 핸들 신호는 기본 꺼짐 ----------

    /** 무조작 7초 뒤 급보정을 n 번 넣는다 */
    private fun Car.jerks(n: Int) {
        repeat(n) {
            repeat(7) { send { SteeringAngle(it, 0f) }; run(1) }
            send { SteeringAngle(it, 10f) }
            run(1)
        }
    }

    @Test
    fun `기본 설정에서는 핸들 각도를 무시한다`() {
        assertFalse(WakeConfig().steeringAssist)
        val car = Car(demo)
        car.drive(recirc = false)
        car.run(30)
        car.jerks(3)
        car.run(25)
        assertEquals(0, car.engine.steerEvents)
        assertEquals(0, car.count<WakeEvent.Propose>())
    }

    @Test
    fun `steeringAssist 를 켜면 같은 입력에 쉬어 가기를 제안한다`() {
        val car = Car(demo.copy(steeringAssist = true))
        car.drive(recirc = false)
        car.run(30)
        car.jerks(3)
        car.run(25)
        val p = car.last<WakeEvent.Propose>()
        assertNotNull(p)
        assertEquals("핸들 보정이 잦아졌어요. 찬 바람을 넣을까요?", p.text)
    }

    // ---------- 4. 창문은 기본 제외 ----------

    @Test
    fun `기본 설정의 졸음 개입에는 창문이 없고 쉼터가 있으면 경유까지 묻는다`() {
        assertFalse(WakeConfig().allowWindow)
        val car = Car(demo)
        car.drive()
        car.send { RestStopCandidate(it, stop) }
        val p = car.drowsyProposal()
        assertEquals("졸음 경고가 떴어요. 찬 바람 넣고, 12km 앞 경부선 망향 졸음쉼터 경유까지 할까요?", p.text)
        assertEquals(listOf(Action.Ventilate(), Action.AddRestStop(stop), Action.CoolDown()), p.actions)
        car.autoResult = false
        car.yes()
        car.send { ActionResult(it, Action.Ventilate(), true) }
        car.send { ActionResult(it, Action.AddRestStop(stop), true) }
        val running = car.engine.accept(ActionResult(car.now, Action.CoolDown(), true)).filterIsInstance<WakeEvent.Running>().single()
        assertEquals("찬 바람 넣는 중", running.label)
    }

    @Test
    fun `기본 설정에서 쉼터가 없거나 이미 경유지면 찬 바람만 묻는다`() {
        val car = Car(demo)
        car.drive()
        val none = car.drowsyProposal()
        assertEquals("졸음 경고가 떴어요. 찬 바람을 넣을까요?", none.text)
        assertEquals(listOf(Action.Ventilate(), Action.CoolDown()), none.actions)
        car.no()
        car.run(21)
        car.send { RestStopCandidate(it, stop) }
        car.drowsyProposal()
        car.yes()
        assertNotNull(car.runUntil<WakeEvent.Done>(60))
        assertEquals(stop, car.engine.currentWaypoint)
        car.run(25)
        val again = car.drowsyProposal()
        assertEquals("졸음 경고가 떴어요. 망향 졸음쉼터는 경유지에 있어요. 찬 바람을 넣을까요?", again.text)
        assertTrue(again.actions.none { it is Action.OpenWindow || it is Action.AddRestStop })
    }

    @Test
    fun `allowWindow 를 켜면 창문 문장과 OpenWindow 가 돌아오고 비가 오면 뺀다`() {
        val car = Car(demo.copy(allowWindow = true))
        car.drive()
        val p = car.drowsyProposal()
        assertEquals("졸음 경고가 떴어요. 찬 바람 넣고 창문 조금 열까요?", p.text)
        assertEquals(listOf(Action.Ventilate(), Action.CoolDown(), Action.OpenWindow()), p.actions)
        car.no()
        car.run(21)
        car.send { WiperChanged(it, true) }
        val rain = car.drowsyProposal()
        assertEquals("졸음 경고가 떴어요. 비가 와서 창문은 두고 찬 바람만 넣을까요?", rain.text)
        assertTrue(rain.actions.none { it is Action.OpenWindow })
    }

    // ---------- 5. 도착 임박 ----------

    @Test
    fun `도착 5분 전이면 환기 제안을 내지 않고 사유를 한 번만 알린다`() {
        val car = Car(demo)
        car.drive()
        car.send { DestinationEta(it, 5) }
        assertTrue(car.engine.arrivingSoon)
        // 데모 설정에서는 도착 예정이 10초면 낡는다. 내비가 살아 있는 것처럼 5초마다 다시 보낸다
        repeat(12) { car.run(5); car.send { DestinationEta(it, 5) } }
        assertEquals(0, car.count<WakeEvent.Propose>())
        assertEquals(1, car.deferred("arriving"))
        assertEquals(WakeEvent.Deferred(TriggerKind.STALE_AIR, "arriving"), car.last<WakeEvent.Deferred>())
    }

    @Test
    fun `도착까지 6분이면 묻고 목적지가 없어지면 미뤘던 제안이 나간다`() {
        val six = Car(demo)
        six.drive()
        six.send { DestinationEta(it, 6) }
        assertFalse(six.engine.arrivingSoon)
        assertEquals(40, six.runUntil<WakeEvent.Propose>(60))

        val car = Car(demo)
        car.drive()
        car.send { DestinationEta(it, 3) }
        repeat(10) { car.run(5); car.send { DestinationEta(it, 3) } }
        assertEquals(0, car.count<WakeEvent.Propose>())
        // 목적지가 없어지면 그 신호에서 바로 묻는다
        assertEquals(1, car.send { DestinationEta(it, null) }.count { it is WakeEvent.Propose })
        assertFalse(car.engine.arrivingSoon)
    }

    @Test
    fun `도착이 쉼터보다 가까우면 졸음 제안에서 쉼터를 빼고 남은 분을 말한다`() {
        val car = Car(demo)
        car.drive(recirc = false)
        car.send { RestStopCandidate(it, stop) } // 12.3km → 100km/h 로 약 7.4분
        car.send { DestinationEta(it, 4) }
        val p = car.drowsyProposal()
        assertEquals("졸음 경고가 떴어요. 4분 뒤 도착이에요. 찬 바람을 넣을까요?", p.text)
        assertEquals(listOf(Action.Ventilate(), Action.CoolDown()), p.actions)
        car.no()
        car.run(21)
        // 7분 남았으면 쉼터가 아직 더 멀다(7 < 7.38). 8분이면 쉼터를 넣는다
        car.send { DestinationEta(it, 7) }
        assertTrue(car.drowsyProposal().actions.none { it is Action.AddRestStop })
        car.no()
        car.run(21)
        car.send { DestinationEta(it, 8) }
        val withStop = car.drowsyProposal()
        assertTrue(withStop.actions.any { it is Action.AddRestStop })
        assertEquals("졸음 경고가 떴어요. 찬 바람 넣고, 12km 앞 경부선 망향 졸음쉼터 경유까지 할까요?", withStop.text)
    }

    @Test
    fun `allowWindow 면 도착 임박 졸음 문장에 창문이 들어간다`() {
        val car = Car(demo.copy(allowWindow = true))
        car.drive()
        car.send { RestStopCandidate(it, stop) }
        car.send { DestinationEta(it, 2) }
        assertEquals("졸음 경고가 떴어요. 2분 뒤 도착이에요. 찬 바람 넣고 창문 조금 열까요?", car.drowsyProposal().text)
    }

    // ---------- 6. 동승자 ----------

    @Test
    fun `조수석에 사람이 있으면 첫 제안에만 한마디를 붙이고 passengerMayAnswer 를 켠다`() {
        val car = Car(demo)
        car.drive()
        car.send { FrontPassengerChanged(it, true) }
        assertEquals(40, car.runUntil<WakeEvent.Propose>(60))
        val first = car.last<WakeEvent.Propose>()!!
        assertEquals("내기순환으로 40분째예요. 20초만 바깥 공기로 바꿀까요? 옆자리에서 눌러 주셔도 돼요.", first.text)
        assertTrue(first.passengerMayAnswer)
        car.no()
        // 거절 쿨다운 20초 뒤 두 번째 제안에는 붙이지 않는다. 버튼은 계속 옆자리에서도 누를 수 있다
        assertNotNull(car.runUntil<WakeEvent.Propose>(25))
        val second = car.last<WakeEvent.Propose>()!!
        assertEquals("내기순환으로 40분째예요. 20초만 바깥 공기로 바꿀까요?", second.text)
        assertTrue(second.passengerMayAnswer)
    }

    @Test
    fun `조수석이 비어 있으면 붙이지 않고 다음 주행에서는 다시 한 번 붙인다`() {
        val car = Car(demo)
        car.drive(recirc = false)
        val alone = car.drowsyProposal()
        assertFalse(alone.passengerMayAnswer)
        assertFalse(alone.text.contains("옆자리"))
        car.no()
        // 도중에 탔으면 그 뒤 첫 제안에 붙는다
        car.send { FrontPassengerChanged(it, true) }
        car.run(21)
        assertTrue(car.drowsyProposal().text.endsWith(" 옆자리에서 눌러 주셔도 돼요."))
        car.no()
        car.arrive()
        car.run(21)
        car.send { GearChanged(it, Gear.DRIVE) }
        assertTrue(car.drowsyProposal().text.endsWith(" 옆자리에서 눌러 주셔도 돼요."))
    }

    // ---------- 7. 차의 자동 내외기 ----------

    @Test
    fun `차가 내외기를 제어하는 동안 환기 제안을 미루고 추정치는 계속 낸다`() {
        val car = Car(demo)
        car.drive()
        car.send { AutoClimateChanged(it, true) }
        assertTrue(car.engine.carAutoActive)
        car.run(60)
        assertEquals(0, car.count<WakeEvent.Propose>())
        assertEquals(1, car.deferred("car-auto"))
        // 차가 제어를 놓으면 그 신호에서 바로 묻는다
        assertEquals(1, car.send { AutoClimateChanged(it, false) }.count { it is WakeEvent.Propose })
        assertFalse(car.engine.carAutoActive)
    }

    @Test
    fun `차 자동 제어 중에도 숨 모델 추정치는 오르고 AirEstimate 가 나온다`() {
        val engine = WakeEngine(real)
        var now = 1_000L
        engine.accept(OccupantsChanged(now, 4))
        engine.accept(RecirculationChanged(now, true))
        engine.accept(AutoClimateChanged(now, true))
        engine.accept(GearChanged(now, Gear.DRIVE))
        val all = mutableListOf<WakeEvent>()
        repeat(600) { now += 1000; all += engine.tick(now) }
        assertTrue(all.none { it is WakeEvent.Propose || it is WakeEvent.Announce })
        assertTrue(all.count { it is WakeEvent.AirEstimate } >= 100)
        assertTrue(engine.estimatedPpm > real.co2LimitPpm, "추정 ${engine.estimatedPpm}")
    }

    @Test
    fun `차 자동 제어 중 졸음 개입은 내외기를 건드리지 않는다`() {
        val car = Car(demo)
        car.drive()
        car.send { AutoClimateChanged(it, true) }
        car.send { RestStopCandidate(it, stop) }
        val p = car.drowsyProposal()
        assertEquals(listOf(Action.AddRestStop(stop), Action.CoolDown()), p.actions)
        car.yes()
        assertNotNull(car.runUntil<WakeEvent.Done>(60))
        assertTrue(car.recirc, "내기순환은 그대로여야 한다")
    }

    // ---------- 8. 하향등 디바운스 ----------

    @Test
    fun `주행 중 하향등이 켜지면 3분 동안 환기 제안을 미루고 야간으로도 보지 않는다`() {
        val car = Car(real)
        car.drive(occupants = 4)
        car.send { TripTimeUpdate(it, 120) }
        car.run(120)
        car.send { LowBeamChanged(it, true) }
        assertEquals(2000, car.engine.effectiveLimitPpm)
        car.run(179)
        assertEquals(0, car.count<WakeEvent.Propose>()) // 4명은 약 4분에 한도에 닿지만 미룬다
        assertEquals(1, car.deferred("lights-just-on"))
        assertEquals(2000, car.engine.effectiveLimitPpm)
        car.run(1)
        assertEquals(1600, car.engine.effectiveLimitPpm)
        assertEquals(1, car.count<WakeEvent.Propose>())
    }

    @Test
    fun `하향등을 3분 안에 끄면 미뤘던 제안이 바로 나가고 야간 한도는 쓰지 않는다`() {
        val car = Car(real)
        car.drive(occupants = 4)
        car.send { TripTimeUpdate(it, 120) }
        car.run(200)
        car.send { LowBeamChanged(it, true) }
        car.run(120)
        assertEquals(0, car.count<WakeEvent.Propose>())
        assertEquals(1, car.send { LowBeamChanged(it, false) }.count { it is WakeEvent.Propose })
        assertEquals(2000, car.engine.effectiveLimitPpm)
        assertFalse(car.last<WakeEvent.Propose>()!!.text.startsWith("오래 달려서"))
    }

    @Test
    fun `출발할 때 이미 켜져 있던 하향등은 미루지 않고 바로 야간으로 본다`() {
        val car = Car(real)
        car.send { LowBeamChanged(it, true) }
        car.drive(occupants = 4)
        car.send { TripTimeUpdate(it, 120) }
        assertEquals(1600, car.engine.effectiveLimitPpm)
        assertNotNull(car.runUntil<WakeEvent.Propose>(200))
        assertEquals(0, car.deferred("lights-just-on"))
        assertTrue(car.last<WakeEvent.Propose>()!!.text.startsWith("오래 달려서 조금 일찍 물어요."))
    }

    @Test
    fun `데모에서는 하향등 3초를 기다린다`() {
        val car = Car(demo)
        car.drive()
        car.run(40) // 주간 상한 40초에 한 번 묻는다
        assertEquals(1, car.count<WakeEvent.Propose>())
        car.no()
        car.run(18)
        car.send { LowBeamChanged(it, true) }
        car.run(2) // 60초: 거절 쿨다운은 끝났지만 하향등이 켜진 지 2초라 미룬다
        assertEquals(1, car.count<WakeEvent.Propose>())
        assertEquals(1, car.deferred("lights-just-on"))
        car.run(1)
        assertEquals(2, car.count<WakeEvent.Propose>())
    }

    // ---------- 1. 맡기기 ----------

    private val announceText = "2분만 바깥 공기로 바꿀게요. 싫으면 '아니'라고 하세요."
    private val offerText = "오늘까지 환기 제안 3번에 모두 '응' 하셨어요. 다음부터 2분 환기는 말만 하고 바꿀까요?"

    /** 1명이 내기순환으로 달려 "2분만" 제안(약 20분)을 받는다 */
    private fun Car.reachRevertProposal(): WakeEvent.Propose {
        assertNotNull(runUntil<WakeEvent.Propose>(1500), "약 20분에 환기 제안이 나와야 한다")
        assertEquals("REVERT", engine.lastProposalVariant)
        return last<WakeEvent.Propose>()!!
    }

    /** 맡긴 1명이 내기순환으로 달려 예고(약 20분)를 받는다 */
    private fun Car.reachAnnounce(): WakeEvent.Announce {
        assertNotNull(runUntil<WakeEvent.Announce>(1500), "약 20분에 예고가 나와야 한다")
        return last<WakeEvent.Announce>()!!
    }

    @Test
    fun `setTrust 는 이벤트 없이 상태만 넣고 음수는 0 으로 본다`() {
        val engine = WakeEngine(real)
        assertEquals(TrustState(), engine.trust)
        engine.setTrust(TrustState(delegated = true, consecutiveYes = -2, cancelCount = -1))
        assertEquals(TrustState(delegated = true), engine.trust)
    }

    @Test
    fun `2분만 제안에 세 번 동의해 원복까지 끝내면 도착 카드에서 맡길지 묻는다`() {
        val car = Car(real)
        car.autoAnswer = true
        car.drive(occupants = 1)
        car.run(3700) // 세 번째 환기의 원복은 60분 3초쯤 끝난다
        assertEquals(listOf("REVERT", "REVERT", "REVERT"), car.proposals.map { it.variant })
        // 원복이 끝날 때마다 한 번씩 알린다. 앱은 그대로 저장한다
        assertEquals(
            listOf(TrustState(consecutiveYes = 1), TrustState(consecutiveYes = 2), TrustState(consecutiveYes = 3)),
            car.events.filterIsInstance<WakeEvent.TrustChanged>().map { it.state },
        )
        val card = car.arrive()
        assertEquals(offerText, card.trustOffer)
        assertTrue(car.announces.isEmpty())
        // [맡길게요]
        assertEquals(
            listOf<WakeEvent>(WakeEvent.TrustChanged(TrustState(delegated = true, consecutiveYes = 3))),
            car.send { TrustAnswer(it, true) },
        )
        assertTrue(car.engine.trust.delegated)
    }

    @Test
    fun `연속 동의는 주행을 넘어 쌓이고 두 번까지는 권하지 않는다`() {
        val one = Car(real, TrustState(consecutiveYes = 1))
        one.drive(occupants = 1)
        one.reachRevertProposal()
        one.yes()
        assertNotNull(one.runUntil<WakeEvent.Done>(200))
        assertEquals(2, one.engine.trust.consecutiveYes)
        assertNull(one.arrive().trustOffer)

        val two = Car(real, TrustState(consecutiveYes = 2))
        two.drive(occupants = 1)
        two.reachRevertProposal()
        two.yes()
        // 동의만으로는 세지 않는다. 원복까지 끝나야 한다
        two.run(60)
        assertEquals(2, two.engine.trust.consecutiveYes)
        assertNotNull(two.runUntil<WakeEvent.Done>(200))
        assertEquals(3, two.engine.trust.consecutiveYes)
        assertEquals(offerText, two.arrive().trustOffer)
    }

    @Test
    fun `거절이나 무응답이면 연속 동의는 0 으로 돌아가고 권하지 않는다`() {
        val declined = Car(real, TrustState(consecutiveYes = 2))
        declined.drive(occupants = 1)
        declined.reachRevertProposal()
        val got = declined.no()
        assertEquals(WakeEvent.TrustChanged(TrustState()), got.last())
        assertNull(declined.arrive().trustOffer)

        val silent = Car(real, TrustState(consecutiveYes = 5))
        silent.drive(occupants = 1)
        silent.reachRevertProposal()
        assertNotNull(silent.runUntil<WakeEvent.ProposalExpired>(30))
        assertEquals(0, silent.engine.trust.consecutiveYes)
        assertNull(silent.arrive().trustOffer)
    }

    @Test
    fun `계속 물어봐 달라고 하면 세 번을 처음부터 다시 센다`() {
        val engine = WakeEngine(real)
        engine.setTrust(TrustState(consecutiveYes = 3))
        assertEquals(listOf<WakeEvent>(WakeEvent.TrustChanged(TrustState())), engine.accept(TrustAnswer(1_000L, false)))
        // 이미 0 이면 알릴 것이 없다
        assertTrue(engine.accept(TrustAnswer(2_000L, false)).isEmpty())
        // 맡기면 취소 횟수도 0 부터
        engine.setTrust(TrustState(consecutiveYes = 3, cancelCount = 2))
        assertEquals(
            listOf<WakeEvent>(WakeEvent.TrustChanged(TrustState(delegated = true, consecutiveYes = 3))),
            engine.accept(TrustAnswer(3_000L, true)),
        )
    }

    @Test
    fun `맡기면 묻지 않고 예고한 뒤 SpeechFinished 부터 5초 동안 취소가 없으면 실행하고 2분 뒤 원복한다`() {
        val car = Car(real, TrustState(delegated = true))
        car.drive(occupants = 1)
        val a = car.reachAnnounce()
        assertEquals(WakeEvent.Announce(TriggerKind.STALE_AIR, announceText, listOf(Action.Ventilate()), 5), a)
        assertEquals("ANNOUNCE", car.engine.lastProposalVariant)
        assertEquals("ANNOUNCING", car.engine.phaseName)
        assertEquals(announceText, car.engine.lastProposalText)
        assertEquals(0, car.count<WakeEvent.Propose>())
        car.run(4)
        car.speechDone()
        car.run(4)
        assertEquals(0, car.count<WakeEvent.Execute>())
        car.run(1)
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Ventilate())), car.last<WakeEvent.Execute>())
        assertFalse(car.recirc)
        assertEquals(120, car.runUntil<WakeEvent.Done>(200))
        assertTrue(car.last<WakeEvent.Done>()!!.text!!.endsWith("원래대로 돌렸어요."))
        assertTrue(car.recirc)
        // 맡긴 환기는 연속 동의로 세지 않고 상태도 그대로다
        assertEquals(0, car.count<WakeEvent.TrustChanged>())
        val card = car.arrive()
        assertEquals(1, card.ventilations)
        assertEquals(0, card.declined)
        assertNull(card.trustOffer)
        assertTrue(card.lines.any { it.contains("맡겨 둔 환기 실행") }, card.lines.toString())
    }

    @Test
    fun `SpeechFinished 가 안 오면 예고 뒤 5초에 문장 길이 여유를 더해 실행한다`() {
        val car = Car(real, TrustState(delegated = true))
        car.drive(occupants = 1)
        car.reachAnnounce()
        val wait = 5 + WakeEngine.speechGraceSec(announceText)
        car.run(wait - 1)
        assertEquals(0, car.count<WakeEvent.Execute>())
        car.run(1)
        assertEquals(1, car.count<WakeEvent.Execute>())
    }

    @Test
    fun `예고 창 안에 응 하면 기다리지 않고 바로 실행한다`() {
        val car = Car(real, TrustState(delegated = true))
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.run(1)
        assertTrue(car.yes().any { it == WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Ventilate())) })
        assertEquals(0, car.count<WakeEvent.TrustChanged>())
    }

    @Test
    fun `예고를 취소하면 그대로 두고 그 주행은 다시 묻는 방식으로 돌아간다`() {
        val car = Car(real, TrustState(delegated = true))
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.run(2)
        assertEquals(
            listOf(
                WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 그대로 둘게요."),
                WakeEvent.TrustChanged(TrustState(delegated = true, cancelCount = 1)),
            ),
            car.no(),
        )
        assertTrue(car.recirc)
        assertEquals(0, car.count<WakeEvent.Execute>())
        // 거절과 같은 쿨다운(20분) 뒤에는 예고가 아니라 질문으로 나온다
        assertEquals(20 * 60, car.runUntil<WakeEvent.Propose>(1300))
        assertEquals("REVERT", car.engine.lastProposalVariant)
        assertEquals(1, car.announces.size)
        val card = car.arrive()
        assertEquals(1, card.declined)
        assertFalse(card.lines.contains("맡기기를 껐어요. 다시 물어볼게요."))
        assertTrue(car.engine.trust.delegated)
    }

    @Test
    fun `취소가 두 번 쌓이면 맡기기가 꺼지고 도착 카드 맨 앞 줄에 알린다`() {
        val car = Car(real, TrustState(delegated = true, consecutiveYes = 4))
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.no()
        assertNull(car.arrive().trustOffer)
        // 다음 주행에서는 다시 예고한다(취소 한 번은 그 주행만 묻는 방식으로 바꾼다)
        car.run(600)
        car.send { GearChanged(it, Gear.DRIVE) }
        car.reachAnnounce()
        assertEquals(2, car.announces.size)
        assertEquals(WakeEvent.TrustChanged(TrustState(delegated = false, consecutiveYes = 0, cancelCount = 2)), car.no().last())
        assertFalse(car.engine.trust.delegated)
        val card = car.arrive()
        assertEquals("맡기기를 껐어요. 다시 물어볼게요.", card.lines.first())
        assertNull(card.trustOffer)
        // 그다음 주행부터는 묻는다
        car.run(600)
        car.send { GearChanged(it, Gear.DRIVE) }
        car.reachRevertProposal()
        assertEquals(2, car.announces.size)
        assertFalse(car.arrive().lines.contains("맡기기를 껐어요. 다시 물어볼게요."))
    }

    @Test
    fun `맡긴 환기 중에 운전자가 직접 내기순환을 켜면 취소로 세고 남은 주행은 묻는다`() {
        val car = Car(real, TrustState(delegated = true))
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.yes()
        car.run(10)
        assertEquals(
            listOf<WakeEvent>(WakeEvent.TrustChanged(TrustState(delegated = true, cancelCount = 1))),
            car.driverRecirc(true),
        )
        // 유지 시간은 그대로 가고 원복으로 끝난다. 운전자가 켠 내기순환은 건드리지 않는다
        assertNotNull(car.runUntil<WakeEvent.Done>(200))
        assertTrue(car.last<WakeEvent.Done>()!!.text!!.endsWith("원래대로 돌렸어요."))
        assertTrue(car.recirc)
        assertNotNull(car.runUntil<WakeEvent.Propose>(1500))
        assertEquals(1, car.announces.size)
    }

    @Test
    fun `물어서 동의한 환기 중에 직접 내기순환을 켜면 연속 동의가 0 으로 돌아간다`() {
        val car = Car(real, TrustState(consecutiveYes = 2))
        car.drive(occupants = 1)
        car.reachRevertProposal()
        car.yes()
        car.run(10)
        assertEquals(listOf<WakeEvent>(WakeEvent.TrustChanged(TrustState())), car.driverRecirc(true))
        assertNotNull(car.runUntil<WakeEvent.Done>(200))
        assertEquals(0, car.engine.trust.consecutiveYes)
        assertNull(car.arrive().trustOffer)
    }

    @Test
    fun `맡겨도 바깥 공기로 두기와 시간 상한과 졸음 개입은 항상 묻는다`() {
        val four = Car(real, TrustState(delegated = true))
        four.drive(occupants = 4)
        assertNotNull(four.runUntil<WakeEvent.Propose>(400))
        assertEquals("KEEP", four.engine.lastProposalVariant)
        assertTrue(four.announces.isEmpty())

        val capped = Car(demo, TrustState(delegated = true))
        capped.drive()
        assertEquals(40, capped.runUntil<WakeEvent.Propose>(60))
        assertEquals("TIME_CAP", capped.engine.lastProposalVariant)
        capped.no()
        capped.run(21)
        // 거절 쿨다운 뒤 나온 두 번째 환기 질문에 답하지 않고, 졸음 경고는 그 뒤 질문으로 나온다
        capped.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        capped.run(40)
        assertTrue(capped.proposals.any { it.variant == "DROWSY" })
        assertTrue(capped.announces.isEmpty())
    }

    @Test
    fun `예고 중에 운전자가 직접 바깥 공기로 바꾸면 조용히 닫고 취소로 세지 않는다`() {
        val car = Car(real, TrustState(delegated = true))
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.run(1)
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, null)), car.driverRecirc(false))
        car.run(30)
        assertEquals(0, car.count<WakeEvent.Execute>())
        assertEquals(TrustState(delegated = true), car.engine.trust)
    }

    @Test
    fun `예고도 발화 게이트를 따른다`() {
        val car = Car(real, TrustState(delegated = true))
        car.drive(occupants = 1)
        car.run(600)
        car.send { TurnSignalChanged(it, TurnSignalState.LEFT) }
        car.run(900)
        assertTrue(car.announces.isEmpty())
        assertEquals(WakeEvent.Deferred(TriggerKind.STALE_AIR, "turn_signal"), car.last<WakeEvent.Deferred>())
        car.send { TurnSignalChanged(it, TurnSignalState.OFF) }
        car.run(1)
        assertEquals(1, car.announces.size)
    }

    @Test
    fun `도착이 가깝거나 차가 내외기를 제어하면 예고도 내지 않는다`() {
        val car = Car(real, TrustState(delegated = true))
        car.drive(occupants = 1)
        car.send { DestinationEta(it, 4) }
        // 도착 예정은 10분이 지나면 낡은 값으로 본다. 내비가 살아 있는 것처럼 5분마다 다시 보낸다
        repeat(5) { car.run(300); car.send { DestinationEta(it, 4) } }
        assertTrue(car.announces.isEmpty())
        assertEquals(1, car.deferred("arriving"))
        car.send { AutoClimateChanged(it, true) }
        car.send { DestinationEta(it, 30) }
        car.run(60)
        assertTrue(car.announces.isEmpty())
        assertEquals(1, car.deferred("car-auto"))
        assertEquals(1, car.send { AutoClimateChanged(it, false) }.count { it is WakeEvent.Announce })
    }

    // ---------- 2. 거절이 쌓이면 조용히 ----------

    private val quietText = "이번 주행에서는 더 묻지 않을게요. 화면에만 표시할게요."

    @Test
    fun `환기 제안을 두 번 연속 거절하면 한 번 알리고 그 주행에서는 더 묻지 않는다`() {
        val car = Car(demo)
        car.drive()
        assertEquals(40, car.runUntil<WakeEvent.Propose>(60))
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 20초 뒤에 다시 볼게요.")), car.no())
        assertFalse(car.engine.quietMode)
        assertEquals(20, car.runUntil<WakeEvent.Propose>(30))
        assertEquals(
            listOf(WakeEvent.Done(TriggerKind.STALE_AIR, null), WakeEvent.Quiet(quietText)),
            car.no(),
        )
        assertTrue(car.engine.quietMode)
        val airsBefore = car.airCount
        car.run(300)
        assertEquals(2, car.count<WakeEvent.Propose>())
        assertEquals(1, car.count<WakeEvent.Quiet>())
        // 추정치는 계속 알리고, 졸음 경고에는 여전히 묻는다
        assertTrue(car.airCount - airsBefore >= 50, "AirEstimate ${car.airCount - airsBefore}")
        assertEquals(TriggerKind.DROWSY, car.drowsyProposal().kind)
        car.no()
        val card = car.arrive()
        assertEquals(3, card.declined)
        assertTrue(card.lines.any { it.contains("더 묻지 않음") }, card.lines.toString())
        // 다음 주행에서는 다시 묻는다
        car.run(30)
        car.send { GearChanged(it, Gear.DRIVE) }
        assertFalse(car.engine.quietMode)
        assertEquals(40, car.runUntil<WakeEvent.Propose>(60))
    }

    @Test
    fun `거절 사이에 동의가 끼면 연속이 아니다`() {
        val car = Car(demo)
        car.drive()
        assertNotNull(car.runUntil<WakeEvent.Propose>(60))
        car.no()
        assertNotNull(car.runUntil<WakeEvent.Propose>(30))
        car.yes()
        assertNotNull(car.runUntil<WakeEvent.Done>(40))
        assertNotNull(car.runUntil<WakeEvent.Propose>(60))
        assertEquals("알겠어요. 20초 뒤에 다시 볼게요.", (car.no().single() as WakeEvent.Done).text)
        assertFalse(car.engine.quietMode)
        assertEquals(0, car.count<WakeEvent.Quiet>())
    }

    @Test
    fun `무응답은 거절로 세지 않아 세 번 넘겨도 조용 모드가 아니고 한도도 그대로다`() {
        val car = Car(demo)
        car.drive()
        repeat(3) { assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(80)) }
        assertFalse(car.engine.quietMode)
        assertEquals(0, car.count<WakeEvent.Quiet>())
        assertEquals(demo.co2LimitPpm, car.engine.effectiveLimitPpm)
        assertNotNull(car.runUntil<WakeEvent.Propose>(40))
    }

    @Test
    fun `바깥 공기로 두기를 거절하고 이어서 2분만도 거절하면 조용 모드이고 한도는 올리지 않는다`() {
        val car = Car(real)
        car.drive(occupants = 4)
        assertNotNull(car.runUntil<WakeEvent.Propose>(400))
        assertEquals("KEEP", car.engine.lastProposalVariant)
        car.no()
        assertEquals(20 * 60, car.runUntil<WakeEvent.Propose>(1300))
        assertEquals("REVERT", car.engine.lastProposalVariant)
        assertEquals(WakeEvent.Quiet(quietText), car.no().last { it !is WakeEvent.TrustChanged })
        assertTrue(car.engine.quietMode)
        assertEquals(2000, car.engine.effectiveLimitPpm)
        car.run(1800)
        assertEquals(2, car.count<WakeEvent.Propose>())
        assertEquals(2, car.arrive().declined)
    }

    @Test
    fun `풍량 올리기 거절은 연속 거절로 세지 않는다`() {
        val car = Car(real)
        car.drive(occupants = 4, recirc = false, fan = 0)
        assertNotNull(car.runUntil<WakeEvent.Propose>(1500))
        assertEquals("FAN_UP", car.engine.lastProposalVariant)
        car.no()
        assertNotNull(car.runUntil<WakeEvent.Propose>(1300))
        assertEquals("FAN_UP", car.engine.lastProposalVariant)
        assertEquals("알겠어요. 20분 뒤에 다시 볼게요.", (car.no().single() as WakeEvent.Done).text)
        assertFalse(car.engine.quietMode)
    }

    // ---------- 2b. 화면 밖 무응답 ----------

    @Test
    fun `화면이 안 보이는 동안 만료된 제안은 세지 않고 5분만 쉰다`() {
        val hidden = Car(real, TrustState(consecutiveYes = 2))
        hidden.drive(occupants = 1)
        hidden.send { AppVisibility(it, false) }
        hidden.reachRevertProposal()
        assertNotNull(hidden.runUntil<WakeEvent.ProposalExpired>(30))
        assertEquals("다음에 다시 물을게요.", hidden.last<WakeEvent.ProposalExpired>()!!.text)
        // 연속 동의는 그대로이고 5분 뒤에 다시 묻는다(보였다면 20분)
        assertEquals(2, hidden.engine.trust.consecutiveYes)
        assertEquals(5 * 60, hidden.runUntil<WakeEvent.Propose>(1300))
        hidden.send { AppVisibility(it, true) }
        hidden.yes()
        assertNotNull(hidden.runUntil<WakeEvent.Done>(200))
        val card = hidden.arrive()
        assertEquals(0, card.declined)
        assertTrue(card.lines.none { it.contains("무응답") }, card.lines.toString())
        assertEquals(offerText, card.trustOffer)
    }

    @Test
    fun `화면이 보일 때 만료되면 기존대로 20분 쉬고 기록에 남는다`() {
        val car = Car(real)
        car.drive(occupants = 1)
        car.reachRevertProposal()
        assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(30))
        assertEquals(20 * 60, car.runUntil<WakeEvent.Propose>(1300))
        assertTrue(car.arrive().lines.any { it.contains("제안 무응답(환기)") })
    }

    @Test
    fun `화면 밖에서 두 번 만료돼도 조용 모드가 아니고 졸음 제안도 5분 뒤 다시 낼 수 있다`() {
        val car = Car(demo)
        car.drive()
        car.send { AppVisibility(it, false) }
        repeat(2) { assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(80)) }
        assertFalse(car.engine.quietMode)
        // 졸음 제안도 화면 밖에서 만료되면 데모 5초 쿨다운만 걸린다
        car.send { RecirculationChanged(it, false) }
        car.run(25)
        car.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(40))
        car.run(20)
        car.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        car.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        car.run(2)
        assertEquals(2, car.proposals.count { it.variant == "DROWSY" })
    }

    // ---------- 9. 쉼 모드 진입 질문 ----------

    /** 쉼 목표 60초(질문 대기 20초보다 길게) */
    private val restDemo = demo.copy(restMinutes = 60)
    private val restAsk = WakeEvent.Propose(TriggerKind.STALE_AIR, "쉬는 동안 바깥 공기로 둘까요?", listOf(Action.Ventilate(fanDelta = 0)), 20)
    private val restClosed = WakeEvent.Done(TriggerKind.STALE_AIR, null)

    /** 졸음 개입으로 쉼터를 경유지로 넣고(원복까지) 그 안에서 P. 쉼 진입 때 나온 이벤트를 돌려준다 */
    private fun Car.parkAtRestStop(recirc: Boolean = true): List<WakeEvent> {
        drive(recirc = recirc)
        send { RestStopCandidate(it, stop) }
        drowsyProposal()
        yes()
        assertNotNull(runUntil<WakeEvent.Done>(60))
        assertEquals(stop, engine.currentWaypoint)
        send { RestStopProximity(it, within = true) }
        return send { GearChanged(it, Gear.PARK) }
    }

    @Test
    fun `내기순환으로 쉬게 되면 RestStarted 뒤에 바깥 공기로 둘지 한 번 묻는다`() {
        val car = Car(restDemo)
        val got = car.parkAtRestStop()
        assertEquals(listOf(WakeEvent.RestStarted("망향 졸음쉼터", 60), restAsk), got)
        assertEquals("REST", car.engine.lastProposalVariant)
        assertEquals("쉬는 동안 바깥 공기로 둘까요?", car.engine.lastProposalText)
        car.run(50)
        assertEquals(1, car.proposals.count { it.variant == "REST" })
    }

    @Test
    fun `쉼 질문에 동의하면 Running 없이 쉬는 동안 유지하고 나갈 때 RestEnded 뒤에 조용히 원복한다`() {
        val car = Car(restDemo)
        car.parkAtRestStop()
        val answered = car.yes()
        assertEquals(listOf(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Ventilate(fanDelta = 0))), restClosed), answered)
        assertFalse(car.recirc)
        val before = car.events.size
        car.run(60)
        assertEquals(before, car.events.size) // 쉬는 동안 RestTick 말고는 아무것도 없다
        assertFalse(car.recirc)
        val ended = car.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(
            listOf(
                WakeEvent.RestEnded(60, "60분 푹 쉬셨어요. 조심히 가요"),
                WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)),
                restClosed,
            ),
            ended,
        )
        assertTrue(car.recirc)
        // 원복 결과가 와도 원복 문장은 없다. 들리는 말은 RestEnded 하나다
        assertTrue(car.events.filterIsInstance<WakeEvent.Done>().none { it.text?.contains("돌렸어요") == true && car.events.indexOf(it) > before })
        val card = car.arrive()
        assertEquals(1, card.ventilations) // 졸음 개입의 환기만 센다
        assertEquals(0, card.declined)
        assertTrue(card.lines.any { it.contains("쉬는 동안 바깥 공기로 두기") }, card.lines.toString())
    }

    @Test
    fun `쉼 질문을 거절하면 아무것도 하지 않고 세지도 않는다`() {
        val car = Car(restDemo)
        car.parkAtRestStop()
        assertEquals(listOf<WakeEvent>(restClosed), car.no())
        assertTrue(car.recirc)
        car.run(30)
        assertEquals(
            listOf<WakeEvent>(WakeEvent.RestEnded(30, "30분 쉬셨어요. 다음 쉼터에서 더 쉬어도 좋아요")),
            car.send { GearChanged(it, Gear.DRIVE) },
        )
        assertEquals(0, car.arrive().declined)
        assertFalse(car.engine.quietMode)
    }

    @Test
    fun `쉼 질문에 답이 없으면 20초 뒤 말없이 닫는다`() {
        val car = Car(restDemo)
        car.parkAtRestStop()
        car.run(2)
        car.speechDone()
        assertTrue(car.run(19).isEmpty())
        assertEquals(listOf<WakeEvent>(restClosed), car.run(1))
        assertEquals(0, car.count<WakeEvent.ProposalExpired>())
        // 늦은 답은 무시한다
        assertTrue(car.yes().isEmpty())
        assertTrue(car.recirc)
        car.run(10)
        assertEquals(1, car.send { GearChanged(it, Gear.DRIVE) }.size)
    }

    @Test
    fun `바깥 공기로 쉬거나 차가 내외기를 제어하면 쉼 질문을 하지 않는다`() {
        val fresh = Car(restDemo)
        assertEquals(listOf<WakeEvent>(WakeEvent.RestStarted("망향 졸음쉼터", 60)), fresh.parkAtRestStop(recirc = false))

        val auto = Car(restDemo)
        auto.send { AutoClimateChanged(it, true) }
        assertEquals(listOf<WakeEvent>(WakeEvent.RestStarted("망향 졸음쉼터", 60)), auto.parkAtRestStop())
    }

    @Test
    fun `쉼 질문이 떠 있을 때 출발하면 닫고 RestEnded 만 낸다`() {
        val car = Car(restDemo)
        car.parkAtRestStop()
        car.run(5)
        val ended = car.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(restClosed, ended.first())
        assertTrue(ended.last() is WakeEvent.RestEnded)
        assertEquals(2, ended.size)
    }

    @Test
    fun `쉬는 동안 운전자가 직접 내기순환으로 되돌리면 나갈 때 원복하지 않는다`() {
        val car = Car(restDemo)
        car.parkAtRestStop()
        car.yes()
        car.run(5)
        assertTrue(car.driverRecirc(true).isEmpty())
        car.run(5)
        val ended = car.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(1, ended.size)
        assertTrue(ended.single() is WakeEvent.RestEnded)
    }

    @Test
    fun `쉼 질문에 동의했는데 제어가 실패하면 조용히 닫고 나갈 때 원복도 없다`() {
        val car = Car(restDemo)
        car.parkAtRestStop()
        car.autoResult = false
        car.yes()
        assertEquals(listOf<WakeEvent>(restClosed), car.send { ActionResult(it, Action.Ventilate(), ok = false, detail = "PERMISSION") })
        car.run(5)
        assertEquals(1, car.send { GearChanged(it, Gear.DRIVE) }.size)
    }

    @Test
    fun `조수석에 사람이 있으면 쉼 질문도 옆자리에서 답할 수 있다`() {
        val car = Car(restDemo)
        car.send { FrontPassengerChanged(it, true) }
        val ask = car.parkAtRestStop().filterIsInstance<WakeEvent.Propose>().single()
        assertTrue(ask.passengerMayAnswer)
        assertEquals("쉬는 동안 바깥 공기로 둘까요?", ask.text)
    }

    // ---------- 한 시간 주행 표(기본 설정, 풍량 3/8, 문장은 4초에 읽고 제안에는 5초 뒤 답한다) ----------

    private fun hourDrive(label: String, occupants: Int, answer: Boolean? = true, trust: TrustState = TrustState()): Pair<Car, ArrivalSummary> {
        val car = Car(real, trust)
        car.autoAnswer = answer
        car.speechSec = 4
        car.drive(occupants = occupants)
        car.run(3600)
        val card = car.arrive()
        fun mmss(sec: Int) = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
        val asked = car.proposals.joinToString { "${mmss(it.atSec)} ${it.variant}" }.ifEmpty { "없음" }
        val told = car.announces.joinToString { mmss(it.atSec) }.ifEmpty { "없음" }
        println(
            "TABLE6 | $label | 질문 ${car.proposals.size}회 [$asked] | 예고 ${car.announces.size}회 [$told] | 조용 ${car.count<WakeEvent.Quiet>()} | " +
                "최고 추정 ${car.peakSeen}ppm | 환기 ${card.ventilations}회 | 바깥 공기 ${card.freshAirMinutes}분 | 한도 위 ${card.minutesOverLimit}분 | " +
                "거절 ${card.declined} | 맡기기 질문 ${if (card.trustOffer != null) "있음" else "없음"} | ${card.headline}",
        )
        return car to card
    }

    @Test
    fun `한 시간 1명은 약 20분부터 2분만을 세 번 묻고 세 번째 원복 전에 도착하면 맡기기 질문은 아직 없다`() {
        val (car, card) = hourDrive("1명, 전부 동의", 1)
        assertEquals(listOf("REVERT", "REVERT", "REVERT"), car.proposals.map { it.variant })
        assertTrue(car.proposals.first().atSec in 1150..1300, "첫 제안 ${car.proposals.first().atSec}초")
        assertEquals(3, card.ventilations)
        // 세 번째 환기는 57분 58초에 물어 60분 3초쯤 원복이 끝난다. 그 전에 도착했으니 연속 동의는 2 이고 다음 주행에서 이어 센다
        assertEquals(2, car.engine.trust.consecutiveYes)
        assertNull(card.trustOffer)
        assertTrue(car.peakSeen <= real.co2LimitPpm * 1.2)
    }

    @Test
    fun `한 시간 맡긴 1명은 질문 없이 예고만 세 번 듣고 같은 만큼 환기한다`() {
        val (asked, _) = hourDrive("1명, 전부 동의(비교용)", 1)
        val (car, card) = hourDrive("1명, 맡기기 켬", 1, trust = TrustState(delegated = true))
        assertTrue(car.proposals.isEmpty())
        assertEquals(3, car.announces.size)
        assertEquals(asked.proposals.first().atSec, car.announces.first().atSec)
        assertEquals(3, card.ventilations)
        assertEquals(0, card.declined)
        assertNull(card.trustOffer)
        assertEquals(0, car.count<WakeEvent.TrustChanged>())
        assertTrue(car.peakSeen <= real.co2LimitPpm * 1.2)
    }

    @Test
    fun `한 시간 1명이 전부 거절하면 두 번 묻고 조용해진다`() {
        val (car, card) = hourDrive("1명, 전부 거절", 1, answer = false)
        assertEquals(listOf("REVERT", "REVERT"), car.proposals.map { it.variant })
        assertEquals(1, car.count<WakeEvent.Quiet>())
        assertEquals(0, card.ventilations)
        assertEquals(2, card.declined)
        assertNull(card.trustOffer)
    }

    @Test
    fun `한 시간 2명은 2분만 두 번 뒤 계속 바깥 공기로 둘지 묻고 맡겨도 세 번째는 묻는다`() {
        val (car, card) = hourDrive("2명, 전부 동의", 2)
        assertEquals(listOf("REVERT", "REVERT", "KEEP_AFTER_TWO"), car.proposals.map { it.variant })
        assertEquals(3, card.ventilations)
        assertNull(card.trustOffer) // 두 번 동의로는 권하지 않는다
        assertEquals(2, car.engine.trust.consecutiveYes)

        val (delegated, _) = hourDrive("2명, 맡기기 켬", 2, trust = TrustState(delegated = true))
        assertEquals(2, delegated.announces.size)
        assertEquals(listOf("KEEP_AFTER_TWO"), delegated.proposals.map { it.variant })
    }

    @Test
    fun `한 시간 4명은 처음부터 바깥 공기로 둘지 한 번만 묻고 맡겨도 같다`() {
        val (car, card) = hourDrive("4명, 전부 동의", 4)
        assertEquals(listOf("KEEP"), car.proposals.map { it.variant })
        assertEquals(1, card.ventilations)
        assertNull(card.trustOffer)
        assertTrue(car.peakSeen <= real.co2LimitPpm * 1.2)

        val (delegated, _) = hourDrive("4명, 맡기기 켬", 4, trust = TrustState(delegated = true))
        assertEquals(listOf("KEEP"), delegated.proposals.map { it.variant })
        assertTrue(delegated.announces.isEmpty())
    }
}

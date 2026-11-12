package ai.kkaeugi.engine

import ai.kkaeugi.engine.Round6Test.Car
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.6.1 에서 고친 것을 본다(4차 리뷰): 예고는 화면이 보일 때만, 예고 창이 끝날 때 다시 검사, 읽지 못한 예고는 다시 묻기,
 * 예고로 시작한 실행 중 취소, 예고와 졸음 경고의 겹침, 조용 모드의 한도 초과 알림과 직접 요청한 환기, 화면 밖 무응답,
 * 낡은 도착 예정, 쉼 진입 질문의 조건, 맡기기 집계.
 */
class Round7Test {

    private val real = WakeConfig()
    private val demo = WakeConfig(
        staleAirMinutes = 40, staleAirMinutesNight = 20, ventilateSeconds = 20, drowsySeconds = 30,
        answerTimeoutSec = 8, declineCooldownMinutes = 20, longTripMinutes = 60, restMinutes = 60,
        demoFast = true, co2PerPersonLph = 0.0,
    )
    private val stop = RestStopInfo("망향", "경부선 서울방향", 12.3, 36.9, 127.1)
    private val announceText = "2분만 바깥 공기로 바꿀게요. 싫으면 '아니'라고 하세요."
    private val delegated = TrustState(delegated = true)

    private fun Car.reachAnnounce(): WakeEvent.Announce {
        assertNotNull(runUntil<WakeEvent.Announce>(1500), "약 20분에 예고가 나와야 한다")
        return last<WakeEvent.Announce>()!!
    }

    /** 예고를 다 읽은 것으로 한다(문장 길이 × 0.1초보다 늦게) */
    private fun Car.announceRead() {
        run(4)
        speechDone()
    }

    private fun Car.drowsyOn(): List<WakeEvent> {
        send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        return send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
    }

    // ---------- P0-1: 예고는 화면이 보일 때만, 실행 직전에 다시 본다 ----------

    @Test
    fun `맡겼어도 화면이 안 보이면 예고하지 않고 묻는다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.send { AppVisibility(it, false) }
        assertNotNull(car.runUntil<WakeEvent.Propose>(1500))
        assertTrue(car.announces.isEmpty())
        assertEquals("REVERT", car.proposals.single().variant)
        assertEquals("AWAITING_ANSWER", car.engine.phaseName)
        // 답이 없으면 차는 그대로다
        assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(60))
        assertEquals(0, car.count<WakeEvent.Execute>())
        assertTrue(car.recirc)
        // 화면이 다시 보이면 다음은 예고다
        car.send { AppVisibility(it, true) }
        assertNotNull(car.runUntil<WakeEvent.Announce>(400))
    }

    @Test
    fun `예고 창이 끝날 때 차 자동 공조나 도착 임박이나 깜빡이나 화면 밖이면 실행하지 않는다`() {
        val blockers: List<Pair<String, (Car) -> Unit>> = listOf(
            "차 자동 공조" to { c -> c.send { AutoClimateChanged(it, true) } },
            "도착 임박" to { c -> c.send { DestinationEta(it, 3) } },
            "깜빡이" to { c -> c.send { TurnSignalChanged(it, TurnSignalState.LEFT) } },
            "경고등" to { c -> c.send { TelltaleChanged(it, Telltale.FCA, true) } },
            "화면 밖" to { c -> c.send { AppVisibility(it, false) } },
        )
        for ((name, block) in blockers) {
            val car = Car(real, delegated)
            car.drive(occupants = 1)
            car.reachAnnounce()
            car.announceRead()
            block(car)
            val closed = car.run(5)
            assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, null)), closed.filter { it !is WakeEvent.Deferred }, name)
            assertEquals(0, car.count<WakeEvent.Execute>(), name)
            assertTrue(car.recirc, name)
            assertEquals("IDLE", car.engine.phaseName, name)
            // 취소로 세지 않는다
            assertEquals(delegated, car.engine.trust, name)
            assertEquals(0, car.count<WakeEvent.TrustChanged>(), name)
            // 5분은 다시 꺼내지 않는다
            car.run(280)
            assertEquals(1, car.announces.size + car.proposals.size, name)
            assertEquals(0, car.arrive().declined, name)
        }
    }

    @Test
    fun `예고 중 자동 공조가 켜졌다 꺼지면 5분 뒤 다시 예고한다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.announceRead()
        car.send { AutoClimateChanged(it, true) }
        car.run(5)
        assertEquals(0, car.count<WakeEvent.Execute>())
        car.send { AutoClimateChanged(it, false) }
        assertNotNull(car.runUntil<WakeEvent.Announce>(320))
        assertEquals(2, car.announces.size)
        car.announceRead()
        car.run(5)
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Ventilate())), car.last<WakeEvent.Execute>())
    }

    @Test
    fun `예고 문장을 읽기에 너무 이른 SpeechFinished 면 실행하지 않고 다시 묻는다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.run(1)
        // 32자 문장이 1초 만에 끝났다. 읽지 못한 것으로 본다
        val got = car.speechDone().filterIsInstance<WakeEvent.Propose>().single()
        assertEquals(TriggerKind.STALE_AIR, got.kind)
        assertTrue(got.text.endsWith("2분만 바깥 공기로 바꿀까요?"), got.text)
        assertEquals("REVERT", car.engine.lastProposalVariant)
        assertEquals("AWAITING_ANSWER", car.engine.phaseName)
        // 답이 없으면 실행하지 않는다
        assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(60))
        assertEquals(0, car.count<WakeEvent.Execute>())
        assertTrue(car.recirc)
        assertEquals(0, car.engine.trust.cancelCount)
    }

    @Test
    fun `예고를 0,5초 안에 끝냈다고 해도 잔재로 넘기지 않고 다시 묻는다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.now += 200
        assertEquals(1, car.speechDone().count { it is WakeEvent.Propose })
    }

    // ---------- P1-1: 예고로 시작한 실행 중 취소 ----------

    @Test
    fun `예고로 시작한 환기 유지 중에 아니라고 하면 바로 원복하고 취소로 센다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.announceRead()
        car.run(5)
        assertFalse(car.recirc)
        assertEquals("RUNNING", car.engine.phaseName)
        assertTrue(car.engine.cancellableRun)
        car.run(20)
        val got = car.no()
        assertEquals(
            listOf(
                WakeEvent.TrustChanged(TrustState(delegated = true, cancelCount = 1)),
                WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)),
                WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 원래대로 돌렸어요."),
            ),
            got,
        )
        assertTrue(car.recirc)
        assertFalse(car.engine.cancellableRun)
        // 남은 주행은 다시 묻는다. 거절과 같은 20분 쿨다운
        assertNotNull(car.runUntil<WakeEvent.Propose>(1300))
        assertEquals(1, car.announces.size)
        assertTrue(car.proposals.single().atSec >= 1200 + 20 * 60)
        assertEquals(1, car.arrive().declined)
    }

    @Test
    fun `예고로 시작한 실행의 결과를 기다리는 중에 아니라고 해도 바로 원복한다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.announceRead()
        car.autoResult = false
        car.run(5)
        assertEquals("EXECUTING", car.engine.phaseName)
        val got = car.no()
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)), got.filterIsInstance<WakeEvent.Execute>().single())
        assertEquals("REVERTING", car.engine.phaseName)
        // 늦게 온 제어 결과는 무시하고 원복 결과로 닫는다
        assertTrue(car.send { ActionResult(it, Action.Ventilate(), ok = true) }.isEmpty())
        assertEquals(
            listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 원래대로 돌렸어요.")),
            car.send { ActionResult(it, Action.Revert, ok = true) },
        )
        assertEquals(1, car.engine.trust.cancelCount)
    }

    @Test
    fun `물어서 동의한 환기 중의 아니는 전처럼 무시한다`() {
        val car = Car(real)
        car.drive(occupants = 1)
        assertNotNull(car.runUntil<WakeEvent.Propose>(1500))
        car.yes()
        car.run(10)
        assertFalse(car.engine.cancellableRun)
        assertTrue(car.no().isEmpty())
        assertEquals("RUNNING", car.engine.phaseName)
    }

    // ---------- P1-2: 예고와 졸음 경고 ----------

    @Test
    fun `예고 중 졸음 경고가 오면 예고를 닫고 졸음 제안을 바로 낸다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.run(2)
        val got = car.drowsyOn()
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, null), got[0])
        val p = got[1] as WakeEvent.Propose
        assertEquals(TriggerKind.DROWSY, p.kind)
        assertEquals("졸음 경고가 떴어요. 찬 바람을 넣을까요?", p.text)
        assertEquals(2, got.size)
        assertEquals(0, car.count<WakeEvent.Execute>())
        assertEquals(delegated, car.engine.trust)
        // 예고의 취소 창이 지나도 환기는 실행되지 않는다
        car.run(4)
        assertEquals(0, car.count<WakeEvent.Execute>())
    }

    @Test
    fun `예고로 시작한 환기 유지 중에 졸음 경고가 오면 조용히 원복하고 바로 졸음 제안을 낸다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.announceRead()
        car.run(5)
        assertEquals("RUNNING", car.engine.phaseName)
        car.run(30)
        val got = car.drowsyOn()
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)), got[0])
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, null), got[1])
        assertEquals(TriggerKind.DROWSY, (got[2] as WakeEvent.Propose).kind)
        assertEquals(3, got.size)
        // 취소로 세지 않는다
        assertEquals(delegated, car.engine.trust)
        // 동의하면 졸음 개입이 그대로 돈다
        car.yes()
        assertEquals("RUNNING", car.engine.phaseName)
        assertFalse(car.recirc)
        assertNotNull(car.runUntil<WakeEvent.Done>(200))
        assertTrue(car.recirc)
        val card = car.arrive()
        assertEquals(1, card.drowsySignals)
        assertEquals(0, card.declined)
    }

    // ---------- P1-3: 조용 모드 ----------

    @Test
    fun `조용 모드로 4명이 오래 달리면 한도의 2배에서 한 번만 알리고 화면에서 직접 환기할 수 있다`() {
        val car = Car(real)
        car.autoAnswer = false
        car.speechSec = 4
        car.drive(occupants = 4)
        assertNotNull(car.runUntil<WakeEvent.Quiet>(3000))
        assertTrue(car.engine.quietMode)
        assertEquals(2, car.proposals.size)
        assertEquals("이번 주행에서는 더 묻지 않을게요. 화면에만 표시할게요.", car.last<WakeEvent.Quiet>()!!.text)
        // 이미 한도 위다. 홈 화면은 묻지 않는 중이라는 말보다 한도 초과를 먼저 보여 준다
        assertTrue(car.engine.overLimitWhileQuiet)
        assertNotNull(car.runUntil<WakeEvent.Quiet>(1800), "한도의 2배를 넘으면 알린다")
        val notice = car.last<WakeEvent.Quiet>()!!.text
        assertTrue(Regex("""차 안 공기가 많이 탁해요\(추정 [0-9,]+ppm\)\. 화면에서 바깥 공기로 바꿀 수 있어요\.""").matches(notice), notice)
        assertTrue(car.engine.estimatedPpm >= 4000)
        // 주행당 한 번
        car.run(3600)
        assertEquals(2, car.count<WakeEvent.Quiet>())
        assertEquals(2, car.proposals.size)

        // 화면에서 직접 "바깥 공기로"
        val started = car.feed(car.engine.requestVentilation(car.now))
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Ventilate())), started.first())
        assertFalse(car.recirc)
        assertEquals("RUNNING", car.engine.phaseName)
        assertTrue(car.engine.cancellableRun)
        assertEquals(120, car.runUntil<WakeEvent.Done>(200))
        assertTrue(car.last<WakeEvent.Done>()!!.text!!.endsWith("원래대로 돌렸어요."))
        assertTrue(car.recirc)
        // 맡기기와 거절 집계에는 넣지 않는다
        assertEquals(TrustState(), car.engine.trust)
        val card = car.arrive()
        assertEquals(2, card.declined)
        assertEquals(1, card.ventilations)
        assertNull(card.trustOffer)
    }

    @Test
    fun `조용 모드로 달리다 한도의 2배를 넘는 순간의 알림은 추정 4,000ppm 이다`() {
        // 숨이 얕은 2명(1인 12L/시간): 약 15분에 한도, 약 35분에 조용 모드(추정 3,200ppm), 약 67분에 4,000ppm
        val car = Car(real.copy(co2PerPersonLph = 12.0))
        car.autoAnswer = false
        car.drive(occupants = 2)
        assertNotNull(car.runUntil<WakeEvent.Quiet>(3000))
        assertTrue(car.engine.estimatedPpm < 3500, "조용해질 때 ${car.engine.estimatedPpm}ppm")
        // 1초 단위로 보다가 처음 넘는 순간에 나오므로 100 단위로 반올림한 값은 4,000 이다
        assertNotNull(car.runUntil<WakeEvent.Quiet>(3600))
        assertEquals("차 안 공기가 많이 탁해요(추정 4,000ppm). 화면에서 바깥 공기로 바꿀 수 있어요.", car.last<WakeEvent.Quiet>()!!.text)

        // 조용해질 때 이미 2배를 넘어 있으면(기본 설정 2명) 바로 말하지 않고 5분 뒤에 알린다
        val two = Car(real)
        two.autoAnswer = false
        two.drive(occupants = 2)
        assertNotNull(two.runUntil<WakeEvent.Quiet>(3000))
        assertTrue(two.engine.estimatedPpm >= 4000)
        assertEquals(300, two.runUntil<WakeEvent.Quiet>(400))
        // 1명은 내기순환으로 오래 달려도 한도의 2배에 닿지 않아 알림이 없다
        val one = Car(real)
        one.autoAnswer = false
        one.drive(occupants = 1)
        one.run(7200)
        assertTrue(one.engine.quietMode)
        assertEquals(1, one.count<WakeEvent.Quiet>())
    }

    /** 한 시간 주행 표의 "전부 거절" 행(README, CONCEPT). 문장은 4초에 읽고 5초 뒤 거절한다 */
    @Test
    fun `한 시간 전부 거절하면 1명은 두 번 묻고 조용하고 4명은 조용해진 5분 뒤 한 번 알린다`() {
        fun mmss(sec: Int) = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
        for (n in listOf(1, 2, 4)) {
            val car = Car(real)
            car.autoAnswer = false
            car.speechSec = 4
            car.drive(occupants = n)
            val quietAt = mutableListOf<Int>()
            repeat(3600) { sec -> if (car.run(1).any { it is WakeEvent.Quiet }) quietAt += sec + 1 }
            val notice = car.events.filterIsInstance<WakeEvent.Quiet>().getOrNull(1)?.text
            val card = car.arrive()
            println(
                "TABLE7 | ${n}명, 전부 거절 | 질문 ${car.proposals.size}회 [${car.proposals.joinToString { "${mmss(it.atSec)} ${it.variant}" }}] | " +
                    "조용 ${quietAt.firstOrNull()?.let { mmss(it) }} | 한도 2배 알림 ${quietAt.getOrNull(1)?.let { mmss(it) } ?: "없음"} ${notice ?: ""} | " +
                    "최고 추정 ${car.peakSeen}ppm | 거절 ${card.declined} | ${card.headline}",
            )
            assertEquals(2, car.proposals.size)
            assertEquals(2, card.declined)
            assertEquals(0, card.ventilations)
            if (n == 1) assertEquals(1, quietAt.size) else {
                assertEquals(2, quietAt.size)
                assertTrue(quietAt[1] - quietAt[0] >= 300, "조용해진 뒤 5분은 말을 걸지 않는다")
            }
        }
    }

    @Test
    fun `직접 요청한 환기는 바깥 공기거나 다른 개입 중이거나 주행 중이 아니면 아무것도 하지 않는다`() {
        val idle = WakeEngine(real)
        assertTrue(idle.requestVentilation(1_000L).isEmpty())

        val fresh = Car(real)
        fresh.drive(recirc = false)
        fresh.run(10)
        assertTrue(fresh.engine.requestVentilation(fresh.now).isEmpty())

        val busy = Car(real)
        busy.drive(occupants = 1)
        assertNotNull(busy.runUntil<WakeEvent.Propose>(1500))
        assertTrue(busy.engine.requestVentilation(busy.now).isEmpty())
        assertEquals("AWAITING_ANSWER", busy.engine.phaseName)
    }

    @Test
    fun `직접 요청한 환기 중의 아니는 원복만 하고 어디에도 세지 않는다`() {
        val car = Car(real)
        car.drive(occupants = 1)
        car.run(60)
        car.feed(car.engine.requestVentilation(car.now))
        car.run(10)
        val got = car.no()
        assertEquals(
            listOf(
                WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)),
                WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 원래대로 돌렸어요."),
            ),
            got,
        )
        assertEquals(TrustState(), car.engine.trust)
        assertEquals(0, car.arrive().declined)
    }

    @Test
    fun `쉼터에서 쉬고 나면 조용 모드가 풀려 다시 묻는다`() {
        val car = Car(demo)
        car.drive()
        assertEquals(40, car.runUntil<WakeEvent.Propose>(60))
        car.no()
        assertEquals(20, car.runUntil<WakeEvent.Propose>(30))
        car.no()
        assertTrue(car.engine.quietMode)
        car.run(25)
        // 졸음 개입으로 쉼터를 경유지에 넣고 들른다
        car.send { RestStopCandidate(it, stop) }
        car.drowsyOn()
        car.yes()
        assertNotNull(car.runUntil<WakeEvent.Done>(60))
        car.send { RestStopProximity(it, within = true) }
        car.send { GearChanged(it, Gear.PARK) }
        assertTrue(car.engine.isResting)
        car.no() // 쉬는 동안 바깥 공기 질문은 넘긴다
        car.run(30)
        car.send { GearChanged(it, Gear.DRIVE) }
        assertFalse(car.engine.quietMode)
        val before = car.proposals.size
        assertNotNull(car.runUntil<WakeEvent.Propose>(60))
        assertEquals(before + 1, car.proposals.size)
        // 한 번 더 거절해도 바로 조용해지지 않는다(연속 거절을 0 부터 다시 센다)
        car.no()
        assertFalse(car.engine.quietMode)
    }

    // ---------- P1-4: 화면 밖 무응답 ----------

    @Test
    fun `화면 밖 만료가 두 번 연속이면 거절과 같은 쿨다운을 걸고 화면이 보이면 다시 5분부터다`() {
        val car = Car(demo)
        car.drive()
        car.send { AppVisibility(it, false) }
        assertEquals(40, car.runUntil<WakeEvent.Propose>(60))
        assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(40))
        val first = car.now
        // 첫 번째는 5초(데모) 뒤에 다시 묻는다
        assertNotNull(car.runUntil<WakeEvent.Propose>(30))
        assertTrue((car.now - first) / 1000 in 5..7, "간격 ${(car.now - first) / 1000}초")
        assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(40))
        val second = car.now
        // 두 번째부터는 20초(데모)
        assertNotNull(car.runUntil<WakeEvent.Propose>(40))
        assertTrue((car.now - second) / 1000 in 20..22, "간격 ${(car.now - second) / 1000}초")
        // 화면이 보였다가 다시 가려지면 처음부터 센다
        car.send { AppVisibility(it, true) }
        car.send { AppVisibility(it, false) }
        assertNotNull(car.runUntil<WakeEvent.ProposalExpired>(40))
        val third = car.now
        assertNotNull(car.runUntil<WakeEvent.Propose>(30))
        assertTrue((car.now - third) / 1000 in 5..7)
        assertFalse(car.engine.quietMode)
        assertEquals(0, car.arrive().declined)
    }

    @Test
    fun `화면 밖에서 두 시간 답이 없으면 질문은 일곱 번을 넘지 않는다`() {
        val car = Car(real)
        car.speechSec = 4
        car.drive(occupants = 1)
        car.send { AppVisibility(it, false) }
        car.run(7200)
        println("TABLE7 | 1명, 화면 밖 2시간 무응답 | 질문 ${car.proposals.size}회 [${car.proposals.joinToString { "${it.atSec / 60}:${(it.atSec % 60).toString().padStart(2, '0')}" }}]")
        // 약 20분에 처음, 5분 뒤 한 번 더, 그 뒤로는 20분마다. 전에는 5분마다라 열아홉 번이었다
        assertTrue(car.proposals.size in 5..7, "질문 ${car.proposals.size}회")
        assertEquals(car.proposals.size, car.count<WakeEvent.ProposalExpired>())
        assertFalse(car.engine.quietMode)
        assertEquals(0, car.count<WakeEvent.Execute>())
        assertEquals(0, car.arrive().declined)
    }

    // ---------- P1-5: 낡은 도착 예정 ----------

    @Test
    fun `도착 예정은 받은 지 10분이 지나면 쓰지 않는다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.run(700)
        car.send { DestinationEta(it, 3) }
        assertTrue(car.engine.arrivingSoon)
        car.run(600)
        assertTrue(car.engine.arrivingSoon)
        // 내비가 더 알려 주지 않는다. 10분이 지나면 믿지 않고 환기를 꺼낸다
        assertNotNull(car.runUntil<WakeEvent.Announce>(5))
        assertFalse(car.engine.arrivingSoon)
        assertEquals(1, car.deferred("arriving"))
    }

    @Test
    fun `데모 설정에서는 10초가 지나면 낡은 도착 예정이고 졸음 문장에도 쓰지 않는다`() {
        val car = Car(demo)
        car.drive(recirc = false)
        car.send { RestStopCandidate(it, stop) }
        car.send { DestinationEta(it, 4) }
        car.run(11)
        assertFalse(car.engine.arrivingSoon)
        val p = car.drowsyOn().filterIsInstance<WakeEvent.Propose>().single()
        assertEquals("졸음 경고가 떴어요. 찬 바람 넣고, 12km 앞 경부선 망향 졸음쉼터 경유까지 할까요?", p.text)
    }

    @Test
    fun `새 주행을 시작하면 지난 주행의 도착 예정은 버린다`() {
        val car = Car(real)
        car.send { DestinationEta(it, 2) }
        assertTrue(car.engine.arrivingSoon)
        car.drive(occupants = 1)
        assertFalse(car.engine.arrivingSoon)
        car.send { DestinationEta(it, 2) }
        assertTrue(car.engine.arrivingSoon)
        car.arrive()
        assertFalse(car.engine.arrivingSoon)
    }

    // ---------- P1-9: 쉼 진입 질문 ----------

    @Test
    fun `바깥 공기로 달리다 졸음 개입 중에 쉼터에 서면 바깥 공기 질문을 하지 않는다`() {
        val car = Car(demo)
        car.drive(recirc = false)
        car.send { RestStopCandidate(it, stop) }
        car.drowsyOn()
        car.yes()
        assertEquals("RUNNING", car.engine.phaseName)
        assertEquals(stop, car.engine.currentWaypoint)
        car.run(3)
        car.send { RestStopProximity(it, within = true) }
        val got = car.send { GearChanged(it, Gear.PARK) }
        assertTrue(car.engine.isResting)
        assertEquals(WakeEvent.RestStarted("망향 졸음쉼터", 60), got.last())
        assertTrue(got.none { it is WakeEvent.Propose }, got.toString())
        assertFalse(car.recirc)
    }

    @Test
    fun `내기순환으로 달리다 졸음 개입 중에 쉼터에 서면 풍량은 그대로 두고 바깥 공기로 둘지 묻는다`() {
        val car = Car(demo)
        car.drive(recirc = true)
        car.send { RestStopCandidate(it, stop) }
        car.drowsyOn()
        car.yes()
        car.run(3)
        car.send { RestStopProximity(it, within = true) }
        val ask = car.send { GearChanged(it, Gear.PARK) }.filterIsInstance<WakeEvent.Propose>().single()
        assertEquals("쉬는 동안 바깥 공기로 둘까요?", ask.text)
        assertEquals(listOf<Action>(Action.Ventilate(fanDelta = 0)), ask.actions)
        val fanBefore = car.fan
        val done = car.yes()
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Ventilate(fanDelta = 0))), done.first())
        assertEquals(fanBefore, car.fan)
        assertFalse(car.recirc)
    }

    // ---------- P2: 맡기기 집계, 문장 ----------

    @Test
    fun `맡기기 질문은 이번 주행에서 연속 동의가 늘었을 때만 카드에 싣는다`() {
        // 지난 주행까지 세 번 동의했지만 이번 주행에는 환기가 없었다
        val none = Car(real, TrustState(consecutiveYes = 3))
        none.drive(occupants = 1, recirc = false)
        none.run(600)
        assertNull(none.arrive().trustOffer)

        // 이번 주행에서 세 번째 동의를 채웠다
        val gained = Car(real, TrustState(consecutiveYes = 2))
        gained.drive(occupants = 1)
        assertNotNull(gained.runUntil<WakeEvent.Propose>(1500))
        gained.yes()
        assertNotNull(gained.runUntil<WakeEvent.Done>(200))
        assertEquals(3, gained.engine.trust.consecutiveYes)
        assertEquals(WakeEngine.TRUST_OFFER_TEXT, gained.arrive().trustOffer)
    }

    @Test
    fun `맡긴 환기를 원복까지 정상으로 끝내면 취소 횟수를 지운다`() {
        val car = Car(real, TrustState(delegated = true, cancelCount = 1))
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.announceRead()
        car.run(5)
        assertNotNull(car.runUntil<WakeEvent.Done>(200))
        assertEquals(TrustState(delegated = true, cancelCount = 0), car.engine.trust)
        assertEquals(WakeEvent.TrustChanged(TrustState(delegated = true)), car.last<WakeEvent.TrustChanged>())
    }

    @Test
    fun `맡긴 환기 중에 직접 내기순환을 켜면 취소 횟수는 지우지 않고 늘린다`() {
        val car = Car(real, TrustState(delegated = true, cancelCount = 0))
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.announceRead()
        car.run(5)
        car.run(20)
        car.driverRecirc(true)
        assertNotNull(car.runUntil<WakeEvent.Done>(200))
        assertEquals(1, car.engine.trust.cancelCount)
    }

    @Test
    fun `setTrust 로 맡기기를 끄면 진행 중인 예고를 닫고 곧 묻는다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.run(2)
        val closed = car.feed(car.engine.setTrust(TrustState(delegated = false), car.now))
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, null)), closed)
        assertEquals("IDLE", car.engine.phaseName)
        assertNotNull(car.runUntil<WakeEvent.Propose>(5))
        assertEquals(0, car.count<WakeEvent.Execute>())
        // 예고가 없을 때는 아무 이벤트도 없다
        assertTrue(WakeEngine(real).setTrust(TrustState(delegated = true)).isEmpty())
    }

    @Test
    fun `setTrust 로 맡기기를 다시 켜면 그 주행의 다시 묻기 상태가 풀린다`() {
        val car = Car(real, delegated)
        car.drive(occupants = 1)
        car.reachAnnounce()
        car.announceRead()
        car.no() // 취소 1회. 남은 주행은 다시 묻는다
        assertEquals(1, car.engine.trust.cancelCount)
        car.feed(car.engine.setTrust(TrustState(delegated = false), car.now))
        car.feed(car.engine.setTrust(TrustState(delegated = true), car.now))
        assertNotNull(car.runUntil<WakeEvent.Announce>(1300))
        assertEquals(0, car.proposals.size)
        assertEquals(2, car.announces.size)
    }

    @Test
    fun `야간 장거리 접두는 오래 달려서 조금 일찍 물어요 다`() {
        assertEquals("오래 달려서 조금 일찍 물어요. ", WakeEngine.NIGHT_PREFIX)
        val car = Car(real)
        car.send { LowBeamChanged(it, true) }
        car.drive(occupants = 1)
        car.send { TripTimeUpdate(it, 120) }
        assertNotNull(car.runUntil<WakeEvent.Propose>(1500))
        assertTrue(car.proposals.single().text.startsWith("오래 달려서 조금 일찍 물어요. "))
    }
}

package ai.kkaeugi.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 데모 설정으로 돌린다: 분 단위 설정을 초로 읽고(staleAir 40초, 야간 20초, 쿨다운 20초, 장거리 60초, 쉼 15초),
 * 환기 유지 20초, 졸음 유지 30초, 동의 대기 8초.
 *
 * 이 파일은 시간 상한 경로와 상태기계를 본다. 숨 모델이 끼어들지 않게 사람이 내쉬는 CO2 를 0 으로 둔다
 * (추정치가 한도에 닿지 않으니 환기 제안은 전부 시간 상한 변형이다). 숨 모델 경로는 AirEngineTest 와 Round5Test 가 본다.
 * 하네스는 AirEstimate 를 events 에 넣지 않고 airs 에 따로 모은다.
 */
class WakeEngineTest {

    private val demo = WakeConfig(
        staleAirMinutes = 40,
        staleAirMinutesNight = 20,
        ventilateSeconds = 20,
        drowsySeconds = 30,
        answerTimeoutSec = 8,
        declineCooldownMinutes = 20,
        longTripMinutes = 60,
        steeringAssist = true,
        allowWindow = true,
        restMinutes = 15,
        demoFast = true,
        co2PerPersonLph = 0.0,
    )

    private val stop = RestStopInfo("망향졸음쉼터", "경부선 서울방향", 12.3, 36.9, 127.1)

    /** 1초 간격으로 tick 을 돌리면서 이벤트를 모으는 작은 하네스 */
    private class Harness(config: WakeConfig, start: Long = 1_000_000L) {
        val engine = WakeEngine(config)
        var now = start
        val events = mutableListOf<WakeEvent>()
        val airs = mutableListOf<WakeEvent.AirEstimate>()

        private fun split(all: List<WakeEvent>): List<WakeEvent> {
            airs += all.filterIsInstance<WakeEvent.AirEstimate>()
            return all.filter { it !is WakeEvent.AirEstimate }
        }

        fun send(make: (Long) -> Signal): List<WakeEvent> = split(engine.accept(make(now))).also { events += it }

        /** seconds 초 동안 1초마다 tick. 그동안 나온 이벤트를 돌려준다 */
        fun run(seconds: Int): List<WakeEvent> {
            val got = mutableListOf<WakeEvent>()
            repeat(seconds) {
                now += 1_000
                got += split(engine.tick(now))
            }
            events += got
            return got
        }

        fun startDriving() {
            send { GearChanged(it, Gear.DRIVE) }
        }

        inline fun <reified T : WakeEvent> last(): T? = events.filterIsInstance<T>().lastOrNull()
        inline fun <reified T : WakeEvent> count(): Int = events.count { it is T }
    }

    private fun Harness.startRecirc() = send { RecirculationChanged(it, true) }
    private fun Harness.candidate(s: RestStopInfo? = stop) = send { RestStopCandidate(it, s) }
    private fun Harness.speechDone() = send { SpeechFinished(it) }

    /** 내기순환 켜고 40초 기다려 환기 제안까지 받는다 */
    private fun Harness.reachStaleProposal(): WakeEvent.Propose {
        startRecirc()
        run(40)
        val p = last<WakeEvent.Propose>()
        assertNotNull(p, "40초 뒤 환기 제안이 나와야 한다")
        assertEquals(TriggerKind.STALE_AIR, p.kind)
        return p
    }

    /** 졸음 경고등으로 졸음 제안을 받는다 */
    private fun Harness.reachDrowsyProposal(): WakeEvent.Propose {
        val got = send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        val p = got.filterIsInstance<WakeEvent.Propose>().single()
        assertEquals(TriggerKind.DROWSY, p.kind)
        return p
    }

    private fun Harness.arrive(): ArrivalSummary =
        send { GearChanged(it, Gear.PARK) }.filterIsInstance<WakeEvent.ArrivalCard>().single().card

    // ---------- 세션 ----------

    @Test
    fun `기어 D 로 세션이 시작되고 P 로 끝나며 도착 카드는 한 번만`() {
        val h = Harness(demo)
        val started = h.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(WakeEvent.TripStateChanged(TripState.DRIVING), started.single())
        assertEquals(TripState.DRIVING, h.engine.tripState)

        h.run(5)
        val ended = h.send { GearChanged(it, Gear.PARK) }
        assertTrue(ended.contains(WakeEvent.TripStateChanged(TripState.ENDED)))
        assertEquals(1, ended.count { it is WakeEvent.ArrivalCard })

        // ENDED 에서 tick 을 더 돌려도 카드는 다시 나오지 않는다
        h.run(5)
        assertEquals(1, h.count<WakeEvent.ArrivalCard>())
        assertEquals(TripState.ENDED, h.engine.tripState)

        h.engine.reset()
        assertEquals(TripState.IDLE, h.engine.tripState)
    }

    @Test
    fun `기어 N 에서 주차 브레이크 경고등 3초 유지로 세션이 끝난다`() {
        val h = Harness(demo)
        h.startDriving()
        h.send { GearChanged(it, Gear.NEUTRAL) }
        h.send { TelltaleChanged(it, Telltale.PARKING_BRAKE, true) }
        h.run(2)
        assertEquals(TripState.DRIVING, h.engine.tripState)
        h.run(1)
        assertEquals(TripState.ENDED, h.engine.tripState)
        assertEquals(1, h.count<WakeEvent.ArrivalCard>())
    }

    @Test
    fun `기어 D 상태에서 들어온 주차 브레이크 ON 은 무시한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.send { TelltaleChanged(it, Telltale.PARKING_BRAKE, true) }
        h.run(10)
        assertEquals(TripState.DRIVING, h.engine.tripState)
        assertEquals(0, h.count<WakeEvent.ArrivalCard>())
        // N 으로 바꾼 뒤 다시 ON 이 오면 그때부터 3초
        h.send { GearChanged(it, Gear.NEUTRAL) }
        h.send { TelltaleChanged(it, Telltale.PARKING_BRAKE, true) }
        h.run(3)
        assertEquals(TripState.ENDED, h.engine.tripState)
    }

    @Test
    fun `IDLE 에서는 어떤 제안도 하지 않는다`() {
        val h = Harness(demo)
        h.startRecirc()
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        h.run(60)
        assertEquals(0, h.count<WakeEvent.Propose>())
        assertEquals(0, h.count<WakeEvent.Deferred>())
        assertTrue(h.events.isEmpty())
    }

    // ---------- STALE_AIR ----------

    @Test
    fun `내기순환 40초 경과 시 환기 제안`() {
        val h = Harness(demo)
        h.startDriving()
        h.startRecirc()
        h.run(39)
        assertEquals(0, h.count<WakeEvent.Propose>())
        h.run(1)
        val p = h.last<WakeEvent.Propose>()
        assertNotNull(p)
        assertEquals(TriggerKind.STALE_AIR, p.kind)
        assertEquals(listOf<Action>(Action.Ventilate()), p.actions)
        assertEquals("내기순환으로 40분째예요. 20초만 바깥 공기로 바꿀까요?", p.text)
        assertEquals(8, p.timeoutSec)
        assertEquals(p.text, h.engine.lastProposalText)
        assertEquals("TIME_CAP", h.engine.lastProposalVariant)
    }

    @Test
    fun `내기순환이 중간에 꺼지면 카운터가 리셋된다`() {
        val h = Harness(demo)
        h.startDriving()
        h.startRecirc()
        h.run(30)
        h.send { RecirculationChanged(it, false) }
        h.run(20)
        assertEquals(0, h.count<WakeEvent.Propose>())

        // 다시 켜면 0 부터
        h.startRecirc()
        h.run(39)
        assertEquals(0, h.count<WakeEvent.Propose>())
        h.run(1)
        assertEquals(1, h.count<WakeEvent.Propose>())
    }

    @Test
    fun `동의하면 Execute 후 ActionResult 를 받아 Running 카운트다운, 만료되면 Revert, 결과 받으면 Done`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()

        val yes = h.send { UserAnswer(it, true) }
        val exec = yes.filterIsInstance<WakeEvent.Execute>().single()
        assertEquals(TriggerKind.STALE_AIR, exec.kind)
        assertEquals(listOf<Action>(Action.Ventilate()), exec.actions)

        // ActionResult 가 오기 전에는 Running 이 나오지 않는다
        h.run(3)
        assertEquals(0, h.count<WakeEvent.Running>())

        val res = h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        assertEquals(WakeEvent.Running(TriggerKind.STALE_AIR, 20, "바깥 공기로 바꾸는 중"), res.single())

        val ticks = h.run(19)
        val remaining = ticks.filterIsInstance<WakeEvent.Running>().map { it.remainingSec }
        assertEquals((19 downTo 1).toList(), remaining)
        assertEquals(0, h.count<WakeEvent.Execute>() - 1)

        val expire = h.run(1)
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)), expire.single())

        val done = h.send { ActionResult(it, Action.Revert, ok = true) }
        // CO2 생성이 0 인 설정이라 추정치는 바깥 값 쪽으로 내려가기만 한다(약 480 → 430). 50 넘게 내려가 수치가 붙는다
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "추정 400ppm까지 내려갔어요. 원래대로 돌렸어요."), done.single())

        // 원복 뒤 카운터는 0 부터 다시: 39초는 조용, 40초에 다시 제안
        h.run(39)
        assertEquals(1, h.count<WakeEvent.Propose>())
        h.run(1)
        assertEquals(2, h.count<WakeEvent.Propose>())
    }

    @Test
    fun `Revert 결과가 20초 안에 안 오면 원복 미확인 문장으로 강제 종료`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(20) // Execute(Revert)
        assertEquals(2, h.count<WakeEvent.Execute>())
        h.run(19)
        assertEquals(0, h.count<WakeEvent.Done>())
        val got = h.run(1)
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "원복을 확인하지 못했어요."), got.single())
    }

    @Test
    fun `Revert 결과가 실패면 일부 못 돌렸다고 말한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(20) // Execute(Revert)
        val got = h.send { ActionResult(it, Action.Revert, ok = false, detail = "WINDOW_STUCK") }
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "일부는 못 돌렸어요. 직접 확인해 주세요."), got.single())
        assertEquals("IDLE", h.engine.phaseName)
    }

    @Test
    fun `실행이 전부 실패하면 Execute(Revert) 를 먼저 내고 원복 결과 뒤 실패 문장으로 Done, 짧은 쿨다운`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        val res = h.send { ActionResult(it, Action.Ventilate(), ok = false, detail = "HVAC 거부") }
        // 늦게 성공한 제어가 남지 않도록 원복을 먼저 시킨다
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)), res.single())
        assertEquals("REVERTING", h.engine.phaseName)
        val done = h.send { ActionResult(it, Action.Revert, ok = true) }
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "지금은 차를 바꿀 수 없어요. 조금 뒤에 다시 볼게요."), done.single())
        assertEquals(0, h.count<WakeEvent.Running>())

        // 실패 쿨다운 5초(데모) 뒤 조건이 남아 있으면 다시 묻는다(발화 쿨다운 20초가 더 길어서 그때)
        h.run(19)
        assertEquals(1, h.count<WakeEvent.Propose>())
        assertEquals("cooldown", h.last<WakeEvent.Deferred>()?.reason)
        h.run(2)
        assertEquals(2, h.count<WakeEvent.Propose>())

        val card = h.arrive()
        assertEquals(0, card.ventilations) // 실패한 환기는 세지 않는다
    }

    @Test
    fun `전부 TIMEOUT 뒤 늦게 온 성공 ActionResult 는 REVERTING 중 무시되고 Revert 결과로 Done`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        val got = h.run(30) // 결과 없이 30초 → 전부 TIMEOUT
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)), got.last())
        assertEquals("REVERTING", h.engine.phaseName)
        // 늦게 성공한 제어 결과: Running 으로 가지 않는다
        assertTrue(h.send { ActionResult(it, Action.Ventilate(), ok = true) }.isEmpty())
        assertEquals("REVERTING", h.engine.phaseName)
        assertEquals(0, h.count<WakeEvent.Running>())
        val done = h.send { ActionResult(it, Action.Revert, ok = true) }
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "지금은 차를 바꿀 수 없어요. 조금 뒤에 다시 볼게요."), done.single())
        assertEquals(0, h.arrive().ventilations)
    }

    @Test
    fun `DRIVING_RESTRICTED 로 공조가 다 실패하고 쉼터만 들어가면 원복 뒤 그 사실을 말해 준다`() {
        val h = Harness(demo)
        h.startDriving()
        h.candidate()
        h.reachDrowsyProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.CoolDown(), ok = false, detail = "DRIVING_RESTRICTED") }
        h.send { ActionResult(it, Action.OpenWindow(), ok = false, detail = "DRIVING_RESTRICTED") }
        h.send { ActionResult(it, Action.AddRestStop(stop), ok = true) }
        val last = h.send { ActionResult(it, Action.Ventilate(), ok = false, detail = "DRIVING_RESTRICTED") }
        assertEquals(WakeEvent.Execute(TriggerKind.DROWSY, listOf(Action.Revert)), last.single())
        val done = h.send { ActionResult(it, Action.Revert, ok = true) }
        assertEquals(
            WakeEvent.Done(TriggerKind.DROWSY, "주행 중엔 공조를 못 바꿔요. 조금 뒤에 다시 볼게요. 쉼터 경유지는 넣었어요."),
            done.single(),
        )
        assertEquals(0, h.count<WakeEvent.Running>())
        assertEquals(stop, h.engine.currentWaypoint)
        assertEquals(1, h.arrive().restStopsAdded)
    }

    @Test
    fun `일부만 성공하면 성공한 것만으로 Running 하고 라벨에 반영한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.candidate()
        h.reachDrowsyProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.CoolDown(), ok = true) }
        h.send { ActionResult(it, Action.OpenWindow(), ok = false, detail = "PERMISSION") }
        h.send { ActionResult(it, Action.AddRestStop(stop), ok = true) }
        val res = h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        assertEquals(WakeEvent.Running(TriggerKind.DROWSY, 30, "바깥 공기, 찬 바람 유지 중"), res.single())
    }

    @Test
    fun `실행 결과가 30초 안에 다 안 오면 못 받은 건 실패로 보고 원복, 원복 타임아웃에도 실패 문장`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        h.run(29)
        assertEquals(1, h.count<WakeEvent.Execute>())
        val got = h.run(1)
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)), got.single())
        // 원복 결과도 안 오면 20초 뒤 실패 문장으로 Done(원복 문장이 아니라)
        h.run(19)
        assertEquals(0, h.count<WakeEvent.Done>())
        val done = h.run(1)
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "지금은 차를 바꿀 수 없어요. 조금 뒤에 다시 볼게요."), done.single())
    }

    @Test
    fun `거부하면 Done 과 함께 쿨다운 동안 다시 제안하지 않는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        val no = h.send { UserAnswer(it, false) }
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 20초 뒤에 다시 볼게요."), no.single())

        // 쿨다운 20초(데모) 동안 조용
        h.run(19)
        assertEquals(1, h.count<WakeEvent.Propose>())
        // 쿨다운이 끝나면 내기순환이 여전히 켜져 있으니 다시 묻는다
        h.run(2)
        assertEquals(2, h.count<WakeEvent.Propose>())
    }

    @Test
    fun `demoFast 가 아니면 거절 문장은 분 단위`() {
        val h = Harness(demo.copy(demoFast = false, staleAirMinutes = 1))
        h.startDriving()
        h.startRecirc()
        h.run(60)
        val no = h.send { UserAnswer(it, false) }
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "알겠어요. 20분 뒤에 다시 볼게요."), no.single())
    }

    @Test
    fun `동의 대기는 SpeechFinished 부터 8초를 센다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.run(5) // TTS 가 5초 걸렸다
        h.speechDone()
        h.run(7)
        assertEquals(0, h.count<WakeEvent.ProposalExpired>())
        val got = h.run(1)
        assertEquals(WakeEvent.ProposalExpired("다음에 다시 물을게요."), got.single())
        // 만료 뒤 동의가 와도 아무 일 없음
        assertTrue(h.send { UserAnswer(it, true) }.isEmpty())
    }

    @Test
    fun `SpeechFinished 가 안 오면 짧은 문장은 Propose 뒤 8+6초를 상한으로 만료한다`() {
        val h = Harness(demo)
        h.startDriving()
        val p = h.reachStaleProposal()
        assertEquals(6, WakeEngine.speechGraceSec(p.text)) // 32자 → 최소 6초
        h.run(13)
        assertEquals(0, h.count<WakeEvent.ProposalExpired>())
        h.run(1)
        assertEquals(1, h.count<WakeEvent.ProposalExpired>())
    }

    @Test
    fun `TTS 여유는 문장 길이에 비례한다(글자당 0,2초)`() {
        assertEquals(6, WakeEngine.speechGraceSec("짧아요"))
        assertEquals(10, WakeEngine.speechGraceSec("가".repeat(50)))
        assertEquals(14, WakeEngine.speechGraceSec("가".repeat(74)))

        val h = Harness(demo)
        h.startDriving()
        h.candidate()
        val p = h.reachDrowsyProposal() // 52자 → 10초
        assertEquals(52, p.text.length)
        assertEquals(10, WakeEngine.speechGraceSec(p.text))
        h.run(17)
        assertEquals(0, h.count<WakeEvent.ProposalExpired>())
        h.run(1)
        assertEquals(1, h.count<WakeEvent.ProposalExpired>())
    }

    @Test
    fun `Propose 직후 0,5초 안에 온 SpeechFinished 는 앞 문장 잔재로 무시한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.engine.accept(SpeechFinished(h.now + 300)) // 제안 0.3초 뒤: 앞 문장의 onDone
        h.run(8) // 인정됐다면 여기서 만료됐을 것
        assertEquals(0, h.count<WakeEvent.ProposalExpired>())
        h.run(6) // Propose + 8 + 6
        assertEquals(1, h.count<WakeEvent.ProposalExpired>())

        // 0.5초 뒤 것은 인정한다
        val h2 = Harness(demo)
        h2.startDriving()
        h2.reachStaleProposal()
        h2.engine.accept(SpeechFinished(h2.now + 500))
        h2.run(8) // SpeechFinished + 7.5초
        assertEquals(0, h2.count<WakeEvent.ProposalExpired>())
        h2.run(1)
        assertEquals(1, h2.count<WakeEvent.ProposalExpired>())
    }

    @Test
    fun `만료 뒤 늦게 온 SpeechFinished 는 다음 제안에 들어가지 않는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.run(14) // 만료
        assertEquals(1, h.count<WakeEvent.ProposalExpired>())
        assertTrue(h.speechDone().isEmpty()) // IDLE: 버린다
        // 쿨다운 20초 뒤 두 번째 제안. 앞의 SpeechFinished 가 섞였다면 8초 뒤에 만료됐을 것
        h.run(20)
        assertEquals(2, h.count<WakeEvent.Propose>())
        h.run(13)
        assertEquals(1, h.count<WakeEvent.ProposalExpired>())
        h.run(1)
        assertEquals(2, h.count<WakeEvent.ProposalExpired>())
    }

    @Test
    fun `동의 대기 중 내기순환이 꺼지면 환기 제안을 조용히 닫는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        val got = h.send { RecirculationChanged(it, false) }
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, null), got.single())
        h.run(20)
        assertEquals(0, h.count<WakeEvent.ProposalExpired>())
        assertEquals(0, h.arrive().declined)
    }

    @Test
    fun `UserAnswer 는 첫 답만 유효하다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        assertTrue(h.send { UserAnswer(it, false) }.isEmpty())
        // 여전히 실행 중: 결과가 오면 Running
        assertIs<WakeEvent.Running>(h.send { ActionResult(it, Action.Ventilate(), ok = true) }.single())

        val h2 = Harness(demo)
        h2.startDriving()
        h2.reachStaleProposal()
        h2.send { UserAnswer(it, false) }
        assertTrue(h2.send { UserAnswer(it, true) }.isEmpty())
        assertEquals(0, h2.count<WakeEvent.Execute>())
    }

    // ---------- DROWSY ----------

    @Test
    fun `DRIVER_ATTENTION 경고등이 켜지면 즉시 졸음 제안, 쉼터 후보가 있으면 경유지 포함`() {
        val h = Harness(demo)
        h.startDriving()
        h.candidate()
        val p = h.reachDrowsyProposal()
        // 순서: 환기 → 경유지 → 찬 바람 → 창문(창문은 막힐 수 있어 맨 뒤)
        assertEquals(
            listOf(Action.Ventilate(), Action.AddRestStop(stop), Action.CoolDown(), Action.OpenWindow()),
            p.actions,
        )
        assertEquals("졸음 경고가 떴어요. 찬 바람과 창문 조금, 12km 앞 경부선 망향졸음쉼터 경유까지 할까요?", p.text)
        assertEquals("DROWSY", h.engine.lastProposalVariant)
    }

    @Test
    fun `쉼터 후보가 없으면 AddRestStop 을 빼고 문장도 바꾼다`() {
        val h = Harness(demo)
        h.startDriving()
        h.candidate(null)
        val p = h.reachDrowsyProposal()
        assertFalse(p.actions.any { it is Action.AddRestStop })
        assertEquals("졸음 경고가 떴어요. 찬 바람 넣고 창문 조금 열까요?", p.text)
    }

    @Test
    fun `비가 오면 졸음 제안에서 OpenWindow 를 빼고 비 문장을 쓴다`() {
        val h = Harness(demo)
        h.startDriving()
        h.candidate()
        h.send { WiperChanged(it, true) }
        val p = h.reachDrowsyProposal()
        assertFalse(p.actions.any { it is Action.OpenWindow })
        assertTrue(p.actions.contains(Action.CoolDown()))
        assertTrue(p.actions.contains(Action.AddRestStop(stop)))
        assertEquals("졸음 경고가 떴어요. 비가 와서 창문은 두고, 찬 바람과 12km 앞 경부선 망향졸음쉼터 경유까지 할까요?", p.text)

        // 비 올 때 Running 라벨
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.CoolDown(), ok = true) }
        h.send { ActionResult(it, Action.AddRestStop(stop), ok = true) }
        val res = h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        assertEquals(WakeEvent.Running(TriggerKind.DROWSY, 30, "찬 바람 넣는 중"), res.single())
    }

    @Test
    fun `비가 아니면 Running 라벨은 찬 바람과 창문`() {
        val h = Harness(demo)
        h.startDriving()
        h.candidate(null)
        h.reachDrowsyProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.CoolDown(), ok = true) }
        h.send { ActionResult(it, Action.OpenWindow(), ok = true) }
        val res = h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        assertEquals(WakeEvent.Running(TriggerKind.DROWSY, 30, "찬 바람과 창문 열어 둔 상태"), res.single())
    }

    @Test
    fun `야간 장거리면 환기 임계가 짧아지고 첫 제안에만 밤길 문장이 붙는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.send { LowBeamChanged(it, true) }
        h.run(60) // 장거리
        h.startRecirc()
        h.run(19)
        assertEquals(0, h.count<WakeEvent.Propose>())
        h.run(1)
        val p = h.last<WakeEvent.Propose>()!!
        assertEquals("오래 달려서 조금 일찍 물어요. 내기순환으로 20분째예요. 20초만 바깥 공기로 바꿀까요?", p.text)
        h.send { UserAnswer(it, false) }
        h.run(21)
        val p2 = h.last<WakeEvent.Propose>()!!
        assertEquals(2, h.count<WakeEvent.Propose>())
        assertEquals("내기순환으로 20분째예요. 20초만 바깥 공기로 바꿀까요?", p2.text)
    }

    @Test
    fun `DROWSY 는 세션당 최대 3회`() {
        val h = Harness(demo)
        h.startDriving()
        repeat(5) {
            h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
            h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
            h.send { UserAnswer(it, false) }
            h.run(25) // 거부 쿨다운 20초 + 발화 쿨다운 넘김
        }
        assertEquals(3, h.events.count { it is WakeEvent.Propose && it.kind == TriggerKind.DROWSY })
    }

    @Test
    fun `DROWSY 에 Ventilate 가 들어 있었으면 끝날 때 내기순환 카운터도 리셋`() {
        val h = Harness(demo)
        h.startDriving()
        h.startRecirc()
        h.run(10)
        h.candidate(null)
        h.reachDrowsyProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.CoolDown(), ok = true) }
        h.send { ActionResult(it, Action.OpenWindow(), ok = true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(30)
        h.send { ActionResult(it, Action.Revert, ok = true) } // 내기순환 켠 지 40초인 시점
        assertEquals(1, h.count<WakeEvent.Done>())
        // 리셋이 없었다면 여기서 바로 환기 제안이 나왔을 것
        h.run(39)
        assertEquals(1, h.count<WakeEvent.Propose>())
        h.run(1)
        assertEquals(2, h.count<WakeEvent.Propose>())
        assertEquals(TriggerKind.STALE_AIR, h.last<WakeEvent.Propose>()!!.kind)
    }

    // ---------- 게이트, 큐 ----------

    @Test
    fun `깜빡이 ON 중 트리거는 Deferred, OFF 되면 다음 tick 에 제안`() {
        val h = Harness(demo)
        h.startDriving()
        h.send { TurnSignalChanged(it, TurnSignalState.LEFT) }
        val got = h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        assertEquals(WakeEvent.Deferred(TriggerKind.DROWSY, "turn_signal"), got.single())

        // 막힌 동안 Deferred 를 반복해서 내지 않는다
        h.run(3)
        assertEquals(1, h.count<WakeEvent.Deferred>())
        assertEquals(0, h.count<WakeEvent.Propose>())

        h.send { TurnSignalChanged(it, TurnSignalState.OFF) }
        h.run(1)
        val p = h.last<WakeEvent.Propose>()
        assertNotNull(p)
        assertEquals(TriggerKind.DROWSY, p.kind)
    }

    @Test
    fun `LKA 는 점멸일 때만 발화를 막고 상시 ON 은 무시한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.send { TelltaleChanged(it, Telltale.LKA, on = true) } // 작동 대기 표시
        h.reachDrowsyProposal() // 막히지 않는다
        h.send { UserAnswer(it, false) }
        h.run(25)

        h.send { TelltaleChanged(it, Telltale.LKA, on = true, blink = true) }
        val got = h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        assertEquals(WakeEvent.Deferred(TriggerKind.DROWSY, "warning:LKA"), got.single())
        h.send { TelltaleChanged(it, Telltale.LKA, on = true, blink = false) }
        h.run(1)
        assertEquals(TriggerKind.DROWSY, h.last<WakeEvent.Propose>()!!.kind)
        assertEquals(2, h.count<WakeEvent.Propose>())
    }

    @Test
    fun `실행 중 들어온 트리거는 큐에 쌓이고 DROWSY 가 STALE_AIR 보다 먼저 나간다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }

        // 환기 실행 중: 내기순환은 그대로(카운터가 돌지만 환기 중이라 새 제안 없음), 졸음 경고등 켜짐
        h.run(5)
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        h.run(10)
        assertEquals(1, h.count<WakeEvent.Propose>())

        // 환기 종료 → Revert → Done
        h.run(5)
        val done = h.send { ActionResult(it, Action.Revert, ok = true) }.filterIsInstance<WakeEvent.Done>().single()
        assertEquals(1, h.count<WakeEvent.Done>())

        // Done 문장을 읽을 시간이 지난 뒤 큐에서 DROWSY 가 먼저 나간다(Done 과 같은 배치에는 안 나온다)
        val holdSec = ((WakeEngine.speechHoldMs(done.text!!) + 999) / 1000).toInt()
        assertTrue(holdSec >= 2)
        assertEquals(1, h.count<WakeEvent.Propose>())
        assertEquals(listOf<WakeEvent>(WakeEvent.Deferred(TriggerKind.DROWSY, "speaking")), h.run(holdSec - 1))
        h.run(1)
        val p = h.last<WakeEvent.Propose>()
        assertNotNull(p)
        assertEquals(TriggerKind.DROWSY, p.kind)
        assertEquals(2, h.count<WakeEvent.Propose>())
    }

    @Test
    fun `REVERT 타임아웃으로 끝나는 tick 에도 Done 과 Propose 를 같이 내지 않는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(5)
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) } // 큐에서 대기
        h.run(15) // Execute(Revert)
        h.run(19)
        val got = h.run(1) // REVERT 타임아웃 20초
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, "원복을 확인하지 못했어요.")), got)
        // 14자 문장이라 2.8초 동안은 읽는 중으로 본다
        assertTrue(h.run(2).none { it is WakeEvent.Propose })
        val next = h.run(1)
        assertEquals(TriggerKind.DROWSY, next.filterIsInstance<WakeEvent.Propose>().single().kind)
    }

    @Test
    fun `동의 대기 중 들어온 다른 트리거도 큐에서 기다린다`() {
        val h = Harness(demo)
        h.startDriving()
        h.startRecirc()
        h.run(40) // STALE_AIR 제안
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        assertEquals(1, h.count<WakeEvent.Propose>())
        h.send { UserAnswer(it, false) } // 환기 거절
        // 발화 쿨다운 20초 때문에 Deferred 후 제안
        h.run(1)
        assertEquals(WakeEvent.Deferred(TriggerKind.DROWSY, "cooldown"), h.last<WakeEvent.Deferred>())
        h.run(20)
        assertEquals(TriggerKind.DROWSY, h.last<WakeEvent.Propose>()?.kind)
    }

    @Test
    fun `같은 순간에 환기 만료와 졸음 텔테일이 겹치면 DROWSY 한 문장만 나간다`() {
        val h = Harness(demo)
        h.startDriving()
        h.startRecirc()
        h.run(39)
        h.now += 1_000 // 40초가 되는 바로 그 순간에 텔테일이 들어온다(tick 보다 먼저)
        val got = h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        val p = got.filterIsInstance<WakeEvent.Propose>().single()
        assertEquals(TriggerKind.DROWSY, p.kind)
        assertEquals(1, h.count<WakeEvent.Propose>())
        // 환기 제안은 큐에서 기다렸다가 졸음 제안이 끝난 뒤 나간다
        h.send { UserAnswer(it, false) }
        h.run(21)
        assertEquals(TriggerKind.STALE_AIR, h.last<WakeEvent.Propose>()!!.kind)
        assertEquals(2, h.count<WakeEvent.Propose>())
    }

    // ---------- 자동 개입 OFF, 설정 교체 ----------

    @Test
    fun `자동 개입 OFF 면 대기 중 제안을 조용히 닫고 트리거를 막으며 거절로 세지 않는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        val off = h.engine.setEnabled(false)
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, null)), off)
        assertFalse(h.engine.enabled)

        // OFF 동안엔 텔테일도 무시(Deferred 도 없음)
        assertTrue(h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.isEmpty())
        h.run(30)
        assertEquals(1, h.count<WakeEvent.Propose>())
        assertEquals(0, h.count<WakeEvent.ProposalExpired>())
        assertEquals(0, h.count<WakeEvent.Deferred>())

        // 다시 ON: 내기순환이 계속 켜져 있었으니 환기 제안이 다시 나온다
        assertTrue(h.engine.setEnabled(true).isEmpty())
        h.run(1)
        assertEquals(2, h.count<WakeEvent.Propose>())
        h.send { UserAnswer(it, false) }
        val card = h.arrive()
        assertEquals(1, card.declined)
        assertEquals(0, card.drowsySignals)
    }

    @Test
    fun `updateConfig 는 엔진 재생성 없이 설정만 바꾸고 차량 상태는 유지한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.send { WiperChanged(it, true) }
        h.startRecirc()
        h.run(20)
        h.engine.updateConfig(demo.copy(staleAirMinutes = 25))
        h.run(4)
        assertEquals(0, h.count<WakeEvent.Propose>())
        h.run(1) // 내기순환 25초
        assertEquals(1, h.count<WakeEvent.Propose>())
        assertEquals("내기순환으로 25분째예요. 20초만 바깥 공기로 바꿀까요?", h.last<WakeEvent.Propose>()!!.text)
        h.send { UserAnswer(it, false) }
        h.send { RecirculationChanged(it, false) }
        h.run(25)
        // 와이퍼 상태도 살아 있다
        val p = h.reachDrowsyProposal()
        assertFalse(p.actions.any { it is Action.OpenWindow })
        assertEquals(TripState.DRIVING, h.engine.tripState)
    }

    // ---------- 쉼 모드, 쉼터 ----------

    /** 졸음 개입으로 쉼터를 경유지에 넣고 원복까지 끝낸다 */
    private fun Harness.addWaypointViaDrowsy() {
        candidate()
        reachDrowsyProposal()
        send { UserAnswer(it, true) }
        send { ActionResult(it, Action.CoolDown(), ok = true) }
        send { ActionResult(it, Action.OpenWindow(), ok = true) }
        send { ActionResult(it, Action.AddRestStop(stop), ok = true) }
        send { ActionResult(it, Action.Ventilate(), ok = true) }
        run(30)
        send { ActionResult(it, Action.Revert, ok = true) }
    }

    @Test
    fun `경유지 쉼터 안에서 P 면 쉼 모드, 매 tick RestTick, 목표를 채우고 D 면 푹 쉬셨어요`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        h.startRecirc()
        h.send { RestStopProximity(it, within = true) }
        val got = h.send { GearChanged(it, Gear.PARK) }
        // 내기순환으로 쉬게 되니 쉬는 동안 바깥 공기로 둘지 묻는다. 여기서는 거절한다(아무 일도 없다)
        assertEquals(listOf(WakeEvent.RestStarted("망향졸음쉼터", 15), WakeEvent.Propose(TriggerKind.STALE_AIR, "쉬는 동안 바깥 공기로 둘까요?", listOf(Action.Ventilate(fanDelta = 0)), 20)), got)
        assertEquals(listOf<WakeEvent>(WakeEvent.Done(TriggerKind.STALE_AIR, null)), h.send { UserAnswer(it, false) })
        assertEquals(TripState.DRIVING, h.engine.tripState)
        assertTrue(h.engine.isResting)

        val ticks = h.run(15).filterIsInstance<WakeEvent.RestTick>().map { it.remainingSec }
        assertEquals((14 downTo 0).toList(), ticks)
        // 0 밑으로 내려가지 않는다. 쉬는 동안 제안도 없다
        val more = h.run(3)
        assertEquals(listOf<WakeEvent>(WakeEvent.RestTick(0), WakeEvent.RestTick(0), WakeEvent.RestTick(0)), more)
        assertEquals(2, h.count<WakeEvent.Propose>()) // 졸음 제안과 쉼 진입 질문뿐

        val ended = h.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(listOf<WakeEvent>(WakeEvent.RestEnded(18, "18분 푹 쉬셨어요. 조심히 가요")), ended)
        assertFalse(h.engine.isResting)

        // 쉰 뒤 내기순환 카운터는 0 부터(쉬는 동안 18초 + 그 전 0초 → 리셋 없으면 22초 뒤 제안)
        h.run(39)
        assertEquals(2, h.count<WakeEvent.Propose>())
        h.run(1)
        assertEquals(3, h.count<WakeEvent.Propose>())

        h.send { RestStopProximity(it, within = false) }
        val card = h.arrive()
        assertEquals(1, card.restStopsVisited)
        assertEquals(18, card.restMinutes)
        assertEquals(1, card.restStopsAdded)
        assertEquals(0, card.restStopsPassed)
        assertEquals("오늘 환기 1번, 쉼터에서 18분 쉬었어요.", card.headline)
        assertTrue(card.lines.any { it.contains("망향졸음쉼터에서 18분 휴식") })
    }

    @Test
    fun `목표보다 짧게 쉬면 다음 쉼터 문장, GPS 가 흔들려도 쉼 모드는 기어로만 나간다`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        h.send { RestStopProximity(it, within = true) }
        h.send { GearChanged(it, Gear.PARK) }
        h.run(3)
        h.send { RestStopProximity(it, within = false) } // GPS 흔들림
        h.send { GearChanged(it, Gear.NEUTRAL) }
        h.send { TelltaleChanged(it, Telltale.PARKING_BRAKE, true) }
        val ticks = h.run(4)
        assertTrue(ticks.all { it is WakeEvent.RestTick })
        assertTrue(h.engine.isResting)
        assertEquals(TripState.DRIVING, h.engine.tripState)

        val ended = h.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(listOf<WakeEvent>(WakeEvent.RestEnded(7, "7분 쉬셨어요. 다음 쉼터에서 더 쉬어도 좋아요")), ended)
        val card = h.arrive()
        assertEquals(1, card.restStopsVisited)
        assertEquals(7, card.restMinutes)
    }

    @Test
    fun `쉼터 밖에서 P 는 그대로 도착 카드`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        h.send { RestStopProximity(it, within = true) }
        h.send { RestStopProximity(it, within = false) }
        val ended = h.send { GearChanged(it, Gear.PARK) }
        assertEquals(0, ended.count { it is WakeEvent.RestStarted })
        assertEquals(1, ended.count { it is WakeEvent.ArrivalCard })
        assertEquals(TripState.ENDED, h.engine.tripState)
    }

    @Test
    fun `쉼 모드 진입 시 진행 중인 개입은 Revert 로 정리한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        h.run(1)
        h.reachStaleProposalAfterRecirc()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(3)
        h.send { RestStopProximity(it, within = true) }
        val got = h.send { GearChanged(it, Gear.PARK) }
        assertEquals(
            listOf(
                WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)),
                WakeEvent.Done(TriggerKind.STALE_AIR, null),
                WakeEvent.RestStarted("망향졸음쉼터", 15),
                // 원복되면 내기순환으로 돌아가니 쉬는 동안 바깥 공기로 둘지 묻는다
                WakeEvent.Propose(TriggerKind.STALE_AIR, "쉬는 동안 바깥 공기로 둘까요?", listOf(Action.Ventilate(fanDelta = 0)), 20),
            ),
            got,
        )
    }

    private fun Harness.reachStaleProposalAfterRecirc() {
        startRecirc()
        run(40)
        assertEquals(TriggerKind.STALE_AIR, last<WakeEvent.Propose>()!!.kind)
    }

    @Test
    fun `경유지 쉼터를 지나치면 한 번만 센다`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        h.send { RestStopProximity(it, within = false, passed = true) }
        h.send { RestStopProximity(it, within = false, passed = true) }
        val card = h.arrive()
        assertEquals(1, card.restStopsPassed)
        assertEquals(1, card.restStopsAdded)
        assertEquals(0, card.restStopsVisited)
    }

    @Test
    fun `경유지를 넣은 적이 없으면 지나침도 세지 않는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.send { RestStopProximity(it, within = false, passed = true) }
        assertEquals(0, h.arrive().restStopsPassed)
    }

    // ---------- 도착 카드 ----------

    @Test
    fun `도착 카드에 환기, 졸음, 쉼터, 거절 횟수가 담긴다`() {
        val h = Harness(demo)
        h.startDriving()
        // 환기 1회 완료
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(20)
        h.send { ActionResult(it, Action.Revert, ok = true) }
        // 졸음 1회: 쉼터 추가 성공(안의 Ventilate 도 환기로 센다). 원복 문장을 읽을 시간이 지난 뒤에 묻는다
        h.run(6)
        h.addWaypointViaDrowsy()
        // 환기 1회 거절(졸음 개입의 Ventilate 원복 시점부터 40초)
        h.run(39)
        assertEquals(2, h.count<WakeEvent.Propose>())
        h.run(1)
        assertEquals(3, h.count<WakeEvent.Propose>())
        h.send { UserAnswer(it, false) }

        val card = h.arrive()
        assertEquals(2, card.ventilations)
        assertEquals(1, card.drowsySignals)
        assertEquals(1, card.restStopsAdded)
        assertEquals(1, card.declined)
        assertEquals(2, card.freshAirMinutes) // 20초 + 30초, 구간마다 최소 1분
        assertEquals("오늘 환기 2번, 졸음 경고 1번이 있었어요.", card.headline)
        // 로그는 마지막 4줄. 공기 줄은 없다
        assertEquals(4, card.lines.size)
        assertTrue(card.lines.first().endsWith("졸음 경고(운전자 주의 경고)"), card.lines.toString())
        assertTrue(card.lines.none { it.contains("ppm") || it.contains("탑승") })
        assertEquals(2, card.tripMinutes) // 136초
    }

    @Test
    fun `freshAirMinutes 는 환기 성공 구간의 합을 분으로 반올림한다`() {
        val h = Harness(demo.copy(demoFast = false, staleAirMinutes = 1, ventilateSeconds = 150))
        h.startDriving()
        h.startRecirc()
        h.run(60)
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(150)
        h.send { ActionResult(it, Action.Revert, ok = true) }
        val card = h.arrive()
        assertEquals(3, card.freshAirMinutes) // 150초 = 2.5분 → 3
        assertEquals(1, card.ventilations)
    }

    @Test
    fun `개입이 없으면 조용한 도착 카드`() {
        val h = Harness(demo)
        h.startDriving()
        h.run(10)
        val card = h.arrive()
        assertEquals("오늘은 개입 없이 무사히 도착했어요.", card.headline)
        assertTrue(card.lines.isEmpty())
        assertEquals(0, card.freshAirMinutes)
        assertNull(h.last<WakeEvent.Propose>())
    }

    @Test
    fun `실행 중에 기어 P 가 들어오면 Revert 를 내고 카드를 낸다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(3)
        val ended = h.send { GearChanged(it, Gear.PARK) }
        assertTrue(ended.contains(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert))))
        assertTrue(ended.contains(WakeEvent.Done(TriggerKind.STALE_AIR, null)))
        assertEquals(1, ended.count { it is WakeEvent.ArrivalCard })
    }

    @Test
    fun `EXECUTING 중 기어 P 는 Execute(Revert) 만 내고 끝낸다(앱이 남은 액션 취소)`() {
        val h = Harness(demo)
        h.startDriving()
        h.candidate()
        h.reachDrowsyProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.CoolDown(), ok = true) } // 나머지 결과는 아직
        val ended = h.send { GearChanged(it, Gear.PARK) }
        assertEquals(
            listOf(
                WakeEvent.Execute(TriggerKind.DROWSY, listOf(Action.Revert)),
                WakeEvent.Done(TriggerKind.DROWSY, null),
                WakeEvent.TripStateChanged(TripState.ENDED),
            ),
            ended.take(3),
        )
        assertIs<WakeEvent.ArrivalCard>(ended[3])
        assertEquals(4, ended.size)
        assertEquals(0, h.count<WakeEvent.Running>())
    }

    @Test
    fun `demoFast 가 아니면 분 단위로 읽는다`() {
        val h = Harness(demo.copy(demoFast = false, staleAirMinutes = 2))
        h.startDriving()
        h.startRecirc()
        h.run(119)
        assertEquals(0, h.count<WakeEvent.Propose>())
        h.run(1)
        assertEquals(1, h.count<WakeEvent.Propose>())
        assertEquals("내기순환으로 2분째예요. 20초만 바깥 공기로 바꿀까요?", h.last<WakeEvent.Propose>()!!.text)
    }

    // ---------- 시계 ----------

    @Test
    fun `시계가 뒤로 가도 터지지 않고 상태를 유지한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.startRecirc()
        h.run(10)
        val p = h.reachDrowsyProposal()
        // 과거 시각의 신호와 tick
        h.engine.accept(SpeechFinished(h.now - 5_000)) // 제안 전 시각: 앞 문장 잔재로 버린다
        h.engine.tick(h.now - 3_000)
        h.engine.accept(RecirculationChanged(h.now - 100_000, true))
        h.engine.tick(h.now - 200_000)
        assertEquals(TripState.DRIVING, h.engine.tripState)
        // 과거 시각으로 만료되지 않았고, Propose 기준 8초 + 문장 여유 뒤에 만료된다
        assertEquals(0, h.count<WakeEvent.ProposalExpired>())
        val grace = WakeEngine.speechGraceSec(p.text)
        h.run(8 + grace - 1)
        assertEquals(0, h.count<WakeEvent.ProposalExpired>())
        h.run(1)
        assertEquals(1, h.count<WakeEvent.ProposalExpired>())
        val card = h.arrive()
        assertTrue(card.tripMinutes >= 0)
    }

    // ---------- 3차: 쉼터/경유지 ----------

    @Test
    fun `경유지 없이 within 만 오고 P 면 쉼 모드가 아니라 도착 카드`() {
        val h = Harness(demo)
        h.startDriving()
        h.candidate() // 후보만 있고 경유지는 없다
        h.send { RestStopProximity(it, within = true) }
        val ended = h.send { GearChanged(it, Gear.PARK) }
        assertEquals(0, ended.count { it is WakeEvent.RestStarted })
        assertEquals(1, ended.count { it is WakeEvent.ArrivalCard })
        assertEquals(TripState.ENDED, h.engine.tripState)
        assertFalse(h.engine.isResting)
    }

    @Test
    fun `쉬고 나서 D 뒤 within=false 없이 바로 P 면 도착 카드이고 쉼은 한 번만 센다`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        h.send { RestStopProximity(it, within = true) }
        h.send { GearChanged(it, Gear.PARK) }
        h.run(5)
        h.send { GearChanged(it, Gear.DRIVE) }
        assertNull(h.engine.currentWaypoint) // 쉰 쉼터는 끝
        // 앱이 within=false 를 못 보냈다. 그래도 다음 P 는 도착
        val ended = h.send { GearChanged(it, Gear.PARK) }
        assertEquals(0, ended.count { it is WakeEvent.RestStarted })
        val card = ended.filterIsInstance<WakeEvent.ArrivalCard>().single().card
        assertEquals(1, card.restStopsVisited)
        assertEquals(TripState.ENDED, h.engine.tripState)
    }

    @Test
    fun `쉰 뒤 같은 쉼터 within 이 또 와도 새 AddRestStop 전에는 쉼 모드로 들어가지 않는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        h.send { RestStopProximity(it, within = true) }
        h.send { GearChanged(it, Gear.PARK) }
        h.run(2)
        h.send { GearChanged(it, Gear.DRIVE) }
        h.send { RestStopProximity(it, within = true) } // 경유지가 없으니 무시
        h.send { RestStopProximity(it, within = false, passed = true) } // 지나침도 무시
        val ended = h.send { GearChanged(it, Gear.PARK) }
        val card = ended.filterIsInstance<WakeEvent.ArrivalCard>().single().card
        assertEquals(1, card.restStopsVisited)
        assertEquals(0, card.restStopsPassed)
    }

    @Test
    fun `이미 경유지인 쉼터가 다시 후보로 오면 AddRestStop 을 빼고 문장도 바꾼다`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        assertEquals(stop, h.engine.currentWaypoint)
        h.run(21) // 발화 쿨다운
        h.candidate(stop.copy(distanceKm = 5.0)) // 같은 이름, 거리만 줄었다
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        val p = h.reachDrowsyProposal()
        assertEquals(listOf(Action.Ventilate(), Action.CoolDown(), Action.OpenWindow()), p.actions)
        assertEquals("졸음 경고가 떴어요. 망향졸음쉼터는 경유지에 있어요. 찬 바람 넣고 창문 조금 열까요?", p.text)
        h.send { UserAnswer(it, false) }
        assertEquals(1, h.arrive().restStopsAdded)
    }

    @Test
    fun `이름이 달라도 좌표가 50m 안이면 같은 쉼터로 본다`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        h.run(21)
        // 약 22m 북쪽, 이름 표기만 다름
        h.candidate(stop.copy(name = "망향 졸음쉼터", lat = stop.lat + 0.0002, distanceKm = 3.0))
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        val p = h.reachDrowsyProposal()
        assertFalse(p.actions.any { it is Action.AddRestStop })
        // 문장에는 경유지로 넣어 둔 이름을 쓴다
        assertEquals("졸음 경고가 떴어요. 망향졸음쉼터는 경유지에 있어요. 찬 바람 넣고 창문 조금 열까요?", p.text)

        // 200m 넘게 떨어진 다른 쉼터면 경유지로 넣는다
        h.send { UserAnswer(it, false) }
        h.run(25)
        val other = stop.copy(name = "다른졸음쉼터", lat = stop.lat + 0.003, distanceKm = 30.0)
        h.candidate(other)
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        val p2 = h.reachDrowsyProposal()
        assertTrue(p2.actions.contains(Action.AddRestStop(other)))
    }

    @Test
    fun `쉼 모드 중 N 과 주차 브레이크는 무시하고 R 은 D 처럼 쉼을 끝낸다`() {
        val h = Harness(demo)
        h.startDriving()
        h.addWaypointViaDrowsy()
        h.send { RestStopProximity(it, within = true) }
        h.send { GearChanged(it, Gear.PARK) }
        h.send { GearChanged(it, Gear.NEUTRAL) }
        h.send { TelltaleChanged(it, Telltale.PARKING_BRAKE, true) }
        h.send { GearChanged(it, Gear.PARK) }
        val ticks = h.run(6)
        assertTrue(ticks.all { it is WakeEvent.RestTick })
        assertTrue(h.engine.isResting)
        assertEquals(TripState.DRIVING, h.engine.tripState)

        val ended = h.send { GearChanged(it, Gear.REVERSE) }
        assertIs<WakeEvent.RestEnded>(ended.single())
        assertFalse(h.engine.isResting)
        assertEquals(TripState.DRIVING, h.engine.tripState)
        // 후진 뒤 P 는 도착
        assertEquals(1, h.send { GearChanged(it, Gear.PARK) }.count { it is WakeEvent.ArrivalCard })
    }

    @Test
    fun `세션 표시 시간에서 쉼 시간을 뺀다`() {
        val h = Harness(demo)
        h.startDriving()
        h.run(50)
        h.addWaypointViaDrowsy() // 약 30초
        h.send { RestStopProximity(it, within = true) }
        h.send { GearChanged(it, Gear.PARK) }
        h.run(70) // 70초 쉼
        h.send { GearChanged(it, Gear.DRIVE) }
        h.run(10)
        val card = h.arrive()
        // 전체 160초 중 쉼 70초 → 주행 90초 = 1분 (쉼 포함이면 2분)
        assertEquals(1, card.tripMinutes)
        assertEquals(70, card.restMinutes)
    }

    @Test
    fun `야간 장거리 판정도 쉼 시간을 뺀 주행 시간으로 한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.send { LowBeamChanged(it, true) }
        h.addWaypointViaDrowsy() // 약 30초 주행
        h.send { RestStopProximity(it, within = true) }
        h.send { GearChanged(it, Gear.PARK) }
        h.run(40) // 쉼 40초. 벽시계로는 70초가 지나 장거리(60초)로 보일 수 있다
        h.send { GearChanged(it, Gear.DRIVE) }
        h.startRecirc()
        h.run(20) // 주행 50초: 아직 장거리 아님 → 야간 임계 20초가 아니라 주간 40초
        assertEquals(1, h.count<WakeEvent.Propose>())
        h.run(20)
        assertEquals(2, h.count<WakeEvent.Propose>())
        assertEquals(TriggerKind.STALE_AIR, h.last<WakeEvent.Propose>()!!.kind)
    }

    // ---------- 3차: 문장, 신호 집계 ----------

    @Test
    fun `환기 유지 문구는 ventilateSeconds 를 따른다`() {
        val h = Harness(demo.copy(ventilateSeconds = 120))
        h.startDriving()
        val p = h.reachStaleProposal()
        assertEquals("내기순환으로 40분째예요. 2분만 바깥 공기로 바꿀까요?", p.text)

        val h2 = Harness(demo.copy(ventilateSeconds = 90))
        h2.startDriving()
        assertEquals("내기순환으로 40분째예요. 90초만 바깥 공기로 바꿀까요?", h2.reachStaleProposal().text)
    }

    @Test
    fun `쿨다운 중에 들어온 DRIVER_ATTENTION 도 졸음 경고로 센다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachDrowsyProposal()
        h.send { UserAnswer(it, false) } // 쿨다운 20초
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        h.run(5)
        assertTrue(h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.isEmpty()) // 제안은 없다
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, false) }
        h.run(5)
        h.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        assertEquals(1, h.count<WakeEvent.Propose>())
        val card = h.arrive()
        assertEquals(3, card.drowsySignals)
        assertEquals(1, card.declined)
    }

    // ---------- 3차: 설정 교체, 자동 개입 OFF ----------

    @Test
    fun `updateConfig 는 진행 중인 동의 대기 만료 시각을 바꾸지 않는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.run(2)
        h.speechDone()
        h.engine.updateConfig(demo.copy(answerTimeoutSec = 2))
        h.run(7) // 새 값이면 2초 뒤 만료됐을 것
        assertEquals(0, h.count<WakeEvent.ProposalExpired>())
        h.run(1)
        assertEquals(1, h.count<WakeEvent.ProposalExpired>())
    }

    @Test
    fun `updateConfig 는 진행 중인 Running 종료와 쉼 목표를 바꾸지 않는다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(5)
        h.engine.updateConfig(demo.copy(ventilateSeconds = 5))
        h.run(14)
        assertEquals(1, h.count<WakeEvent.Execute>()) // 아직 Revert 없음
        h.run(1)
        assertEquals(2, h.count<WakeEvent.Execute>()) // 원래 20초에 Revert
        h.send { ActionResult(it, Action.Revert, ok = true) }

        h.run(6) // 원복 문장을 읽을 시간
        h.addWaypointViaDrowsy()
        h.send { RestStopProximity(it, within = true) }
        h.send { GearChanged(it, Gear.PARK) }
        h.send { UserAnswer(it, false) } // 쉬는 동안 바깥 공기로 둘지 묻는 질문은 거절
        h.run(3)
        h.engine.updateConfig(demo.copy(restMinutes = 5))
        val ticks = h.run(3).filterIsInstance<WakeEvent.RestTick>().map { it.remainingSec }
        assertEquals(listOf(11, 10, 9), ticks) // 목표 15초 그대로
        h.run(9)
        val ended = h.send { GearChanged(it, Gear.DRIVE) }
        assertEquals(WakeEvent.RestEnded(15, "15분 푹 쉬셨어요. 조심히 가요"), ended.single())
    }

    @Test
    fun `실제 모드에서 데모 모드로 바꿔도 즉시 환기 제안이 나가지 않는다`() {
        val h = Harness(demo.copy(demoFast = false))
        h.startDriving()
        h.startRecirc()
        h.run(300) // 실제 5분: 임계 40분이라 조용
        assertEquals(0, h.count<WakeEvent.Propose>())
        h.engine.updateConfig(demo) // 데모 임계 40초. 리셋이 없었다면 바로 제안
        h.run(39)
        assertEquals(0, h.count<WakeEvent.Propose>())
        h.run(1)
        assertEquals(1, h.count<WakeEvent.Propose>())
    }

    @Test
    fun `자동 개입 OFF 가 Running 중이면 즉시 원복을 시작한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.reachStaleProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        h.run(5)
        val off = h.engine.setEnabled(false)
        assertEquals(listOf<WakeEvent>(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert))), off)
        assertEquals("REVERTING", h.engine.phaseName)
        assertTrue(h.run(3).isEmpty()) // Running 은 더 안 나온다
        val done = h.send { ActionResult(it, Action.Revert, ok = true) }
        assertEquals(WakeEvent.Done(TriggerKind.STALE_AIR, "원래대로 돌렸어요."), done.single())
        h.run(60)
        assertEquals(1, h.count<WakeEvent.Propose>()) // OFF 라 새 제안 없음
    }

    @Test
    fun `자동 개입 OFF 가 결과 대기 중이면 결과가 모인 뒤 바로 원복한다`() {
        val h = Harness(demo)
        h.startDriving()
        h.candidate()
        h.reachDrowsyProposal()
        h.send { UserAnswer(it, true) }
        h.send { ActionResult(it, Action.Ventilate(), ok = true) }
        assertTrue(h.engine.setEnabled(false).isEmpty()) // 아직 결과 대기
        h.send { ActionResult(it, Action.AddRestStop(stop), ok = true) }
        h.send { ActionResult(it, Action.CoolDown(), ok = true) }
        val got = h.send { ActionResult(it, Action.OpenWindow(), ok = true) }
        assertEquals(listOf<WakeEvent>(WakeEvent.Execute(TriggerKind.DROWSY, listOf(Action.Revert))), got)
        assertEquals(0, h.count<WakeEvent.Running>())
        h.send { ActionResult(it, Action.Revert, ok = true) }
        val card = h.arrive()
        assertEquals(0, card.ventilations) // 유지 구간이 없었으니 환기로 세지 않는다
        assertEquals(1, card.restStopsAdded) // 경유지는 남는다
    }

    @Test
    fun `실제 모드 실행 결과 상한은 60초`() {
        val h = Harness(demo.copy(demoFast = false, staleAirMinutes = 1))
        h.startDriving()
        h.startRecirc()
        h.run(60)
        h.send { UserAnswer(it, true) }
        h.run(59)
        assertEquals(1, h.count<WakeEvent.Execute>())
        val got = h.run(1)
        assertEquals(WakeEvent.Execute(TriggerKind.STALE_AIR, listOf(Action.Revert)), got.single())
    }
}

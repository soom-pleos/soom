package ai.kkaeugi.app

import ai.kkaeugi.app.signals.DemoScenario
import ai.kkaeugi.app.signals.SignalBus
import ai.kkaeugi.app.speech.ANNOUNCE_BUTTON_PART
import ai.kkaeugi.app.speech.announceText
import ai.kkaeugi.app.speech.speechText
import ai.kkaeugi.app.ui.AirLineContext
import ai.kkaeugi.app.ui.FRESH_AIR_BUTTON
import ai.kkaeugi.app.ui.PreviewStates
import ai.kkaeugi.app.ui.airStatusLine
import ai.kkaeugi.app.ui.runningBarText
import ai.kkaeugi.app.ui.showFreshAirButton
import ai.kkaeugi.app.vehicle.EtaJudge
import ai.kkaeugi.app.vehicle.OccupantDebouncer
import ai.kkaeugi.app.vehicle.SeatLiveness
import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.ActionResult
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.GearChanged
import ai.kkaeugi.engine.Signal
import ai.kkaeugi.engine.TriggerKind
import ai.kkaeugi.engine.TrustState
import ai.kkaeugi.engine.UserAnswer
import ai.kkaeugi.engine.WakeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.6.1 에서 앱에 더한 순수 로직(4차 리뷰): 예고 문장 치환, 좌석 값 끊김, 도착 예정 단위, 신호 버스, 시연 격리, 홈 문구와 버튼 */
class V61LogicTest {

    // ---------- 예고 문장: 말로 답할 수 없으면 "취소를 누르세요" ----------

    private val announce = "2분만 바깥 공기로 바꿀게요. 싫으면 '아니'라고 하세요."

    @Test fun `말로 답할 수 있으면 예고 문장은 엔진 문장 그대로다`() {
        assertEquals(announce, announceText(announce, voiceAnswer = true))
    }

    @Test fun `음성 인식기가 없거나 마이크 권한이 없으면 취소를 누르라고 읽고 보여 준다`() {
        val text = announceText(announce, voiceAnswer = false)
        assertEquals("2분만 바깥 공기로 바꿀게요. 싫으면 취소를 누르세요.", text)
        assertFalse(text.contains("아니"))
        // 읽는 문장도 같다(괄호가 없어 그대로)
        assertEquals(text, speechText(text))
        // 데모 속도의 "20초만" 문장도 같은 자리만 바뀐다
        assertEquals("20초만 바깥 공기로 바꿀게요. $ANNOUNCE_BUTTON_PART.", announceText("20초만 바깥 공기로 바꿀게요. 싫으면 '아니'라고 하세요.", false))
        // 예고가 아닌 문장은 건드리지 않는다
        assertEquals("2분만 바깥 공기로 바꿀까요?", announceText("2분만 바깥 공기로 바꿀까요?", false))
    }

    @Test fun `프리뷰의 예고 문장은 엔진 문장과 같아서 치환이 실제로 걸린다`() {
        assertEquals(announce, PreviewStates.ANNOUNCE_TEXT)
        assertTrue(announceText(PreviewStates.ANNOUNCE_TEXT, false).endsWith("취소를 누르세요."))
    }

    // ---------- 실행 중 바 ----------

    @Test fun `실행 중 바 문장`() {
        assertEquals("바깥 공기로 바꾸는 중  ·  1:05 뒤 원래대로", runningBarText(65, "바깥 공기로 바꾸는 중"))
        assertEquals("되돌리는 중...", runningBarText(0, "되돌리는 중..."))
    }

    // ---------- 좌석 값 끊김 ----------

    @Test fun `좌석 값이 한 번 끊긴 것은 넘기고 두 번 연속이면 끊긴 것으로 본다`() {
        val s = SeatLiveness()
        assertNull(s.offer(false))
        assertEquals(true, s.offer(true))
        assertNull(s.offer(true))
        // 한 번 튄 것
        assertNull(s.offer(false))
        assertTrue(s.live)
        assertNull(s.offer(true))
        // 두 번 연속
        assertNull(s.offer(false))
        assertEquals(false, s.offer(false))
        assertFalse(s.live)
        assertNull(s.offer(false))
        // 돌아오면 바로 인정
        assertEquals(true, s.offer(true))
    }

    @Test fun `끊김이 인정되면 디바운서를 새로 만들어 같은 인원이 돌아와도 다시 알린다`() {
        val live = SeatLiveness()
        var debouncer = OccupantDebouncer()
        val published = mutableListOf<Int>()
        fun poll(sensorLive: Boolean, count: Int) {
            live.offer(sensorLive)?.let { if (!it) debouncer = OccupantDebouncer() }
            if (sensorLive) debouncer.offer(count)?.let { published += it }
        }
        poll(true, 3); poll(true, 3)
        assertEquals(listOf(3), published)
        poll(false, 1); poll(false, 1) // 끊김 인정. 앱은 수동 입력 값으로 덮는다
        poll(true, 3); poll(true, 3)
        assertEquals("값이 돌아오면 같은 3명이어도 다시 알린다", listOf(3, 3), published)
        // 한 번만 튄 경우에는 다시 알리지 않는다
        poll(false, 1); poll(true, 3); poll(true, 3)
        assertEquals(listOf(3, 3), published)
    }

    // ---------- 도착 예정 단위 ----------

    @Test fun `정한 단위는 반대 판정 한 번으로 바꾸지 않고 두 번 연속일 때만 바꾼다`() {
        val j = EtaJudge()
        assertEquals(21, j.minutes(1260, 28_000)) // 초
        assertEquals(true, j.unitSeconds)
        // 분으로 보이는 표본 하나(21, 28km): 아직 초로 읽는다(올림 1분)
        assertEquals(1, j.minutes(21, 28_000))
        assertEquals(true, j.unitSeconds)
        // 다시 초로 보이면 반대 횟수는 0 부터
        assertEquals(20, j.minutes(1200, 27_000))
        assertEquals(1, j.minutes(20, 27_000))
        assertEquals(true, j.unitSeconds)
        // 두 번 연속 분으로 보이면 바꾼다
        assertEquals(19, j.minutes(19, 26_000))
        assertEquals(false, j.unitSeconds)
    }

    @Test fun `남은 거리가 300m 미만인 표본은 단위 판정에 쓰지 않는다`() {
        // 200m 를 4(분)에: 분으로 보면 시속 3km, 초로 보면 시속 180km. 어차피 판정 불가지만 250m 를 9초(시속 100km)는 초로 보인다
        assertEquals(true, EtaJudge.unitOf(9, 250))
        val fresh = EtaJudge()
        assertNull("처음 표본이 300m 미만이면 판정하지 않는다", fresh.minutes(9, 250))
        assertNull(fresh.unitSeconds)
        // 분으로 정해진 뒤 도착 직전의 짧은 표본 두 개로는 뒤집히지 않는다
        val j = EtaJudge()
        assertEquals(21, j.minutes(21, 28_000))
        assertEquals(9, j.minutes(9, 250))
        assertEquals(8, j.minutes(8, 220))
        assertEquals(false, j.unitSeconds)
        assertEquals(300, EtaJudge.MIN_SAMPLE_M)
    }

    // ---------- 신호 버스 ----------

    @Test fun `버퍼는 256 이고 넘치면 답과 제어 결과는 버리지 않고 다시 넣는다`() = runBlocking {
        assertEquals(256, SignalBus.BUFFER)
        val dropped = mutableListOf<Pair<Signal, Boolean>>()
        val slow = SignalBus(capacity = 2, onDrop = { s, kept -> dropped += s to kept })
        val received = mutableListOf<Signal>()
        val gate = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { slow.signals.collect { received += it; gate.receive() } }
        while (slow.subscriberCount == 0) kotlinx.coroutines.delay(1)
        // 첫 신호는 구독자가 잡고 멈춘다. 그 뒤 둘은 버퍼, 나머지는 넘친다
        slow.emit(GearChanged(1, Gear.DRIVE))
        while (received.isEmpty()) kotlinx.coroutines.delay(1)
        slow.emit(GearChanged(2, Gear.DRIVE)); slow.emit(GearChanged(3, Gear.DRIVE))
        slow.emit(GearChanged(4, Gear.DRIVE))                       // 버림
        slow.emit(UserAnswer(5, true))                              // 보관
        slow.emit(ActionResult(6, Action.Revert, ok = true))        // 보관
        slow.emit(GearChanged(7, Gear.PARK))                        // 버림(보관한 것 뒤라 순서를 지키려고 넣지 않는다)
        assertEquals(listOf(false, true, true, false), dropped.map { it.second })
        assertEquals(2, slow.pendingCount)
        // 구독자가 다시 받기 시작하면 flushPending 이 보관한 것을 순서대로 넣는다
        repeat(3) { gate.send(Unit) }
        while (received.size < 3) kotlinx.coroutines.delay(1)
        slow.flushPending()
        repeat(4) { gate.send(Unit) }
        while (received.size < 5) kotlinx.coroutines.delay(1)
        assertEquals(0, slow.pendingCount)
        assertEquals(listOf(1L, 2L, 3L, 5L, 6L), received.map { it.t })
        assertTrue(received[3] is UserAnswer && received[4] is ActionResult)
        collector.cancel()
    }

    @Test fun `답과 제어 결과만 보관 대상이다`() {
        assertTrue(SignalBus.critical(UserAnswer(1, false)))
        assertTrue(SignalBus.critical(ActionResult(1, Action.Ventilate(), ok = false)))
        assertFalse(SignalBus.critical(GearChanged(1, Gear.PARK)))
    }

    // ---------- 시연 시나리오 격리 ----------

    @Test fun `시연 격리는 처음 잡은 값을 들고 있다가 끝날 때 한 번만 돌려준다`() {
        val iso = DemoIsolation()
        assertFalse(iso.active)
        assertNull(iso.end())
        val before = DemoSnapshot(TrustState(delegated = true, consecutiveYes = 2), manualOccupants = 3, carAuto = true, etaMinutes = 40)
        iso.begin(before)
        assertTrue(iso.active)
        // 시나리오를 다시 눌러도(이미 시나리오가 바꾼 값으로) 덮지 않는다
        iso.begin(DemoSnapshot(TrustState(), manualOccupants = 4, carAuto = false, etaMinutes = null))
        assertEquals(before, iso.end())
        assertFalse(iso.active)
        assertNull(iso.end())
    }

    @Test fun `시나리오 첫 단계에서 차 자동 공조를 끄고 목적지를 지운다`() {
        val calls = mutableListOf<String>()
        val hands = object : DemoScenario.Hands {
            override fun gear(g: Gear) { calls += "gear $g" }
            override fun recirculation(on: Boolean) {}
            override fun occupants(n: Int) { calls += "occupants $n" }
            override fun fanSpeed(level: Int, max: Int) {}
            override fun windowOpen(percent: Int) {}
            override fun lowBeam(on: Boolean) {}
            override fun addTripMinutes(m: Int) {}
            override fun driverAttention(on: Boolean) {}
            override fun restWithin() {}
            override fun answerYes() {}
            override fun frontPassenger(present: Boolean) {}
            override fun eta(minutes: Int?) { calls += "eta $minutes" }
            override fun autoClimate(on: Boolean) { calls += "autoClimate $on" }
        }
        val runner = DemoScenario.Runner(DemoScenario.steps(20, 30, 15), hands)
        runner.tick(0, DemoScenario.View())
        assertEquals(0, runner.index)
        assertEquals(listOf("autoClimate false", "eta null"), calls.take(2))
        assertTrue(calls.contains("occupants 1"))
        // 기어 D 는 그 뒤 단계다(자동 공조와 목적지가 먼저 꺼진다)
        assertTrue(calls.indexOf("autoClimate false") < calls.indexOf("gear PARK"))
    }

    // ---------- 화면 밖 만료 뒤의 "다시 묻기까지" ----------

    @Test fun `화면 밖 만료는 첫 번째 5 두 번 연속부터는 거절과 같은 값이다`() {
        assertEquals(5, hiddenExpiryCooldown(1, 20))
        assertEquals(20, hiddenExpiryCooldown(2, 20))
        assertEquals(20, hiddenExpiryCooldown(3, 20))
        assertEquals(WakeEngine.HIDDEN_EXPIRY_COOLDOWN_MINUTES, hiddenExpiryCooldown(0, 20))
    }

    // ---------- 홈 문구와 "바깥 공기로" 버튼 ----------

    private fun air(ppm: Int, ventilating: Boolean = false) = AirUi(ppm, 4, if (ppm >= 2000) 0 else 3, ventilating)

    @Test fun `조용 모드여도 한도 이상이면 한도 초과 문구가 먼저다`() {
        val quiet = AirLineContext(quiet = true)
        assertEquals("4명 탑승 · 한도를 넘었어요(추정). 묻지 않는 중이에요", airStatusLine(air(2000), 4, false, quiet))
        assertEquals("4명 탑승 · 이번 주행은 화면에만 표시해요", airStatusLine(air(1999), 4, false, quiet))
        // 야간 장거리의 낮춘 한도를 따른다
        assertEquals("4명 탑승 · 한도를 넘었어요(추정). 묻지 않는 중이에요", airStatusLine(air(1700), 4, false, quiet.copy(limit = 1600)))
        // 조용 모드가 아니면 전과 같다
        assertEquals("4명 탑승 · 한도를 넘었어요(추정)", airStatusLine(air(2100), 4, false, AirLineContext()))
        // 이미 바깥 공기면 그 문구가 먼저다
        assertEquals("바깥 공기 들어오는 중", airStatusLine(air(2100, ventilating = true).copy(falling = false), 4, false, quiet.copy(limit = 5000)))
    }

    @Test fun `바깥 공기로 버튼은 조용 모드에서 한도를 넘었고 다른 개입이 없을 때만 보인다`() {
        assertEquals("바깥 공기로", FRESH_AIR_BUTTON)
        val ui = PreviewStates.quietOverLimit
        assertTrue(showFreshAirButton(ui))
        assertFalse("조용 모드가 아니면 없다", showFreshAirButton(ui.copy(quietThisTrip = false)))
        assertFalse("한도 아래면 없다", showFreshAirButton(ui.copy(air = air(1800))))
        assertFalse("이미 바깥 공기면 없다", showFreshAirButton(ui.copy(air = air(2600, ventilating = true))))
        assertFalse("환기 실행 중이면 없다", showFreshAirButton(ui.copy(running = RunningBar(TriggerKind.STALE_AIR, 100, "바깥 공기로 바꾸는 중"))))
        assertFalse("졸음 제안이 떠 있으면 없다", showFreshAirButton(ui.copy(proposal = Proposal(TriggerKind.DROWSY, "졸음 경고가 떴어요. 찬 바람을 넣을까요?", 8))))
        assertFalse("쉬는 중이면 없다", showFreshAirButton(ui.copy(rest = RestUi("석적 졸음쉼터", 15, 600))))
        assertFalse("숨이 꺼져 있으면 없다", showFreshAirButton(ui.copy(autoIntervene = false)))
        assertFalse("정차 중이면 없다", showFreshAirButton(ui.copy(tripState = ai.kkaeugi.engine.TripState.ENDED)))
    }
}

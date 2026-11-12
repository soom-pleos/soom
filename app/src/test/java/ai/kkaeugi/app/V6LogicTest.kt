package ai.kkaeugi.app

import ai.kkaeugi.app.speech.AnswerClassifier
import ai.kkaeugi.app.speech.announceHint
import ai.kkaeugi.app.ui.AirLineContext
import ai.kkaeugi.app.ui.airStatusLine
import ai.kkaeugi.app.ui.answerButtonLayout
import ai.kkaeugi.app.ui.countdownLeft
import ai.kkaeugi.app.ui.developerSummary
import ai.kkaeugi.app.ui.diagStatusLine
import ai.kkaeugi.app.ui.keepWords
import ai.kkaeugi.app.ui.trustAnswerLine
import ai.kkaeugi.app.vehicle.DiagLine
import ai.kkaeugi.app.vehicle.DiagReport
import ai.kkaeugi.app.vehicle.EtaJudge
import ai.kkaeugi.app.vehicle.OccupantCounter
import ai.kkaeugi.app.vehicle.OccupantInput
import ai.kkaeugi.app.vehicle.StableFlag
import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.TrustState
import ai.kkaeugi.engine.WakeConfig
import ai.kkaeugi.engine.WakeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.6(계약 v4)에서 앱에 더한 순수 로직: 도착 예정 단위 판정, 맡기기 저장, 예고 카드, 자가 진단 보고서, 탑승 인원 입력, 쉼 중 스냅샷 */
class V6LogicTest {

    // ---------- 도착 예정: 남은 시간의 단위 판정 ----------

    @Test fun `남은 시간이 초 단위면 거리로 나눈 속도가 20에서 150 사이다`() {
        // 28km 를 1,260초(21분)에 간다 = 시속 80km
        assertEquals(true, EtaJudge.unitOf(1260, 28_000))
        assertEquals(21, EtaJudge().minutes(1260, 28_000))
        // 올림: 61초는 2분
        val j = EtaJudge()
        assertEquals(21, j.minutes(1260, 28_000))
        assertEquals(2, j.minutes(61, 1_000))
    }

    @Test fun `남은 시간이 분 단위면 분으로 본 속도가 범위에 든다`() {
        // 28km 를 21(분)에 간다. 초로 보면 시속 4,800km 라 말이 안 된다
        assertEquals(false, EtaJudge.unitOf(21, 28_000))
        assertEquals(21, EtaJudge().minutes(21, 28_000))
    }

    @Test fun `어느 쪽도 범위에 들지 않으면 판정하지 않는다`() {
        // 정체: 1km 에 600초(시속 6km), 분으로 보면 시속 0.1km
        assertNull(EtaJudge.unitOf(600, 1_000))
        assertNull(EtaJudge().minutes(600, 1_000))
        // 값이 0 이하
        assertNull(EtaJudge.unitOf(0, 28_000))
        assertNull(EtaJudge.unitOf(1260, 0))
        assertNull(EtaJudge().minutes(-1, 28_000))
    }

    @Test fun `한 번 판정한 단위는 판정이 안 되는 값에도 이어 쓴다`() {
        val j = EtaJudge()
        assertEquals(21, j.minutes(1260, 28_000))
        assertEquals(true, j.unitSeconds)
        // 도착 직전 정체: 이번 값만으로는 판정이 안 되지만 초로 읽는다. 240초 = 4분(엔진의 도착 임박 기준 5분 이하)
        assertEquals(4, j.minutes(240, 900))
        assertEquals(0, j.minutes(0, 0))
        // 분 단위 차도 같다
        val m = EtaJudge()
        assertEquals(40, m.minutes(40, 50_000))
        assertEquals(3, m.minutes(3, 400))
    }

    @Test fun `경계 속도 20과 150은 범위 안이다`() {
        assertEquals(true, EtaJudge.unitOf(3600, 20_000))
        assertEquals(true, EtaJudge.unitOf(3600, 150_000))
        assertNull(EtaJudge.unitOf(3600, 151_000))
        assertEquals("남은 시간 raw 1260, 거리 raw 28000 → 초(80km/h), 21분", EtaJudge.describe(1260, 28_000, 21))
        assertEquals("남은 시간 raw 600, 거리 raw 1000 → 단위 판정 불가", EtaJudge.describe(600, 1_000, null))
    }

    // ---------- 맡기기 저장과 복원 ----------

    @Test fun `맡기기 상태는 저장했다 읽으면 그대로다`() {
        for (s in listOf(TrustState(), TrustState(true, 0, 0), TrustState(false, 2, 0), TrustState(true, 5, 1))) {
            assertEquals(s, TrustCodec.decode(TrustCodec.encode(s)))
        }
        assertEquals("1,3,1", TrustCodec.encode(TrustState(true, 3, 1)))
    }

    @Test fun `저장이 없거나 깨졌으면 맡기지 않은 처음 상태다`() {
        for (bad in listOf(null, "", "1", "1,2", "x,1,0", "1,a,0", "1,2,b", "2,0,0", "1,2,3,4")) assertEquals(bad, TrustState(), TrustCodec.decode(bad))
        assertEquals("음수는 0 으로", TrustState(true, 0, 0), TrustCodec.decode("1,-3,-1"))
    }

    @Test fun `복원한 상태를 엔진에 넣으면 그대로 돌아온다`() {
        val saved = TrustCodec.encode(TrustState(delegated = true, consecutiveYes = 3, cancelCount = 1))
        val engine = WakeEngine(WakeConfig())
        assertEquals(TrustState(), engine.trust)
        engine.setTrust(TrustCodec.decode(saved))
        assertEquals(TrustState(true, 3, 1), engine.trust)
    }

    @Test fun `설정 토글 - 켜면 취소 횟수를 지우고, 끄면 처음부터 다시 센다`() {
        assertEquals(TrustState(true, 3, 0), TrustCodec.toggled(TrustState(false, 3, 1), on = true))
        assertEquals(TrustState(), TrustCodec.toggled(TrustState(true, 3, 1), on = false))
    }

    // ---------- 예고 카드 ----------

    @Test fun `예고 카드 카운트다운은 다 읽은 뒤부터 5초를 센다`() {
        assertNull("읽는 중", countdownLeft(5, null, 10_000))
        assertEquals(5, countdownLeft(5, 10_000, 10_000))
        assertEquals(3, countdownLeft(5, 10_000, 12_400))
        assertEquals(0, countdownLeft(5, 10_000, 15_000))
        assertEquals("음수가 되지 않는다", 0, countdownLeft(5, 10_000, 99_000))
    }

    @Test fun `예고 카드 안내 - 바로 하라는 답은 말로만 받는다`() {
        assertEquals("말하는 중...", announceHint(null, voiceAnswer = true))
        assertEquals("3초 뒤 바꿔요  ·  바로 하려면 \"응\", 싫으면 \"아니\"", announceHint(3, voiceAnswer = true))
        assertEquals("음성 인식이 없으면 취소 버튼만 안내한다", "3초 뒤 바꿔요  ·  싫으면 취소를 눌러 주세요", announceHint(3, voiceAnswer = false))
    }

    @Test fun `예고를 물리는 말 - 아니, 괜찮아, 취소`() {
        for (no in listOf("아니", "아니요", "괜찮아", "괜찮아요", "취소", "취소해 줘", "취소해줘요")) assertEquals(no, false, AnswerClassifier.classify(no))
        assertEquals(true, AnswerClassifier.classify("응"))
        assertNull("긴 문장은 판정하지 않는다", AnswerClassifier.classify("그거 취소하면 안 되는 거 아니야"))
    }

    // ---------- 조수석에 사람이 있을 때의 버튼 배치 ----------

    @Test fun `조수석에 사람이 있으면 버튼을 오른쪽에 붙이고 키운다`() {
        val normal = answerButtonLayout(false)
        val passenger = answerButtonLayout(true)
        assertFalse(normal.passengerSide); assertEquals(96, normal.heightDp); assertEquals(1f, normal.widthFraction)
        assertTrue(passenger.passengerSide)
        assertTrue(passenger.heightDp > normal.heightDp && passenger.yesSp > normal.yesSp)
        assertTrue("오른쪽에 붙이려면 폭을 다 쓰지 않는다", passenger.widthFraction < 1f)
    }

    // ---------- 자가 진단 보고서 ----------

    @Test fun `자가 진단 한 줄은 항목 값 또는 항목 실패 사유`() {
        assertEquals("기어: GEAR_PARK", DiagLine.ok("기어", "GEAR_PARK").text())
        assertEquals("와이퍼: 실패 (응답 없음 3초)", DiagLine.fail("와이퍼", "응답 없음 3초").text())
        assertEquals("사유가 비면", "와이퍼: 실패 (사유 없음)", DiagLine.fail("와이퍼", "").text())
        assertEquals("퍼미션 CAR_DRIVE: 허용", DiagReport.permissionLine("pleos.car.permission.CAR_DRIVE", true).text())
        assertEquals("퍼미션 PRIVATE_CAR_SAFETY: 실패 (거부)", DiagReport.permissionLine("pleos.car.permission.PRIVATE_CAR_SAFETY", false).text())
    }

    @Test fun `보고서는 머리말, 성공 실패 요약, 항목 줄 순서다`() {
        val lines = listOf(DiagLine.ok("연결 판정", "연결 gear=GEAR_PARK"), DiagLine.fail("텔테일 등록", "PERMISSION"), DiagLine.ok("기어", "GEAR_PARK"))
        assertEquals("성공 2 / 실패 1", DiagReport.summary(lines))
        assertEquals("성공 0 / 실패 0", DiagReport.summary(emptyList()))
        val header = DiagReport.header("0.6.0", "2026-11-11 09:12:30")
        assertEquals("숨 자가 진단 v0.6.0 · 2026-11-11 09:12:30", header)
        assertEquals(
            listOf(header, "성공 2 / 실패 1", "연결 판정: 연결 gear=GEAR_PARK", "텔테일 등록: 실패 (PERMISSION)", "기어: GEAR_PARK"),
            DiagReport.format(header, lines).lines(),
        )
        val ui = DiagUi(header, lines, running = false, saved = true)
        assertEquals(DiagReport.format(header, lines), ui.report)
        assertTrue(diagStatusLine(ui).contains("Kkaeugi.Diag") && diagStatusLine(ui).contains("diag_latest.txt"))
        assertTrue(diagStatusLine(ui.copy(running = true)).startsWith("진단 중"))
        assertTrue(diagStatusLine(ui.copy(saved = false)).contains("파일 저장은 실패"))
    }

    @Test fun `보고서 줄을 세 단으로 나누면 순서가 유지되고 빠지는 줄이 없다`() {
        val lines = (1..49).map { DiagLine.ok("항목$it", "값") }
        val cols = DiagReport.columns(lines, 3)
        assertEquals(3, cols.size)
        assertEquals(listOf(17, 17, 15), cols.map { it.size })
        assertEquals(lines, cols.flatten())
        assertEquals(listOf(emptyList<DiagLine>()), DiagReport.columns(emptyList(), 3))
        assertEquals(2, DiagReport.columns(lines.take(2), 3).size)
    }

    @Test fun `표본 보고서의 요약은 줄 수와 맞는다`() {
        val d = ai.kkaeugi.app.ui.PreviewStates.diagnosis.diag!!
        assertEquals(d.lines.size, d.lines.count { it.ok } + d.lines.count { !it.ok })
        assertEquals("성공 43 / 실패 6", d.summary)
        assertTrue("퍼미션 14개가 다 있다", d.lines.count { it.name.startsWith("퍼미션 ") } == 14)
    }

    // ---------- 탑승 인원 입력 ----------

    @Test fun `좌석 값이 오면 스테퍼를 숨기고, 안 오면 정차 중에만 스테퍼`() {
        assertEquals(OccupantInput.Mode.SENSOR, OccupantInput.mode(sensorLive = true, driving = false))
        assertEquals(OccupantInput.Mode.SENSOR, OccupantInput.mode(sensorLive = true, driving = true))
        assertEquals(OccupantInput.Mode.STEPPER, OccupantInput.mode(sensorLive = false, driving = false))
        assertEquals("주행 중에는 바꿀 수 없다", OccupantInput.Mode.LOCKED, OccupantInput.mode(sensorLive = false, driving = true))
        assertEquals("좌석 센서 기준 3명", OccupantInput.sensorLabel(3))
        assertEquals("4명 그대로", OccupantInput.carriedChip(4))
    }

    @Test fun `스테퍼는 1에서 5 사이, 주행 중이거나 좌석 값이 오면 그대로`() {
        assertEquals(2, OccupantInput.step(1, +1, sensorLive = false, driving = false))
        assertEquals(1, OccupantInput.step(1, -1, sensorLive = false, driving = false))
        assertEquals(5, OccupantInput.step(5, +1, sensorLive = false, driving = false))
        assertEquals(3, OccupantInput.step(3, +1, sensorLive = false, driving = true))
        assertEquals(3, OccupantInput.step(3, -1, sensorLive = true, driving = false))
        assertEquals(5, OccupantInput.clamp(9)); assertEquals(1, OccupantInput.clamp(0))
    }

    @Test fun `수동 입력만으로는 조수석에 사람이 있다고 보지 않는다`() {
        assertFalse(OccupantInput.frontPassenger(sensorLive = false, frontSeatOccupied = true))
        assertFalse(OccupantInput.frontPassenger(sensorLive = true, frontSeatOccupied = false))
        assertTrue(OccupantInput.frontPassenger(sensorLive = true, frontSeatOccupied = true))
    }

    @Test fun `좌석 값이 하나도 안 오면 센서가 없는 것으로 보고, 조수석은 점유 없으면 벨트로 본다`() {
        val none = OccupantCounter.count(mapOf("ROW_1_LEFT" to null, "ROW_1_RIGHT" to null), driverSeat = "ROW_1_LEFT", driving = true)
        assertFalse("주행 중 운전석을 1 로 세는 것만으로는 센서가 있다고 보지 않는다", none.sensorLive)
        assertFalse(none.occupied("ROW_1_RIGHT"))
        val occ = OccupantCounter.count(mapOf("ROW_1_LEFT" to true, "ROW_1_RIGHT" to true))
        assertTrue(occ.sensorLive); assertTrue(occ.occupied("ROW_1_RIGHT"))
        val belt = OccupantCounter.count(mapOf("ROW_1_LEFT" to true, "ROW_1_RIGHT" to null), mapOf("ROW_1_RIGHT" to true))
        assertTrue(belt.occupied("ROW_1_RIGHT"))
        assertFalse("못 읽은 자리", belt.occupied("ROW_2_LEFT"))
    }

    @Test fun `조수석 값은 두 번 연속 같을 때만, 바뀔 때만 알린다`() {
        val f = StableFlag()
        assertNull(f.offer(true))
        assertEquals(true, f.offer(true))
        assertNull("같은 값은 다시 알리지 않는다", f.offer(true))
        assertNull("한 번 튄 값", f.offer(false))
        assertNull(f.offer(true))
        assertNull(f.offer(false))
        assertEquals(false, f.offer(false))
    }

    // ---------- 쉼 모드의 바깥 공기: 스냅샷 유지 ----------

    @Test fun `쉬는 동안 바꾼 바깥 공기는 Done 에서 스냅샷을 버리지 않고, 쉼이 끝난 뒤 원복으로 끝난다`() {
        val t = ExecutionTracker()
        // RestStarted → Propose → 응 → Execute(Ventilate) → 결과 → Done(null)
        assertFalse(t.onExecute(listOf(Action.Ventilate())))
        assertEquals(ExecEnd.HeldForRest, t.onDone(resting = true))
        // RestEnded → Execute([Revert]) → Done(null)
        assertTrue(t.onExecute(listOf(Action.Revert)))
        assertEquals(ExecEnd.Reverted, t.onDone(resting = false))
        assertEquals(ExecEnd.None, t.onDone())
    }

    @Test fun `쉼 질문을 거절하거나 답이 없으면 아무 일도 없다`() {
        val t = ExecutionTracker()
        assertEquals(ExecEnd.None, t.onDone(resting = true))
    }

    @Test fun `주행 중 원복 없는 실행은 그대로 스냅샷 폐기 대상이다`() {
        val t = ExecutionTracker()
        t.onExecute(listOf(Action.Ventilate()))
        assertEquals(ExecEnd.Kept(listOf(Action.Ventilate())), t.onDone(resting = false))
    }

    // ---------- 홈 문구, 설정 ----------

    @Test fun `환기 제안을 미루는 사유의 홈 문구`() {
        assertEquals("도착 4분 전이라 묻지 않아요", HomeHold.line(HomeHold.ARRIVING, 4))
        assertEquals("곧 도착이라 묻지 않아요", HomeHold.line(HomeHold.ARRIVING, 0))
        assertEquals("곧 도착이라 묻지 않아요", HomeHold.line(HomeHold.ARRIVING, null))
        assertEquals("차가 내외기를 자동 조절 중이에요", HomeHold.line(HomeHold.CAR_AUTO, 30))
        assertNull(HomeHold.line(null, 4))
        assertNull("하향등이 막 켜진 것은 표시하지 않는다", HomeHold.fromDeferred("lights-just-on"))
        assertNull(HomeHold.fromDeferred("turn_signal"))
        assertEquals(HomeHold.ARRIVING, HomeHold.fromDeferred("arriving"))
        assertEquals(HomeHold.CAR_AUTO, HomeHold.fromDeferred("car-auto"))
        assertEquals("도착 임박이 먼저", HomeHold.ARRIVING, HomeHold.reason(arrivingSoon = true, carAuto = true))
        assertEquals(HomeHold.CAR_AUTO, HomeHold.reason(arrivingSoon = false, carAuto = true))
        assertNull(HomeHold.reason(arrivingSoon = false, carAuto = false))
        // 엔진의 사유 코드와 같다
        assertEquals(WakeEngine.HOLD_ARRIVING, HomeHold.ARRIVING)
        assertEquals(WakeEngine.HOLD_CAR_AUTO, HomeHold.CAR_AUTO)
    }

    @Test fun `게이지 아래 한 줄 - 두 번 거절한 주행, 도착 임박, 차의 자동 내외기`() {
        val air = AirUi(1500, 2, 6, ventilating = false)
        assertEquals("2명 탑승 · 이번 주행은 화면에만 표시해요", airStatusLine(air, 2, false, AirLineContext(quiet = true, cooldownLeft = 12)))
        assertEquals("2명 탑승 · 도착 4분 전이라 묻지 않아요", airStatusLine(air, 2, false, AirLineContext(holdLine = HomeHold.line(HomeHold.ARRIVING, 4))))
        assertEquals("2명 탑승 · 차가 내외기를 자동 조절 중이에요", airStatusLine(air, 2, false, AirLineContext(holdLine = HomeHold.line(HomeHold.CAR_AUTO, null))))
        assertEquals("숨을 끈 것이 먼저", "2명 탑승 · 숨 꺼짐", airStatusLine(air, 2, false, AirLineContext(autoIntervene = false, quiet = true)))
        assertEquals("바깥 공기면 그 상태가 먼저", "바깥 공기 들어오는 중", airStatusLine(air.copy(ventilating = true), 2, false, AirLineContext(quiet = true)))
    }

    @Test fun `엔진 설정 - 핸들 보조 신호와 창문은 기본이 꺼짐`() {
        for (demo in listOf(false, true)) {
            val c = DemoSettings.build(demoFast = demo)
            assertEquals(demo, c.demoFast)
            assertFalse(c.steeringAssist); assertFalse(c.allowWindow)
        }
        val on = DemoSettings.build(demoFast = false, steeringAssist = true, allowWindow = true)
        assertTrue(on.steeringAssist); assertTrue(on.allowWindow)
        assertEquals("나머지 값은 기본 그대로", WakeConfig().copy(steeringAssist = true, allowWindow = true), on)
    }

    @Test fun `도착 카드 맡기기 질문에 답한 뒤의 한 줄, 개발자 묶음 요약`() {
        assertEquals("맡겼어요. 설정에서 언제든 끌 수 있어요", trustAnswerLine(true))
        assertEquals("알겠어요. 계속 물어볼게요", trustAnswerLine(false))
        assertEquals("모의 모드", developerSummary(UiState()))
        assertEquals("등록 실패 1건 · 연결됨 · 데모 속도 · 창문 포함 · 화면 밖 유지",
            developerSummary(UiState(pleosAvailable = true, permissionFailures = listOf("x"), demoFast = true, allowWindow = true, keepAlive = true)))
    }

    @Test fun `큰 글씨 문장은 낱말 가운데에서 끊기지 않게 이음표를 넣되 보이는 글자는 같다`() {
        val text = "바깥 공기로 바꿔 둘까요?"
        val kept = keepWords(text)
        assertEquals(text, kept.replace("⁠", ""))
        assertEquals("띄어쓰기는 그대로", 3, kept.count { it == ' ' })
        assertTrue(kept.startsWith("바⁠깥 "))
    }
}

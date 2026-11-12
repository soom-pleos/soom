package ai.kkaeugi.app.vehicle

import ai.kkaeugi.app.signals.SignalBus
import ai.kkaeugi.app.ui.hvacNotice
import ai.kkaeugi.app.ui.noRestStopText
import ai.kkaeugi.app.UiState
import ai.kkaeugi.engine.Action
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.2: 2026-11-11 Pleos Connect v2.1.0 에뮬레이터 첫 실측에서 나온 것들.
 * HVAC 영역 선택 규칙, 창문 범위 0~10, 자가 진단 분류(보류, 대체), 런타임 퍼미션, heading 없는 실제 위치.
 */
class HvacAreaTest {
    private val unsupported = "java.lang.IllegalArgumentException: area ID: 0x1 not supported for property ID: HVAC_RECIRC_ON"
    private val unavailable = "android.car.hardware.property.PropertyNotAvailableException: Property ID: HVAC_TEMPERATURE_SET area ID: 0x1 - is not available because of vendor error code: 0"

    private fun read(mask: Int, kind: ProbeKind, text: String = "") = ProbeRead(mask, kind, text)

    // ---------- 영역 선택 규칙 ----------

    @Test fun `좌석 마스크는 SDK SeatValue 의 id 를 OR 한 값`() {
        assertEquals(0x75, HvacAreaRule.maskOf(listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")))
        assertEquals(0x31, HvacAreaRule.maskOf(listOf("ROW_1_LEFT", "ROW_2_LEFT", "ROW_2_CENTER")))
        assertEquals(0x5, HvacAreaRule.maskOf(listOf("ROW_1_LEFT", "ROW_1_RIGHT")))
        assertEquals(listOf("ROW_1_LEFT", "ROW_2_LEFT", "ROW_2_CENTER"), HvacAreaRule.seatNames(0x31))
        assertEquals("0x75", HvacAreaRule.hex(0x75))
        assertEquals(0, HvacAreaRule.maskOf(listOf("없는 좌석")))
    }

    @Test fun `후보 순서는 전 좌석, 운전석 쪽 구역, 1열 좌우, 운전석 단독 순으로 시작`() {
        assertEquals(listOf(0x75, 0x31, 0x5, 0x1), HvacAreaRule.CANDIDATES.take(4))
        assertEquals("후보에 중복이 없다", HvacAreaRule.CANDIDATES.size, HvacAreaRule.CANDIDATES.toSet().size)
        assertTrue("모든 후보는 SDK 좌석으로 만들 수 있다", HvacAreaRule.CANDIDATES.all { HvacAreaRule.maskOf(HvacAreaRule.seatNames(it)) == it })
    }

    @Test fun `capability - 운전석을 포함하는 영역을 고른다`() {
        assertEquals(0x31, HvacAreaRule.pickFromCapability(listOf(0x44, 0x31)))
        assertEquals(0x75, HvacAreaRule.pickFromCapability(listOf(0x75)))
        assertEquals("좌석별 영역이면 운전석", 0x1, HvacAreaRule.pickFromCapability(listOf(0x4, 0x1, 0x10)))
    }

    @Test fun `capability - 운전석을 포함하는 영역이 없으면 첫 영역`() {
        assertEquals(0x44, HvacAreaRule.pickFromCapability(listOf(0x44, 0x40)))
    }

    @Test fun `capability - 비었거나 전역뿐이면 고르지 않는다`() {
        assertNull(HvacAreaRule.pickFromCapability(emptyList()))
        assertNull(HvacAreaRule.pickFromCapability(null))
        assertNull("영역 0(전역)과 -1 은 SeatArea 로 부를 수 없다", HvacAreaRule.pickFromCapability(listOf(0, -1)))
        assertEquals(0x75, HvacAreaRule.pickFromCapability(listOf(0, 0x75)))
    }

    @Test fun `실패 문장 분류 - 실측 문장`() {
        assertEquals(ProbeKind.UNSUPPORTED, HvacAreaRule.classify(unsupported))
        assertEquals(ProbeKind.UNAVAILABLE, HvacAreaRule.classify(unavailable))
        assertEquals(ProbeKind.UNSUPPORTED, HvacAreaRule.classify("ERROR_NOT_SUPPORTED_PROPERTY(60001): not supported"))
        assertEquals(ProbeKind.ERROR, HvacAreaRule.classify("응답 없음(1500ms)"))
        assertEquals(ProbeKind.ERROR, HvacAreaRule.classify("ERROR_SECURITY_PERMISSION(2): denied"))
        assertTrue(HvacAreaRule.isUnavailable(unavailable)); assertFalse(HvacAreaRule.isUnavailable(unsupported))
    }

    @Test fun `탐색 - 값이 오는 첫 후보를 고른다`() {
        val reads = listOf(read(0x75, ProbeKind.UNSUPPORTED), read(0x31, ProbeKind.VALUE, "꺼짐"), read(0x5, ProbeKind.VALUE, "꺼짐"))
        assertEquals(0x31 to false, HvacAreaRule.pickFromProbes(reads))
    }

    @Test fun `탐색 - 값은 없고 지금 못 읽는 후보가 있으면 보류로 채택`() {
        val reads = listOf(read(0x75, ProbeKind.UNAVAILABLE), read(0x31, ProbeKind.UNSUPPORTED), read(0x1, ProbeKind.UNSUPPORTED))
        assertEquals(0x75 to true, HvacAreaRule.pickFromProbes(reads))
        assertEquals("값이 오는 후보가 뒤에 있으면 그쪽", 0x1 to false,
            HvacAreaRule.pickFromProbes(listOf(read(0x75, ProbeKind.UNAVAILABLE), read(0x1, ProbeKind.VALUE, "3"))))
    }

    @Test fun `탐색 - 전부 미지원이거나 응답이 없으면 고르지 않는다`() {
        assertNull(HvacAreaRule.pickFromProbes(HvacAreaRule.CANDIDATES.map { read(it, ProbeKind.UNSUPPORTED) }))
        assertNull(HvacAreaRule.pickFromProbes(listOf(read(0x75, ProbeKind.ERROR, "응답 없음"))))
        assertNull(HvacAreaRule.pickFromProbes(emptyList()))
    }

    @Test fun `결정 - capability 가 영역을 주면 그대로`() {
        val a = HvacAreaRule.decide(HvacProp.RECIRC, listOf(0x75), null, read(0x75, ProbeKind.VALUE, "켜짐(내기)"), emptyList())
        assertEquals(0x75, a.mask); assertEquals(HvacArea.Source.CAPABILITY, a.source); assertFalse(a.pending)
        assertEquals("0x75 (capability)", HvacAreaRule.describe(a))
    }

    @Test fun `결정 - capability 영역이 지금 못 읽는 상태면 보류`() {
        val a = HvacAreaRule.decide(HvacProp.TEMP, listOf(0x1, 0x4), null, read(0x1, ProbeKind.UNAVAILABLE, unavailable), emptyList())
        assertEquals(0x1, a.mask); assertTrue(a.pending)
        assertEquals("0x1 (capability, 지금은 읽을 수 없음)", HvacAreaRule.describe(a))
    }

    @Test fun `결정 - capability 가 준 영역이 미지원으로 답하면 버리고 후보에서 찾는다`() {
        val probes = listOf(read(0x75, ProbeKind.VALUE, "3"), read(0x1, ProbeKind.UNSUPPORTED, unsupported))
        val a = HvacAreaRule.decide(HvacProp.FAN, listOf(0x1), null, read(0x1, ProbeKind.UNSUPPORTED, unsupported), probes)
        assertEquals(0x75, a.mask); assertEquals(HvacArea.Source.PROBE, a.source)
        assertEquals("0x75 (탐색)", HvacAreaRule.describe(a))
    }

    @Test fun `결정 - capability 를 못 읽으면 후보 탐색, 2026-11-11 실측 모양`() {
        // 운전석 단독(0x1)은 미지원, 전 좌석 합(0x75)에서 값이 온다
        val probes = listOf(read(0x75, ProbeKind.VALUE, "꺼짐(외기)"))
        val a = HvacAreaRule.decide(HvacProp.RECIRC, null, "응답 없음(4000ms)", null, probes)
        assertEquals(0x75, a.mask); assertEquals(HvacArea.Source.PROBE, a.source)
        assertEquals("조회 실패: 응답 없음(4000ms)", HvacAreaRule.capabilityText(a))
    }

    @Test fun `결정 - 끝내 못 찾으면 지원 안 함`() {
        val a = HvacAreaRule.decide(HvacProp.RECIRC, emptyList(), null, null, HvacAreaRule.CANDIDATES.map { read(it, ProbeKind.UNSUPPORTED, unsupported) })
        assertNull(a.mask); assertFalse(a.supported); assertEquals(HvacArea.Source.NONE, a.source)
        assertEquals("지원 안 함", HvacAreaRule.describe(a))
        assertEquals("빈 목록", HvacAreaRule.capabilityText(a))
    }

    @Test fun `원문 줄 - capability 영역 목록과 후보별 읽기 결과`() {
        val a = HvacAreaRule.decide(HvacProp.FAN, listOf(0x75, 0), null, null,
            listOf(read(0x75, ProbeKind.VALUE, "3"), read(0x31, ProbeKind.UNSUPPORTED, unsupported), read(0x5, ProbeKind.UNAVAILABLE, unavailable), read(0x1, ProbeKind.ERROR, "응답 없음(1500ms)")))
        assertEquals("0x75=ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_CENTER+ROW_2_RIGHT, 0x0(SeatArea 로 못 부름)", HvacAreaRule.capabilityText(a))
        assertEquals("0x75=3 · 0x31=영역 미지원 · 0x5=지금 못 읽음 · 0x1=응답 없음(1500ms)", HvacAreaRule.probesText(a.probes))
        assertEquals("읽어 보지 않음", HvacAreaRule.probesText(emptyList()))
    }

    // ---------- 창문 범위 0~10 ----------

    @Test fun `창문 15퍼센트 - 범위 0~10 이면 내림 1, 반올림 2 가 아니다`() {
        assertEquals(1, WindowLimit.positionAtMost(15, 0, 10))
        assertEquals("반올림이면 2(20%)가 된다", 2, WindowScale.positionOf(15, 0, 10))
        assertEquals("열림 10%, 15% 상한 안", 10, WindowScale.percentOf(1, 0, 10))
        assertTrue(WindowLimit.opens(15, 0, 10))
    }

    @Test fun `창문 15퍼센트 - 방향 반전이면 max 쪽에서 한 칸`() {
        assertEquals(9, WindowLimit.positionAtMost(15, 0, 10, inverted = true))
        assertEquals(10, WindowScale.percentOf(9, 0, 10, inverted = true))
    }

    @Test fun `창문 15퍼센트 - 범위가 넓으면 전과 같다`() {
        assertEquals(15, WindowLimit.positionAtMost(15, 0, 100))
        assertEquals(85, WindowLimit.positionAtMost(15, 0, 100, inverted = true))
        assertEquals("0~255 는 38(14.9%)", 38, WindowLimit.positionAtMost(15, 0, 255))
        assertTrue(WindowScale.percentOf(38, 0, 255) <= 15)
        assertEquals("min 이 0 이 아니어도", 11, WindowLimit.positionAtMost(15, 10, 20))
    }

    @Test fun `창문 - 어떤 범위에서도 15퍼센트를 넘지 않는다`() {
        for (max in 1..300) for (inv in listOf(false, true)) {
            val pos = WindowLimit.positionAtMost(15, 0, max, inv)
            val open = if (inv) max - pos else pos
            assertTrue("0~$max 반전=$inv 위치 $pos", open * 100 <= 15 * max)
        }
    }

    @Test fun `창문 - 한 칸이 15퍼센트보다 크면 열지 않는다`() {
        assertFalse(WindowLimit.opens(15, 0, 5))
        assertEquals(0, WindowLimit.positionAtMost(15, 0, 5))
        assertEquals(5, WindowLimit.positionAtMost(15, 0, 5, inverted = true))
        assertFalse("범위가 없으면", WindowLimit.opens(15, 0, 0))
        assertTrue("0~7 은 한 칸이 14.3%", WindowLimit.opens(15, 0, 7))
    }

    @Test fun `창문 - 범위 0~10 의 열림 퍼센트와 원복 오차`() {
        assertEquals(0, WindowScale.percentOf(0, 0, 10)); assertEquals(50, WindowScale.percentOf(5, 0, 10)); assertEquals(100, WindowScale.percentOf(10, 0, 10))
        assertEquals("범위의 5% 는 0.5, 최소 1", 1, VehicleActuator.toleranceInt(0, 10))
        assertEquals(5, VehicleActuator.toleranceInt(0, 100))
    }

    private fun actuator(car: VehicleActuatorTest.FakeVehicleControls, inverted: Boolean = false) =
        VehicleActuator(SignalBus(), kotlinx.coroutines.GlobalScope, car, VehicleActuatorTest.FakeNav(), hasVehicle = { true }, now = { 1000L }, dispatcher = Dispatchers.Unconfined, windowInverted = { inverted })

    @Test fun `액추에이터 - 범위 0~10 에서 창문은 1 까지만 열고 원복한다`() {
        val car = VehicleActuatorTest.FakeVehicleControls().apply { windowMinMax = 0 to 10 }
        val a = actuator(car)
        val r = runBlocking { a.run(listOf(Action.OpenWindow())) {} }
        assertTrue(r.single().ok); assertEquals(1, car.window)
        assertEquals(VehicleActuator.Snap(0, 1), a.saved.windowPos)
        // 운전자가 한 칸 더 열었다(2). 오차 1 안이라 닫는다. 세 칸 더(4)면 둔다
        car.window = 2
        runBlocking { a.run(listOf(Action.Revert)) {} }
        assertEquals(0, car.window)
        runBlocking { a.run(listOf(Action.OpenWindow())) {} }
        car.window = 4
        val back = runBlocking { a.run(listOf(Action.Revert)) {} }
        assertEquals(4, car.window); assertTrue(back.single().detail!!.contains("창문은 운전자가 바꿔서"))
    }

    @Test fun `액추에이터 - 범위 0~10 방향 반전이면 9`() {
        val car = VehicleActuatorTest.FakeVehicleControls().apply { windowMinMax = 0 to 10; window = 10 }
        runBlocking { actuator(car, inverted = true).run(listOf(Action.OpenWindow())) {} }
        assertEquals(9, car.window)
    }

    @Test fun `액추에이터 - 한 칸이 15퍼센트보다 큰 창문은 열지 않고 실패로 알린다`() {
        val car = VehicleActuatorTest.FakeVehicleControls().apply { windowMinMax = 0 to 5 }
        val a = actuator(car)
        val r = runBlocking { a.run(listOf(Action.OpenWindow())) {} }
        assertFalse(r.single().ok); assertEquals(0, car.window); assertNull(a.saved.windowPos)
        assertFalse("쓰기 호출이 없다", car.calls.any { it.startsWith("set window") })
    }

    @Test fun `액추에이터 - 설정 온도를 지금 못 읽으면 그 액션만 READ_FAILED`() {
        val car = VehicleActuatorTest.FakeVehicleControls().apply { failReads += "temp" }
        val r = runBlocking { actuator(car).run(listOf(Action.Ventilate(), Action.CoolDown())) {} }
        assertTrue("환기는 된다", r[0].ok)
        assertFalse(r[1].ok); assertEquals(FailureCode.READ_FAILED, r[1].detail)
        assertEquals("온도는 건드리지 않는다", 24f, car.temp)
    }

    // ---------- 자가 진단 분류 ----------

    @Test fun `진단 줄 종류 - 보류와 대체는 실패로 세지 않는다`() {
        val lines = listOf(
            DiagLine.ok("기어", "GEAR_PARK"), DiagLine.fail("내기순환", "x"), DiagLine.hold("설정 온도", "지금은 읽을 수 없음"),
            DiagLine.alt("트립 시간 raw", "미지원, 앱 타이머 사용"), DiagLine.section("HVAC 영역 탐색"),
        )
        assertEquals("성공 1 / 실패 1 / 보류 1 / 대체 1", DiagReport.summary(lines))
        assertEquals("보류나 대체가 없으면 전과 같은 모양", "성공 1 / 실패 1", DiagReport.summary(lines.take(2)))
        assertEquals("설정 온도: 보류 (지금은 읽을 수 없음)", lines[2].text())
        assertEquals("트립 시간 raw: 미지원, 앱 타이머 사용", lines[3].text())
        assertEquals("[HVAC 영역 탐색]", lines[4].text())
        assertFalse(lines[2].ok); assertFalse(lines[2].failed); assertFalse(lines[3].failed); assertFalse(lines[4].ok)
    }

    @Test fun `진단 - HVAC 영역 탐색 세 줄`() {
        val a = HvacAreaRule.decide(HvacProp.RECIRC, null, "응답 없음(4000ms)", null,
            listOf(read(0x75, ProbeKind.VALUE, "꺼짐(외기)"), read(0x31, ProbeKind.UNSUPPORTED, unsupported)))
        assertEquals(
            listOf("내기순환 영역: 0x75 (탐색)", "  내기순환 capability: 조회 실패: 응답 없음(4000ms)", "  내기순환 후보별 읽기: 0x75=꺼짐(외기) · 0x31=영역 미지원"),
            DiagReport.hvacAreaLines(a).map { it.text() },
        )
    }

    @Test fun `진단 - 영역을 못 찾으면 핵심 속성은 실패, 나머지는 대체`() {
        fun none(p: HvacProp) = HvacAreaRule.decide(p, emptyList(), null, null, emptyList())
        assertTrue(DiagReport.hvacAreaLines(none(HvacProp.RECIRC)).first().failed)
        assertTrue(DiagReport.hvacAreaLines(none(HvacProp.FAN)).first().failed)
        for (p in listOf(HvacProp.AUTO_RECIRC, HvacProp.TEMP, HvacProp.POWER)) assertEquals(p.label, DiagLine.Kind.ALT, DiagReport.hvacAreaLines(none(p)).first().kind)
    }

    @Test fun `진단 - 설정 온도의 PropertyNotAvailable 은 보류`() {
        val l = DiagReport.hvacReadFailure("설정 온도", HvacProp.TEMP, unavailable)
        assertEquals(DiagLine.Kind.HOLD, l.kind)
        assertTrue(l.text().startsWith("설정 온도: 보류 (지금은 읽을 수 없음"))
        assertEquals("영역 미지원 문장은 실패", DiagLine.Kind.FAIL, DiagReport.hvacReadFailure("내기순환", HvacProp.RECIRC, unsupported).kind)
        assertEquals("영역이 없는 핵심 속성", DiagLine.Kind.FAIL, DiagReport.hvacReadFailure("풍량", HvacProp.FAN, "${PleosCalls.AREA_UNSUPPORTED}: 이 차에는 풍량 영역이 없음").kind)
        assertEquals("영역이 없는 선택 속성", DiagLine.Kind.ALT, DiagReport.hvacReadFailure("공조 전원", HvacProp.POWER, "${PleosCalls.AREA_UNSUPPORTED}: 없음").kind)
    }

    @Test fun `진단 - 트립 시간 미지원은 앱 타이머로 대체`() {
        val l = DiagReport.tripFailure("ERROR_NOT_SUPPORTED_PROPERTY(60001): not supported")
        assertEquals(DiagLine.Kind.ALT, l.kind); assertEquals("트립 시간 raw: 미지원, 앱 타이머 사용", l.text())
        assertEquals("다른 실패는 실패", DiagLine.Kind.FAIL, DiagReport.tripFailure("응답 없음(4000ms)").kind)
        assertTrue(TripTimeSupport.isUnsupported("ERROR_NOT_SUPPORTED_PROPERTY(60001)")); assertFalse(TripTimeSupport.isUnsupported("ERROR_SECURITY_PERMISSION(2)"))
    }

    @Test fun `진단 - FusedLocation 은 선택 항목, 내비 위치가 있으면 대체`() {
        val l = DiagReport.fusedFallback("FUSED_LOCATION 퍼미션 거부", navHasLocation = true)
        assertEquals(DiagLine.Kind.ALT, l.kind)
        assertEquals("FusedLocation: 선택 항목, 내비 위치로 대체 (FUSED_LOCATION 퍼미션 거부)", l.text())
        assertTrue("내비 위치도 없으면 실패", DiagReport.fusedFallback("위치 없음 또는 응답 없음", navHasLocation = false).failed)
    }

    @Test fun `진단 - FUSED_LOCATION 퍼미션 거부는 대체, 다른 퍼미션 거부는 실패`() {
        val fused = DiagReport.permissionLine("pleos.car.permission.FUSED_LOCATION", false, "signature|privileged")
        assertEquals(DiagLine.Kind.ALT, fused.kind)
        assertEquals("퍼미션 FUSED_LOCATION: 거부(선택 항목, 내비 위치로 대체, 보호 수준 signature|privileged)", fused.text())
        assertEquals("퍼미션 CAR_CLIMATE: 실패 (거부, 보호 수준 dangerous)", DiagReport.permissionLine("pleos.car.permission.CAR_CLIMATE", false, "dangerous").text())
        assertTrue(DiagReport.permissionLine("pleos.car.permission.FUSED_LOCATION", true).ok)
    }

    @Test fun `진단 - 2026-11-11 실측을 새 분류로 세면 실패가 0`() {
        // 그날의 실패 7줄: FUSED_LOCATION 퍼미션, 내기순환, 자동 내기순환, 풍량(영역을 찾으면 성공), 설정 온도, 트립 시간, FusedLocation
        val lines = listOf(
            DiagReport.permissionLine("pleos.car.permission.FUSED_LOCATION", false, "signature"),
            DiagLine.ok("내기순환", "꺼짐(외기)"), DiagLine.ok("자동 내기순환", "꺼짐"), DiagLine.ok("풍량", "현재 0 / 최대 6"),
            DiagReport.hvacReadFailure("설정 온도", HvacProp.TEMP, unavailable),
            DiagReport.tripFailure("ERROR_NOT_SUPPORTED_PROPERTY(60001)"),
            DiagReport.fusedFallback("FUSED_LOCATION 퍼미션 거부", navHasLocation = true),
        )
        assertEquals("성공 3 / 실패 0 / 보류 1 / 대체 3", DiagReport.summary(lines))
    }

    @Test fun `진단 - 탑승 인원 집계 줄`() {
        // 에뮬레이터 기본값: 운전석 없음, 나머지 4석 있음. 주행 중 규칙이면 운전석을 1 로 세어 5명
        val occ = linkedMapOf<String, Boolean?>("ROW_1_LEFT" to false, "ROW_1_RIGHT" to true, "ROW_2_LEFT" to true, "ROW_2_CENTER" to true, "ROW_2_RIGHT" to true)
        val r = OccupantCounter.count(occ, driverSeat = "ROW_1_LEFT", driving = true)
        assertEquals(5, r.count)
        assertTrue(DiagReport.occupantText(r.count, r.detail).startsWith("집계 결과 5명(운전석 보정 포함)"))
        assertEquals("정차 중이면 값 그대로 4명", 4, OccupantCounter.count(occ, driverSeat = "ROW_1_LEFT", driving = false).count)
    }

    // ---------- 퍼미션 보호 수준 ----------

    @Test fun `보호 수준 글과 런타임 요청 대상`() {
        assertEquals("normal", PermissionLevel.describe(0)); assertEquals("dangerous", PermissionLevel.describe(1))
        assertEquals("signature", PermissionLevel.describe(2)); assertEquals("signature|privileged", PermissionLevel.describe(0x12))
        assertTrue(PermissionLevel.isDangerous(1)); assertFalse(PermissionLevel.isDangerous(0x12))
        val states = listOf(
            RuntimePermissions.State("pleos.car.permission.FUSED_LOCATION", granted = false, protectionLevel = 1),
            RuntimePermissions.State("pleos.car.permission.CAR_DRIVE", granted = true, protectionLevel = 1),
            RuntimePermissions.State("pleos.car.permission.TTS_SERVICE", granted = false, protectionLevel = 2),
            RuntimePermissions.State("pleos.car.permission.NAVI_ROUTE", granted = false, protectionLevel = null),
        )
        assertEquals("거부됐고 dangerous 인 것만 묻는다", listOf("pleos.car.permission.FUSED_LOCATION"), RuntimePermissions.toRequest(states))
    }

    // ---------- heading 없는 실제 위치, 홈 한 줄 ----------

    @Test fun `내비가 실제 위치를 주는 동안에는 모의 heading 을 지어내지 않는다`() {
        assertNull(HeadingFallback.mockHeading(pleosConnected = true, navHasLocation = true))
        assertEquals("모의 모드", DemoLocation.POINT.heading, HeadingFallback.mockHeading(pleosConnected = false, navHasLocation = false))
        assertEquals("연결됐지만 내비 위치가 없을 때(데모 콘솔 위치)", DemoLocation.POINT.heading, HeadingFallback.mockHeading(pleosConnected = true, navHasLocation = false))
    }

    @Test fun `주행 방향을 모르면 쉼터 카드가 그렇게 말한다`() {
        assertEquals("주행 방향을 알면 쉼터를 안내해요", noRestStopText(UiState(headingUnknown = true)))
        assertEquals("전방 80km 안에 같은 방향 쉼터 없음", noRestStopText(UiState()))
    }

    @Test fun `내기순환 영역이 없는 차에서만 홈에 한 줄`() {
        assertEquals("이 차에서는 내외기 상태를 읽을 수 없어요", hvacNotice(UiState(pleosAvailable = true, recircUnsupported = true)))
        assertNull(hvacNotice(UiState(pleosAvailable = true)))
        assertNull("모의 모드에서는 안 나온다", hvacNotice(UiState(pleosAvailable = false, recircUnsupported = true)))
    }
}

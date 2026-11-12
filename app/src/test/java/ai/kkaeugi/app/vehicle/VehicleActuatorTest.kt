package ai.kkaeugi.app.vehicle

import ai.kkaeugi.app.signals.SignalBus
import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.ActionResult
import ai.kkaeugi.engine.FanSpeedChanged
import ai.kkaeugi.engine.RecirculationChanged
import ai.kkaeugi.engine.RestStopInfo
import ai.kkaeugi.engine.Signal
import ai.kkaeugi.engine.WindowOpenChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VehicleActuator 순수 로직: 스냅샷/원복/허용 오차/읽기 실패. SDK 는 FakeVehicleControls 로 바꿔 끼운다.
 * 코루틴은 runBlocking 에서 actuator.run 을 직접 부른다(버스/디스패처 없이).
 */
class VehicleActuatorTest {

    /** 상태를 들고 있는 가짜 차량. 특정 읽기/쓰기를 실패시킬 수 있다 */
    class FakeVehicleControls : VehicleControls {
        var recirc = true
        var fan = 3
        var temp = 24f
        var window = 0
        /** 공조 전원. null 이면 전원을 주지 않는 차(읽기와 쓰기가 실패로 답한다) */
        var power: Boolean? = null
        /** 이 이름의 쓰기를 이 횟수만큼 성공이라고 답하고 무시한다(값이 안 바뀐다) */
        val ignoreWrites = mutableMapOf<String, Int>()
        /** 풍량 명령을 받으면 차가 이 값으로 둔다(자동 공조가 고치는 차) */
        var fanOverride: Int? = null
        val failReads = mutableSetOf<String>()
        val failWrites = mutableSetOf<String>()
        val calls = mutableListOf<String>()

        private fun <T> read(name: String, v: T): Outcome<T> { calls += "get $name"; return if (name in failReads) Outcome.Fail("응답 없음(4000ms)") else Outcome.Ok(v) }
        private fun <T> write(name: String, v: T, apply: () -> Unit): Outcome<T> {
            calls += "set $name=$v"
            if (name in failWrites) return Outcome.Fail("ERROR_DRIVING_RESTRICTED(3): restricted")
            val skip = ignoreWrites[name] ?: 0
            if (skip > 0) { ignoreWrites[name] = skip - 1; return Outcome.Ok(v) }
            apply(); return Outcome.Ok(v)
        }
        override suspend fun getRecirculation() = read("recirc", recirc)
        override suspend fun setRecirculation(on: Boolean) = write("recirc", on) { recirc = on }
        override suspend fun getFanSpeed() = read("fan", fan)
        override suspend fun setFanSpeed(v: Int) = write("fan", v) { fan = fanOverride?.also { fanOverride = null } ?: v }
        override suspend fun fanRange() = 0 to 8
        override suspend fun getTemperature() = read("temp", temp)
        override suspend fun setTemperature(c: Float) = write("temp", c) { temp = c }
        override suspend fun temperatureRangeC() = 16f to 30f
        override suspend fun getWindow() = read("window", window)
        override suspend fun setWindow(pos: Int) = write("window", pos) { window = pos }
        override suspend fun getHvacPower(): Outcome<Boolean> = power?.let { read("power", it) } ?: Outcome.Fail(VehicleControls.POWER_UNKNOWN)
        override suspend fun setHvacPower(on: Boolean): Outcome<Boolean> = if (power == null) Outcome.Fail(VehicleControls.POWER_UNKNOWN) else write("power", on) { power = on }
        /** 창문 위치 범위. 2026-11-11 에뮬레이터 실측은 0~10 */
        var windowMinMax = 0 to 100
        override suspend fun windowRange() = windowMinMax
    }

    class FakeNav : WaypointControls {
        val added = mutableListOf<String>()
        var fail = false
        override fun addWaypoint(lat: Double, lng: Double, name: String): Result<Unit> = if (fail) Result.failure(IllegalStateException("navi 없음")) else { added += name; Result.success(Unit) }
        override fun removeFirstWaypoint(): Result<Unit> = Result.success(Unit)
    }

    private val stop = RestStopInfo("석적", "경부선 서울방향", 12.0, 36.05, 128.41)

    /** 단조 시계(ms). 테스트가 직접 돌린다 */
    private var clockMs = 1000L
    /** 확인 사이에 기다린 시간의 합. 실제로 기다리지는 않는다 */
    private var waitedMs = 0L

    private fun actuator(car: FakeVehicleControls, nav: FakeNav = FakeNav(), onAdded: (RestStopInfo) -> Unit = {}): VehicleActuator =
        VehicleActuator(
            SignalBus(), kotlinx.coroutines.GlobalScope, car, nav, hasVehicle = { true }, onRestStopAdded = onAdded, now = { clockMs }, dispatcher = Dispatchers.Unconfined,
            wait = { waitedMs += it },
        )

    private fun run(a: VehicleActuator, vararg actions: Action): List<ActionResult> = runBlocking { a.run(actions.toList()) {} }

    @Test
    fun `환기 - 외기 전환과 풍량 +1, 스냅샷은 바꾸기 전 값`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        val r = run(a, Action.Ventilate())
        assertTrue(r.single().ok)
        assertFalse(car.recirc); assertEquals(4, car.fan)
        assertEquals(VehicleActuator.Snap(true, false), a.saved.recirculation)
        assertEquals(VehicleActuator.Snap(3, 4), a.saved.fanSpeed)
    }

    @Test
    fun `읽기 실패면 실행하지 않고 READ_FAILED`() {
        val car = FakeVehicleControls().apply { failReads += "recirc"; failReads += "window"; failReads += "temp" }
        val a = actuator(car)
        val r = run(a, Action.Ventilate(), Action.CoolDown(), Action.OpenWindow())
        assertEquals(listOf(false, false, false), r.map { it.ok })
        assertEquals(listOf("READ_FAILED", "READ_FAILED", "READ_FAILED"), r.map { it.detail })
        // set 호출이 하나도 없어야 한다
        assertTrue(car.calls.none { it.startsWith("set") })
        assertNull(a.saved.recirculation); assertNull(a.saved.windowPos); assertNull(a.saved.temperature)
    }

    @Test
    fun `원복 - 바꾼 값이 그대로면 전부 before 로`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate(), Action.CoolDown(), Action.OpenWindow())
        assertEquals(22f, car.temp); assertEquals(15, car.window)
        val r = run(a, Action.Revert)
        assertTrue(r.single().ok)
        assertTrue(car.recirc); assertEquals(3, car.fan); assertEquals(24f, car.temp); assertEquals(0, car.window)
        assertNull(a.saved.recirculation)
    }

    @Test
    fun `원복 허용 오차 - 범위의 5퍼센트(최소 1) 밖이면 운전자가 바꾼 것으로 보고 둔다`() {
        assertEquals(1, VehicleActuator.toleranceInt(0, 8))     // 0.4 → 최소 1
        assertEquals(5, VehicleActuator.toleranceInt(0, 100))
        assertEquals(2, VehicleActuator.toleranceInt(0, 30))    // 1.5 → 반올림 2
        assertEquals(0.7f, VehicleActuator.toleranceTemp(16f, 30f), 0.001f)
        assertEquals(0.5f, VehicleActuator.toleranceTemp(20f, 24f), 0.001f) // 0.2 → 최소 0.5

        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate(), Action.OpenWindow())
        // 운전자가 창문을 15 → 40 으로 더 열었고(오차 5 밖), 풍량은 4 → 5 (오차 1 안)
        car.window = 40; car.fan = 5
        val r = run(a, Action.Revert)
        assertTrue(r.single().ok)
        assertEquals("창문은 그대로", 40, car.window)
        assertEquals("풍량은 복원", 3, car.fan)
        assertTrue(r.single().detail!!.contains("창문은 운전자가 바꿔서"))
    }

    @Test
    fun `내기순환은 현재값과 무관하게 before 로 - AUTO 가 바꿔도 돌린다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())
        car.recirc = true // 공조 AUTO 가 스스로 내기순환으로 돌아간 상황
        val r = run(a, Action.Revert)
        assertTrue(r.single().ok)
        assertTrue(car.calls.contains("set recirc=true"))
        assertTrue(car.recirc)
    }

    @Test
    fun `실행 중 리스너로 운전자 조작이 보이면 내기순환은 건드리지 않는다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())
        val setsBefore = car.calls.count { it.startsWith("set recirc") }
        a.onRecirculationObserved(false) // 우리가 넣은 값 그대로 → 조작 아님
        assertFalse(a.saved.recircTouchedByDriver)
        // v0.7.4: 명령 직후 3초 안의 값은 차의 반응으로 본다. 운전자 조작은 그 뒤에 온 값만 센다(그래서 시계를 돌린다)
        clockMs += VehicleActuator.RECIRC_SETTLE_MS
        a.onRecirculationObserved(true)  // set(false) 과 다름 → 운전자가 눌렀다
        assertTrue(a.saved.recircTouchedByDriver)
        val r = run(a, Action.Revert)
        assertTrue(r.single().ok)
        assertEquals(setsBefore, car.calls.count { it.startsWith("set recirc") })
        assertTrue(r.single().detail!!.contains("내기순환은 운전자가 바꿔서"))
    }

    @Test
    fun `원복 실패는 ok=false 로 보고한다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())
        car.failWrites += "recirc"
        val r = run(a, Action.Revert)
        assertFalse(r.single().ok)
        assertEquals("DRIVING_RESTRICTED", r.single().detail)
    }

    @Test
    fun `쓰기 실패 detail 정규화와 경유지 추가 콜백`() {
        val car = FakeVehicleControls().apply { failWrites += "window" }
        var added: RestStopInfo? = null
        val a = actuator(car, onAdded = { added = it })
        val r = run(a, Action.Ventilate(), Action.AddRestStop(stop), Action.CoolDown(), Action.OpenWindow())
        assertEquals(listOf(true, true, true, false), r.map { it.ok })
        assertEquals("DRIVING_RESTRICTED", r.last().detail)
        assertEquals(stop, added)
        assertTrue(a.saved.waypointAdded)
        assertNull("실패한 창문은 스냅샷 없음", a.saved.windowPos)
    }

    @Test
    fun `원복 요청이 오면 남은 액션은 건너뛴다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        val batch = VehicleActuator.Batch().apply { cancelled = true }
        val r = runBlocking { a.run(listOf(Action.Ventilate(), Action.CoolDown()), batch) {} }
        assertTrue(r.isEmpty())
        assertTrue(car.calls.isEmpty())
    }

    @Test
    fun `두 번 바꿔도 최초 값으로 돌아간다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())   // fan 3→4
        run(a, Action.Ventilate())   // fan 4→5, 스냅샷은 3→4 유지
        assertEquals(5, car.fan)
        assertEquals(VehicleActuator.Snap(3, 4), a.saved.fanSpeed)
        // 현재 5 는 set(4) 의 오차 1 안 → 3 으로 복원
        run(a, Action.Revert)
        assertEquals(3, car.fan)
    }

    // ---------- 원복 없는 실행: 스냅샷 폐기 ----------

    @Test
    fun `원복 없이 끝난 환기 - 스냅샷을 버리면 다음 원복이 내기순환과 풍량을 되돌리지 않는다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate(), Action.OpenWindow())
        a.discardSnapshots(listOf(Action.Ventilate()))
        assertNull(a.saved.recirculation); assertNull(a.saved.fanSpeed)
        val r = run(a, Action.Revert)
        assertTrue(r.single().ok)
        assertFalse("외기 그대로", car.recirc)
        assertEquals("풍량 그대로", 4, car.fan)
        assertEquals("그 실행에 없던 창문은 원복", 0, car.window)
    }

    @Test
    fun `스냅샷 폐기는 그 실행의 액션 종류만 - 온도와 창문도 같은 규칙`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate(), Action.CoolDown(), Action.OpenWindow())
        a.discardSnapshots(listOf(Action.CoolDown(), Action.OpenWindow()))
        assertNull(a.saved.temperature); assertNull(a.saved.windowPos)
        assertEquals(VehicleActuator.Snap(true, false), a.saved.recirculation)
        run(a, Action.Revert)
        assertTrue("환기는 원복", car.recirc)
        assertEquals("온도는 그대로", 22f, car.temp)
        assertEquals("창문은 그대로", 15, car.window)
    }

    @Test
    fun `유지한 뒤의 졸음 개입 - 원복은 풍량만 유지하던 값으로, 내외기는 외기 그대로`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())                     // 바꿔 둘까요? 응: 외기, 풍량 3→4
        a.discardSnapshots(listOf(Action.Ventilate()))
        run(a, Action.Ventilate(), Action.CoolDown())  // 졸음 개입: 이미 외기, 풍량 4→5
        assertNull("이미 외기면 내외기 스냅샷이 없다", a.saved.recirculation)
        assertEquals(VehicleActuator.Snap(4, 5), a.saved.fanSpeed)
        run(a, Action.Revert)
        assertFalse(car.recirc)
        assertEquals(4, car.fan)
        assertEquals(24f, car.temp)
    }

    // ---------- 이미 외기일 때의 환기 ----------

    @Test
    fun `이미 외기면 내외기는 건드리지 않고 풍량만 올린다`() {
        val car = FakeVehicleControls().apply { recirc = false }
        val a = actuator(car)
        val r = run(a, Action.Ventilate(fanDelta = 2))
        assertTrue(r.single().ok)
        assertTrue("내외기 쓰기 호출이 없어야 한다", car.calls.none { it.startsWith("set recirc") })
        assertEquals(5, car.fan)
        assertNull(a.saved.recirculation)
        assertEquals(VehicleActuator.Snap(3, 5), a.saved.fanSpeed)
        assertTrue(r.single().detail!!.contains("이미 외기"))
    }

    @Test
    fun `풍량은 최대 단계까지만 올린다`() {
        val car = FakeVehicleControls().apply { recirc = false; fan = 7 }
        val a = actuator(car)
        run(a, Action.Ventilate(fanDelta = 2))
        assertEquals(8, car.fan)
        assertEquals(VehicleActuator.Snap(7, 8), a.saved.fanSpeed)
        val again = run(a, Action.Ventilate(fanDelta = 2))
        assertTrue(again.single().ok)
        assertEquals(8, car.fan)
        assertTrue(again.single().detail!!.contains("풍량 이미 최대"))
    }

    // ---------- 창문 방향 반전 ----------

    @Test
    fun `창문 방향 반전 - 15퍼센트 열기가 max 쪽에서 내려온다`() {
        val car = FakeVehicleControls().apply { window = 100 }  // 이 차는 100 이 닫힘
        val a = VehicleActuator(SignalBus(), kotlinx.coroutines.GlobalScope, car, FakeNav(), hasVehicle = { true }, now = { 1000L }, dispatcher = Dispatchers.Unconfined, windowInverted = { true })
        val r = run(a, Action.OpenWindow())
        assertTrue(r.single().ok)
        assertEquals(85, car.window)
        run(a, Action.Revert)
        assertEquals(100, car.window)
    }

    @Test
    fun `경유지 이름은 쉼터 표시 이름`() {
        val nav = FakeNav()
        val a = actuator(FakeVehicleControls(), nav)
        val r = run(a, Action.AddRestStop(stop))
        assertEquals(listOf("석적 졸음쉼터"), nav.added)
        assertTrue(r.single().detail!!.contains("석적 졸음쉼터"))
    }

    // ---------- 모의 모드: 모의 차량의 값이 실제로 바뀌고 신호가 나간다 ----------

    private class MockRig(inverted: Boolean = false) {
        val signals = mutableListOf<Signal>()
        val calls = mutableListOf<String>()
        val car = MockCar(emit = { signals += it }, now = { 5_000L }, windowInverted = { inverted }, onControlCall = { calls += it })
        val actuator = VehicleActuator(
            SignalBus(), kotlinx.coroutines.GlobalScope, FakeVehicleControls(), FakeNav(), hasVehicle = { false },
            now = { 5_000L }, dispatcher = Dispatchers.Unconfined, mockControls = car, windowInverted = { inverted }, onMockCall = { calls += it },
        )
        fun run(vararg actions: Action): List<ActionResult> = runBlocking { actuator.run(actions.toList()) {} }
    }

    @Test
    fun `모의 환기 - 내외기와 풍량이 바뀌고 해당 신호가 나간다`() {
        val rig = MockRig()
        rig.car.userRecirculation(true)
        rig.signals.clear()
        val r = rig.run(Action.Ventilate())
        assertTrue(r.single().ok)
        assertTrue(r.single().detail!!.startsWith("mock: "))
        assertFalse(rig.car.recirculating)
        assertEquals(4, rig.car.fanLevel)
        assertEquals(listOf(RecirculationChanged(5_000L, false), FanSpeedChanged(5_000L, 4, 8)), rig.signals)
        assertEquals(listOf("setHvacRecirculation(false)", "setHvacFanSpeed(4)"), rig.calls)
    }

    @Test
    fun `모의 원복 - 내기순환과 풍량이 돌아오고 신호가 나간다`() {
        val rig = MockRig()
        rig.car.userRecirculation(true)
        rig.run(Action.Ventilate())
        rig.signals.clear()
        rig.run(Action.Revert)
        assertTrue(rig.car.recirculating)
        assertEquals(3, rig.car.fanLevel)
        assertEquals(listOf(RecirculationChanged(5_000L, true), FanSpeedChanged(5_000L, 3, 8)), rig.signals)
    }

    @Test
    fun `모의 졸음 개입 - 창문 15퍼센트 신호와 원복`() {
        val rig = MockRig()
        rig.car.userRecirculation(true)
        rig.signals.clear()
        val r = rig.run(Action.Ventilate(), Action.AddRestStop(stop), Action.CoolDown(), Action.OpenWindow())
        assertEquals(listOf(true, true, true, true), r.map { it.ok })
        assertEquals(22f, rig.car.temperatureC)
        assertEquals(15, rig.car.windowPercent)
        assertTrue(rig.signals.contains(WindowOpenChanged(5_000L, 15)))
        assertTrue("모의 경유지 호출도 증거 띠로", rig.calls.contains("addWaypoint(석적 졸음쉼터)"))
        rig.run(Action.Revert)
        assertEquals(0, rig.car.windowPercent)
        assertEquals(24f, rig.car.temperatureC)
        assertEquals(WindowOpenChanged(5_000L, 0), rig.signals.last())
    }

    @Test
    fun `모의 차량 - 공조 전원이 꺼지면 풍량 0 으로 나간다`() {
        val rig = MockRig()
        rig.car.userHvacPower(false)
        assertEquals(FanSpeedChanged(5_000L, 0, 8), rig.signals.last())
        rig.car.userFan(5)
        assertEquals("꺼져 있는 동안은 계속 0", FanSpeedChanged(5_000L, 0, 8), rig.signals.last())
        rig.car.userHvacPower(true)
        assertEquals(FanSpeedChanged(5_000L, 5, 8), rig.signals.last())
    }

    @Test
    fun `모의 차량 - 창문 방향 반전이면 위치가 반대로 저장되고 퍼센트는 같다`() {
        val rig = MockRig(inverted = true)
        rig.car.userWindowPercent(0)
        assertEquals(100, rig.car.windowPosition)
        rig.run(Action.OpenWindow())
        assertEquals(85, rig.car.windowPosition)
        assertEquals(15, rig.car.windowPercent)
        assertEquals(WindowOpenChanged(5_000L, 15), rig.signals.last())
    }

    @Test
    fun `모의 차량이 없으면 예전처럼 아무것도 바꾸지 않고 ok 만`() {
        val car = FakeVehicleControls()
        val a = VehicleActuator(SignalBus(), kotlinx.coroutines.GlobalScope, car, FakeNav(), hasVehicle = { false }, now = { 1000L }, dispatcher = Dispatchers.Unconfined)
        val r = run(a, Action.Ventilate())
        assertTrue(r.single().ok)
        assertEquals("mock", r.single().detail)
        assertTrue(car.calls.isEmpty())
    }

    // ---------- v0.7.4: 원복이 안 되던 경로(명령 직후의 값, 원복 확인, 공조 전원) ----------

    @Test
    fun `명령 직후 3초 안에 온 내기순환 값은 운전자 조작으로 세지 않고 원복한다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())
        // 명령 0.4초 뒤 리스너가 앞의 값(내기)을 한 번 더 알렸다. v0.7.3 은 이것을 운전자 조작으로 보고 원복 때 외기로 남겨 두었다
        clockMs += 400
        a.onRecirculationObserved(true)
        assertFalse(a.saved.recircTouchedByDriver)
        clockMs += 120_000
        val r = run(a, Action.Revert)
        assertTrue(r.single().ok)
        assertTrue("내기순환으로 돌아온다", car.recirc)
        assertTrue(r.single().detail!!.contains("내기순환 ON 복원"))
    }

    @Test
    fun `다시 환기를 명령하면 앞서 본 운전자 조작 표시는 지운다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())
        clockMs += 10_000
        car.recirc = true
        a.onRecirculationObserved(true)   // 운전자가 내기순환으로 바꿨다
        assertTrue(a.saved.recircTouchedByDriver)
        run(a, Action.Ventilate())        // 그 뒤 다시 동의해서 외기로 바꿨다
        assertFalse(a.saved.recircTouchedByDriver)
        clockMs += 120_000
        run(a, Action.Revert)
        assertTrue("둘째 환기의 원복은 내기순환으로 돌린다", car.recirc)
    }

    @Test
    fun `원복 명령이 한 번에 안 먹으면 3초까지 확인한 뒤 한 번 다시 보낸다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())
        car.ignoreWrites["fan"] = 1   // 원복의 첫 풍량 쓰기는 성공이라 답하지만 값이 그대로다
        waitedMs = 0
        val r = run(a, Action.Revert)
        assertTrue(r.single().ok)
        assertEquals(3, car.fan)
        assertEquals(2, car.calls.count { it == "set fan=3" })
        assertTrue(r.single().detail, r.single().detail!!.contains("풍량 3 복원(한 번 다시 보냄)"))
        assertEquals("첫 확인을 3초까지 기다렸다", Confirm.TIMEOUT_MS, waitedMs)
    }

    @Test
    fun `원복을 다시 보내도 값이 안 돌아오면 ok=false 로 알린다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())
        car.ignoreWrites["fan"] = 2
        val r = run(a, Action.Revert)
        assertFalse(r.single().ok)
        assertEquals(4, car.fan)
        assertTrue(r.single().detail, r.single().detail!!.contains("풍량 복원 확인 실패"))
        assertTrue("내기순환은 돌아왔다", car.recirc)
    }

    @Test
    fun `바로 돌아오는 차에서는 원복 확인에 기다리는 시간이 없다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate(), Action.CoolDown(), Action.OpenWindow())
        waitedMs = 0
        assertTrue(run(a, Action.Revert).single().ok)
        assertEquals(0L, waitedMs)
    }

    @Test
    fun `읽을 수 없는 값은 확인을 기다리지 않고 명령 결과만으로 본다`() {
        val car = FakeVehicleControls()
        val a = actuator(car)
        run(a, Action.Ventilate())
        car.failReads += "fan"
        waitedMs = 0
        val r = run(a, Action.Revert)
        assertTrue(r.single().ok)
        assertEquals(3, car.fan)
        assertTrue(r.single().detail, r.single().detail!!.contains("풍량 3 복원(확인은 못 함)"))
        assertEquals(0L, waitedMs)
    }

    @Test
    fun `명령 직후 차가 풍량을 다른 값으로 두어도 운전자 조작으로 보지 않고 원복한다`() {
        val car = FakeVehicleControls().apply { fanOverride = 6 }   // 4 를 명령했는데 차가 6 으로 뒀다
        val a = actuator(car)
        val r = run(a, Action.Ventilate())
        assertEquals(6, car.fan)
        assertEquals(VehicleActuator.Snap(3, 4), a.saved.fanSpeed)
        assertEquals(6, a.saved.fanSettled)
        assertTrue(r.single().detail, r.single().detail!!.contains("풍량 3→4 명령, 차는 6"))
        // v0.7.3 은 6 이 4 의 허용 오차(1) 밖이라 "운전자가 바꿔서 그대로 둠"으로 남겼다
        val back = run(a, Action.Revert)
        assertTrue(back.single().ok)
        assertEquals(3, car.fan)
        // 그 뒤 운전자가 정말 바꾼 값(8)은 그대로 둔다
        car.fanOverride = 6
        run(a, Action.Ventilate())
        car.fan = 8
        assertTrue(run(a, Action.Revert).single().detail!!.contains("풍량은 운전자가 바꿔서"))
        assertEquals(8, car.fan)
    }

    @Test
    fun `공조 전원이 꺼져 있으면 켜고 환기하고 원복 때 맨 끝에 끈다`() {
        val car = FakeVehicleControls().apply { power = false }
        val a = actuator(car)
        val r = run(a, Action.Ventilate())
        assertTrue(r.single().ok)
        assertEquals(true, car.power)
        assertEquals(VehicleActuator.Snap(false, true), a.saved.power)
        assertTrue(r.single().detail, r.single().detail!!.startsWith("공조 전원 켬, 외기 전환"))
        assertEquals("전원을 먼저 켠다", "set power=true", car.calls.first { it.startsWith("set") })
        val back = run(a, Action.Revert)
        assertTrue(back.single().ok)
        assertEquals(false, car.power); assertTrue(car.recirc); assertEquals(3, car.fan)
        assertEquals("전원은 맨 끝에 끈다", "set power=false", car.calls.last { it.startsWith("set") })
        assertTrue(back.single().detail, back.single().detail!!.endsWith("공조 전원 끔"))
        assertNull(a.saved.power)
    }

    @Test
    fun `전원이 켜져 있었거나 전원을 주지 않는 차에서는 전원을 건드리지 않는다`() {
        for (p in listOf(true, null)) {
            val car = FakeVehicleControls().apply { power = p }
            val a = actuator(car)
            run(a, Action.Ventilate()); run(a, Action.Revert)
            assertTrue("power=$p", car.calls.none { it.startsWith("set power") })
            assertEquals(p, car.power)
        }
    }

    @Test
    fun `운전자가 그 사이 풍량을 바꿨으면 전원은 켠 채로 둔다`() {
        val car = FakeVehicleControls().apply { power = false }
        val a = actuator(car)
        run(a, Action.Ventilate())
        car.fan = 7   // 운전자가 풍량을 크게 올렸다
        val r = run(a, Action.Revert)
        assertTrue(r.single().ok)
        assertEquals(true, car.power)
        assertEquals(7, car.fan)
        assertTrue(r.single().detail, r.single().detail!!.contains("공조 전원은 운전자가 공조를 만져서 켠 채로 둠"))
    }

    @Test
    fun `바깥 공기로 두기로 한 실행은 전원 스냅샷도 버린다`() {
        val car = FakeVehicleControls().apply { power = false }
        val a = actuator(car)
        run(a, Action.Ventilate())
        a.discardSnapshots(listOf(Action.Ventilate()))
        assertNull(a.saved.power)
        run(a, Action.Revert)
        assertEquals("전원은 켠 채로", true, car.power)
        assertFalse(car.recirc)
    }

    @Test
    fun `전원을 켰는데도 내외기를 못 읽으면 실행하지 않고 뒤따르는 원복이 전원을 끈다`() {
        val car = FakeVehicleControls().apply { power = false; failReads += "recirc" }
        val a = actuator(car)
        val r = run(a, Action.Ventilate())
        assertEquals("READ_FAILED", r.single().detail)
        assertEquals(true, car.power)
        run(a, Action.Revert)   // 엔진은 제어가 전부 실패해도 원복을 낸다
        assertEquals(false, car.power)
    }
}

package ai.kkaeugi.app.vehicle

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * adb 로 부르는 제어 검증(v0.7.4): 항목마다 읽기 → 명령 → 확인(최대 3초) → 2초 유지 → 원복 → 확인(최대 3초, 안 되면 한 번 다시).
 * 공조 전원은 처음에 읽어 두고 끝에 처음 상태로 돌린다. 끝나면 처음에 읽은 값과 견줘 다른 것을 한 번 더 돌린다.
 * 기다리는 시간은 가짜 시계로 센다(실제로 기다리지 않는다)
 */
class ControlVerifierTest {

    /** 기다린 시간을 세는 가짜 시계 */
    private class Clock {
        var waited = 0L
        val waits = mutableListOf<Long>()
        val wait: suspend (Long) -> Unit = { waited += it; waits += it }
    }

    private fun car(calls: MutableList<String> = mutableListOf()) = MockCar(emit = {}, now = { 0L }, onControlCall = { calls += it })
    private fun verifier(c: VehicleControls, inverted: Boolean = false, clock: Clock = Clock(), lines: MutableList<String> = mutableListOf()) =
        ControlVerifier(c, windowInverted = { inverted }, wait = clock.wait, onLine = { lines += it })
    private fun run(c: VehicleControls, what: String, inverted: Boolean = false, clock: Clock = Clock()): ControlVerifier.Result =
        runBlocking { verifier(c, inverted, clock).run(what) }!!

    /**
     * 실제 차처럼 굴 수 있는 가짜 차. 값이 늦게 바뀌거나, 쓰기를 받고도 무시하거나,
     * 전원이 꺼진 채로 명령을 받으면 전원을 스스로 켜거나, 전원이 켜질 때 내외기를 외기로 바꾼다
     */
    private class QuirkyCar(private val clock: Clock) : VehicleControls {
        var power: Boolean? = true
        var recirc = true
        var fan = 3
        var window = 0
        /** 풍량이 명령을 받은 뒤 이만큼 지나야 바뀐다 */
        var fanDelayMs = 0L
        private var fanDue: Pair<Long, Int>? = null
        /** 이 횟수만큼 풍량 쓰기를 성공이라고 답하고 무시한다 */
        var ignoreFanWrites = 0
        var powerWritable = true
        var fanCommandTurnsPowerOn = false
        var powerOnSetsFreshAir = false
        /** 전원이 꺼져 있으면 내외기와 풍량을 읽을 수 없다(AAOS 기본 VHAL 의 답) */
        var unreadableWhenOff = false
        val calls = mutableListOf<String>()

        private fun settle() { fanDue?.let { (at, v) -> if (clock.waited >= at) { fan = v; fanDue = null } } }
        private fun off(): Outcome.Fail? = if (unreadableWhenOff && power == false) Outcome.Fail("PropertyNotAvailableException: not available") else null
        private fun turnOn() { if (power == false) { power = true; if (powerOnSetsFreshAir) recirc = false } }

        override suspend fun getRecirculation(): Outcome<Boolean> = off() ?: Outcome.Ok(recirc)
        override suspend fun setRecirculation(on: Boolean): Outcome<Boolean> { calls += "recirc=$on"; off()?.let { return it }; recirc = on; return Outcome.Ok(on) }
        override suspend fun getFanSpeed(): Outcome<Int> { settle(); return off() ?: Outcome.Ok(fan) }
        override suspend fun setFanSpeed(v: Int): Outcome<Int> {
            calls += "fan=$v"
            if (fanCommandTurnsPowerOn) turnOn()
            off()?.let { return it }
            if (ignoreFanWrites > 0) { ignoreFanWrites--; return Outcome.Ok(v) }
            if (fanDelayMs > 0) fanDue = (clock.waited + fanDelayMs) to v else fan = v
            return Outcome.Ok(v)
        }
        override suspend fun fanRange(): Pair<Int, Int> = 0 to 8
        override suspend fun getTemperature(): Outcome<Float> = Outcome.Ok(24f)
        override suspend fun setTemperature(c: Float): Outcome<Float> = Outcome.Ok(c)
        override suspend fun temperatureRangeC(): Pair<Float, Float> = 16f to 30f
        override suspend fun getWindow(): Outcome<Int> = Outcome.Ok(window)
        override suspend fun setWindow(pos: Int): Outcome<Int> { calls += "window=$pos"; window = pos; return Outcome.Ok(pos) }
        override suspend fun windowRange(): Pair<Int, Int> = 0 to 10
        override suspend fun getHvacPower(): Outcome<Boolean> = power?.let { Outcome.Ok(it) } ?: Outcome.Fail(VehicleControls.POWER_UNKNOWN)
        override suspend fun setHvacPower(on: Boolean): Outcome<Boolean> {
            calls += "power=$on"
            if (power == null) return Outcome.Fail(VehicleControls.POWER_UNKNOWN)
            if (!powerWritable) return Outcome.Fail("ERROR_SECURITY_PERMISSION(2)")
            if (on) turnOn() else power = false
            return Outcome.Ok(on)
        }
    }

    @Test fun `셋을 차례로 바꿨다가 처음 값으로 돌려놓는다`() {
        val calls = mutableListOf<String>()
        val c = car(calls)
        c.userRecirculation(true); c.userFan(3, 8)
        val clock = Clock()
        val r = run(c, ControlVerifier.ALL, clock = clock)
        assertEquals(listOf("내외기", "풍량", "창문"), r.steps.map { it.name })
        assertTrue(r.steps.toString(), r.steps.all { it.ok })
        assertEquals("[내외기] 전 내기 → 명령 외기 → 읽음 외기(0.5초) → 원복 내기 → 읽음 내기(0.5초) : 성공", r.steps[0].line())
        assertEquals("[풍량] 전 3 → 명령 4 → 읽음 4(0.5초) → 원복 3 → 읽음 3(0.5초) : 성공", r.steps[1].line())
        assertEquals("[창문] 전 0 → 명령 10 → 읽음 10(0.5초) → 원복 0 → 읽음 0(0.5초) : 성공", r.steps[2].line())
        // 차는 처음 그대로다
        assertTrue(c.recirculating); assertEquals(3, c.fanLevel); assertEquals(0, c.windowPosition); assertTrue(c.hvacPower)
        // 전원이 켜져 있었으니 전원은 건드리지 않는다
        assertEquals(
            listOf("setHvacRecirculation(false)", "setHvacRecirculation(true)", "setHvacFanSpeed(4)", "setHvacFanSpeed(3)", "setWindowPosition(10)", "setWindowPosition(0)"),
            calls,
        )
        assertEquals("성공 3 / 실패 0", ControlVerifier.summary(r.steps))
        assertEquals(true, r.restored)
        assertEquals("[처음 상태] 공조 전원 켜짐, 내외기 내기, 풍량 3, 창문 0", r.lines.first())
        assertEquals("[끝 상태] 공조 전원 켜짐, 내외기 내기, 풍량 3, 창문 0 : 처음과 같음", r.lines.last())
        // 항목마다 확인 0.5초, 유지 2초, 원복 확인 0.5초
        assertEquals(listOf(500L, 2000L, 500L, 500L, 2000L, 500L, 500L, 2000L, 500L), clock.waits)
    }

    @Test fun `하나씩도 돌릴 수 있고 모르는 값이면 아무것도 하지 않는다`() {
        val calls = mutableListOf<String>()
        val c = car(calls)
        assertEquals(listOf("내외기"), run(c, "fresh").steps.map { it.name })
        assertEquals(listOf("풍량"), run(c, "fan").steps.map { it.name })
        assertEquals(listOf("창문"), run(c, "window").steps.map { it.name })
        calls.clear()
        assertNull(runBlocking { verifier(c).run("door") })
        assertTrue("모르는 값이면 차를 건드리지 않는다", calls.isEmpty())
        assertEquals(listOf("fresh", "fan", "window", "all"), ControlVerifier.KINDS)
    }

    @Test fun `바깥 공기였으면 내기순환으로 바꿨다가 돌리고 최대 풍량이면 한 단 내린다`() {
        val c = car()
        c.userRecirculation(false); c.userFan(8, 8)
        val (fresh, fan) = run(c, "all").steps
        assertEquals("[내외기] 전 외기 → 명령 내기 → 읽음 내기(0.5초) → 원복 외기 → 읽음 외기(0.5초) : 성공", fresh.line())
        assertEquals("[풍량] 전 8 → 명령 7 → 읽음 7(0.5초) → 원복 8 → 읽음 8(0.5초) : 성공", fan.line())
    }

    @Test fun `창문은 10퍼센트를 넘지 않게 열고 범위가 거칠면 건너뛴다`() {
        // 에뮬레이터 실측 범위 0~10: 10% 는 1
        val emu = object : VehicleControls by car() {
            var pos = 0
            override suspend fun getWindow(): Outcome<Int> = Outcome.Ok(pos)
            override suspend fun setWindow(pos: Int): Outcome<Int> { this.pos = pos; return Outcome.Ok(pos) }
            override suspend fun windowRange(): Pair<Int, Int> = 0 to 10
        }
        assertEquals("[창문] 전 0 → 명령 1 → 읽음 1(0.5초) → 원복 0 → 읽음 0(0.5초) : 성공", run(emu, "window").steps.single().line())
        // 방향 반전(max 가 닫힘)
        emu.pos = 10
        assertEquals("[창문] 전 10 → 명령 9 → 읽음 9(0.5초) → 원복 10 → 읽음 10(0.5초) : 성공", run(emu, "window", inverted = true).steps.single().line())
        // 범위 0~5 는 한 칸이 20% 라 열지 않는다
        val coarse = object : VehicleControls by car() { override suspend fun windowRange(): Pair<Int, Int> = 0 to 5 }
        val skipped = run(coarse, "window").steps.single()
        assertTrue(skipped.skipped); assertFalse(skipped.ok)
        assertEquals("[창문] 건너뜀 (범위 0~5 는 한 칸이 10% 보다 커서 열지 않음)", skipped.line())
        assertEquals("성공 0 / 실패 0 / 건너뜀 1", ControlVerifier.summary(listOf(skipped)))
    }

    @Test fun `쓰기가 막히면 사유를 적고 값이 그대로면 원복 명령을 보내지 않고 다음 항목으로 간다`() {
        val calls = mutableListOf<String>()
        val base = car(calls)
        base.userRecirculation(true)
        val blocked = object : VehicleControls by base {
            override suspend fun setRecirculation(on: Boolean): Outcome<Boolean> { calls += "setHvacRecirculation($on)"; return Outcome.Fail("DRIVING_RESTRICTED") }
        }
        val clock = Clock()
        val r = run(blocked, "all", clock = clock)
        val (fresh, fan) = r.steps
        assertFalse(fresh.ok)
        assertEquals("[내외기] 전 내기 → 명령 외기 → 읽음 내기(0.5초) → 원복 내기 → 읽음 내기(0.0초) : 실패 (쓰기 실패: DRIVING_RESTRICTED; 값이 그대로라 원복 명령은 보내지 않음)", fresh.line())
        assertEquals("거절된 쓰기는 한 번만 보낸다", 1, calls.count { it.startsWith("setHvacRecirculation") })
        // 거절된 항목은 3초를 다 기다리지 않는다(한 번만 읽는다)
        assertEquals(500L, clock.waits.first())
        assertTrue(fan.ok)
        assertEquals("성공 2 / 실패 1", ControlVerifier.summary(r.steps))
        assertEquals("값은 안 바뀌었으니 끝 상태는 처음과 같다", true, r.restored)
    }

    @Test fun `값을 못 읽거나 예외가 나도 한 줄로 남긴다`() {
        val unreadable = object : VehicleControls by car() { override suspend fun getFanSpeed(): Outcome<Int> = Outcome.Fail("ERROR_SECURITY_PERMISSION(2)") }
        assertEquals("[풍량] 전 못 읽음 : 실패 (명령 전 값을 못 읽음: ERROR_SECURITY_PERMISSION(2))", run(unreadable, "fan").steps.single().line())
        val throwing = object : VehicleControls by car() { override suspend fun getRecirculation(): Outcome<Boolean> = throw IllegalStateException("서비스 끊김") }
        val step = run(throwing, "fresh").steps.single()
        assertFalse(step.ok)
        assertTrue(step.line(), step.line().endsWith("실패 (예외 IllegalStateException: 서비스 끊김)"))
    }

    @Test fun `쓰기는 성공이라는데 값이 안 바뀌는 차는 3초를 기다린 뒤 실패로 적는다`() {
        val clock = Clock()
        val c = QuirkyCar(clock).apply { ignoreFanWrites = 1 }
        val step = run(c, "fan", clock = clock).steps.single()
        assertEquals("[풍량] 전 3 → 명령 4 → 읽음 3(3.0초) → 원복 3 → 읽음 3(0.5초) : 실패 (3.0초 안에 명령한 값이 되지 않음)", step.line())
        // 명령이 안 먹었으면 2초 유지는 하지 않는다
        assertFalse(clock.waits.contains(ControlVerifier.HOLD_MS))
    }

    @Test fun `값이 늦게 바뀌는 차는 바뀔 때까지 다시 읽는다 - 한 번 읽고 실패로 단정하지 않는다`() {
        val clock = Clock()
        val c = QuirkyCar(clock).apply { fanDelayMs = 1_200L }
        val step = run(c, "fan", clock = clock).steps.single()
        // v0.7.3 은 0.6초 뒤 한 번 읽고 "읽은 값이 명령과 다름"으로 적었다
        assertEquals("[풍량] 전 3 → 명령 4 → 읽음 4(1.5초) → 원복 3 → 읽음 3(1.5초) : 성공", step.line())
        assertEquals(3, c.fan)
    }

    @Test fun `원복이 한 번에 안 먹으면 한 번 다시 보낸다`() {
        val clock = Clock()
        val c = QuirkyCar(clock)
        // 첫 쓰기(명령)는 먹고, 둘째 쓰기(원복)는 무시되게 한다
        val verifier = ControlVerifier(object : VehicleControls by c {
            var writes = 0
            override suspend fun setFanSpeed(v: Int): Outcome<Int> { writes++; if (writes == 2) c.ignoreFanWrites = 1; return c.setFanSpeed(v) }
        }, wait = clock.wait)
        val step = runBlocking { verifier.run("fan") }!!.steps.single()
        assertEquals("[풍량] 전 3 → 명령 4 → 읽음 4(0.5초) → 원복 3 → 읽음 3(3.5초) : 성공 (원복을 한 번 다시 보냄)", step.line())
        assertEquals(listOf("fan=4", "fan=3", "fan=3"), c.calls)
        assertEquals(3, c.fan)
    }

    @Test fun `원복이 끝내 안 되면 실패로 적고 끝 상태가 처음과 다르다고 알린다`() {
        val clock = Clock()
        val c = QuirkyCar(clock)
        val stuckAfterFirst = object : VehicleControls by c {
            var writes = 0
            override suspend fun setFanSpeed(v: Int): Outcome<Int> { writes++; if (writes >= 2) c.ignoreFanWrites = 1; return c.setFanSpeed(v) }
        }
        val r = runBlocking { ControlVerifier(stuckAfterFirst, wait = clock.wait).run("fan") }!!
        val step = r.steps.single()
        assertFalse(step.ok)
        assertEquals("[풍량] 전 3 → 명령 4 → 읽음 4(0.5초) → 원복 3 → 읽음 4(6.0초) : 실패 (원복을 두 번 보냈지만 처음 값이 되지 않음)", step.line())
        assertEquals(false, r.restored)
        assertTrue(r.closing.toString(), r.closing.single().startsWith("[마무리] 풍량: 처음 3, 지금 4 → 처음 값으로 다시 돌림"))
        assertTrue(r.lines.last(), r.lines.last().endsWith(": 처음과 다름"))
        assertEquals("성공 0 / 실패 1 · 끝 상태는 처음과 다름", ControlVerifier.summary(r))
    }

    // ---------- 공조 전원 ----------

    @Test fun `공조 전원이 꺼져 있으면 켜고 시작해서 맨 끝에 다시 끈다`() {
        val calls = mutableListOf<String>()
        val c = car(calls)
        c.userRecirculation(true); c.userHvacPower(false)
        val r = run(c, "all")
        assertEquals(listOf("공조 전원", "내외기", "풍량", "창문"), r.steps.map { it.name })
        assertEquals("[공조 전원] 전 꺼짐 → 명령 켜짐 → 읽음 켜짐(0.5초) → 원복 꺼짐 → 읽음 꺼짐(0.5초) : 성공", r.steps[0].line())
        assertEquals("setHvacPower(true)", calls.first())
        assertEquals("전원은 다른 값을 다 돌린 뒤 맨 끝에 끈다", "setHvacPower(false)", calls.last())
        assertFalse(c.hvacPower); assertTrue(c.recirculating); assertEquals(3, c.fanLevel)
        assertEquals(true, r.restored)
        assertEquals("성공 4 / 실패 0 · 끝 상태는 처음과 같음", ControlVerifier.summary(r))
        // 창문만 볼 때는 전원을 건드리지 않는다
        calls.clear()
        assertEquals(listOf("창문"), run(c, "window").steps.map { it.name })
        assertTrue(calls.none { it.startsWith("setHvacPower") })
    }

    @Test fun `전원이 켜지면서 내외기가 외기로 바뀌는 차 - 마무리에서 처음 값으로 돌리고 전원을 끈다`() {
        // 2026-11-12 에뮬레이터 실측과 같은 꼴: 실행 전 내기순환 켜짐, 공조 꺼짐. v0.7.3 은 끝난 뒤 전원 켜짐, 외기로 남았다
        val clock = Clock()
        val c = QuirkyCar(clock).apply { power = false; recirc = true; powerOnSetsFreshAir = true }
        val r = run(c, "all", clock = clock)
        assertFalse("전원은 처음처럼 꺼짐", c.power!!)
        assertTrue("내외기는 처음처럼 내기", c.recirc)
        assertEquals(3, c.fan); assertEquals(0, c.window)
        // 내외기 항목은 전원을 켠 뒤의 값(외기)에서 시작한다. 처음 값(내기)으로는 마무리가 돌린다
        assertEquals("[내외기] 전 외기 → 명령 내기 → 읽음 내기(0.5초) → 원복 외기 → 읽음 외기(0.5초) : 성공", r.steps[1].line())
        assertEquals("[마무리] 내외기: 처음 내기, 지금 외기 → 처음 값으로 다시 돌림 → 읽음 내기(0.5초) : 성공", r.closing.single())
        assertTrue("내외기를 돌린 뒤에 전원을 끈다", c.calls.indexOf("power=false") > c.calls.lastIndexOf("recirc=true"))
        assertEquals(true, r.restored)
    }

    @Test fun `전원이 꺼지면 내외기를 못 읽는 차 - 전원을 켜서 보고 끝나면 다시 끈다`() {
        val clock = Clock()
        val c = QuirkyCar(clock).apply { power = false; unreadableWhenOff = true }
        val r = run(c, "fresh", clock = clock)
        assertEquals("[처음 상태] 공조 전원 꺼짐, 내외기 못 읽음, 풍량 못 읽음, 창문 0", r.lines.first())
        assertTrue(r.steps.toString(), r.steps.all { it.ok })
        assertEquals("[내외기] 전 내기 → 명령 외기 → 읽음 외기(0.5초) → 원복 내기 → 읽음 내기(0.5초) : 성공", r.steps[1].line())
        assertFalse(c.power!!); assertTrue(c.recirc)
        // 처음에 못 읽은 값은 견주지 않는다. 읽힌 값(전원, 창문)은 처음과 같다
        assertEquals(true, r.restored)
    }

    @Test fun `전원을 끌 수 없는데 명령을 받은 차가 전원을 켰으면 실패로 적는다`() {
        val clock = Clock()
        val c = QuirkyCar(clock).apply { power = false; powerWritable = false; fanCommandTurnsPowerOn = true }
        val r = run(c, "fan", clock = clock)
        val power = r.steps.first()
        assertEquals("공조 전원", power.name)
        assertFalse(power.ok)
        assertTrue(power.line(), power.line().contains("쓰기 실패: ERROR_SECURITY_PERMISSION(2)"))
        assertTrue(power.line(), power.line().contains("원복을 두 번 보냈지만 처음 값이 되지 않음"))
        assertEquals("전원이 켜진 채로 남았다고 알린다", false, r.restored)
        assertTrue(r.lines.last(), r.lines.last().startsWith("[끝 상태] 공조 전원 켜짐"))
    }

    @Test fun `전원을 읽을 수 없는 차에서는 전원을 건드리지 않고 그 사실을 적는다`() {
        val clock = Clock()
        val c = QuirkyCar(clock).apply { power = null }
        val r = run(c, "fresh", clock = clock)
        assertTrue(c.calls.none { it.startsWith("power=") })
        assertEquals(listOf("내외기"), r.steps.map { it.name })
        assertTrue(r.closing.single(), r.closing.single().startsWith("[마무리] 공조 전원을 읽을 수 없어 전원은 되돌리지 못해요"))
        assertEquals("[처음 상태] 공조 전원 못 읽음, 내외기 내기, 풍량 3, 창문 0", r.lines.first())
    }

    // ---------- 수명 ----------

    @Test fun `도중에 취소돼도 바꾼 값을 처음으로 돌려놓는다`() {
        val clock = Clock()
        val c = QuirkyCar(clock).apply { power = false }
        // 2초 유지 중에 코루틴이 취소된다(화면이 닫히는 경우)
        val wait: suspend (Long) -> Unit = { ms ->
            clock.wait(ms)
            if (ms == ControlVerifier.HOLD_MS) { currentCoroutineContext().cancel(); currentCoroutineContext().ensureActive() }
        }
        runBlocking {
            val job = launch { ControlVerifier(c, wait = wait).run("fan") }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals("풍량은 처음 값으로", 3, c.fan)
        assertFalse("전원도 처음처럼 꺼짐", c.power!!)
        assertEquals(listOf("power=true", "fan=4", "fan=3", "power=false"), c.calls)
    }

    // ---------- 보고서 ----------

    @Test fun `보고서는 머리말과 요약과 처음 상태와 항목 줄과 끝 상태다`() {
        val lines = mutableListOf<String>()
        val r = runBlocking { verifier(car(), lines = lines).run("all") }!!
        val report = ControlVerifier.report("숨 제어 검증 v0.7.4 what=all", r).trim().lines()
        assertEquals("숨 제어 검증 v0.7.4 what=all", report[0])
        assertEquals("성공 3 / 실패 0 · 끝 상태는 처음과 같음", report[1])
        assertTrue(report[2].startsWith("[처음 상태] "))
        assertTrue(report.last().startsWith("[끝 상태] "))
        assertEquals(7, report.size)
        // 한 줄이 정해질 때마다 Logcat 으로 나가는 줄도 같은 글이다
        assertEquals(report.drop(2), lines)
        assertEquals("verify_latest.txt", ControlVerifier.FILE_NAME)
    }

    @Test fun `걸리는 시간 어림 - 셋 다 돌리면 방송 수신기가 붙드는 시간을 넘는다`() {
        assertEquals(3, ControlVerifier.expectedSeconds("window"))
        assertEquals(4, ControlVerifier.expectedSeconds("fresh"))
        assertEquals(10, ControlVerifier.expectedSeconds("all"))
        assertEquals(0, ControlVerifier.expectedSeconds("door"))
        assertTrue(ControlVerifier.expectedSeconds("all") * 1000L > ai.kkaeugi.app.gleo.AdbReply.HOLD_MS)
    }

    // ---------- 명령 뒤 확인 ----------

    @Test fun `확인은 기대한 값이 보이면 바로 끝나고 안 보이면 끝까지 기다린 뒤 마지막 값을 돌려준다`() {
        val clock = Clock()
        var value = 1
        val get: suspend () -> Outcome<Int> = { if (clock.waited >= 1_000L) value = 2; Outcome.Ok(value) }
        val seen = runBlocking { Confirm.until(2, get, clock.wait) }
        assertEquals(Confirm.Seen(2, 1_000L, true), seen)
        val never = runBlocking { Confirm.until(9, get, Clock().wait) }
        assertEquals(Confirm.Seen(2, 3_000L, false), never)
        // 먼저 읽기: 이미 그 값이면 기다리지 않는다
        val quick = Clock()
        assertEquals(Confirm.Seen(2, 0L, true), runBlocking { Confirm.until(2, get, quick.wait, readFirst = true) })
        assertTrue(quick.waits.isEmpty())
        // 읽기가 계속 실패하면 사유를 남긴다
        val failing = runBlocking { Confirm.until(1, { Outcome.Fail("응답 없음(4000ms)") }, Clock().wait) }
        assertEquals(Confirm.Seen<Int>(null, 3_000L, false, "응답 없음(4000ms)"), failing)
        assertEquals("0.5초", Confirm.seconds(500)); assertEquals("3.0초", Confirm.seconds(3_000))
    }
}

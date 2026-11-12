package ai.kkaeugi.app

import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.CabinAirModel
import ai.kkaeugi.engine.WakeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WakeViewModel 이 엔진 이벤트를 처리할 때 쓰는 규칙들 */
class EventLogicTest {
    private val revert = listOf<Action>(Action.Revert)
    private val vent = listOf<Action>(Action.Ventilate())

    // ---------- 스냅샷 폐기 규칙 ----------

    @Test fun `보통 흐름 - Execute, Revert, Done 이면 원복으로 끝남`() {
        val t = ExecutionTracker()
        assertFalse(t.onExecute(vent))
        assertTrue(t.onExecute(revert))
        assertEquals(ExecEnd.Reverted, t.onDone())
        assertEquals("한 번 쓴 뒤에는 비어 있다", ExecEnd.None, t.onDone())
    }

    @Test fun `원복 없이 Done 이 오면 그 실행의 액션을 돌려준다`() {
        val t = ExecutionTracker()
        t.onExecute(vent)
        assertEquals(ExecEnd.Kept(vent), t.onDone())
        assertEquals(ExecEnd.None, t.onDone())
    }

    @Test fun `풍량 올리기도 같은 규칙 - 문장과 무관하다`() {
        val t = ExecutionTracker()
        val fanUp = listOf<Action>(Action.Ventilate(fanDelta = 2))
        t.onExecute(fanUp)
        assertEquals(ExecEnd.Kept(fanUp), t.onDone())
    }

    @Test fun `실행한 적 없는 Done 은 None - 거절, 무응답, 조용히 닫힘`() {
        assertEquals(ExecEnd.None, ExecutionTracker().onDone())
    }

    @Test fun `유지로 끝난 뒤 다음 개입은 다시 원복으로 끝난다`() {
        val t = ExecutionTracker()
        t.onExecute(vent); assertTrue(t.onDone() is ExecEnd.Kept)
        val drowsy = listOf(Action.Ventilate(), Action.CoolDown(), Action.OpenWindow())
        t.onExecute(drowsy); t.onExecute(revert)
        assertEquals(ExecEnd.Reverted, t.onDone())
    }

    @Test fun `세션 종료 묶음 - Execute(Revert) 와 Done 이 같이 와도 원복으로 본다`() {
        val t = ExecutionTracker()
        t.onExecute(vent)
        t.onExecute(revert)
        assertEquals(ExecEnd.Reverted, t.onDone())
    }

    @Test fun `reset 뒤에는 남은 실행이 없다`() {
        val t = ExecutionTracker()
        t.onExecute(vent); t.reset()
        assertEquals(ExecEnd.None, t.onDone())
        assertTrue(ExecutionTracker.isRevert(revert))
        assertFalse(ExecutionTracker.isRevert(listOf(Action.Ventilate(), Action.Revert)))
    }

    // ---------- 바깥 공기 누적 ----------

    private val min = 60_000L

    @Test fun `2분 환기 한 번 - 열려 있는 동안은 흐른 만큼, 닫히면 반올림`() {
        val f = FreshAirTally()
        f.onVentilated(0)
        assertEquals(0, f.units(59_000, min))
        assertEquals(1, f.units(61_000, min))
        f.onReverted(125_000, min, recirculating = true)
        assertEquals(2, f.units(500_000, min))
        assertFalse(f.open)
    }

    @Test fun `짧은 구간도 닫히면 최소 1`() {
        val f = FreshAirTally()
        f.onVentilated(0); f.onReverted(20_000, min, recirculating = true)
        assertEquals(1, f.units(20_000, min))
    }

    @Test fun `유지 구간 - 원복 없이 두면 주행이 끝날 때까지 이어진다`() {
        val f = FreshAirTally()
        f.onVentilated(4 * min)
        f.onKept(4 * min)
        assertTrue(f.keeping)
        assertEquals(126, f.units(130 * min, min))
        f.onTripEnded(200 * min, min)
        assertEquals(196, f.units(999 * min, min))
    }

    @Test fun `유지 중 졸음 개입이 겹치면 구간을 닫았다 다시 연다`() {
        val f = FreshAirTally()
        f.onVentilated(0); f.onKept(0)
        f.onVentilated(100 * min)                       // 졸음 개입 안의 환기: 이미 열려 있어 그대로
        f.onReverted(102 * min, min, recirculating = false)
        assertTrue(f.open)
        assertEquals(102 + 8, f.units(110 * min, min))
    }

    @Test fun `유지 중 운전자가 내기순환을 켜면 구간이 닫히고 유지가 풀린다`() {
        val f = FreshAirTally()
        f.onVentilated(0); f.onKept(0)
        assertTrue(f.onRecirculationOn(30 * min, min, ventActive = false))
        assertFalse(f.keeping); assertFalse(f.open)
        assertEquals(30, f.units(90 * min, min))
        assertFalse("이미 풀렸다", f.onRecirculationOn(31 * min, min, ventActive = false))
    }

    @Test fun `엔진이 환기를 유지하는 중의 내기순환 신호로는 닫지 않는다`() {
        val f = FreshAirTally()
        f.onVentilated(0)
        f.onRecirculationOn(30_000, min, ventActive = true)
        assertTrue(f.open)
    }

    @Test fun `쉬는 시간은 넣지 않는다`() {
        val f = FreshAirTally()
        f.onVentilated(0); f.onKept(0)
        f.onRestStarted(60 * min, min)
        assertEquals(60, f.units(70 * min, min))
        f.onRestEnded(75 * min, recirculating = false)
        assertEquals(60 + 5, f.units(80 * min, min))
    }

    @Test fun `데모 속도는 초 단위, 닫자마자 다시 닫히는 구간은 버린다`() {
        val f = FreshAirTally()
        f.onVentilated(0)
        f.onReverted(20_400, 1_000, recirculating = true)
        assertEquals(20, f.units(30_000, 1_000))
        f.onVentilated(40_000); f.onKept(40_000)
        f.onReverted(70_000, 1_000, recirculating = false)   // 다시 열림
        f.onRestStarted(70_100, 1_000)                        // 0.1초 구간은 세지 않는다
        assertEquals(50, f.units(99_000, 1_000))
        f.reset()
        assertEquals(0, f.units(99_000, 1_000))
    }

    // ---------- 프로세스 복원 ----------

    @Test fun `30분 안에 다시 뜨면 트립을 이어 붙인다`() {
        val start = 1_000_000_000L
        assertEquals(12, SessionRestore.carriedMinutes(start, start + 12 * min + 30_000))
        assertEquals(30, SessionRestore.carriedMinutes(start, start + 30 * min))
    }

    @Test fun `30분을 넘거나 1분이 안 됐거나 저장이 없으면 새 주행`() {
        val start = 1_000_000_000L
        assertEquals(0, SessionRestore.carriedMinutes(start, start + 30 * min + 1))
        assertEquals(0, SessionRestore.carriedMinutes(start, start + 6 * 60 * min))
        assertEquals(0, SessionRestore.carriedMinutes(start, start + 59_000))
        assertEquals(0, SessionRestore.carriedMinutes(0L, start))
        assertEquals("벽시계가 뒤로 감", 0, SessionRestore.carriedMinutes(start, start - 5 * min))
    }

    // ---------- 한도까지 남은 시간(반올림) ----------

    private val cfg = WakeConfig()

    @Test fun `출발 직후 한도까지 - 혼자 약 20분, 둘 8분, 넷 4분`() {
        val start = cfg.outdoorPpm + 200
        assertEquals(20.2, AirMath.minutesToLimitExact(start, 1, 2000, cfg)!!, 0.05)
        assertEquals(8.1, AirMath.minutesToLimitExact(start, 2, 2000, cfg)!!, 0.05)
        assertEquals(3.7, AirMath.minutesToLimitExact(start, 4, 2000, cfg)!!, 0.05)
    }

    @Test fun `엔진 모델과 같은 값`() {
        for (n in 1..5) {
            val m = CabinAirModel(cfg).apply { occupants = n; recirculating = true; fanLevel = 3; fanMax = 8; reset(0) }
            m.advance(90_000)
            assertEquals(m.minutesUntilAbove(2000.0)!!, AirMath.minutesToLimitExact(m.ppm(), n, 2000, cfg)!!, 0.02)
            assertEquals(m.minutesUntilAbove(1600.0)!!, AirMath.minutesToLimitExact(m.ppm(), n, 1600, cfg)!!, 0.02)
        }
    }

    @Test fun `화면에는 반올림 - 엔진의 올림값 21, 9 가 20, 8 로`() {
        val start = cfg.outdoorPpm + 200
        assertEquals(20, AirMath.roundedMinutesToLimit(21, false, start, 1, 2000, cfg))
        assertEquals(8, AirMath.roundedMinutesToLimit(9, false, start, 2, 2000, cfg))
        assertEquals(4, AirMath.roundedMinutesToLimit(4, false, start, 4, 2000, cfg))
    }

    @Test fun `닿지 않음과 이미 넘음은 엔진 값을 그대로`() {
        assertNull(AirMath.roundedMinutesToLimit(null, false, 900, 1, 2000, cfg))
        assertEquals(0, AirMath.roundedMinutesToLimit(0, false, 2100, 4, 2000, cfg))
        assertEquals("환기 중이면 손대지 않는다", 7, AirMath.roundedMinutesToLimit(7, true, 900, 4, 2000, cfg))
    }

    @Test fun `반올림해도 0 이 되지는 않고, 엔진 값과 많이 다르면 엔진 값을 쓴다`() {
        // 한도 바로 아래: 남은 시간 0.1분 → 반올림 0 이 아니라 1
        assertEquals(1, AirMath.roundedMinutesToLimit(1, false, 1990, 4, 2000, cfg))
        // 앱이 모르는 조건(창문 등)으로 엔진이 훨씬 길게 본 경우
        assertEquals(40, AirMath.roundedMinutesToLimit(40, false, cfg.outdoorPpm + 200, 1, 2000, cfg))
    }

    @Test fun `데모 설정 - 환기 20초, 졸음 30초, 쉼 15초, 60배속`() {
        val d = DemoSettings.config()
        assertTrue(d.demoFast)
        assertEquals(20, d.ventilateSeconds); assertEquals(30, d.drowsySeconds); assertEquals(15, d.restMinutes); assertEquals(8, d.answerTimeoutSec)
    }
}

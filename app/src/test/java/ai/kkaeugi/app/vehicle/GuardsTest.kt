package ai.kkaeugi.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardsTest {
    private var t = 0L
    private val guard = WaypointGuard { t }

    @Test fun `추가 뒤 10초 안에 내비 이벤트가 오면 우리 경유지로 확인`() {
        assertFalse(guard.canRemove)
        guard.onOurAdd(); assertFalse("확인 전", guard.canRemove)
        t = 2_000; guard.onWaypointEvent()
        assertTrue(guard.canRemove)
    }

    @Test fun `확인 이벤트가 없으면 지우지 않는다`() {
        guard.onOurAdd()
        t = 15_000; guard.onWaypointEvent() // 너무 늦게 온 이벤트는 운전자 조작으로 본다
        assertFalse(guard.canRemove)
    }

    @Test fun `확인 뒤 운전자가 경유지를 바꾸면 지우지 않는다`() {
        guard.onOurAdd(); t = 1_000; guard.onWaypointEvent()
        t = 60_000; guard.onWaypointEvent()
        assertFalse(guard.canRemove)
    }

    @Test fun `우리가 넣은 적이 없으면 이벤트가 와도 지우지 않는다`() {
        guard.onWaypointEvent()
        assertFalse(guard.canRemove)
        guard.onOurAdd(); t = 500; guard.onWaypointEvent(); guard.clear()
        assertFalse(guard.canRemove)
    }

    @Test fun `heading 0 이나 정지 상태면 이전 값을 유지한다`() {
        val h = HeadingHold()
        assertNull("처음부터 0 이면 모름", h.resolve(0.0))
        assertEquals(330.0, h.resolve(330.0, 95.0)!!, 0.001)
        assertEquals("0 은 버림", 330.0, h.resolve(0.0, 95.0)!!, 0.001)
        assertEquals("정지 중 값은 버림", 330.0, h.resolve(120.0, 1.0)!!, 0.001)
        assertEquals(120.0, h.resolve(120.0, 40.0)!!, 0.001)
        assertEquals("null 도 유지", 120.0, h.resolve(null)!!, 0.001)
        assertEquals("음수는 0~360 으로", 350.0, h.resolve(-10.0)!!, 0.001)
    }

    @Test fun `heading 이 없으면 직전 두 위치의 방위로 대신한다`() {
        val h = HeadingHold()
        assertNull("첫 위치만으로는 모른다", h.resolve(null, lat = 36.0, lng = 128.0))
        assertNull("50m 가 안 되면 아직 모른다", h.resolve(null, lat = 36.0002, lng = 128.0))
        // 북쪽으로 약 111m
        assertEquals(0.0, h.resolve(null, lat = 36.001, lng = 128.0)!!, 0.5)
        // 그 자리에서 동쪽으로 약 180m
        assertEquals(90.0, h.resolve(null, lat = 36.001, lng = 128.002)!!, 0.5)
    }

    @Test fun `heading 이 0 으로 고정이어도 위치로 방위를 잡는다`() {
        val h = HeadingHold()
        h.resolve(0.0, 80.0, 36.0, 128.0)
        // 남쪽으로 약 220m 이동하는 동안 SDK 는 계속 0 을 준다
        assertEquals(180.0, h.resolve(0.0, 80.0, 35.998, 128.0)!!, 0.5)
    }

    @Test fun `쓸 만한 heading 이 오면 위치 방위보다 그 값을 쓴다`() {
        val h = HeadingHold()
        h.resolve(null, lat = 36.0, lng = 128.0)
        assertEquals(200.0, h.resolve(200.0, 90.0, 36.001, 128.0)!!, 0.001)
        // 조금 움직인 것(50m 미만)으로는 방위가 바뀌지 않는다
        assertEquals(200.0, h.resolve(null, lat = 36.0011, lng = 128.0)!!, 0.001)
    }

    @Test fun `위치 두 개로 속도 추정`() {
        val s = SpeedEstimator()
        assertNull("첫 위치", s.update(0, 36.0, 128.0))
        // 1초에 북쪽으로 27.8m → 약 100km/h
        val kph = s.update(1_000, 36.00025, 128.0)!!
        assertEquals(100.0, kph, 1.0)
        assertEquals("0.5초보다 짧은 간격은 직전 값을 돌려준다", kph, s.update(1_200, 36.0003, 128.0)!!, 0.001)
    }

    @Test fun `속도를 모르는 경우 - 간격이 너무 길거나 위치가 튐`() {
        val s = SpeedEstimator()
        s.update(0, 36.0, 128.0)
        assertNull("1분 넘게 끊겼다", s.update(61_000, 36.01, 128.0))
        assertNull("1초에 1km: 위치가 튄 것", s.update(62_000, 36.019, 128.0))
        assertEquals("다음 표본부터 다시 잰다", 0.0, s.update(63_000, 36.019, 128.0)!!, 0.001)
    }
}

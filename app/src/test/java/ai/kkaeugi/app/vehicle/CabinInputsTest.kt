package ai.kkaeugi.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinInputsTest {
    @Test fun `점유 센서가 답하면 앉은 자리 수`() {
        val r = OccupantCounter.count(mapOf("ROW_1_LEFT" to true, "ROW_1_RIGHT" to true, "ROW_2_LEFT" to false, "ROW_2_CENTER" to null, "ROW_2_RIGHT" to true))
        assertEquals(3, r.count); assertEquals(OccupantCounter.Source.OCCUPANCY, r.source)
    }

    @Test fun `점유를 못 읽으면 안전벨트 체결 수`() {
        val none = mapOf<String, Boolean?>("ROW_1_LEFT" to null, "ROW_1_RIGHT" to null)
        val r = OccupantCounter.count(none, mapOf("ROW_1_LEFT" to true, "ROW_1_RIGHT" to true, "ROW_2_LEFT" to true, "ROW_2_RIGHT" to true))
        assertEquals(4, r.count); assertEquals(OccupantCounter.Source.SEAT_BELT, r.source)
    }

    @Test fun `둘 다 못 읽으면 1명 가정`() {
        val r = OccupantCounter.count(mapOf("ROW_1_LEFT" to null), mapOf("ROW_1_LEFT" to null))
        assertEquals(1, r.count); assertEquals(OccupantCounter.Source.ASSUMED, r.source)
        assertEquals(1, OccupantCounter.count(emptyMap()).count)
    }

    @Test fun `센서가 전부 빈자리여도 최소 1명(운전자)`() {
        assertEquals(1, OccupantCounter.count(mapOf("ROW_1_LEFT" to false, "ROW_1_RIGHT" to false)).count)
        assertEquals(1, OccupantCounter.count(emptyMap(), mapOf("ROW_1_LEFT" to false)).count)
    }

    @Test fun `핸들 샘플은 초당 최대 5개`() {
        val s = SteeringSampler()
        val accepted = (0L until 1000L step 10L).count { s.accept(it) } // 100Hz 로 1초
        assertEquals(5, accepted)
        assertTrue(s.accept(1000L))
        assertFalse(s.accept(1100L))
        assertTrue("시계가 뒤로 가면 다시 받는다", s.accept(500L))
    }

    @Test fun `창문 열림은 네 창 중 최댓값 퍼센트`() {
        val w = WindowOpenTracker()
        assertEquals(0, w.maxPercent)
        assertEquals(15, w.update("ROW_1_LEFT", 15, 0, 100))
        assertEquals(50, w.update("ROW_2_RIGHT", 5, 0, 10))
        assertEquals(50, w.update("ROW_1_LEFT", 0, 0, 100))
        assertEquals(0, w.update("ROW_2_RIGHT", 0, 0, 10))
        assertEquals(0, WindowOpenTracker.percentOf(3, 5, 5))
        assertEquals(100, WindowOpenTracker.percentOf(300, 0, 255))
    }

    // ---------- 탑승 인원: 좌석별 혼합 응답, 운전석, 두 번 연속 ----------

    @Test fun `좌석마다 점유 값이 없으면 그 자리의 벨트 값을 쓴다`() {
        // 앞자리는 점유 값이 오고, 뒷자리는 벨트만 답하는 차
        val occ = mapOf("ROW_1_LEFT" to true, "ROW_1_RIGHT" to false, "ROW_2_LEFT" to null, "ROW_2_CENTER" to null, "ROW_2_RIGHT" to null)
        val belt = mapOf("ROW_2_LEFT" to true, "ROW_2_CENTER" to false, "ROW_2_RIGHT" to true)
        val r = OccupantCounter.count(occ, belt)
        assertEquals(3, r.count)
        assertEquals(OccupantCounter.Source.MIXED, r.source)
        assertTrue(r.detail.contains("ROW_2_LEFT=O(벨트)"))
        assertTrue(r.detail.contains("ROW_1_RIGHT=X"))
    }

    @Test fun `점유 값이 있는 자리는 벨트를 안 매도 탄 것으로 센다`() {
        val r = OccupantCounter.count(mapOf("ROW_1_LEFT" to true, "ROW_2_LEFT" to true), mapOf("ROW_2_LEFT" to false))
        assertEquals(2, r.count)
        assertEquals(OccupantCounter.Source.OCCUPANCY, r.source)
    }

    @Test fun `둘 다 못 읽은 자리는 세지 않는다`() {
        val r = OccupantCounter.count(mapOf("ROW_1_LEFT" to true, "ROW_2_LEFT" to null), mapOf("ROW_2_LEFT" to null))
        assertEquals(1, r.count)
        assertTrue(r.detail.contains("ROW_2_LEFT=?"))
    }

    @Test fun `주행 중 운전석은 값과 무관하게 1`() {
        val occ = mapOf<String, Boolean?>("ROW_1_LEFT" to false, "ROW_1_RIGHT" to true)
        assertEquals("정차 중에는 값 그대로", 1, OccupantCounter.count(occ, driverSeat = "ROW_1_LEFT", driving = false).count)
        val r = OccupantCounter.count(occ, driverSeat = "ROW_1_LEFT", driving = true)
        assertEquals(2, r.count)
        assertTrue(r.detail.contains("ROW_1_LEFT=O(운전석)"))
        // 운전석 값을 못 읽어도, 벨트를 안 매도
        assertEquals(2, OccupantCounter.count(mapOf("ROW_1_LEFT" to null, "ROW_1_RIGHT" to true), mapOf("ROW_1_LEFT" to false), driverSeat = "ROW_1_LEFT", driving = true).count)
    }

    @Test fun `출처 이름`() {
        assertEquals("좌석 점유 + 안전벨트", OccupantCounter.label(OccupantCounter.Source.MIXED))
        assertEquals("가정(1명)", OccupantCounter.label(OccupantCounter.Source.ASSUMED))
    }

    @Test fun `두 번 연속 같은 값일 때만 알린다`() {
        val d = OccupantDebouncer()
        assertEquals("처음 값도 한 번 더 확인", null, d.offer(1))
        assertEquals(1, d.offer(1))
        assertEquals("같은 값은 다시 알리지 않는다", null, d.offer(1))
        assertEquals("한 번 튄 값", null, d.offer(4))
        assertEquals(null, d.offer(1))
        assertEquals(null, d.offer(4))
        assertEquals(4, d.offer(4))
        assertEquals(4, d.published)
        assertEquals(null, d.offer(4))
    }

    // ---------- 핸들 각도 다시 보내기 ----------

    @Test fun `주행 중 마지막 각도를 1초마다 다시 보낸다`() {
        val r = SteeringRepeater(1_000)
        assertEquals("아는 각도가 없다", null, r.due(5_000, driving = true))
        r.onSent(10_000, 2.5f)
        assertEquals(null, r.due(10_900, driving = true))
        assertEquals(2.5f, r.due(11_000, driving = true))
        r.onSent(11_000, 2.5f)
        assertEquals(null, r.due(11_500, driving = true))
        assertEquals("정차 중에는 보내지 않는다", null, r.due(20_000, driving = false))
    }

    @Test fun `걸러 낸 최신 각도로 다시 보낸다`() {
        val r = SteeringRepeater(1_000)
        r.onSent(0, 1f)
        r.onObserved(3f)
        assertEquals(3f, r.due(1_000, driving = true))
    }

    // ---------- 창문 방향, 공조 전원 ----------

    @Test fun `창문 방향 반전 - 위치와 퍼센트가 같이 뒤집힌다`() {
        assertEquals(15, WindowScale.positionOf(15, 0, 100))
        assertEquals(85, WindowScale.positionOf(15, 0, 100, inverted = true))
        assertEquals(15, WindowScale.percentOf(85, 0, 100, inverted = true))
        assertEquals(0, WindowScale.percentOf(100, 0, 100, inverted = true))
        assertEquals(100, WindowScale.percentOf(0, 0, 100, inverted = true))
        // 범위가 0~255 인 차
        val pos = WindowScale.positionOf(15, 0, 255, inverted = true)
        assertEquals(217, pos)
        assertEquals(15, WindowScale.percentOf(pos, 0, 255, inverted = true))
        assertEquals("범위를 모르면 0", 0, WindowScale.percentOf(3, 5, 5, inverted = true))
    }

    @Test fun `창문 신호도 반전을 따른다 - 바꾸면 기억한 위치로 다시 계산`() {
        val w = WindowOpenTracker()
        assertEquals(0, w.update("ROW_1_LEFT", 0, 0, 100))
        assertEquals(0, w.update("ROW_1_RIGHT", 0, 0, 100))
        w.inverted = true
        assertEquals("반전하면 0 이 다 열림", 100, w.maxPercent)
        assertEquals(100, w.update("ROW_1_LEFT", 100, 0, 100))   // 오른쪽 창이 아직 0(다 열림)
        assertEquals(15, w.update("ROW_1_RIGHT", 85, 0, 100))    // 왼쪽 0%, 오른쪽 15%
    }

    @Test fun `공조 전원이 꺼져 있으면 풍량 0 으로 알린다`() {
        val g = FanGate()
        assertEquals("아는 풍량이 없다", null, g.next(8))
        g.level = 3
        assertEquals(3 to 8, g.next(8))
        assertEquals("같은 값은 다시 알리지 않는다", null, g.next(8))
        g.power = false
        assertEquals(0 to 8, g.next(8))
        g.level = 5
        assertEquals("꺼져 있는 동안 단계가 바뀌어도 0", null, g.next(8))
        g.power = true
        assertEquals(5 to 8, g.next(8))
    }
}

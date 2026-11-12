package ai.kkaeugi.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 좌석 지도에 넘기는 자리별 값(순수 로직). 집계 규칙은 OccupantCounter 를 그대로 따른다 */
class SeatLayoutTest {
    private val driver = "ROW_1_LEFT"
    private fun occupancy(vararg seated: String): Map<String, Boolean?> = SeatLayout.ORDER.associateWith { it in seated }

    @Test fun `자리 순서는 운전석, 조수석, 뒷줄 왼쪽, 가운데, 오른쪽`() {
        assertEquals(listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT"), SeatLayout.ORDER)
        assertEquals("ROW_1_LEFT", SeatLayout.ORDER[SeatLayout.DRIVER])
        assertEquals("ROW_1_RIGHT", SeatLayout.ORDER[SeatLayout.FRONT_PASSENGER])
    }

    @Test fun `운전석과 뒷줄 왼쪽처럼 임의의 조합을 그대로 편다`() {
        val r = OccupantCounter.count(occupancy(driver, "ROW_2_LEFT"), driverSeat = driver, driving = true)
        assertEquals(listOf<Boolean?>(true, false, true, false, false), SeatLayout.of(r))
        assertEquals(2, r.count)
        val rearRightOnly = OccupantCounter.count(occupancy(driver, "ROW_2_RIGHT", "ROW_2_CENTER"), driverSeat = driver, driving = true)
        assertEquals(listOf<Boolean?>(true, false, false, true, true), SeatLayout.of(rearRightOnly))
    }

    @Test fun `주행 중 운전석은 값과 무관하게 탄 것으로 본다`() {
        // 운전석 점유 센서가 비었다고 해도 주행 중이면 운전자는 타고 있다(OccupantCounter 와 같은 규칙)
        val r = OccupantCounter.count(occupancy("ROW_1_RIGHT"), driverSeat = driver, driving = true)
        assertEquals(listOf<Boolean?>(true, true, false, false, false), SeatLayout.of(r))
        assertEquals(2, r.count)
        // 정차 중에는 값 그대로다
        val parked = OccupantCounter.count(occupancy("ROW_1_RIGHT"), driverSeat = driver, driving = false)
        assertEquals(listOf<Boolean?>(false, true, false, false, false), SeatLayout.of(parked))
    }

    @Test fun `점유 값이 없는 자리는 벨트 값, 둘 다 없으면 모름`() {
        val occ = mapOf<String, Boolean?>("ROW_1_LEFT" to true, "ROW_1_RIGHT" to false, "ROW_2_LEFT" to null, "ROW_2_CENTER" to null, "ROW_2_RIGHT" to null)
        val belt = mapOf<String, Boolean?>("ROW_2_LEFT" to true, "ROW_2_CENTER" to false)
        val r = OccupantCounter.count(occ, belt, driverSeat = driver, driving = true)
        assertEquals(listOf(true, false, true, false, null), SeatLayout.of(r))
        assertEquals(2, r.count)
    }

    @Test fun `아무도 탔다고 하지 않으면 인원 1명과 맞게 운전석을 채운다`() {
        val r = OccupantCounter.count(occupancy(), driverSeat = driver, driving = false)
        assertEquals(1, r.count)
        assertEquals(listOf<Boolean?>(true, false, false, false, false), SeatLayout.of(r))
    }

    @Test fun `좌석 값이 하나도 안 오면 자리별 값은 없다`() {
        val none = SeatLayout.ORDER.associateWith<String, Boolean?> { null }
        assertNull(SeatLayout.of(OccupantCounter.count(none, none, driverSeat = driver, driving = false)))
        assertNull(SeatLayout.of(OccupantCounter.count(emptyMap(), driverSeat = driver, driving = false)))
        // 주행 중이면 운전석만 가정으로 채워지지만 좌석 값이 온 것은 아니다
        assertNull(SeatLayout.of(OccupantCounter.count(none, none, driverSeat = driver, driving = true)))
    }

    @Test fun `자리별 값으로 센 인원은 집계 인원과 같다`() {
        val cases = listOf(
            OccupantCounter.count(occupancy(driver), driverSeat = driver, driving = true),
            OccupantCounter.count(occupancy(driver, "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT"), driverSeat = driver, driving = true),
            OccupantCounter.count(occupancy("ROW_2_LEFT"), driverSeat = driver, driving = true),
            OccupantCounter.count(occupancy(), driverSeat = driver, driving = false),
        )
        for (r in cases) assertEquals(r.detail, r.count, SeatLayout.count(SeatLayout.of(r)!!))
        assertEquals(1, SeatLayout.count(listOf(false, false, null, null, null)))
    }

    @Test fun `자리별 값도 두 번 연속 같을 때만, 바뀌었을 때만 알린다`() {
        val stable = StableValue<List<Boolean?>>()
        val a = listOf<Boolean?>(true, false, true, false, false)
        val b = listOf<Boolean?>(true, false, false, false, true)
        assertNull(stable.offer(a))
        assertEquals(a, stable.offer(a))
        assertNull("같은 값은 다시 알리지 않는다", stable.offer(a))
        assertNull("한 번 튄 값은 거른다", stable.offer(b))
        assertNull(stable.offer(a))
        assertNull(stable.offer(b))
        assertEquals(b, stable.offer(b))
        assertEquals(b, stable.published)
    }
}

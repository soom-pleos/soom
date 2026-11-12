package ai.kkaeugi.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 증거 띠 문자열: 제어 호출만, 성공은 걸린 시간, 실패는 짧은 코드 */
class EvidenceLineTest {
    @Test fun `성공 - 이름과 걸린 시간`() {
        assertEquals("setHvacRecirculation(false) → ok 0.3s", EvidenceLine.format("setHvacRecirculation(false)", ok = true, elapsedMs = 312))
        assertEquals("setHvacFanSpeed(4) → ok 0.0s", EvidenceLine.format("setHvacFanSpeed(4)", ok = true))
        assertEquals("setWindowPosition(15) → ok 1.2s", EvidenceLine.format("setWindowPosition(15)", ok = true, elapsedMs = 1_249))
    }

    @Test fun `실패 - 짧은 코드, 시간은 없다`() {
        assertEquals(
            "setHvacRecirculation(false) → 실패 DRIVING_RESTRICTED",
            EvidenceLine.format("setHvacRecirculation(false)", ok = false, error = "ERROR_DRIVING_RESTRICTED(3): restricted", elapsedMs = 400),
        )
        assertEquals("setHvacTemperature(22.0 C) → 실패 PERMISSION", EvidenceLine.format("setHvacTemperature(22.0 C)", ok = false, error = "ERROR_SECURITY_PERMISSION(5): denied"))
        assertEquals("setWindowPosition(15) → 실패 TIMEOUT", EvidenceLine.format("setWindowPosition(15)", ok = false, error = "응답 없음(4000ms)"))
    }

    @Test fun `경유지 호출도 찍는다`() {
        assertEquals("addWaypoint(석적 졸음쉼터) → ok 0.0s", EvidenceLine.format("addWaypoint(석적 졸음쉼터)", ok = true, elapsedMs = 4))
        assertEquals("removeWaypoint(FIRST) → ok 0.0s", EvidenceLine.format("removeWaypoint(FIRST)", ok = true))
        assertEquals("addWaypoint(석적 졸음쉼터) → 실패 NaviHelper 없음", EvidenceLine.format("addWaypoint(석적 졸음쉼터)", ok = false, error = "NaviHelper 없음"))
    }

    @Test fun `읽기 호출은 찍지 않는다`() {
        assertNull(EvidenceLine.format("getHvacRecirculationState", ok = true, elapsedMs = 100))
        assertNull(EvidenceLine.format("getCurrentSeatOccupancy(ROW_2_LEFT)", ok = false, error = "응답 없음(4000ms)"))
        assertNull(EvidenceLine.format("checkHvacFanSpeedCapabilities", ok = true))
        assertNull(EvidenceLine.format("getCurrentSteeringWheelAngle", ok = true))
        assertTrue(EvidenceLine.isControlCall("setHvacFanSpeed(4)"))
        assertFalse(EvidenceLine.isControlCall("getHvacFanSpeed"))
    }
}

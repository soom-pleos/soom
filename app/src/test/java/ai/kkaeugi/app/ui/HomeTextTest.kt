package ai.kkaeugi.app.ui

import ai.kkaeugi.app.AirUi
import ai.kkaeugi.app.RestUi
import ai.kkaeugi.app.UiState
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.TripState
import ai.kkaeugi.engine.restStopDisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 주행 중 홈의 문구·눈금 계산(순수 함수) */
class HomeTextTest {
    @Test fun `게이지 아래 한 줄`() {
        assertEquals("4명 탑승 · 약 3분 뒤 환기 제안", airStatusLine(AirUi(1640, 4, 3, false), 4, demoFast = false))
        assertEquals("4명 탑승 · 약 3초 뒤 환기 제안", airStatusLine(AirUi(1640, 4, 3, false), 4, demoFast = true))
        assertEquals("바깥 공기 들어오는 중", airStatusLine(AirUi(900, 2, null, true), 2, demoFast = false))
        assertEquals("바깥 공기 들어오는 중 · 내려가는 중", airStatusLine(AirUi(900, 2, null, true, falling = true), 2, demoFast = false))
        assertEquals("1명 탑승 · 한도를 넘었어요(추정)", airStatusLine(AirUi(2100, 1, 0, false), 1, demoFast = false))
        assertEquals("1명 탑승 · 지금은 한도에 닿지 않아요", airStatusLine(AirUi(600, 1, null, false), 1, demoFast = false))
        assertEquals("2명 탑승 · 추정 준비 중", airStatusLine(null, 2, demoFast = false))
    }

    @Test fun `게이지 눈금은 420에서 3000`() {
        assertEquals(0f, gaugeFraction(420), 0.001f)
        assertEquals(0f, gaugeFraction(100), 0.001f)
        assertEquals(1f, gaugeFraction(3000), 0.001f)
        assertEquals(1f, gaugeFraction(5000), 0.001f)
        assertEquals(0.612f, gaugeFraction(2000), 0.002f)
        assertEquals("1,640", ppmText(1640))
    }

    @Test fun `야간 장거리 배지와 쉼터 표시 이름`() {
        assertTrue(isNightLongTrip(UiState(night = true, tripMinutes = 120, longTripMinutes = 120)))
        assertFalse(isNightLongTrip(UiState(night = false, tripMinutes = 300)))
        assertFalse(isNightLongTrip(UiState(night = true, tripMinutes = 119)))
        // 화면, 음성, 경유지 이름이 모두 엔진의 같은 함수를 쓴다
        assertEquals("석적 졸음쉼터", restStopDisplayName("석적"))
        assertEquals("석적 졸음쉼터", restStopDisplayName("석적 졸음쉼터"))
        assertEquals("칠곡휴게소", restStopDisplayName("칠곡휴게소"))
    }

    // ---------- 게이지 아래 한 줄: 추가 분기 ----------

    private val recirc4 = AirUi(1640, 4, 3, ventilating = false)

    @Test fun `숨 OFF 면 곧 묻는다고 하지 않는다`() {
        assertEquals("4명 탑승 · 숨 꺼짐", airStatusLine(recirc4, 4, false, AirLineContext(autoIntervene = false)))
        assertEquals("한도를 넘어도 같은 문구", "4명 탑승 · 숨 꺼짐", airStatusLine(AirUi(2300, 4, 0, false), 4, false, AirLineContext(autoIntervene = false)))
        // 바깥 공기가 들어오는 중이면 그 사실을 먼저 알린다
        assertEquals("바깥 공기 들어오는 중", airStatusLine(AirUi(900, 2, null, true), 2, false, AirLineContext(autoIntervene = false)))
    }

    @Test fun `창문이 열려 있으면 창문 열림`() {
        assertEquals("창문 열림 · 바깥 공기 들어오는 중", airStatusLine(AirUi(1200, 2, null, true), 2, false, AirLineContext(windowPercent = 15)))
        assertEquals("창문 열림 · 내려가는 중", airStatusLine(AirUi(1200, 2, null, true, falling = true), 2, false, AirLineContext(windowPercent = 15)))
        assertEquals("5퍼센트 미만은 닫힌 것으로 본다", "바깥 공기 들어오는 중", airStatusLine(AirUi(1200, 2, null, true), 2, false, AirLineContext(windowPercent = 4)))
    }

    @Test fun `거절 뒤에는 다시 물을 때까지 남은 시간`() {
        assertEquals("4명 탑승 · 18분 뒤 다시 물을게요", airStatusLine(recirc4, 4, false, AirLineContext(cooldownLeft = 18)))
        assertEquals("4명 탑승 · 18초 뒤 다시 물을게요", airStatusLine(recirc4, 4, true, AirLineContext(cooldownLeft = 18)))
        assertEquals("한도를 넘은 채 기다리는 중에도", "4명 탑승 · 5분 뒤 다시 물을게요", airStatusLine(AirUi(2400, 4, 0, false), 4, false, AirLineContext(cooldownLeft = 5)))
        assertEquals("남은 시간이 없으면 평소 문구", "4명 탑승 · 약 3분 뒤 환기 제안", airStatusLine(recirc4, 4, false, AirLineContext(cooldownLeft = 0)))
    }

    @Test fun `외기인데 한도 위에서 안 내려오면 바람이 약해요`() {
        val weak = AirUi(2150, 4, null, ventilating = true)
        assertEquals("바깥 공기인데 바람이 약해요(추정)", airStatusLine(weak, 4, false, AirLineContext(limit = 2000)))
        assertTrue(weakFreshAir(weak, AirLineContext(limit = 2000)))
        // 막 바꿔서 내려가는 중이면 약한 게 아니다
        assertEquals("바깥 공기 들어오는 중 · 내려가는 중", airStatusLine(weak.copy(falling = true), 4, false, AirLineContext(limit = 2000)))
        // 야간 장거리 한도(1,600) 기준
        assertEquals("바깥 공기인데 바람이 약해요(추정)", airStatusLine(AirUi(1700, 4, null, true), 4, false, AirLineContext(limit = 1600)))
        assertEquals("바깥 공기 들어오는 중", airStatusLine(AirUi(1700, 4, null, true), 4, false, AirLineContext(limit = 2000)))
        assertFalse(weakFreshAir(null, AirLineContext()))
    }

    @Test fun `쉬는 중에는 추정이 멈춘다고 알린다`() {
        assertEquals("쉬는 중 · 추정은 잠시 멈춰요", airStatusLine(recirc4, 4, false, AirLineContext(resting = true)))
        assertEquals("쉬는 중 · 추정은 잠시 멈춰요", airStatusLine(null, 4, false, AirLineContext(resting = true)))
    }

    @Test fun `화면 상태에서 주변 상태 뽑기 - 쿨다운 남은 시간은 올림`() {
        val ui = UiState(nowMs = 100_000, autoIntervene = false, windowPercent = 15, co2LimitPpm = 1600, staleCooldownUntilMs = 100_000 + 17 * 60_000 + 1)
        val ctx = airLineContext(ui)
        assertEquals(AirLineContext(autoIntervene = false, windowPercent = 15, limit = 1600, resting = false, cooldownLeft = 18), ctx)
        assertEquals("데모 속도는 초", 12, airLineContext(UiState(nowMs = 0, demoFast = true, staleCooldownUntilMs = 11_500)).cooldownLeft)
        assertEquals("지난 쿨다운은 없음", null, airLineContext(UiState(nowMs = 5_000, staleCooldownUntilMs = 4_000)).cooldownLeft)
        assertTrue(airLineContext(UiState(rest = RestUi("석적 졸음쉼터", 15, 600))).resting)
    }

    @Test fun `오늘 카드 첫 줄 - 데모 속도면 초`() {
        assertEquals("환기 1회 · 바깥 공기 128분", todayLine(1, 128, demoFast = false))
        assertEquals("환기 2회 · 바깥 공기 41초", todayLine(2, 41, demoFast = true))
    }

    // ---------- 주행 중 데모 탭 ----------

    @Test fun `모의 모드에서는 주행 중 항상 데모 탭`() {
        assertTrue(showDrivingDrawer(UiState(tripState = TripState.DRIVING, gear = Gear.DRIVE, pleosAvailable = false)))
        assertFalse("연결 판정 중에는 숨김", showDrivingDrawer(UiState(tripState = TripState.DRIVING, probing = true)))
        assertFalse("정차 중에는 드로어가 아니라 콘솔", showDrivingDrawer(UiState(tripState = TripState.IDLE)))
    }

    @Test fun `Pleos 연결이면 기어 P 이거나 증거 모드일 때만 데모 탭`() {
        val pleos = UiState(tripState = TripState.DRIVING, gear = Gear.DRIVE, pleosAvailable = true)
        assertFalse(showDrivingDrawer(pleos))
        assertTrue(showDrivingDrawer(pleos.copy(evidenceMode = true)))
        assertTrue(showDrivingDrawer(pleos.copy(gear = Gear.PARK)))
    }

    @Test fun `말로 답하기는 음성 인식기와 마이크 권한이 둘 다 있을 때만`() {
        assertTrue(UiState(sttAvailable = true, micGranted = true).voiceAnswer)
        assertFalse("모의 모드: 음성 인식기 없음", UiState(sttAvailable = false, micGranted = true).voiceAnswer)
        assertFalse("마이크 권한 없음", UiState(sttAvailable = true, micGranted = false).voiceAnswer)
    }

    @Test fun `데모 콘솔의 차 상태 한 줄 - 환기하면 내외기와 풍량이 바뀐 것이 보인다`() {
        assertEquals("내기순환 ON · 풍량 3/8 · 창문 0%", cabinStateLine(UiState(recirculating = true, fanLevel = 3, fanMax = 8)))
        assertEquals("바깥 공기(내기순환 OFF) · 풍량 4/8 · 창문 15%", cabinStateLine(UiState(recirculating = false, fanLevel = 4, fanMax = 8, windowPercent = 15)))
        assertEquals("바깥 공기(내기순환 OFF) · 풍량 모름 · 창문 0%", cabinStateLine(UiState()))
    }
}

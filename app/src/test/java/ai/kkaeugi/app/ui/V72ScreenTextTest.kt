package ai.kkaeugi.app.ui

import ai.kkaeugi.app.AnnounceUi
import ai.kkaeugi.app.RestUi
import ai.kkaeugi.app.RunningBar
import ai.kkaeugi.app.UiState
import ai.kkaeugi.app.ui.theme.KWindow
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.TracePoint
import ai.kkaeugi.engine.TriggerKind
import ai.kkaeugi.engine.TripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.7.2 에서 더한 화면 글과 배치 계산(순수 함수): 좌석 지도, 위 막대, 곡선 아래 둘째 줄, 최근 주행 행, 곡선의 "지금" 자리, 창 배율 */
class V72ScreenTextTest {
    // ---------- 좌석 지도 ----------

    @Test fun `자리별 값이 있으면 그 조합을 그대로 그리고 좌석 센서라고 적는다`() {
        val driverAndRearLeft = listOf<Boolean?>(true, false, true, false, false)
        val m = seatMapUi(driverAndRearLeft, occupants = 2, frontPassenger = false, sensorLive = true)
        assertEquals(driverAndRearLeft, m.seats)
        assertTrue(m.fromSensor)
        assertEquals("좌석 센서", m.label)
        // 순서 채움이라면 조수석이 찼을 조합이다. 자리별 값이 이긴다
        assertFalse(seatFill(2, frontPassenger = false, sensorLive = false) == driverAndRearLeft)
        // 못 읽은 자리(null)도 그대로 넘긴다
        val partial = listOf(true, false, null, null, null)
        assertEquals(partial, seatMapUi(partial, 1, frontPassenger = false, sensorLive = true).seats)
    }

    @Test fun `자리별 값이 없으면 순서대로 채우고 직접 입력이라고 적는다`() {
        val m = seatMapUi(null, occupants = 3, frontPassenger = false, sensorLive = false)
        assertEquals(seatFill(3, frontPassenger = false, sensorLive = false), m.seats)
        assertEquals(listOf<Boolean?>(true, true, false, false, true), m.seats)
        assertFalse(m.fromSensor)
        assertEquals("직접 입력", m.label)
    }

    @Test fun `자리별 값이 화면 인원과 어긋나면 순서 채움으로 돌아간다`() {
        // 데모 콘솔이 4명을 넣었는데 좌석 값은 운전석뿐일 때: 숫자 옆 그림이 숫자와 다르면 안 된다
        val m = seatMapUi(listOf(true, false, false, false, false), occupants = 4, frontPassenger = false, sensorLive = true)
        assertEquals(4, m.seats.count { it == true })
        assertFalse(m.fromSensor)
        // 칸 수가 다섯이 아니어도 쓰지 않는다
        assertFalse(seatMapUi(listOf(true, true), 2, frontPassenger = false, sensorLive = true).fromSensor)
    }

    @Test fun `표본의 좌석 지도는 자리별 값 그대로다`() {
        // 15: 운전석과 뒷줄 왼쪽
        val breath = seatMapUi(PreviewStates.breathHome)
        assertEquals(listOf<Boolean?>(true, false, true, false, false), breath.seats)
        assertEquals(SEAT_LABEL_SENSOR, breath.label)
        // 01: 운전석과 뒷줄 셋(조수석은 비었다)
        assertEquals(listOf<Boolean?>(true, false, true, true, true), seatMapUi(PreviewStates.drivingHome).seats)
        // 13: 조수석에 사람이 있는 4명
        assertEquals(listOf<Boolean?>(true, true, true, false, true), seatMapUi(PreviewStates.proposalPassenger).seats)
        // 모든 주행 표본에서 그림의 인원과 숫자가 같다
        for ((name, s) in PreviewStates.all + PreviewStates.breathShots) {
            if (s.tripState != TripState.DRIVING) continue
            assertEquals(name, s.air?.occupants ?: s.occupants, seatMapUi(s).seats.count { it == true })
        }
    }

    // ---------- 위 막대 ----------

    @Test fun `위 막대는 주행 시간과 꼬리표만 글자로 잇는다`() {
        val base = UiState(tripState = TripState.DRIVING, tripMinutes = 13)
        assertEquals("주행 13분", topBarTripLine(base))
        assertEquals("주행 2시간 54분 · 야간 장거리", topBarTripLine(base.copy(tripMinutes = 174, night = true)))
        assertEquals("주행 13분 · 비: 창문은 닫아 둬요", topBarTripLine(base.copy(raining = true)))
        val resting = base.copy(rest = RestUi(stopName = "석적 졸음쉼터", targetMinutes = 15, remainingSec = 600))
        assertEquals("쉬는 중 13분", topBarTripLine(resting))
        assertEquals("연결", connectionWord(UiState(pleosAvailable = true)))
        assertEquals("확인 중", connectionWord(UiState(probing = true)))
        assertEquals("모의", connectionWord(UiState()))
        assertEquals("대기 · 기어 P", idleTopLine(UiState(gear = Gear.PARK, micGranted = true)))
        assertEquals("도착 · 기어 P · 마이크 권한 없음(버튼으로 답)", idleTopLine(UiState(tripState = TripState.ENDED, gear = Gear.PARK)))
    }

    // ---------- 곡선 아래 둘째 줄 ----------

    @Test fun `둘째 줄은 마지막으로 한 말을 한 군데에서만 말한다`() {
        val home = PreviewStates.breathHome
        assertEquals("추정 1,200ppm까지 내려갔어요. 원래대로 돌렸어요.", homeSecondLine(home))
        // 한 말이 없으면 예보 한 줄
        assertEquals(BreathText.forecastLine(home.breath!!.forecast, false), homeSecondLine(home.copy(lastSaid = null)))
        // 물음은 남기지 않는다(답할 버튼이 없다)
        assertEquals(BreathText.forecastLine(home.breath!!.forecast, false), homeSecondLine(home.copy(lastSaid = PreviewStates.KEEP_TEXT)))
        assertEquals(BreathText.forecastLine(home.breath!!.forecast, false), homeSecondLine(home.copy(lastSaid = PreviewStates.PASSENGER_TEXT)))
    }

    @Test fun `제안, 예고, 실행 중에는 마지막 말 대신 예보를 둔다`() {
        val home = PreviewStates.breathHome
        val forecast = BreathText.forecastLine(home.breath!!.forecast, false)
        assertEquals(forecast, homeSecondLine(home.copy(running = RunningBar(TriggerKind.STALE_AIR, remainingSec = 60, label = "바깥 공기로 바꾸는 중"))))
        assertEquals(forecast, homeSecondLine(home.copy(announce = AnnounceUi(PreviewStates.ANNOUNCE_TEXT, cancelWindowSec = 5))))
        assertEquals(forecast, homeSecondLine(PreviewStates.proposalStale.copy(breath = home.breath)))
    }

    @Test fun `묻지 않는 중이거나 꺼져 있으면 그 사실이 먼저다`() {
        val quiet = PreviewStates.quietOverLimit
        assertEquals(BreathText.NOTE_QUIET, homeSecondLine(quiet))
        assertEquals(BreathText.NOTE_OFF, homeSecondLine(PreviewStates.breathHome.copy(autoIntervene = false)))
        // 추정 전(설계 없음)이고 한 말도 없으면 둘째 줄은 없다
        assertNull(homeSecondLine(UiState(tripState = TripState.DRIVING)))
        assertEquals("바깥 공기로 바꿔 뒀어요.", homeSecondLine(UiState(tripState = TripState.DRIVING, lastSaid = "바깥 공기로 바꿔 뒀어요.")))
    }

    // ---------- 최근 주행 기록 ----------

    @Test fun `최근 주행 기록은 날짜 줄과 요약 줄로 나눈다`() {
        val row = tripRow("11/09 02:40  200분 주행, 환기 2회, 졸음 1회, 쉼 1회, 거절 0회, 최고 2,050ppm 추정")
        assertEquals("11/09 02:40", row.date)
        assertEquals("200분 주행 · 환기 2회 · 졸음 1회 · 쉼 1회 · 최고 2,050ppm 추정", row.summary)
        // 0회인 항목은 뺀다
        assertEquals("85분 주행 · 환기 4회 · 최고 2,010ppm 추정", tripRow("11/07 19:12  85분 주행, 환기 4회, 졸음 0회, 쉼 0회, 거절 0회, 최고 2,010ppm 추정").summary)
        assertEquals("12분 주행", tripRow("11/07 19:12  12분 주행, 환기 0회, 졸음 0회, 쉼 0회, 거절 0회").summary)
        // 날짜가 없는 줄은 통째로 요약
        assertEquals(TripRow("", "기록 한 줄"), tripRow("기록 한 줄"))
        assertEquals(2, RECENT_TRIP_ROWS)
        // 표본의 두 줄도 그렇게 나뉜다
        assertTrue(PreviewStates.idle.recentTrips.map { tripRow(it) }.all { it.date.isNotEmpty() && "0회" !in it.summary })
    }

    @Test fun `숫자와 단위를 나눈다`() {
        assertEquals("12" to "km", numberAndUnit(kmText(12.0)))
        assertEquals("200" to "m", numberAndUnit(kmText(0.2)))
        assertEquals("1,646" to "ppm", numberAndUnit("1,646ppm"))
        assertEquals("--" to "", numberAndUnit("--"))
    }

    // ---------- 곡선의 "지금" 자리 ----------

    @Test fun `출발 직후에는 지금을 왼쪽 4분의 1로 당긴다`() {
        fun trace(minutes: Double) = (0..(minutes * 2).toInt()).map { TracePoint(it * 0.5, 600 + it * 20, 600 + it * 30) }
        assertEquals(10.0, BreathText.pastWindowMin(emptyList()), 1e-9)
        assertEquals(10.0, BreathText.pastWindowMin(trace(2.5)), 1e-9)
        assertEquals(13.0, BreathText.pastWindowMin(trace(13.0)), 1e-9)
        assertEquals(30.0, BreathText.pastWindowMin(trace(45.0)), 1e-9)

        val short = trace(2.5)
        val c = BreathText.chart(short, emptyList(), 2000, 420, BreathText.pastWindowMin(short))
        assertEquals(0.25f, c.nowX, 1e-6f)
        // 지나온 곡선은 "지금"에서 끝나고 왼쪽 끝을 넘지 않는다
        assertEquals(c.nowX, c.actual.last().x, 1e-6f)
        assertTrue(c.actual.first().x >= 0f)

        val mid = trace(13.0)
        val m = BreathText.chart(mid, emptyList(), 2000, 420, BreathText.pastWindowMin(mid))
        assertEquals((13.0 / 43.0).toFloat(), m.nowX, 1e-6f)
        assertEquals("지나온 곡선이 왼쪽 끝에서 시작한다", 0f, m.actual.first().x, 1e-6f)

        val long = trace(45.0)
        assertEquals(0.5f, BreathText.chart(long, emptyList(), 2000, 420, BreathText.pastWindowMin(long)).nowX, 1e-6f)
        // 길이를 주지 않으면 예전과 같이 한가운데
        assertEquals(0.5f, BreathText.chart(short, emptyList(), 2000, 420).nowX, 1e-6f)
    }

    // ---------- 쉼 화면, 창 배율 ----------

    @Test fun `쉼 화면 버튼은 하는 일 그대로 화면 닫기다`() {
        assertEquals("화면 닫기", REST_LEAVE_BUTTON)
        assertEquals("기어를 D 로 넣으면 쉼이 끝나요", restLeaveNote(freshAir = false))
        assertEquals("바깥 공기로 두는 중 · 기어를 D 로 넣으면 쉼이 끝나요", restLeaveNote(freshAir = true))
    }

    @Test fun `창이 넓어지면 같은 비율로 키우되 위아래 끝이 있다`() {
        assertEquals(1f, KWindow.scale(1280f, 720f), 1e-6f)
        // 가로와 세로 가운데 덜 늘어난 쪽을 따른다
        assertEquals(1350f / 1280f, KWindow.scale(1350f, 1000f), 1e-6f)
        assertEquals(1.25f, KWindow.scale(1700f, 900f), 1e-6f)
        assertEquals(1.3f, KWindow.scale(2560f, 1440f), 1e-6f)
        assertEquals(0.8f, KWindow.scale(1024f, 600f), 1e-6f)
        assertEquals(0.8f, KWindow.scale(800f, 480f), 1e-6f)
    }
}

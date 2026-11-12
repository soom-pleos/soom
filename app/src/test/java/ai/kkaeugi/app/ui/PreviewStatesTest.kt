package ai.kkaeugi.app.ui

import ai.kkaeugi.app.AirMath
import ai.kkaeugi.app.DemoText
import ai.kkaeugi.app.speech.speechText
import ai.kkaeugi.engine.CabinAirModel
import ai.kkaeugi.engine.TriggerKind
import ai.kkaeugi.engine.WakeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 스크린샷 표본(PreviewStates)의 숫자가 엔진 기본 설정의 숨 모델과 맞는지 다시 계산해 본다.
 * 화면에 모델로는 나올 수 없는 조합이 실리지 않게 한다.
 */
class PreviewStatesTest {
    private val cfg = WakeConfig()

    /** 출발 직후 상태에서 시작하는 모델. fan/8 */
    private fun model(n: Int, recirculating: Boolean, fan: Int = 3) =
        CabinAirModel(cfg).apply { occupants = n; this.recirculating = recirculating; fanLevel = fan; fanMax = 8; reset(0) }

    private fun minutes(m: Double) = (m * 60_000).toLong()

    @Test fun `01, 08 - 내기순환 2점7분에 혼자 871, 4명 1,646ppm`() {
        val t = minutes(PreviewStates.RECIRC_MINUTES)
        val one = model(1, true).apply { advance(t) }
        val four = model(4, true).apply { advance(t) }
        val alone = PreviewStates.aloneHome.air!!
        val family = PreviewStates.fourHome.air!!
        assertEquals(one.ppm(), alone.ppm)
        assertEquals(four.ppm(), family.ppm)
        // 한도까지 남은 시간(반올림): 혼자 17분, 4명 1분
        assertEquals(one.minutesUntilAbove(2000.0)!!.roundToInt(), alone.minutesToLimit)
        assertEquals(four.minutesUntilAbove(2000.0)!!.roundToInt().coerceAtLeast(1), family.minutesToLimit)
        assertEquals(17, alone.minutesToLimit); assertEquals(1, family.minutesToLimit)
        assertEquals("앱의 반올림 계산과도 같다", alone.minutesToLimit, AirMath.roundedMinutesToLimit(one.minutesToLimit(2000), false, alone.ppm, 1, 2000, cfg))
        // 같은 차, 같은 시간: 인원 말고는 같다
        assertEquals(PreviewStates.aloneHome.recircSinceMs, PreviewStates.fourHome.recircSinceMs)
        assertEquals(PreviewStates.aloneHome.tripMinutes, PreviewStates.fourHome.tripMinutes)
        assertEquals(PreviewStates.drivingHome, PreviewStates.fourHome)
        // 아직 환기한 적이 없다
        assertEquals(0, PreviewStates.drivingHome.todayVentilations); assertEquals(0, PreviewStates.drivingHome.todayFreshAirUnits)
        assertEquals(family.ppm, PreviewStates.drivingHome.todayPeakPpm)
    }

    @Test fun `02 - 4명은 출발 3점7분에 2,000ppm, 원복 없는 제안`() {
        val reach = model(4, true).minutesUntilAbove(2000.0)!!
        assertEquals(3.7, reach, 0.05)
        val s = PreviewStates.proposalStale
        assertEquals(reach.toInt(), s.tripMinutes)
        assertTrue(s.air!!.ppm in 2000..2010)
        assertEquals(0, s.air!!.minutesToLimit)
        assertEquals(reach, (s.nowMs - s.recircSinceMs!!) / 60_000.0, 0.05)
        assertEquals(PreviewStates.KEEP_TEXT, s.proposal!!.text)
        assertTrue(s.proposal!!.text.endsWith("바깥 공기로 바꿔 둘까요?"))
        assertEquals(TriggerKind.STALE_AIR, s.proposal!!.kind)
    }

    @Test fun `09 - 혼자는 출발 20분에 2,000ppm, 2분만 바꾸고 원복하는 제안`() {
        val reach = model(1, true).minutesUntilAbove(2000.0)!!
        assertEquals(20.2, reach, 0.05)
        val s = PreviewStates.proposalSolo
        assertEquals(reach.toInt(), s.tripMinutes)
        assertEquals(1, s.occupants)
        assertTrue(s.air!!.ppm in 2000..2010)
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", s.proposal!!.text)
    }

    @Test fun `04 - 혼자 환기 1분째 1,282ppm`() {
        val m = model(1, true)
        m.advance(minutes(m.minutesUntilAbove(2000.0)!!))
        assertEquals(2000, m.ppm())
        m.recirculating = false; m.fanLevel = 4     // 외기 전환, 풍량 3→4
        m.advance(minutes(20.16) + 60_000)
        val s = PreviewStates.running
        assertEquals(m.ppm().toDouble(), s.air!!.ppm.toDouble(), 3.0)
        assertTrue(s.air!!.ventilating); assertTrue(s.air!!.falling)
        assertFalse(s.recirculating)
        assertEquals("2분 중 1분 남음", 60, s.running!!.remainingSec)
        assertEquals(1, s.todayVentilations); assertEquals(1, s.todayFreshAirUnits)
        assertEquals("환기 중 마지막 제어 호출은 풍량", "setHvacFanSpeed(4) → ok 0.2s", s.evidenceLine)
    }

    @Test fun `03, 05 - 4명이 바깥 공기로 두면 풍량 4단에서 약 1,000ppm 에 머문다`() {
        val steady = model(4, false, fan = 4).steadyStatePpm()
        assertEquals(1001, steady)
        for (s in listOf(PreviewStates.proposalDrowsy, PreviewStates.resting)) {
            assertEquals(steady, s.air!!.ppm)
            assertTrue(s.air!!.ventilating); assertNull(s.air!!.minutesToLimit)
            // 쉼 모드 표본은 운전자가 내기순환을 다시 켠 뒤라 바깥 공기 질문이 떠 있다(추정은 쉬는 동안 멈춘다)
            assertEquals(s === PreviewStates.resting, s.recirculating)
            // 밤길 장거리: 배지가 뜨고 한도선은 1,600
            assertTrue(isNightLongTrip(s))
            assertEquals(PreviewStates.NIGHT_LIMIT, s.co2LimitPpm)
            assertEquals((cfg.co2LimitPpm * 0.8).roundToInt(), s.co2LimitPpm)
            // 출발 4분쯤부터 계속 바깥 공기
            assertTrue(s.todayFreshAirUnits in (s.tripMinutes - 5)..s.tripMinutes)
        }
        assertEquals(PreviewStates.DROWSY_TEXT, PreviewStates.proposalDrowsy.proposal!!.text)
        assertEquals("석적 졸음쉼터", PreviewStates.resting.rest!!.stopName)
    }

    @Test fun `06 - 도착 카드 숫자가 서로 맞는다`() {
        val c = PreviewStates.arrivalSummary
        assertEquals("오늘 환기 ${c.ventilations}번, 쉼터에서 ${c.restMinutes}분 쉬었어요.", c.headline)
        assertTrue("바깥 공기는 주행 시간보다 길 수 없다", c.freshAirMinutes <= c.tripMinutes)
        assertEquals("4명이면 출발 4분쯤부터 바깥 공기", c.tripMinutes - 3, c.freshAirMinutes)
        assertEquals(1, c.restStopsVisited); assertEquals(1, c.restStopsAdded); assertEquals(1, c.drowsySignals)
        assertTrue(c.peakPpm in 2000..2100)
        assertEquals("제안에 바로 답하면 한도 위에 머문 시간은 1분이 안 된다", 0, c.minutesOverLimit)
        assertTrue("공기 줄은 기록 줄에 없다", c.lines.none { it.contains("ppm") })
        assertEquals(4, DemoText.arrivalLines(c, demo = false).size)
        assertEquals("최고 2,050ppm 추정  ·  한도 위 0분(추정)  ·  4명 탑승", DemoText.arrivalAirLine(c, PreviewStates.arrival.todayMaxOccupants, demo = false))
        assertEquals(c.tripMinutes, PreviewStates.arrival.tripMinutes)
    }

    @Test fun `제안 문장은 전부 질문으로 끝나고, 읽을 때는 추정 수치가 빠진다`() {
        for (t in listOf(PreviewStates.KEEP_TEXT, PreviewStates.SOLO_TEXT, PreviewStates.DROWSY_TEXT)) {
            assertTrue(t, t.endsWith("?"))
            assertFalse(speechText(t).contains("ppm"))
            assertFalse(speechText(t).contains("("))
        }
    }

    @Test fun `스크린샷은 14장`() {
        assertEquals(13, PreviewStates.all.size)
        assertEquals(listOf("01", "02", "03", "04", "05", "06", "07", "09", "10", "11", "12", "13", "14"), PreviewStates.all.map { it.first.take(2) })
        assertTrue(PreviewStates.COMPARE_NAME.startsWith("08"))
        assertTrue(PreviewStates.all.any { it.first == "09_제안_혼자" })
        assertTrue(PreviewStates.all.any { it.first == "14_조용_한도초과" })
    }

    @Test fun `14 - 조용 모드에서 한도를 넘으면 한도 초과 문구와 바깥 공기로 버튼이 보인다`() {
        val ui = PreviewStates.quietOverLimit
        assertTrue(showFreshAirButton(ui))
        assertEquals("4명 탑승 · 한도를 넘었어요(추정). 묻지 않는 중이에요", airStatusLine(ui.air, ui.occupants, ui.demoFast, airLineContext(ui)))
        // 같은 조용 모드라도 한도 아래면 버튼이 없고 "화면에만 표시해요"
        val below = ui.copy(air = ui.air!!.copy(ppm = 1900))
        assertFalse(showFreshAirButton(below))
        assertEquals("4명 탑승 · 이번 주행은 화면에만 표시해요", airStatusLine(below.air, below.occupants, below.demoFast, airLineContext(below)))
    }
}

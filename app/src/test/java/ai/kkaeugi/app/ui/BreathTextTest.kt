package ai.kkaeugi.app.ui

import ai.kkaeugi.app.DemoText
import ai.kkaeugi.app.RestUi
import ai.kkaeugi.engine.Alertness
import ai.kkaeugi.engine.BreathDesign
import ai.kkaeugi.engine.Forecast
import ai.kkaeugi.engine.ForecastPoint
import ai.kkaeugi.engine.OutdoorView
import ai.kkaeugi.engine.PmGrade
import ai.kkaeugi.engine.ThreeWay
import ai.kkaeugi.engine.TracePoint
import ai.kkaeugi.engine.TripState
import ai.kkaeugi.engine.VentPlan
import ai.kkaeugi.engine.VentPlanner
import ai.kkaeugi.engine.WakeEngine
import ai.kkaeugi.engine.WayStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "숨 설계" 카드와 도착 카드 세 갈래 비교의 문구와 숫자(v0.7.0). 화면 표본(15, 16)이 엔진을 굴린 값과 맞는지도 본다.
 */
class BreathTextTest {

    private val calm = Alertness(0.0, 0.0, 0.0, clockKnown = true, sleepyHours = false)

    private fun design(
        plan: VentPlan, outdoor: OutdoorView? = null, forecast: Forecast = Forecast(60, false, 3, 20),
        alertness: Alertness = calm,
    ) = BreathDesign(outdoor, plan, forecast, emptyList(), 2000, 600, alertness, null)

    private fun bad(pm: Double = 41.0) = OutdoorView(pm, VentPlanner.grade(pm), "Open-Meteo", 3, 12.0)
    private fun plain(line: String) = line.replace('\u00A0', ' ').replace("\u2060", "")

    // ---------- 설계 한 줄 ----------

    @Test fun `길이는 분과 초로, 데모 속도면 초로`() {
        assertEquals("1분", BreathText.duration(60, false)); assertEquals("1분 30초", BreathText.duration(90, false))
        assertEquals("2분", BreathText.duration(120, false)); assertEquals("45초", BreathText.duration(45, false))
        assertEquals("10초", BreathText.duration(10, true)); assertEquals("90초", BreathText.duration(90, true))
    }

    @Test fun `설계 한 줄 - 밖 미세먼지 나쁨(41) · 4명 · 약 9분마다 1분 30초`() {
        val d = design(VentPlan(9.2, 90, VentPlanner.REASON_BAD, PmGrade.BAD, targetPpm = 1200), bad())
        val line = BreathText.planLine(d, occupants = 4, recirculating = true, demo = false)
        assertEquals("밖 미세먼지 나쁨(41) · 4명 · 약 9분마다 1분 30초", plain(line))
        // 덩어리 안에서는 줄이 바뀌지 않는다: 보통 공백은 " · " 의 앞뒤뿐이다
        assertEquals(4, line.count { it == ' ' })
        assertTrue("덩어리 안에서는 줄이 바뀌지 않게 이음표가 들어 있다", line.contains('\u2060'))
        assertEquals("밖 미세먼지 매우 나쁨(90) · 1명 · 약 14분마다 1분", plain(BreathText.planLine(design(VentPlan(13.6, 60, "", PmGrade.VERY_BAD), bad(90.0)), 1, true, false)))
    }

    @Test fun `설계 한 줄 - 미세먼지 값이 없으면 인원과 간격만`() {
        val d = design(VentPlan(18.8, 120, VentPlanner.REASON_DEFAULT, null))
        assertEquals("1명 · 약 19분마다 2분", plain(BreathText.planLine(d, 1, true, false)))
        assertNull(BreathText.outdoorLabel(null))
        assertEquals("0명이어도 운전자 1명", "1명 · 약 19분마다 2분", plain(BreathText.planLine(d, 0, true, false)))
    }

    @Test fun `설계 한 줄 - 외기로 달리는 중이면 가정이라고 적고, 두는 쪽이 나으면 그렇게 적는다`() {
        val d = design(VentPlan(7.0, 120, "", PmGrade.BAD), bad())
        assertEquals("밖 미세먼지 나쁨(41) · 4명 · 내기순환이면 약 7분마다 2분", plain(BreathText.planLine(d, 4, recirculating = false, demo = false)))
        val keep = design(VentPlan(7.0, 120, VentPlanner.REASON_KEEP, PmGrade.GOOD, keepFresh = true), OutdoorView(9.0, PmGrade.GOOD, "x", 0, 4.0))
        assertEquals("밖 미세먼지 좋음(9) · 4명 · 바깥 공기로 두는 쪽이 나아요", plain(BreathText.planLine(keep, 4, true, false)))
        assertEquals("밖 미세먼지 좋음(9) · 4명 · 바깥 공기로 두는 쪽이 나아요", plain(BreathText.planLine(keep, 4, false, false)))
        val never = design(VentPlan(null, 120, VentPlanner.REASON_NEVER, null))
        assertEquals("1명 · 지금은 한도에 닿지 않아요", plain(BreathText.planLine(never, 1, true, false)))
    }

    @Test fun `설계 한 줄 - 데모 속도면 초`() {
        val d = design(VentPlan(31.0, 10, "", PmGrade.BAD), bad())
        assertEquals("밖 미세먼지 나쁨(41) · 1명 · 약 31초마다 10초", plain(BreathText.planLine(d, 1, true, demo = true)))
    }

    // ---------- 예보 한 줄 ----------

    @Test fun `예보 한 줄`() {
        assertEquals("앞으로 1시간 환기 약 3번 · 첫 번째는 20분 뒤", BreathText.forecastLine(Forecast(60, false, 3, 20), false))
        assertEquals("도착까지 환기 약 5번 · 첫 번째는 4분 뒤", BreathText.forecastLine(Forecast(95, true, 5, 4), false))
        assertEquals("도착까지 환기 약 1번 · 첫 번째는 곧", BreathText.forecastLine(Forecast(30, true, 1, 0), false))
        assertEquals("도착까지 환기 없이 갈 수 있어요", BreathText.forecastLine(Forecast(22, true, 0, null), false))
        assertEquals("앞으로 1시간 환기 없이 갈 수 있어요", BreathText.forecastLine(Forecast(60, false, 0, null), false))
        assertEquals("첫 환기는 4분 뒤, 그 뒤로는 바깥 공기로", BreathText.forecastLine(Forecast(60, false, 1, 4, keepFresh = true), false))
        assertEquals("앞으로 60초 환기 약 2번 · 첫 번째는 21초 뒤", BreathText.forecastLine(Forecast(60, false, 2, 21), true))
        // 묻지 않는 중이거나 숨이 꺼져 있으면 예보 대신 그 사실을 적는다
        val f = Forecast(60, false, 3, 20)
        assertEquals(BreathText.forecastLine(f, false), BreathText.bottomLine(f, false, autoIntervene = true, quiet = false))
        assertEquals(BreathText.NOTE_QUIET, BreathText.bottomLine(f, false, autoIntervene = true, quiet = true))
        assertEquals(BreathText.NOTE_OFF, BreathText.bottomLine(f, false, autoIntervene = false, quiet = true))
    }

    // ---------- 그래프 재료 ----------

    @Test fun `그래프 - 지금이 한가운데, 한도는 높이의 절반쯤, 천장을 넘는 그림자는 잘린다`() {
        val trace = (0..80).map { TracePoint(it * 0.5, 630 + it * 15, 630 + it * 60) }   // 40분, 그림자는 5,430 까지
        val forecast = (0..40).map { ForecastPoint(it.toDouble(), 1830 + it * 2) }
        val c = BreathText.chart(trace, forecast, limit = 2000, outdoorPpm = 430)
        assertEquals(0.5f, c.nowX, 1e-6f)
        assertEquals((2000f - 430f) / (4000f - 430f), c.limitY, 1e-6f)
        assertEquals("최근 30분만 그린다", 61, c.actual.size)
        assertEquals(0f, c.actual.first().x, 1e-6f); assertEquals(0.5f, c.actual.last().x, 1e-6f)
        assertEquals("예보는 30분까지", 31, c.forecast.size)
        assertEquals(0.5f, c.forecast.first().x, 1e-6f); assertEquals(1f, c.forecast.last().x, 1e-6f)
        assertTrue(c.shadowClipped); assertEquals(1f, c.shadow.last().y, 1e-6f)
        assertTrue((c.actual + c.shadow + c.forecast).all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals(4000, BreathText.chartTop(2000)); assertEquals(3200, BreathText.chartTop(1600))
        // 아직 점이 없어도 터지지 않는다
        val empty = BreathText.chart(emptyList(), emptyList(), 2000, 430)
        assertTrue(empty.actual.isEmpty() && empty.forecast.isEmpty()); assertFalse(empty.shadowClipped)
    }

    // ---------- 세 갈래 비교 ----------

    private val w = ThreeWay(
        recircOnly = WayStats(6430, 52, 480.0, 8.0), freshOnly = WayStats(870, 0, 1476.0, 24.6), actual = WayStats(2050, 1, 720.0, 12.0),
        pmGrade = PmGrade.BAD, pmOutdoor = 41,
    )

    @Test fun `세 갈래 - 막대 두 줄, 내기만 외기만 이번 주행 순서, 전부 추정 표기`() {
        val rows = BreathText.threeWayRows(w)
        assertEquals(2, rows.size)
        assertEquals(listOf("내기만", "외기만", "이번 주행"), rows[0].bars.map { it.label })
        assertEquals(listOf("내기만", "외기만", "이번 주행"), rows[1].bars.map { it.label })
        assertTrue(rows.all { it.title.contains("추정") })
        // 내기만과 외기만은 가정으로 낸 값이라 "약"을 붙이고 어림한다(CO2 는 백 단위). 이번 주행은 그대로
        assertEquals(listOf("약 6,400", "약 900", "2,050"), rows[0].bars.map { it.value })
        assertEquals(listOf("약 8", "약 25", "12"), rows[1].bars.map { it.value })
        assertEquals("약 5,600", BreathText.approxPpm(5645)); assertEquals("약 800", BreathText.approxPpm(754)); assertEquals("약 2,000", BreathText.approxPpm(1950))
        assertEquals("약 7", BreathText.approxPm(7.1)); assertEquals("1 미만", BreathText.approxPm(0.4))
        // 막대 길이: 그 줄에서 가장 큰 값이 1
        assertEquals(1f, rows[0].bars[0].fraction, 1e-6f); assertEquals(870f / 6430f, rows[0].bars[1].fraction, 1e-6f)
        assertEquals(1f, rows[1].bars[1].fraction, 1e-6f); assertEquals((8.0 / 24.6).toFloat(), rows[1].bars[0].fraction, 1e-6f)
        assertEquals(listOf(false, false, true), rows[0].bars.map { it.mine })
        // 맞교환이 보인다: CO2 는 내기만이 가장 길고, 미세먼지는 외기만이 가장 길다
        assertEquals("내기만", rows[0].bars.maxByOrNull { it.fraction }!!.label)
        assertEquals("외기만", rows[1].bars.maxByOrNull { it.fraction }!!.label)
        assertTrue(BreathText.threeWayFootnote(w).startsWith("가정에 따라 크게 달라지는 추정이에요")); assertTrue(BreathText.threeWayFootnote(w).contains("나쁨(41)"))
    }

    @Test fun `세 갈래 - 미세먼지 값이 없었으면 CO2 한 줄만`() {
        val none = ThreeWay(WayStats(6430, 52), WayStats(870, 0), WayStats(2050, 1))
        val rows = BreathText.threeWayRows(none)
        assertEquals(1, rows.size); assertEquals("ppm", rows.single().unit)
        assertTrue(BreathText.threeWayFootnote(none).contains(BreathText.THREE_WAY_NO_PM))
        // 값이 전부 0 이어도 터지지 않는다
        assertEquals(1, BreathText.threeWayRows(ThreeWay(WayStats(0, 0), WayStats(0, 0), WayStats(0, 0))).size)
    }

    @Test fun `도착 카드 기록 줄 수 - 세 갈래나 맡기기 질문이 있으면 줄인다`() {
        assertEquals(4, arrivalLineCount(threeWay = false, trustOffer = false))
        assertEquals(2, arrivalLineCount(threeWay = true, trustOffer = false))
        assertEquals(2, arrivalLineCount(threeWay = false, trustOffer = true))
        assertEquals(0, arrivalLineCount(threeWay = true, trustOffer = true))
    }

    // ---------- 트립 카드 ----------

    @Test fun `트립 카드 - 졸리기 쉬운 시간대 알약은 엔진 가중을 따르고 둘까지만 보인다`() {
        val base = PreviewStates.breathHome
        assertTrue(tripPills(base).none { it.text == SLEEPY_HOURS_PILL })
        val sleepy = base.copy(breath = base.breath!!.copy(alertness = Alertness(0.8, 1.0, 0.3, clockKnown = true, sleepyHours = true)))
        assertEquals(listOf(SLEEPY_HOURS_PILL), tripPills(sleepy).map { it.text })
        val all = sleepy.copy(night = true, tripMinutes = 200, raining = true, rest = RestUi("석적 졸음쉼터", 15, 600))
        assertEquals(listOf("쉬는 중", SLEEPY_HOURS_PILL), tripPills(all).map { it.text })
        assertEquals(listOf(SLEEPY_HOURS_PILL, "야간 장거리"), tripPills(all.copy(rest = null)).map { it.text })
        assertFalse("감지라고 쓰지 않는다", SLEEPY_HOURS_PILL.contains("감지"))
        assertEquals("13분 동안 1분 30초 환기 한 번", 1, base.todayVentilations)
        assertEquals("환기 1회 · 바깥 공기 2분 · 최고 2,030ppm 추정", todayCompactLine(base))
        assertEquals("환기 0회 · 바깥 공기 0분", todayCompactLine(base.copy(todayVentilations = 0, todayFreshAirUnits = 0, todayPeakPpm = 0)))
    }

    @Test fun `설정 카드 - 바깥 공기 한 줄`() {
        assertEquals("바깥 공기: PM2.5 41µg/m³(나쁨) · Open-Meteo(CAMS 격자 모델값) · 12분 전", pmSettingsLine(PreviewStates.breathHome))
        assertEquals("바깥 공기: 확인 중", pmSettingsLine(PreviewStates.idle))
        assertEquals("바깥 공기: 꺼짐", pmSettingsLine(PreviewStates.idle.copy(pmEnabled = false)))
    }

    // ---------- 화면 표본 15, 16 ----------

    @Test fun `15 - 숨 설계 홈 표본은 엔진을 굴린 값이다`() {
        val ui = PreviewStates.breathHome
        val d = ui.breath!!
        assertEquals(TripState.DRIVING, ui.tripState); assertEquals(2, ui.occupants)
        assertEquals(PmGrade.BAD, d.outdoor!!.grade); assertEquals(PreviewStates.SAMPLE_PM, d.outdoor!!.pm25, 1e-9)
        assertEquals(90, d.plan.durationSec); assertEquals(1200, d.plan.targetPpm)
        assertEquals("밖 미세먼지 나쁨(41) · 2명 · 약 6분마다 1분 30초", plain(BreathText.planLine(d, ui.occupants, ui.recirculating, ui.demoFast)))
        assertTrue(BreathText.forecastLine(d.forecast, false).startsWith("앞으로 1시간 환기 약 "))
        // 13분 달렸고 환기를 한 번 했다: 곡선에 내려간 구간이 있고 그림자는 실제보다 위에 있다. 지금은 내기순환으로 다시 오르는 중
        assertEquals(1, ui.todayVentilations)
        assertFalse(ui.air!!.ventilating)
        assertTrue(d.trace.size >= 26)
        assertTrue(d.trace.zipWithNext().any { (a, b) -> b.ppm < a.ppm - 100 })
        assertTrue("그림자 ${d.shadowRecircPpm}, 실제 ${ui.air!!.ppm}", d.shadowRecircPpm > ui.air!!.ppm + 500)
        assertEquals("곡선은 지금 값에서 끝난다", ui.air!!.ppm.toDouble(), d.trace.last().ppm.toDouble(), 15.0)
        assertFalse(d.alertness.sleepyHours)
        assertNotNull(d.outdoor!!.cabinPm)
    }

    @Test fun `16 - 도착 카드 세 갈래 표본은 맞교환을 보여 준다`() {
        val card = PreviewStates.threeWaySummary
        val w = card.threeWay!!
        assertEquals(60, card.tripMinutes)
        assertTrue(w.recircOnly.peakPpm > w.actual.peakPpm && w.actual.peakPpm > w.freshOnly.peakPpm)
        assertTrue(w.recircOnly.pmAverage!! < w.actual.pmAverage!! && w.actual.pmAverage!! < w.freshOnly.pmAverage!!)
        assertEquals(card.peakPpm, w.actual.peakPpm)
        assertEquals(2, BreathText.threeWayRows(w).size)
        assertEquals(PmGrade.BAD, w.pmGrade); assertEquals(41, w.pmOutdoor)
        assertEquals(PreviewStates.arrivalThreeWay.arrival, card)
        // 기존 표본(06, 11)에는 세 갈래가 없다. 화면은 예전과 같다
        assertNull(PreviewStates.arrivalSummary.threeWay); assertNull(PreviewStates.trustSummary.threeWay)
    }

    @Test fun `스크린샷은 16장 - 기존 13장과 인원 비교에 숨 설계 2장`() {
        assertEquals(listOf("15_숨설계_홈", "16_도착카드_세갈래"), PreviewStates.breathShots.map { it.first })
        assertEquals(16, PreviewStates.all.size + 1 + PreviewStates.breathShots.size)
    }

    @Test fun `01, 04, 14 - 기존 홈 표본의 숨 설계 카드도 엔진 값이다`() {
        val home = PreviewStates.drivingHome
        assertNull("바깥 미세먼지 값이 없는 표본", home.breath!!.outdoor)
        assertEquals(home.air!!.ppm.toDouble(), home.breath!!.trace.last().ppm.toDouble(), 5.0)
        assertTrue(home.breath!!.plan.keepFresh)
        assertEquals("4명 · 바깥 공기로 두는 쪽이 나아요", plain(BreathText.planLine(home.breath!!, 4, true, false)))
        val running = PreviewStates.running.breath!!
        assertTrue("환기 중인 곡선은 내려가는 중", running.trace.takeLast(2).let { it[1].ppm < it[0].ppm })
        assertTrue(running.forecast.points.first().venting)
        val quiet = PreviewStates.quietOverLimit.breath!!
        assertEquals(2600.0, quiet.trace.last().ppm.toDouble(), 30.0)
    }

    // ---------- 낮잠 안내 ----------

    @Test fun `낮잠 안내는 데모 속도에서 초로 읽힌다`() {
        assertEquals("커피 한 잔 뒤 15초 안쪽으로 눈을 붙이면 도움이 된다는 연구가 있어요.", DemoText.minutesToSeconds(WakeEngine.napTip(), true))
        assertEquals("15초 지났어요. 몸을 풀고 출발해요.", DemoText.minutesToSeconds(WakeEngine.napDoneText(), true))
        assertEquals(WakeEngine.napTip(), DemoText.minutesToSeconds(WakeEngine.napTip(), false))
    }

    @Test fun `화면 문구에 금지 표현과 긴 대시가 없다`() {
        val texts = listOf(
            BreathText.CARD_TITLE, BreathText.THREE_WAY_NOTE, BreathText.THREE_WAY_NO_PM, SLEEPY_HOURS_PILL,
            BreathText.forecastLine(Forecast(60, false, 3, 20), false), BreathText.forecastLine(Forecast(60, true, 0, null), false),
            plain(BreathText.planLine(PreviewStates.breathHome.breath!!, 2, true, false)),
        ) + BreathText.threeWayRows(w).map { it.title }
        for (t in texts) {
            for (bad in listOf("측정", "감지", "예방", "방지", "최적", "최초", "유일")) assertFalse("$t 에 '$bad'", t.contains(bad))
            for (dash in listOf("—", "–", "ㅡ")) assertFalse(t, t.contains(dash))
        }
    }
}

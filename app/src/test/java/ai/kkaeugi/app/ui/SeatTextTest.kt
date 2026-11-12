package ai.kkaeugi.app.ui

import ai.kkaeugi.app.DemoSettings
import ai.kkaeugi.app.DemoText
import ai.kkaeugi.app.SeatProfileCodec
import ai.kkaeugi.engine.ArrivalSummary
import ai.kkaeugi.engine.SeatCare
import ai.kkaeugi.engine.SeatProfile
import ai.kkaeugi.engine.SeatStats
import ai.kkaeugi.engine.Seats
import ai.kkaeugi.engine.TriggerKind
import ai.kkaeugi.engine.WakeConfig
import ai.kkaeugi.engine.WakeEngine
import ai.kkaeugi.engine.WakeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.7.3 자리별 설정과 자리별 요약의 화면 글, 저장 형식, 화면 표본을 본다 */
class SeatTextTest {

    private val D = SeatProfile.DEFAULT
    private val family = PreviewStates.FAMILY_PROFILES

    // ---------- 자리 설정 ----------

    @Test fun `시트의 한 줄은 바뀌는 것만 말한다`() {
        assertEquals("바꾸는 것이 없어요", SeatText.effect(SeatProfile.DEFAULT))
        assertEquals("환기를 조금 일찍 물어요", SeatText.effect(SeatProfile.CHILD))
        assertEquals("환기를 조금 일찍 묻고, 90분마다 쉬어요", SeatText.effect(SeatProfile.PREGNANT))
        assertEquals("90분마다 쉬어요", SeatText.effect(SeatProfile.SENIOR))
        assertEquals("미세먼지를 먼저 봐요", SeatText.effect(SeatProfile.RESPIRATORY))
        assertEquals("미세먼지를 먼저 보고, 90분마다 쉬어요", SeatText.effect(SeatProfile.CARDIO))
        // 간격은 설정값을 따른다
        assertEquals("75분마다 쉬어요", SeatText.effect(SeatProfile.SENIOR, WakeConfig(sitBreakMinutesShort = 75)))
        assertEquals("조수석 설정", SeatText.sheetTitle(Seats.FRONT_PASSENGER))
    }

    @Test fun `정차 화면 목록에는 고른 자리만 나오고 지도에는 그 자리에 점을 찍는다`() {
        assertEquals(listOf("조수석" to "임산부", "뒷줄 왼쪽" to "어린이"), SeatText.chosen(family))
        assertEquals(listOf(false, true, true, false, false), SeatText.marked(family))
        assertEquals(emptyList<Pair<String, String>>(), SeatText.chosen(SeatProfileCodec.NONE))
        assertEquals(List(5) { false }, SeatText.marked(emptyList()))
    }

    @Test fun `화면 글에 쓰지 않기로 한 말이 없다`() {
        val banned = listOf("진단", "치료", "측정", "건강", "예방", "보장", "감지")
        val texts = SeatProfile.entries.map { SeatText.effect(it) } + SeatProfile.entries.map { it.label } + listOf(
            SeatText.PROFILE_NOTE, SeatText.STICKY_NOTE, SeatText.HINT, SeatText.MANUAL_NOTE, SeatText.FOOTNOTE, SeatText.FOOTNOTE_CABIN,
            WakeEngine.WALK_ADDED_TEXT, WakeEngine.WALK_FAILED_TEXT, WakeEngine.walkTip(5), WakeEngine.walkDoneText(5), PreviewStates.WALK_TEXT,
        )
        for (t in texts) for (b in banned) assertFalse("$t 에 '$b'", t.contains(b))
        // "의학적"은 조언이 아니라고 밝히는 한 줄에만 나온다
        assertEquals(listOf(SeatText.PROFILE_NOTE), texts.filter { it.contains("의학") })
        assertTrue(SeatText.PROFILE_NOTE.endsWith("의학적 조언이 아니에요."))
        for (t in texts) assertFalse("긴 대시: $t", t.contains('—') || t.contains('–') || t.contains('ㅡ'))
    }

    @Test fun `자리 설정 저장 형식은 다섯 칸이고 모르는 이름은 기본으로 읽는다`() {
        assertEquals("DEFAULT,PREGNANT,CHILD,DEFAULT,DEFAULT", SeatProfileCodec.encode(family))
        assertEquals(family, SeatProfileCodec.decode(SeatProfileCodec.encode(family)))
        assertEquals(SeatProfileCodec.NONE, SeatProfileCodec.decode(null))
        assertEquals(listOf(D, SeatProfile.CARDIO, D, D, D), SeatProfileCodec.decode("X, CARDIO"))
        assertEquals(listOf(D, D, D, D, SeatProfile.SENIOR), SeatProfileCodec.with(SeatProfileCodec.NONE, Seats.REAR_RIGHT, SeatProfile.SENIOR))
        assertEquals(5, SeatProfileCodec.encode(List(9) { SeatProfile.CHILD }).split(',').size)
    }

    // ---------- 주행 홈 ----------

    @Test fun `살피는 것 한 줄`() {
        assertEquals("미세먼지 먼저 · 90분마다 쉬기", SeatText.careLine(SeatCare(pmFirst = true, breakMinutes = 90)))
        assertEquals("공기 여유 넓게 · 120분마다 쉬기", SeatText.careLine(SeatCare(wideMargin = true, breakMinutes = 120)))
        assertEquals("미세먼지 먼저, 공기 여유 넓게 · 90분마다 쉬기", SeatText.careLine(SeatCare(pmFirst = true, wideMargin = true, breakMinutes = 90)))
        assertEquals("기본 설정 · 120분마다 쉬기", SeatText.careLine(SeatCare(breakMinutes = 120)))
        // 걷기 쉼이 꺼져 있으면 간격은 적지 않는다. 데모 속도면 초
        assertEquals("기본 설정", SeatText.careLine(SeatCare()))
        assertEquals("미세먼지 먼저 · 90초마다 쉬기", SeatText.careLine(SeatCare(pmFirst = true, breakMinutes = 90), demo = true))
    }

    @Test fun `앉은 시간 한 줄`() {
        assertEquals("앉은 지 1시간 12분", SeatText.sitLine(SeatCare(sitMinutes = 72)))
        assertEquals("앉은 지 45분", SeatText.sitLine(SeatCare(sitMinutes = 45)))
        assertEquals("방금 앉았어요", SeatText.sitLine(SeatCare(sitMinutes = 0)))
        assertEquals("앉은 지 72초", SeatText.sitLine(SeatCare(sitMinutes = 72), demo = true))
    }

    // ---------- 도착 카드 ----------

    private val card = ArrivalSummary(
        headline = "오늘 환기 3번 했어요.", tripMinutes = 130, ventilations = 3, freshAirMinutes = 6, drowsySignals = 0,
        restStopsAdded = 1, restStopsVisited = 1, restMinutes = 6, restStopsPassed = 0, declined = 0, lines = emptyList(),
        seatHeadline = "오늘 2시간 10분, 둘이 탔어요.",
        seats = listOf(
            SeatStats(Seats.DRIVER, D, minutesAboard = 130, minutesOverLimit = 12, averagePpm = 1400, pmAverage = 13.4, pmExposure = 1742.0, longestSitMinutes = 106, walkBreaks = 1),
            SeatStats(Seats.REAR_LEFT, SeatProfile.CHILD, minutesAboard = 60, minutesOverLimit = 0, averagePpm = 1300, pmAverage = 0.4, longestSitMinutes = 60, walkBreaks = 0, boardedMidTrip = true),
        ),
        cabin = SeatStats(Seats.CABIN, minutesAboard = 130, minutesOverLimit = 12, averagePpm = 1400, longestSitMinutes = 106, walkBreaks = 1),
    )

    @Test fun `자리마다 한 줄 숫자와 단위를 나눠 적는다`() {
        val (driver, child) = SeatText.rows(card, maxOccupants = 2, demo = false)
        assertEquals("운전석", driver.name); assertEquals("", driver.tag)
        assertEquals(SeatText.Cell("12", "분"), driver.over)
        assertEquals(SeatText.Cell("13", "µg/m³"), driver.pm)
        assertEquals(listOf(SeatText.Cell("1", "시간"), SeatText.Cell("46", "분")), driver.sit)
        assertEquals(SeatText.Cell("1", "회"), driver.walk)
        assertEquals("뒷줄 왼쪽", child.name); assertEquals("어린이 · 중간에 탐", child.tag)
        assertEquals(SeatText.Cell("0", "분"), child.over)
        assertEquals(SeatText.Cell("1 미만", "µg/m³"), child.pm)
        assertEquals(listOf(SeatText.Cell("1", "시간")), child.sit)
        assertEquals(SeatText.Cell("없음"), child.walk)
        assertEquals(5, SeatText.HEADERS.size); assertEquals(5, SeatText.HEADER_NOTES.size)
    }

    @Test fun `데모 속도면 한도 위 시간과 앉은 시간은 초다`() {
        val driver = SeatText.rows(card, 2, demo = true).first()
        assertEquals(SeatText.Cell("12", "초"), driver.over)
        assertEquals(listOf(SeatText.Cell("106", "초")), driver.sit)
    }

    @Test fun `자리별 값이 없는 주행은 차 전체 한 줄이고 미세먼지는 세 갈래 비교에서 가져온다`() {
        val three = PreviewStates.threeWaySummary
        assertEquals(emptyList<SeatStats>(), three.seats)
        val row = SeatText.rows(three, maxOccupants = 2, demo = false).single()
        assertEquals("차 전체", row.name); assertEquals("2명 탑승", row.tag)
        assertEquals(SeatText.Cell(three.minutesOverLimit.toString(), "분"), row.over)
        assertEquals(SeatText.Cell("13", "µg/m³"), row.pm)
        assertEquals(SeatText.FOOTNOTE_CABIN, SeatText.footnote(three))
        assertEquals(SeatText.FOOTNOTE, SeatText.footnote(card))
        // 바깥 값을 못 받은 주행
        val noPm = SeatText.rows(card.copy(seats = emptyList(), threeWay = null), 1, false).single()
        assertEquals(SeatText.Cell("값 없음"), noPm.pm)
        // 주행한 적이 없는 요약(옛 형식)은 줄이 없다
        assertEquals(emptyList<SeatText.Row>(), SeatText.rows(card.copy(seats = emptyList(), cabin = null), 1, false))
    }

    @Test fun `제목은 엔진의 자리별 문장이고 없으면 기존 첫 문장이다`() {
        assertEquals("오늘 2시간 10분, 둘이 탔어요.", SeatText.headline(card, demo = false))
        assertEquals("오늘 환기 3번 했어요.", SeatText.headline(card.copy(seatHeadline = ""), demo = false))
    }

    @Test fun `접은 세 갈래 한 줄은 어림으로 적는다`() {
        val (recirc, fresh) = SeatText.whatIf(PreviewStates.threeWaySummary.threeWay!!)
        assertEquals("내기만 탔다면 CO2 최고 약 5,600ppm · 미세먼지 약 7µg/m³", recirc)
        assertEquals("외기만 탔다면 CO2 최고 약 800ppm · 미세먼지 약 25µg/m³", fresh)
    }

    @Test fun `걷기 쉼 쉼터의 안내 문장`() {
        assertEquals("석적 졸음쉼터. 내려서 5분 걸어요", DemoText.restLine("석적 졸음쉼터", 5, demo = false, walk = true))
        assertEquals("석적 졸음쉼터. 내려서 5초 걸어요(데모)", DemoText.restLine("석적 졸음쉼터", 5, demo = true, walk = true))
        assertEquals("석적 졸음쉼터. 내려서 5초 걸어요", DemoText.restLine("석적 졸음쉼터", 5, demo = true, forSpeech = true, walk = true))
        // 보통 쉼은 그대로다
        assertEquals("석적 졸음쉼터. 15분만 쉬고 가세요", DemoText.restLine("석적 졸음쉼터", 15, demo = false))
        assertEquals("석적 졸음쉼터. 15초만 쉬고 가세요(데모)", DemoText.restLine("석적 졸음쉼터", 15, demo = true))
    }

    // ---------- 설정과 화면 표본 ----------

    @Test fun `앱은 걷기 쉼을 켜서 쓰고 기본 조립은 꺼 둔다`() {
        assertFalse(DemoSettings.build(demoFast = false).walkBreak)
        assertTrue(DemoSettings.build(demoFast = false, walkBreak = true).walkBreak)
        assertTrue(DemoSettings.build(demoFast = true, walkBreak = true).demoFast)
    }

    @Test fun `주행 홈 표본은 엔진이 낸 값이다`() {
        val s = PreviewStates.careHome
        assertEquals(SeatCare(pmFirst = true, wideMargin = false, breakMinutes = 90, sitMinutes = s.care.sitMinutes, sitSeat = Seats.DRIVER), s.care)
        assertTrue("앉은 시간 ${s.care.sitMinutes}", s.care.sitMinutes in 72..82)
        assertEquals(s.tripMinutes, s.care.sitMinutes)
        assertEquals("미세먼지 먼저 · 90분마다 쉬기", SeatText.careLine(s.care))
        // 바깥 등급은 보통 그대로 보여 주고, 설계는 한 단계 나쁘게 본 값으로 한다(짧게 바꾼다)
        assertEquals("보통", s.breath!!.outdoor!!.grade.label)
        assertEquals(90, s.breath!!.plan.durationSec)
        assertFalse(s.air!!.ventilating)
        assertEquals(2, seatMapUi(s).seats.count { it == true })
    }

    @Test fun `걷기 쉼 제안 표본은 엔진 문장과 같다`() {
        val d = BreathSampleDrive(
            2, pm25 = null, minuteOfDay = 14 * 60, answer = null,
            seats = listOf(true, true, false, false, false), profiles = listOf(D, SeatProfile.CARDIO), config = WakeConfig(walkBreak = true),
            candidate = ai.kkaeugi.engine.RestStopInfo("석적", "경부선 서울방향", 12.0, 36.0516, 128.4081),
        )
        // 바깥 공기로 달려 환기 제안이 끼지 않게 한다
        d.signal { ai.kkaeugi.engine.RecirculationChanged(it, false) }
        d.run(91 * 60) { it.lastProposal != null }
        assertEquals(PreviewStates.WALK_TEXT, d.lastProposal)
        assertEquals(TriggerKind.WALK_BREAK, PreviewStates.proposalWalk.proposal!!.kind)
    }

    @Test fun `자리별 도착 카드 표본`() {
        val card = PreviewStates.seatSummary
        assertEquals("오늘 2시간 10분, 넷이 탔어요.", card.seatHeadline)
        assertEquals(listOf("운전석", "조수석", "뒷줄 왼쪽", "뒷줄 오른쪽"), SeatText.rows(card, 4, false).map { it.name })
        assertEquals(listOf("", "임산부", "어린이", "중간에 탐"), SeatText.rows(card, 4, false).map { it.tag })
        assertEquals(1, card.walkBreaks)
        assertEquals(listOf(1, 1, 1, 1), card.seats.map { it.walkBreaks })
        // v0.7.4: 미세먼지 나쁨에 넷이면 약한 바람으로 계속 들여 한도를 넘지 않는다(환기 1번). 늦게 탄 자리는 가장 긴 착석이 더 짧다
        assertEquals(1, card.ventilations)
        assertEquals(0, card.minutesOverLimit)
        assertTrue(card.seats[3].minutesOverLimit <= card.seats[0].minutesOverLimit)
        assertTrue(card.seats[3].longestSitMinutes < card.seats[0].longestSitMinutes)
        assertEquals(card.minutesOverLimit, card.seats[0].minutesOverLimit)
        assertNotNull(card.threeWay)
        assertNull(card.trustOffer)
        // 손으로 적은 두 표본(06, 11)도 자리별 줄이 있다
        assertEquals(4, SeatText.rows(PreviewStates.arrivalSummary, 4, false).size)
        assertEquals(1, SeatText.rows(PreviewStates.trustSummary, 1, false).size)
    }

    @Test fun `정차 표본과 시트 표본`() {
        assertEquals(family, PreviewStates.idle.seatProfiles)
        assertNull(PreviewStates.idle.profileSheetSeat)
        assertEquals(Seats.FRONT_PASSENGER, PreviewStates.profileSheet.profileSheetSeat)
        assertEquals(listOf("20_자리설정_시트", "21_주행중_살피는것", "22_제안_걷기쉼", "23_도착카드_자리별", "24_자가진단_프로필_Gleo"), PreviewStates.seatShots.map { it.first })
        assertEquals(7, PreviewStates.nightShots.size)
    }
}

package ai.kkaeugi.app

import ai.kkaeugi.engine.ArrivalSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoTextTest {
    @Test fun `쉼 문구 - 실제는 분, 데모는 초와 데모 표시`() {
        assertEquals("석적 졸음쉼터. 15분만 쉬고 가세요", DemoText.restLine("석적 졸음쉼터", 15, demo = false))
        assertEquals("석적 졸음쉼터. 15초만 쉬고 가세요(데모)", DemoText.restLine("석적 졸음쉼터", 15, demo = true))
        assertEquals("석적 졸음쉼터. 15초만 쉬고 가세요", DemoText.restLine("석적 졸음쉼터", 15, demo = true, forSpeech = true))
    }

    @Test fun `RestEnded 문장의 분을 초로 - 숫자 뒤만`() {
        assertEquals("17초 푹 쉬셨어요. 조심히 가요", DemoText.minutesToSeconds("17분 푹 쉬셨어요. 조심히 가요", demo = true))
        assertEquals("17분 푹 쉬셨어요. 조심히 가요", DemoText.minutesToSeconds("17분 푹 쉬셨어요. 조심히 가요", demo = false))
        assertEquals("기분 좋게 3초 쉬셨어요", DemoText.minutesToSeconds("기분 좋게 3분 쉬셨어요", demo = true))
        assertEquals("잠깐 쉬셨어요", DemoText.minutesToSeconds("잠깐 쉬셨어요", demo = true))
    }

    @Test fun `최근 기록은 주행 요약만`() {
        val e = listOf(
            TripLog.Entry(1, "summary", "a", tripMinutes = 10),
            TripLog.Entry(2, "answer", "환기 제안 동의(버튼)"),
            TripLog.Entry(3, "summary", "b", tripMinutes = 20),
            TripLog.Entry(4, "answer", "졸음 제안 거절(음성)"),
        )
        assertEquals(listOf(20, 10), TripLog.recentSummaries(e, 5).map { it.tripMinutes })
        assertEquals(listOf(20), TripLog.recentSummaries(e, 1).map { it.tripMinutes })
    }

    // ---------- 도착 카드 ----------

    private val card = ArrivalSummary(
        headline = "오늘 환기 3번, 쉼터에서 15분 쉬었어요.",
        tripMinutes = 122, ventilations = 3, freshAirMinutes = 3, drowsySignals = 1,
        restStopsAdded = 1, restStopsVisited = 1, restMinutes = 15, restStopsPassed = 0, declined = 0,
        lines = listOf(
            "출발 직후 바깥 공기로 환기",
            "출발 1분 뒤 졸음 경고(운전자 주의 경고)",
            "출발 1분 뒤 석적 졸음쉼터 경유지 추가",
            "출발 2분 뒤 석적 졸음쉼터에서 15분 휴식",
        ),
        peakPpm = 2040, minutesOverLimit = 4,
    )

    @Test fun `도착 카드 첫 문장 - 데모 속도면 쉼 시간을 초로`() {
        assertEquals("오늘 환기 3번, 쉼터에서 15분 쉬었어요.", DemoText.arrivalHeadline(card, demo = false))
        assertEquals("오늘 환기 3번, 쉼터에서 15초 쉬었어요.", DemoText.arrivalHeadline(card, demo = true))
        val over = card.copy(headline = "오늘 환기 2번, 추정 2,000ppm 을 넘긴 건 4분이었어요.")
        assertEquals("오늘 환기 2번, 추정 2,000ppm 을 넘긴 건 4초이었어요.", DemoText.arrivalHeadline(over, demo = true))
    }

    @Test fun `도착 카드 숫자 줄 - 실제 속도는 엔진 값 그대로`() {
        val m = DemoText.arrivalMetrics(card.copy(freshAirMinutes = 6), demo = false, freshUnits = 999).associate { it.label to it.value }
        assertEquals("2시간 2분", m["주행"])
        assertEquals("3회", m["환기"])
        assertEquals("엔진이 센 분", "6분", m["바깥 공기"])
        assertEquals("1회", m["졸음 경고"])
        assertEquals("1회 15분", m["쉼터"])
        assertEquals("0회", m["거절"])
        assertEquals("지나친 쉼터가 없으면 칸도 없다", null, m["지나침"])
    }

    @Test fun `도착 카드 숫자 줄 - 데모 속도는 쉼 시간과 바깥 공기를 초로`() {
        val m = DemoText.arrivalMetrics(card, demo = true, freshUnits = 54).associate { it.label to it.value }
        assertEquals("1회 15초", m["쉼터"])
        assertEquals("앱이 엔진과 같은 기준으로 센 초", "54초", m["바깥 공기"])
        assertEquals("주행 시간은 엔진이 센 실제 분 그대로", "2시간 2분", m["주행"])
        assertEquals("1회", DemoText.arrivalMetrics(card.copy(restStopsPassed = 1), demo = true, freshUnits = 0).first { it.label == "지나침" }.value)
    }

    @Test fun `도착 카드 공기 줄 - 한 줄, 수치 옆에 추정`() {
        assertEquals("최고 2,040ppm 추정  ·  한도 위 4분(추정)  ·  4명 탑승", DemoText.arrivalAirLine(card, 4, demo = false))
        assertEquals("최고 2,040ppm 추정  ·  한도 위 4초(추정)  ·  4명 탑승", DemoText.arrivalAirLine(card, 4, demo = true))
        assertEquals("1명 탑승", DemoText.arrivalAirLine(card.copy(peakPpm = 0), 0, demo = false))
    }

    @Test fun `도착 카드 기록 줄 - 공기 줄은 빼고, 데모 속도면 머리말의 분은 그대로 두고 쉼 시간만 초로`() {
        assertEquals(card.lines, DemoText.arrivalLines(card, demo = false))
        val demo = DemoText.arrivalLines(card, demo = true)
        assertEquals("출발 2분 뒤 석적 졸음쉼터에서 15초 휴식", demo.last())
        assertEquals("출발 1분 뒤 졸음 경고(운전자 주의 경고)", demo[1])
        assertEquals("출발 직후 바깥 공기로 환기", demo[0])
        // 예전 엔진처럼 공기 줄이 섞여 와도 화면에는 한 번만(위의 공기 줄)
        val withAir = card.copy(lines = listOf("4명 탑승, 최고 추정 2,300ppm") + card.lines)
        assertEquals(card.lines, DemoText.arrivalLines(withAir, demo = false))
        assertEquals(4, DemoText.arrivalLines(card.copy(lines = card.lines + "출발 3분 뒤 제안 거절(환기)"), demo = false).size)
    }
}

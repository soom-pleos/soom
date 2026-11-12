package ai.kkaeugi.engine

import ai.kkaeugi.engine.Round6Test.Car
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2시간 주행을 엔진으로 실제 굴린 표(v0.7.4). 바깥 미세먼지(좋음 10, 보통 25, 나쁨 41) × 맡기기(켬, 끔) × 인원.
 * 제안에는 모두 동의한다(듣는 데 6초, 묻는 제안은 5초 뒤에 답한다. 맡긴 환기의 예고에는 답하지 않고 취소 창 5초를 그대로 보낸다).
 * 차는 풍량 3/8, 내기순환으로 출발한다. 바깥 값은 10분마다 다시 넣는다.
 *
 * 왜 있는가: v0.7.3 의 도착 카드 표본(4명, 나쁨, 2시간 10분)이 "환기 18번, 한도 위 70분"이었다. 표본이 임의 숫자가 아니라
 * 엔진을 굴린 값이었고, 규칙이 그런 결과를 냈다. 이 표가 고친 뒤의 결과를 지킨다.
 * `gradle :engine:test --tests '*TwoHourTableTest*' -i` 로 표를 볼 수 있다
 */
class TwoHourTableTest {

    data class Row(
        val occupants: Int, val pm: Double, val delegated: Boolean,
        val ventilations: Int, val asked: Int, val announced: Int, val overLimit: Int, val peak: Int, val freshMinutes: Int,
        val pmAverage: Double, val pmFreshOnly: Double, val pmRecircOnly: Double, val recircOnlyPeak: Int, val variants: Map<String, Int>,
    ) {
        val perHour: Double get() = ventilations / 2.0
        fun line(): String = "%d명 | 밖 %s(%d) | 맡기기 %s | 환기 %d번(시간당 %.1f) | 물음 %d, 예고 %d | 한도 위 %d분 | 최고 %,d | 바깥 공기 %d분 | PM 평균 %.1f (외기만 %.1f, 내기만 %.1f) | 내기만 최고 %,d | %s".format(
            occupants, VentPlanner.grade(pm).label, pm.toInt(), if (delegated) "켬" else "끔", ventilations, perHour, asked, announced,
            overLimit, peak, freshMinutes, pmAverage, pmFreshOnly, pmRecircOnly, recircOnlyPeak, variants,
        )
    }

    private fun drive(occupants: Int, pm: Double, delegated: Boolean, minutes: Int = 120): Row {
        val c = Car(WakeConfig(), TrustState(delegated = delegated)).also { it.autoAnswer = true; it.answerDelaySec = 8; it.speechSec = 6 }
        c.send { OutdoorAir(it, pm, "테스트", 0) }
        c.drive(occupants = occupants)
        repeat(minutes) { m -> c.run(60); if (m % 10 == 9) c.send { OutdoorAir(it, pm, "테스트", 0) } }
        val variants = c.proposals.groupingBy { it.variant }.eachCount()
        val card = c.arrive()
        val w = card.threeWay!!
        return Row(
            occupants, pm, delegated, card.ventilations, c.count<WakeEvent.Propose>(), c.count<WakeEvent.Announce>(), card.minutesOverLimit, card.peakPpm,
            card.freshAirMinutes, w.actual.pmAverage!!, w.freshOnly.pmAverage!!, w.recircOnly.pmAverage!!, w.recircOnly.peakPpm, variants,
        )
    }

    private val grades = listOf(10.0, 25.0, 41.0)

    @Test
    fun `넷이 2시간 - 어느 등급에서도 한 번 묻고 바깥 공기로 두며 한도 위에 머물지 않는다`() {
        for (pm in grades) for (delegated in listOf(true, false)) {
            val r = drive(4, pm, delegated)
            println(r.line())
            assertEquals(1, r.ventilations, r.line())
            assertEquals(1, r.asked, "원복이 없는 변형은 맡겨 두어도 묻는다. ${r.line()}")
            assertEquals(0, r.announced, r.line())
            assertEquals(0, r.overLimit, r.line())
            assertTrue(r.peak < 2100, r.line())
            assertTrue(r.recircOnlyPeak > 12_000, "내기만 탔다면: ${r.line()}")
            // 나쁨이면 약한 바람(KEEP_LOW), 아니면 바깥 공기로 두기(KEEP)
            assertEquals(mapOf((if (pm > 35) "KEEP_LOW" else "KEEP") to 1), r.variants, r.line())
            // 바깥 공기로 계속 두니 미세먼지 평균은 "내내 외기"와 거의 같다. 이것이 값이다
            assertTrue(r.pmAverage > r.pmFreshOnly * 0.9, r.line())
        }
    }

    @Test
    fun `셋도 넷과 같고, 혼자와 둘은 시간 분할을 그대로 쓴다`() {
        for (pm in grades) for (delegated in listOf(true, false)) {
            val three = drive(3, pm, delegated)
            println(three.line())
            assertEquals(1, three.ventilations, three.line()); assertEquals(0, three.overLimit, three.line())
        }
        for (n in listOf(1, 2)) for (pm in grades) for (delegated in listOf(true, false)) {
            val r = drive(n, pm, delegated)
            println(r.line())
            assertTrue(r.overLimit <= 5, r.line())
            assertTrue(r.peak < 2100, r.line())
            // 혼자는 어느 등급에서도 시간당 4번 이하
            if (n == 1) assertTrue(r.perHour <= 4.0, r.line())
        }
        // 둘이 미세먼지 나쁜 날에 타면 짧게 되풀이한다: 한도는 지키지만 시간당 8~9번이다(미세먼지 평균은 "내내 외기"의 절반).
        // 이 횟수를 줄이려면 둘에게도 약한 바람을 권해야 하는데, 그러면 미세먼지 노출이 두 배가 된다. 판단을 남겨 두었다(README)
        val twoBad = drive(2, 41.0, delegated = false)
        println(twoBad.line())
        assertTrue(twoBad.ventilations in 16..19, twoBad.line())
        assertTrue(twoBad.pmAverage < twoBad.pmFreshOnly * 0.6, twoBad.line())
    }

    @Test
    fun `고치기 전 규칙이었다면 - 넷이 나쁨에서 짧게 되풀이하면 한도 위에 오래 머문다`() {
        // 같은 차를 엔진 없이 굴려 본다: 한도(2,000)에 닿으면 풍량 +1 외기로 목표(1,200)까지, 그 뒤 내기로 돌아가고 5분은 다시 바꾸지 않는다.
        // v0.7.3 엔진이 4명, 나쁨에서 하던 일이다. 묻고 답하는 시간이 없는 이 어림으로도 한도 위가 2시간의 4할을 넘는다
        // (v0.7.3 엔진을 같은 조건으로 굴린 값은 환기 17번, 한도 위 79분이었다)
        val cfg = WakeConfig()
        val air = CabinAirModel(cfg).apply { occupants = 4; recirculating = true; fanLevel = 3; fanMax = 8; reset(0L) }
        var over = 0.0; var vents = 0; var sinceVent = 99.0; var venting = false
        val step = 1.0 / 60.0
        repeat(120 * 60) {
            if (!venting && air.ppmExact() >= 2000.0 && sinceVent >= 5.0) { venting = true; vents++; air.recirculating = false; air.fanLevel = 4 }
            air.advanceModelMinutes(step)
            if (venting && air.ppmExact() <= 1200.0) { venting = false; sinceVent = 0.0; air.recirculating = true; air.fanLevel = 3 } else if (!venting) sinceVent += step
            if (air.ppmExact() >= 2000.0) over += step
        }
        println("고치기 전 규칙(엔진 없이 어림): 환기 ${vents}번, 한도 위 ${over.toInt()}분")
        assertTrue(vents >= 12, "환기 $vents")
        assertTrue(over >= 45.0, "한도 위 $over")
    }
}

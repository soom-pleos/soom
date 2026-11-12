package ai.kkaeugi.engine

import ai.kkaeugi.engine.Round6Test.Car
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.8.0 기침 자리.
 *  - 감염 위험 식이 Knibbs 외 2012 표를 다시 낸다(같은 차 부피, 같은 공기 교환, 90분)
 *  - 기침 자리와 다른 사람이 같이 타면 한도를 기다리지 않고 바깥 공기로 계속 둘지 묻는다
 *  - 도착 카드에 추정 감염 위험이 실리고, 바깥 공기로 둔 주행은 내내 내기보다 낮다
 *  - 기침 자리가 없거나 혼자면 아무것도 바뀌지 않는다
 */
class CoughTest {

    private val D = SeatProfile.DEFAULT
    private fun profiles(vararg p: Pair<Int, SeatProfile>): List<SeatProfile> = MutableList(Seats.COUNT) { D }.also { l -> p.forEach { (i, v) -> l[i] = v } }
    private fun Car.profiles(vararg p: Pair<Int, SeatProfile>) = send { SeatProfilesChanged(it, this@CoughTest.profiles(*p)) }
    private fun Car.seats(vararg on: Boolean?) = send { SeatOccupancyChanged(it, on.toList()) }

    private fun knibbs(ach: Double, minutes: Int): Double {
        val m = InfectionModel(WakeConfig(cabinVolumeL = 3880))
        repeat(minutes) { m.advance(1.0, ach, true) }
        return m.risk() * 100
    }

    @Test
    fun `Knibbs 2012 의 2005 골프 100km_h 90분 값을 다시 낸다`() {
        // 논문 표: 내기(풍량 최저) 98%, 외기 풍량 최저 24%, 외기 풍량 높음 17% (q 67, p 0.6, V 3.88)
        assertTrue(abs(knibbs(2.7, 90) - 98.0) < 1.5, "내기 ${knibbs(2.7, 90)}")
        assertTrue(abs(knibbs(57.8, 90) - 24.0) < 1.5, "외기 1단 ${knibbs(57.8, 90)}")
        assertTrue(abs(knibbs(85.9, 90) - 17.0) < 1.5, "외기 높음 ${knibbs(85.9, 90)}")
    }

    @Test
    fun `같이 타지 않으면 들이마시는 양이 없다`() {
        val m = InfectionModel(WakeConfig())
        repeat(60) { m.advance(1.0, 2.0, false) }
        assertEquals(0.0, m.risk())
    }

    @Test
    fun `기침 자리와 넷이 타면 일찍 묻고 바깥 공기로 둔다`() {
        val c = Car(WakeConfig()).also { it.autoAnswer = true; it.speechSec = 3 }
        c.seats(true, true, true, false, true)
        c.profiles(Seats.REAR_LEFT to SeatProfile.COUGH, Seats.FRONT_PASSENGER to SeatProfile.PREGNANT)
        c.drive(occupants = 4)
        val at = c.runUntil<WakeEvent.Propose>(600)
        assertNotNull(at, "제안이 나와야 한다")
        assertTrue(at <= 120, "한도(2,000ppm 근처)를 기다리지 않는다: ${at}초")
        val p = c.last<WakeEvent.Propose>()!!
        assertEquals(TriggerKind.STALE_AIR, p.kind)
        assertEquals(WakeEngine.COUGH_KEEP_ASK, p.text)
        c.run(90 * 60)
        val card = c.arrive()
        val inf = assertNotNull(card.infection)
        assertEquals(3, inf.others)
        assertTrue(inf.recircOnlyPercent > 90.0, "내내 내기 ${inf.recircOnlyPercent}")
        assertTrue(inf.actualPercent < inf.recircOnlyPercent / 2, "이번 주행 ${inf.actualPercent} vs 내기 ${inf.recircOnlyPercent}")
    }

    @Test
    fun `미세먼지가 나쁘면 약한 바람으로 계속 들이자고 묻는다`() {
        val c = Car(WakeConfig()).also { it.autoAnswer = true; it.speechSec = 3 }
        c.seats(true, true, true, false, true)
        c.profiles(Seats.REAR_LEFT to SeatProfile.COUGH)
        c.send { OutdoorAir(it, 50.0, "테스트") }
        c.drive(occupants = 4)
        assertNotNull(c.runUntil<WakeEvent.Propose>(600))
        assertEquals(WakeEngine.COUGH_KEEP_LOW_ASK, c.last<WakeEvent.Propose>()!!.text)
    }

    @Test
    fun `기침 자리가 혼자면 카드에 감염 위험이 없다`() {
        val c = Car(WakeConfig()).also { it.autoAnswer = true; it.speechSec = 3 }
        c.seats(true, false, false, false, false)
        c.profiles(Seats.DRIVER to SeatProfile.COUGH)
        c.drive(occupants = 1)
        c.run(30 * 60)
        assertNull(c.arrive().infection)
        assertTrue(c.events.none { it is WakeEvent.Propose && it.text == WakeEngine.COUGH_KEEP_ASK })
    }
}

package ai.kkaeugi.engine

import ai.kkaeugi.engine.Round6Test.Car
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.7.0 "숨 설계"를 엔진에 이어 붙인 동작을 본다.
 *  - 바깥 미세먼지 값이 없으면(없음, null, 60분 넘게 묵음) v0.6.2 와 이벤트가 하나도 다르지 않다. 보통 등급도 같다
 *  - 한도를 정하는 순서: 기본 → 야간 장거리 0.8배 → 나쁨 이상이면 그 여유를 없앰. 기본 한도는 넘기지 않는다
 *  - 등급별 동작: 좋음(바깥 공기로 두는 쪽으로), 나쁨(늦추고 필요한 만큼만), 매우 나쁨(한도 아래면 기다림)
 *  - 세 갈래 비교, 여정 예보, 지나온 곡선, 졸리기 쉬운 시간대, 낮잠 안내
 */
class BreathEngineTest {

    private val real = WakeConfig()
    private val demo = WakeConfig(
        staleAirMinutes = 30, staleAirMinutesNight = 20, ventilateSeconds = 20, drowsySeconds = 30,
        answerTimeoutSec = 8, declineCooldownMinutes = 20, longTripMinutes = 60, restMinutes = 15, demoFast = true,
    )
    /** 숨 모델이 오르지 않는 설정: 내기순환 시간 상한만 본다 */
    private val timeOnly = real.copy(co2PerPersonLph = 0.0)
    private val stop = RestStopInfo("망향", "경부선 서울방향", 12.3, 36.9, 127.1)

    private fun Car.pm(value: Double?, ageMin: Int = 0) = send { OutdoorAir(it, value, "테스트", ageMin) }

    /** 운전자가 3초 걸려 듣고 5초 뒤 답하는 차 */
    private fun car(config: WakeConfig = real, answer: Boolean? = true, trust: TrustState = TrustState()) =
        Car(config, trust).also { it.autoAnswer = answer; it.speechSec = 3 }

    private fun Car.firstProposal(max: Int = 2400): Round6Test.Proposal {
        assertNotNull(runUntil<WakeEvent.Propose>(max), "제안이 나와야 한다")
        return proposals.last()
    }

    /** 실행(Execute)부터 원복(Execute(Revert))까지 걸린 초 */
    private fun Car.ventSeconds(): Int {
        assertNotNull(runUntil<WakeEvent.Execute>(60))
        val started = now
        for (i in 1..400) {
            val got = run(1)
            if (got.any { it is WakeEvent.Execute && it.actions == listOf<Action>(Action.Revert) }) return ((now - started) / 1000).toInt()
        }
        error("원복이 나오지 않았다")
    }

    // ---------- 값이 없으면 v0.6.2 와 같다 ----------

    @Test
    fun `바깥 값이 없거나 null 이거나 묵었거나 보통이면 이벤트가 v0_6_2 와 같다`() {
        fun trip(occupants: Int, config: WakeConfig, feed: (Car) -> Unit): Pair<List<WakeEvent>, ArrivalSummary> {
            val c = car(config)
            feed(c)
            c.drive(occupants = occupants)
            c.run(1800)
            feed(c)
            c.run(1800)
            val card = c.arrive()
            // 도착 카드의 세 갈래 비교는 새로 생긴 칸이라(보통이면 미세먼지 값이 찬다) 빼고 견준다
            val events = c.events.map { if (it is WakeEvent.ArrivalCard) it.copy(card = it.card.copy(threeWay = null)) else it }
            return events to card
        }
        for (config in listOf(real, demo)) for (n in listOf(1, 4)) {
            val (base, baseCard) = trip(n, config) {}
            val variants: List<Pair<String, (Car) -> Unit>> = listOf(
                "null" to { c -> c.pm(null) },
                "묵은 값(61분)" to { c -> c.pm(90.0, ageMin = 61) },
                "보통(25)" to { c -> c.pm(25.0) },
                "말이 안 되는 값" to { c -> c.pm(-5.0); c.pm(9_999.0) },
                "시각만" to { c -> c.send { LocalClock(it, 3 * 60) } },
            )
            for ((name, feed) in variants) {
                val (got, card) = trip(n, config, feed)
                assertEquals(base, got, "$name, ${n}명, 데모=${config.demoFast}")
                assertEquals(baseCard.copy(threeWay = null), card.copy(threeWay = null), name)
                assertEquals(baseCard.threeWay!!.actual.peakPpm, card.threeWay!!.actual.peakPpm)
            }
            assertTrue(base.any { it is WakeEvent.Propose }, "비교할 제안이 있어야 한다")
        }
    }

    @Test
    fun `받은 지 60분이 지나면 값이 없는 것으로 본다`() {
        val c = Car(real)
        c.drive(recirc = false)
        c.pm(41.0, ageMin = 50)
        assertEquals(PmGrade.BAD, c.engine.outdoorGrade)
        assertEquals(50, c.engine.breath(c.now).outdoor!!.ageMin)
        c.run(9 * 60)
        assertEquals(PmGrade.BAD, c.engine.outdoorGrade)
        assertEquals(59, c.engine.breath(c.now).outdoor!!.ageMin)
        c.run(2 * 60)
        assertNull(c.engine.outdoorGrade, "50분 묵은 값을 받고 11분이 지났다")
        assertNull(c.engine.breath(c.now).outdoor)
        // 새 값이 오면 다시 쓴다
        c.pm(80.0)
        assertEquals(PmGrade.VERY_BAD, c.engine.outdoorGrade)
        assertEquals("테스트", c.engine.breath(c.now).outdoor!!.source)
        // 데모 속도여도 묵는 시간은 실제 분으로 센다
        val d = Car(demo)
        d.drive(recirc = false)
        d.pm(41.0)
        d.run(600)
        assertEquals(PmGrade.BAD, d.engine.outdoorGrade)
    }

    // ---------- 한도를 정하는 순서 ----------

    private fun nightLong(config: WakeConfig = real): Car {
        val c = Car(config)
        c.send { LowBeamChanged(it, true) }
        c.drive(recirc = false)
        c.send { TripTimeUpdate(it, config.longTripMinutes + 10) }
        return c
    }

    @Test
    fun `한도 순서 - 기본, 야간 장거리 0점8배, 나쁨 이상이면 그 여유를 없앤다`() {
        val base = real.co2LimitPpm
        val night = nightLong()
        assertEquals((base * WakeEngine.NIGHT_LIMIT_FACTOR).toInt(), night.engine.effectiveLimitPpm)
        for ((value, expected) in listOf(10.0 to 1600, 25.0 to 1600, 36.0 to base, 41.0 to base, 76.0 to base, 150.0 to base)) {
            night.pm(value)
            assertEquals(expected, night.engine.effectiveLimitPpm, "야간 장거리, PM2.5 $value")
        }
        night.pm(null)
        assertEquals(1600, night.engine.effectiveLimitPpm, "값이 사라지면 야간 여유가 돌아온다")
        // 낮에는 등급이 무엇이든 기본 한도다. 기본 한도를 넘기는 경우는 없다
        val day = Car(real)
        day.drive(recirc = false)
        for (value in listOf(null, 5.0, 25.0, 41.0, 120.0)) {
            day.pm(value)
            assertEquals(base, day.engine.effectiveLimitPpm, "낮, PM2.5 $value")
            assertTrue(day.engine.effectiveLimitPpm <= base)
        }
    }

    @Test
    fun `야간 장거리에 나쁨이면 일찍 묻지 않고 접두도 없다`() {
        // 값 없음: 1,600 에 닿을 때 "오래 달려서 조금 일찍 물어요"
        val plain = nightLong()
        plain.driverRecirc(true)
        val early = plain.firstProposal()
        assertTrue(early.text.startsWith(WakeEngine.NIGHT_PREFIX), early.text)
        assertTrue(plain.engine.estimatedPpm < 1700)
        // 나쁨: 2,000 에 닿을 때, 접두 없이
        val bad = nightLong()
        bad.pm(41.0)
        bad.driverRecirc(true)
        val late = bad.firstProposal()
        assertFalse(late.text.startsWith(WakeEngine.NIGHT_PREFIX), late.text)
        assertTrue(bad.engine.estimatedPpm in 2000..2060, "추정 ${bad.engine.estimatedPpm}")
        assertTrue(late.atSec > early.atSec + 300, "나쁨 ${late.atSec}초, 값 없음 ${early.atSec}초")
        assertTrue(bad.peakSeen <= real.co2LimitPpm + 60, "기본 한도를 넘겨 기다리지는 않는다")
    }

    @Test
    fun `야간 시간 상한 20분도 나쁨이면 평소 30분으로 본다`() {
        fun capAt(pm: Double?): Int {
            val c = nightLong(timeOnly)
            c.pm(pm)
            c.driverRecirc(true)
            val p = c.firstProposal(3000)
            assertEquals("TIME_CAP", p.variant)
            return p.atSec / 60
        }
        assertEquals(20, capAt(null)); assertEquals(20, capAt(25.0)); assertEquals(30, capAt(41.0))
    }

    // ---------- 나쁨: 늦추고 필요한 만큼만 ----------

    @Test
    fun `나쁨 - 혼자는 1분만 짧게 바꾸고 되돌린다`() {
        val c = car()
        c.pm(41.0)
        c.drive(occupants = 1)
        val p = c.firstProposal()
        assertEquals("REVERT", p.variant)
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 밖 미세먼지가 나쁨이라 1분만 짧게 바꿀까요?", p.text)
        assertTrue(p.text.endsWith("?"))
        val plan = assertNotNull(c.engine.lastVentPlan)
        assertEquals(60, plan.durationSec); assertEquals(PmGrade.BAD, plan.pmGrade); assertEquals(1200, plan.targetPpm)
        assertEquals(60, c.ventSeconds())
        assertEquals("추정 1,300ppm까지 내려갔어요. 원래대로 돌렸어요.", c.last<WakeEvent.Done>()?.text)
        assertTrue(c.recirc, "내기순환으로 돌아왔다")
        // 값이 없을 때는 2분
        val plainCar = car()
        plainCar.drive(occupants = 1)
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", plainCar.firstProposal().text)
        assertEquals(120, plainCar.ventSeconds())
        assertEquals(120, plainCar.engine.lastVentPlan!!.durationSec)
    }

    @Test
    fun `나쁨 - 2명은 1분 30초를 말하고 목표에 닿으면 그 전에 되돌린다`() {
        val c = car()
        c.pm(60.0)
        c.drive(occupants = 2)
        val p = c.firstProposal()
        assertEquals("2명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 밖 미세먼지가 나쁨이라 1분 30초만 짧게 바꿀까요?", p.text)
        assertEquals(90, c.engine.lastVentPlan!!.durationSec)
        val held = c.ventSeconds()
        assertTrue(held in 60..90, "유지 ${held}초")
        // 다 채우고 끝났으면 목표 위일 수 있고, 일찍 끝났으면 목표(1,200ppm) 아래다
        if (held < 90) assertTrue(c.engine.estimatedPpm <= 1210, "추정 ${c.engine.estimatedPpm}")
    }

    @Test
    fun `나쁨 - 가장 짧은 길이는 지킨다`() {
        // 풍량 최대: 30초면 목표 아래로 내려가지만 1분은 유지한다
        val c = car()
        c.pm(41.0)
        c.drive(occupants = 1, fan = 7)
        c.firstProposal()
        assertEquals(60, c.engine.lastVentPlan!!.durationSec)
        assertEquals(60, c.ventSeconds())
        assertTrue(c.engine.estimatedPpm < 1200)
    }

    @Test
    fun `나쁨 - 둘이면 바깥 공기로 두자고 하지 않고 짧게 되풀이한다`() {
        // v0.7.4 에서 기대값이 바뀐 테스트다. 전에는 4명으로 "2분씩 되풀이"를 봤다. 그런데 4명을 2시간 굴려 보면
        // 환기 17번에 한도 위 약 80분이 나온다(짧게 바꾼 뒤 2분 만에 다시 닿는데 환기 뒤 5분은 다시 묻지 않는다).
        // 그래서 3명 이상은 아래 테스트의 KEEP_LOW 로 옮겼고, 되풀이로 한도를 지킬 수 있는 2명만 여기 남겼다
        val c = car()
        c.pm(41.0)
        c.drive(occupants = 2)
        val p = c.firstProposal()
        assertEquals("REVERT", p.variant)
        assertEquals("2명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 밖 미세먼지가 나쁨이라 1분 30초만 짧게 바꿀까요?", p.text)
        // 두 번 끝낸 뒤에도 "계속 바깥 공기로 둘까요?" 로 넘어가지 않는다
        c.run(1500)
        assertTrue(c.proposals.size >= 3, "제안 ${c.proposals.size}번")
        assertTrue(c.proposals.all { it.variant == "REVERT" }, c.proposals.map { it.variant }.toString())
        assertTrue(c.recirc)
        assertTrue(c.peakSeen < 2100, "둘은 되풀이로 한도 근처를 지킨다. 최고 ${c.peakSeen}")
    }

    @Test
    fun `나쁨 - 셋 이상이면 짧게 여러 번 대신 약한 바람으로 바깥 공기를 계속 들일지 한 번 묻는다`() {
        // 값이 없으면 4명은 처음부터 "바꿔 둘까요?"
        val plainCar = car()
        plainCar.drive(occupants = 4)
        assertEquals("KEEP", plainCar.firstProposal().variant)
        for ((n, word) in listOf(3 to "셋이", 4 to "넷이")) for (pm in listOf(41.0, 90.0)) {
            val c = car()
            c.pm(pm)
            c.drive(occupants = n)
            val p = c.firstProposal()
            assertEquals("KEEP_LOW", p.variant, "${n}명, pm=$pm")
            assertEquals("$word 타서 금방 다시 탁해져요(추정 2,000ppm). 약한 바람으로 바깥 공기를 계속 들일까요?", p.text)
            // 다시 닿기까지가 환기 뒤 쉬는 시간(5분)보다 짧다
            assertTrue(c.engine.lastRecurrenceMinutes!! < WakeEngine.POST_VENT_COOLDOWN_MINUTES, "재도달 ${c.engine.lastRecurrenceMinutes}")
            c.run(10)
            // 동의하면 원복 없이 외기, 풍량은 약한 단(3 → 1)
            val exec = c.events.filterIsInstance<WakeEvent.Execute>().single()
            assertEquals(listOf<Action>(Action.Ventilate(fanDelta = -2)), exec.actions)
            assertEquals(WakeEngine.KEEP_LOW_DONE, c.last<WakeEvent.Done>()!!.text)
            assertFalse(c.recirc); assertEquals(1, c.fan)
            // 그 뒤 한 시간 동안 다시 묻지 않고, 한도 아래에 머문다
            c.run(3600)
            assertEquals(1, c.proposals.size, c.proposals.map { it.variant }.toString())
            assertTrue(c.engine.estimatedPpm < 1500, "약한 바람으로도 한도의 75% 아래: ${c.engine.estimatedPpm}")
            val card = c.arrive()
            assertEquals(1, card.ventilations)
            assertEquals(0, card.minutesOverLimit)
        }
    }

    @Test
    fun `약한 바람 제안은 맡겨 두어도 묻고, 거절하면 다음에는 짧게 바꿀지 묻는다`() {
        val c = car(answer = false, trust = TrustState(delegated = true))
        c.pm(41.0)
        c.drive(occupants = 4)
        assertEquals("KEEP_LOW", c.firstProposal().variant)
        assertEquals(0, c.announces.size, "원복이 없는 변형은 예고가 아니라 질문이다")
        c.run(10)
        assertTrue(c.recirc, "거절하면 그대로 둔다")
        // 거절 뒤 쿨다운(20분)이 지나면 한 번은 "N분만" 으로 묻는다
        c.autoAnswer = null
        c.run(21 * 60)
        val next = (c.proposals + c.announces).last()
        assertTrue(next.text.contains("밖 미세먼지가 나쁨이라"), next.text)
    }

    @Test
    fun `약한 바람의 풍량은 한도의 75퍼센트 아래에 머무는 가장 약한 단이고 최대 단을 모르면 그대로 둔다`() {
        fun cabin(n: Int, fan: Int = 3, max: Int = 8) = CabinAirModel(real).apply { occupants = n; recirculating = true; fanLevel = fan; fanMax = max; reset(0L) }
        assertEquals(1, VentPlanner.lowFanLevel(cabin(3), real, 2000.0))
        assertEquals(1, VentPlanner.lowFanLevel(cabin(4), real, 2000.0))
        // 5명은 1단으로는 1,541ppm 에 머문다. 2단이 필요하다
        assertEquals(2, VentPlanner.lowFanLevel(cabin(5), real, 2000.0))
        // 어린이 자리의 여유(1,800)에서는 4명도 1단(1,319ppm)이 75%(1,350) 아래다
        assertEquals(1, VentPlanner.lowFanLevel(cabin(4), real, 1800.0))
        // 풍량이 1단이던 차는 내리지 않는다. 최대 단을 모르면 계산하지 않는다
        assertEquals(1, VentPlanner.lowFanLevel(cabin(4, fan = 1), real, 2000.0))
        assertNull(VentPlanner.lowFanLevel(cabin(4, fan = 0, max = 0), real, 2000.0))
        val c = car()
        c.pm(41.0)
        c.drive(occupants = 4, fan = 1)
        assertEquals("KEEP_LOW", c.firstProposal().variant)
        c.run(10)
        assertEquals(listOf<Action>(Action.Ventilate(fanDelta = 0)), c.events.filterIsInstance<WakeEvent.Execute>().single().actions)
        assertEquals(1, c.fan)
    }

    @Test
    fun `나쁨 - 시간 상한 제안도 계산한 길이를 말한다`() {
        val c = car(timeOnly)
        c.pm(41.0)
        c.drive(occupants = 1)
        val p = c.firstProposal(2000)
        assertEquals("TIME_CAP", p.variant)
        assertEquals("내기순환으로 30분째예요. 밖 미세먼지가 나쁨이라 1분만 짧게 바꿀까요?", p.text)
        assertEquals(60, c.ventSeconds())
    }

    @Test
    fun `나쁨 - 맡겨 둔 환기의 예고도 계산한 길이를 말한다`() {
        val c = car(trust = TrustState(delegated = true))
        c.pm(41.0)
        c.drive(occupants = 1)
        assertNotNull(c.runUntil<WakeEvent.Announce>(1500))
        assertEquals("밖 미세먼지가 나쁨이라 1분만 짧게 바깥 공기로 바꿀게요. 싫으면 '아니'라고 하세요.", c.announces.single().text)
        assertEquals(60, c.ventSeconds())
        // 값이 없으면 예고 문장은 v0.6.2 그대로
        val plainCar = car(trust = TrustState(delegated = true))
        plainCar.drive(occupants = 1)
        assertNotNull(plainCar.runUntil<WakeEvent.Announce>(1500))
        assertEquals("2분만 바깥 공기로 바꿀게요. 싫으면 '아니'라고 하세요.", plainCar.announces.single().text)
    }

    @Test
    fun `나쁨 - 데모 속도에서는 20초의 절반인 10초만`() {
        val c = car(demo)
        c.pm(41.0)
        c.drive(occupants = 1)
        val p = c.firstProposal(120)
        assertTrue(p.text.endsWith("밖 미세먼지가 나쁨이라 10초만 짧게 바꿀까요?"), p.text)
        assertEquals(10, c.ventSeconds())
    }

    @Test
    fun `나쁨이어도 화면에서 직접 누른 환기와 졸음 개입은 그대로다`() {
        val c = car()
        c.pm(41.0)
        c.drive(occupants = 1)
        c.run(60)
        c.feed(c.engine.requestVentilation(c.now))
        val started = c.now
        assertNotNull(c.runUntil<WakeEvent.Done>(200))
        assertTrue((c.now - started) / 1000 in 120..125, "직접 누른 환기는 2분")
        // 졸음 제안: 문장과 액션이 등급과 무관하다
        fun drowsy(pm: Double?): WakeEvent.Propose {
            val d = Car(real)
            d.pm(pm)
            d.drive(occupants = 1)
            d.send { RestStopCandidate(it, stop) }
            return d.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.filterIsInstance<WakeEvent.Propose>().single()
        }
        assertEquals(drowsy(null), drowsy(90.0))
    }

    // ---------- 매우 나쁨, 좋음 ----------

    @Test
    fun `매우 나쁨 - 추정치가 한도 아래면 시간 상한으로도 묻지 않고 기다린다`() {
        val waiting = car(timeOnly)
        waiting.pm(90.0)
        waiting.drive(occupants = 1)
        waiting.run(55 * 60)
        assertTrue(waiting.proposals.isEmpty(), "55분 동안 묻지 않는다")
        // 그 사이 값이 나쁨으로 내려오면 시간 상한이 다시 산다
        waiting.pm(41.0)
        waiting.run(2)
        assertEquals("TIME_CAP", waiting.proposals.single().variant)
        // 한도에 닿으면 매우 나쁨이어도 묻는다
        val c = car()
        c.pm(90.0)
        c.drive(occupants = 1)
        val p = c.firstProposal()
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 밖 미세먼지가 매우 나쁨이라 1분만 짧게 바꿀까요?", p.text)
        assertTrue(c.peakSeen <= real.co2LimitPpm + 60)
        assertEquals(VentPlanner.REASON_VERY_BAD, c.engine.lastVentPlan!!.reason)
    }

    @Test
    fun `좋음 - 2명이면 바깥 공기로 둘지 묻는다, 값이 없으면 2분만`() {
        val plainCar = car()
        plainCar.drive(occupants = 2)
        assertEquals("REVERT", plainCar.firstProposal().variant)
        val good = car()
        good.pm(9.0)
        good.drive(occupants = 2)
        val p = good.firstProposal()
        assertEquals("KEEP", p.variant)
        assertEquals("2명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?", p.text)
        // 혼자는 좋음이어도 2분만(다시 차기까지 10분이 넘는다)
        val solo = car()
        solo.pm(9.0)
        solo.drive(occupants = 1)
        assertEquals("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?", solo.firstProposal().text)
    }

    // ---------- 세 갈래 비교 ----------

    @Test
    fun `도착 카드 - 내기만은 CO2 가 높고 외기만은 미세먼지가 높다, 이번 주행은 그 사이`() {
        val c = car()
        c.pm(50.0)
        c.drive(occupants = 2)
        c.run(3600)
        val card = c.arrive()
        val w = assertNotNull(card.threeWay)
        assertEquals(PmGrade.BAD, w.pmGrade); assertEquals(50, w.pmOutdoor)
        assertTrue(w.recircOnly.peakPpm > 5000 && w.freshOnly.peakPpm < 1000, "내기만 ${w.recircOnly.peakPpm}, 외기만 ${w.freshOnly.peakPpm}")
        assertTrue(w.actual.peakPpm in (w.freshOnly.peakPpm + 1) until w.recircOnly.peakPpm)
        assertTrue(w.actual.peakPpm <= real.co2LimitPpm + 100, "이번 주행 최고 ${w.actual.peakPpm}")
        assertTrue(w.recircOnly.minutesOverLimit > 40); assertEquals(0, w.freshOnly.minutesOverLimit)
        val r = w.recircOnly.pmAverage!!; val a = w.actual.pmAverage!!; val f = w.freshOnly.pmAverage!!
        assertTrue(r < a && a < f, "미세먼지 평균: 내기만 $r, 이번 $a, 외기만 $f")
        assertEquals(30.0, f, 0.01)                 // 50 × 0.6
        assertTrue(r in 7.5..9.0, "내기만 평균 $r")   // 50 × 0.15 로 수렴
        assertEquals(w.actual.pmExposure!! / 60.0, a, 0.2)
        // 요약의 최고값과 세 갈래의 "이번 주행" 최고값은 같은 값이다
        assertEquals(card.peakPpm, w.actual.peakPpm); assertEquals(card.minutesOverLimit, w.actual.minutesOverLimit)
    }

    @Test
    fun `도착 카드 - 바깥 값이 없으면 CO2 만 비교하고 미세먼지 칸은 빈다`() {
        val c = car()
        c.drive(occupants = 4)
        c.run(1200)
        val card = c.arrive()
        val w = assertNotNull(card.threeWay)
        assertNull(w.pmGrade); assertNull(w.pmOutdoor)
        for (s in listOf(w.recircOnly, w.freshOnly, w.actual)) { assertNull(s.pmExposure); assertNull(s.pmAverage) }
        assertEquals(card.peakPpm, w.actual.peakPpm); assertEquals(card.minutesOverLimit, w.actual.minutesOverLimit)
        assertTrue(w.recircOnly.peakPpm > w.actual.peakPpm && w.actual.peakPpm > w.freshOnly.peakPpm)
        // 다음 주행은 그림자도 처음부터
        c.engine.reset()
        c.run(600)
        c.drive(occupants = 1, recirc = false)
        c.run(60)
        val next = c.arrive().threeWay!!
        assertTrue(next.recircOnly.peakPpm < 1100, "1분 달린 내기만 ${next.recircOnly.peakPpm}")
    }

    @Test
    fun `운전자가 직접 외기로 바꾼 구간도 이번 주행에 그대로 들어간다`() {
        val c = car(answer = null)
        c.pm(50.0)
        c.drive(occupants = 1, recirc = false)
        c.run(1200)
        c.driverRecirc(true)
        c.run(300)
        val w = c.arrive().threeWay!!
        // 20분 외기 + 5분 내기: 평균은 외기만(30)보다 낮고 내기만보다 높다
        assertTrue(w.actual.pmAverage!! < w.freshOnly.pmAverage!! && w.actual.pmAverage!! > w.recircOnly.pmAverage!!)
        assertTrue(w.actual.pmAverage!! > 20.0)
    }

    // ---------- 숨 설계 읽기: 설계, 예보, 곡선 ----------

    @Test
    fun `breath 는 읽기만 한다 - 불러도 엔진의 이벤트가 달라지지 않는다`() {
        val a = car(); val b = car()
        for (c in listOf(a, b)) { c.pm(41.0); c.drive(occupants = 2) }
        repeat(1800) {
            a.run(1); b.run(1)
            b.engine.breath(b.now); b.engine.ventPlan(b.now); b.engine.alertness(b.now)
        }
        assertEquals(a.events, b.events)
        assertEquals(a.engine.estimatedPpm, b.engine.estimatedPpm)
    }

    @Test
    fun `설계와 예보 - 혼자 내기순환이면 약 19분마다 2분, 한 시간에 약 3번, 첫 번째는 약 20분 뒤`() {
        val c = Car(real)
        c.drive(occupants = 1)
        c.run(5)
        val d = c.engine.breath(c.now)
        assertNull(d.outdoor); assertNull(d.plan.pmGrade); assertNull(d.nap)
        assertEquals(120, d.plan.durationSec); assertTrue(d.plan.intervalMin!! in 18.0..20.0)
        assertEquals(60, d.forecast.horizonMin); assertFalse(d.forecast.toArrival)
        assertEquals(3, d.forecast.ventCount); assertEquals(20, d.forecast.firstVentInMin)
        // 목적지까지 22분: 한도에 닿을 때는 도착 2분 전이라 바꾸지 않는다
        c.send { DestinationEta(it, 22) }
        val toArrival = c.engine.breath(c.now).forecast
        assertTrue(toArrival.toArrival); assertEquals(22, toArrival.horizonMin); assertEquals(0, toArrival.ventCount)
        // 10분 달리면 첫 환기는 약 10분 뒤
        c.send { DestinationEta(it, null) }
        c.run(600)
        assertEquals(10, c.engine.breath(c.now).forecast.firstVentInMin)
        // 나쁨이면 1분씩, 간격은 짧아진다
        c.pm(41.0)
        val bad = c.engine.breath(c.now)
        assertEquals(60, bad.plan.durationSec); assertEquals(PmGrade.BAD, bad.plan.pmGrade)
        assertTrue(bad.plan.intervalMin!! < d.plan.intervalMin!!)
        assertEquals(41.0, bad.outdoor!!.pm25); assertEquals(PmGrade.BAD, bad.outdoor!!.grade)
        assertNotNull(bad.outdoor!!.cabinPm)
    }

    @Test
    fun `예보 - 외기로 달리면 0번, 엔진이 바꿔 둔 중이면 되돌린 뒤를 내다본다`() {
        val fresh = Car(real)
        fresh.drive(occupants = 4, recirc = false)
        fresh.run(10)
        assertEquals(0, fresh.engine.breath(fresh.now).forecast.ventCount)
        val c = car()
        c.drive(occupants = 1)
        c.firstProposal()
        assertNotNull(c.runUntil<WakeEvent.Execute>(30))
        c.run(30)
        assertFalse(c.recirc, "환기 유지 중")
        val f = c.engine.breath(c.now).forecast
        assertTrue(f.points.first().venting)
        assertTrue(f.ventCount >= 2, "되돌린 뒤 다시 차는 것까지 센다: ${f.ventCount}")
        assertTrue(f.firstVentInMin!! in 15..19, "다음 환기 ${f.firstVentInMin}분 뒤")
    }

    @Test
    fun `지나온 곡선 - 30초마다 한 점, 그림자 내기만은 실제보다 낮지 않다`() {
        val c = car()
        c.drive(occupants = 2)
        c.run(600)
        val d = c.engine.breath(c.now)
        assertEquals(21, d.trace.size, "10분이면 0.5분 간격으로 20점에 지금 값 한 점")
        assertEquals(c.engine.estimatedPpm, d.trace.last().ppm, "곡선은 지금 값에서 끝난다")
        assertTrue(d.trace.zipWithNext().all { (x, y) -> y.minute > x.minute })
        assertTrue(d.trace.all { it.recircOnlyPpm >= it.ppm - 1 })
        assertTrue(d.shadowRecircPpm > d.trace.last().ppm + 500, "환기를 한 번 했으니 그림자는 훨씬 위에 있다")
        assertTrue(d.shadowFreshPpm < 1000)
        c.run(3000)
        assertEquals(61, c.engine.breath(c.now).trace.size)
        // 데모 속도: 1초가 모델 1분이라 1초에 한 점씩 쌓인다(60초면 꽉 찬다)
        val fast = Car(demo)
        fast.drive(occupants = 1, recirc = false)
        fast.run(40)
        assertEquals(40, fast.engine.breath(fast.now).trace.size)
        fast.run(40)
        assertEquals(60, fast.engine.breath(fast.now).trace.size)
        // 도착한 뒤에는 지금 값을 붙이지 않는다
        // 새 주행이면 비운다
        fast.arrive()
        fast.send { GearChanged(it, Gear.DRIVE) }
        assertTrue(fast.engine.breath(fast.now).trace.size <= 1)
    }

    // ---------- 졸리기 쉬운 시간대, 낮잠 안내 ----------

    @Test
    fun `졸리기 쉬운 시간대 - 시각과 연속 주행으로만 낸다, 쉬고 나오면 주행 성분은 처음부터`() {
        val c = Car(real)
        c.drive(recirc = false)
        assertFalse(c.engine.alertness(c.now).clockKnown)
        assertFalse(c.engine.alertness(c.now).sleepyHours)
        c.send { LocalClock(it, 3 * 60 + 30) }
        val dawn = c.engine.alertness(c.now)
        assertTrue(dawn.clockKnown && dawn.sleepyHours); assertEquals(0.0, dawn.drive, 0.01)
        // 한 시간 달리면 주행 성분 0.5, 시각은 04시 30분으로 흘러 있다
        c.run(3600)
        val later = c.engine.alertness(c.now)
        assertEquals(0.5, later.drive, 0.01)
        assertEquals(AlertnessWeight.circadian(4 * 60 + 30), later.circadian, 1e-9)
        // 낮 11시에는 오래 달려도 표시하지 않는다
        c.send { LocalClock(it, 11 * 60) }
        c.send { TripTimeUpdate(it, 300) }
        val noon = c.engine.alertness(c.now)
        assertEquals(1.0, noon.drive, 1e-9); assertFalse(noon.sleepyHours)
        assertEquals(noon, c.engine.breath(c.now).alertness)
        // 한도는 이 값으로 바뀌지 않는다(야간 장거리 규칙만)
        assertEquals(real.co2LimitPpm, c.engine.effectiveLimitPpm)
    }

    @Test
    fun `졸리기 쉬운 시간대면 졸음 제안에서 쉼터 경유를 먼저 실행한다, 문장은 그대로`() {
        fun drowsy(minuteOfDay: Int?): WakeEvent.Propose {
            val c = Car(real)
            if (minuteOfDay != null) c.send { LocalClock(it, minuteOfDay) }
            c.drive(occupants = 1)
            c.send { RestStopCandidate(it, stop) }
            return c.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }.filterIsInstance<WakeEvent.Propose>().single()
        }
        val plain = drowsy(null)
        assertEquals(listOf(Action.Ventilate(), Action.AddRestStop(stop), Action.CoolDown()), plain.actions)
        assertEquals(plain, drowsy(11 * 60), "낮에는 v0.6.2 와 같다")
        val dawn = drowsy(3 * 60)
        assertEquals(listOf(Action.AddRestStop(stop), Action.Ventilate(), Action.CoolDown()), dawn.actions)
        assertEquals(plain.text, dawn.text)
        assertFalse(dawn.text.contains("감지"))
    }

    @Test
    fun `쉼 모드 - 낮잠 안내와 15분 타이머, RestStarted 는 그대로`() {
        val c = Car(real)
        c.drive(recirc = false)
        c.send { RestStopCandidate(it, stop) }
        c.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        c.yes()
        assertNotNull(c.runUntil<WakeEvent.Done>(200))
        assertNull(c.engine.breath(c.now).nap, "달리는 중에는 없다")
        c.send { RestStopProximity(it, within = true) }
        val entered = c.send { GearChanged(it, Gear.PARK) }
        assertEquals(listOf<WakeEvent>(WakeEvent.RestStarted("망향 졸음쉼터", 15)), entered)
        val nap = assertNotNull(c.engine.breath(c.now).nap)
        assertEquals("커피 한 잔 뒤 15분 안쪽으로 눈을 붙이면 도움이 된다는 연구가 있어요.", nap.text)
        assertEquals(15 * 60, nap.remainingSec); assertFalse(nap.done)
        c.run(14 * 60)
        assertEquals(60, c.engine.breath(c.now).nap!!.remainingSec)
        c.run(60)
        val done = c.engine.breath(c.now).nap!!
        assertTrue(done.done); assertEquals(0, done.remainingSec)
        assertEquals("15분 지났어요. 몸을 풀고 출발해요.", done.doneText)
        // 의학적으로 단정하지 않는다
        for (t in listOf(nap.text, done.doneText)) for (bad in listOf("예방", "방지", "치료", "감지", "최적")) assertFalse(t.contains(bad), "$t / $bad")
        // 쉬고 나오면 연속 주행은 처음부터
        c.send { GearChanged(it, Gear.DRIVE) }
        assertNull(c.engine.breath(c.now).nap)
        assertEquals(0.0, c.engine.alertness(c.now).drive, 0.01)
    }

    @Test
    fun `쉼 목표가 더 길어도 낮잠 타이머는 15분이다, 데모 속도면 15초`() {
        assertEquals(15, WakeEngine.NAP_MINUTES)
        val long = Car(real.copy(restMinutes = 60))
        long.drive(recirc = false)
        long.send { RestStopCandidate(it, stop) }
        long.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        long.yes()
        assertNotNull(long.runUntil<WakeEvent.Done>(200))
        long.send { RestStopProximity(it, within = true) }
        long.send { GearChanged(it, Gear.PARK) }
        assertEquals(15 * 60, long.engine.breath(long.now).nap!!.remainingSec)
        val fast = Car(demo)
        fast.drive(recirc = false)
        fast.send { RestStopCandidate(it, stop) }
        fast.send { TelltaleChanged(it, Telltale.DRIVER_ATTENTION, true) }
        fast.yes()
        assertNotNull(fast.runUntil<WakeEvent.Done>(200))
        fast.send { RestStopProximity(it, within = true) }
        fast.send { GearChanged(it, Gear.PARK) }
        assertEquals(15, fast.engine.breath(fast.now).nap!!.remainingSec)
    }

    @Test
    fun `엔진 문장과 설계 문구에 금지 표현이 없다`() {
        val texts = listOf(
            VentPlanner.REASON_DEFAULT, VentPlanner.REASON_GOOD, VentPlanner.REASON_BAD, VentPlanner.REASON_VERY_BAD,
            VentPlanner.REASON_KEEP, VentPlanner.REASON_NEVER, WakeEngine.napTip(), WakeEngine.napDoneText(),
        )
        for (t in texts) {
            for (bad in listOf("측정", "감지", "예방", "방지", "최적", "최초", "유일")) assertFalse(t.contains(bad), "$t 에 '$bad'")
            for (dash in listOf("—", "–", "ㅡ")) assertFalse(t.contains(dash), t)
        }
    }
}

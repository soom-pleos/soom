package ai.kkaeugi.app.signals

import ai.kkaeugi.app.vehicle.GeoPoint
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.OutdoorAir
import ai.kkaeugi.engine.PmGrade
import ai.kkaeugi.engine.VentPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 바깥 미세먼지 소스(v0.7.0). 네트워크 없이 본다: 주소와 좌표 반올림, 응답 읽기, 30분 캐시와 5분 재시도,
 * 설정 꺼짐과 수동 선택, 실패하면 조용히 값 없음, 그리고 시연 시나리오가 미세먼지와 시각을 넣는 순서.
 */
class OutdoorAirSourceTest {

    private val min = 60_000L
    private val sample = """{"latitude":36.0,"longitude":128.4,"generationtime_ms":0.05,"utc_offset_seconds":0,"timezone":"GMT",
        "current_units":{"time":"iso8601","interval":"seconds","pm2_5":"μg/m³","pm10":"μg/m³"},
        "current":{"time":"2026-11-11T03:00","interval":3600,"pm2_5":41.2,"pm10":58.0}}"""

    // ---------- 주소, 응답 ----------

    @Test fun `좌표는 소수 첫째 자리까지만 보낸다`() {
        assertEquals(37.4, OpenMeteoAir.roundCoord(37.4123), 1e-9)
        assertEquals(127.1, OpenMeteoAir.roundCoord(127.0871), 1e-9)
        assertEquals(-33.9, OpenMeteoAir.roundCoord(-33.8688), 1e-9)
        val url = OpenMeteoAir.url(37.4123456, 127.0871234)
        assertEquals("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=37.4&longitude=127.1&current=pm2_5,pm10", url)
        assertFalse("정확한 좌표가 주소에 남지 않는다", url.contains("4123") || url.contains("0871"))
        assertFalse("키나 기기 정보를 붙이지 않는다", url.contains("key", ignoreCase = true) || url.contains("id="))
        assertEquals(5_000, OpenMeteoAir.TIMEOUT_MS)
    }

    @Test fun `응답에서 pm2_5 와 pm10 을 꺼낸다`() {
        val v = OpenMeteoAir.parse(sample)!!
        assertEquals(41.2, v.pm25, 1e-9); assertEquals(58.0, v.pm10!!, 1e-9)
        assertEquals(PmGrade.BAD, VentPlanner.grade(v.pm25))
        // 정수로 와도, pm10 이 없어도 된다
        assertEquals(12.0, OpenMeteoAir.parse("""{"current":{"pm2_5":12}}""")!!.pm25, 1e-9)
        assertNull(OpenMeteoAir.parse("""{"current":{"pm2_5":12}}""")!!.pm10)
    }

    @Test fun `읽을 수 없는 응답은 값 없음이다`() {
        val broken = listOf(
            null, "", "   ", "not json", "[]", "{}", """{"current":null}""", """{"current":{}}""", """{"current":{"pm2_5":null,"pm10":3}}""",
            """{"current":{"pm2_5":"많음"}}""", """{"current":{"pm2_5":-1}}""", """{"error":true,"reason":"Latitude must be in range of -90 to 90°."}""",
            """{"hourly":{"pm2_5":[1,2,3]}}""", "<html>502</html>",
        )
        for (b in broken) assertNull("$b", OpenMeteoAir.parse(b))
    }

    @Test fun `출처 표기와 한계 문장`() {
        assertEquals("Air quality data: CAMS ENSEMBLE via Open-Meteo", OpenMeteoAir.ATTRIBUTION)
        assertTrue(OpenMeteoAir.GRID_NOTE.contains("도로변 실측이 아니라"))
        assertTrue(OpenMeteoAir.GRID_NOTE.contains("45km"))
        for (t in listOf(OpenMeteoAir.GRID_NOTE, OpenMeteoAir.SOURCE)) assertFalse("측정이라고 쓰지 않는다: $t", t.contains("측정"))
    }

    // ---------- 캐시 ----------

    @Test fun `받은 값은 30분 쓰고 실패하면 5분 뒤 다시 해 본다`() {
        val c = OutdoorAirCache()
        assertTrue(c.shouldFetch(0))
        c.onTry(0); c.onSuccess(OutdoorReading(41.0, null, 0, "x"))
        assertFalse(c.shouldFetch(29 * min)); assertTrue(c.shouldFetch(30 * min))
        c.onTry(30 * min); c.onFailure()
        assertFalse(c.shouldFetch(34 * min)); assertTrue(c.shouldFetch(35 * min))
        assertNotNull("실패해도 앞서 받은 값은 들고 있다", c.reading)
        assertEquals(34, c.reading!!.ageMin(34 * min))
        c.onTry(35 * min); c.onSuccess(OutdoorReading(12.0, null, 35 * min, "x"))
        assertFalse(c.shouldFetch(64 * min)); assertTrue(c.shouldFetch(65 * min))
        c.clear()
        assertNull(c.reading); assertTrue(c.shouldFetch(65 * min))
        // 모의 위치로 받은 값은 5분 뒤에 다시 받아 본다(그 사이 실제 위치가 잡혔을 수 있다)
        c.onTry(0); c.onSuccess(OutdoorReading(41.0, null, 0, "x"), provisional = true)
        assertFalse(c.shouldFetch(4 * min)); assertTrue(c.shouldFetch(5 * min))
    }

    // ---------- 소스 ----------

    private class Rig(body: String? = null, private val fail: Throwable? = null, var place: GeoPoint? = GeoPoint(37.4123, 127.0871)) {
        var now = 1_000_000L
        val sent = mutableListOf<OutdoorAir>()
        val urls = mutableListOf<String>()
        var body: String? = body
        val source = OutdoorAirSource(
            CoroutineScope(Dispatchers.Unconfined), emit = { sent += it }, now = { now }, location = { place },
            fetch = { url -> urls += url; fail?.let { throw it }; this.body ?: error("no body") },
        )
    }

    @Test fun `자동 - 처음에는 값 없음을 넘기고 받아 오면 값을 넘긴다, 30분 동안 다시 받지 않는다`() {
        val r = Rig(sample)
        r.source.refresh()
        assertEquals(listOf(null, 41.2), r.sent.map { it.pm25UgM3 })
        assertEquals(1, r.urls.size)
        assertTrue(r.urls.single().contains("latitude=37.4&longitude=127.1"))
        assertEquals(OpenMeteoAir.SOURCE, r.sent.last().source); assertEquals(0, r.sent.last().ageMin)
        // 12분 뒤: 받아 둔 값을 나이와 함께 넘긴다. 새로 받지 않는다
        r.now += 12 * 60_000L
        r.source.refresh()
        assertEquals(1, r.urls.size)
        assertEquals(41.2, r.sent.last().pm25UgM3!!, 1e-9); assertEquals(12, r.sent.last().ageMin)
        assertEquals("PM2.5 41µg/m³(나쁨) · Open-Meteo(CAMS 격자 모델값) · 12분 전", r.source.statusLine())
        // 30분이 지나면 새로 받는다
        r.now += 18 * 60_000L
        r.body = sample.replace("41.2", "9.0")
        r.source.refresh()
        assertEquals(2, r.urls.size)
        assertEquals(9.0, r.sent.last().pm25UgM3!!, 1e-9); assertEquals(0, r.sent.last().ageMin)
    }

    @Test fun `실패하면 조용히 값 없음이고 5분 뒤 다시 해 본다`() {
        val r = Rig(fail = java.net.UnknownHostException("air-quality-api.open-meteo.com"))
        r.source.refresh()
        assertTrue(r.sent.all { it.pm25UgM3 == null })
        assertTrue(r.source.lastError!!.contains("UnknownHostException"))
        assertTrue(r.source.statusLine().startsWith("값 없음"))
        r.now += 4 * 60_000L; r.source.refresh()
        assertEquals(1, r.urls.size)
        r.now += 60_000L; r.source.refresh()
        assertEquals(2, r.urls.size)
        assertTrue(r.sent.all { it.pm25UgM3 == null })
        // 응답은 왔는데 값이 없을 때도 같다
        val empty = Rig("""{"current":{"pm2_5":null}}""")
        empty.source.refresh()
        assertTrue(empty.sent.all { it.pm25UgM3 == null })
        assertNotNull(empty.source.lastError)
    }

    @Test fun `받은 뒤 실패가 이어지면 앞의 값을 나이와 함께 넘긴다 - 60분에서 버리는 것은 엔진이다`() {
        val r = Rig(sample)
        r.source.refresh()
        r.body = null   // 이제부터 실패
        r.now += 45 * 60_000L
        r.source.refresh()
        assertEquals(41.2, r.sent.last().pm25UgM3!!, 1e-9); assertEquals(45, r.sent.last().ageMin)
        // 엔진은 45분 묵은 값은 쓰고 61분 묵은 값은 버린다
        val engine = ai.kkaeugi.engine.WakeEngine(ai.kkaeugi.engine.WakeConfig())
        engine.accept(ai.kkaeugi.engine.GearChanged(r.now, Gear.DRIVE))
        engine.accept(r.sent.last())
        assertEquals(PmGrade.BAD, engine.outdoorGrade)
        engine.accept(r.sent.last().copy(ageMin = 61))
        assertNull(engine.outdoorGrade)
    }

    @Test fun `꺼 두면 값 없음만 넘기고 받지 않는다`() {
        val r = Rig(sample)
        r.source.enabled = false
        r.source.refresh()
        assertEquals(listOf<Double?>(null), r.sent.map { it.pm25UgM3 })
        assertTrue(r.urls.isEmpty())
        assertTrue(r.source.statusLine().startsWith("꺼짐"))
    }

    @Test fun `수동 선택이면 그 등급의 대표값을 넘기고 받지 않는다`() {
        for (c in PmChoice.entries.filter { it != PmChoice.AUTO }) {
            val r = Rig(sample)
            r.source.choice = c
            r.source.refresh()
            assertTrue(r.urls.isEmpty())
            val sent = r.sent.single()
            assertEquals(c.pm25!!, sent.pm25UgM3!!, 1e-9); assertEquals(OutdoorAirSource.MANUAL_SOURCE, sent.source); assertEquals(0, sent.ageMin)
            assertEquals("대표값은 그 등급에 든다", c.label, VentPlanner.grade(c.pm25!!).label)
            assertTrue(r.source.statusLine().contains("수동 선택"))
        }
        assertEquals(listOf("자동", "좋음", "보통", "나쁨", "매우 나쁨"), PmChoice.entries.map { it.label })
        assertEquals(PmChoice.AUTO, PmChoice.of(null)); assertEquals(PmChoice.AUTO, PmChoice.of("없는 값")); assertEquals(PmChoice.BAD, PmChoice.of("BAD"))
    }

    @Test fun `위치가 없거나 한국 밖이면 모의 위치로 받고 출처에 적는다`() {
        for (place in listOf(null, GeoPoint(37.42, -122.08))) {
            val r = Rig(sample, place = place)
            r.source.refresh()
            assertTrue(r.urls.single().contains("latitude=36.0&longitude=128.5") || r.urls.single().contains("latitude=35.9&longitude=128.4") || r.urls.single().contains("latitude=36.0&longitude=128.4") || r.urls.single().contains("latitude=35.9&longitude=128.5"))
            assertTrue(r.sent.last().source.contains("모의 위치"))
            // 5분 뒤 실제 위치가 잡혀 있으면 그 자리로 다시 받는다
            r.place = GeoPoint(37.4123, 127.0871)
            r.now += 5 * 60_000L
            r.source.refresh()
            assertEquals(2, r.urls.size)
            assertTrue(r.urls.last().contains("latitude=37.4&longitude=127.1"))
            assertFalse(r.sent.last().source.contains("모의 위치"))
        }
    }

    @Test fun `자가 진단용으로 지금 한 번 받아 본다`() {
        val ok = Rig(sample)
        val reading = runBlocking { ok.source.fetchNow() }!!
        assertEquals(41.2, reading.pm25, 1e-9); assertEquals(PmGrade.BAD, reading.grade)
        assertNull(ok.source.lastError)
        assertEquals("PM2.5 41µg/m³(나쁨) · Open-Meteo(CAMS 격자 모델값) · 0분 전", OutdoorAirSource.describe(reading, ok.now))
        val bad = Rig(fail = java.net.SocketTimeoutException("timeout"))
        assertNull(runBlocking { bad.source.fetchNow() })
        assertTrue(bad.source.lastError!!.contains("SocketTimeoutException"))
    }

    // ---------- 시연 시나리오 ----------

    @Test fun `시연 시나리오는 미세먼지 나쁨으로 시작해 좋음으로 바꾸고, 밤길 장면에서 새벽 3시를 넣는다`() {
        val pm = mutableListOf<Double?>()
        val clock = mutableListOf<Int?>()
        val hands = object : DemoScenario.Hands {
            override fun gear(g: Gear) {}
            override fun recirculation(on: Boolean) {}
            override fun occupants(n: Int) { if (n == 4) assertEquals("가족이 타기 전에 좋음으로 바뀌어 있다", listOf<Double?>(41.0, 9.0), pm) }
            override fun fanSpeed(level: Int, max: Int) {}
            override fun windowOpen(percent: Int) {}
            override fun lowBeam(on: Boolean) {}
            override fun addTripMinutes(m: Int) {}
            override fun driverAttention(on: Boolean) { if (on) assertEquals("졸음 경고 전에 새벽 3시가 들어가 있다", 180, clock.last()) }
            override fun restWithin() {}
            override fun answerYes() {}
            override fun frontPassenger(present: Boolean) {}
            override fun eta(minutes: Int?) {}
            override fun autoClimate(on: Boolean) {}
            override fun outdoorPm(pm25: Double?) { pm += pm25 }
            override fun localClock(minuteOfDay: Int?) { clock += minuteOfDay }
        }
        val steps = DemoScenario.steps(ventilateSec = 20, drowsySec = 30, restSec = 15)
        for (s in steps) s.act(hands)
        assertEquals(listOf<Double?>(41.0, 9.0), pm)
        assertEquals(listOf<Int?>(11 * 60, 3 * 60), clock)
        assertEquals(PmGrade.BAD, VentPlanner.grade(DemoScenario.PM_BAD)); assertEquals(PmGrade.GOOD, VentPlanner.grade(DemoScenario.PM_GOOD))
        assertTrue(steps.first().caption.contains("나쁨"))
        assertTrue(steps.any { it.caption.contains("짧게(10초)") })
        // 넣지 않는 쪽(기본 구현)에서는 아무 일도 없다
        val plain = object : DemoScenario.Hands {
            override fun gear(g: Gear) {}; override fun recirculation(on: Boolean) {}; override fun occupants(n: Int) {}
            override fun fanSpeed(level: Int, max: Int) {}; override fun windowOpen(percent: Int) {}; override fun lowBeam(on: Boolean) {}
            override fun addTripMinutes(m: Int) {}; override fun driverAttention(on: Boolean) {}; override fun restWithin() {}
            override fun answerYes() {}; override fun frontPassenger(present: Boolean) {}; override fun eta(minutes: Int?) {}; override fun autoClimate(on: Boolean) {}
        }
        for (s in steps) s.act(plain)
    }
}

package ai.kkaeugi.app.gleo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gleo Interface 연동의 순수 로직: 파라미터 읽기, 함수 셋의 답, 응답 JSON, 스키마 파일이 문서 제약을 지키는지 */
class GleoLogicTest {

    private val driving = GleoState(
        driving = true, recirculating = true, ppm = 1749, level = "탁함", minutesToLimit = 2,
        pmGrade = "나쁨", pm25 = 41, sitMinutes = 72, breakMinutes = 90,
        restStopName = "석적 졸음쉼터", restStopKm = 12,
    )

    private fun call(fn: String, s: GleoState, vararg p: Pair<String, String>) = GleoLogic.handle(fn, mapOf(*p), s)

    // ---------- 파라미터 ----------

    @Test fun `ACTION_PARAM 의 JSON 을 키와 값으로 편다`() {
        assertEquals(mapOf("topic" to "sitting"), GleoParams.parse("""{"topic":"sitting"}"""))
        // 문서의 예시처럼 따옴표로 한 번 더 감싼 꼴
        assertEquals(mapOf("topic" to "air"), GleoParams.parse("\"{\\\"topic\\\":\\\"air\\\"}\""))
        assertEquals(mapOf("n" to "3", "on" to "true"), GleoParams.parse("""{"n":3,"on":true}"""))
        assertEquals(emptyMap<String, String>(), GleoParams.parse(null))
        assertEquals(emptyMap<String, String>(), GleoParams.parse(""))
        assertEquals(emptyMap<String, String>(), GleoParams.parse("환기해 줘"))
        assertEquals(emptyMap<String, String>(), GleoParams.parse("{깨진 JSON"))
    }

    // ---------- (a) 환기해 줘 ----------

    @Test fun `환기는 주행 중 내기순환일 때만 엔진에 부탁한다`() {
        val o = call(Gleo.VENTILATE, driving)
        assertEquals(GleoCommand.VENTILATE, o.command)
        assertEquals(GleoStatus.SUCCESS, o.response.status)
        assertEquals("Switching to outside air for 2 minutes, then restoring the previous setting.", o.response.message)
        assertEquals("2분만 바깥 공기로 바꿀게요. 끝나면 원래대로 돌려요.", o.response.korean)
        // 데모 속도의 20초
        assertEquals("20초만 바깥 공기로 바꿀게요. 끝나면 원래대로 돌려요.", call(Gleo.VENTILATE, driving.copy(ventSeconds = 20)).response.korean)
    }

    @Test fun `차의 자동 공조와 정차와 쉼과 진행 중인 안내는 그대로 둔다`() {
        fun refuse(s: GleoState): GleoResponse = call(Gleo.VENTILATE, s).also { assertNull(it.command) }.response
        assertEquals(GleoStatus.FAIL, refuse(driving.copy(carAuto = true)).status)
        assertTrue(refuse(driving.copy(carAuto = true)).korean.contains("자동으로 조절"))
        assertEquals(GleoStatus.FAIL, refuse(driving.copy(driving = false)).status)
        assertEquals(GleoStatus.FAIL, refuse(driving.copy(resting = true)).status)
        assertEquals(GleoStatus.FAIL, refuse(driving.copy(busy = true)).status)
        // 이미 바깥 공기면 할 일이 없다(실패가 아니다)
        val fresh = refuse(driving.copy(recirculating = false))
        assertEquals(GleoStatus.SUCCESS, fresh.status)
        assertEquals("지금도 바깥 공기가 들어오고 있어요.", fresh.korean)
        // 자동 공조가 먼저다
        assertTrue(refuse(driving.copy(carAuto = true, recirculating = false)).korean.contains("자동으로"))
    }

    // ---------- (b) 공기 어때 ----------

    @Test fun `공기는 추정값과 상태 한 단어와 바깥 등급과 다음 환기를 한 문장씩 말한다`() {
        val r = call(Gleo.CHECK, driving).response
        assertEquals(GleoStatus.SUCCESS, r.status)
        assertEquals("차 안 이산화탄소는 추정 1,700ppm, 탁함이에요. 바깥 미세먼지는 나쁨(41)이에요. 약 2분 뒤에 환기를 물을게요.", r.korean)
        assertEquals(
            "Estimated cabin CO2 is about 1,700 ppm (stale). Outside fine dust is bad (41). The next ventilation prompt is in about 2 minutes. These are estimates, not sensor readings.",
            r.message,
        )
        // topic 이 air 여도, 모르는 값이어도 공기다
        assertEquals(r, call(Gleo.CHECK, driving, "topic" to "air").response)
        assertEquals(r, call(Gleo.CHECK, driving, "topic" to "weather").response)
        assertTrue(r.korean.contains("추정"))
    }

    @Test fun `공기 답의 갈래`() {
        fun ko(s: GleoState) = call(Gleo.CHECK, s).response.korean
        assertEquals("아직 추정값이 없어요. 출발하면 추정을 시작해요.", ko(GleoState()))
        assertEquals("차 안 이산화탄소는 추정 900ppm, 쾌적이에요. 지금은 바깥 공기가 들어오고 있어요.", ko(GleoState(driving = true, ppm = 871, level = "쾌적", ventilating = true)))
        assertEquals("차 안 이산화탄소는 추정 1,000ppm, 보통이에요. 지금 상태로는 한도에 닿지 않아요.", ko(GleoState(driving = true, recirculating = true, ppm = 1001, level = "보통")))
        assertEquals("차 안 이산화탄소는 추정 2,000ppm, 한도예요. 한도에 닿았어요.", ko(GleoState(driving = true, recirculating = true, ppm = 2003, level = "한도", minutesToLimit = 0)))
        // 숨을 꺼 두었으면 묻는다는 말을 하지 않는다. 데모 속도면 초
        assertEquals("차 안 이산화탄소는 추정 1,700ppm, 탁함이에요. 바깥 미세먼지는 나쁨(41)이에요.", ko(driving.copy(enabled = false)))
        assertTrue(ko(driving.copy(demo = true)).endsWith("약 2초 뒤에 환기를 물을게요."))
        assertTrue(call(Gleo.CHECK, driving.copy(minutesToLimit = 1)).response.message.contains("in about 1 minute."))
    }

    // ---------- (d) 얼마나 앉아 있었어 ----------

    @Test fun `앉은 시간과 다음 걷기 쉼까지 남은 시간`() {
        val r = call(Gleo.CHECK, driving, "topic" to "sitting").response
        assertEquals("앉은 지 1시간 12분이에요(좌석과 기어 신호로 센 값). 약 18분 뒤에 걷기 쉼을 물을게요.", r.korean)
        assertEquals("Seated for about 72 minutes, counted from seat and gear signals. The next walk break suggestion is in about 18 minutes.", r.message)
        assertEquals("앉은 지 45분이에요(좌석과 기어 신호로 센 값).", call(Gleo.CHECK, driving.copy(sitMinutes = 45, breakMinutes = null), "topic" to "SITTING").response.korean)
        assertEquals("앉은 지 2시간 5분이에요(좌석과 기어 신호로 센 값). 쉬어 갈 때가 됐어요.", call(Gleo.CHECK, driving.copy(sitMinutes = 125, breakMinutes = 120), "topic" to "sitting").response.korean)
        assertEquals("아직 앉은 시간을 세기 시작한 지 얼마 안 됐어요.", call(Gleo.CHECK, GleoState(), "topic" to "sitting").response.korean)
        assertEquals("앉은 지 72초예요(좌석과 기어 신호로 센 값). 약 18초 뒤에 걷기 쉼을 물을게요.", call(Gleo.CHECK, driving.copy(demo = true), "topic" to "sitting").response.korean)
    }

    // ---------- (c) 쉬었다 가자 ----------

    @Test fun `쉼터는 주행 방향을 알고 앞에 쉼터가 있을 때만 넣는다`() {
        val o = call(Gleo.REST_STOP, driving)
        assertEquals(GleoCommand.ADD_REST_STOP, o.command)
        assertEquals("12km 앞 석적 졸음쉼터를 경유지에 넣을게요.", o.response.korean)
        assertEquals("Adding 석적 졸음쉼터, about 12 km ahead, as a waypoint.", o.response.message)
        fun none(s: GleoState): GleoResponse = call(Gleo.REST_STOP, s).also { assertNull(it.command) }.response
        assertEquals(GleoStatus.FAIL, none(driving.copy(headingUnknown = true)).status)
        assertTrue(none(driving.copy(headingUnknown = true)).korean.contains("주행 방향"))
        assertEquals(GleoStatus.FAIL, none(driving.copy(restStopName = null)).status)
        assertEquals(GleoStatus.FAIL, none(driving.copy(driving = false)).status)
        assertEquals(GleoStatus.FAIL, none(driving.copy(busy = true)).status)
        // 이미 경유지에 있거나 쉬는 중이면 할 일이 없다(실패가 아니다)
        assertEquals("석적 졸음쉼터는 이미 경유지에 있어요.", none(driving.copy(waypointName = "석적 졸음쉼터", restStopName = null)).korean)
        assertEquals(GleoStatus.SUCCESS, none(driving.copy(resting = true)).status)
    }

    @Test fun `모르는 함수와 엔진이 받지 않은 부탁과 앱이 꺼져 있을 때`() {
        assertEquals(GleoStatus.FAIL, call("PLAY_MUSIC", driving).response.status)
        assertEquals("PLAY_MUSIC", call("PLAY_MUSIC", driving).response.functionName)
        assertEquals(GleoStatus.FAIL, GleoLogic.refused(Gleo.VENTILATE).status)
        assertEquals("숨이 켜져 있지 않아요. 앱을 먼저 열어 주세요.", GleoLogic.notRunning(Gleo.CHECK).korean)
    }

    @Test fun `답에 쓰지 않기로 한 말이 없다`() {
        val states = listOf(driving, GleoState(), driving.copy(carAuto = true), driving.copy(resting = true), driving.copy(recirculating = false), driving.copy(headingUnknown = true))
        val all = states.flatMap { s -> Gleo.FUNCTIONS.flatMap { fn -> listOf(call(fn, s), call(fn, s, "topic" to "sitting")) } }.map { it.response }
        for (r in all) {
            for (b in listOf("진단", "치료", "측정", "건강", "예방", "보장", "감지", "의학")) assertFalse("${r.korean} 에 '$b'", r.korean.contains(b))
            for (b in listOf("diagnos", "treat", "health", "medical", "measure", "prevent", "guarantee", "detect")) assertFalse("${r.message} 에 '$b'", r.message.lowercase().contains(b))
            assertFalse(r.korean.contains('—') || r.message.contains('—'))
        }
    }

    // ---------- 응답 JSON ----------

    @Test fun `응답 JSON 은 문서의 콜백 형식이다`() {
        val json = Json.parseToJsonElement(call(Gleo.CHECK, driving).response.toJson()).jsonObject
        assertEquals(setOf("functionName", "status", "message", "data"), json.keys)
        assertEquals("CHECK_CABIN_STATUS", json["functionName"]!!.jsonPrimitive.content)
        assertEquals("success", json["status"]!!.jsonPrimitive.content)
        // data 는 JSON 배열을 문자열로 넣은 것이다
        assertTrue(json["data"]!!.jsonPrimitive.isString)
        val data = Json.parseToJsonElement(json["data"]!!.jsonPrimitive.content).jsonArray.single().jsonObject
        assertEquals("1749", data["estimatedCo2Ppm"]!!.jsonPrimitive.content)
        assertTrue(data[Gleo.KEY_KOREAN]!!.jsonPrimitive.content.startsWith("차 안 이산화탄소는 추정"))
        // data 가 없으면 키도 없다
        assertEquals(setOf("functionName", "status", "message"), Json.parseToJsonElement(GleoResponse("X", GleoStatus.FAIL, "m").toJson()).jsonObject.keys)
        assertEquals(listOf("success", "asking", "fail"), GleoStatus.entries.map { it.wire })
        assertEquals("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE", Gleo.RESPONSE_ACTION)
        assertEquals("ACTION_PARAM", Gleo.EXTRA_PARAM)
    }

    // ---------- 스키마 파일 ----------

    private val schemaFile = listOf(File("src/main/assets/schema.json"), File("app/src/main/assets/schema.json")).first { it.exists() }

    @Test fun `schema_json 은 문서의 제약을 지킨다`() {
        val text = schemaFile.readText()
        val s = GleoSchema.inspect(text, "ai.kkaeugi.app")
        assertEquals(emptyList<String>(), s.problems)
        assertEquals(Gleo.FUNCTIONS, s.functions)
        assertTrue("함수는 셋까지", s.functions.size <= Gleo.MAX_FUNCTIONS)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("packageName", "appDescription", "version", "actionCalls"), root.keys)
        for (call in root["actionCalls"]!!.jsonArray) {
            assertEquals("function", call.jsonObject["type"]!!.jsonPrimitive.content)
            val f = call.jsonObject["function"]!!.jsonObject
            // 방송으로 받는다(수신기)
            assertEquals("service", f["component"]!!.jsonPrimitive.content)
            val description = f["description"]!!.jsonPrimitive.content
            assertTrue("설명은 영어 한두 문장: $description", description.all { it.code < 128 } && description.count { it == '.' } in 1..2)
            val params = f["parameters"]!!.jsonObject
            assertEquals("object", params["type"]!!.jsonPrimitive.content)
            for ((_, p) in params["properties"]!!.jsonObject) {
                assertTrue(p.jsonObject["type"]!!.jsonPrimitive.content in setOf("string", "number", "boolean", "array"))
                assertTrue(p.jsonObject["description"]!!.jsonPrimitive.content.all { it.code < 128 })
            }
        }
        // 매니페스트의 수신기가 같은 함수 이름을 action 으로 받는다
        val manifest = File(schemaFile.parentFile.parentFile, "AndroidManifest.xml").readText()
        for (fn in Gleo.FUNCTIONS) assertTrue("$fn action 필터", manifest.contains("<action android:name=\"$fn\" />"))
        assertTrue(manifest.contains("android:name=\".gleo.GleoReceiver\""))
        // 제어 검증 수신기는 DUMP 퍼미션으로 막아 둔다
        assertTrue(Regex("android:name=\"\\.gleo\\.VerifyReceiver\"[^>]*android:permission=\"android\\.permission\\.DUMP\"").containsMatchIn(manifest))
    }

    @Test fun `스키마 점검은 어긋난 점을 적는다`() {
        assertEquals(listOf("JSON 을 읽지 못함"), GleoSchema.inspect("{", "ai.kkaeugi.app").problems)
        val bad = """{"packageName":"other","actionCalls":[
            {"type":"function","function":{"name":"VENTILATE_CABIN"}},{"type":"function","function":{"name":"VENTILATE_CABIN"}},
            {"type":"function","function":{"name":"checkAir"}},{"type":"function","function":{"name":"ADD_REST_STOP"}}]}"""
        val p = GleoSchema.inspect(bad, "ai.kkaeugi.app").problems
        assertTrue(p.toString(), p.contains("packageName 이 앱과 다름"))
        assertTrue(p.toString(), p.contains("함수가 3개를 넘음"))
        assertTrue(p.toString(), p.contains("이름이 겹침"))
        assertTrue(p.toString(), p.any { it.startsWith("checkAir: 대문자") })
        assertTrue(p.toString(), p.contains("CHECK_CABIN_STATUS: 스키마에 없음"))
    }
}

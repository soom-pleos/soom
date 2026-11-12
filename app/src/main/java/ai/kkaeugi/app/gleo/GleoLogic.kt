package ai.kkaeugi.app.gleo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Gleo Interface 연동의 순수 로직(JVM 테스트 대상). 수신기(GleoReceiver)와 따로 둔다.
 *
 * Pleos 문서에서 확인한 것(document.pleos.ai, "Implementing the Gleo Interface", 2026-11-12 열람):
 *  - 앱은 assets/schema.json(App Schema)에 Gleo AI 가 부를 함수를 적는다. actionCalls 는 셋까지다.
 *    함수 이름은 대문자와 밑줄, 설명은 영어 한두 문장, 파라미터 타입은 string, number, boolean, array 가운데 하나.
 *  - Gleo AI 는 함수 이름을 Intent 의 action 으로, 파라미터를 문자열 extra "ACTION_PARAM"(JSON)으로 넣어
 *    component 가 "service" 면 sendBroadcast, "activity" 면 startActivity 로 앱(패키지 지정)에 보낸다.
 *  - 앱은 결과를 방송 "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" 로 돌려준다.
 *    extra "packageName"(앱 패키지)과 "response"(JSON: functionName, status, message, data).
 *    status 는 success, asking, fail. message 는 짧은 영어 한 문장(장소 이름 같은 것은 다른 언어도 된다).
 *    data 는 키와 값 객체의 JSON 배열을 문자열로 넣은 것(선택). Gleo 는 7초 안에 답이 없으면 그 액션을 버린다.
 *  - 스키마는 Gleo 쪽 검토를 거쳐 승인된 뒤에 쓸 수 있다.
 * 문서에 없는 것(수신기의 exported 여부, 보내는 쪽 퍼미션, 응답 방송의 대상 패키지)은 지어내지 않았다.
 *
 * 함수는 셋이다(문서의 상한). "공기 어때"와 "얼마나 앉아 있었어"는 CHECK_CABIN_STATUS 하나에 topic 으로 묶었다.
 * 판단은 엔진이 한다. 여기서는 지금 상태(GleoState)를 보고 무엇을 답하고 무엇을 엔진에 부탁할지만 정한다.
 * 값은 전부 추정이고 답에도 그렇게 적는다. 몸을 재지 않는다.
 */
object Gleo {
    const val TAG = "Kkaeugi.Gleo"
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    const val EXTRA_PARAM = "ACTION_PARAM"
    const val EXTRA_PACKAGE = "packageName"
    const val EXTRA_RESPONSE = "response"
    /** Gleo 가 답을 기다리는 시간(문서 값) */
    const val TIMEOUT_MS = 7_000L

    const val VENTILATE = "VENTILATE_CABIN"
    const val CHECK = "CHECK_CABIN_STATUS"
    const val REST_STOP = "ADD_REST_STOP"
    val FUNCTIONS = listOf(VENTILATE, CHECK, REST_STOP)
    /** 문서가 정한 함수 수의 상한 */
    const val MAX_FUNCTIONS = 3

    const val TOPIC = "topic"
    const val TOPIC_AIR = "air"
    const val TOPIC_SITTING = "sitting"
    /** data 에 싣는 한국어 한 문장의 키. Gleo 가 답을 만들 때 참고하고, 화면과 로그에도 같은 문장을 쓴다 */
    const val KEY_KOREAN = "koreanSentence"
}

enum class GleoStatus(val wire: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }

/** 콜백 한 건. data 는 키와 값(문자열) 쌍의 목록이고 JSON 에서는 객체 하나짜리 배열의 문자열이 된다 */
data class GleoResponse(val functionName: String, val status: GleoStatus, val message: String, val data: List<Pair<String, String>> = emptyList()) {
    /** 한국어 한 문장(data 에 실은 것). 없으면 영어 문장 */
    val korean: String get() = data.firstOrNull { it.first == Gleo.KEY_KOREAN }?.second ?: message

    /** 응답 방송의 "response" extra 에 넣는 JSON 문자열 */
    fun toJson(): String = buildJsonObject {
        put("functionName", functionName)
        put("status", status.wire)
        put("message", message)
        if (data.isNotEmpty()) put("data", JsonArray(listOf(JsonObject(data.associate { it.first to JsonPrimitive(it.second) }))).toString())
    }.toString()
}

/** Gleo 가 보낸 파라미터 읽기(순수 함수) */
object GleoParams {
    /**
     * "ACTION_PARAM" 의 JSON 문자열을 키와 값으로 편다. 문서의 예시는 JSON 을 따옴표로 한 번 더 감싼 꼴이라 그것도 벗겨 읽는다.
     * 비었거나 JSON 이 아니면 빈 맵. 값은 전부 문자열로 본다
     */
    fun parse(raw: String?): Map<String, String> {
        var text = raw?.trim().orEmpty()
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) text = text.substring(1, text.length - 1).replace("\\\"", "\"")
        if (!text.startsWith("{")) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
        }.getOrDefault(emptyMap())
    }
}

/** 지금 앱과 엔진의 상태에서 답에 필요한 것만 뽑은 것(순수 데이터) */
data class GleoState(
    val driving: Boolean = false,
    val resting: Boolean = false,
    /** 제안, 예고, 실행이 떠 있다 */
    val busy: Boolean = false,
    /** 지금 내기순환인가(엔진이 잠깐 바꿔 둔 중이면 false) */
    val recirculating: Boolean = false,
    /** 차가 내외기를 스스로 제어하는 중. 이때는 건드리지 않는다 */
    val carAuto: Boolean = false,
    /** 숨 켜기 설정 */
    val enabled: Boolean = true,
    /** 추정 CO2(ppm). 아직 없으면 null */
    val ppm: Int? = null,
    /** 상태 한 단어("쾌적", "보통", "탁함", "한도") */
    val level: String? = null,
    /** 한도까지 남은 분(반올림). 닿지 않으면 null */
    val minutesToLimit: Int? = null,
    val ventilating: Boolean = false,
    /** 바깥 미세먼지 등급("좋음"…)과 값. 없으면 null */
    val pmGrade: String? = null,
    val pm25: Int? = null,
    /** 가장 오래 앉은 자리의 앉은 시간(분, 데모 속도면 초)과 쉬는 간격 */
    val sitMinutes: Int = 0,
    val breakMinutes: Int? = null,
    /** 지금 넣을 수 있는 전방 쉼터의 이름(표시 이름)과 거리 */
    val restStopName: String? = null,
    val restStopKm: Int? = null,
    /** 경유지로 이미 넣은 쉼터 */
    val waypointName: String? = null,
    /** 주행 방향을 아직 모른다 */
    val headingUnknown: Boolean = false,
    /** 환기 길이(초) */
    val ventSeconds: Int = 120,
    /** 데모 속도(분이 초로 흐른다) */
    val demo: Boolean = false,
)

/** 답과 함께 엔진에 부탁할 일 */
enum class GleoCommand { VENTILATE, ADD_REST_STOP }

data class GleoOutcome(val response: GleoResponse, val command: GleoCommand? = null)

object GleoLogic {
    private fun unit(s: GleoState) = if (s.demo) "초" else "분"
    private fun unitEn(s: GleoState, n: Int) = (if (s.demo) "second" else "minute") + if (n == 1) "" else "s"
    private fun ppm(n: Int): String = "%,d".format(java.util.Locale.US, (n + 50) / 100 * 100)
    private val LEVEL_EN = mapOf("쾌적" to "fresh", "보통" to "normal", "탁함" to "stale", "한도" to "at the limit")
    private val GRADE_EN = mapOf("좋음" to "good", "보통" to "moderate", "나쁨" to "bad", "매우 나쁨" to "very bad")

    /** 받침이 있으면 "이에요", 없으면 "예요"("탁함이에요", "한도예요", "72초예요") */
    internal fun copula(word: String): String {
        val last = word.lastOrNull { it in '가'..'힣' } ?: return "이에요"
        return if ((last.code - 0xAC00) % 28 != 0) "이에요" else "예요"
    }

    private fun ok(fn: String, en: String, ko: String, vararg more: Pair<String, String>) =
        GleoResponse(fn, GleoStatus.SUCCESS, en, listOf(Gleo.KEY_KOREAN to ko) + more)
    private fun fail(fn: String, en: String, ko: String) = GleoResponse(fn, GleoStatus.FAIL, en, listOf(Gleo.KEY_KOREAN to ko))

    /** 함수 하나를 처리한다. 모르는 함수는 fail */
    fun handle(function: String, params: Map<String, String>, s: GleoState): GleoOutcome = when (function) {
        Gleo.VENTILATE -> ventilate(s)
        Gleo.CHECK -> GleoOutcome(if (params[Gleo.TOPIC]?.lowercase() == Gleo.TOPIC_SITTING) sitting(s) else air(s))
        Gleo.REST_STOP -> restStop(s)
        else -> GleoOutcome(fail(function, "Soom does not have this function.", "숨에 없는 기능이에요."))
    }

    /** 엔진이 부탁을 받지 않았을 때(그 사이 상태가 바뀌었다)의 답 */
    fun refused(function: String): GleoResponse =
        fail(function, "Kkaeugi could not do that right now. Try again shortly.", "지금은 할 수 없어요. 조금 뒤에 다시 말해 주세요.")

    /** 앱 화면이 떠 있지 않아 엔진이 없을 때의 답 */
    fun notRunning(function: String): GleoResponse =
        fail(function, "Soom is not running. Open the app first.", "숨이 켜져 있지 않아요. 앱을 먼저 열어 주세요.")

    /** (a) 환기해 줘: 바깥 공기로 잠깐 바꾸고 되돌린다. 차의 자동 공조는 건드리지 않는다 */
    private fun ventilate(s: GleoState): GleoOutcome {
        val fn = Gleo.VENTILATE
        val length = if (s.ventSeconds % 60 == 0 && s.ventSeconds >= 60) "${s.ventSeconds / 60}분" else "${s.ventSeconds}초"
        val lengthEn = if (s.ventSeconds % 60 == 0 && s.ventSeconds >= 60) "${s.ventSeconds / 60} minute${if (s.ventSeconds == 60) "" else "s"}" else "${s.ventSeconds} seconds"
        return when {
            !s.driving -> GleoOutcome(fail(fn, "The car is not being driven. Kkaeugi ventilates only while driving.", "주행 중일 때만 환기해요."))
            s.resting -> GleoOutcome(fail(fn, "The car is resting at a rest area.", "지금은 쉼터에서 쉬는 중이에요."))
            s.carAuto -> GleoOutcome(fail(fn, "The car is controlling the air intake automatically, so Kkaeugi leaves it alone.", "차가 내외기를 자동으로 조절하고 있어서 건드리지 않아요."))
            !s.recirculating -> GleoOutcome(ok(fn, "Outside air is already coming in.", "지금도 바깥 공기가 들어오고 있어요."))
            s.busy -> GleoOutcome(fail(fn, "Another Kkaeugi action is in progress. Try again shortly.", "다른 안내가 진행 중이에요. 조금 뒤에 다시 말해 주세요."))
            else -> GleoOutcome(
                ok(fn, "Switching to outside air for $lengthEn, then restoring the previous setting.", "${length}만 바깥 공기로 바꿀게요. 끝나면 원래대로 돌려요."),
                GleoCommand.VENTILATE,
            )
        }
    }

    /** (b) 공기 어때: 추정 CO2, 상태 한 단어, 바깥 미세먼지 등급, 다음 환기 예상 */
    private fun air(s: GleoState): GleoResponse {
        val fn = Gleo.CHECK
        val value = s.ppm ?: return ok(fn, "There is no estimate yet. It starts after the car begins driving.", "아직 추정값이 없어요. 출발하면 추정을 시작해요.")
        val level = s.level ?: ""
        val pmKo = s.pmGrade?.let { " 바깥 미세먼지는 $it${s.pm25?.let { v -> "($v)" } ?: ""}이에요." } ?: ""
        val pmEn = s.pmGrade?.let { " Outside fine dust is ${GRADE_EN[it] ?: it}${s.pm25?.let { v -> " ($v)" } ?: ""}." } ?: ""
        val (nextKo, nextEn) = when {
            !s.driving -> "" to ""
            s.ventilating -> " 지금은 바깥 공기가 들어오고 있어요." to " Outside air is coming in now."
            !s.enabled -> "" to ""
            s.minutesToLimit == null -> " 지금 상태로는 한도에 닿지 않아요." to " At this rate it will not reach the limit."
            s.minutesToLimit <= 0 -> " 한도에 닿았어요." to " It has reached the limit."
            else -> " 약 ${s.minutesToLimit}${unit(s)} 뒤에 환기를 물을게요." to " The next ventilation prompt is in about ${s.minutesToLimit} ${unitEn(s, s.minutesToLimit)}."
        }
        return ok(
            fn,
            "Estimated cabin CO2 is about ${ppm(value)} ppm${LEVEL_EN[level]?.let { " ($it)" } ?: ""}.$pmEn$nextEn These are estimates, not sensor readings.",
            "차 안 이산화탄소는 추정 ${ppm(value)}ppm${if (level.isNotEmpty()) ", $level${copula(level)}" else "이에요"}.$pmKo$nextKo",
            "estimatedCo2Ppm" to value.toString(),
        )
    }

    /** (d) 얼마나 앉아 있었어: 앉은 시간과 다음 걷기 쉼까지 남은 시간. 좌석과 기어 신호로 센 값이다 */
    private fun sitting(s: GleoState): GleoResponse {
        val fn = Gleo.CHECK
        if (s.sitMinutes < 1) return ok(fn, "No one has been counted as seated for a minute yet.", "아직 앉은 시간을 세기 시작한 지 얼마 안 됐어요.")
        val sat = if (s.demo) "${s.sitMinutes}초" else if (s.sitMinutes >= 60) "${s.sitMinutes / 60}시간 ${s.sitMinutes % 60}분" else "${s.sitMinutes}분"
        val left = s.breakMinutes?.let { it - s.sitMinutes }
        val (nextKo, nextEn) = when {
            left == null -> "" to ""
            left <= 0 -> " 쉬어 갈 때가 됐어요." to " A walk break is due."
            else -> " 약 $left${unit(s)} 뒤에 걷기 쉼을 물을게요." to " The next walk break suggestion is in about $left ${unitEn(s, left)}."
        }
        return ok(
            fn,
            "Seated for about ${s.sitMinutes} ${unitEn(s, s.sitMinutes)}, counted from seat and gear signals.$nextEn",
            "앉은 지 $sat${copula(sat)}(좌석과 기어 신호로 센 값).$nextKo",
            "seatedMinutes" to s.sitMinutes.toString(),
        )
    }

    /** (c) 쉬었다 가자: 앞쪽 같은 방향 쉼터를 경유지로. 주행 방향을 알 때만 */
    private fun restStop(s: GleoState): GleoOutcome {
        val fn = Gleo.REST_STOP
        return when {
            !s.driving -> GleoOutcome(fail(fn, "The car is not being driven.", "주행 중일 때만 쉼터를 넣어요."))
            s.resting -> GleoOutcome(ok(fn, "The car is already resting at a rest area.", "지금 쉼터에서 쉬는 중이에요."))
            s.waypointName != null -> GleoOutcome(ok(fn, "${s.waypointName} is already a waypoint.", "${s.waypointName}는 이미 경유지에 있어요."))
            s.headingUnknown -> GleoOutcome(fail(fn, "The driving direction is not known yet, so Kkaeugi cannot pick a rest area ahead.", "주행 방향을 아직 몰라서 앞쪽 쉼터를 고를 수 없어요."))
            s.restStopName == null -> GleoOutcome(fail(fn, "No rest area was found ahead in the driving direction.", "앞쪽 같은 방향에 넣을 쉼터가 없어요."))
            s.busy -> GleoOutcome(fail(fn, "Another Kkaeugi action is in progress. Try again shortly.", "다른 안내가 진행 중이에요. 조금 뒤에 다시 말해 주세요."))
            else -> {
                val km = s.restStopKm?.let { " about $it km ahead" } ?: " ahead"
                val kmKo = s.restStopKm?.let { "${it}km 앞 " } ?: ""
                GleoOutcome(
                    ok(fn, "Adding ${s.restStopName},$km, as a waypoint.", "$kmKo${s.restStopName}를 경유지에 넣을게요.", "restStop" to s.restStopName),
                    GleoCommand.ADD_REST_STOP,
                )
            }
        }
    }
}

/** assets/schema.json 을 읽어 본 결과(자가 진단용, 순수 함수) */
object GleoSchema {
    data class Summary(val functions: List<String>, val problems: List<String>)

    /** 함수 이름 목록과, 문서의 제약에 어긋나는 점(없으면 빈 목록) */
    fun inspect(json: String, packageName: String): Summary {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return Summary(emptyList(), listOf("JSON 을 읽지 못함"))
        val problems = mutableListOf<String>()
        if ((root["packageName"] as? JsonPrimitive)?.content != packageName) problems += "packageName 이 앱과 다름"
        val calls = (root["actionCalls"] as? JsonArray).orEmpty()
        if (calls.size > Gleo.MAX_FUNCTIONS) problems += "함수가 ${Gleo.MAX_FUNCTIONS}개를 넘음"
        val names = calls.mapNotNull { c -> ((c as? JsonObject)?.get("function") as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.content } }
        if (names.size != calls.size) problems += "이름 없는 함수가 있음"
        if (names.distinct().size != names.size) problems += "이름이 겹침"
        names.filterNot { Regex("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*").matches(it) }.forEach { problems += "$it: 대문자와 밑줄 규칙에 어긋남" }
        names.filterNot { it in Gleo.FUNCTIONS }.forEach { problems += "$it: 수신기가 모르는 함수" }
        Gleo.FUNCTIONS.filterNot { it in names }.forEach { problems += "$it: 스키마에 없음" }
        return Summary(names, problems)
    }
}

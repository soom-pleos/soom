package ai.kkaeugi.app.signals

import ai.kkaeugi.app.vehicle.DemoLocation
import ai.kkaeugi.app.vehicle.GeoPoint
import ai.kkaeugi.app.vehicle.RestStopMatcher
import ai.kkaeugi.engine.OutdoorAir
import ai.kkaeugi.engine.PmGrade
import ai.kkaeugi.engine.VentPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 설정의 "바깥 미세먼지" 선택. 자동이면 Open-Meteo 에서 받고, 나머지는 그 등급의 대표값을 직접 넣는다.
 * 수동 선택은 네트워크가 없는 에뮬레이터와 시연용이다(대표값은 등급 한가운데쯤의 임의 값이고 실제 값이 아니다)
 */
enum class PmChoice(val label: String, val pm25: Double?) {
    AUTO("자동", null),
    GOOD("좋음", 10.0),
    MODERATE("보통", 25.0),
    BAD("나쁨", 41.0),
    VERY_BAD("매우 나쁨", 90.0);

    companion object {
        fun of(name: String?): PmChoice = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Open-Meteo Air Quality API(순수 함수와 HTTP 한 번). 키가 필요 없고 비상업 용도는 무료다.
 * 값은 CAMS(코페르니쿠스 대기 감시 서비스)의 전지구 모델에서 온다. 격자가 약 45km(0.4도)라서 도로변 실측이 아니다.
 * 터널 안, 대형 트럭 뒤 같은 그 자리의 공기는 이 값으로 알 수 없다.
 * 출처 표기(ATTRIBUTION)를 화면과 README 에 적는다.
 */
object OpenMeteoAir {
    const val ENDPOINT = "https://air-quality-api.open-meteo.com/v1/air-quality"
    const val ATTRIBUTION = "Air quality data: CAMS ENSEMBLE via Open-Meteo"
    /** 화면의 작은 글씨와 문서에 쓰는 한계 문장 */
    const val GRID_NOTE = "도로변 실측이 아니라 넓은 격자(약 45km) 모델값이에요"
    const val SOURCE = "Open-Meteo(CAMS 격자 모델값)"
    const val TIMEOUT_MS = 5_000

    /** 좌표는 소수 첫째 자리까지만 보낸다(약 10km. 격자가 45km 라 값은 같고, 정확한 위치는 보내지 않는다) */
    fun roundCoord(v: Double): Double = (v * 10).roundToInt() / 10.0

    fun url(lat: Double, lng: Double): String =
        String.format(Locale.US, "%s?latitude=%.1f&longitude=%.1f&current=pm2_5,pm10", ENDPOINT, roundCoord(lat), roundCoord(lng))

    /** 받은 값 한 쌍(µg/m³). pm10 은 화면 참고용이다 */
    data class Values(val pm25: Double, val pm10: Double?)

    /**
     * 응답에서 current.pm2_5 를 꺼낸다. 형식이 다르거나, 값이 없거나(null), 음수면 null.
     * 예: {"current":{"time":"2026-11-11T03:00","interval":3600,"pm2_5":41.2,"pm10":58.0}}
     */
    fun parse(body: String?): Values? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val current = (Json.parseToJsonElement(body) as? JsonObject)?.get("current") as? JsonObject ?: return null
            val pm25 = number(current["pm2_5"]) ?: return null
            if (pm25 < 0.0 || pm25.isNaN()) return null
            Values(pm25, number(current["pm10"])?.takeIf { it >= 0.0 })
        }.getOrNull()
    }

    private fun number(e: Any?): Double? = when (e) {
        null, is JsonNull -> null
        is JsonPrimitive -> e.doubleOrNull
        else -> null
    }

    /**
     * HTTP GET 한 번. 새 의존성 없이 HttpURLConnection 으로 한다. 연결과 읽기 모두 5초.
     * 실패하면 예외를 던진다(부르는 쪽이 조용히 null 로 바꾼다). 메인 스레드에서 부르지 않는다
     */
    fun httpGet(url: String, timeoutMs: Int = TIMEOUT_MS): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** 받아 둔 값 하나. at 은 받은 시각(단조 시계 ms) */
data class OutdoorReading(val pm25: Double, val pm10: Double?, val at: Long, val source: String) {
    fun ageMin(now: Long): Int = ((now - at).coerceAtLeast(0L) / 60_000L).toInt()
    val grade: PmGrade get() = VentPlanner.grade(pm25)
}

/**
 * 언제 다시 받을지 정하는 규칙(순수 로직, JVM 테스트 대상).
 *  - 받은 값은 CACHE_MS(30분) 동안 그대로 쓴다. 격자 모델값이라 자주 받아도 달라지지 않는다
 *  - 실패하면 RETRY_MS(5분) 뒤에 다시 해 본다. 그동안 앞서 받은 값이 있으면 나이를 붙여 계속 넘긴다(엔진이 60분에서 버린다)
 *  - 실제 위치를 몰라 모의 위치로 받은 값(provisional)도 5분 뒤에 다시 받아 본다. 그 사이 내비 위치가 잡히면 그 자리의 값으로 바뀐다
 */
class OutdoorAirCache {
    var reading: OutdoorReading? = null
        private set
    private var lastTryAt: Long? = null
    private var lastFailed = false
    private var provisional = false

    fun shouldFetch(now: Long): Boolean {
        val tried = lastTryAt ?: return true
        val wait = if (lastFailed || provisional) RETRY_MS else CACHE_MS
        return now - tried >= wait
    }

    fun onTry(now: Long) { lastTryAt = now }
    fun onSuccess(r: OutdoorReading, provisional: Boolean = false) { reading = r; lastFailed = false; this.provisional = provisional }
    fun onFailure() { lastFailed = true }
    fun clear() { reading = null; lastTryAt = null; lastFailed = false; provisional = false }

    companion object {
        const val CACHE_MS = 30 * 60_000L
        const val RETRY_MS = 5 * 60_000L
    }
}

/**
 * 바깥 미세먼지 값을 엔진에 넣는 곳. 차에는 바깥 공기 센서 값을 주는 API 가 없어서(docs/API_FACTS.md) 날씨 서비스의 격자 모델값을 쓴다.
 *
 * refresh() 를 1분마다 부르면 된다:
 *  - 설정이 꺼져 있으면 값 없음(null)을 넘긴다. 엔진은 v0.6.2 와 똑같이 움직인다
 *  - 수동 선택(좋음, 보통, 나쁨, 매우 나쁨)이면 그 등급의 대표값을 넘긴다
 *  - 자동이면 받아 둔 값을 나이와 함께 넘기고, 30분이 지났으면 새로 받는다(실패하면 5분 뒤 다시)
 * 위치는 내비 위치를 먼저 쓰고, 없거나 한국 밖이면 모의 위치를 쓴다(출처에 "모의 위치"라고 적는다).
 * 실패는 조용히 넘긴다. 값이 없으면 앱은 CO2 추정만으로 판단한다
 */
class OutdoorAirSource(
    private val scope: CoroutineScope,
    private val emit: (OutdoorAir) -> Unit,
    private val now: () -> Long,
    private val location: suspend () -> GeoPoint?,
    private val fetch: suspend (String) -> String = { url -> withContext(Dispatchers.IO) { OpenMeteoAir.httpGet(url) } },
) {
    @Volatile var enabled = true
    @Volatile var choice = PmChoice.AUTO
    val cache = OutdoorAirCache()
    /** 마지막 실패 사유(자가 진단용). 성공하면 null */
    @Volatile var lastError: String? = null
        private set
    private var job: Job? = null

    /** 지금 넘길 값을 엔진에 넣는다. 자동이고 받을 때가 됐으면 뒤에서 받아 와 한 번 더 넣는다 */
    fun refresh() {
        val t = now()
        if (!enabled) { emit(OutdoorAir(t, null, "꺼짐")); return }
        choice.pm25?.let { emit(OutdoorAir(t, it, MANUAL_SOURCE)); return }
        emitCached(t)
        if (cache.shouldFetch(t) && job?.isActive != true) job = scope.launch { fetchNow() }
    }

    private fun emitCached(t: Long) {
        val r = cache.reading
        emit(if (r == null) OutdoorAir(t, null, OpenMeteoAir.SOURCE) else OutdoorAir(t, r.pm25, r.source, r.ageMin(t)))
    }

    /** 지금 한 번 받아 본다(자가 진단도 이걸 부른다). 성공하면 받은 값을 엔진에도 넣는다. 실패하면 null 이고 사유는 lastError */
    suspend fun fetchNow(): OutdoorReading? {
        val t = now()
        cache.onTry(t)
        val real = runCatching { location() }.getOrNull()
        val inKorea = real != null && RestStopMatcher.isInKorea(real.lat, real.lng)
        val p = DemoLocation.resolve(real)
        val result = runCatching { OpenMeteoAir.parse(fetch(OpenMeteoAir.url(p.lat, p.lng))) ?: error("응답에 pm2_5 값이 없음") }
        val values = result.getOrNull()
        if (values == null) {
            cache.onFailure()
            lastError = result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" } ?: "알 수 없는 실패"
            return null
        }
        val reading = OutdoorReading(values.pm25, values.pm10, now(), if (inKorea) OpenMeteoAir.SOURCE else "${OpenMeteoAir.SOURCE}, 모의 위치")
        cache.onSuccess(reading, provisional = !inKorea)
        lastError = null
        // 받는 사이 설정이 바뀌었으면 그쪽을 따른다
        if (enabled && choice == PmChoice.AUTO) emitCached(now())
        return reading
    }

    /** 자가 진단과 정차 홈에 쓰는 한 줄: 값, 등급, 출처, 나이 */
    fun statusLine(t: Long = now()): String = when {
        !enabled -> "꺼짐(CO2 추정만으로 판단해요)"
        choice != PmChoice.AUTO -> "PM2.5 ${choice.pm25!!.roundToInt()}µg/m³(${choice.label}) · $MANUAL_SOURCE"
        else -> cache.reading?.let { describe(it, t) } ?: ("값 없음" + (lastError?.let { " ($it)" } ?: "") + ". CO2 추정만으로 판단해요")
    }

    companion object {
        const val MANUAL_SOURCE = "수동 선택"

        fun describe(r: OutdoorReading, now: Long): String =
            "PM2.5 ${r.pm25.roundToInt()}µg/m³(${r.grade.label}) · ${r.source} · ${r.ageMin(now)}분 전"
    }
}

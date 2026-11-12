package ai.kkaeugi.app.vehicle

/** SDK 호출 한 번의 결과. 콜백 값이 null 이거나 시간 안에 답이 없으면 Fail */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Fail(val error: String) : Outcome<Nothing>
}

/**
 * VehicleActuator 가 쓰는 차량 제어 호출 묶음. 실제 구현은 PleosCalls(Pleos Vehicle SDK), 테스트는 FakeVehicleControls.
 * 모든 get/set 은 suspend 이고 실패를 Outcome.Fail 로 돌려준다(예외를 던지지 않는다).
 */
interface VehicleControls {
    suspend fun getRecirculation(): Outcome<Boolean>
    suspend fun setRecirculation(on: Boolean): Outcome<Boolean>
    suspend fun getFanSpeed(): Outcome<Int>
    suspend fun setFanSpeed(v: Int): Outcome<Int>
    /** 운전석 풍량 범위(min, max). 못 읽으면 기본값 */
    suspend fun fanRange(): Pair<Int, Int>
    suspend fun getTemperature(): Outcome<Float>
    suspend fun setTemperature(c: Float): Outcome<Float>
    /** 운전석 온도 범위(섭씨). 못 읽으면 기본값 */
    suspend fun temperatureRangeC(): Pair<Float, Float>
    suspend fun getWindow(): Outcome<Int>
    suspend fun setWindow(pos: Int): Outcome<Int>
    /** 운전석 창문 위치 범위(min, max). 못 읽으면 0~100 */
    suspend fun windowRange(): Pair<Int, Int>
    /**
     * 공조 전원. 전원을 주지 않는 차(영역 없음)와 전원을 모르는 구현은 Fail(POWER_UNKNOWN)로 답한다.
     * 그때 쓰는 쪽은 전원을 건드리지 않고, 전원을 되돌리지 못한다는 사실을 결과에 적는다
     */
    suspend fun getHvacPower(): Outcome<Boolean> = Outcome.Fail(POWER_UNKNOWN)
    suspend fun setHvacPower(on: Boolean): Outcome<Boolean> = Outcome.Fail(POWER_UNKNOWN)

    companion object {
        const val POWER_UNKNOWN = "POWER_UNKNOWN: 이 차량 구현은 공조 전원을 다루지 않음"
    }
}

/**
 * 명령 뒤 확인(순수 로직, JVM 테스트 대상). 차는 값을 조금 늦게 바꾼다. 한 번 읽고 "안 바뀌었다"고 단정하지 않도록
 * stepMs 간격으로 timeoutMs 까지 다시 읽는다. 기대한 값이 보이면 바로 끝낸다.
 * waitedMs 는 기다린 시간의 합이다(읽기 호출 자체에 걸린 시간은 넣지 않는다)
 */
object Confirm {
    const val TIMEOUT_MS = 3_000L
    const val STEP_MS = 500L

    /** value: 마지막으로 읽은 값(한 번도 못 읽었으면 null). matched: 기대한 값을 봤는가. error: 마지막 읽기 실패 사유 */
    data class Seen<T>(val value: T?, val waitedMs: Long, val matched: Boolean, val error: String? = null)

    suspend fun <T : Any> until(
        expected: T, get: suspend () -> Outcome<T>, wait: suspend (Long) -> Unit,
        timeoutMs: Long = TIMEOUT_MS, stepMs: Long = STEP_MS,
        /** 기다리기 전에 먼저 한 번 읽는다(값이 바로 바뀌는 차에서는 기다리지 않고 끝난다) */
        readFirst: Boolean = false,
        same: (T, T) -> Boolean = { a, b -> a == b },
    ): Seen<T> {
        var waited = 0L
        var last: T? = null
        var error: String? = null
        suspend fun read(): Boolean = when (val r = get()) {
            is Outcome.Ok -> { last = r.value; error = null; same(r.value, expected) }
            is Outcome.Fail -> { error = r.error; false }
        }
        if (readFirst && read()) return Seen(last, waited, true)
        while (waited < timeoutMs) {
            val step = minOf(stepMs.coerceAtLeast(1L), timeoutMs - waited)
            wait(step); waited += step
            if (read()) return Seen(last, waited, true)
        }
        return Seen(last, waited, false, error)
    }

    /** "0.5초" 꼴(소수 첫째 자리) */
    fun seconds(ms: Long): String = "%.1f초".format(java.util.Locale.US, ms / 1000.0)
}

/** NaviHelper 경유지 추가/삭제. 응답 콜백이 없어서 호출 성공 여부만 안다 */
interface WaypointControls {
    fun addWaypoint(lat: Double, lng: Double, name: String): Result<Unit>
    fun removeFirstWaypoint(): Result<Unit>
}

/** 실패 문자열 → 계약(ActionResult.detail)의 짧은 코드 */
object FailureCode {
    const val READ_FAILED = "READ_FAILED"
    const val DRIVING_RESTRICTED = "DRIVING_RESTRICTED"
    const val PERMISSION = "PERMISSION"
    const val TIMEOUT = "TIMEOUT"
    const val NO_VEHICLE = "NO_VEHICLE"

    /**
     * 엔진은 이 코드로 "주행 중 제한이면 정차 후로" 같은 분기를 한다.
     * DRIVING_RESTRICTED / PERMISSION / TIMEOUT / NO_VEHICLE / READ_FAILED / 그 외는 원문 앞 40자.
     */
    fun normalize(error: String): String = when {
        error == READ_FAILED -> READ_FAILED
        error.contains("DRIVING_RESTRICTED") -> DRIVING_RESTRICTED
        error.contains("PERMISSION") || error.contains("SecurityException") || error.contains("Permission") -> PERMISSION
        error.contains("응답 없음") || error.contains("TIMEOUT", ignoreCase = true) || error.contains("Timeout") -> TIMEOUT
        error.contains("Vehicle SDK 없음") -> NO_VEHICLE
        else -> error.take(40)
    }
}

/**
 * 증거 띠 한 줄(순수 함수). 주행 화면 아래에 "앱이 실제로 부른 SDK 제어 호출"을 한 줄로 보여 준다.
 * 제어 호출(set*, addWaypoint, removeWaypoint)만 찍는다. 읽기 호출(get*, check*)은 5초마다 도는 조회가 섞여 띠가 의미 없이 바뀌므로 뺀다.
 *  성공: "setHvacRecirculation(false) → ok 0.3s"
 *  실패: "setHvacRecirculation(false) → 실패 DRIVING_RESTRICTED"
 */
object EvidenceLine {
    fun isControlCall(name: String): Boolean =
        name.startsWith("set") || name.startsWith("addWaypoint") || name.startsWith("removeWaypoint")

    /** 제어 호출이 아니면 null. elapsedMs 는 호출부터 응답까지 */
    fun format(name: String, ok: Boolean, error: String? = null, elapsedMs: Long = 0L): String? {
        if (!isControlCall(name)) return null
        return if (ok) "$name → ok ${"%.1f".format(java.util.Locale.US, elapsedMs.coerceAtLeast(0L) / 1000.0)}s"
        else "$name → 실패 ${FailureCode.normalize(error ?: "")}".trimEnd()
    }
}

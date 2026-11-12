package ai.kkaeugi.app.vehicle

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SDK 검증 패널의 뒷단. 버튼 하나 = 시나리오 하나. 결과는 화면 로그와 Logcat(태그 Kkaeugi.Verify)에 같이 남긴다.
 * 목적: Pleos 에뮬레이터에서 기어 D 상태로 공조/창문 제어와 경유지 추가가 "되는지"를 확인하는 것.
 * ERROR_DRIVING_RESTRICTED 가 나오면 주행 중 제어 불가, ERROR_SECURITY_PERMISSION 이면 퍼미션 문제.
 */
class SdkVerifier(
    private val scope: CoroutineScope,
    private val calls: PleosCalls,
    private val nav: NavCalls,
    private val hasVehicle: () -> Boolean,
    private val currentLocation: suspend () -> GeoPoint?,
    private val onLog: (String) -> Unit,
    /** 사용자가 트립시간 단위를 확정했을 때(true=초) */
    private val onTripUnitConfirmed: (Boolean) -> Unit = {},
    /** 설정 "창문 방향 반전". 창문 15% 버튼과 창문 위치 읽기의 퍼센트 해석이 따른다 */
    private val windowInverted: () -> Boolean = { false },
) {
    /** 마지막으로 읽은 raw 와 읽은 시각(단조). 두 번 읽어 증가 속도로 단위를 보여 준다 */
    private var lastTripRaw: Int? = null
    private var lastTripRawAt = 0L

    /** 검증 버튼이 값을 바꾸기 직전에 읽어 둔 원래 값. "검증 원복" 이 이 값으로 되돌린다(처음 바꾸기 전 값만 유지) */
    private var snapRecirc: Boolean? = null
    private var snapFan: Int? = null
    private var snapTemp: Float? = null
    private var snapWindow: Int? = null
    private var testWaypointAdded = false

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private fun log(s: String) {
        val line = "${fmt.format(Date())} $s"
        Log.i(TAG, s)
        onLog(line)
    }

    private fun <T> Outcome<T>.text() = when (this) { is Outcome.Ok -> "성공 → $value"; is Outcome.Fail -> "실패 $error" }

    /** 탐색된 HVAC 영역: "0x75 (capability)". 제어 검증 버튼은 전부 이 영역으로 부른다(PleosCalls) */
    private suspend fun areaText(prop: HvacProp): String =
        calls.resolveHvacAreas()[prop]?.let { "영역 ${HvacAreaRule.describe(it)}" } ?: "영역 모름"

    private fun run(title: String, block: suspend () -> Unit) {
        if (!hasVehicle()) { log("[$title] Vehicle SDK 연결 안 됨(연결 판정 실패 또는 Pleos 가 아닌 기기). 모의 모드."); return }
        scope.launch(Dispatchers.Main.immediate) {
            log("[$title] 시작 (기어: ${calls.getGear().let { (it as? Outcome.Ok)?.value?.name ?: "?" }})")
            runCatching { block() }.onFailure { log("[$title] 예외: ${PleosCalls.describe(it)}") }
        }
    }

    /** 1. 내기순환 읽기 */
    fun readRecirculation() = run("내기순환 읽기") {
        log("getHvacRecirculationState(${areaText(HvacProp.RECIRC)}) ${calls.getRecirculation().text()}")
    }

    /** 2. 외기로 전환 후 다시 읽어 확인 */
    fun switchToFreshAir() = run("외기로 전환") {
        val before = calls.getRecirculation()
        log("getHvacRecirculationState(${areaText(HvacProp.RECIRC)}) ${before.text()}")
        if (before !is Outcome.Ok) { log("현재값을 못 읽어 바꾸지 않아요(원복 기준 없음)"); return@run }
        if (snapRecirc == null) snapRecirc = before.value
        log("setHvacRecirculation(false) ${calls.setRecirculation(false).text()}")
        kotlinx.coroutines.delay(500)
        log("다시 읽기 ${calls.getRecirculation().text()} (false 면 외기)")
    }

    /** 3. 풍량 +1 */
    fun fanPlusOne() = run("풍량 +1") {
        val cur = calls.getFanSpeed()
        log("getHvacFanSpeed(${areaText(HvacProp.FAN)}) ${cur.text()}")
        if (cur is Outcome.Ok) {
            val bounds = calls.fanSpeedBounds()
            log("checkHvacFanSpeedCapabilities ${bounds.text()}")
            if (snapFan == null) snapFan = cur.value
            log("setHvacFanSpeed(${cur.value + 1}) ${calls.setFanSpeed(cur.value + 1).text()}")
        }
    }

    /** 4. 온도 -2 */
    fun tempMinusTwo() = run("온도 -2") {
        val cur = calls.getTemperature()
        log("getHvacTemperature(${areaText(HvacProp.TEMP)}, CELSIUS) ${cur.text()}")
        if (cur is Outcome.Fail && HvacAreaRule.isUnavailable(cur.error)) log("지금은 읽을 수 없어요. 공조 전원이 꺼져 있으면 이렇게 답해요. 전원을 켠 뒤 다시 눌러 주세요")
        if (cur is Outcome.Ok) {
            if (snapTemp == null) snapTemp = cur.value
            log("setHvacTemperature(${cur.value - 2f}) ${calls.setTemperature(cur.value - 2f).text()}")
        }
    }

    /** 5. 창문 15%. 방향 반전 설정이 켜져 있으면 max 쪽을 닫힘으로 보고 계산한다 */
    fun window15() = run("창문 15%") {
        val bounds = calls.windowBounds()
        log("checkWindowPositionCapabilities ${bounds.text()}")
        val (min, max) = calls.windowRange()
        val inverted = windowInverted()
        if (!WindowLimit.opens(15, min, max)) { log("범위 $min~$max 는 한 칸이 15% 보다 커서 열지 않아요"); return@run }
        // 15% 를 넘지 않게 닫힘 쪽으로 내린다(범위 0~10 이면 1)
        val target = WindowLimit.positionAtMost(15, min, max, inverted)
        val cur = calls.getWindow()
        log("getCurrentWindowPosition ${cur.text()}")
        if (cur !is Outcome.Ok) { log("현재값을 못 읽어 열지 않아요(원복 기준 없음)"); return@run }
        if (snapWindow == null) snapWindow = cur.value
        log("setWindowPosition(ROW_1_LEFT, $target) [범위 $min~$max 의 15% 이하${if (inverted) ", 방향 반전" else ""}] ${calls.setWindow(target).text()}")
    }

    /**
     * 창문 위치 읽기(raw/min/max). 창문을 닫아 둔 채 눌러 raw 가 min 쪽인지 max 쪽인지 본다.
     * 닫힌 창문의 raw 가 max 에 가까우면 이 차는 방향이 반대다. 설정의 "창문 방향 반전"을 켠다.
     */
    fun readWindowPositions() = run("창문 위치 읽기") {
        val bounds = (calls.windowBounds() as? Outcome.Ok)?.value
        if (bounds == null) log("checkWindowPositionCapabilities 실패. 범위는 0~100 으로 가정")
        val inverted = windowInverted()
        for (w in PleosCalls.SIDE_WINDOWS) {
            val b = bounds?.entries?.firstOrNull { it.key == ai.pleos.playground.vehicle.api.model.area.WindowArea(w) }?.value
            val min = b?.minValue ?: 0
            val max = b?.maxValue ?: 100
            when (val r = calls.getWindowAt(w)) {
                is Outcome.Ok -> log("${w.name} raw=${r.value} min=$min max=$max → 열림 ${WindowScale.percentOf(r.value, min, max, inverted)}%${if (inverted) " (방향 반전)" else ""}")
                is Outcome.Fail -> log("${w.name} 읽기 실패 ${r.error} (min=$min max=$max)")
            }
        }
        log("닫힌 창문이 열림 100% 근처로 나오면 설정에서 \"창문 방향 반전\"을 바꾸세요")
    }

    /** 6. 경유지 추가 (현재 위치 +0.01도). NAVI_ROUTE_SEARCH 퍼미션 확인용 */
    fun addTestWaypoint() {
        scope.launch(Dispatchers.Main.immediate) {
            val base = currentLocation() ?: DemoLocation.POINT
            if (base === DemoLocation.POINT) log("[경유지 추가] 실제 위치 없음 또는 한국 밖. 모의 위치(경부선 칠곡 부근) 사용")
            val lat = base.lat + 0.01; val lng = base.lng + 0.01
            val r = nav.addWaypoint(lat, lng, "테스트 졸음쉼터")
            if (r.isSuccess) testWaypointAdded = true
            log("[경유지 추가] addWaypoint($lat, $lng, FIRST) ${if (r.isSuccess) "호출 성공 (결과는 onWaypointChanged/onError 로 옴)" else "실패 ${r.exceptionOrNull()?.message}"}")
        }
    }

    /** 7. 경유지 삭제 */
    fun removeTestWaypoint() {
        val r = nav.removeFirstWaypoint()
        testWaypointAdded = false
        log("[경유지 삭제] removeWaypoint(FIRST) ${if (r.isSuccess) "호출 성공" else "실패 ${r.exceptionOrNull()?.message}"}")
    }

    /**
     * 탑승 인원 읽기(CAR_SEATS): 지원 좌석 목록을 먼저 읽고, 좌석마다 점유 값, 없으면 안전벨트 체결을 읽는다.
     * 앱이 5초마다 세는 것과 같은 규칙으로 한 번 센 결과도 같이 보여 준다
     */
    fun readOccupants() = run("탑승 인원 읽기") {
        val (occSeats, beltSeats) = calls.seatCapabilities(refresh = true)
        log("checkSeatOccupancyCapabilities ${occSeats?.joinToString { it.name } ?: "실패(기본 좌석을 전부 물어봄)"}")
        log("checkSeatBeltBuckledCapabilities ${beltSeats?.joinToString { it.name } ?: "실패(기본 좌석을 전부 물어봄)"}")
        for (seat in PleosCalls.OCCUPANT_SEATS) {
            val askOcc = occSeats == null || seat in occSeats
            val askBelt = beltSeats == null || seat in beltSeats
            if (!askOcc && !askBelt) { log("${seat.name} 지원 안 함"); continue }
            val occ = if (askOcc) calls.getSeatOccupancy(seat) else null
            if (occ != null) log("getCurrentSeatOccupancy(${seat.name}) ${occ.text()}")
            if (occ !is Outcome.Ok && askBelt) log("getCurrentSeatBeltBuckledState(${seat.name}) ${calls.getSeatBelt(seat).text()}")
        }
        val driving = (calls.getGear() as? Outcome.Ok)?.value?.let { it.name != "GEAR_PARK" && it.name != "GEAR_NEUTRAL" && it.name != "GEAR_UNKNOWN" } ?: false
        val r = calls.readOccupants(driving)
        log("탑승 인원 ${r.count}명 (${OccupantCounter.label(r.source)}: ${r.detail})${if (driving) " · 주행 중이라 운전석은 1" else ""}")
    }

    /** 핸들 각도 읽기(CAR_DRIVE). 단위는 도로 보고 있다. 범위 읽기로 확인한다 */
    fun readSteeringAngle() = run("핸들 각도 읽기") {
        log("getCurrentSteeringWheelAngle ${calls.getSteeringAngle().text()}")
    }

    /**
     * 핸들 각도 범위 읽기. min/max 가 ±수백이면 도(핸들 각도), ±수십이면 바퀴 조향각, ±10 안쪽이면 라디안일 수 있다.
     * 엔진의 핸들 신호 기준(1도 안쪽 무조작, 8도 급보정)은 핸들 각도(도)를 전제로 한다
     */
    fun readSteeringRange() = run("핸들 각도 범위 읽기") {
        when (val b = calls.steeringAngleBounds()) {
            is Outcome.Ok -> log("checkSteeringWheelAngleCapabilities min=${b.value.minValue} max=${b.value.maxValue}")
            is Outcome.Fail -> log("checkSteeringWheelAngleCapabilities 실패 ${b.error}")
        }
        log("getCurrentSteeringWheelAngle ${calls.getSteeringAngle().text()} (핸들을 오른쪽으로 조금 돌린 채 다시 눌러 부호도 확인)")
    }

    /**
     * 검증 원복: 위 버튼들이 바꾼 내기순환·풍량·온도·창문을 처음 값으로 되돌리고, 테스트 경유지를 넣었으면 지운다.
     * 검증하다가 창문이 열린 채, 온도가 내려간 채로 남지 않게 한다.
     */
    fun revertAll() = run("검증 원복") {
        var any = false
        snapRecirc?.let { any = true; log("setHvacRecirculation($it) ${calls.setRecirculation(it).text()}"); snapRecirc = null }
        snapFan?.let { any = true; log("setHvacFanSpeed($it) ${calls.setFanSpeed(it).text()}"); snapFan = null }
        snapTemp?.let { any = true; log("setHvacTemperature($it) ${calls.setTemperature(it).text()}"); snapTemp = null }
        snapWindow?.let { any = true; log("setWindowPosition($it) ${calls.setWindow(it).text()}"); snapWindow = null }
        if (testWaypointAdded) {
            any = true
            val r = nav.removeFirstWaypoint()
            log("removeWaypoint(FIRST) ${if (r.isSuccess) "호출 성공" else "실패 ${r.exceptionOrNull()?.message}"}")
            testWaypointAdded = false
        }
        if (!any) log("되돌릴 것이 없어요(검증 버튼으로 바꾼 값 없음)")
    }

    /**
     * 트립 시간 raw 읽기(단위 판정용). 두 번 이상 누르면 두 읽기 사이 증가량/경과초로 단위를 추정해 보여 준다.
     * 에뮬레이터 버추얼 콘솔에서 주행 중 상태로 두고 10초쯤 간격으로 두 번 누르면 된다.
     */
    fun readTripTime() = run("트립시간 raw") {
        val r = calls.getTripTimeRaw()
        log("getTripCurrentDrivingTime raw ${r.text()}")
        if (r is Outcome.Ok) {
            val now = android.os.SystemClock.elapsedRealtime()
            val prev = lastTripRaw
            if (prev != null && now > lastTripRawAt) {
                val sec = (now - lastTripRawAt) / 1000.0
                val ratio = (r.value - prev) / sec
                log("  이전 읽기 대비 +${r.value - prev} / ${"%.0f".format(sec)}초 → 증가율 ${"%.2f".format(ratio)}/s (1 에 가까우면 초, 0.017 에 가까우면 분). 아래 '단위 확정' 버튼으로 고정하세요")
            } else log("  10초쯤 뒤 한 번 더 누르면 증가 속도로 단위를 추정해요")
            lastTripRaw = r.value; lastTripRawAt = now
        }
    }

    /** 사용자가 단위를 확정 */
    fun confirmTripUnit(seconds: Boolean) {
        onTripUnitConfirmed(seconds)
        log("[트립시간] 단위를 ${if (seconds) "초" else "분"}로 확정했어요. 이후 TripTimeUpdate 가 발행됩니다")
    }

    companion object { const val TAG = "Kkaeugi.Verify" }
}

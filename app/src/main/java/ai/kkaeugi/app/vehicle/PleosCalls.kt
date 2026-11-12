package ai.kkaeugi.app.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.ErrorCode
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos SDK 의 콜백 API 를 suspend 로 감싼 얇은 층. VehicleActuator 와 SdkVerifier 가 같이 쓴다.
 * 모든 호출은 CALL_TIMEOUT_MS 안에 답이 없으면 실패로 본다(서비스 바인딩이 안 된 상태에서 콜백이 영영 안 오는 경우 대비).
 */
class PleosCalls(private val vehicle: () -> Vehicle?) : VehicleControls {

    val driverWindow = WindowArea(WindowValue.ROW_1_LEFT)

    /**
     * 증거 모드용: 제어 호출(set*)의 결과를 "setHvacRecirculation(false) → ok 0.3s" / "… → 실패 DRIVING_RESTRICTED" 한 줄로 알린다.
     * 읽기 호출(get*, check*)은 알리지 않는다(EvidenceLine).
     */
    @Volatile var onCall: ((String) -> Unit)? = null

    /** SDK 콜백은 값이 nullable 로 온다. null 은 실패로 본다. */
    private suspend fun <T : Any> call(name: String, timeoutMs: Long = CALL_TIMEOUT_MS, block: (ok: (T?) -> Unit, fail: (Exception) -> Unit) -> Unit): Outcome<T> {
        val v = vehicle() ?: return Outcome.Fail("Vehicle SDK 없음")
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val r = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Outcome<T>> { cont ->
                try {
                    block({ value -> if (cont.isActive) cont.resume(if (value != null) Outcome.Ok(value) else Outcome.Fail("null 응답")) },
                        { e -> if (cont.isActive) cont.resume(Outcome.Fail(describe(e))) })
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resume(Outcome.Fail(describe(e)))
                }
            }
        } ?: Outcome.Fail("응답 없음(${timeoutMs}ms)")
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
        when (r) {
            is Outcome.Ok -> { Log.i(TAG, "$name → ${r.value}"); EvidenceLine.format(name, ok = true, elapsedMs = elapsedMs)?.let { onCall?.invoke(it) } }
            is Outcome.Fail -> { Log.w(TAG, "$name 실패: ${r.error}"); EvidenceLine.format(name, ok = false, error = r.error)?.let { onCall?.invoke(it) } }
        }
        return r
    }

    // ---- HVAC 영역 ----
    // 영역을 하드코딩하지 않는다. 속성마다 capability 또는 후보 읽기로 찾아 읽기, 쓰기, 리스너 등록에 같은 영역을 쓴다(HvacAreaRule).
    private val areaLock = Mutex()
    @Volatile private var areas: Map<HvacProp, HvacArea>? = null
    private var areasResolvedAt = 0L
    /** 탐색이 끝났으면 속성별 결과. 아직이면 null */
    val hvacAreas: Map<HvacProp, HvacArea>? get() = areas
    /** 탐색이 끝날 때마다(처음, 자가 진단의 다시 탐색). 홈의 "내외기 상태를 읽을 수 없어요" 표시에 쓴다 */
    @Volatile var onHvacAreas: ((Map<HvacProp, HvacArea>) -> Unit)? = null

    /** 마스크 → SeatArea. 좌석 순서는 SDK 가 capability 에서 돌려주는 순서(SeatValue.values())와 같게 한다 */
    fun seatArea(mask: Int): SeatArea = SeatArea(*SeatValue.values().filter { mask and it.id != 0 }.toTypedArray())
    fun maskOf(area: SeatArea): Int = area.values.fold(0) { acc, v -> acc or v.id }

    private suspend fun capabilityOf(prop: HvacProp): Outcome<List<Int>> = when (prop) {
        HvacProp.RECIRC -> call<List<SeatArea>>("checkHvacRecirculationCapabilities") { ok, fail -> vehicle()!!.getHVAC().checkHvacRecirculationCapabilities(ok, fail) }.areasOf { it }
        HvacProp.AUTO_RECIRC -> call<List<SeatArea>>("checkHvacAutoRecirculationCapabilities") { ok, fail -> vehicle()!!.getHVAC().checkHvacAutoRecirculationCapabilities(ok, fail) }.areasOf { it }
        HvacProp.POWER -> call<List<SeatArea>>("checkHvacPowerCapabilities") { ok, fail -> vehicle()!!.getHVAC().checkHvacPowerCapabilities(ok, fail) }.areasOf { it }
        HvacProp.FAN -> fanSpeedBounds().areasOf { it.keys }
        HvacProp.TEMP -> temperatureBounds().areasOf { it.keys }
    }
    private fun <T> Outcome<T>.areasOf(keys: (T) -> Collection<SeatArea>): Outcome<List<Int>> = when (this) {
        is Outcome.Ok -> Outcome.Ok(keys(value).map { maskOf(it) })
        is Outcome.Fail -> this
    }

    /** 영역 하나로 한 번 읽어 본다(탐색, 자가 진단용. 차 값을 바꾸지 않는다) */
    private suspend fun probe(prop: HvacProp, mask: Int): ProbeRead {
        val area = seatArea(mask)
        val at = HvacAreaRule.hex(mask)
        val r: Outcome<String> = when (prop) {
            HvacProp.RECIRC -> call<Boolean>("getHvacRecirculationState($at)", PROBE_TIMEOUT_MS) { ok, fail -> vehicle()!!.getHVAC().getHvacRecirculationState(area, ok, fail) }.textOf { if (it) "켜짐(내기)" else "꺼짐(외기)" }
            HvacProp.AUTO_RECIRC -> call<Boolean>("getHvacAutoRecirculationState($at)", PROBE_TIMEOUT_MS) { ok, fail -> vehicle()!!.getHVAC().getHvacAutoRecirculationState(area, ok, fail) }.textOf { if (it) "켜짐" else "꺼짐" }
            HvacProp.POWER -> call<Boolean>("getHvacPowerState($at)", PROBE_TIMEOUT_MS) { ok, fail -> vehicle()!!.getHVAC().getHvacPowerState(area, ok, fail) }.textOf { if (it) "켜짐" else "꺼짐" }
            HvacProp.FAN -> call<Int>("getHvacFanSpeed($at)", PROBE_TIMEOUT_MS) { ok, fail -> vehicle()!!.getHVAC().getHvacFanSpeed(area, ok, fail) }.textOf { it.toString() }
            HvacProp.TEMP -> call<Float>("getHvacTemperature($at, C)", PROBE_TIMEOUT_MS) { ok, fail -> vehicle()!!.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail) }.textOf { "${it}C" }
        }
        return when (r) {
            is Outcome.Ok -> ProbeRead(mask, ProbeKind.VALUE, r.value)
            is Outcome.Fail -> ProbeRead(mask, HvacAreaRule.classify(r.error), r.error)
        }
    }
    private fun <T> Outcome<T>.textOf(f: (T) -> String): Outcome<String> = when (this) {
        is Outcome.Ok -> Outcome.Ok(f(value))
        is Outcome.Fail -> this
    }

    /**
     * 속성별 영역을 정한다(처음 한 번, refresh 면 다시).
     * (a) capability 가 준 영역 중 운전석을 포함하는 것, 없으면 첫 영역. 한 번 읽어 "영역 미지원"이면 버린다.
     * (b) 못 정했으면 후보(HvacAreaRule.CANDIDATES)를 차례로 읽어 값이 오는 첫 후보. "지금은 읽을 수 없음"으로 답한 후보는 보류로 채택.
     * probeAll 이면 후보를 전부 읽는다(자가 진단의 "HVAC 영역 탐색" 섹션). Vehicle 이 없으면 빈 맵(기억하지 않는다).
     */
    suspend fun resolveHvacAreas(refresh: Boolean = false, probeAll: Boolean = false): Map<HvacProp, HvacArea> = areaLock.withLock {
        areas?.let { if (!refresh) return@withLock it }
        if (vehicle() == null) return@withLock emptyMap()
        val out = linkedMapOf<HvacProp, HvacArea>()
        for (prop in HvacProp.values()) {
            val cap = capabilityOf(prop)
            val capMasks = (cap as? Outcome.Ok)?.value
            val capPick = HvacAreaRule.pickFromCapability(capMasks)
            val capRead = capPick?.let { probe(prop, it) }
            val probes = mutableListOf<ProbeRead>()
            if (probeAll || capPick == null || capRead?.kind == ProbeKind.UNSUPPORTED) {
                for (m in HvacAreaRule.CANDIDATES) {
                    val r = if (m == capPick && capRead != null) capRead else probe(prop, m)
                    probes += r
                    if (!probeAll && r.kind == ProbeKind.VALUE) break
                }
            }
            val a = HvacAreaRule.decide(prop, capMasks, (cap as? Outcome.Fail)?.error, capRead, probes)
            out[prop] = a
            Log.i(AREA_TAG, "${prop.label} 영역: ${HvacAreaRule.describe(a)}")
            Log.i(AREA_TAG, "  capability: ${HvacAreaRule.capabilityText(a)}")
            for (r in listOfNotNull(capRead.takeIf { probes.isEmpty() }) + probes) Log.i(AREA_TAG, "  읽기 ${HvacAreaRule.hex(r.mask)}: ${r.kind} ${r.text}")
        }
        areas = out
        areasResolvedAt = android.os.SystemClock.elapsedRealtime()
        onHvacAreas?.invoke(out)
        out
    }

    /**
     * 속성의 영역. 아직 안 정했으면 지금 정한다. 이 차에 없으면 null.
     * 응답 없음 같은 실패 때문에 못 정한 속성은 AREA_RETRY_MS 뒤에 한 번 더 찾아본다(서비스 바인딩 직후일 수 있다).
     */
    suspend fun hvacSeatArea(prop: HvacProp): SeatArea? {
        var a = resolveHvacAreas()[prop] ?: return null
        if (a.mask == null && a.probes.any { it.kind == ProbeKind.ERROR } && android.os.SystemClock.elapsedRealtime() - areasResolvedAt > AREA_RETRY_MS) {
            a = resolveHvacAreas(refresh = true)[prop] ?: return null
        }
        return a.mask?.let { seatArea(it) }
    }

    private fun noArea(prop: HvacProp): Outcome.Fail = Outcome.Fail("$AREA_UNSUPPORTED: 이 차에는 ${prop.label} 영역이 없음")

    // ---- HVAC ----
    override suspend fun getRecirculation(): Outcome<Boolean> {
        val area = hvacSeatArea(HvacProp.RECIRC) ?: return noArea(HvacProp.RECIRC)
        return call("getHvacRecirculationState") { ok, fail -> vehicle()!!.getHVAC().getHvacRecirculationState(area, ok, fail) }
    }
    override suspend fun setRecirculation(on: Boolean): Outcome<Boolean> {
        val area = hvacSeatArea(HvacProp.RECIRC) ?: return noArea(HvacProp.RECIRC)
        return call("setHvacRecirculation($on)") { ok, fail -> vehicle()!!.getHVAC().setHvacRecirculation(area, on, { _, v -> ok(v) }, fail) }
    }
    override suspend fun getFanSpeed(): Outcome<Int> {
        val area = hvacSeatArea(HvacProp.FAN) ?: return noArea(HvacProp.FAN)
        return call("getHvacFanSpeed") { ok, fail -> vehicle()!!.getHVAC().getHvacFanSpeed(area, ok, fail) }
    }
    override suspend fun setFanSpeed(v: Int): Outcome<Int> {
        val area = hvacSeatArea(HvacProp.FAN) ?: return noArea(HvacProp.FAN)
        return call("setHvacFanSpeed($v)") { ok, fail -> vehicle()!!.getHVAC().setHvacFanSpeed(area, v, { _, r -> ok(r) }, fail) }
    }
    suspend fun fanSpeedBounds() = call<Map<SeatArea, AreaBounds<Int>>>("checkHvacFanSpeedCapabilities") { ok, fail -> vehicle()!!.getHVAC().checkHvacFanSpeedCapabilities(ok, fail) }
    override suspend fun getTemperature(): Outcome<Float> {
        val area = hvacSeatArea(HvacProp.TEMP) ?: return noArea(HvacProp.TEMP)
        return call("getHvacTemperature(C)") { ok, fail -> vehicle()!!.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail) }
    }
    override suspend fun setTemperature(c: Float): Outcome<Float> {
        val area = hvacSeatArea(HvacProp.TEMP) ?: return noArea(HvacProp.TEMP)
        return call("setHvacTemperature($c C)") { ok, fail -> vehicle()!!.getHVAC().setHvacTemperature(area, c, TemperatureUnit.CELSIUS, { _, r -> ok(r) }, fail) }
    }
    suspend fun temperatureBounds() = call<Map<SeatArea, AreaBounds<Float>>>("checkHvacTemperatureCapabilities") { ok, fail -> vehicle()!!.getHVAC().checkHvacTemperatureCapabilities(ok, fail) }
    /** 차의 자동 내외기 제어(공기질 연동 등)가 켜져 있는가. 일반 내기순환 상태와는 다른 값이다 */
    suspend fun getAutoRecirculation(): Outcome<Boolean> {
        val area = hvacSeatArea(HvacProp.AUTO_RECIRC) ?: return noArea(HvacProp.AUTO_RECIRC)
        return call("getHvacAutoRecirculationState") { ok, fail -> vehicle()!!.getHVAC().getHvacAutoRecirculationState(area, ok, fail) }
    }
    /** 공조 전원. 꺼져 있으면 풍량 단계가 남아 있어도 바람이 안 나온다 */
    override suspend fun getHvacPower(): Outcome<Boolean> {
        val area = hvacSeatArea(HvacProp.POWER) ?: return noArea(HvacProp.POWER)
        return call("getHvacPowerState") { ok, fail -> vehicle()!!.getHVAC().getHvacPowerState(area, ok, fail) }
    }
    /**
     * 공조 전원 켜고 끄기(Vehicle 2.3.2 HVAC.setHvacPower, javap 로 확인). 앱이 전원을 켠 경우와 명령 때문에 차가 전원을 켠 경우에
     * 원복에서 처음 상태로 되돌리는 데만 쓴다
     */
    override suspend fun setHvacPower(on: Boolean): Outcome<Boolean> {
        val area = hvacSeatArea(HvacProp.POWER) ?: return noArea(HvacProp.POWER)
        return call("setHvacPower($on)") { ok, fail -> vehicle()!!.getHVAC().setHvacPower(area, on, { _, v -> ok(v) }, fail) }
    }

    /** 범위 맵에서 탐색된 영역의 값을 찾는다. 없으면 운전석을 포함하는 영역, 그것도 없으면 첫 값 */
    private fun <T> boundOf(map: Map<SeatArea, AreaBounds<T>>, prop: HvacProp): AreaBounds<T>? {
        val mask = areas?.get(prop)?.mask
        return map.entries.firstOrNull { mask != null && maskOf(it.key) == mask }?.value
            ?: map.entries.firstOrNull { maskOf(it.key) and HvacAreaRule.DRIVER != 0 }?.value
            ?: map.values.firstOrNull()
    }

    // ---- Window ----
    override suspend fun getWindow() = call<Int>("getCurrentWindowPosition") { ok, fail -> vehicle()!!.getWindow().getCurrentWindowPosition(driverWindow, ok, fail) }
    override suspend fun setWindow(pos: Int) = call<Int>("setWindowPosition($pos)") { ok, fail -> vehicle()!!.getWindow().setWindowPosition(driverWindow, pos, { _, r -> ok(r) }, fail) }
    suspend fun windowBounds() = call<Map<WindowArea, AreaBounds<Int>>>("checkWindowPositionCapabilities") { ok, fail -> vehicle()!!.getWindow().checkWindowPositionCapabilities(ok, fail) }

    /** 운전석 창문 범위. 못 읽으면 0~100 으로 가정 */
    override suspend fun windowRange(): Pair<Int, Int> = when (val b = windowBounds()) {
        is Outcome.Ok -> {
            val bound = b.value.entries.firstOrNull { it.key == driverWindow }?.value ?: b.value.values.firstOrNull()
            if (bound != null) bound.minValue to bound.maxValue else 0 to 100
        }
        is Outcome.Fail -> 0 to 100
    }

    /** 풍량 범위(탐색된 영역의 값, 없으면 운전석을 포함하는 영역, 첫 값 순). 못 읽으면 0~8 */
    override suspend fun fanRange(): Pair<Int, Int> = when (val b = fanSpeedBounds()) {
        is Outcome.Ok -> boundOf(b.value, HvacProp.FAN)?.let { it.minValue to it.maxValue } ?: (0 to 8)
        is Outcome.Fail -> 0 to 8
    }

    /**
     * 온도 범위(섭씨). 하한이 40 을 넘으면 화씨 범위가 온 것으로 보고 버린다(화씨 하한으로 클램프하면 최대 난방이 된다).
     * 못 읽거나 이상하면 16~30.
     */
    override suspend fun temperatureRangeC(): Pair<Float, Float> {
        val b = temperatureBounds() as? Outcome.Ok ?: return 16f to 30f
        val bound = boundOf(b.value, HvacProp.TEMP) ?: return 16f to 30f
        val min = bound.minValue; val max = bound.maxValue
        if (min > 40f || max > 45f || min >= max) { Log.w(TAG, "온도 bounds $min~$max 는 섭씨가 아닌 듯, 16~30 사용"); return 16f to 30f }
        return min to max
    }

    /** 자가 진단용: 탐색된 영역의 풍량 최대, 온도 범위 원문 */
    suspend fun fanMaxText(): String = (fanSpeedBounds() as? Outcome.Ok)?.value?.let { boundOf(it, HvacProp.FAN)?.maxValue?.toString() } ?: "못 읽음"
    suspend fun temperatureRangeText(): String = (temperatureBounds() as? Outcome.Ok)?.value?.let { m -> boundOf(m, HvacProp.TEMP)?.let { "${it.minValue}~${it.maxValue}" } } ?: "못 읽음"

    // ---- 숨 모델 입력: 좌석 점유, 안전벨트, 핸들 각도, 창문(네 곳) ----
    suspend fun getSeatOccupancy(seat: SeatValue) = call<Boolean>("getCurrentSeatOccupancy(${seat.name})") { ok, fail -> vehicle()!!.getSeat().getCurrentSeatOccupancy(SeatArea(seat), ok, fail) }
    suspend fun getSeatBelt(seat: SeatValue) = call<Boolean>("getCurrentSeatBeltBuckledState(${seat.name})") { ok, fail -> vehicle()!!.getSeat().getCurrentSeatBeltBuckledState(SeatArea(seat), ok, fail) }
    suspend fun getSteeringAngle() = call<Float>("getCurrentSteeringWheelAngle") { ok, fail -> vehicle()!!.getSteeringWheel().getCurrentSteeringWheelAngle(ok, fail) }
    suspend fun getWindowAt(w: WindowValue) = call<Int>("getCurrentWindowPosition(${w.name})") { ok, fail -> vehicle()!!.getWindow().getCurrentWindowPosition(WindowArea(w), ok, fail) }

    /** 핸들 각도 범위(min, max). 단위(도)와 부호 방향을 확인하는 데 쓴다 */
    suspend fun steeringAngleBounds() = call<AreaBounds<Float>>("checkSteeringWheelAngleCapabilities") { ok, fail -> vehicle()!!.getSteeringWheel().checkSteeringWheelAngleCapabilities({ _, b -> ok(b) }, fail) }

    /** 좌석 점유 값을 주는 좌석 목록 */
    suspend fun seatOccupancySeats() = call<List<SeatArea>>("checkSeatOccupancyCapabilities") { ok, fail -> vehicle()!!.getSeat().checkSeatOccupancyCapabilities(ok, fail) }
    /** 안전벨트 체결 값을 주는 좌석 목록 */
    suspend fun seatBeltSeats() = call<List<SeatArea>>("checkSeatBeltBuckledCapabilities") { ok, fail -> vehicle()!!.getSeat().checkSeatBeltBuckledCapabilities(ok, fail) }

    /** 지원 좌석(점유, 벨트). 한 번 읽어 기억한다. 못 읽은 쪽은 null(기본 좌석을 전부 물어본다) */
    private var occupancySupported: Set<SeatValue>? = null
    private var beltSupported: Set<SeatValue>? = null
    private var seatCapabilitiesRead = false

    /** 지원 좌석 목록을 읽는다(처음 한 번, refresh 면 다시). 반환: (점유 지원 좌석, 벨트 지원 좌석). null 은 못 읽음 */
    suspend fun seatCapabilities(refresh: Boolean = false): Pair<Set<SeatValue>?, Set<SeatValue>?> {
        if (!seatCapabilitiesRead || refresh) {
            occupancySupported = (seatOccupancySeats() as? Outcome.Ok)?.value?.flatMap { it.values.toList() }?.toSet()
            beltSupported = (seatBeltSeats() as? Outcome.Ok)?.value?.flatMap { it.values.toList() }?.toSet()
            // 둘 다 못 읽었으면 다음 번에 다시 물어본다(서비스 바인딩 직후일 수 있다)
            seatCapabilitiesRead = occupancySupported != null || beltSupported != null
        }
        return occupancySupported to beltSupported
    }

    /**
     * 좌석을 한 번 훑어 탑승 인원을 센다. 지원 좌석만 물어본다(checkSeatOccupancyCapabilities / checkSeatBeltBuckledCapabilities).
     * 좌석마다 점유 값(getCurrentSeatOccupancy)을 먼저 읽고, 값이 없으면 그 자리의 안전벨트 체결(getCurrentSeatBeltBuckledState)을 읽는다.
     * driving 이면 운전석은 값과 무관하게 1 로 센다. 어느 자리도 답하지 않으면 OccupantCounter 가 1 을 돌려준다.
     */
    suspend fun readOccupants(driving: Boolean = false, seats: List<SeatValue> = OCCUPANT_SEATS): OccupantCounter.Reading {
        val (occSeats, beltSeats) = seatCapabilities()
        val occ = linkedMapOf<String, Boolean?>()
        val belt = linkedMapOf<String, Boolean?>()
        for (seat in seats) {
            val askOcc = occSeats == null || seat in occSeats
            val askBelt = beltSeats == null || seat in beltSeats
            if (!askOcc && !askBelt) continue
            val o = if (askOcc) (getSeatOccupancy(seat) as? Outcome.Ok)?.value else null
            occ[seat.name] = o
            if (o == null && askBelt) belt[seat.name] = (getSeatBelt(seat) as? Outcome.Ok)?.value
        }
        return OccupantCounter.count(occ, belt, driverSeat = SeatValue.ROW_1_LEFT.name, driving = driving)
    }

    // ---- 등화, 와이퍼, 경고등(자가 진단의 읽기 전용 호출) ----
    suspend fun getLowBeam() = call<ai.pleos.playground.vehicle.constant.control.LightState>("getCurrentLowBeamLightState") { ok, fail -> vehicle()!!.getLight().getCurrentLowBeamLightState(ok, fail) }
    suspend fun getWiper() = call<ai.pleos.playground.vehicle.constant.control.WiperState>("getCurrentWiperState") { ok, fail -> vehicle()!!.getWiper().getCurrentWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), ok, fail) }
    suspend fun getTelltales() = call<ai.pleos.playground.vehicle.api.model.telltale.Telltales>("getTelltalesInfo") { ok, fail -> vehicle()!!.getSafety().getTelltalesInfo(ok, fail) }

    // ---- Gear / Odometer (검증 패널 표시용) ----
    suspend fun getGear() = call<ai.pleos.playground.vehicle.constant.control.VehicleGear>("getCurrentGearSelectionState") { ok, fail -> vehicle()!!.getGear().getCurrentGearSelectionState(ok, fail) }
    suspend fun getTripTimeRaw() = call<Int>("getTripCurrentDrivingTime") { ok, fail -> vehicle()!!.getOdometer().getTripCurrentDrivingTime(ok, fail) }

    companion object {
        const val TAG = "Kkaeugi.Pleos"
        const val CALL_TIMEOUT_MS = 4_000L
        /** 영역 탐색의 읽기 한 번. 후보가 여럿이라 짧게 둔다 */
        const val PROBE_TIMEOUT_MS = 1_500L
        const val AREA_RETRY_MS = 30_000L
        /** 영역 탐색 로그 태그. "내기순환 영역: 0x75 (capability)" */
        const val AREA_TAG = "Kkaeugi.Vehicle"
        /** 이 차에 그 속성의 영역이 없을 때의 실패 문자열 앞머리 */
        const val AREA_UNSUPPORTED = "AREA_UNSUPPORTED"
        /** 탑승 인원을 세는 좌석(1열 좌우, 2열 좌·중·우) */
        val OCCUPANT_SEATS = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        /** 열림 정도를 보는 창문 네 곳 */
        val SIDE_WINDOWS = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)

        /**
         * SDK 예외를 사람이 읽을 문자열로. 예외 클래스가 난독화돼 있어(callback.exception.a) ErrorCode 를 돌려주는
         * 인자 없는 메서드를 리플렉션으로 찾는다. ERROR_DRIVING_RESTRICTED / ERROR_SECURITY_PERMISSION 구분이 핵심.
         */
        fun describe(e: Throwable): String {
            val code = runCatching {
                e.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.returnType == ErrorCode::class.java }?.invoke(e) as? ErrorCode
            }.getOrNull()
            val msg = e.message ?: e.javaClass.simpleName
            return if (code != null) "${code.name}(${code.id}): $msg" else msg
        }

        /** 실패 문자열을 계약(ActionResult.detail)의 짧은 코드로. 규칙은 FailureCode.normalize */
        fun normalizeDetail(error: String): String = FailureCode.normalize(error)

        /** 리스너 등록 실패 사유를 퍼미션 거부인지 판정 */
        fun isPermissionError(e: Throwable): Boolean =
            e is SecurityException || describe(e).let { it.contains("PERMISSION") || it.contains("Permission") }
    }
}

/**
 * NaviHelper 경유지 추가/삭제. 응답 콜백이 없어서(void) 호출 성공 여부만 안다. 결과는 onWaypointChanged/onError 로 따로 온다.
 * 증거 띠에는 "addWaypoint(석적 졸음쉼터) → ok 0.0s" 로 찍힌다. 여기서 ok 는 호출이 예외 없이 끝났다는 뜻이다.
 */
class NavCalls(private val navi: () -> NaviHelper?) : WaypointControls {
    /** 증거 모드용: 경유지 호출 결과 한 줄 */
    @Volatile var onCall: ((String) -> Unit)? = null

    private fun report(name: String, startedAt: Long, r: Result<Unit>) {
        val line = EvidenceLine.format(name, ok = r.isSuccess, error = r.exceptionOrNull()?.message, elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt)
        if (line != null) onCall?.invoke(line)
    }

    override fun addWaypoint(lat: Double, lng: Double, name: String): Result<Unit> {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        return runCatching<Unit> {
            val n = navi() ?: error("NaviHelper 없음")
            n.addWaypoint(RequestWaypointInfo(lat, lng, name, WaypointIndex.FIRST, "", "", "", ""))
            Log.i(PleosCalls.TAG, "addWaypoint($name, $lat, $lng) 호출")
        }.also { report("addWaypoint($name)", startedAt, it) }
    }

    override fun removeFirstWaypoint(): Result<Unit> {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        return runCatching<Unit> {
            val n = navi() ?: error("NaviHelper 없음")
            n.removeWaypoint(WaypointIndex.FIRST)
            Log.i(PleosCalls.TAG, "removeWaypoint(FIRST) 호출")
        }.also { report("removeWaypoint(FIRST)", startedAt, it) }
    }
}

/** Pleos FusedLocation 폴백. 내비가 위치를 안 줄 때 한 번 읽는다. 타임아웃이어도 release 한다. */
class FusedLocationCalls(private val context: Context) {
    private val headingHold = HeadingHold()
    private var deniedLogged = false

    /** pleos.car.permission.FUSED_LOCATION 이 허용돼 있는가. 2026-11-11 에뮬레이터 실측에서는 거부였다 */
    fun permissionGranted(): Boolean =
        context.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 선택 경로다. 퍼미션이 없으면 서비스를 부르지 않고 null(로그는 한 번만). 위치는 내비가 주 경로다 */
    suspend fun lastKnown(): GeoPoint? {
        if (!permissionGranted()) {
            if (!deniedLogged) { deniedLogged = true; Log.i(PleosCalls.TAG, "FUSED_LOCATION 퍼미션 없음. FusedLocation 은 건너뛰고 내비 위치를 써요") }
            return null
        }
        val fl = try { FusedLocation(context).also { it.initialize() } } catch (e: Throwable) {
            Log.w(PleosCalls.TAG, "FusedLocation 없음: ${e.message}"); return null
        }
        try {
            return withTimeoutOrNull(3_000L) {
                suspendCancellableCoroutine { cont ->
                    try {
                        fl.getLastKnownLocation(object : FusedLocationListener {
                            override fun onChanged(d: LocationData?) {
                                // heading 이 없거나 0 으로 고정이면 직전 두 위치의 방위로 대신한다
                                if (cont.isActive) cont.resume(d?.let { GeoPoint(it.latitude, it.longitude, headingHold.resolve(it.heading.toDouble(), lat = it.latitude, lng = it.longitude)) })
                            }
                            override fun onError(e: Exception) {
                                Log.w(PleosCalls.TAG, "FusedLocation 실패: ${e.message}")
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    } catch (e: Throwable) {
                        Log.w(PleosCalls.TAG, "FusedLocation 호출 실패: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }.also { if (it == null) Log.w(PleosCalls.TAG, "FusedLocation 타임아웃 또는 위치 없음") }
        } finally {
            runCatching { fl.release() }
        }
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}

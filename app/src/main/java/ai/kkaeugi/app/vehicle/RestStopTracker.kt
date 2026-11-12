package ai.kkaeugi.app.vehicle

import ai.kkaeugi.app.signals.MonoClock
import ai.kkaeugi.app.signals.SignalBus
import ai.kkaeugi.engine.RestStopCandidate
import ai.kkaeugi.engine.RestStopInfo
import ai.kkaeugi.engine.RestStopProximity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 쉼터 관련 두 신호를 만든다. Android 의존 없음(JVM 테스트 대상).
 *
 * 1) RestStopCandidate: 주행 중 CANDIDATE_PERIOD(30초)마다, 또는 위치가 1km 이상 바뀌면 즉시, 현재 노선 전방 같은 방향 최근접 졸음쉼터.
 *    위치는 NaviHelper → FusedLocation → 마지막 LocationUpdate 순으로 받고, 없거나 한국 밖이면 DemoLocation 으로 고정한다.
 * 2) RestStopProximity: AddRestStop 성공 뒤 그 쉼터와의 거리를 추적한다.
 *    주행 중 PROXIMITY_PERIOD(5초)마다 위치를 다시 읽고, LocationUpdate 가 오면 그 즉시 판정한다(GPS 를 옮기고 바로 P 를 넣어도 쉼 모드가 되게).
 *    300m 안 → within=true(한 번). 밖으로 나가면 within=false. 1km 안까지 들어갔다가 다시 멀어져 1.5km 밖 → passed=true + removeWaypoint.
 *    RestEnded 가 오면 엔진 쪽 경유지가 사라지므로 추적도 끝낸다(within=false 는 보내지 않는다. 다음 쉼은 새 AddRestStop 뒤).
 *    모의 모드에서는 위치가 고정이라 데모 콘솔 버튼(mockWithin / mockPassed)으로 발행한다.
 */
class RestStopTracker(
    private val bus: SignalBus,
    private val scope: CoroutineScope,
    private val restStops: RestStopIndex,
    private val locationProvider: suspend () -> GeoPoint?,
    private val removeWaypoint: () -> Result<Unit>,
    private val onLog: (String) -> Unit = {},
    /** 추적 중인 쉼터까지 거리(km). 추적이 없으면 null. 증거 모드 표시용 */
    private val onProximityKm: (Double?) -> Unit = {},
    /** 현재 노선 이름(최근접 쉼터의 노선). 화면 "다음 쉼터" 카드용 */
    private val onRouteChanged: (String?) -> Unit = {},
    private val now: () -> Long = { MonoClock.now() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    @Volatile var lastCandidate: RestStopInfo? = null
        private set
    @Volatile var lastLocation: GeoPoint? = null
        private set
    @Volatile var usingDemoLocation = false
        private set
    @Volatile var currentRoute: String? = null
        private set
    /** 추적 중인 경유지 쉼터 */
    @Volatile var tracking: RestStopInfo? = null
        private set
    /** 추적 중인 쉼터까지 마지막 거리(km) */
    @Volatile var proximityKm: Double? = null
        private set

    private var candidateLoop: Job? = null
    private var proximityLoop: Job? = null
    private var lastComputedAt: GeoPoint? = null
    /** 300m 안에 있다고 마지막으로 알린 상태 */
    var withinNow = false
        private set
    private var entered1km = false
    private var minDistKm = Double.MAX_VALUE
    /** 데모 콘솔 버튼으로 근접을 넣은 뒤에는 실제 거리 판정을 멈춘다(모의 위치가 12km 밖이라 바로 within=false 가 나가는 것을 막는다) */
    private var mockOverride = false

    fun onDrivingChanged(driving: Boolean) {
        candidateLoop?.cancel(); candidateLoop = null
        proximityLoop?.cancel(); proximityLoop = null
        if (!driving) { stopTracking("주행 종료"); return }
        candidateLoop = scope.launch(dispatcher) {
            while (isActive) {
                refresh("주기")
                delay(CANDIDATE_PERIOD_MS)
            }
        }
        proximityLoop = scope.launch(dispatcher) {
            while (isActive) {
                delay(PROXIMITY_PERIOD_MS)
                if (tracking != null) {
                    val real = runCatching { locationProvider() }.getOrNull()
                    val loc = DemoLocation.resolve(real)
                    usingDemoLocation = loc === DemoLocation.POINT
                    lastLocation = loc
                    updateProximity(loc)
                }
            }
        }
    }

    /** LocationUpdate 가 올 때. 1km 이상 움직였으면 후보도 다시 계산, 근접은 항상 즉시 판정 */
    fun onLocation(p: GeoPoint) {
        val loc = DemoLocation.resolve(p)
        usingDemoLocation = loc === DemoLocation.POINT
        lastLocation = loc
        updateProximity(loc)
        val prev = lastComputedAt
        val moved = prev == null || haversineKm(prev.lat, prev.lng, loc.lat, loc.lng) >= 1.0
        if (candidateLoop != null && moved) scope.launch(dispatcher) { refresh("이동 1km") }
    }

    private suspend fun refresh(reason: String) {
        val real = runCatching { locationProvider() }.getOrNull()
        val loc = DemoLocation.resolve(real)
        usingDemoLocation = loc === DemoLocation.POINT
        lastLocation = loc
        lastComputedAt = loc
        computeCandidate(loc, reason)
        updateProximity(loc)
    }

    /** 현재 위치에서 노선 잠금 + 후보 계산 + RestStopCandidate 발행 */
    fun computeCandidate(loc: GeoPoint, reason: String = "직접") {
        val route = restStops.currentRoute(loc)
        if (route != currentRoute) { currentRoute = route; onRouteChanged(route) }
        val pick = restStops.nearestAhead(loc)
        val info = pick?.let { (s, km) -> s.toInfo(km) }
        if (info?.name != lastCandidate?.name || info?.distanceKm?.toInt() != lastCandidate?.distanceKm?.toInt()) {
            onLog("후보($reason): ${route ?: "노선 모름"} / ${info?.let { "${it.routeLabel} ${it.name} ${"%.1f".format(it.distanceKm)}km" } ?: "전방 없음"}${if (usingDemoLocation) " [모의 위치]" else ""}")
        }
        lastCandidate = info
        bus.emit(RestStopCandidate(now(), info))
    }

    fun startTracking(stop: RestStopInfo) {
        tracking = stop
        withinNow = false; entered1km = false; minDistKm = Double.MAX_VALUE; mockOverride = false
        proximityKm = null; onProximityKm(null)
        onLog("쉼터 근접 추적 시작: ${stop.name}")
        // 지금 위치를 알고 있으면 바로 한 번 판정
        lastLocation?.let { updateProximity(it) }
    }

    fun stopTracking(reason: String) {
        if (tracking != null) onLog("쉼터 추적 종료: $reason")
        tracking = null
        withinNow = false
        proximityKm = null; onProximityKm(null)
    }

    /** 쉼이 끝났을 때(RestEnded). 엔진의 경유지는 사라졌으니 추적만 끝낸다. within=false 는 보내지 않는다 */
    fun onRestEnded() = stopTracking("쉼 끝")

    /** 추적 중인 쉼터와의 거리로 within/passed 를 판정한다. 모의 위치면 아무것도 하지 않는다(데모 콘솔 버튼으로 발행) */
    fun updateProximity(loc: GeoPoint) {
        val stop = tracking ?: return
        if (mockOverride || usingDemoLocation || loc === DemoLocation.POINT) return
        val km = haversineKm(loc.lat, loc.lng, stop.lat, stop.lng)
        proximityKm = km; onProximityKm(km)
        if (km < minDistKm) minDistKm = km
        if (km <= WITHIN_KM && !withinNow) {
            withinNow = true
            onLog("쉼터 ${stop.name} 300m 안")
            bus.emit(RestStopProximity(now(), within = true))
        } else if (km > WITHIN_KM + 0.1 && withinNow) {
            withinNow = false
            onLog("쉼터 ${stop.name} 300m 밖으로")
            bus.emit(RestStopProximity(now(), within = false))
        }
        if (km <= ENTER_KM) entered1km = true
        if (entered1km && km >= PASSED_KM && km > minDistKm + 0.3) {
            onLog("쉼터 ${stop.name} 지나침(${"%.1f".format(km)}km), 경유지 삭제")
            bus.emit(RestStopProximity(now(), within = false, passed = true))
            removeWaypoint().onFailure { onLog("removeWaypoint 실패: ${it.message}") }
            stopTracking("지나침")
        }
    }

    // ---- 모의 모드(데모 콘솔) ----

    fun mockWithin() {
        val stop = tracking
        withinNow = true; mockOverride = true
        onLog("데모: 쉼터 ${stop?.name ?: "(추적 없음)"} 300m 안")
        bus.emit(RestStopProximity(now(), within = true))
    }

    fun mockPassed() {
        val stop = tracking
        withinNow = false
        onLog("데모: 쉼터 ${stop?.name ?: "(추적 없음)"} 지나침")
        bus.emit(RestStopProximity(now(), within = false, passed = true))
        removeWaypoint()
        stopTracking("데모 지나침")
    }

    fun stop() { candidateLoop?.cancel(); candidateLoop = null; proximityLoop?.cancel(); proximityLoop = null }

    companion object {
        const val TAG = "Kkaeugi.RestStop"
        /** 후보(전방 최근접 쉼터) 재계산 주기 */
        const val CANDIDATE_PERIOD_MS = 30_000L
        /** 경유지 근접 판정 주기(주행 중, 추적 중일 때만 위치를 읽는다) */
        const val PROXIMITY_PERIOD_MS = 5_000L
        const val WITHIN_KM = 0.3
        const val ENTER_KM = 1.0
        const val PASSED_KM = 1.5
    }
}

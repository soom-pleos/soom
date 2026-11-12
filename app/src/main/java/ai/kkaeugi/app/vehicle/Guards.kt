package ai.kkaeugi.app.vehicle

/**
 * 경유지 삭제 안전장치(순수 로직, JVM 테스트 대상).
 *
 * NaviHelper.removeWaypoint(FIRST) 는 "첫 경유지"를 지운다. 그게 우리가 넣은 졸음쉼터인지 SDK 로는 알 수 없다.
 * 그래서 다음 두 조건이 다 맞을 때만 지운다.
 *  1) 우리가 addWaypoint 를 부른 뒤 CONFIRM_WINDOW_MS 안에 내비의 경유지 변경 이벤트가 왔다(우리 추가가 반영됐다).
 *  2) 그 뒤로 우리가 부르지 않은 경유지 변경 이벤트가 없었다(운전자가 경유지를 직접 바꾸지 않았다).
 * 조건이 안 맞으면 지우지 않고 그대로 둔다. 남은 경유지는 운전자가 내비에서 지운다.
 */
class WaypointGuard(private val now: () -> Long) {
    private var addCalledAt: Long? = null
    private var confirmed = false
    private var foreignChange = false

    /** 우리가 addWaypoint 를 부른 직후 */
    fun onOurAdd() { addCalledAt = now(); confirmed = false; foreignChange = false }

    /** 내비의 onWaypointChanged 가 올 때마다 */
    fun onWaypointEvent() {
        val at = addCalledAt
        when {
            at == null -> {}
            !confirmed && now() - at <= CONFIRM_WINDOW_MS -> confirmed = true
            else -> foreignChange = true
        }
    }

    /** 첫 경유지가 우리 것이라고 볼 수 있는가 */
    val canRemove: Boolean get() = addCalledAt != null && confirmed && !foreignChange

    /** 지웠거나 추적을 끝냈을 때 */
    fun clear() { addCalledAt = null; confirmed = false; foreignChange = false }

    companion object { const val CONFIRM_WINDOW_MS = 10_000L }
}

/**
 * heading 유지(순수 로직). 내비·FusedLocation 은 정지 중이거나 값이 없을 때 heading 을 0 으로 준다.
 * 0(정북)으로 믿으면 남하 중에도 북쪽 쉼터를 고르므로, 0 이거나 정지 상태면 마지막으로 믿을 만했던 값을 유지한다.
 * heading 이 아예 없거나 0 으로 고정된 환경에서는 직전 두 위치의 방위(MIN_TRACK_KM 이상 움직였을 때)로 대신한다.
 */
class HeadingHold {
    var last: Double? = null
        private set
    /** 방위를 잴 기준 위치(위도, 경도). MIN_TRACK_KM 이상 움직이면 그 방위를 재고 기준을 옮긴다 */
    private var anchor: Pair<Double, Double>? = null

    /**
     * raw: SDK 가 준 heading(도). speedKph: 알면 속도. lat/lng: 알면 현재 위치(방위 대체용).
     * 반환: 쓸 heading(없으면 null)
     */
    fun resolve(raw: Double?, speedKph: Double? = null, lat: Double? = null, lng: Double? = null): Double? {
        val stopped = speedKph != null && speedKph < STOPPED_KPH
        val usable = raw != null && !raw.isNaN() && raw != 0.0 && !stopped
        var track: Double? = null
        if (lat != null && lng != null) {
            val a = anchor
            if (a == null) anchor = lat to lng
            else if (haversineKm(a.first, a.second, lat, lng) >= MIN_TRACK_KM) {
                track = bearingDeg(a.first, a.second, lat, lng)
                anchor = lat to lng
            }
        }
        when {
            usable -> last = ((raw!! % 360.0) + 360.0) % 360.0
            track != null -> last = track
        }
        return last
    }

    companion object {
        const val STOPPED_KPH = 3.0
        /** 이만큼(50m) 움직여야 두 위치의 방위를 믿는다. GPS 흔들림으로 방위가 튀지 않게 */
        const val MIN_TRACK_KM = 0.05
    }
}

/**
 * 위치 두 개의 거리와 시간으로 속도(km/h)를 추정한다(순수 로직). 내비·FusedLocation 위치에는 속도가 없다.
 *  - 표본 간격이 MIN_DT_MS 보다 짧으면 기준 표본을 그대로 두고 직전 추정값을 돌려준다(짧은 간격은 GPS 흔들림이 속도로 부풀려진다)
 *  - 간격이 MAX_DT_MS 를 넘거나 추정값이 MAX_KPH 를 넘으면 모른다(null). 위치를 한동안 못 받다가 다시 받았을 때의 위치 점프 등
 */
class SpeedEstimator {
    private var refAt = 0L
    private var ref: Pair<Double, Double>? = null
    private var lastKph: Double? = null

    fun update(t: Long, lat: Double, lng: Double): Double? {
        val r = ref
        if (r == null) { ref = lat to lng; refAt = t; return null }
        val dt = t - refAt
        if (dt < MIN_DT_MS) return if (dt >= 0) lastKph else null
        val kph = if (dt > MAX_DT_MS) null else (haversineKm(r.first, r.second, lat, lng) / (dt / 3_600_000.0)).takeIf { it <= MAX_KPH }
        ref = lat to lng; refAt = t
        lastKph = kph
        return kph
    }

    companion object {
        const val MIN_DT_MS = 500L
        const val MAX_DT_MS = 60_000L
        const val MAX_KPH = 250.0
    }
}

/** 버스 위치에 heading 이 없을 때 모의 기본 heading 을 써도 되는가(순수 함수) */
object HeadingFallback {
    /**
     * 모의 모드(Pleos 없음)이거나 내비가 실제 위치를 주지 않을 때만 모의 기본 heading.
     * 내비가 실제 위치를 주는데 heading 만 없으면(정차 중) null. 쉼터 후보를 내지 않고 "주행 방향을 알면 쉼터를 안내해요"를 보여 준다
     */
    fun mockHeading(pleosConnected: Boolean, navHasLocation: Boolean): Double? =
        if (pleosConnected && navHasLocation) null else DemoLocation.POINT.heading
}

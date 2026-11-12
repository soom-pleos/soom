package ai.kkaeugi.app.signals

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.kkaeugi.app.vehicle.EtaJudge
import ai.kkaeugi.app.vehicle.GeoPoint
import ai.kkaeugi.engine.CautionKind
import ai.kkaeugi.engine.DestinationEta
import ai.kkaeugi.engine.LocationUpdate
import ai.kkaeugi.engine.NavCaution
import ai.kkaeugi.engine.NavDestinationArrived
import ai.kkaeugi.engine.NavSpeedLimit
import ai.kkaeugi.engine.NavTurnAhead
import ai.kkaeugi.engine.TurnDir
import android.content.Context
import android.util.Log

/**
 * Pleos NaviHelper SDK → 엔진 신호.
 * 쓰는 이벤트: onTBTInfo(다음 회전), onCautionPointInfo(주의 구간), onCurrentRoadSpeedLimit, onCurrentLocationInfo, onDestinationArrived,
 *   onDestinationInfo(목적지까지 남은 시간과 거리 → DestinationEta. getDestinationInfo() 를 부르면 이 콜백으로 온다).
 * 목적지 남은 시간은 단위가 문서에 없어 EtaJudge 가 거리와 함께 보고 초/분을 고른다. 경로가 없어지면 null 을 보낸다.
 * 경유지 추가(addWaypoint)는 VehicleActuator 가 navi 프로퍼티를 빌려서 한다. 마지막 위치/heading 도 여기서 들고 있는다.
 */
class PleosNavSource(private val context: Context, private val bus: SignalBus) : SignalSource {
    override val name = "Pleos NaviHelper SDK"

    var navi: NaviHelper? = null
        private set
    /** 마지막으로 받은 현재 위치(위도, 경도, heading 도 단위). 경유지 탐색의 기준. */
    @Volatile var lastPoint: GeoPoint? = null
        private set
    /** 마지막 경유지 변경/오류 이벤트 문자열 (검증 패널 표시용) */
    @Volatile var lastWaypointEvent: String? = null
        private set
    var onWaypointEvent: ((String) -> Unit)? = null
    /** initialize 의 준비 콜백이 왔는가, 실패했다면 그 사유(자가 진단 표시용) */
    @Volatile var ready = false
        private set
    @Volatile var initError: String? = null
        private set

    /** 경유지 변경 이벤트가 올 때(우리 경유지 확인용, WaypointGuard) */
    var onWaypointChangedRaw: (() -> Unit)? = null
    private val headingHold = ai.kkaeugi.app.vehicle.HeadingHold()

    private val speedEstimator = ai.kkaeugi.app.vehicle.SpeedEstimator()

    private fun now() = MonoClock.now()

    // ---------- 목적지까지 남은 분 ----------
    private val etaJudge = EtaJudge()
    /** 마지막으로 받은 목적지 raw(남은 시간, 남은 거리). 자가 진단 표시용 */
    @Volatile var lastDestinationRaw: Pair<Int, Int>? = null
        private set
    /** lastDestinationRaw 를 받은 시각(단조). 자가 진단이 새 값이 왔는지 볼 때 쓴다 */
    @Volatile var lastDestinationAt = 0L
        private set
    /** 마지막 위치를 받은 시각(단조) */
    @Volatile var lastPointAt = 0L
        private set
    /** 마지막으로 알린 남은 분. 목적지가 없으면 null */
    @Volatile var etaMinutes: Int? = null
        private set
    private var etaPublished = false

    private fun publishEta(minutes: Int?, force: Boolean = false) {
        if (!force && etaPublished && etaMinutes == minutes) return
        etaPublished = true
        etaMinutes = minutes
        bus.emit(DestinationEta(now(), minutes))
    }

    /** 목적지 정보를 다시 달라고 한다. 답은 onDestinationInfo 로 온다. 주행 중 1분마다, 경로가 바뀔 때 부른다 */
    fun requestDestination() { navi?.let { n -> runCatching { n.getDestinationInfo() }.onFailure { Log.w(TAG, "getDestinationInfo 실패: ${it.message}") } } }

    /** 현재 위치를 다시 달라고 한다(자가 진단). 답은 onCurrentLocationInfo 로 온다 */
    fun requestLocation() { navi?.let { n -> runCatching { n.getCurrentLocationInfo() } } }

    /** 새 주행이 시작됐다. 아는 값을 다시 알리고(없으면 null) 새 값을 요청한다 */
    fun onNewTrip() {
        if (navi == null) return
        publishEta(etaMinutes, force = true)
        requestDestination()
    }

    private fun clearDestination(why: String) {
        Log.i(TAG, "목적지 없음($why)")
        lastDestinationRaw = null
        publishEta(null)
    }

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            lastDestinationRaw = info.remainTime to info.remainDistance
            lastDestinationAt = now()
            // 남은 시간과 거리가 모두 0 이하면 목적지가 없는 것으로 본다
            if (info.remainTime <= 0 && info.remainDistance <= 0) { publishEta(null); return }
            val minutes = etaJudge.minutes(info.remainTime, info.remainDistance)
            Log.i(TAG, "목적지: " + EtaJudge.describe(info.remainTime, info.remainDistance, minutes))
            // 값이 같아도 다시 알린다. 엔진은 받은 지 10분이 지난 남은 시간을 낡은 값으로 보고 쓰지 않는다
            publishEta(minutes, force = true)
        }
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) = requestDestination()
        override fun onRoutingCompleted() = requestDestination()
        override fun onReRouteEnded() = requestDestination()
        override fun onDestinationChanged() = requestDestination()
        override fun onRouteCancelled() = clearDestination("경로 취소")
        override fun onRouteEnded() = clearDestination("경로 끝")

        override fun onTBTInfo(list: List<TBTInfo>) {
            val next = list.firstOrNull() ?: return
            bus.emit(NavTurnAhead(now(), next.type.toEngine(), next.distance))
        }

        override fun onCautionPointInfo(list: List<CautionPoint>) {
            list.forEach { bus.emit(NavCaution(now(), it.type.toEngine(), it.remainDistance)) }
        }

        override fun onCurrentRoadSpeedLimit(kph: Int) = bus.emit(NavSpeedLimit(now(), kph))

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            val t = now()
            // 내비 위치에는 속도가 없어서 이전 위치와의 거리/시간으로 추정한다. 추정할 수 없으면 null(엔진은 속도를 모르는 것으로 본다)
            val speed = speedEstimator.update(t, info.latitude, info.longitude)
            // heading 이 0 이거나 정지 중이면 이전 값을 유지한다(정북으로 튀면 반대 방향 쉼터를 고른다).
            // heading 이 없거나 0 으로 고정이면 직전 두 위치의 방위로 대신한다
            lastPoint = GeoPoint(info.latitude, info.longitude, headingHold.resolve(info.heading, speed, info.latitude, info.longitude))
            lastPointAt = t
            bus.emit(LocationUpdate(t, info.latitude, info.longitude, speed))
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) { bus.emit(NavDestinationArrived(now())); clearDestination("도착") }

        override fun onWaypointChanged(info: WaypointChangedInfo) {
            val s = "경유지 변경: index=${info.index} cmd=${info.cmdType}"
            Log.i(TAG, s); lastWaypointEvent = s; onWaypointChangedRaw?.invoke(); onWaypointEvent?.invoke(s)
        }

        override fun onError(code: NaviErrorCode) {
            val s = "내비 오류: $code"
            Log.w(TAG, s); lastWaypointEvent = s; onWaypointEvent?.invoke(s)
        }
    }

    override fun start(): Boolean = try {
        val n = NaviHelper(context)
        n.initialize({ Log.i(TAG, "NaviHelper ready"); ready = true; runCatching { n.getCurrentLocationInfo() }; runCatching { n.getDestinationInfo() } }, { e -> initError = e.message ?: e.javaClass.simpleName; Log.w(TAG, "NaviHelper init failed: ${e.message}") })
        n.addListener(listener)
        navi = n
        true
    } catch (e: Throwable) {
        Log.w(TAG, "NaviHelper unavailable: ${e.message}")
        navi = null
        false
    }

    override fun stop() {
        navi?.let { runCatching { it.removeListener(listener); it.release() } }
        navi = null
    }

    private fun TurnType.toEngine() = when (this) {
        TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> TurnDir.LEFT
        TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> TurnDir.RIGHT
        TurnType.TURN_UTURN -> TurnDir.UTURN
        TurnType.TURN_STRAIGHT, TurnType.TURN_KEEP_L, TurnType.TURN_KEEP_R -> TurnDir.STRAIGHT
        TurnType.TURN_ENTER_IC_R, TurnType.TURN_ENTER_IC_L, TurnType.TURN_ENTER_IC_C -> TurnDir.ENTER_IC
        TurnType.TURN_EXIT_IC_R, TurnType.TURN_EXIT_IC_L -> TurnDir.EXIT_IC
        else -> TurnDir.OTHER
    }

    private fun CautionPointType.toEngine() = when (this) {
        CautionPointType.SCHOOL_ZONE -> CautionKind.SCHOOL_ZONE
        CautionPointType.SPEED_BUMP -> CautionKind.SPEED_BUMP
        CautionPointType.ACCIDENT_PRONE_AREA -> CautionKind.ACCIDENT_PRONE
        CautionPointType.RAILROAD_CROSSING -> CautionKind.RAILROAD
        CautionPointType.FOG_AREA -> CautionKind.FOG
        else -> CautionKind.OTHER
    }

    companion object { const val TAG = "Kkaeugi.Nav" }
}

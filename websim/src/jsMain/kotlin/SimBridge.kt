package ai.kkaeugi.websim

import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.ActionResult
import ai.kkaeugi.engine.AppVisibility
import ai.kkaeugi.engine.AutoClimateChanged
import ai.kkaeugi.engine.DestinationEta
import ai.kkaeugi.engine.FrontPassengerChanged
import ai.kkaeugi.engine.TrustAnswer
import ai.kkaeugi.engine.TrustState
import ai.kkaeugi.engine.ArrivalSummary
import ai.kkaeugi.engine.FanSpeedChanged
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.GearChanged
import ai.kkaeugi.engine.LocationUpdate
import ai.kkaeugi.engine.LowBeamChanged
import ai.kkaeugi.engine.OccupantsChanged
import ai.kkaeugi.engine.RecirculationChanged
import ai.kkaeugi.engine.RestStopCandidate
import ai.kkaeugi.engine.RestStopInfo
import ai.kkaeugi.engine.RestStopProximity
import ai.kkaeugi.engine.Signal
import ai.kkaeugi.engine.SpeechFinished
import ai.kkaeugi.engine.SteeringAngle
import ai.kkaeugi.engine.Telltale
import ai.kkaeugi.engine.TelltaleChanged
import ai.kkaeugi.engine.TripTimeUpdate
import ai.kkaeugi.engine.TurnSignalChanged
import ai.kkaeugi.engine.TurnSignalState
import ai.kkaeugi.engine.UserAnswer
import ai.kkaeugi.engine.WakeConfig
import ai.kkaeugi.engine.WakeEngine
import ai.kkaeugi.engine.WakeEvent
import ai.kkaeugi.engine.WindowOpenChanged
import ai.kkaeugi.engine.restStopDisplayName
import ai.kkaeugi.engine.WiperChanged

/**
 * 브라우저 시뮬레이터용 얇은 파사드. 판단은 전부 engine/src/main/kotlin 의 WakeEngine 이 한다(앱과 같은 소스).
 * 여기서는 JS 값을 엔진 신호로 바꿔 넣고, 엔진이 돌려준 WakeEvent 목록을 JSON 문자열로 바꿔 돌려줄 뿐이다.
 *
 * 시각 t 는 단조 시계 ms(JS number). 엔진의 Long 으로 바꿔 넣는다.
 * 설정: demoFast=true 면 앱 WakeViewModel.demoConfig() 와 같은 값, false 면 WakeConfig() 기본값.
 *       핸들 신호(steeringAssist)와 창문(allowWindow)은 엔진 기본값대로 꺼져 있고 configure 로 켠다(페이지의 ?steer=1, ?window=1).
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
class Sim(demoFast: Boolean) {
    private var steer = false
    private var window = false
    private var config: WakeConfig = configFor(demoFast, steer, window)
    private val engine = WakeEngine(config)
    /** 마지막으로 받은 Execute(원복 제외)의 액션. actionResult 가 이름으로 찾아 쓴다 */
    private var lastExecute: List<Action> = emptyList()
    private var lastStop: RestStopInfo? = null

    // ---------- 신호 (JS → 엔진). 전부 WakeEvent 목록 JSON 을 돌려준다 ----------

    fun gear(t: Double, gear: String): String =
        send(GearChanged(ms(t), Gear.entries.firstOrNull { it.name == gear } ?: Gear.UNKNOWN))

    fun recirc(t: Double, on: Boolean): String = send(RecirculationChanged(ms(t), on))
    fun occupants(t: Double, n: Int): String = send(OccupantsChanged(ms(t), n))
    fun fan(t: Double, level: Int, max: Int): String = send(FanSpeedChanged(ms(t), level, max))
    fun windowOpen(t: Double, pct: Int): String = send(WindowOpenChanged(ms(t), pct))
    fun wiper(t: Double, on: Boolean): String = send(WiperChanged(ms(t), on))
    fun lowBeam(t: Double, on: Boolean): String = send(LowBeamChanged(ms(t), on))

    fun telltale(t: Double, type: String, on: Boolean, blink: Boolean): String =
        send(TelltaleChanged(ms(t), Telltale.entries.firstOrNull { it.name == type } ?: Telltale.OTHER, on, blink))

    fun turnSignal(t: Double, state: String): String =
        send(TurnSignalChanged(ms(t), TurnSignalState.entries.firstOrNull { it.name == state } ?: TurnSignalState.OFF))

    fun steering(t: Double, deg: Double): String = send(SteeringAngle(ms(t), deg.toFloat()))
    fun tripTime(t: Double, min: Int): String = send(TripTimeUpdate(ms(t), min))
    fun answer(t: Double, yes: Boolean): String = send(UserAnswer(ms(t), yes))
    fun speechFinished(t: Double): String = send(SpeechFinished(ms(t)))

    /**
     * 차량 제어 결과. action 은 "Ventilate" | "CoolDown" | "OpenWindow" | "AddRestStop" | "Revert".
     * 엔진이 Execute 로 내보낸 바로 그 액션 객체를 찾아 돌려준다(없으면 기본값으로 만든다).
     */
    fun actionResult(t: Double, action: String, ok: Boolean, detail: String?): String {
        val a: Action = if (action == "Revert") Action.Revert
        else lastExecute.firstOrNull { actionName(it) == action } ?: when (action) {
            "Ventilate" -> Action.Ventilate()
            "CoolDown" -> Action.CoolDown()
            "OpenWindow" -> Action.OpenWindow()
            "AddRestStop" -> lastStop?.let { Action.AddRestStop(it) } ?: return "[]"
            else -> return "[]"
        }
        return send(ActionResult(ms(t), a, ok, detail))
    }

    fun restStopCandidate(t: Double, name: String, routeLabel: String, km: Double, lat: Double, lng: Double): String {
        val stop = RestStopInfo(name, routeLabel, km, lat, lng)
        lastStop = stop
        return send(RestStopCandidate(ms(t), stop))
    }

    fun noRestStop(t: Double): String {
        lastStop = null
        return send(RestStopCandidate(ms(t), null))
    }

    fun restProximity(t: Double, within: Boolean, passed: Boolean): String = send(RestStopProximity(ms(t), within, passed))

    /** 위치와 속도(km/h). 엔진은 속도를 알면 핸들 신호를 고속 주행에서만 센다 */
    fun location(t: Double, lat: Double, lng: Double, kph: Double): String = send(LocationUpdate(ms(t), lat, lng, kph))

    // ----- 계약 v4 -----

    /** 도착 카드의 맡기기 질문에 답했다. accept = [맡길게요] */
    fun trustAnswer(t: Double, accept: Boolean): String = send(TrustAnswer(ms(t), accept))
    /** 앱 화면이 운전자에게 보이는가 */
    fun appVisibility(t: Double, visible: Boolean): String = send(AppVisibility(ms(t), visible))
    /** 목적지까지 남은 분. null(JS 의 null, undefined) = 목적지 없음 */
    fun destinationEta(t: Double, minutes: Int?): String = send(DestinationEta(ms(t), minutes))
    fun frontPassenger(t: Double, present: Boolean): String = send(FrontPassengerChanged(ms(t), present))
    fun autoClimate(t: Double, on: Boolean): String = send(AutoClimateChanged(ms(t), on))

    fun tick(t: Double): String = json(engine.tick(ms(t)))

    // ---------- 설정 ----------

    /** 자동 개입 ON/OFF. 진행 중인 제안/실행 정리 이벤트를 돌려준다 */
    fun setEnabled(on: Boolean, t: Double): String = json(engine.setEnabled(on, ms(t)))

    /** 속도 전환. 앱 setDemoFast 와 같이 엔진을 새로 만들지 않고 updateConfig 로 바꾼다 */
    fun setDemoFast(on: Boolean, t: Double): String {
        config = configFor(on, steer, window)
        engine.updateConfig(config, ms(t))
        return "[]"
    }

    /** 핸들 신호와 창문 개입을 켜고 끈다(엔진 기본은 둘 다 꺼짐) */
    fun configure(steeringAssist: Boolean, allowWindow: Boolean, t: Double) {
        steer = steeringAssist
        window = allowWindow
        config = configFor(config.demoFast, steer, window)
        engine.updateConfig(config, ms(t))
    }

    /**
     * 맡기기 상태를 넣는다(앱이 저장해 둔 값을 넣는 자리). TrustChanged 는 나오지 않는다.
     * 맡기기를 끄는 순간 예고가 떠 있으면 그 예고를 닫는 Done 이 돌아온다. 그 밖에는 빈 목록이다
     */
    fun setTrust(delegated: Boolean, yes: Int, cancel: Int): String =
        json(engine.setTrust(TrustState(delegated, yes, cancel)))

    /** 화면의 "바깥 공기로"(조용 모드에서 한도를 넘었을 때). 묻지 않고 Execute(Ventilate) 를 낸다. 할 수 없는 때면 빈 목록 */
    fun requestVentilation(t: Double): String = json(engine.requestVentilation(ms(t)))

    /** 도착 카드를 닫은 뒤 대기 상태로 */
    fun reset() {
        engine.reset()
        lastExecute = emptyList()
    }

    // ---------- 읽기 ----------

    val phase: String get() = engine.phaseName
    val ppm: Int get() = engine.estimatedPpm
    val steerEvents: Int get() = engine.steerEvents
    val tripState: String get() = engine.tripState.name
    val resting: Boolean get() = engine.isResting
    val enabled: Boolean get() = engine.enabled
    val demoFast: Boolean get() = config.demoFast
    val waypointName: String? get() = engine.currentWaypoint?.name
    val lastProposalText: String? get() = engine.lastProposalText
    /** 맡기기 상태 JSON: {"delegated":..,"consecutiveYes":..,"cancelCount":..} */
    val trust: String get() = trustJson(engine.trust)
    val arrivingSoon: Boolean get() = engine.arrivingSoon
    val carAutoActive: Boolean get() = engine.carAutoActive
    val quietMode: Boolean get() = engine.quietMode
    /** 조용 모드라 묻지 않는 중인데 추정치가 유효 한도 이상이다. 홈에 한도 초과 문구와 "바깥 공기로" 버튼을 보여 준다 */
    val overLimitWhileQuiet: Boolean get() = engine.overLimitWhileQuiet
    /** 지금 실행은 예고로 시작했거나 직접 요청한 환기라 실행 중에도 취소(answer(false))를 받는다 */
    val cancellableRun: Boolean get() = engine.cancellableRun
    val steeringAssist: Boolean get() = config.steeringAssist
    val allowWindow: Boolean get() = config.allowWindow

    /** 지금 쓰는 환기 제안 한도(ppm). 야간 장거리면 기본 한도보다 낮다. 게이지 한도선에 쓴다 */
    val effectiveLimitPpm: Int get() = engine.effectiveLimitPpm
    /** 마지막 제안의 변형("REVERT", "KEEP", "KEEP_AFTER_TWO", "FAN_UP", "TIME_CAP", "DROWSY"). 로그 표시용 */
    val lastProposalVariant: String? get() = engine.lastProposalVariant

    /** 쉼터 표시 이름(엔진 restStopDisplayName). "석적" → "석적 졸음쉼터" */
    fun stopDisplayName(name: String): String = restStopDisplayName(name)

    // 화면이 쓰는 설정값(엔진 설정 그대로)
    val limitPpm: Int get() = config.co2LimitPpm
    val outdoorPpm: Int get() = config.outdoorPpm
    val longTripMinutes: Int get() = config.longTripMinutes
    val ventilateSeconds: Int get() = config.ventilateSeconds
    val drowsySeconds: Int get() = config.drowsySeconds
    val restMinutes: Int get() = config.restMinutes
    val answerTimeoutSec: Int get() = config.answerTimeoutSec
    val steerQuietSec: Int get() = config.steerQuietSec
    val steerJerkDeg: Double get() = config.steerJerkDeg.toDouble()
    val declineCooldownMinutes: Int get() = config.declineCooldownMinutes
    val leakAch: Double get() = config.leakAch
    val co2PerPersonLph: Double get() = config.co2PerPersonLph
    val cabinVolumeL: Int get() = config.cabinVolumeL

    // ---------- 내부 ----------

    private fun send(s: Signal): String = json(engine.accept(s))

    private fun json(events: List<WakeEvent>): String {
        for (e in events) if (e is WakeEvent.Execute && e.actions.none { it == Action.Revert }) lastExecute = e.actions
        return events.joinToString(",", "[", "]") { eventJson(it) }
    }

    private fun ms(t: Double): Long = t.toLong()
}

// ---------- 파일 안에서만 쓰는 도우미(내보내지 않는다) ----------

/** 앱 WakeViewModel.demoConfig() 와 같은 값. 실제 속도는 WakeConfig() 기본값 */
private fun configFor(demoFast: Boolean, steer: Boolean, window: Boolean): WakeConfig = if (demoFast) WakeConfig(
    staleAirMinutes = 30, staleAirMinutesNight = 20, ventilateSeconds = 20, drowsySeconds = 30, answerTimeoutSec = 8,
    declineCooldownMinutes = 20, longTripMinutes = 60, restMinutes = 15, demoFast = true,
    steeringAssist = steer, allowWindow = window,
) else WakeConfig(steeringAssist = steer, allowWindow = window)

private fun trustJson(s: TrustState): String = obj(
    "delegated" to s.delegated.toString(), "consecutiveYes" to s.consecutiveYes.toString(), "cancelCount" to s.cancelCount.toString(),
)

private fun actionName(a: Action): String = when (a) {
    is Action.Ventilate -> "Ventilate"
    is Action.CoolDown -> "CoolDown"
    is Action.OpenWindow -> "OpenWindow"
    is Action.AddRestStop -> "AddRestStop"
    Action.Revert -> "Revert"
}

private fun eventJson(e: WakeEvent): String = when (e) {
    is WakeEvent.Propose -> obj(
        "type" to q("Propose"), "kind" to q(e.kind.name), "text" to q(e.text),
        "actions" to arr(e.actions.map { actionJson(it) }), "timeoutSec" to e.timeoutSec.toString(),
        "passengerMayAnswer" to e.passengerMayAnswer.toString(),
    )
    is WakeEvent.Announce -> obj(
        "type" to q("Announce"), "kind" to q(e.kind.name), "text" to q(e.text),
        "actions" to arr(e.actions.map { actionJson(it) }), "cancelWindowSec" to e.cancelWindowSec.toString(),
    )
    is WakeEvent.Quiet -> obj("type" to q("Quiet"), "text" to q(e.text))
    is WakeEvent.TrustChanged -> obj("type" to q("TrustChanged"), "state" to trustJson(e.state))
    is WakeEvent.ProposalExpired -> obj("type" to q("ProposalExpired"), "text" to q(e.text))
    is WakeEvent.Execute -> obj(
        "type" to q("Execute"), "kind" to q(e.kind.name), "actions" to arr(e.actions.map { actionJson(it) }),
        "revert" to (e.actions.size == 1 && e.actions[0] == Action.Revert).toString(),
    )
    is WakeEvent.Running -> obj(
        "type" to q("Running"), "kind" to q(e.kind.name), "remainingSec" to e.remainingSec.toString(), "label" to q(e.label),
    )
    is WakeEvent.Done -> obj("type" to q("Done"), "kind" to q(e.kind.name), "text" to qn(e.text))
    is WakeEvent.Deferred -> obj("type" to q("Deferred"), "kind" to q(e.kind.name), "reason" to q(e.reason))
    is WakeEvent.TripStateChanged -> obj("type" to q("TripStateChanged"), "state" to q(e.state.name))
    is WakeEvent.RestStarted -> obj(
        "type" to q("RestStarted"), "stopName" to q(e.stopName), "targetMinutes" to e.targetMinutes.toString(),
    )
    is WakeEvent.RestTick -> obj("type" to q("RestTick"), "remainingSec" to e.remainingSec.toString())
    is WakeEvent.RestEnded -> obj(
        "type" to q("RestEnded"), "restedMinutes" to e.restedMinutes.toString(), "text" to q(e.text),
    )
    is WakeEvent.AirEstimate -> obj(
        "type" to q("AirEstimate"), "ppm" to e.ppm.toString(), "occupants" to e.occupants.toString(),
        "minutesToLimit" to (e.minutesToLimit?.toString() ?: "null"), "ventilating" to e.ventilating.toString(),
    )
    is WakeEvent.ArrivalCard -> obj("type" to q("ArrivalCard"), "card" to summaryJson(e.card))
}

private fun actionJson(a: Action): String = when (a) {
    is Action.Ventilate -> obj("type" to q("Ventilate"), "fanDelta" to a.fanDelta.toString())
    is Action.CoolDown -> obj("type" to q("CoolDown"), "celsiusDelta" to num(a.celsiusDelta.toDouble()))
    is Action.OpenWindow -> obj("type" to q("OpenWindow"), "percent" to a.percent.toString())
    is Action.AddRestStop -> obj("type" to q("AddRestStop"), "stop" to stopJson(a.stop))
    Action.Revert -> obj("type" to q("Revert"))
}

private fun stopJson(s: RestStopInfo): String = obj(
    "name" to q(s.name), "routeLabel" to q(s.routeLabel), "distanceKm" to num(s.distanceKm),
    "lat" to num(s.lat), "lng" to num(s.lng),
)

private fun summaryJson(c: ArrivalSummary): String = obj(
    "headline" to q(c.headline), "tripMinutes" to c.tripMinutes.toString(), "ventilations" to c.ventilations.toString(),
    "freshAirMinutes" to c.freshAirMinutes.toString(), "drowsySignals" to c.drowsySignals.toString(),
    "restStopsAdded" to c.restStopsAdded.toString(), "restStopsVisited" to c.restStopsVisited.toString(),
    "restMinutes" to c.restMinutes.toString(), "restStopsPassed" to c.restStopsPassed.toString(),
    "declined" to c.declined.toString(), "lines" to arr(c.lines.map { q(it) }),
    "peakPpm" to c.peakPpm.toString(), "minutesOverLimit" to c.minutesOverLimit.toString(),
    "trustOffer" to qn(c.trustOffer),
)

// ---- JSON 조각(문자열 이스케이프 직접) ----

private fun obj(vararg fields: Pair<String, String>): String =
    fields.joinToString(",", "{", "}") { (k, v) -> q(k) + ":" + v }

private fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

private fun num(d: Double): String = if (d.isNaN() || d.isInfinite()) "null" else d.toString()

private fun qn(s: String?): String = if (s == null) "null" else q(s)

private fun q(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

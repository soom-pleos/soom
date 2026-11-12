package ai.kkaeugi.app.signals

import ai.kkaeugi.app.vehicle.PleosCalls
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.kkaeugi.app.vehicle.FanGate
import ai.kkaeugi.app.vehicle.HvacAreaRule
import ai.kkaeugi.app.vehicle.HvacProp
import ai.kkaeugi.app.vehicle.TripTimeSupport
import ai.kkaeugi.app.vehicle.OccupantCounter
import ai.kkaeugi.app.vehicle.OccupantDebouncer
import ai.kkaeugi.app.vehicle.OccupantInput
import ai.kkaeugi.app.vehicle.Outcome
import ai.kkaeugi.app.vehicle.SteeringRepeater
import ai.kkaeugi.app.vehicle.StableFlag
import ai.kkaeugi.app.vehicle.SteeringSampler
import ai.kkaeugi.app.vehicle.WindowOpenTracker
import ai.kkaeugi.engine.AutoClimateChanged
import ai.kkaeugi.engine.BrakePedal
import ai.kkaeugi.engine.FrontPassengerChanged
import ai.kkaeugi.engine.FanSpeedChanged
import ai.kkaeugi.engine.OccupantsChanged
import ai.kkaeugi.engine.SteeringAngle
import ai.kkaeugi.engine.WindowOpenChanged
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.GearChanged
import ai.kkaeugi.engine.LowBeamChanged
import ai.kkaeugi.engine.RecirculationChanged
import ai.kkaeugi.engine.Telltale
import ai.kkaeugi.engine.TelltaleChanged
import ai.kkaeugi.engine.TripTimeUpdate
import ai.kkaeugi.engine.TurnSignalChanged
import ai.kkaeugi.engine.TurnSignalState
import ai.kkaeugi.engine.WiperChanged
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK → 엔진 신호.
 * 읽는 도메인: Gear, Brake, TurnSignal, Wiper, Light(하향등), Safety(경고등), HVAC(내기순환, 풍량), Odometer(트립 주행시간),
 *   Seat(좌석 점유/안전벨트 → 탑승 인원), Window(네 창 위치 → 열림 퍼센트), SteeringWheel(핸들 각도).
 * 숨 모델 입력: 탑승 인원은 5초마다 지원 좌석만 조회(리스너가 자리마다 따로라 폴링이 단순하다), 풍량·공조 전원·창문·핸들은 리스너.
 *   탑승 인원은 두 번 연속 같은 값일 때만 알린다. 주행 중 운전석은 값과 무관하게 1 로 센다.
 *   공조 전원이 꺼져 있으면 풍량 0 으로 알린다. 풍량 리스너 등록이 실패하면 10초 폴링으로 내려간다.
 *   핸들 각도는 값이 바뀔 때만 오는 차를 대비해 주행 중 마지막 각도를 1초마다 다시 보낸다.
 * 제어는 여기서 하지 않는다. VehicleActuator 가 같은 Vehicle 인스턴스를 빌려 쓴다(vehicle 프로퍼티).
 *
 * 연결 판정: Vehicle.initialize() 는 예외를 던지지 않는다(서비스 바인딩이 비동기 재시도). 그래서 initialize 뒤 1.5초 기다렸다가
 * getCurrentGearSelectionState 를 한 번 부르고, 4초 안에 답이 오면 "Pleos 연결", 아니면 vehicle 을 null 로 내리고 모의 모드로 간다.
 *
 * 필요한 퍼미션: CAR_DRIVE(기어, 트립, 핸들 각도), CAR_LIGHTS, CAR_WINDOWS, PRIVATE_CAR_SAFETY(텔테일), CAR_CLIMATE, CAR_SEATS (AndroidManifest 참조).
 * 도메인별 리스너 등록 실패는 permissionFailures 에 모아 검증 패널에 보여 준다.
 */
class PleosVehicleSource(private val context: Context, private val bus: SignalBus, private val scope: CoroutineScope) : SignalSource {
    override val name = "Pleos Vehicle SDK"

    enum class Status { NONE, PROBING, CONNECTED, FAILED }

    /** 초기화되고 연결 판정까지 통과한 Vehicle. 그 전/실패 시 null. 액추에이터/검증 패널이 빌려 쓴다. */
    @Volatile var vehicle: Vehicle? = null
        private set
    @Volatile var status = Status.NONE
        private set
    /** 도메인별 등록 실패 목록("telltales: ERROR_SECURITY_PERMISSION ..."). 비어 있으면 전부 성공 */
    @Volatile var permissionFailures: List<String> = emptyList()
        private set
    /** 연결 판정 결과 콜백(연결 여부, 실패 사유) */
    var onProbe: ((connected: Boolean, detail: String) -> Unit)? = null
    /** 트립시간 단위 판정 로그 등 사람이 볼 메시지 */
    var onInfo: ((String) -> Unit)? = null

    private val activeTelltales = mutableMapOf<Telltale, Boolean>() // type → blink
    /** 리스너를 붙인 HVAC 영역(속성별). 해제할 때 같은 영역을 넘긴다. 영역은 PleosCalls 가 탐색해 정한다 */
    private val hvacRegistered = mutableMapOf<HvacProp, SeatArea>()

    /** 트립 시간 단위 판정용. 첫 두 값의 차이로 초/분을 추정한다. 판정 전에는 발행하지 않는다 */
    private var firstTripRaw: Int? = null
    private var firstTripRawAt = 0L
    @Volatile var tripUnitIsSeconds: Boolean? = null
        private set
    /** 검증 패널에서 사용자가 단위를 확정하면 여기에 들어온다 */
    fun forceTripUnit(seconds: Boolean) { tripUnitIsSeconds = seconds; onInfo?.invoke("트립시간 단위를 ${if (seconds) "초" else "분"}로 확정") }

    private fun now() = MonoClock.now()
    private fun fail(tag: String): (Exception?) -> Unit = { e -> Log.w(TAG, "$tag failed: ${e?.let { PleosCalls.describe(it) }}") }

    /** 기어가 D/R 인가. 운전석 인원 보정과 핸들 각도 다시 보내기에 쓴다 */
    @Volatile private var driving = false
    private fun publishGear(g: VehicleGear) {
        val gear = g.toEngine()
        driving = gear == Gear.DRIVE || gear == Gear.REVERSE
        bus.emit(GearChanged(now(), gear))
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(g: VehicleGear?) { if (g != null) publishGear(g) }
        override fun onFailed(e: Exception) { fail("gear")(e) }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) { if (pressed != null) bus.emit(BrakePedal(now(), pressed)) }
        override fun onFailed(e: Exception) { fail("brake")(e) }
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(v: TurnSignalValue?) { if (v != null) bus.emit(TurnSignalChanged(now(), v.toEngine())) }
        override fun onFailed(e: Exception) { fail("turnSignal")(e) }
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(s: WiperState?) { if (s != null) bus.emit(WiperChanged(now(), s == WiperState.ON)) }
        override fun onFailed(e: Exception) { fail("wiper")(e) }
    }
    private val lowBeamListener = object : LightStateListener {
        override fun onLightStateUpdated(s: LightState?) { if (s != null) bus.emit(LowBeamChanged(now(), s == LightState.ON)) }
        override fun onFailed(e: Exception) { fail("lowBeam")(e) }
    }
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(t: Telltales?) { if (t != null) publishTelltales(t) }
        override fun onFailed(e: Exception) { fail("telltales")(e); noteFailure("telltales(콜백)", e) }
    }
    /** 내기순환. SDK 리스너 이름은 AutoRecirculation 이지만 registerHvacRecirculationState 에 붙으면 일반 내기순환 상태가 온다. */
    private val recircListener = object : HvacRecirculationStateListener {
        override fun onHvacAutoRecirculationStateUpdated(on: Boolean?) {
            if (on != null) { Log.i(TAG, "recirculation=$on"); bus.emit(RecirculationChanged(now(), on)) }
        }
        override fun onFailed(e: Exception) { fail("recirculation")(e) }
    }
    /**
     * 차의 자동 내외기 제어. 리스너 타입이 일반 내기순환과 같다(HvacRecirculationStateListener). 같은 객체를 두 곳에 붙이면
     * 어느 값인지 가릴 수 없으므로 따로 만들어 registerHvacAutoRecirculationState 에만 붙인다
     */
    private val autoRecircListener = object : HvacRecirculationStateListener {
        override fun onHvacAutoRecirculationStateUpdated(on: Boolean?) { if (on != null) publishAutoClimate(on) }
        override fun onFailed(e: Exception) { fail("autoRecirculation")(e) }
    }
    @Volatile private var lastAutoClimate: Boolean? = null
    private fun publishAutoClimate(on: Boolean) {
        if (lastAutoClimate == on) return
        lastAutoClimate = on
        Log.i(TAG, "autoRecirculation=$on")
        bus.emit(AutoClimateChanged(now(), on))
    }
    private val tripTimeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(raw: Int?) { if (raw != null) publishTripTime(raw) }
        override fun onFailed(e: Exception) { onTripTimeFailure(PleosCalls.describe(e)) }
    }
    /**
     * 트립 주행시간을 주지 않는 차(ERROR_NOT_SUPPORTED_PROPERTY. 2026-11-11 에뮬레이터 실측). 앱 타이머만 쓴다.
     * 한 번만 알리고 리스너를 뗀다(다시 붙이거나 같은 로그를 되풀이하지 않는다)
     */
    @Volatile var tripTimeUnsupported = false
        private set
    private var tripFailureLogged = false
    private fun onTripTimeFailure(detail: String) {
        if (TripTimeSupport.isUnsupported(detail)) {
            if (tripTimeUnsupported) return
            tripTimeUnsupported = true
            Log.i(TAG, "tripTime 미지원, 앱 타이머 사용: $detail")
            onInfo?.invoke("트립 시간 미지원, 앱 타이머 사용")
            runCatching { (vehicle ?: pending)?.getOdometer()?.unregisterTripCurrentDrivingTime(tripTimeListener) }
        } else if (!tripFailureLogged) {
            tripFailureLogged = true
            Log.w(TAG, "tripTime failed: $detail")
        }
    }

    // ---------- 숨 모델 입력 ----------
    /** 앱 전체가 같이 쓰는 호출 층. HVAC 영역 탐색 결과를 한 곳에 둔다 */
    val calls = PleosCalls { vehicle }
    private var occupantJob: Job? = null
    private var fanPollJob: Job? = null
    @Volatile var lastOccupants: OccupantCounter.Reading? = null
        private set
    private var fanMax = 0
    private val fanGate = FanGate()
    private var occupantDebouncer = OccupantDebouncer()
    private var frontPassengerFlag = StableFlag()
    private var seatsStable = ai.kkaeugi.app.vehicle.StableValue<List<Boolean?>>()
    private val seatLiveness = ai.kkaeugi.app.vehicle.SeatLiveness()
    /** 좌석 값(점유 또는 벨트)이 실제로 오고 있는가가 바뀔 때. 안 오면 앱이 수동 입력 값을 쓴다 */
    var onSeatSensor: ((live: Boolean) -> Unit)? = null
    /** 자리별 값(SeatLayout 순서 다섯 칸)이 바뀔 때. 좌석 지도가 이 값을 그대로 그린다. 좌석 값이 끊기면 null */
    var onSeats: ((seats: List<Boolean?>?) -> Unit)? = null
    @Volatile var seatSensorLive = false
        private set
    private val windowTracker = WindowOpenTracker()
    private var windowRanges: Map<WindowValue, Pair<Int, Int>> = emptyMap()
    private var lastWindowPercent = -1
    private val steeringSampler = SteeringSampler()
    private val steeringRepeater = SteeringRepeater(STEER_REPEAT_MS)
    private var steerRepeatJob: Job? = null

    /** 설정 "창문 방향 반전". 바꾸면 기억해 둔 창문 위치로 열림 퍼센트를 다시 계산해 알린다 */
    var windowInverted: Boolean
        get() = windowTracker.inverted
        set(value) {
            if (windowTracker.inverted == value) return
            windowTracker.inverted = value
            if (lastWindowPercent >= 0) publishWindowPercent(windowTracker.maxPercent)
        }

    private val fanListener = object : HvacFanSpeedListener {
        override fun onHvacFanSpeedUpdated(level: Int?) { if (level != null) publishFan(level) }
        override fun onFailed(e: Exception) { fail("fanSpeed")(e) }
    }
    private val powerListener = object : HvacPowerStateListener {
        override fun onHvacPowerStateUpdated(on: Boolean?) { if (on != null) publishHvacPower(on) }
        override fun onFailed(e: Exception) { fail("hvacPower")(e) }
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(deg: Float?) {
            if (deg == null) return
            val t = now()
            // 리스너는 훨씬 자주 온다. 엔진에는 초당 최대 5개만. 걸러 낸 값도 최신 각도로 기억해 둔다
            synchronized(steeringRepeater) {
                if (steeringSampler.accept(t)) { steeringRepeater.onSent(t, deg); bus.emit(SteeringAngle(t, deg)) }
                else steeringRepeater.onObserved(deg)
            }
        }
        override fun onFailed(e: Exception) { fail("steering")(e) }
    }
    private val windowListeners: Map<WindowValue, WindowPositionListener> = PleosCalls.SIDE_WINDOWS.associateWith { w ->
        object : WindowPositionListener {
            override fun onWindowPositionUpdated(pos: Int?) { if (pos != null) publishWindow(w, pos) }
            override fun onFailed(e: Exception) { fail("window ${w.name}")(e) }
        }
    }

    /** 풍량 단계가 바뀌었다. 공조 전원이 꺼져 있으면 0 으로 알린다(FanGate) */
    private fun publishFan(level: Int) = synchronized(fanGate) {
        fanGate.level = level
        fanGate.next(fanMax)?.let { (l, m) -> bus.emit(FanSpeedChanged(now(), l, m)) }
    }

    private fun publishHvacPower(on: Boolean) = synchronized(fanGate) {
        if (fanGate.power != on) Log.i(TAG, "hvacPower=$on")
        fanGate.power = on
        fanGate.next(fanMax)?.let { (l, m) -> bus.emit(FanSpeedChanged(now(), l, m)) }
    }

    private fun publishWindow(w: WindowValue, pos: Int) {
        val (min, max) = windowRanges[w] ?: (0 to 100)
        publishWindowPercent(windowTracker.update(w.name, pos, min, max))
    }

    private fun publishWindowPercent(percent: Int) {
        if (percent == lastWindowPercent) return
        lastWindowPercent = percent
        bus.emit(WindowOpenChanged(now(), percent))
    }

    /**
     * 탑승 인원: 지금 한 번 읽고, 이후 5초마다. 두 번 연속 같은 값이고 마지막으로 알린 값과 다를 때만 알린다(처음 값은 약 5초 뒤).
     * 로그도 그때만 남긴다
     */
    private fun startOccupantPolling() {
        occupantJob?.cancel()
        occupantJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && vehicle != null) {
                val r = calls.readOccupants(driving)
                lastOccupants = r
                // 끊긴 것은 두 번 연속일 때만 인정한다(폴링 한 번이 실패했다고 수동 입력으로 넘어가지 않는다)
                seatLiveness.offer(r.sensorLive)?.let { live ->
                    seatSensorLive = live
                    if (!live) {
                        // 값이 돌아왔을 때 같은 값이어도 다시 알리도록 새로 만든다(그 사이 수동 입력 값으로 덮인다)
                        occupantDebouncer = OccupantDebouncer()
                        frontPassengerFlag = StableFlag()
                        seatsStable = ai.kkaeugi.app.vehicle.StableValue()
                        onSeats?.invoke(null)
                    }
                    onInfo?.invoke(if (live) "좌석 값이 와요(${OccupantCounter.label(r.source)})" else "좌석 값이 안 와요. 정차 화면의 탑승 인원 입력을 써요")
                    onSeatSensor?.invoke(live)
                }
                // 좌석 값이 안 온 폴링은 건너뛴다(끊김이 인정되기 전의 한 번도 포함). 수동 입력 값이 그대로 쓰인다
                if (r.sensorLive) {
                    occupantDebouncer.offer(r.count)?.let { n ->
                        bus.emit(OccupantsChanged(now(), n))
                        onInfo?.invoke("탑승 인원 ${n}명 (${OccupantCounter.label(r.source)}: ${r.detail})")
                    }
                    // 조수석: 점유 값, 없으면 벨트
                    frontPassengerFlag.offer(OccupantInput.frontPassenger(true, r.occupied(SeatValue.ROW_1_RIGHT.name)))?.let { present ->
                        bus.emit(FrontPassengerChanged(now(), present))
                        onInfo?.invoke("조수석 ${if (present) "탑승" else "비어 있음"}")
                    }
                    // 자리별 값: 인원과 같은 규칙(두 번 연속 같을 때)으로 좌석 지도에 넘긴다
                    ai.kkaeugi.app.vehicle.SeatLayout.of(r)?.let { seats -> seatsStable.offer(seats)?.let { onSeats?.invoke(it) } }
                }
                delay(OCCUPANT_PERIOD_MS)
            }
        }
    }

    /** 주행 중 마지막 핸들 각도를 1초마다 다시 보낸다(값이 바뀔 때만 오는 리스너 대비) */
    private fun startSteeringRepeat() {
        steerRepeatJob?.cancel()
        steerRepeatJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && vehicle != null) {
                delay(STEER_REPEAT_CHECK_MS)
                val t = now()
                synchronized(steeringRepeater) {
                    steeringRepeater.due(t, driving)?.let { deg -> steeringRepeater.onSent(t, deg); bus.emit(SteeringAngle(t, deg)) }
                }
            }
        }
    }

    /** 제어 직후(환기, 원복) 실제 풍량을 바로 읽어 발행한다. 리스너가 늦거나 폴링 중일 때 대비 */
    fun refreshFan() {
        if (vehicle == null) return
        scope.launch(Dispatchers.Main.immediate) {
            // 환기가 공조 전원을 켜고 원복이 끌 수 있다(v0.7.4). 전원 리스너가 없는 차에서도 풍량 0 여부가 맞게 전원부터 읽는다
            (calls.getHvacPower() as? Outcome.Ok)?.let { publishHvacPower(it.value) }
            (calls.getFanSpeed() as? Outcome.Ok)?.let { publishFan(it.value) }
        }
    }

    /** 풍량 리스너를 못 붙였을 때: 10초마다 읽는다 */
    private fun startFanPolling() {
        fanPollJob?.cancel()
        fanPollJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && vehicle != null) {
                (calls.getFanSpeed() as? Outcome.Ok)?.let { publishFan(it.value) }
                delay(FAN_POLL_MS)
            }
        }
    }

    /** HVAC 영역을 찾아 리스너를 붙이고, 풍량 최대값과 창문 범위를 읽은 뒤 나머지 리스너를 붙인다(범위를 알아야 퍼센트·비율을 낼 수 있다) */
    private fun registerCabinInputs(v: Vehicle, safely: (String, () -> Unit) -> Unit) {
        scope.launch(Dispatchers.Main.immediate) {
            syncHvacListeners(v)
            (calls.windowBounds() as? Outcome.Ok)?.let { b ->
                windowRanges = PleosCalls.SIDE_WINDOWS.mapNotNull { w ->
                    b.value.entries.firstOrNull { e -> e.key == WindowArea(w) }?.value?.let { w to (it.minValue to it.maxValue) }
                }.toMap()
            }
            for (w in PleosCalls.SIDE_WINDOWS) {
                safely("window ${w.name}") { v.getWindow().registerWindowPosition(WindowArea(w), windowListeners.getValue(w)) }
                (calls.getWindowAt(w) as? Outcome.Ok)?.let { publishWindow(w, it.value) }
            }
            safely("steering(CAR_DRIVE)") { v.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
            startSteeringRepeat()
            startOccupantPolling()
        }
    }

    /** 내기순환을 읽을 수 있는 차인가. 영역 탐색이 끝나야 정해진다(그 전에는 null) */
    @Volatile var recirculationSupported: Boolean? = null
        private set
    /** 영역 탐색이 끝났을 때(내기순환 지원 여부). 홈의 "이 차에서는 내외기 상태를 읽을 수 없어요" 표시에 쓴다 */
    var onHvacResolved: ((recirculationSupported: Boolean) -> Unit)? = null
    private val hvacSync = Mutex()

    /** 자가 진단이 영역을 다시 찾은 뒤 부른다. 영역이 바뀐 속성만 리스너를 옮긴다 */
    fun resyncHvacListeners() {
        val v = vehicle ?: return
        scope.launch(Dispatchers.Main.immediate) { syncHvacListeners(v) }
    }

    /** 속성의 리스너를 탐색된 영역에 맞춘다. 이미 그 영역에 붙어 있으면 false(아무것도 안 함). 영역이 바뀌었으면 옛 것을 떼고 true */
    private fun moveListener(prop: HvacProp, area: SeatArea?, unregister: (SeatArea) -> Unit): Boolean {
        val old = hvacRegistered[prop]
        if (old != null && area != null && calls.maskOf(old) == calls.maskOf(area)) return false
        if (old != null) { runCatching { unregister(old) }; hvacRegistered.remove(prop) }
        return area != null
    }

    /**
     * HVAC 리스너. 영역을 먼저 찾고(PleosCalls.resolveHvacAreas) 그 영역으로 붙인다. 읽기, 쓰기와 같은 영역이다.
     * 내기순환 영역이 없는 차에서는 붙이지 않고 신호도 내지 않는다(앱은 "이 차에서는 내외기 상태를 읽을 수 없어요"를 보여 준다).
     * 풍량 리스너를 못 붙였으면(영역은 있는데 등록 실패) 10초 폴링으로 내려간다.
     */
    private suspend fun syncHvacListeners(v: Vehicle) = hvacSync.withLock {
        val first = recirculationSupported == null
        val areas = calls.resolveHvacAreas()
        if (first) for (a in areas.values) onInfo?.invoke("${a.prop.label} 영역: ${HvacAreaRule.describe(a)}")
        val hvac = v.getHVAC()

        val recirc = calls.hvacSeatArea(HvacProp.RECIRC)
        if (recirculationSupported != (recirc != null)) { recirculationSupported = recirc != null; onHvacResolved?.invoke(recirc != null) }
        if (moveListener(HvacProp.RECIRC, recirc) { hvac.unregisterHvacRecirculationState(it, recircListener) } && recirc != null) {
            try { hvac.registerHvacRecirculationState(recirc, recircListener); hvacRegistered[HvacProp.RECIRC] = recirc } catch (e: Throwable) {
                Log.w(TAG, "recirculation register failed: ${PleosCalls.describe(e)}"); noteFailure("recirculation", e)
            }
            (calls.getRecirculation() as? Outcome.Ok)?.let { bus.emit(RecirculationChanged(now(), it.value)) }
        }
        // 차의 자동 내외기 제어. 없는 차도 있으니 실패해도 등록 실패 목록에는 넣지 않고, 그때는 신호를 내지 않는다
        val auto = calls.hvacSeatArea(HvacProp.AUTO_RECIRC)
        if (moveListener(HvacProp.AUTO_RECIRC, auto) { hvac.unregisterHvacAutoRecirculationState(it, autoRecircListener) } && auto != null) {
            try { hvac.registerHvacAutoRecirculationState(auto, autoRecircListener); hvacRegistered[HvacProp.AUTO_RECIRC] = auto } catch (e: Throwable) {
                Log.w(TAG, "autoRecirculation register failed: ${PleosCalls.describe(e)}"); onInfo?.invoke("자동 내외기 상태 리스너 없음: ${PleosCalls.describe(e)}")
            }
            (calls.getAutoRecirculation() as? Outcome.Ok)?.let { publishAutoClimate(it.value) }
        }
        val fan = calls.hvacSeatArea(HvacProp.FAN)
        if (moveListener(HvacProp.FAN, fan) { hvac.unregisterHvacFanSpeed(it, fanListener) } && fan != null) {
            fanMax = calls.fanRange().second
            try { hvac.registerHvacFanSpeed(fan, fanListener); hvacRegistered[HvacProp.FAN] = fan; fanPollJob?.cancel(); fanPollJob = null } catch (e: Throwable) {
                Log.w(TAG, "fanSpeed register failed: ${PleosCalls.describe(e)}"); noteFailure("fanSpeed", e)
                startFanPolling()
            }
        }
        // 공조 전원: 못 붙여도 숨 모델은 돈다(전원을 모르면 켜진 것으로 본다). 퍼미션 실패 목록에는 넣지 않는다
        val power = calls.hvacSeatArea(HvacProp.POWER)
        if (power == null && first) onInfo?.invoke("공조 전원 영역 없음(전원은 켜진 것으로 봄)")
        if (moveListener(HvacProp.POWER, power) { hvac.unregisterHvacPowerState(it, powerListener) } && power != null) {
            try { hvac.registerHvacPowerState(power, powerListener); hvacRegistered[HvacProp.POWER] = power } catch (e: Throwable) {
                Log.w(TAG, "hvacPower register failed: ${PleosCalls.describe(e)}"); onInfo?.invoke("공조 전원 리스너 없음(전원은 켜진 것으로 봄): ${PleosCalls.describe(e)}")
            }
        }
        (calls.getHvacPower() as? Outcome.Ok)?.let { publishHvacPower(it.value) }
        (calls.getFanSpeed() as? Outcome.Ok)?.let { publishFan(it.value) }
    }

    private var pending: Vehicle? = null

    /** 초기화만 시작하고 true 를 돌려준다. 실제 연결 여부는 onProbe 로 온다(약 1.5~5.5초 뒤). */
    override fun start(): Boolean = try {
        val v = Vehicle(context)
        v.initialize()
        pending = v
        status = Status.PROBING
        Log.i(TAG, "Pleos Vehicle SDK initializing, probing in ${PROBE_DELAY_MS}ms")
        scope.launch(Dispatchers.Main.immediate) { probe(v) }
        true
    } catch (e: Throwable) {
        // SDK 클래스 로딩 자체가 안 되는 기기
        Log.w(TAG, "Pleos Vehicle SDK unavailable: ${e.message}")
        status = Status.FAILED
        onProbe?.invoke(false, "SDK 초기화 예외: ${e.message}")
        false
    }

    /** 1.5초 뒤 getCurrentGearSelectionState 한 번. 4초 안에 답이 오면 연결 */
    private suspend fun probe(v: Vehicle) {
        delay(PROBE_DELAY_MS)
        val r: String? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    v.getGear().getCurrentGearSelectionState(
                        { g -> if (cont.isActive) cont.resume("gear=${g?.name}") },
                        { e -> if (cont.isActive) cont.resume("fail:" + PleosCalls.describe(e)) },
                    )
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resume("fail:" + PleosCalls.describe(e))
                }
            }
        }
        if (pending !== v) return
        when {
            r == null -> {
                status = Status.FAILED
                Log.w(TAG, "probe timeout(${PROBE_TIMEOUT_MS}ms): Pleos 차량 서비스 없음, 모의 모드")
                runCatching { v.release() }
                pending = null
                onProbe?.invoke(false, "getCurrentGearSelectionState 응답 없음(${PROBE_TIMEOUT_MS / 1000}초)")
            }
            r.startsWith("fail:") -> {
                // 서비스는 있는데 호출이 실패(퍼미션 등). 서비스가 답했으니 연결로 보고, 사유는 기록한다
                val detail = r.removePrefix("fail:")
                status = Status.CONNECTED
                vehicle = v
                noteFailure("gear.get", detail)
                Log.w(TAG, "probe answered with failure: $detail (연결로 간주)")
                register(v)
                onProbe?.invoke(true, "연결(기어 읽기 실패: $detail)")
            }
            else -> {
                status = Status.CONNECTED
                vehicle = v
                Log.i(TAG, "probe ok: $r")
                register(v)
                onProbe?.invoke(true, r)
            }
        }
    }

    private fun noteFailure(what: String, e: Throwable) = noteFailure(what, PleosCalls.describe(e))
    private fun noteFailure(what: String, detail: String) {
        val line = "$what: $detail"
        if (line !in permissionFailures) permissionFailures = permissionFailures + line
        onInfo?.invoke("등록 실패 $line")
    }

    private fun register(v: Vehicle) {
        // 도메인별로 따로 try. 하나가 퍼미션 오류여도 나머지는 살린다. 실패는 permissionFailures 로 모은다.
        fun safely(what: String, block: () -> Unit) = try { block() } catch (e: Throwable) {
            Log.w(TAG, "$what register failed: ${PleosCalls.describe(e)}"); noteFailure(what, e)
        }
        safely("gear") {
            v.getGear().registerGearSelection(gearListener)
            v.getGear().getCurrentGearSelectionState({ g -> if (g != null) publishGear(g) }, fail("gear.get"))
        }
        safely("brake") { v.getBrake().registerBrakePedalStatus(brakeListener) }
        safely("turnSignal") { v.getTurnSignal().registerTurnSignalState(turnListener) }
        safely("wiper") { v.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener) }
        safely("lowBeam") {
            v.getLight().registerLowBeamLightState(lowBeamListener)
            // 출발 전에 이미 켜져 있었는지도 알린다(엔진은 출발 때 켜져 있던 하향등을 바로 야간으로 본다)
            v.getLight().getCurrentLowBeamLightState({ s -> if (s != null) bus.emit(LowBeamChanged(now(), s == LightState.ON)) }, fail("lowBeam.get"))
        }
        safely("telltales(PRIVATE_CAR_SAFETY)") {
            v.getSafety().registerTelltales(telltaleListener)
            v.getSafety().getTelltalesInfo({ t -> if (t != null) publishTelltales(t) }, { e -> fail("telltales.get")(e); noteFailure("telltales.get", e) })
        }
        safely("tripTime") {
            v.getOdometer().registerTripCurrentDrivingTime(tripTimeListener)
            v.getOdometer().getTripCurrentDrivingTime({ raw -> if (raw != null) publishTripTime(raw) }, { e -> onTripTimeFailure(PleosCalls.describe(e)) })
        }
        registerCabinInputs(v) { what, block -> safely(what, block) }
        Log.i(TAG, "Pleos Vehicle SDK listeners registered, failures=${permissionFailures.size}")
    }

    override fun stop() {
        occupantJob?.cancel(); occupantJob = null
        fanPollJob?.cancel(); fanPollJob = null
        steerRepeatJob?.cancel(); steerRepeatJob = null
        val v = vehicle ?: pending ?: return
        hvacRegistered[HvacProp.FAN]?.let { runCatching { v.getHVAC().unregisterHvacFanSpeed(it, fanListener) } }
        hvacRegistered[HvacProp.POWER]?.let { runCatching { v.getHVAC().unregisterHvacPowerState(it, powerListener) } }
        hvacRegistered[HvacProp.AUTO_RECIRC]?.let { runCatching { v.getHVAC().unregisterHvacAutoRecirculationState(it, autoRecircListener) } }
        hvacRegistered[HvacProp.RECIRC]?.let { runCatching { v.getHVAC().unregisterHvacRecirculationState(it, recircListener) } }
        hvacRegistered.clear()
        runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        for (w in PleosCalls.SIDE_WINDOWS) runCatching { v.getWindow().unregisterWindowPosition(WindowArea(w), windowListeners.getValue(w)) }
        runCatching {
            v.getGear().unregisterGearSelection(gearListener)
            v.getBrake().unregisterBrakePedalStatus(brakeListener)
            v.getTurnSignal().unregisterTurnSignalState(turnListener)
            v.getWiper().unregisterWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener)
            v.getLight().unregisterLowBeamLightState(lowBeamListener)
            v.getSafety().unregisterTelltales(telltaleListener)
            if (!tripTimeUnsupported) v.getOdometer().unregisterTripCurrentDrivingTime(tripTimeListener)
        }
        runCatching { v.release() }
        vehicle = null; pending = null
        status = Status.NONE
    }

    /**
     * 트립 주행시간을 분으로 정규화한다. 단위가 문서에 없어서("분으로 추정") 첫 두 샘플의 증가 속도로 판정한다.
     * 실제 경과 초 대비 raw 증가량이 0.5 이상이면 초 단위, 아니면 분 단위.
     * 판정 전에는 발행하지 않는다(초 단위 raw 를 분으로 보내면 "트립 300분" 같은 오류가 난다). 그동안은 엔진의 앱 타이머가 쓰인다.
     * 검증 패널의 "트립시간 raw 읽기" 로 사용자가 단위를 확정할 수도 있다(forceTripUnit).
     */
    private fun publishTripTime(raw: Int) {
        val t = now()
        val unit = tripUnitIsSeconds
        if (unit == null) {
            val first = firstTripRaw
            if (first == null) {
                firstTripRaw = raw; firstTripRawAt = t
                onInfo?.invoke("트립시간 raw=$raw (단위 판정 전, 발행 안 함)")
                return
            }
            if (raw == first || t - firstTripRawAt < 5_000) return
            val elapsedSec = (t - firstTripRawAt) / 1000.0
            val ratio = (raw - first) / elapsedSec
            val isSec = ratio >= 0.5
            tripUnitIsSeconds = isSec
            onInfo?.invoke("트립시간 단위 판정: raw +${raw - first} / ${"%.0f".format(elapsedSec)}s → ${if (isSec) "초" else "분"}")
            Log.i(TAG, "tripTime unit: ${if (isSec) "seconds" else "minutes"} (ratio=$ratio)")
            bus.emit(TripTimeUpdate(t, if (isSec) raw / 60 else raw))
            return
        }
        bus.emit(TripTimeUpdate(t, if (unit) raw / 60 else raw))
    }

    /** 경고등 전체 스냅샷을 받아서, 켜짐/꺼짐/점멸이 바뀐 것만 신호로 보낸다. BLINK 면 blink=true */
    private fun publishTelltales(t: Telltales) {
        val nowOn = mutableMapOf<Telltale, Boolean>()
        for (d in t.telltales) {
            if (d.state == TelltaleState.OFF) continue
            val type = d.type.toEngine() ?: continue
            val blink = d.state == TelltaleState.BLINK
            nowOn[type] = (nowOn[type] ?: false) || blink
        }
        val ts = now()
        for ((type, blink) in nowOn) {
            val prev = activeTelltales[type]
            if (prev == null || prev != blink) bus.emit(TelltaleChanged(ts, type, on = true, blink = blink))
        }
        for (type in activeTelltales.keys - nowOn.keys) bus.emit(TelltaleChanged(ts, type, on = false))
        activeTelltales.clear(); activeTelltales += nowOn
    }

    private fun VehicleGear.toEngine() = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // DRIVE, 1~9단 모두 주행
    }

    private fun TurnSignalValue.toEngine() = when (this) {
        TurnSignalValue.LEFT -> TurnSignalState.LEFT
        TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
        else -> TurnSignalState.OFF
    }

    /** 숨에 의미 있는 경고등만 매핑. DRIVER_ATTENTION 과 LKA/LFA(→LKA) 가 핵심. */
    private fun TelltaleType.toEngine(): Telltale? = when (this) {
        TelltaleType.PARKING_BRAKE, TelltaleType.ELECTRONIC_PARKING_BRAKE -> Telltale.PARKING_BRAKE
        TelltaleType.SEAT_BELT -> Telltale.SEAT_BELT
        TelltaleType.FCA -> Telltale.FCA
        TelltaleType.LKA, TelltaleType.LFA -> Telltale.LKA
        TelltaleType.OVER_SPEED -> Telltale.OVER_SPEED
        TelltaleType.ICY_ROAD -> Telltale.ICY_ROAD
        TelltaleType.DRIVER_ATTENTION -> Telltale.DRIVER_ATTENTION
        TelltaleType.DOOR_OPEN -> Telltale.DOOR_OPEN
        else -> null
    }

    companion object {
        const val TAG = "Kkaeugi.Vehicle"
        const val PROBE_DELAY_MS = 1_500L
        const val PROBE_TIMEOUT_MS = 4_000L
        const val OCCUPANT_PERIOD_MS = 5_000L
        /** 주행 중 마지막 핸들 각도를 다시 보내는 간격과 그걸 확인하는 주기 */
        const val STEER_REPEAT_MS = 1_000L
        const val STEER_REPEAT_CHECK_MS = 250L
        const val FAN_POLL_MS = 10_000L
    }
}

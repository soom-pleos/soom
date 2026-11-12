package ai.kkaeugi.app.signals

import ai.kkaeugi.app.vehicle.MockCar
import ai.kkaeugi.engine.AutoClimateChanged
import ai.kkaeugi.engine.BrakePedal
import ai.kkaeugi.engine.DestinationEta
import ai.kkaeugi.engine.FrontPassengerChanged
import ai.kkaeugi.engine.CautionKind
import ai.kkaeugi.engine.OccupantsChanged
import ai.kkaeugi.engine.OutdoorAir
import ai.kkaeugi.engine.SteeringAngle
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.GearChanged
import ai.kkaeugi.engine.LocalClock
import ai.kkaeugi.engine.LocationUpdate
import ai.kkaeugi.engine.LowBeamChanged
import ai.kkaeugi.engine.NavCaution
import ai.kkaeugi.engine.NavTurnAhead
import ai.kkaeugi.engine.Telltale
import ai.kkaeugi.engine.TelltaleChanged
import ai.kkaeugi.engine.TripTimeUpdate
import ai.kkaeugi.engine.TurnDir
import ai.kkaeugi.engine.TurnSignalChanged
import ai.kkaeugi.engine.TurnSignalState
import ai.kkaeugi.engine.WiperChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 데모 콘솔. Pleos 에뮬레이터가 없는 곳(일반 에뮬레이터, 폰) 또는 연결 판정에 실패한 곳에서 같은 신호를 손으로 넣는다.
 * Pleos 에뮬레이터에서는 버추얼 콘솔이 이 역할을 하므로, 이 클래스는 신호를 "만드는" 쪽일 뿐 판단은 전부 엔진에 있다.
 *
 * 공조·창문 상태는 모의 차량(car)이 들고 있다. 콘솔 버튼은 운전자가 직접 누른 것처럼, 모의 실행(VehicleActuator)은 SDK 제어처럼
 * 같은 차량의 값을 바꾸고, 값이 바뀌면 실제 리스너처럼 신호가 나간다.
 */
class MockSignalSource(
    private val bus: SignalBus,
    private val scope: CoroutineScope,
    windowInverted: () -> Boolean = { false },
    onControlCall: (String) -> Unit = {},
    /** 실제 시각(자정부터 흐른 분). 시연용 시각을 걷을 때 이 값으로 돌아간다 */
    private val realMinuteOfDay: () -> Int = { java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) } },
) : SignalSource {
    override val name = "데모 콘솔"
    /** 모의 차량. VehicleActuator 가 Pleos 없는 기기에서 이 차량을 대신 바꾼다 */
    val car = MockCar(emit = { bus.emit(it) }, now = { MonoClock.now() }, windowInverted = windowInverted, onControlCall = onControlCall)
    private var script: Job? = null
    private var tripMinutes = 0

    override fun start() = true
    override fun stop() { script?.cancel(); steerJob?.cancel() }

    private fun now() = MonoClock.now()

    fun gear(g: Gear) = bus.emit(GearChanged(now(), g))
    fun brake(pressed: Boolean) = bus.emit(BrakePedal(now(), pressed))
    fun turnSignal(s: TurnSignalState) = bus.emit(TurnSignalChanged(now(), s))
    fun wiper(on: Boolean) = bus.emit(WiperChanged(now(), on))
    fun lowBeam(on: Boolean) = bus.emit(LowBeamChanged(now(), on))
    fun telltale(t: Telltale, on: Boolean, blink: Boolean = false) = bus.emit(TelltaleChanged(now(), t, on, blink))
    fun recirculation(on: Boolean) = car.userRecirculation(on)
    fun turnAhead(dir: TurnDir, m: Double) = bus.emit(NavTurnAhead(now(), dir, m))
    fun caution(kind: CautionKind, m: Int) = bus.emit(NavCaution(now(), kind, m))
    fun location(lat: Double, lng: Double, kph: Double?) = bus.emit(LocationUpdate(now(), lat, lng, kph))

    // ---- 계약 v4 신호: 도착 예정, 조수석, 차의 자동 내외기 ----
    /** 마지막으로 넣은 남은 분. 새 주행마다 다시 알린다 */
    var etaMinutes: Int? = null
        private set
    /** 목적지까지 남은 분. null = 목적지 없음 */
    fun eta(minutes: Int?) { etaMinutes = minutes; bus.emit(DestinationEta(now(), minutes)) }
    fun republishEta() = bus.emit(DestinationEta(now(), etaMinutes))
    fun frontPassenger(present: Boolean) = bus.emit(FrontPassengerChanged(now(), present))
    /** 마지막으로 넣은 차 자동 공조 상태 */
    var autoClimateOn = false
        private set
    fun autoClimate(on: Boolean) { autoClimateOn = on; bus.emit(AutoClimateChanged(now(), on)) }

    // ---- 숨 설계(v0.7.0): 바깥 미세먼지, 지금 시각 ----
    /** 바깥 PM2.5(µg/m³)를 직접 넣는다. null = 값 없음. 시연 시나리오가 쓴다(설정의 수동 선택은 OutdoorAirSource 가 맡는다) */
    fun outdoorPm(pm25: Double?) = bus.emit(OutdoorAir(now(), pm25, DEMO_PM_SOURCE))
    /** 시연용 시각(자정부터 흐른 분). null 이면 실제 시각. 앱이 1분마다 시각을 다시 알릴 때도 이 값을 쓴다 */
    var clockOverride: Int? = null
        private set
    fun clock(minuteOfDay: Int?) { clockOverride = minuteOfDay; bus.emit(LocalClock(now(), minuteOfDay ?: realMinuteOfDay())) }

    // ---- 자리별 값과 프로필(v0.7.3). 시연 시나리오와 데모 콘솔이 쓴다 ----
    /** 자리별 탑승 값(운전석, 조수석, 뒷줄 왼쪽, 가운데, 오른쪽). null = 자리별 값 없음 */
    fun seats(values: List<Boolean?>?) = bus.emit(ai.kkaeugi.engine.SeatOccupancyChanged(now(), values))
    fun seatProfiles(profiles: List<ai.kkaeugi.engine.SeatProfile>) = bus.emit(ai.kkaeugi.engine.SeatProfilesChanged(now(), profiles))
    /** 문 열림(문 열림 경고등으로 넣는다. 실제 차와 같은 경로) */
    fun door(open: Boolean) = telltale(Telltale.DOOR_OPEN, open)

    // ---- 숨 모델 입력 ----
    fun occupants(n: Int) = bus.emit(OccupantsChanged(now(), n))
    fun fanSpeed(level: Int, max: Int = 8) = car.userFan(level, max)
    /** 공조 전원. 꺼지면 풍량 단계가 남아 있어도 풍량 0 으로 나간다 */
    fun hvacPower(on: Boolean) = car.userHvacPower(on)
    /** 앱 시작 직후: 혼자, 풍량 3/8, 창문 닫힘. 풍량을 안 보내면 엔진이 외기 유입을 낮게 잡아 추정이 높게 나온다 */
    fun emitCabinDefaults(occupants: Int = 1) { occupants(occupants); fanSpeed(3, 8); windowOpen(0) }
    fun windowOpen(percent: Int) = car.userWindowPercent(percent)

    private var steerJob: Job? = null
    /**
     * 핸들 급보정 패턴을 times 회 만든다: quietSec 초 넘게 가만히(0.2초마다 같은 각도) 있다가 한 번에 jerkDeg 만큼 꺾는다.
     * 엔진은 "오래 무조작 뒤 급보정"만 센다. 꺾은 각도에서 다시 가만히 있다가 되돌리는 것도 1회다.
     */
    fun steeringJerks(times: Int = 3, quietSec: Int, jerkDeg: Float = 12f, onStep: (String) -> Unit = {}) {
        steerJob?.cancel()
        steerJob = scope.launch {
            var angle = 0f
            repeat(times) { i ->
                val holdUntil = now() + quietSec * 1000L + 400L
                while (now() < holdUntil) { bus.emit(SteeringAngle(now(), angle)); delay(200) }
                angle = if (angle == 0f) jerkDeg else 0f
                bus.emit(SteeringAngle(now(), angle))
                onStep("핸들 급보정 ${i + 1}/$times")
                delay(200)
            }
        }
    }

    /** 트립 시간을 분 단위로 더한다(Odometer 가 없는 기기에서 "2시간 넘게 운전" 조건을 만들기 위해) */
    fun addTripMinutes(m: Int) { tripMinutes += m; bus.emit(TripTimeUpdate(now(), tripMinutes)) }
    fun resetTripMinutes() { tripMinutes = 0 }

    val scriptRunning: Boolean get() = script?.isActive == true
    fun cancelScript() { script?.cancel(); script = null }

    /**
     * 시연 시나리오(DemoScenario, 데모 속도 기준. 길이는 DemoScenario.LENGTH_LABEL)를 돌린다. 단계와 순서는 DemoScenario.steps 에 있다.
     * 제안에 대한 답은 사용자가 화면에서 누른다. 다 읽고 6초가 지나도록 안 누르면 시나리오가 onAnswerYes 로 대신 누른다.
     * 쉼터 도착 신호는 RestStopTracker 가 내므로 onRestWithin 콜백으로 넘긴다. view 는 지금 화면 상태(제안, 실행 바).
     */
    fun runDemoScript(
        ventilateSec: Int, drowsySec: Int, restSec: Int,
        /** 돌릴 단계. 기본은 v0.7.3 의 가족 시나리오다 */
        steps: List<DemoScenario.Step> = DemoScenario.familySteps(ventilateSec),
        view: () -> DemoScenario.View,
        onRestWithin: () -> Unit,
        onAnswerYes: () -> Unit,
        /** 끝까지 돌았을 때(중단은 cancelScript 를 부른 쪽이 안다) */
        onFinished: () -> Unit = {},
        onStep: (String) -> Unit = {},
    ) {
        script?.cancel()
        tripMinutes = 0
        val hands = object : DemoScenario.Hands {
            override fun gear(g: Gear) { this@MockSignalSource.gear(g) }
            override fun recirculation(on: Boolean) = this@MockSignalSource.recirculation(on)
            override fun occupants(n: Int) { this@MockSignalSource.occupants(n) }
            override fun fanSpeed(level: Int, max: Int) = this@MockSignalSource.fanSpeed(level, max)
            override fun windowOpen(percent: Int) = this@MockSignalSource.windowOpen(percent)
            override fun lowBeam(on: Boolean) { this@MockSignalSource.lowBeam(on) }
            override fun addTripMinutes(m: Int) = this@MockSignalSource.addTripMinutes(m)
            override fun driverAttention(on: Boolean) { telltale(Telltale.DRIVER_ATTENTION, on) }
            override fun restWithin() = onRestWithin()
            override fun answerYes() = onAnswerYes()
            override fun frontPassenger(present: Boolean) { this@MockSignalSource.frontPassenger(present) }
            override fun eta(minutes: Int?) { this@MockSignalSource.eta(minutes) }
            override fun autoClimate(on: Boolean) { this@MockSignalSource.autoClimate(on) }
            override fun outdoorPm(pm25: Double?) { this@MockSignalSource.outdoorPm(pm25) }
            override fun localClock(minuteOfDay: Int?) { this@MockSignalSource.clock(minuteOfDay) }
            override fun seats(values: List<Boolean?>?) { this@MockSignalSource.seats(values) }
            override fun seatProfiles(profiles: List<ai.kkaeugi.engine.SeatProfile>) { this@MockSignalSource.seatProfiles(profiles) }
        }
        val runner = DemoScenario.Runner(steps, hands)
        script = scope.launch {
            while (isActive && !runner.finished) {
                runner.tick(now(), view())?.let(onStep)
                delay(SCRIPT_TICK_MS)
            }
            if (runner.finished) onFinished()
        }
    }

    companion object {
        const val SCRIPT_TICK_MS = 200L
        const val DEMO_PM_SOURCE = "시연 시나리오"
    }
}

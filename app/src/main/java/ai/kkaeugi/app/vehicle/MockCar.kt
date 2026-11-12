package ai.kkaeugi.app.vehicle

import ai.kkaeugi.engine.FanSpeedChanged
import ai.kkaeugi.engine.RecirculationChanged
import ai.kkaeugi.engine.Signal
import ai.kkaeugi.engine.WindowOpenChanged

/**
 * 모의 차량(순수 로직, JVM 테스트 대상). Pleos 가 없는 기기에서 공조·창문 상태를 들고 있다가, 값이 바뀌면 실제 차의 리스너처럼 신호를 낸다.
 *
 * 쓰는 곳이 둘이다.
 *  - 데모 콘솔(사람 손): user* 함수. 운전자가 공조 버튼을 누른 것과 같다.
 *  - VehicleActuator(모의 실행): VehicleControls 구현. 환기하면 내외기·풍량이 실제로 바뀌고, 원복하면 돌아온다.
 * 그래서 모의 모드에서도 화면의 내외기 상태와 숨 모델 입력(풍량, 창문)이 실제 차와 같은 순서로 움직인다.
 *
 * 창문 위치는 0(닫힘)~100(다 열림). "창문 방향 반전" 설정이 켜져 있으면 실제 차와 같이 반대로 읽고 쓴다.
 * 공조 전원이 꺼져 있으면 풍량 단계가 남아 있어도 풍량 0 으로 알린다.
 */
class MockCar(
    private val emit: (Signal) -> Unit,
    private val now: () -> Long,
    private val windowInverted: () -> Boolean = { false },
    /** 제어 호출 이름("setHvacRecirculation(false)"). 증거 띠 표시용 */
    private val onControlCall: (String) -> Unit = {},
) : VehicleControls {
    var recirculating = false
        private set
    var fanLevel = 3
        private set
    var fanMax = 8
        private set
    var hvacPower = true
        private set
    var temperatureC = 24f
        private set
    /** 창문 위치(raw). 범위는 WINDOW_MIN~WINDOW_MAX */
    var windowPosition = 0
        private set

    val windowPercent: Int get() = WindowScale.percentOf(windowPosition, WINDOW_MIN, WINDOW_MAX, windowInverted())

    private fun emitFan() = emit(FanSpeedChanged(now(), if (hvacPower) fanLevel else 0, fanMax))
    private fun emitWindow() = emit(WindowOpenChanged(now(), windowPercent))

    // ---- 데모 콘솔(운전자가 직접 누른 것) ----
    fun userRecirculation(on: Boolean) { recirculating = on; emit(RecirculationChanged(now(), on)) }
    fun userFan(level: Int, max: Int = fanMax) { fanMax = max.coerceAtLeast(1); fanLevel = level.coerceIn(0, fanMax); emitFan() }
    fun userHvacPower(on: Boolean) { hvacPower = on; emitFan() }
    fun userWindowPercent(percent: Int) { windowPosition = WindowScale.positionOf(percent, WINDOW_MIN, WINDOW_MAX, windowInverted()); emitWindow() }
    /** 설정(창문 방향 반전)이 바뀐 뒤 같은 위치를 새 방향으로 다시 알린다 */
    fun republishWindow() = emitWindow()

    // ---- VehicleControls(모의 실행) ----
    override suspend fun getRecirculation(): Outcome<Boolean> = Outcome.Ok(recirculating)
    override suspend fun setRecirculation(on: Boolean): Outcome<Boolean> {
        onControlCall("setHvacRecirculation($on)")
        if (recirculating != on) { recirculating = on; emit(RecirculationChanged(now(), on)) }
        return Outcome.Ok(on)
    }
    override suspend fun getFanSpeed(): Outcome<Int> = Outcome.Ok(fanLevel)
    override suspend fun setFanSpeed(v: Int): Outcome<Int> {
        onControlCall("setHvacFanSpeed($v)")
        val next = v.coerceIn(0, fanMax)
        if (fanLevel != next) { fanLevel = next; emitFan() }
        return Outcome.Ok(next)
    }
    override suspend fun fanRange(): Pair<Int, Int> = 0 to fanMax
    override suspend fun getTemperature(): Outcome<Float> = Outcome.Ok(temperatureC)
    override suspend fun setTemperature(c: Float): Outcome<Float> {
        onControlCall("setHvacTemperature($c C)")
        temperatureC = c
        return Outcome.Ok(c)
    }
    override suspend fun temperatureRangeC(): Pair<Float, Float> = 16f to 30f
    override suspend fun getWindow(): Outcome<Int> = Outcome.Ok(windowPosition)
    override suspend fun setWindow(pos: Int): Outcome<Int> {
        onControlCall("setWindowPosition($pos)")
        val next = pos.coerceIn(WINDOW_MIN, WINDOW_MAX)
        if (windowPosition != next) { windowPosition = next; emitWindow() }
        return Outcome.Ok(next)
    }
    override suspend fun windowRange(): Pair<Int, Int> = WINDOW_MIN to WINDOW_MAX
    override suspend fun getHvacPower(): Outcome<Boolean> = Outcome.Ok(hvacPower)
    override suspend fun setHvacPower(on: Boolean): Outcome<Boolean> {
        onControlCall("setHvacPower($on)")
        if (hvacPower != on) { hvacPower = on; emitFan() }
        return Outcome.Ok(on)
    }

    companion object {
        const val WINDOW_MIN = 0
        const val WINDOW_MAX = 100
    }
}

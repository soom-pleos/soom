package ai.kkaeugi.engine

enum class TripState { IDLE, DRIVING, ENDED }

/**
 * 주행 세션 상태기계. 시작/종료만 책임진다.
 *
 * 시작: IDLE(또는 ENDED) 에서 기어가 D/R 로 바뀜
 * 종료: 기어가 P 로 돌아오거나, 기어가 N 또는 알 수 없음인 상태에서 주차 브레이크 경고등이 켜진 채 3초가 지남
 *       (D 상태에서 들어온 주차 브레이크 ON 은 무시한다. 에뮬레이터 기본 상태가 ON 이라 D 에서 믿을 수 없다)
 * ENDED 에서 다시 D/R 이 오면 카드를 닫지 않았어도 새 세션을 시작한다(앱이 reset 을 놓쳐도 멈추지 않게).
 *
 * 쉼 모드(경유지 쉼터 안에서 P)는 WakeEngine 이 처리한다. 엔진은 그때 GearChanged 를 여기로 넘기지 않는다.
 */
class TripSession {
    var state: TripState = TripState.IDLE
        private set

    /** 세션 시작 시각(단조 시계 ms). DRIVING/ENDED 에서만 의미 있음 */
    var startedAt: Long = 0L
        private set
    /** 세션 종료 시각. ENDED 에서만 의미 있음 */
    var endedAt: Long = 0L
        private set

    private var gear = Gear.UNKNOWN
    private var parkingBrakeOnSince: Long? = null

    /** 신호를 반영하고, 상태가 바뀌었으면 새 상태를 돌려준다. */
    fun accept(s: Signal): TripState? {
        val before = state
        when (s) {
            is GearChanged -> onGear(s)
            is TelltaleChanged -> if (s.type == Telltale.PARKING_BRAKE) onParkingBrake(s)
            else -> {}
        }
        return if (state != before) state else null
    }

    fun tick(now: Long): TripState? {
        val before = state
        if (state == TripState.DRIVING) {
            parkingBrakeOnSince?.let { if (now - it >= PARKING_BRAKE_HOLD_MS) end(now) }
        }
        return if (state != before) state else null
    }

    /** 대기 상태로 돌아간다(도착 카드를 닫은 뒤). */
    fun reset() {
        state = TripState.IDLE
        parkingBrakeOnSince = null
    }

    /** 세션 경과 시간(ms). IDLE 이면 0. 시계가 뒤로 가도 음수는 내지 않는다 */
    fun elapsedMs(now: Long): Long = when (state) {
        TripState.IDLE -> 0L
        TripState.DRIVING -> (now - startedAt).coerceAtLeast(0L)
        TripState.ENDED -> (endedAt - startedAt).coerceAtLeast(0L)
    }

    private fun onParkingBrake(s: TelltaleChanged) {
        if (!s.on) { parkingBrakeOnSince = null; return }
        // 주차 브레이크 종료 조건은 기어가 N 이거나 알 수 없을 때만
        val gearAllows = gear == Gear.NEUTRAL || gear == Gear.UNKNOWN
        if (gearAllows && parkingBrakeOnSince == null) parkingBrakeOnSince = s.t
    }

    private fun onGear(s: GearChanged) {
        val prev = gear
        gear = s.gear
        val driving = s.gear == Gear.DRIVE || s.gear == Gear.REVERSE
        // D/R 로 가면 주차 브레이크 타이머는 버린다(주행 중 ON 은 믿지 않음)
        if (driving) parkingBrakeOnSince = null
        when (state) {
            TripState.IDLE -> if (driving) start(s.t)
            TripState.DRIVING -> if (s.gear == Gear.PARK && prev != Gear.PARK) end(s.t)
            TripState.ENDED -> if (driving) start(s.t)
        }
    }

    private fun start(t: Long) {
        state = TripState.DRIVING
        startedAt = t
        parkingBrakeOnSince = null
    }

    private fun end(t: Long) {
        state = TripState.ENDED
        endedAt = t
    }

    companion object {
        const val PARKING_BRAKE_HOLD_MS = 3_000L
    }
}

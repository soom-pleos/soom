package ai.kkaeugi.engine

/**
 * 차량·내비 신호를 엔진이 이해하는 공통 형태로 정규화한 것.
 * 앱 모듈의 PleosSignalSource(실제 SDK)와 MockSignalSource(데모 콘솔)가 같은 타입을 만든다.
 * 시간 t 는 단조 시계(앱에서는 SystemClock.elapsedRealtime) 기준 ms. 엔진의 tick(now) 와 같은 시계를 쓴다.
 */
sealed interface Signal {
    val t: Long
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }
enum class TurnSignalState { OFF, LEFT, RIGHT }
enum class TurnDir { LEFT, RIGHT, UTURN, STRAIGHT, ENTER_IC, EXIT_IC, OTHER }
enum class CautionKind { SCHOOL_ZONE, SPEED_BUMP, ACCIDENT_PRONE, RAILROAD, FOG, OTHER }

/** 코칭에 쓰는 경고등. 차량에는 100개가 넘지만 초보 코칭에 의미 있는 것만 고른다. */
enum class Telltale {
    PARKING_BRAKE, SEAT_BELT, FCA, LKA, OVER_SPEED, ICY_ROAD, DRIVER_ATTENTION, DOOR_OPEN, OTHER
}

data class GearChanged(override val t: Long, val gear: Gear) : Signal
data class BrakePedal(override val t: Long, val pressed: Boolean) : Signal
data class TurnSignalChanged(override val t: Long, val state: TurnSignalState) : Signal
data class WiperChanged(override val t: Long, val on: Boolean) : Signal
data class LowBeamChanged(override val t: Long, val on: Boolean) : Signal
/** blink: 경고등이 점멸 중(작동 경고). LKA 는 작동 대기 중 상시 ON 이라 blink 일 때만 경고로 본다. */
data class TelltaleChanged(override val t: Long, val type: Telltale, val on: Boolean, val blink: Boolean = false) : Signal

/** 내비 다음 회전 정보. distanceM 이 줄어들다가 회전을 지나면 새 지시로 바뀐다. */
data class NavTurnAhead(override val t: Long, val turn: TurnDir, val distanceM: Double) : Signal
data class NavCaution(override val t: Long, val kind: CautionKind, val distanceM: Int) : Signal
data class NavSpeedLimit(override val t: Long, val kph: Int) : Signal
data class NavDestinationArrived(override val t: Long) : Signal
data class LocationUpdate(
    override val t: Long,
    val lat: Double,
    val lng: Double,
    val speedKph: Double?,
) : Signal

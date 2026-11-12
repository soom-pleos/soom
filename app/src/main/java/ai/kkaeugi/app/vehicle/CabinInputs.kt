package ai.kkaeugi.app.vehicle

import kotlin.math.roundToInt

/**
 * 탑승 인원 세기(순수 로직, JVM 테스트 대상).
 * 좌석별 점유 값(true/false/null=못 읽음)과 안전벨트 체결 값을 받아 인원과 출처를 돌려준다.
 *  - 좌석마다 따로 본다: 점유 값이 있으면 그 값, 없으면 그 자리의 벨트 값. 둘 다 없으면 그 자리는 세지 않는다
 *    (앞자리는 점유 센서, 뒷자리는 벨트만 답하는 차가 있다. 자리 하나가 답했다고 나머지를 빈자리로 보지 않는다)
 *  - 주행 중 운전석은 값과 무관하게 1 (운전석 점유 센서가 없거나 벨트를 안 맨 경우에도 운전자는 타고 있다)
 *  - 어느 자리도 답하지 않으면 1(운전자만)로 가정
 * 어느 경우든 최소 1.
 */
object OccupantCounter {
    enum class Source { OCCUPANCY, SEAT_BELT, MIXED, ASSUMED }
    /** values: 좌석별로 사람이 있다고 본 값(점유, 없으면 벨트). 어느 값도 못 읽은 자리는 null */
    data class Reading(val count: Int, val source: Source, val detail: String, val values: Map<String, Boolean?> = emptyMap()) {
        /** 좌석 값이 실제로 오고 있는가(점유나 벨트 중 하나라도 답했다). 아니면 수동 입력을 쓴다 */
        val sensorLive: Boolean get() = source != Source.ASSUMED
        /** 그 자리에 사람이 있는가. 값을 못 읽었으면 false(모름은 없음으로 본다) */
        fun occupied(seat: String): Boolean = values[seat] == true
    }

    fun count(
        occupancy: Map<String, Boolean?>,
        belt: Map<String, Boolean?> = emptyMap(),
        driverSeat: String? = null,
        driving: Boolean = false,
    ): Reading {
        val seats = (occupancy.keys + belt.keys).distinct()
        var count = 0
        var usedOccupancy = false
        var usedBelt = false
        val parts = mutableListOf<String>()
        val values = linkedMapOf<String, Boolean?>()
        for (seat in seats) {
            val occ = occupancy[seat]
            val buckled = belt[seat]
            val isDriver = seat == driverSeat
            val value: Boolean? = when {
                isDriver && driving -> true
                occ != null -> { usedOccupancy = true; occ }
                buckled != null -> { usedBelt = true; buckled }
                else -> null
            }
            if (value == true) count++
            values[seat] = value
            val mark = when {
                isDriver && driving -> "O(운전석)"
                occ != null -> if (occ) "O" else "X"
                buckled != null -> if (buckled) "O(벨트)" else "X(벨트)"
                else -> "?"
            }
            parts += "$seat=$mark"
        }
        val source = when {
            usedOccupancy && usedBelt -> Source.MIXED
            usedOccupancy -> Source.OCCUPANCY
            usedBelt -> Source.SEAT_BELT
            else -> Source.ASSUMED
        }
        if (source == Source.ASSUMED && count == 0) return Reading(1, Source.ASSUMED, if (parts.isEmpty()) "좌석 점유·안전벨트 모두 못 읽음" else parts.joinToString(" "), values)
        return Reading(count.coerceAtLeast(1), source, parts.joinToString(" "), values)
    }

    fun label(s: Source) = when (s) {
        Source.OCCUPANCY -> "좌석 점유 값"
        Source.SEAT_BELT -> "안전벨트 체결"
        Source.MIXED -> "좌석 점유 + 안전벨트"
        Source.ASSUMED -> "가정(1명)"
    }
}

/**
 * 좌석 지도에 그릴 자리별 값(순수 로직, JVM 테스트 대상).
 * 차가 준 자리별 값(OccupantCounter.Reading.values)을 다섯 자리 순서로 편다:
 * 운전석(ROW_1_LEFT), 조수석(ROW_1_RIGHT), 뒷줄 왼쪽, 뒷줄 가운데, 뒷줄 오른쪽.
 * 한 칸은 true(탔다), false(비었다), null(그 자리 값을 못 읽었다).
 * 집계 규칙은 OccupantCounter 를 그대로 따른다:
 *  - 주행 중 운전석은 값과 무관하게 탄 것으로 본다(Reading.values 에 이미 그렇게 들어 있다)
 *  - 어느 자리도 탔다고 하지 않으면 인원을 1(운전자)로 올려 잡는 것처럼 지도에서도 운전석을 채운다
 *  - 좌석 값이 하나도 안 오면(sensorLive 아님) null. 화면은 인원 수로 순서대로 채우고 "직접 입력"이라고 적는다
 */
object SeatLayout {
    val ORDER = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")
    const val DRIVER = 0
    const val FRONT_PASSENGER = 1

    fun of(reading: OccupantCounter.Reading): List<Boolean?>? {
        if (!reading.sensorLive) return null
        val seats = ORDER.map { reading.values[it] }.toMutableList()
        if (seats.none { it == true }) seats[DRIVER] = true
        return seats
    }

    /** 자리별 값으로 센 인원. OccupantCounter 와 같이 최소 1 */
    fun count(seats: List<Boolean?>): Int = seats.count { it == true }.coerceAtLeast(1)
}

/**
 * 값을 두 번 연속 같을 때만, 마지막으로 알린 값과 다를 때만 알린다(순수 로직). 인원 디바운서와 같은 규칙을 자리별 값에 쓴다.
 * 인원은 자리별 값에서 나오므로 자리별 값이 알려질 때는 인원도 이미 같은 값으로 알려져 있다
 */
class StableValue<T : Any> {
    private var previous: T? = null
    var published: T? = null
        private set

    fun offer(value: T): T? {
        val stable = previous == value
        previous = value
        if (!stable || published == value) return null
        published = value
        return value
    }
}

/**
 * 탑승 인원 발행 조건(순수 로직): 두 번 연속 같은 값일 때만, 그리고 마지막으로 알린 값과 다를 때만 알린다.
 * 한 번 튄 값(자세를 고쳐 앉음, 벨트를 잠깐 풂)으로 숨 모델이 흔들리지 않게 한다.
 */
class OccupantDebouncer {
    private var previous: Int? = null
    var published: Int? = null
        private set

    /** 새로 읽은 값. 반환: 지금 알릴 값(없으면 null) */
    fun offer(count: Int): Int? {
        val stable = previous == count
        previous = count
        if (!stable || published == count) return null
        published = count
        return count
    }
}

/** 핸들 각도 샘플링(순수 로직): 초당 최대 5개(200ms 간격)만 엔진에 보낸다. 리스너는 훨씬 자주 온다 */
class SteeringSampler(private val minIntervalMs: Long = 200L) {
    private var lastAt: Long? = null
    /** 이 샘플을 보내도 되는가 */
    fun accept(t: Long): Boolean {
        val last = lastAt
        if (last != null && t - last in 0 until minIntervalMs) return false
        lastAt = t
        return true
    }
}

/**
 * 핸들 각도 다시 보내기(순수 로직). 각도 리스너가 값이 바뀔 때만 오는 차에서는 핸들을 가만히 쥐고 있는 동안 표본이 끊긴다.
 * 엔진은 "오래 무조작 뒤 급보정"을 표본 간격으로 가리므로, 주행 중에는 마지막 각도를 periodMs 마다 한 번씩 다시 보낸다.
 */
class SteeringRepeater(private val periodMs: Long = 1_000L) {
    private var lastDeg: Float? = null
    private var lastSentAt = 0L

    /** 엔진에 실제로 보낸 표본 */
    fun onSent(t: Long, deg: Float) { lastDeg = deg; lastSentAt = t }

    /** 리스너가 줬지만 샘플러가 걸러 낸 값도 최신 각도로 기억한다(다시 보낼 때 이 값을 쓴다) */
    fun onObserved(deg: Float) { lastDeg = deg }

    /** 지금 다시 보낼 각도. 주행 중이 아니거나, 아는 각도가 없거나, 마지막 표본이 periodMs 안이면 null */
    fun due(t: Long, driving: Boolean): Float? {
        val deg = lastDeg ?: return null
        if (!driving || t - lastSentAt < periodMs) return null
        return deg
    }
}

/**
 * 창문 위치 ↔ 열림 퍼센트(순수 함수). 기본은 "min = 닫힘, max = 다 열림".
 * 차에 따라 반대일 수 있어(검증 패널의 "창문 위치 읽기"로 확인) inverted 면 "max = 닫힘, min = 다 열림"으로 읽고 쓴다.
 * 창문을 여는 쪽(VehicleActuator)과 열림을 읽는 쪽(WindowOpenTracker)이 같은 함수를 쓴다.
 */
object WindowScale {
    fun percentOf(position: Int, min: Int, max: Int, inverted: Boolean = false): Int {
        if (max <= min) return 0
        val p = ((position - min) * 100.0 / (max - min)).roundToInt().coerceIn(0, 100)
        return if (inverted) 100 - p else p
    }

    fun positionOf(percent: Int, min: Int, max: Int, inverted: Boolean = false): Int {
        if (max <= min) return min
        val p = percent.coerceIn(0, 100).let { if (inverted) 100 - it else it }
        return min + ((max - min) * p / 100.0).roundToInt()
    }
}

/** 창문 열림 정도(순수 로직). 창문별 위치와 범위를 기억해 두고 네 창 중 최댓값(퍼센트)을 낸다 */
class WindowOpenTracker {
    private data class Raw(val position: Int, val min: Int, val max: Int)
    private val raws = mutableMapOf<String, Raw>()
    /** 방향 반전. 바꾸면 기억해 둔 위치로 다시 계산된다 */
    var inverted = false

    /** 한 창문의 위치가 바뀔 때. 반환: 네 창 중 최댓값(0~100) */
    fun update(window: String, position: Int, min: Int, max: Int): Int {
        raws[window] = Raw(position, min, max)
        return maxPercent
    }
    val maxPercent: Int get() = raws.values.maxOfOrNull { WindowScale.percentOf(it.position, it.min, it.max, inverted) } ?: 0

    companion object {
        fun percentOf(position: Int, min: Int, max: Int): Int = WindowScale.percentOf(position, min, max)
    }
}

/**
 * 엔진에 알릴 풍량(순수 로직). 공조 전원이 꺼져 있으면 풍량 단계가 남아 있어도 바람은 안 나오므로 0 으로 알린다.
 * 전원 상태를 모르면(null) 켜진 것으로 본다.
 */
class FanGate {
    var level: Int? = null
    var power: Boolean? = null
    private var last: Pair<Int, Int>? = null

    /** 지금 알릴 (단계, 최대). 아는 풍량이 없거나 마지막으로 알린 값과 같으면 null */
    fun next(max: Int): Pair<Int, Int>? {
        val l = level ?: return null
        val v = (if (power == false) 0 else l) to max
        if (v == last) return null
        last = v
        return v
    }
}

/**
 * 켜짐/꺼짐 값을 두 번 연속 같을 때만 알린다(순수 로직). 조수석 점유처럼 폴링으로 읽는 값이 한 번 튀는 것을 거른다.
 * 처음 값도 두 번 본 뒤에 알린다. 마지막으로 알린 값과 같으면 null
 */
class StableFlag {
    private var previous: Boolean? = null
    private var published: Boolean? = null

    fun offer(value: Boolean): Boolean? {
        val stable = previous == value
        previous = value
        if (!stable || published == value) return null
        published = value
        return value
    }
}

/**
 * 좌석 값이 오고 있는가(순수 로직). 폴링 한 번이 실패했다고 바로 "안 온다"로 보지 않는다.
 *  - 오기 시작한 것은 바로 인정한다
 *  - 끊긴 것은 두 번 연속일 때만 인정한다. 인정되면 호출자는 인원 디바운서와 조수석 플래그를 새로 만들어
 *    값이 돌아왔을 때 같은 값이어도 다시 알리게 한다(그 사이 수동 입력 값으로 덮였기 때문)
 */
class SeatLiveness {
    var live = false
        private set
    private var misses = 0

    /** 이번 폴링에서 좌석 값이 왔는가. 반환: 상태가 바뀌었으면 새 상태, 아니면 null */
    fun offer(sensorLive: Boolean): Boolean? {
        if (sensorLive) {
            misses = 0
            if (live) return null
            live = true
            return true
        }
        if (!live) return null
        misses++
        if (misses < DROP_AFTER) return null
        misses = 0
        live = false
        return false
    }

    companion object { const val DROP_AFTER = 2 }
}

/**
 * 정차 홈의 "탑승 인원" 입력을 어떻게 보여 줄지(순수 로직).
 *  - 좌석 값(점유 또는 벨트)이 실제로 오고 있으면 스테퍼를 숨기고 "좌석 센서 기준 N명"
 *  - 안 오면 스테퍼(1~5). 주행 중에는 바꿀 수 없다(정차 홈에만 있다)
 * 스테퍼 값은 저장한다. 다음 정차 때 묻지 않고 "N명 그대로" 칩만 보여 준다
 */
object OccupantInput {
    const val MIN = 1
    const val MAX = 5

    enum class Mode { SENSOR, STEPPER, LOCKED }

    fun mode(sensorLive: Boolean, driving: Boolean): Mode = when {
        sensorLive -> Mode.SENSOR
        driving -> Mode.LOCKED
        else -> Mode.STEPPER
    }

    fun clamp(n: Int): Int = n.coerceIn(MIN, MAX)

    /** 스테퍼 한 칸. 주행 중이거나 좌석 값이 오는 중이면 그대로 */
    fun step(current: Int, delta: Int, sensorLive: Boolean, driving: Boolean): Int =
        if (mode(sensorLive, driving) == Mode.STEPPER) clamp(current + delta) else current

    fun sensorLabel(count: Int): String = "좌석 센서 기준 ${count}명"
    fun carriedChip(count: Int): String = "${count}명 그대로"

    /**
     * 수동 입력만으로는 누가 어느 자리에 탔는지 모른다. 2명 이상이어도 조수석에 사람이 있다고 보지 않는다(모름 → false).
     * 조수석은 좌석 값이 올 때만 그 값으로 판단한다
     */
    fun frontPassenger(sensorLive: Boolean, frontSeatOccupied: Boolean): Boolean = sensorLive && frontSeatOccupied
}

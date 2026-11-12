package ai.kkaeugi.engine

/**
 * 앉은 시간(v0.7.3). 자리마다 지금 이어지고 있는 연속 착석 시간을 센다. 순수 Kotlin.
 * 몸을 재는 것이 아니다. 좌석 값과 기어, 문 신호로 "앉아 있었다 / 일어섰다"만 가린다.
 *
 * 일어선 것으로 보는 때(둘 중 하나):
 *  1) 그 자리가 emptyResetMs(기본 2분) 이상 비어 있었다(내렸다 탔다). 그보다 짧게 빈 것은 자세를 고친 것으로 보고 이어 센다
 *  2) 차가 기어 P 로 오래 서 있었다(standAll). 언제 부를지는 엔진이 정한다:
 *     기어 P 로 sitParkResetMinutes(5분) 이상, 문 신호가 있는 차면 그동안 문이 열렸다 닫혔을 때만
 * 시계는 엔진과 같은 단조 시계(ms)다. 뒤로 가도 음수는 내지 않는다.
 */
class SitClock(private val seatCount: Int = Seats.COUNT) {
    private val seatedSince = arrayOfNulls<Long>(seatCount)
    /** 앉아 있던 자리가 비기 시작한 시각. 다시 앉거나 emptyResetMs 가 지나면 지운다 */
    private val emptySince = arrayOfNulls<Long>(seatCount)
    /** 마지막으로 일어선 시각. 그보다 앞으로는 앉은 시간을 당기지 않는다 */
    private val stoodAt = arrayOfNulls<Long>(seatCount)

    /** 그 자리에 지금 사람이 있는가(occupied)를 알린다. 값이 그대로여도 매번 불러도 된다 */
    fun update(seat: Int, occupied: Boolean, now: Long, emptyResetMs: Long) {
        if (seat !in 0 until seatCount) return
        if (occupied) {
            val gapFrom = emptySince[seat]
            when {
                seatedSince[seat] == null -> seatedSince[seat] = now
                // 오래 비었다 다시 앉았다: 내렸다 탄 것이다. 지금부터 다시 센다
                gapFrom != null && now - gapFrom >= emptyResetMs -> { stoodAt[seat] = gapFrom; seatedSince[seat] = now }
            }
            emptySince[seat] = null
        } else if (seatedSince[seat] != null) {
            val gapFrom = emptySince[seat]
            if (gapFrom == null) emptySince[seat] = now
            else if (now - gapFrom >= emptyResetMs) { stoodAt[seat] = gapFrom; seatedSince[seat] = null; emptySince[seat] = null }
        }
    }

    /** 모든 자리가 일어섰다(기어 P 로 오래 서 있었다). 탄 자리는 다음 update 에서 지금부터 다시 센다 */
    fun standAll(now: Long) {
        for (i in 0 until seatCount) {
            if (seatedSince[i] != null) stoodAt[i] = now
            seatedSince[i] = null
            emptySince[i] = null
        }
    }

    /**
     * 앉기 시작한 시각을 since 까지 당긴다(더 오래 앉아 있었다고 본다). 앉아 있는 자리에만, 마지막으로 일어선 시각보다 앞으로는 당기지 않는다.
     * 앱이 늦게 켜졌거나 다시 떴을 때 차가 알려 준 주행 시간으로 운전석의 아래 값을 주는 데 쓴다
     */
    fun backdate(seat: Int, since: Long) {
        if (seat !in 0 until seatCount) return
        val current = seatedSince[seat] ?: return
        val floor = maxOf(since, stoodAt[seat] ?: Long.MIN_VALUE)
        if (floor < current) seatedSince[seat] = floor
    }

    /** 지금 이어지고 있는 연속 착석 시간(ms). 앉아 있지 않으면 0. 잠깐 빈 동안은 비기 직전 값에서 멈춘다 */
    fun seatedMs(seat: Int, now: Long): Long {
        if (seat !in 0 until seatCount) return 0L
        val since = seatedSince[seat] ?: return 0L
        return ((emptySince[seat] ?: now) - since).coerceAtLeast(0L)
    }

    fun isSeated(seat: Int): Boolean = seat in 0 until seatCount && seatedSince[seat] != null

    /** 가장 오래 앉아 있는 자리와 그 시간(ms). 앉은 사람이 없으면 null. 같으면 앞 자리(운전석 쪽)가 먼저다 */
    fun longest(now: Long): Pair<Int, Long>? {
        var best: Pair<Int, Long>? = null
        for (i in 0 until seatCount) {
            if (seatedSince[i] == null) continue
            val ms = seatedMs(i, now)
            if (best == null || ms > best.second) best = i to ms
        }
        return best
    }

    /** 전부 비운다 */
    fun reset() {
        for (i in 0 until seatCount) { seatedSince[i] = null; emptySince[i] = null; stoodAt[i] = null }
    }
}

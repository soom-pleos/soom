package ai.kkaeugi.engine

import kotlin.math.roundToInt

/**
 * 자리별 누적(v0.7.3). 탄 자리마다 이번 주행에서 쌓인 것을 적는다. 순수 Kotlin. 전부 추정이다.
 *
 * 차 안 공기는 한 덩어리로 계산한다(자리마다 농도가 다르다고 보지 않는다). 자리마다 다른 것은 "언제부터 언제까지 탔는가"다.
 * 그래서 같은 추정값(실내 CO2, 실내 PM2.5)을 그 자리에 사람이 있던 시간만큼만 쌓는다. 중간에 탄 사람은 탄 시점부터다.
 *   한도 위 CO2 노출(분)     그 자리에 사람이 있는 동안 추정 CO2 가 기본 한도 이상이었던 시간
 *   평균 CO2(추정)           그동안의 시간 평균
 *   PM2.5 노출(추정)         CabinPmModel 이 쌓은 노출량(µg/m³·분) 가운데 그 자리에 사람이 있던 몫. 바깥 값을 받은 동안만
 *   가장 길었던 연속 착석(분)  SitClock 값의 최댓값
 *   걷기 쉼 횟수             쉼터에서 일어선 횟수
 * 차 전체 한 줄(cabin)은 자리별 값이 없는 주행에서 쓴다. 탄 사람이 있든 없든 주행한 시간 전부를 쌓는다.
 */
class SeatLedger(private val seatCount: Int = Seats.COUNT) {

    private class Row {
        var minutes = 0.0
        var overMinutes = 0.0
        var ppmMinutes = 0.0
        var pmExposure = 0.0
        var pmMinutes = 0.0
        var longestSit = 0.0
        var walkBreaks = 0
        /** 처음 탄 때(주행을 시작하고 흐른 분, 모델 시간). 탄 적이 없으면 null */
        var boardedAt: Double? = null

        fun add(minutes: Double, ppm: Double, overLimit: Boolean, pmExposure: Double, pmMinutes: Double) {
            this.minutes += minutes
            ppmMinutes += ppm * minutes
            if (overLimit) overMinutes += minutes
            this.pmExposure += pmExposure
            this.pmMinutes += pmMinutes
        }

        fun stats(seat: Int, profile: SeatProfile): SeatStats = SeatStats(
            seat = seat, profile = profile,
            minutesAboard = minutes.roundToInt(),
            minutesOverLimit = overMinutes.roundToInt(),
            averagePpm = if (minutes > 0) (ppmMinutes / minutes).roundToInt() else 0,
            pmAverage = if (pmMinutes > 0) pmExposure / pmMinutes else null,
            pmExposure = if (pmMinutes > 0) pmExposure else null,
            longestSitMinutes = longestSit.toInt(),
            walkBreaks = walkBreaks,
            boardedMidTrip = (boardedAt ?: 0.0) > MID_TRIP_MINUTES,
        )
    }

    private var rows = List(seatCount) { Row() }
    private var cabin = Row()
    /** 주행을 시작하고 흐른 분(모델 시간, 쉼 제외) */
    private var tripMinutes = 0.0

    /** 새 주행: 전부 비운다 */
    fun reset() {
        rows = List(seatCount) { Row() }
        cabin = Row()
        tripMinutes = 0.0
    }

    /**
     * 모델 시간 minutes 분만큼 쌓는다. aboard: 그동안 사람이 있던 자리(Seats 순서).
     * ppm: 그 구간의 추정 CO2, overLimit: 기본 한도 이상인가.
     * pmExposure / pmMinutes: 그 구간에 실내 미세먼지 추정이 쌓은 노출량(µg/m³·분)과 시간(분). 바깥 값을 못 받았으면 둘 다 0
     */
    fun advance(minutes: Double, aboard: List<Boolean>, ppm: Double, overLimit: Boolean, pmExposure: Double = 0.0, pmMinutes: Double = 0.0) {
        if (minutes <= 0) return
        for (i in 0 until seatCount) {
            if (aboard.getOrNull(i) != true) continue
            val r = rows[i]
            if (r.boardedAt == null) r.boardedAt = tripMinutes
            r.add(minutes, ppm, overLimit, pmExposure, pmMinutes)
        }
        if (cabin.boardedAt == null) cabin.boardedAt = 0.0
        cabin.add(minutes, ppm, overLimit, pmExposure, pmMinutes)
        tripMinutes += minutes
    }

    /** 그 자리의 지금 연속 착석(분). 가장 길었던 값만 남긴다. seat 에 Seats.CABIN 을 주면 차 전체 줄에 적는다 */
    fun noteSit(seat: Int, sitMinutes: Double) {
        val r = row(seat) ?: return
        if (sitMinutes > r.longestSit) r.longestSit = sitMinutes
    }

    /** 쉼터에서 일어섰다(걷기 쉼). aboard: 그때 타고 있던 자리. 차 전체 줄에도 한 번 센다 */
    fun noteWalkBreak(aboard: List<Boolean>) {
        for (i in 0 until seatCount) if (aboard.getOrNull(i) == true && rows[i].boardedAt != null) rows[i].walkBreaks++
        cabin.walkBreaks++
    }

    /** 이번 주행에서 한 번이라도 사람이 있던 자리의 누적(Seats 순서). profiles 는 지금의 자리별 프로필 */
    fun seats(profiles: List<SeatProfile>): List<SeatStats> =
        (0 until seatCount).filter { rows[it].boardedAt != null }.map { rows[it].stats(it, profiles.getOrNull(it) ?: SeatProfile.DEFAULT) }

    /** 차 전체로 본 누적. 주행한 적이 없으면 null */
    fun cabin(profile: SeatProfile = SeatProfile.DEFAULT): SeatStats? =
        if (cabin.boardedAt == null) null else cabin.stats(Seats.CABIN, profile)

    private fun row(seat: Int): Row? = when (seat) {
        Seats.CABIN -> cabin
        in 0 until seatCount -> rows[seat]
        else -> null
    }

    companion object {
        /** 주행을 시작하고 이 시간(분, 모델 시간)이 지난 뒤에 처음 탔으면 "중간에 탔다"고 적는다 */
        const val MID_TRIP_MINUTES = 1.0
    }
}

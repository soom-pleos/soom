package ai.kkaeugi.engine

/**
 * 자리별 프로필에서 이번 주행의 설정을 뽑는다(v0.7.3). 순수 Kotlin, 상태 없음.
 *
 * 프로필은 설정이다. 프로필별 숫자를 뒷받침하는 문헌은 확인하지 못했다(docs/EVIDENCE.md J 절).
 * 그래서 여기서 하는 일은 셋뿐이고, 셋 다 "무엇을 먼저 볼지"와 "여유를 얼마나 둘지"를 바꾸는 것이다.
 *   미세먼지 먼저(RESPIRATORY, CARDIO)  바깥 미세먼지 등급을 한 단계 나쁘게 보고 환기 규칙을 고른다
 *   공기 여유 넓게(CHILD, PREGNANT)     환기를 묻는 시점을 한도의 airMarginFraction(90%)로 당긴다
 *   더 자주 쉬기(PREGNANT, SENIOR, CARDIO) 쉬는 간격을 sitBreakMinutesShort(90분)로 줄인다
 * 탄 자리의 프로필만 쓴다. 빈자리에 붙여 둔 프로필은 아무 일도 하지 않는다.
 *
 * 둘이 같이 걸릴 때의 규칙(SeatPolicyTest, SeatEngineTest 가 지킨다):
 *  1) 환기를 묻는 한도는 어떤 조합에서도 기본 한도(co2LimitPpm)보다 높아지지 않는다. 이것이 맨 먼저다
 *  2) "공기 여유 넓게"의 90%는 "미세먼지 먼저"와 함께여도 남는다. 미세먼지가 나쁜 날에 없어지는 것은 야간 장거리의 여유(0.8배)뿐이다
 *  3) "미세먼지 먼저"는 그대로 환기를 짧게 하고, 바깥 공기로 계속 두자는 제안을 막는다
 */
object SeatPolicy {

    /** 야간 장거리일 때 한도에 곱하는 값. WakeEngine.NIGHT_LIMIT_FACTOR 와 같다 */
    const val NIGHT_LIMIT_FACTOR = 0.8
    /** 여유 비율로 쓸 수 있는 가장 작은 값. 설정이 잘못 들어와도 한도가 반 아래로 내려가지 않게 한다 */
    const val MIN_MARGIN_FRACTION = 0.5

    /** 다섯 칸으로 맞춘 프로필 목록. 모자라는 칸은 기본, 넘치는 칸은 버린다 */
    fun normalize(profiles: List<SeatProfile>): List<SeatProfile> =
        List(Seats.COUNT) { profiles.getOrNull(it) ?: SeatProfile.DEFAULT }

    /**
     * 지금 탄 자리. seats 는 차가 준 자리별 값(없으면 null). 주행 중 운전석은 값과 무관하게 탄 것으로 본다.
     * 자리별 값이 없으면 운전석만 안다(주행 중이면 탔다). 나머지는 모른다(false)
     */
    fun occupied(seats: List<Boolean?>?, driving: Boolean): List<Boolean> = List(Seats.COUNT) { i ->
        if (i == Seats.DRIVER && driving) true else seats?.getOrNull(i) == true
    }

    /**
     * 탄 자리의 프로필. 기본은 뺀다.
     * 자리별 값이 없으면(수동 입력) 누가 어디 앉았는지 모른다. 혼자면 운전석 프로필만, 둘 이상이면 고른 프로필을 전부 쓴다(넓게 잡는 쪽)
     */
    fun aboard(profiles: List<SeatProfile>, seats: List<Boolean?>?, occupants: Int, driving: Boolean): List<SeatProfile> {
        val p = normalize(profiles)
        val picked = if (seats == null) {
            if (occupants >= 2) p else listOf(p[Seats.DRIVER])
        } else {
            val on = occupied(seats, driving)
            p.filterIndexed { i, _ -> on[i] }
        }
        return picked.filter { it != SeatProfile.DEFAULT }
    }

    /** 미세먼지 먼저 */
    fun pmFirst(aboard: List<SeatProfile>): Boolean = aboard.any { it == SeatProfile.RESPIRATORY || it == SeatProfile.CARDIO }

    /** 공기 여유 넓게 */
    fun wideMargin(aboard: List<SeatProfile>): Boolean = aboard.any { it == SeatProfile.CHILD || it == SeatProfile.PREGNANT }

    /** 더 자주 쉬기 */
    fun shortBreak(aboard: List<SeatProfile>): Boolean =
        aboard.any { it == SeatProfile.PREGNANT || it == SeatProfile.SENIOR || it == SeatProfile.CARDIO }

    /** 쉬는 간격(분) */
    fun breakMinutes(aboard: List<SeatProfile>, config: WakeConfig): Int {
        val base = config.sitBreakMinutes.coerceAtLeast(1)
        // 줄인 값이 기본보다 길게 들어와 있으면 기본을 쓴다(프로필 때문에 덜 쉬게 되지 않게)
        return if (shortBreak(aboard)) minOf(base, config.sitBreakMinutesShort.coerceAtLeast(1)) else base
    }

    /** 등급을 한 단계 나쁘게 본다. 매우 나쁨은 그대로, 값이 없으면 없는 그대로(미세먼지 규칙을 쓰지 않는다) */
    fun worse(grade: PmGrade?): PmGrade? = when (grade) {
        null -> null
        PmGrade.GOOD -> PmGrade.MODERATE
        PmGrade.MODERATE -> PmGrade.BAD
        PmGrade.BAD, PmGrade.VERY_BAD -> PmGrade.VERY_BAD
    }

    /** 환기 규칙을 고를 때 쓰는 등급. 미세먼지 먼저면 한 단계 나쁘게 본 값, 아니면 받은 그대로 */
    fun policyGrade(actual: PmGrade?, pmFirst: Boolean): PmGrade? = if (pmFirst) worse(actual) else actual

    /**
     * 환기를 묻는 한도에 곱하는 값(1.0 이하). 순서를 고정한다:
     *  1) 기본 1.0
     *  2) 야간 장거리면 0.8. 단 미세먼지 규칙이 "늦추고 짧게"(pmShort)면 이 여유는 없앤다(v0.7.0 과 같다)
     *  3) 공기 여유 넓게면 airMarginFraction(0.9). pmShort 여도 남는다
     *  둘 다 걸리면 더 작은 쪽. 어떤 경우에도 1.0 을 넘지 않는다
     */
    fun limitFactor(wideMargin: Boolean, nightLongTrip: Boolean, pmShort: Boolean, config: WakeConfig): Double {
        var f = 1.0
        if (nightLongTrip && !pmShort) f = minOf(f, NIGHT_LIMIT_FACTOR)
        if (wideMargin) f = minOf(f, config.airMarginFraction.coerceIn(MIN_MARGIN_FRACTION, 1.0))
        return f
    }

    /** 문장에 넣을 프로필 이름 하나. wanted 순서대로 처음 탄 것. 없으면 null */
    fun firstLabel(aboard: List<SeatProfile>, wanted: List<SeatProfile>): String? =
        wanted.firstOrNull { it in aboard }?.label

    /** "두 시간째", "한 시간 반째", "45분째". 30분 단위로 내려서 읽는다 */
    fun sitPhrase(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        if (m < 60) return "${m}분째"
        val hours = m / 60
        val half = m % 60 >= 30
        val word = when (hours) { 1 -> "한"; 2 -> "두"; 3 -> "세"; 4 -> "네"; 5 -> "다섯"; else -> hours.toString() }
        return if (half) "$word 시간 반째" else "$word 시간째"
    }

    /** "혼자", "둘이", "셋이", "넷이", "다섯이". 도착 요약의 제목에 쓴다 */
    fun countWord(n: Int): String = when (n.coerceAtLeast(1)) {
        1 -> "혼자"; 2 -> "둘이"; 3 -> "셋이"; 4 -> "넷이"; 5 -> "다섯이"; else -> "${n}명이"
    }
}

package ai.kkaeugi.engine

/**
 * 주행 중 발화 허용 판단. Pleos 주의분산 정책에 맞춰 "말하지 않을 때"를 먼저 정한다.
 *
 * 말하지 않는다:
 *  - 방향지시등이 켜져 있는 동안 (회전 조작 중)
 *  - 안전 경고등(FCA, LKA, OVER_SPEED, ICY_ROAD)이 켜져 있는 동안
 *    (DRIVER_ATTENTION 은 졸음 제안의 트리거 자체라 막지 않는다)
 *    LKA 는 작동 대기 중 상시 ON 이므로 점멸(blink)일 때만 경고로 본다. 상시 ON 은 무시.
 *  - 다음 회전까지 80m 안쪽
 *  - 직전 제안 후 20초 이내 (우선순위 HIGH는 10초)
 *  - 제안이 아닌 문장(Done, RestEnded, ProposalExpired)을 읽고 있을 시간. 문장마다 길이로 정한 시간만큼만 막는다
 */
class SpeechGate(
    private val cooldownMs: Long = 20_000,
    private val highCooldownMs: Long = 10_000,
    private val turnHoldM: Double = 80.0,
) {
    enum class Priority { NORMAL, HIGH }

    private var turnSignalOn = false
    private val activeWarnings = mutableSetOf<Telltale>()
    private var nextTurnDistance: Double? = null
    private var lastSpokeAt: Long = Long.MIN_VALUE / 2
    /** 제안이 아닌 문장을 읽기 시작한 시각과 그 문장을 읽는 데 걸린다고 본 시간 */
    private var utteranceAt: Long = Long.MIN_VALUE / 2
    private var utteranceHoldMs: Long = 0L

    fun onTurnSignal(on: Boolean) { turnSignalOn = on }

    fun onTelltale(type: Telltale, on: Boolean, blink: Boolean = false) {
        if (type !in BLOCKING) return
        // LKA 는 점멸 중일 때만 경고. ON 만 들어오면 경고에서 뺀다
        val warning = if (type == Telltale.LKA) on && blink else on
        if (warning) activeWarnings += type else activeWarnings -= type
    }

    fun onTurnAhead(turn: TurnDir, distanceM: Double) {
        nextTurnDistance = if (turn == TurnDir.STRAIGHT || turn == TurnDir.OTHER) null else distanceM
    }

    fun blockedReason(now: Long, priority: Priority = Priority.NORMAL): String? {
        if (turnSignalOn) return "turn_signal"
        if (activeWarnings.isNotEmpty()) return "warning:" + activeWarnings.joinToString(",")
        nextTurnDistance?.let { if (it < turnHoldM) return "turn_near" }
        val cd = if (priority == Priority.HIGH) highCooldownMs else cooldownMs
        // 시계가 뒤로 간 경우(음수 차이)도 쿨다운 안으로 본다. 터지지만 않으면 된다
        if ((now - lastSpokeAt).coerceAtLeast(0) < cd) return "cooldown"
        if ((now - utteranceAt).coerceAtLeast(0) < utteranceHoldMs) return "speaking"
        return null
    }

    /** 쿨다운과 읽는 중을 뺀 차단 사유(깜빡이, 경고등, 회전 임박). 예고한 제어를 실행해도 되는지 볼 때 쓴다 */
    fun hazardReason(): String? {
        if (turnSignalOn) return "turn_signal"
        if (activeWarnings.isNotEmpty()) return "warning:" + activeWarnings.joinToString(",")
        nextTurnDistance?.let { if (it < turnHoldM) return "turn_near" }
        return null
    }

    /** 직전 제안의 쿨다운을 지운다. 그 제안을 말없이 닫고 더 급한 제안을 내야 할 때 쓴다 */
    fun clearCooldown() { lastSpokeAt = Long.MIN_VALUE / 2 }

    fun canSpeak(now: Long, priority: Priority = Priority.NORMAL) = blockedReason(now, priority) == null

    /** 제안을 말했다. 여기서부터 쿨다운을 센다 */
    fun markSpoken(now: Long) { lastSpokeAt = now }

    /**
     * 제안이 아닌 문장을 말했다. holdMs 동안은 새 제안을 내지 않는다(읽는 중인 문장을 끊지 않게).
     * 앞 문장이 더 늦게 끝나면 그쪽을 그대로 둔다
     */
    fun markUtterance(now: Long, holdMs: Long) {
        if (now + holdMs >= utteranceAt + utteranceHoldMs) {
            utteranceAt = now
            utteranceHoldMs = holdMs.coerceAtLeast(0L)
        }
    }

    companion object {
        val BLOCKING = setOf(
            Telltale.FCA, Telltale.LKA, Telltale.OVER_SPEED, Telltale.ICY_ROAD,
        )
    }
}

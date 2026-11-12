package ai.kkaeugi.app.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 제어 검증 한 바퀴(순수 로직, JVM 테스트 대상). adb 로 부르는 실측용이다.
 * 차 값을 실제로 바꾸지만 끝나면 처음 상태로 돌려놓는다. 항목마다 한 줄로 적는다.
 *
 * 한 항목의 순서(v0.7.4): 읽기 → 명령 → 확인(0.5초 간격, 최대 3초) → 2초 유지 → 원복 → 확인(최대 3초) → 안 되면 원복을 한 번 더 보내고 다시 확인.
 *   fresh   내외기 전환(지금 값의 반대로 바꿨다가 되돌린다)
 *   fan     풍량 한 단(최대 단이면 한 단 내린다)
 *   window  운전석 창문 10%(범위가 거칠어 10% 이하로 열 수 없으면 건너뛴다)
 *
 * 공조 전원(v0.7.4): 시작할 때 전원을 읽어 둔다. 꺼져 있으면 내외기와 풍량을 보기 전에 켜고(꺼진 채로는 값을 못 읽거나, 명령을 받은 차가 전원을 스스로 켠다),
 * 다 끝난 뒤 처음 상태로 되돌린다. 전원을 읽을 수 없는 차에서는 전원을 건드리지 않고 그 사실을 적는다.
 *
 * 마무리: 항목이 끝난 뒤 처음에 읽어 둔 값(내외기, 풍량, 창문, 전원)과 지금 값을 견줘, 다른 것이 있으면 한 번 더 되돌린다.
 * 한 항목의 명령이 다른 값을 건드린 경우(풍량 명령이 전원을 켜고, 전원이 켜지면서 내외기가 바뀌는 차)를 여기서 잡는다.
 * 도중에 취소되거나 예외가 나도 마무리는 돈다.
 */
class ControlVerifier(
    private val controls: VehicleControls,
    private val windowInverted: () -> Boolean = { false },
    private val wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /** 명령한 값으로 둔 채 기다리는 시간(ms). 버추얼 콘솔에서 눈으로 볼 틈이다 */
    private val holdMs: Long = HOLD_MS,
    private val confirmMs: Long = Confirm.TIMEOUT_MS,
    private val pollMs: Long = Confirm.STEP_MS,
    /** 한 줄이 정해질 때마다(Logcat 에 바로 남기려고) */
    private val onLine: (String) -> Unit = {},
) {
    /** 한 항목의 결과. 값은 읽은 그대로의 글자, 못 읽었으면 null. afterMs, revertedMs 는 그 값을 볼 때까지 기다린 시간 */
    data class Step(
        val name: String, val before: String?, val command: String? = null, val after: String? = null, val afterMs: Long? = null,
        val reverted: String? = null, val revertedMs: Long? = null,
        val ok: Boolean, val error: String? = null, val skipped: Boolean = false,
        /** 성공했지만 적어 둘 것(원복을 한 번 다시 보냄 등) */
        val note: String? = null,
    ) {
        /** `[내외기] 전 내기 → 명령 외기 → 읽음 외기(0.5초) → 원복 내기 → 읽음 내기(1.0초) : 성공` */
        fun line(): String = buildString {
            append("[$name] ")
            if (skipped) { append("건너뜀 (${error ?: "사유 없음"})"); return@buildString }
            append("전 ${before ?: "못 읽음"}")
            if (command != null) {
                append(" → 명령 $command → 읽음 ${after ?: "못 읽음"}${afterMs?.let { "(${Confirm.seconds(it)})" } ?: ""}")
                append(" → 원복 $before → 읽음 ${reverted ?: "못 읽음"}${revertedMs?.let { "(${Confirm.seconds(it)})" } ?: ""}")
            }
            append(" : ").append(if (ok) "성공" else "실패")
            (error ?: note)?.let { append(" ($it)") }
        }
    }

    /** 처음과 끝에 한 번씩 읽는 값. 못 읽은 것은 null */
    data class Snapshot(val power: Boolean?, val recirculation: Boolean?, val fan: Int?, val window: Int?) {
        fun text(): String = listOf(
            "공조 전원 ${power?.let(::powerText) ?: "못 읽음"}", "내외기 ${recirculation?.let(::recircText) ?: "못 읽음"}",
            "풍량 ${fan ?: "못 읽음"}", "창문 ${window ?: "못 읽음"}",
        ).joinToString(", ")
    }

    /** 한 바퀴의 결과. restored: 끝 상태가 처음과 같은가(견줄 수 없는 값이 있으면 null) */
    data class Result(val first: Snapshot, val steps: List<Step>, val last: Snapshot, val closing: List<String>, val restored: Boolean?) {
        val lines: List<String> get() = listOf("[처음 상태] ${first.text()}") + steps.map { it.line() } + closing + "[끝 상태] ${last.text()} : ${restoredText()}"
        fun restoredText(): String = when (restored) { true -> "처음과 같음"; false -> "처음과 다름"; null -> "일부는 견줄 수 없음" }
    }

    private fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Ok)?.value
    private fun <T> Outcome<T>.errorOrNull(): String? = (this as? Outcome.Fail)?.error

    private fun emit(step: Step): Step { onLine(step.line()); return step }

    private suspend fun snapshot(): Snapshot = Snapshot(
        power = runCatching { controls.getHvacPower().valueOrNull() }.getOrNull(),
        recirculation = runCatching { controls.getRecirculation().valueOrNull() }.getOrNull(),
        fan = runCatching { controls.getFanSpeed().valueOrNull() }.getOrNull(),
        window = runCatching { controls.getWindow().valueOrNull() }.getOrNull(),
    )

    /** what 에 맞는 항목을 순서대로 돌린다. 모르는 값이면 null */
    suspend fun run(what: String): Result? {
        if (what !in KINDS) return null
        val hvac = what != WINDOW
        val first = snapshot()
        onLine("[처음 상태] ${first.text()}")
        val steps = mutableListOf<Step>()
        // 전원을 우리가 켰는가, 그때 읽은 값
        var powerOn: Confirm.Seen<Boolean>? = null
        var powerOnError: String? = null
        var closing: List<String> = emptyList()
        var powerStep: Step? = null
        try {
            if (hvac && first.power == false) {
                powerOnError = controls.setHvacPower(true).errorOrNull()
                powerOn = Confirm.until(true, controls::getHvacPower, wait, confirmMs, pollMs)
                onLine("[공조 전원] 전 꺼짐 → 명령 켜짐 → 읽음 ${powerOn.value?.let(::powerText) ?: "못 읽음"}(${Confirm.seconds(powerOn.waitedMs)})${powerOnError?.let { " (쓰기 실패: $it)" } ?: ""}. 원복은 맨 끝에 해요")
            }
            if (what == FRESH || what == ALL) steps += emit(fresh())
            if (what == FAN || what == ALL) steps += emit(fan())
            if (what == WINDOW || what == ALL) steps += emit(window())
        } finally {
            // 취소되거나 예외가 나도 처음 상태로 돌려놓는다
            withContext(NonCancellable) {
                val notes = mutableListOf<String>()
                runCatching { closeUp(first, hvac, notes) }.onFailure { notes += "[마무리] 예외 ${it.javaClass.simpleName}: ${it.message}" }
                powerStep = runCatching { restorePower(first, powerOn, powerOnError, notes) }
                    .getOrElse { notes += "[마무리] 공조 전원 원복 중 예외 ${it.javaClass.simpleName}: ${it.message}"; null }
                closing = notes
            }
        }
        powerStep?.let { steps.add(0, emit(it)) }
        closing.forEach(onLine)
        val last = snapshot()
        val result = Result(first, steps, last, closing, sameState(first, last))
        onLine("[끝 상태] ${last.text()} : ${result.restoredText()}")
        return result
    }

    /** 처음에 읽힌 값만 견준다. 처음에 읽혔는데 끝에 못 읽은 값이 있으면 null(공조 전원을 다시 끈 차는 내외기를 못 읽을 수 있다) */
    private fun sameState(first: Snapshot, last: Snapshot): Boolean? {
        val pairs = listOf(first.power to last.power, first.recirculation to last.recirculation, first.fan to last.fan, first.window to last.window)
        if (pairs.any { (a, b) -> a != null && b != null && a != b }) return false
        return if (pairs.any { (a, b) -> a != null && b == null }) null else true
    }

    /** 값을 value 로 돌리고 확인한다. 안 되면 한 번 더 보낸다. 돌아온 값: 마지막으로 본 것, 다시 보냈는가 */
    private suspend fun <T : Any> putBack(value: T, get: suspend () -> Outcome<T>, set: suspend (T) -> Outcome<T>, errors: MutableList<String>): Pair<Confirm.Seen<T>, Boolean> {
        set(value).errorOrNull()?.let { errors += "원복 쓰기 실패: $it" }
        val seen = Confirm.until(value, get, wait, confirmMs, pollMs)
        if (seen.matched) return seen to false
        set(value).errorOrNull()?.let { errors += "원복 재시도 쓰기 실패: $it" }
        val again = Confirm.until(value, get, wait, confirmMs, pollMs)
        val total = again.copy(value = again.value ?: seen.value, waitedMs = seen.waitedMs + again.waitedMs)
        if (!again.matched) errors += if (total.value == null) "원복 뒤 값을 못 읽음: ${again.error}" else "원복을 두 번 보냈지만 처음 값이 되지 않음"
        return total to true
    }

    /** 한 항목의 공통 흐름. target 이 null 이면 건너뛴다 */
    private suspend fun <T : Any> step(
        name: String, get: suspend () -> Outcome<T>, set: suspend (T) -> Outcome<T>, show: (T) -> String = { it.toString() },
        target: suspend (T) -> Pair<T?, String?>,
    ): Step {
        return try {
            val first = get()
            val before = first.valueOrNull() ?: return Step(name, null, ok = false, error = "명령 전 값을 못 읽음: ${first.errorOrNull()}")
            val (goal, why) = target(before)
            if (goal == null) return Step(name, show(before), ok = false, error = why, skipped = true)
            val errors = mutableListOf<String>()
            val written = set(goal)
            written.errorOrNull()?.let { errors += "쓰기 실패: $it" }
            // 쓰기가 거절됐으면 길게 기다리지 않고 한 번만 읽는다
            val after = Confirm.until(goal, get, wait, if (written is Outcome.Ok) confirmMs else pollMs, pollMs)
            if (written is Outcome.Ok && !after.matched) {
                errors += if (after.value == null) "명령 뒤 값을 못 읽음: ${after.error}" else "${Confirm.seconds(confirmMs)} 안에 명령한 값이 되지 않음"
            }
            if (after.matched) wait(holdMs)
            // 쓰기가 거절됐고 값도 그대로면 되돌릴 것이 없다(원복 명령을 보내지 않는다)
            if (written is Outcome.Fail && after.value == before) {
                return Step(name, show(before), show(goal), show(before), after.waitedMs, show(before), 0L, ok = false, error = errors.joinToString("; ") + "; 값이 그대로라 원복 명령은 보내지 않음")
            }
            val (back, retried) = putBack(before, get, set, errors)
            Step(
                name, show(before), show(goal), after.value?.let(show), after.waitedMs, back.value?.let(show), back.waitedMs,
                ok = errors.isEmpty(), error = errors.joinToString("; ").ifEmpty { null },
                note = if (retried && errors.isEmpty()) "원복을 한 번 다시 보냄" else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Step(name, null, ok = false, error = "예외 ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun fresh(): Step = step("내외기", controls::getRecirculation, controls::setRecirculation, show = ::recircText) { before -> !before to null }

    private suspend fun fan(): Step = step("풍량", controls::getFanSpeed, controls::setFanSpeed) { before ->
        val (min, max) = controls.fanRange()
        when {
            before < max -> (before + 1) to null
            before > min -> (before - 1) to null
            else -> null to "풍량 범위가 $min~$max 라 바꿀 단이 없음"
        }
    }

    private suspend fun window(): Step = step("창문", controls::getWindow, controls::setWindow) { before ->
        val (min, max) = controls.windowRange()
        val inverted = windowInverted()
        if (!WindowLimit.opens(WINDOW_PERCENT, min, max)) return@step null to "범위 $min~$max 는 한 칸이 $WINDOW_PERCENT% 보다 커서 열지 않음"
        val goal = WindowLimit.positionAtMost(WINDOW_PERCENT, min, max, inverted)
        if (goal == before) null to "이미 그 위치($before)라 바꿀 것이 없음" else goal to null
    }

    /**
     * 마무리: 처음에 읽어 둔 내외기, 풍량, 창문과 지금 값을 견줘 다른 것을 한 번 더 되돌린다.
     * 처음에 못 읽은 값은 기준이 없어 건드리지 않는다. 내외기와 풍량은 공조 항목을 돌렸을 때만 본다
     */
    private suspend fun closeUp(first: Snapshot, hvac: Boolean, notes: MutableList<String>) {
        suspend fun <T : Any> check(name: String, was: T?, get: suspend () -> Outcome<T>, set: suspend (T) -> Outcome<T>, show: (T) -> String) {
            if (was == null) return
            val now = get().valueOrNull() ?: return
            if (now == was) return
            val errors = mutableListOf<String>()
            val (back, _) = putBack(was, get, set, errors)
            notes += "[마무리] $name: 처음 ${show(was)}, 지금 ${show(now)} → 처음 값으로 다시 돌림 → 읽음 ${back.value?.let(show) ?: "못 읽음"}(${Confirm.seconds(back.waitedMs)}) : " +
                if (errors.isEmpty()) "성공" else "실패 (${errors.joinToString("; ")})"
        }
        if (hvac) {
            check("내외기", first.recirculation, controls::getRecirculation, controls::setRecirculation, ::recircText)
            check("풍량", first.fan, controls::getFanSpeed, controls::setFanSpeed) { it.toString() }
        }
        check("창문", first.window, controls::getWindow, controls::setWindow) { it.toString() }
    }

    /**
     * 공조 전원을 처음 상태로. 우리가 켰으면 끄고, 명령을 받은 차가 스스로 켰어도 끈다. 처음에 켜져 있었는데 꺼졌으면 다시 켠다.
     * 전원 줄은 우리가 전원을 건드렸거나 전원이 바뀌어 있을 때만 낸다. 전원을 읽을 수 없으면 그 사실만 적는다
     */
    private suspend fun restorePower(first: Snapshot, powerOn: Confirm.Seen<Boolean>?, powerOnError: String?, notes: MutableList<String>): Step? {
        val was = first.power
        if (was == null) {
            val why = controls.getHvacPower().errorOrNull() ?: "값 없음"
            notes += "[마무리] 공조 전원을 읽을 수 없어 전원은 되돌리지 못해요(${why.take(80)}). 버추얼 콘솔에서 전원이 처음과 같은지 봐 주세요"
            return null
        }
        val now = controls.getHvacPower().valueOrNull()
        if (powerOn == null && now == was) return null
        val errors = mutableListOf<String>()
        powerOnError?.let { errors += "쓰기 실패: $it" }
        if (powerOn != null && !powerOn.matched) errors += "${Confirm.seconds(confirmMs)} 안에 전원이 켜지지 않음"
        var back: Confirm.Seen<Boolean>? = null
        var retried = false
        if (now != was) {
            val r = putBack(was, controls::getHvacPower, controls::setHvacPower, errors)
            back = r.first; retried = r.second
        }
        val command = if (powerOn != null) powerText(true) else "없음(차가 스스로 ${now?.let(::powerText) ?: "바꿈"})"
        return Step(
            "공조 전원", powerText(was), command, (powerOn?.value ?: now)?.let(::powerText), powerOn?.waitedMs,
            (back?.value ?: now)?.let(::powerText), back?.waitedMs ?: 0L,
            ok = errors.isEmpty(), error = errors.joinToString("; ").ifEmpty { null },
            note = if (retried && errors.isEmpty()) "원복을 한 번 다시 보냄" else null,
        )
    }

    companion object {
        const val FRESH = "fresh"
        const val FAN = "fan"
        const val WINDOW = "window"
        const val ALL = "all"
        val KINDS = listOf(FRESH, FAN, WINDOW, ALL)
        const val WINDOW_PERCENT = 10
        const val HOLD_MS = 2_000L
        const val FILE_NAME = "verify_latest.txt"

        fun recircText(on: Boolean): String = if (on) "내기" else "외기"
        fun powerText(on: Boolean): String = if (on) "켜짐" else "꺼짐"

        /**
         * 다 잘 될 때 걸리는 시간 어림(초). 항목마다 확인 0.5 + 유지 2 + 원복 확인 0.5, 공조 항목이면 전원 확인 두 번을 더한다.
         * 방송 수신기가 붙들 수 있는 시간(약 10초)을 넘는지 가리는 데 쓴다
         */
        fun expectedSeconds(what: String, holdMs: Long = HOLD_MS, pollMs: Long = Confirm.STEP_MS): Int {
            val items = when (what) { ALL -> 3; FRESH, FAN, WINDOW -> 1; else -> 0 }
            val power = if (what != WINDOW && items > 0) 2 * pollMs else 0L
            return ((items * (holdMs + 2 * pollMs) + power + 999) / 1000).toInt()
        }

        /** 보고서: 머리말 한 줄, 요약 한 줄, 처음 상태, 항목마다 한 줄, 마무리, 끝 상태. Logcat 과 verify_latest.txt 가 같은 글을 쓴다 */
        fun report(header: String, result: Result): String = buildString {
            appendLine(header)
            appendLine(summary(result))
            result.lines.forEach { appendLine(it) }
        }

        fun summary(steps: List<Step>): String =
            "성공 ${steps.count { it.ok }} / 실패 ${steps.count { !it.ok && !it.skipped }}" + steps.count { it.skipped }.let { if (it > 0) " / 건너뜀 $it" else "" }

        /** 요약 줄 끝에 끝 상태를 붙인다: "성공 3 / 실패 0 · 끝 상태는 처음과 같음" */
        fun summary(result: Result): String = "${summary(result.steps)} · 끝 상태는 ${result.restoredText()}"
    }
}

package ai.kkaeugi.app.vehicle

import ai.kkaeugi.engine.restStopDisplayName
import ai.kkaeugi.app.signals.MonoClock
import ai.kkaeugi.app.signals.SignalBus
import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.ActionResult
import ai.kkaeugi.engine.RestStopInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 엔진의 Action 을 실제 차량 제어로 바꾼다. SDK 는 VehicleControls/WaypointControls 인터페이스 뒤에 있다(JVM 테스트는 fake).
 *
 * 원칙
 *  - 실행 전에 현재값을 읽는다. 읽기 실패(null/타임아웃)면 그 액션은 실행하지 않고 ActionResult(ok=false, detail="READ_FAILED").
 *    원복할 기준이 없는 제어는 하지 않는다.
 *  - 실행 전 값(before)과 우리가 넣은 값(set)을 함께 저장한다(처음 바꾸기 직전 값만. 같은 세션에서 두 번 바꿔도 최초 값으로 돌아간다).
 *  - Revert: 풍량·온도·창문은 "현재값이 set 에서 허용 오차(범위의 5%, 최소 1) 안" 일 때만 before 로 돌린다. 운전자가 그 사이 바꿨으면 둔다.
 *    풍량과 온도는 명령 직후 차가 실제로 둔 값(settled)도 기억해, 차가 스스로 고친 값을 운전자 조작으로 잘못 보지 않는다(v0.7.4).
 *    내기순환은 공조 AUTO 가 값을 바꿀 수 있어 현재값 비교를 하지 않고 무조건 before 로 돌린다.
 *    단, 실행 중 리스너(registerHvacRecirculationState)로 set 과 다른 값이 관찰됐으면(운전자가 눌렀다고 봄) 건드리지 않는다.
 *    우리 명령 뒤 RECIRC_SETTLE_MS 안에 온 값은 차의 반응으로 보고 운전자 조작으로 세지 않는다(v0.7.4).
 *  - 원복 확인(v0.7.4): 되돌리는 명령을 보낸 뒤 0.5초 간격으로 최대 3초까지 읽어 확인하고, 안 됐으면 한 번 다시 보낸다.
 *    그래도 안 되면 ok=false 로 알린다(엔진이 "일부는 못 돌렸어요"라고 말한다).
 *  - 공조 전원(v0.7.4): 환기 전에 전원을 읽는다. 꺼져 있으면 켜고(꺼진 채로는 바깥 공기가 들어오지 않고, 값을 못 읽는 차도 있다)
 *    원복 때 다시 끈다. 그 사이 운전자가 공조를 만졌으면 켠 채로 둔다. 전원을 읽을 수 없는 차에서는 전원을 건드리지 않는다.
 *  - Execute 배치는 취소 토큰을 가진다. Revert 가 오면 아직 실행 안 한 남은 액션은 취소되고, 진행 중인 액션 하나만 끝낸 뒤 바로 원복한다.
 *  - 결과는 성공/실패 모두 ActionResult 로 버스에 보낸다. 실패 detail 은 READ_FAILED / DRIVING_RESTRICTED / PERMISSION / TIMEOUT 으로 정규화.
 *  - 환기(Ventilate): 이미 외기면 내외기는 건드리지 않고(스냅샷도 없다) 풍량만 fanDelta 만큼, 최대 단계까지 올린다.
 *    fanDelta 가 음수면(약한 바람으로 바깥 공기를 계속 들이기, v0.7.4) 그만큼 내리되 1단 아래로는 내리지 않는다. 0 이면 풍량은 그대로 둔다.
 *  - 원복 없이 끝난 실행(엔진이 Execute 뒤 Revert 없이 Done 을 냄)은 discardSnapshots 로 그 실행의 스냅샷을 버린다.
 *  - Pleos 가 없는 기기(폰, 일반 에뮬레이터) 또는 연결 판정 실패(hasVehicle=false)에서는 모의 실행.
 *    모의 차량(mockControls)이 있으면 같은 순서로 그 차량의 값을 바꾼다(detail="mock: ..."). 없으면 ok=true, detail="mock" 만 돌려준다.
 */
class VehicleActuator(
    private val bus: SignalBus,
    private val scope: CoroutineScope,
    private val controls: VehicleControls,
    private val nav: WaypointControls,
    private val hasVehicle: () -> Boolean,
    private val onLog: (String) -> Unit = {},
    /** AddRestStop 성공 직후(실제/모의 모두). 근접 추적 시작용 */
    private val onRestStopAdded: (RestStopInfo) -> Unit = {},
    private val now: () -> Long = { MonoClock.now() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** Pleos 가 없을 때 대신 바꿀 모의 차량. 화면의 내외기·풍량이 실제처럼 움직인다 */
    private val mockControls: VehicleControls? = null,
    /** 설정 "창문 방향 반전". 켜져 있으면 max 가 닫힘, min 이 다 열림 */
    private val windowInverted: () -> Boolean = { false },
    /** 모의 실행에서 낸 경유지 호출 이름(증거 띠 표시용) */
    private val onMockCall: (String) -> Unit = {},
    /** 명령 뒤 확인 사이에 기다리는 함수. 테스트는 기다리지 않는 것으로 바꿔 끼운다 */
    private val wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    /** 한 항목의 원복 정보: 바꾸기 전 값과 우리가 넣은 값 */
    data class Snap<T>(val before: T, val set: T)

    /** 원복용 스냅샷 */
    data class Saved(
        var recirculation: Snap<Boolean>? = null,
        var fanSpeed: Snap<Int>? = null,
        var temperature: Snap<Float>? = null,
        var windowPos: Snap<Int>? = null,
        var waypointAdded: Boolean = false,
        /** 실행 중 리스너로 내기순환이 set 과 다른 값으로 바뀐 것을 봤다(운전자 조작) */
        var recircTouchedByDriver: Boolean = false,
        /** 우리가 내기순환 명령을 마지막으로 보낸 시각(단조 ms). 바로 뒤에 오는 값은 차의 반응으로 본다 */
        var recircCommandAt: Long = 0L,
        /** 공조 전원: 환기 전에 꺼져 있었고 우리가(또는 명령을 받은 차가) 켰다. 원복 때 끈다 */
        var power: Snap<Boolean>? = null,
        /** 명령 직후 차가 실제로 둔 풍량, 온도(명령한 값과 다를 때만). 운전자 조작을 가릴 때 이 값도 우리 것으로 본다 */
        var fanSettled: Int? = null,
        var temperatureSettled: Float? = null,
    )

    @Volatile var saved = Saved()
        private set
    /** 마지막으로 경유지에 넣은 졸음쉼터(화면 표시용) */
    @Volatile var lastRestStop: RestStopInfo? = null
        private set

    /** 되돌릴 값이 남아 있는가(환기나 졸음 개입이 유지 중). 화면이 닫힐 때 이것이 참이면 닫기 전에 원복한다 */
    val hasSnapshots: Boolean
        get() = saved.let { it.recirculation != null || it.fanSpeed != null || it.temperature != null || it.windowPos != null || it.power != null }

    private val mutex = Mutex()
    /** 현재 실행 중인 배치. Revert 가 오면 cancelled 를 세워 남은 액션을 건너뛴다 */
    class Batch { @Volatile var cancelled = false }
    @Volatile private var current: Batch? = null

    /** 액션 목록을 순서대로 실행한다. 각 액션마다 ActionResult 를 버스로 보낸다. */
    fun execute(actions: List<Action>) {
        val isRevert = actions.size == 1 && actions[0] == Action.Revert
        if (isRevert) {
            // 아직 실행 안 한 남은 액션은 취소. 진행 중인 액션은 mutex 가 풀릴 때 끝난다.
            current?.let { if (!it.cancelled) { it.cancelled = true; onLog("원복 요청: 남은 액션 취소") } }
        }
        val batch = Batch()
        if (!isRevert) current = batch
        scope.launch(dispatcher) {
            run(actions, batch) { r -> bus.emit(r) }
            if (!isRevert && current === batch) current = null
        }
    }

    /**
     * 실행 본체(테스트에서 직접 부른다). 액션마다 onResult 를 부른다.
     * batch.cancelled 가 서면 남은 액션은 건너뛴다(Revert 는 취소되지 않는다).
     */
    suspend fun run(actions: List<Action>, batch: Batch = Batch(), onResult: (ActionResult) -> Unit): List<ActionResult> {
        val out = mutableListOf<ActionResult>()
        mutex.withLock {
            for (a in actions) {
                if (a != Action.Revert && batch.cancelled) { onLog("${label(a)} 건너뜀(원복 요청)"); continue }
                val (ok, detail) = runCatching { perform(a) }.getOrElse { false to "예외: ${it.message}" }
                onLog("${label(a)} ${if (ok) "완료" else "실패"}${detail?.let { " ($it)" } ?: ""}")
                val r = ActionResult(now(), a, ok, if (ok) detail else detail?.let { FailureCode.normalize(it) })
                out += r
                onResult(r)
            }
        }
        return out
    }

    /** 테스트/검증 패널용: 결과를 버스에 보내지 않고 한 액션만 실행한다(스냅샷은 같이 쌓인다) */
    suspend fun performQuietly(a: Action): Pair<Boolean, String?> = mutex.withLock { runCatching { perform(a) }.getOrElse { false to "예외: ${it.message}" } }

    /**
     * 내기순환 리스너 값이 들어올 때마다 부른다. 우리가 외기로 바꿔 둔 동안(스냅샷 있음) set 과 다른 값이 보이면
     * 운전자가 직접 바꾼 것으로 보고 원복 때 건드리지 않는다.
     */
    fun onRecirculationObserved(on: Boolean) {
        val snap = saved.recirculation ?: return
        if (on == snap.set || saved.recircTouchedByDriver) return
        // 명령 직후에는 차가 앞의 값을 한 번 더 알리거나 스스로 값을 고칠 수 있다. 그것을 운전자 조작으로 세면 원복을 건너뛰게 된다
        val sinceCommand = now() - saved.recircCommandAt
        if (sinceCommand < RECIRC_SETTLE_MS) {
            onLog("내기순환 ${if (on) "ON" else "OFF"} 이 명령 ${sinceCommand}ms 뒤에 옴. 차의 반응으로 보고 운전자 조작으로 세지 않음")
            return
        }
        saved.recircTouchedByDriver = true
        onLog("내기순환이 실행 중 ${if (on) "ON" else "OFF"} 으로 바뀜(운전자 조작으로 봄). 원복 때 두기로")
    }

    /**
     * 원복 없이 끝난 실행의 스냅샷을 버린다(엔진이 Execute 뒤 Execute([Revert]) 없이 Done 을 냈을 때. "바꿔 둘까요?", "풍량을 올릴까요?").
     * 버리지 않으면 다음 개입의 원복이 내기순환과 풍량까지 되돌린다. 그 실행에 없던 항목의 스냅샷은 그대로 둔다.
     */
    fun discardSnapshots(actions: List<Action>) {
        val dropped = mutableListOf<String>()
        for (a in actions) when (a) {
            is Action.Ventilate -> {
                if (saved.recirculation != null) dropped += "내기순환"
                if (saved.fanSpeed != null) dropped += "풍량"
                if (saved.power != null) dropped += "공조 전원"
                saved.recirculation = null
                saved.fanSpeed = null
                saved.fanSettled = null
                saved.power = null
                saved.recircTouchedByDriver = false
            }
            is Action.CoolDown -> { if (saved.temperature != null) dropped += "온도"; saved.temperature = null; saved.temperatureSettled = null }
            is Action.OpenWindow -> { if (saved.windowPos != null) dropped += "창문"; saved.windowPos = null }
            is Action.AddRestStop, Action.Revert -> {}
        }
        if (dropped.isNotEmpty()) onLog("원복 없이 유지: ${dropped.joinToString(", ")} 스냅샷을 버림")
    }

    private suspend fun perform(a: Action): Pair<Boolean, String?> {
        val real = hasVehicle()
        val c = if (real) controls else mockControls ?: return mock(a)
        val r = when (a) {
            is Action.Ventilate -> ventilate(c, a.fanDelta)
            is Action.CoolDown -> coolDown(c, a.celsiusDelta)
            is Action.OpenWindow -> openWindow(c, a.percent)
            is Action.AddRestStop -> if (real) addRestStop(a.stop) else return mock(a)
            Action.Revert -> revert(c)
        }
        return if (real) r else r.first to "mock: ${r.second}"
    }

    // ---------- 실제 제어 ----------

    private suspend fun ventilate(c: VehicleControls, fanDelta: Int): Pair<Boolean, String?> {
        val notes = mutableListOf<String>()
        // 0) 공조 전원. 꺼져 있으면 켠다(꺼진 채로는 바깥 공기가 들어오지 않는다). 읽을 수 없는 차에서는 건드리지 않는다
        val powerBefore = (c.getHvacPower() as? Outcome.Ok)?.value
        if (powerBefore == false) when (val r = c.setHvacPower(true)) {
            is Outcome.Ok -> {
                val seen = Confirm.until(true, c::getHvacPower, wait, readFirst = true)
                if (saved.power == null) saved.power = Snap(false, true)
                notes += if (seen.matched) "공조 전원 켬" else "공조 전원 켜기 확인 못 함"
            }
            is Outcome.Fail -> notes += "공조 전원 켜기 실패: ${FailureCode.normalize(r.error)}"
        }
        // 1) 내기순환 현재값. 못 읽으면 원복 기준이 없으니 실행하지 않는다(켠 전원은 뒤따르는 원복이 끈다)
        val before = when (val r = c.getRecirculation()) {
            is Outcome.Ok -> r.value
            is Outcome.Fail -> return false to FailureCode.READ_FAILED
        }
        if (before) {
            when (val r = c.setRecirculation(false)) {
                is Outcome.Ok -> {
                    notes += "외기 전환"
                    if (saved.recirculation == null) saved.recirculation = Snap(true, false)
                    // 우리가 다시 명령했다. 앞서 본 "운전자 조작" 표시는 이 명령보다 전의 것이라 지운다
                    saved.recircTouchedByDriver = false
                    saved.recircCommandAt = now()
                }
                is Outcome.Fail -> return false to r.error
            }
        } else {
            // 이미 외기: 내외기는 건드리지 않는다(원복할 것도 없다). 풍량만 올린다
            notes += "이미 외기"
        }
        // 2) 풍량 +delta (운전석 범위 안에서, 최대 단계까지). 읽기 실패면 풍량은 건너뛴다(외기 전환은 이미 됐다)
        if (fanDelta != 0) when (val cur = c.getFanSpeed()) {
            is Outcome.Ok -> {
                val (min, max) = c.fanRange()
                // 풍량을 내리는 환기(약한 바람으로 계속 들이기, v0.7.4)도 1단 아래로는 내리지 않는다(바람이 꺼지면 바깥 공기가 들어오지 않는다)
                val floor = if (fanDelta < 0) maxOf(min, minOf(1, max)) else min
                val target = (cur.value + fanDelta).coerceIn(floor, maxOf(floor, max))
                if (target != cur.value) when (val r = c.setFanSpeed(target)) {
                    is Outcome.Ok -> {
                        // 차가 실제로 둔 값을 본다. 명령과 다르면 그 값도 우리 것으로 기억한다(운전자 조작으로 잘못 보지 않게)
                        val settled = Confirm.until(target, c::getFanSpeed, wait, readFirst = true).value
                        notes += if (settled == null || settled == target) "풍량 ${cur.value}→$target" else "풍량 ${cur.value}→$target 명령, 차는 $settled"
                        if (saved.fanSpeed == null) { saved.fanSpeed = Snap(cur.value, target); saved.fanSettled = settled?.takeIf { it != target } }
                    }
                    is Outcome.Fail -> notes += "풍량 실패: ${FailureCode.normalize(r.error)}"
                } else notes += if (fanDelta < 0) "풍량 이미 가장 약함(${cur.value})" else "풍량 이미 최대($max)"
            }
            is Outcome.Fail -> notes += "풍량 읽기 실패, 건너뜀"
        }
        // 3) 전원을 우리가 켜지 못했는데 명령을 받은 차가 스스로 켰으면 그것도 원복 대상이다
        if (powerBefore == false && saved.power == null && (c.getHvacPower() as? Outcome.Ok)?.value == true) {
            saved.power = Snap(false, true)
            notes += "명령을 받은 차가 공조 전원을 켬"
        }
        return true to notes.joinToString(", ")
    }

    private suspend fun coolDown(c: VehicleControls, delta: Float): Pair<Boolean, String?> {
        val curV = when (val cur = c.getTemperature()) {
            is Outcome.Ok -> cur.value
            is Outcome.Fail -> return false to FailureCode.READ_FAILED
        }
        if (curV > 45f) return false to "온도 값 $curV 가 섭씨가 아닌 듯, 건너뜀"
        val (min, max) = c.temperatureRangeC()
        val target = (curV + delta).coerceIn(min, max)
        // 공조 온도는 0.5도 단위가 보통이라 반올림
        val rounded = (target * 2).roundToInt() / 2f
        if (abs(rounded - curV) < 0.25f) return true to "온도 이미 하한($min)"
        return when (val r = c.setTemperature(rounded)) {
            is Outcome.Ok -> {
                // 풍량과 같다: 차가 실제로 둔 값이 명령과 다르면 그 값도 우리 것으로 기억한다
                val settled = Confirm.until(rounded, c::getTemperature, wait, readFirst = true) { a, b -> abs(a - b) < 0.25f }.value
                if (saved.temperature == null) { saved.temperature = Snap(curV, rounded); saved.temperatureSettled = settled?.takeIf { abs(it - rounded) >= 0.25f } }
                true to "온도 $curV→$rounded"
            }
            is Outcome.Fail -> false to r.error
        }
    }

    private suspend fun openWindow(c: VehicleControls, percent: Int): Pair<Boolean, String?> {
        val curV = when (val cur = c.getWindow()) {
            is Outcome.Ok -> cur.value
            is Outcome.Fail -> return false to FailureCode.READ_FAILED
        }
        val (min, max) = c.windowRange()
        // 창문은 15% 이상 열지 않는다(CONCEPT). 범위의 percent 지점을 닫힘 쪽으로 내린다(범위 0~10 에서 15% 는 2 가 아니라 1).
        // 방향 반전 설정이면 max 쪽이 닫힘이다
        val p = percent.coerceIn(0, 15)
        val inverted = windowInverted()
        if (!WindowLimit.opens(p, min, max)) return false to "창문 한 칸이 ${p}% 보다 커서 열지 않음(범위 $min~$max)"
        val target = WindowLimit.positionAtMost(p, min, max, inverted)
        return when (val r = c.setWindow(target)) {
            is Outcome.Ok -> {
                if (saved.windowPos == null) saved.windowPos = Snap(curV, target)
                true to "창문 $curV→$target (범위 $min~$max${if (inverted) ", 방향 반전" else ""})"
            }
            is Outcome.Fail -> false to r.error
        }
    }

    private suspend fun addRestStop(stop: RestStopInfo): Pair<Boolean, String?> {
        // 경유지 이름은 화면·음성과 같은 쉼터 표시 이름
        val r = nav.addWaypoint(stop.lat, stop.lng, restStopDisplayName(stop.name))
        return if (r.isSuccess) {
            saved.waypointAdded = true
            lastRestStop = stop
            onRestStopAdded(stop)
            true to "${stop.routeLabel} ${restStopDisplayName(stop.name)}(${"%.1f".format(stop.distanceKm)}km) 경유지 추가"
        } else false to "경유지 추가 실패: ${r.exceptionOrNull()?.message}"
    }

    /** 되돌릴 항목 하나: 보내는 명령, 지금 값이 되돌린 값과 같은지 읽는 함수, 지금 값의 글자 */
    private class Restore(
        val label: String, val okNote: String,
        val send: suspend () -> Outcome<*>,
        /** 지금 값이 되돌린 값과 같은가. 못 읽으면 null */
        val matches: suspend () -> Boolean?,
        val current: suspend () -> String,
    ) {
        var error: String? = null
        var readable = false
        var done = false
        var resent = false
    }

    /** 먼저 한 번 읽고, 안 맞는 것이 있으면 0.5초 간격으로 최대 3초까지 다시 읽는다. 읽을 수 없는 항목은 기다리지 않는다 */
    private suspend fun settle(items: List<Restore>) {
        var waited = 0L
        while (true) {
            for (i in items) if (!i.done) {
                val m = i.matches()
                if (m != null) i.readable = true
                if (m == true) i.done = true
            }
            if (items.all { it.done || !it.readable } || waited >= Confirm.TIMEOUT_MS) return
            wait(Confirm.STEP_MS); waited += Confirm.STEP_MS
        }
    }

    /** 되돌리는 명령을 보내고 확인한다. 안 된 것은 한 번 다시 보낸다. 전부 됐으면 true */
    private suspend fun restoreAll(items: List<Restore>, notes: MutableList<String>): Boolean {
        if (items.isEmpty()) return true
        for (i in items) i.error = (i.send() as? Outcome.Fail)?.error
        settle(items)
        val left = items.filter { !it.done && (it.readable || it.error != null) }
        if (left.isNotEmpty()) {
            for (i in left) { i.resent = true; i.error = (i.send() as? Outcome.Fail)?.error }
            settle(left)
        }
        var ok = true
        for (i in items) when {
            i.done -> notes += if (i.resent) "${i.okNote}(한 번 다시 보냄)" else i.okNote
            i.error != null -> { ok = false; notes += "${i.label} 복원 실패: ${FailureCode.normalize(i.error!!)}" }
            !i.readable -> notes += "${i.okNote}(확인은 못 함)"
            else -> { ok = false; notes += "${i.label} 복원 확인 실패(읽은 값 ${i.current()})" }
        }
        return ok
    }

    /**
     * 원복. 풍량·온도·창문은 현재값이 set(또는 차가 실제로 둔 값)의 허용 오차 안일 때만 before 로(운전자가 바꿨으면 둔다).
     * 내기순환은 AUTO 가 바꿀 수 있어 현재값을 보지 않고 before 로. 단 실행 중 운전자 조작이 관찰됐으면 둔다.
     * 명령을 보낸 뒤 값을 다시 읽어 확인하고, 안 됐으면 한 번 다시 보낸다. 공조 전원은 다른 값을 다 돌린 뒤 맨 끝에 돌린다
     */
    private suspend fun revert(controls: VehicleControls): Pair<Boolean, String?> {
        val s = saved
        val notes = mutableListOf<String>()
        val items = mutableListOf<Restore>()
        // 운전자가 공조를 직접 만졌는가. 그랬으면 전원은 켠 채로 둔다
        var driverUsedHvac = false
        s.recirculation?.let { snap ->
            if (s.recircTouchedByDriver) { driverUsedHvac = true; notes += "내기순환은 운전자가 바꿔서 그대로 둠" }
            else items += Restore(
                "내기순환", "내기순환 ${if (snap.before) "ON" else "OFF"} 복원",
                send = { controls.setRecirculation(snap.before) },
                matches = { (controls.getRecirculation() as? Outcome.Ok)?.value?.let { it == snap.before } },
                current = { (controls.getRecirculation() as? Outcome.Ok)?.value?.let { if (it) "ON" else "OFF" } ?: "못 읽음" },
            )
        }
        s.fanSpeed?.let { snap ->
            val (min, max) = controls.fanRange()
            val tol = toleranceInt(min, max)
            val cur = (controls.getFanSpeed() as? Outcome.Ok)?.value
            val ours = cur == null || abs(cur - snap.set) <= tol || s.fanSettled?.let { abs(cur - it) <= tol } == true
            if (!ours) { driverUsedHvac = true; notes += "풍량은 운전자가 바꿔서 그대로 둠($cur)" }
            else items += Restore(
                "풍량", "풍량 ${snap.before} 복원",
                send = { controls.setFanSpeed(snap.before) },
                matches = { (controls.getFanSpeed() as? Outcome.Ok)?.value?.let { it == snap.before } },
                current = { (controls.getFanSpeed() as? Outcome.Ok)?.value?.toString() ?: "못 읽음" },
            )
        }
        s.temperature?.let { snap ->
            val (min, max) = controls.temperatureRangeC()
            val tol = toleranceTemp(min, max)
            val cur = (controls.getTemperature() as? Outcome.Ok)?.value
            val ours = cur == null || abs(cur - snap.set) <= tol || s.temperatureSettled?.let { abs(cur - it) <= tol } == true
            if (!ours) { driverUsedHvac = true; notes += "온도는 운전자가 바꿔서 그대로 둠($cur)" }
            else items += Restore(
                "온도", "온도 ${snap.before} 복원",
                send = { controls.setTemperature(snap.before) },
                matches = { (controls.getTemperature() as? Outcome.Ok)?.value?.let { abs(it - snap.before) < 0.25f } },
                current = { (controls.getTemperature() as? Outcome.Ok)?.value?.toString() ?: "못 읽음" },
            )
        }
        s.windowPos?.let { snap ->
            val (min, max) = controls.windowRange()
            val cur = (controls.getWindow() as? Outcome.Ok)?.value
            if (cur != null && abs(cur - snap.set) > toleranceInt(min, max)) notes += "창문은 운전자가 바꿔서 그대로 둠($cur)"
            else items += Restore(
                "창문", "창문 ${snap.before} 복원",
                send = { controls.setWindow(snap.before) },
                matches = { (controls.getWindow() as? Outcome.Ok)?.value?.let { it == snap.before } },
                current = { (controls.getWindow() as? Outcome.Ok)?.value?.toString() ?: "못 읽음" },
            )
        }
        var ok = restoreAll(items, notes)
        // 공조 전원: 환기 전에 꺼져 있었으면 다시 끈다. 내기순환과 풍량을 먼저 돌린 뒤라야 그 값들이 남는다
        s.power?.let { snap ->
            val cur = (controls.getHvacPower() as? Outcome.Ok)?.value
            when {
                driverUsedHvac -> notes += "공조 전원은 운전자가 공조를 만져서 켠 채로 둠"
                cur == snap.before -> notes += "공조 전원은 이미 ${if (snap.before) "켜져" else "꺼져"} 있음"
                else -> ok = restoreAll(listOf(Restore(
                    "공조 전원", "공조 전원 ${if (snap.before) "켬" else "끔"}",
                    send = { controls.setHvacPower(snap.before) },
                    matches = { (controls.getHvacPower() as? Outcome.Ok)?.value?.let { it == snap.before } },
                    current = { (controls.getHvacPower() as? Outcome.Ok)?.value?.let { if (it) "켜짐" else "꺼짐" } ?: "못 읽음" },
                )), notes) && ok
            }
        }
        // 경유지는 운전자가 쉼터로 가는 중일 수 있으니 원복에서 지우지 않는다. 스냅샷만 비운다.
        if (s.waypointAdded) notes += "경유지는 유지"
        saved = Saved()
        return ok to notes.joinToString(", ").ifEmpty { "바꾼 것 없음" }
    }

    // ---------- 모의 실행 (Pleos 없는 기기 / 연결 판정 실패). 모의 차량이 없을 때와 경유지 ----------

    private fun mock(a: Action): Pair<Boolean, String?> {
        when (a) {
            is Action.AddRestStop -> {
                saved.waypointAdded = true
                lastRestStop = a.stop
                onRestStopAdded(a.stop)
                onMockCall("addWaypoint(${restStopDisplayName(a.stop.name)})")
                return true to "mock: ${a.stop.routeLabel} ${restStopDisplayName(a.stop.name)}(${"%.1f".format(a.stop.distanceKm)}km)"
            }
            Action.Revert -> saved = Saved()
            else -> {}
        }
        return true to "mock"
    }

    private fun label(a: Action) = when (a) {
        is Action.Ventilate -> "외기 전환"
        is Action.CoolDown -> "온도 낮춤"
        is Action.OpenWindow -> "창문 열기"
        is Action.AddRestStop -> "졸음쉼터 경유지"
        Action.Revert -> "원복"
    }

    companion object {
        const val TAG = "Kkaeugi.Actuator"
        /** 우리 내기순환 명령 뒤 이 시간 안에 온 값은 차의 반응으로 본다(운전자 조작으로 세지 않는다) */
        const val RECIRC_SETTLE_MS = 3_000L
        /** 원복 허용 오차 비율(범위 대비) */
        const val TOLERANCE_RATIO = 0.05

        /** 정수 값(풍량, 창문)의 허용 오차: 범위의 5%, 최소 1 */
        fun toleranceInt(min: Int, max: Int): Int = maxOf(1, ((max - min) * TOLERANCE_RATIO).roundToInt())

        /** 온도의 허용 오차: 범위의 5%, 최소 0.5도(공조 온도 한 눈금) */
        fun toleranceTemp(min: Float, max: Float): Float = maxOf(0.5f, ((max - min) * TOLERANCE_RATIO).toFloat())
    }
}

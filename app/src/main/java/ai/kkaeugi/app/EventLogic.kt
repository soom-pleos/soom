package ai.kkaeugi.app

import ai.kkaeugi.engine.Action
import ai.kkaeugi.engine.SeatProfile
import ai.kkaeugi.engine.Seats
import ai.kkaeugi.engine.TrustState
import ai.kkaeugi.engine.WakeConfig
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * WakeViewModel 이 엔진 이벤트를 처리할 때 쓰는 작은 규칙들(순수 로직, JVM 테스트 대상).
 * 여기서 "판단"은 하지 않는다. 엔진이 낸 이벤트 순서를 보고 앱이 해야 할 뒷정리만 정한다.
 */

/** 개입 하나가 어떻게 끝났는가(ExecutionTracker.onDone) */
sealed interface ExecEnd {
    /** 실행한 적이 없다(거절, 무응답, 조건이 사라져 닫힘) */
    data object None : ExecEnd
    /** 원복까지 하고 끝났다 */
    data object Reverted : ExecEnd
    /** 원복 없이 끝났다. actions 는 그 실행의 액션 목록(스냅샷 폐기 대상) */
    data class Kept(val actions: List<Action>) : ExecEnd
    /** 쉼 모드에서 바깥 공기로 바꿔 두었다. 쉼이 끝나면 엔진이 Execute([Revert]) 를 내므로 스냅샷을 버리지 않는다 */
    data object HeldForRest : ExecEnd
}

/**
 * 원복 없는 실행을 가려낸다.
 *
 * 엔진의 보통 흐름은 Execute(제어) → Running → Execute([Revert]) → Done 이다.
 * "바깥 공기로 바꿔 둘까요?", "풍량을 올릴까요?" 처럼 원복이 없는 제안은 Execute(제어) → Done 으로 바로 끝난다.
 * 그때는 그 실행이 남긴 원복 스냅샷을 버려야 한다. 안 버리면 다음 개입의 원복이 내기순환과 풍량까지 되돌린다.
 *
 * 규칙: Execute(제어) 뒤에 Execute([Revert]) 없이 Done 이 오면 Kept(그 실행의 액션 목록).
 * 문장을 비교하지 않는다. 엔진 문장이 바뀌어도 규칙은 그대로다.
 *
 * 예외(계약 v4): 쉼 모드의 "쉬는 동안 바깥 공기로 둘까요?" 는 Execute(Ventilate) → Done(null) 로 닫히지만 원복이 남아 있다.
 * 쉼이 끝날 때 RestEnded → Execute([Revert]) → Done(null) 이 온다. 그래서 쉬는 동안의 Done 은 HeldForRest 로 돌려주고 스냅샷을 둔다.
 */
class ExecutionTracker {
    private var pending: List<Action>? = null
    private var reverted = false

    /** Execute 이벤트마다. 반환: 원복 요청인가 */
    fun onExecute(actions: List<Action>): Boolean {
        val isRevert = isRevert(actions)
        if (isRevert) { pending = null; reverted = true } else { pending = actions; reverted = false }
        return isRevert
    }

    /** Done 이벤트마다. resting: 쉼 모드 중인가(엔진의 isResting) */
    fun onDone(resting: Boolean = false): ExecEnd {
        val kept = pending
        val wasReverted = reverted
        pending = null
        reverted = false
        return when {
            kept != null && resting -> ExecEnd.HeldForRest
            kept != null -> ExecEnd.Kept(kept)
            wasReverted -> ExecEnd.Reverted
            else -> ExecEnd.None
        }
    }

    /** 세션이 바뀌거나 쉼 모드로 들어갈 때 */
    fun reset() { pending = null; reverted = false }

    companion object {
        fun isRevert(actions: List<Action>): Boolean = actions.size == 1 && actions[0] == Action.Revert
    }
}

/**
 * 바깥 공기로 달린 시간(화면 "오늘" 카드, 데모 속도의 도착 카드). 엔진의 freshAirMinutes 와 같은 기준으로 센다.
 *  - 구간 시작: 환기(Ventilate)가 성공한 때. 이미 열려 있으면 그대로 잇는다
 *  - 구간 끝: 원복이 끝난 때, 운전자가 내기순환으로 되돌린 때(엔진이 환기를 유지하는 중이 아니면), 쉼 모드에 들어갈 때, 주행이 끝날 때
 *  - 원복 없이 유지하기로 했으면(keeping) 구간이 닫힌 뒤에도 외기 상태면 곧바로 다시 연다. 쉬는 시간은 넣지 않는다
 *  - 닫힌 구간은 단위(분, 데모 속도면 초)로 반올림해 더한다. 최소 1. 0.5초가 안 되는 구간은 버린다(닫자마자 다시 닫히는 경우)
 * 열려 있는 구간은 지금까지 흐른 만큼을 그대로 보여 준다(내림).
 */
class FreshAirTally {
    private var since: Long? = null
    private var closedUnits = 0
    /** 운전자가 "바꿔 둘까요?"/"풍량을 올릴까요?"에 동의해 외기로 유지 중인가 */
    var keeping = false
        private set

    val open: Boolean get() = since != null

    private fun open(now: Long) { if (since == null) since = now }

    private fun close(now: Long, unitMs: Long) {
        since?.let {
            val ms = (now - it).coerceAtLeast(0L)
            if (ms >= MIN_INTERVAL_MS) closedUnits += (ms.toDouble() / unitMs).roundToInt().coerceAtLeast(1)
        }
        since = null
    }

    /** 환기가 성공했다 */
    fun onVentilated(now: Long) = open(now)

    /** 원복 없이 유지하기로 했다. 구간은 열어 둔다 */
    fun onKept(now: Long) { keeping = true; open(now) }

    /** 원복까지 끝났다. 유지 중이고 외기 상태면(졸음 개입이 겹친 경우) 구간을 다시 연다 */
    fun onReverted(now: Long, unitMs: Long, recirculating: Boolean) {
        close(now, unitMs)
        if (keeping && !recirculating) open(now)
    }

    /**
     * 내기순환으로 바뀌었다. 엔진이 환기를 유지하는 중(ventActive)이 아니면 구간을 닫는다.
     * 반환: 유지 중이었는가(운전자가 직접 되돌린 것. 엔진은 거절로 보고 쿨다운을 건다)
     */
    fun onRecirculationOn(now: Long, unitMs: Long, ventActive: Boolean): Boolean {
        if (!ventActive) close(now, unitMs)
        val was = keeping
        keeping = false
        return was
    }

    fun onRestStarted(now: Long, unitMs: Long) = close(now, unitMs)

    fun onRestEnded(now: Long, recirculating: Boolean) { if (keeping && !recirculating) open(now) }

    fun onTripEnded(now: Long, unitMs: Long) = close(now, unitMs)

    /** 화면에 보여 줄 누적 값(단위: 분, 데모 속도면 초) */
    fun units(now: Long, unitMs: Long): Int =
        closedUnits + (since?.let { ((now - it).coerceAtLeast(0L) / unitMs).toInt() } ?: 0)

    fun reset() { since = null; closedUnits = 0; keeping = false }

    companion object { const val MIN_INTERVAL_MS = 500L }
}

/**
 * 시연용 엔진 설정. 분 단위가 초로 해석된다: 내기순환 30초 상한, 환기 20초, 졸음 개입 30초, 거부 쿨다운 20초, 쉼 15초.
 * 숨 모델은 60배속(1초 = 1분). 핸들 무조작 기준(6초)은 줄이지 않는다
 */
object DemoSettings {
    fun config() = WakeConfig(
        staleAirMinutes = 30, staleAirMinutesNight = 20, ventilateSeconds = 20, drowsySeconds = 30, answerTimeoutSec = 8,
        declineCooldownMinutes = 20, longTripMinutes = 60, restMinutes = 15, demoFast = true,
    )

    /**
     * 지금 쓸 엔진 설정. 데모 속도 여부에 개발자 토글 둘을 얹는다.
     * 핸들 보조 신호와 졸음 개입의 창문은 실차에서 확인하기 전이라 기본은 꺼 둔다.
     * walkBreak: 오래 앉아 있으면 걷기 쉼을 묻는다(v0.7.3). 앱은 켜서 쓴다
     */
    fun build(demoFast: Boolean, steeringAssist: Boolean = false, allowWindow: Boolean = false, walkBreak: Boolean = false): WakeConfig =
        (if (demoFast) config() else WakeConfig()).copy(steeringAssist = steeringAssist, allowWindow = allowWindow, walkBreak = walkBreak)
}

/**
 * 맡기기 상태를 기기에 남기는 형식(순수 함수). 엔진에는 저장소가 없어서 앱이 TrustChanged 를 받을 때마다 저장하고
 * 앱이 시작할 때 WakeEngine.setTrust 로 돌려준다. 형식: "맡김(0/1),연속 동의,취소 횟수"
 */
object TrustCodec {
    fun encode(s: TrustState): String = "${if (s.delegated) 1 else 0},${s.consecutiveYes},${s.cancelCount}"

    /** 저장이 없거나 형식이 깨졌으면 처음 상태(맡기지 않음) */
    fun decode(text: String?): TrustState {
        val parts = text?.split(',') ?: return TrustState()
        if (parts.size != 3) return TrustState()
        val yes = parts[1].trim().toIntOrNull() ?: return TrustState()
        val cancels = parts[2].trim().toIntOrNull() ?: return TrustState()
        val delegated = when (parts[0].trim()) { "1" -> true; "0" -> false; else -> return TrustState() }
        return TrustState(delegated, yes.coerceAtLeast(0), cancels.coerceAtLeast(0))
    }

    /** 설정의 "2분 환기 맡기기" 토글. 켜면 취소 횟수를 지우고, 끄면 연속 동의도 처음부터 다시 센다 */
    fun toggled(prev: TrustState, on: Boolean): TrustState =
        if (on) prev.copy(delegated = true, cancelCount = 0) else TrustState()
}

/** 프로세스가 정리됐다 다시 떴을 때 트립을 이어 붙일지(순수 함수) */
object SessionRestore {
    /** 이 시간 안에 다시 뜨면 같은 주행으로 본다. 넘으면 새 주행이다(차에서 내렸다 탔을 가능성이 크다) */
    const val KEEP_MS = 30 * 60_000L

    /** 저장해 둔 시작 시각(벽시계)부터 흐른 분. 저장이 없거나, 1분이 안 됐거나, KEEP_MS 를 넘었으면 0 */
    fun carriedMinutes(startWallMs: Long, nowWallMs: Long): Int {
        if (startWallMs <= 0L) return 0
        val elapsed = nowWallMs - startWallMs
        return if (elapsed in 60_000L..KEEP_MS) (elapsed / 60_000L).toInt() else 0
    }
}

/**
 * 화면 표시용 계산. 엔진의 AirEstimate.minutesToLimit 은 올림한 정수라 "약 21분 뒤"처럼 문서의 계산값(20.2분)보다 크게 보인다.
 * 화면에는 반올림해서 보여 준다. 식은 엔진 CabinAirModel 과 같은 질량 수지(내기순환, 창문 닫힘일 때 공기 교환 = leakAch).
 */
object AirMath {
    /**
     * 내기순환이 이어질 때 지금 추정치(ppm)에서 한도(limit)까지 남은 분(모델 시간, 소수).
     * 이미 넘었으면 0, 정상상태가 한도에 못 미치면 null
     */
    fun minutesToLimitExact(ppm: Int, occupants: Int, limit: Int, c: WakeConfig): Double? {
        if (ppm >= limit) return 0.0
        val gen = occupants.coerceAtLeast(1) * c.co2PerPersonLph * 1e6 / c.cabinVolumeL.coerceAtLeast(1)
        val ach = c.leakAch
        if (ach < 1e-9) return if (gen > 0) (limit - ppm) / gen * 60.0 else null
        val css = c.outdoorPpm + gen / ach
        if (css <= limit) return null
        return ln((css - ppm) / (css - limit)) / ach * 60.0
    }

    /**
     * 화면에 쓸 "약 N분 뒤". 엔진 값(올림)이 null 이거나 0 이면 그대로 따른다(닿지 않음, 이미 넘음은 엔진이 정한다).
     * 그 밖에는 반올림한 값을 쓰되, 엔진 값과 1 넘게 벌어지면(창문 등 앱이 모르는 조건) 엔진 값을 쓴다. 최소 1
     */
    fun roundedMinutesToLimit(engineMinutes: Int?, ventilating: Boolean, ppm: Int, occupants: Int, limit: Int, c: WakeConfig): Int? {
        if (engineMinutes == null || engineMinutes <= 0 || ventilating) return engineMinutes
        val exact = minutesToLimitExact(ppm, occupants, limit, c) ?: return engineMinutes
        val rounded = exact.roundToInt().coerceAtLeast(1)
        return if (rounded in (engineMinutes - 1)..engineMinutes) rounded else engineMinutes
    }
}

/**
 * 엔진이 환기 제안을 미루는 사유를 홈 문구로 바꾼다(순수 함수).
 * 사유 코드는 엔진 Deferred.reason 과 같다: "arriving"(도착 5분 전), "car-auto"(차가 내외기 제어 중).
 * "lights-just-on"(하향등이 막 켜짐)은 길어야 3분이라 화면에 알리지 않는다.
 */
object HomeHold {
    const val ARRIVING = "arriving"
    const val CAR_AUTO = "car-auto"

    /** 지금 엔진 상태에서 보여 줄 사유. 도착 임박이 먼저다 */
    fun reason(arrivingSoon: Boolean, carAuto: Boolean): String? = when {
        arrivingSoon -> ARRIVING
        carAuto -> CAR_AUTO
        else -> null
    }

    /** Deferred 이벤트의 사유 중 홈에 알릴 것만 */
    fun fromDeferred(reason: String): String? = reason.takeIf { it == ARRIVING || it == CAR_AUTO }

    /** 홈 문구. etaMinutes 를 모르면 "곧 도착이라 묻지 않아요" */
    fun line(reason: String?, etaMinutes: Int?): String? = when (reason) {
        ARRIVING -> if (etaMinutes != null && etaMinutes >= 1) "도착 ${etaMinutes}분 전이라 묻지 않아요" else "곧 도착이라 묻지 않아요"
        CAR_AUTO -> "차가 내외기를 자동 조절 중이에요"
        else -> null
    }
}

/**
 * 시연 시나리오가 건드리는 실제 상태의 시작 전 값(순수 데이터). 시나리오가 도는 동안에는 이 값들을 기기에 저장하지 않고,
 * 끝나거나 중단되면 이 값으로 되돌린다. 시연 한 번이 실제 주행의 맡기기 집계나 저장해 둔 탑승 인원을 바꾸지 않게 한다.
 */
data class DemoSnapshot(
    val trust: TrustState,
    /** 정차 홈 스테퍼로 넣어 둔 탑승 인원 */
    val manualOccupants: Int,
    /** 데모 콘솔 칩: 차 자동 공조, 도착 예정(분, 없으면 null) */
    val carAuto: Boolean,
    val etaMinutes: Int?,
    /** 자리별 프로필(v0.7.3). 시나리오가 넣은 가족 설정을 걷어 내고 이 값으로 되돌린다 */
    val profiles: List<SeatProfile> = emptyList(),
)

/**
 * 자리별 프로필을 기기에 남기는 형식(순수 함수). 엔진에는 저장소가 없어서 앱이 저장해 두었다가 시작할 때 SeatProfilesChanged 로 넣는다.
 * 형식: 다섯 자리의 이름을 쉼표로 이은 글("DEFAULT,PREGNANT,CHILD,DEFAULT,DEFAULT"). 서버로 보내지 않는다
 */
object SeatProfileCodec {
    val NONE: List<SeatProfile> = List(Seats.COUNT) { SeatProfile.DEFAULT }

    fun encode(profiles: List<SeatProfile>): String = normalize(profiles).joinToString(",") { it.name }

    /** 저장이 없거나 모르는 이름이 섞여 있으면 그 칸은 기본으로 읽는다 */
    fun decode(text: String?): List<SeatProfile> {
        val parts = text?.split(',') ?: return NONE
        return List(Seats.COUNT) { i -> SeatProfile.entries.firstOrNull { it.name == parts.getOrNull(i)?.trim() } ?: SeatProfile.DEFAULT }
    }

    fun normalize(profiles: List<SeatProfile>): List<SeatProfile> = List(Seats.COUNT) { profiles.getOrNull(it) ?: SeatProfile.DEFAULT }

    /** 한 자리만 바꾼 목록 */
    fun with(profiles: List<SeatProfile>, seat: Int, profile: SeatProfile): List<SeatProfile> =
        normalize(profiles).mapIndexed { i, p -> if (i == seat) profile else p }
}

/** 시연 시나리오의 격리 상태(순수 로직). begin 은 이미 격리 중이면 처음 잡은 값을 그대로 둔다(시나리오를 다시 눌러도 덮지 않는다) */
class DemoIsolation {
    var snapshot: DemoSnapshot? = null
        private set
    val active: Boolean get() = snapshot != null

    fun begin(current: DemoSnapshot) { if (snapshot == null) snapshot = current }

    /** 격리를 끝내고 되돌릴 값을 돌려준다. 격리 중이 아니었으면 null */
    fun end(): DemoSnapshot? = snapshot.also { snapshot = null }
}

/**
 * 화면 밖에서 만료된 환기 제안 뒤 홈에 보여 줄 "다시 묻기까지"(분, 데모 속도면 초). 엔진과 같은 규칙:
 * 첫 번째는 5, 두 번 연속부터는 거절과 같은 값
 */
fun hiddenExpiryCooldown(streak: Int, declineCooldown: Int): Int =
    if (streak >= ai.kkaeugi.engine.WakeEngine.HIDDEN_EXPIRY_LONG_AFTER) declineCooldown else ai.kkaeugi.engine.WakeEngine.HIDDEN_EXPIRY_COOLDOWN_MINUTES

package ai.kkaeugi.app.vehicle

/**
 * HVAC 영역 탐색(순수 로직, JVM 테스트 대상).
 *
 * 2026-11-11 Pleos Connect v2.1.0 에뮬레이터 실측: 내기순환, 자동 내기순환, 풍량을 운전석 영역(0x1)으로 부르면
 * "area ID: 0x1 not supported for property ID: HVAC_RECIRC_ON" 으로 실패한다. 이 속성들은 좌석 여러 개를 합친 영역에 정의돼 있다.
 * 그래서 영역을 하드코딩하지 않고 속성마다 찾는다.
 *
 * 영역은 좌석 id 를 OR 한 마스크(int)로 다룬다(SDK 의 SeatValue.getId 와 같은 값. AAOS VehicleAreaSeat).
 */
enum class HvacProp(val label: String) {
    RECIRC("내기순환"), AUTO_RECIRC("자동 내기순환"), FAN("풍량"), TEMP("설정 온도"), POWER("공조 전원")
}

/** 영역 하나를 읽어 본 결과의 종류 */
enum class ProbeKind {
    /** 값이 왔다 */
    VALUE,
    /** 영역은 맞는데 지금은 값을 못 준다(PropertyNotAvailable. 공조 전원이 꺼져 있을 때 등) */
    UNAVAILABLE,
    /** 이 속성은 이 영역에 없다(area ID not supported) */
    UNSUPPORTED,
    /** 그 밖의 실패(응답 없음, 퍼미션 등). 영역이 맞는지 알 수 없다 */
    ERROR,
}

/** 후보 영역 하나를 읽어 본 결과. text 는 값 또는 예외 원문 */
data class ProbeRead(val mask: Int, val kind: ProbeKind, val text: String)

/** 속성 하나의 탐색 결과 */
data class HvacArea(
    val prop: HvacProp,
    /** 채택한 영역 마스크. null 이면 이 차에서는 지원 안 함 */
    val mask: Int?,
    val source: Source,
    /** 영역은 정했지만 지금은 값을 읽을 수 없다(보류) */
    val pending: Boolean = false,
    /** capability 가 준 영역 목록 원문(마스크). null 이면 조회 실패 */
    val capability: List<Int>? = null,
    val capabilityError: String? = null,
    /** 후보별 읽기 결과(탐색을 했을 때만) */
    val probes: List<ProbeRead> = emptyList(),
) {
    enum class Source(val label: String) { CAPABILITY("capability"), PROBE("탐색"), NONE("없음") }

    val supported: Boolean get() = mask != null
}

object HvacAreaRule {
    const val DRIVER = 0x1
    /** 1열 좌우 + 2열 좌·중·우. AAOS 기본 VHAL 의 HVAC_ALL */
    const val ALL_SEATS = 0x75
    /** 운전석 쪽 구역(1열 좌 + 2열 좌·중). AAOS 기본 VHAL 의 HVAC_LEFT */
    const val LEFT_ZONE = 0x31
    const val FRONT_ROW = 0x5

    /** 좌석 이름과 id(SDK SeatValue 와 같은 순서, 같은 값) */
    val SEATS: List<Pair<String, Int>> = listOf(
        "ROW_1_LEFT" to 0x1, "ROW_1_CENTER" to 0x2, "ROW_1_RIGHT" to 0x4,
        "ROW_2_LEFT" to 0x10, "ROW_2_CENTER" to 0x20, "ROW_2_RIGHT" to 0x40,
        "ROW_3_LEFT" to 0x100, "ROW_3_CENTER" to 0x200, "ROW_3_RIGHT" to 0x400,
    )

    /**
     * capability 로 못 정했을 때 차례로 읽어 볼 후보.
     * 전 좌석 합, 운전석 쪽 구역, 1열 좌우 합, 운전석 단독, 그 밖에 SDK 로 만들 수 있는 합(1열 가운데 포함, 3열 포함), 마지막으로 조수석 쪽 구역.
     * SDK 에는 전체/글로벌을 뜻하는 SeatArea 상수가 없다(GlobalArea 는 HVAC 호출에 못 넘긴다).
     */
    val CANDIDATES: List<Int> = listOf(ALL_SEATS, LEFT_ZONE, FRONT_ROW, DRIVER, 0x77, 0x7, 0x777, 0x44)

    fun hex(mask: Int): String = "0x" + Integer.toHexString(mask)

    /** 마스크에 든 좌석 이름(SDK SeatValue 순서) */
    fun seatNames(mask: Int): List<String> = SEATS.filter { (_, id) -> mask and id != 0 }.map { it.first }

    /** 좌석 이름들을 마스크로. 모르는 이름은 무시한다 */
    fun maskOf(seatNames: Collection<String>): Int = SEATS.filter { it.first in seatNames }.fold(0) { acc, s -> acc or s.second }

    /**
     * (a) capability 가 준 영역 중에서 고른다: 운전석을 포함하는 첫 영역, 없으면 첫 영역.
     * 0 이하(전역 또는 빈 영역)는 SeatArea 로 부를 수 없으니 뺀다. 고를 것이 없으면 null.
     */
    fun pickFromCapability(masks: List<Int>?): Int? {
        val usable = masks.orEmpty().filter { it > 0 }
        return usable.firstOrNull { it and DRIVER != 0 } ?: usable.firstOrNull()
    }

    /** 읽기 실패 문자열을 분류한다. 실측 문장: "area ID: 0x1 not supported for property ID: ...", "... is not available because of vendor error code: 0" */
    fun classify(error: String): ProbeKind = when {
        error.contains("not supported for property", ignoreCase = true) -> ProbeKind.UNSUPPORTED
        error.contains("NOT_SUPPORTED") || error.contains("IllegalArgumentException") -> ProbeKind.UNSUPPORTED
        isUnavailable(error) -> ProbeKind.UNAVAILABLE
        else -> ProbeKind.ERROR
    }

    /** "지금은 읽을 수 없음"(영역은 맞다). 실패가 아니라 보류로 다룬다 */
    fun isUnavailable(error: String): Boolean =
        error.contains("not available", ignoreCase = true) || error.contains("NotAvailable") || error.contains("NOT_AVAILABLE")

    /**
     * (b) 후보를 읽어 본 결과에서 고른다: 값이 온 첫 후보. 없으면 "지금은 읽을 수 없음"으로 답한 첫 후보(보류).
     * 반환: (마스크, 보류 여부). 고를 것이 없으면 null.
     */
    fun pickFromProbes(reads: List<ProbeRead>): Pair<Int, Boolean>? =
        reads.firstOrNull { it.kind == ProbeKind.VALUE }?.let { it.mask to false }
            ?: reads.firstOrNull { it.kind == ProbeKind.UNAVAILABLE }?.let { it.mask to true }

    /**
     * 탐색 결과를 합친다. capability 가 고른 영역을 한 번 읽어 본 결과(capabilityRead)가 "영역 미지원"이면 버리고 후보로 간다.
     * capabilityRead 가 null 이면 읽어 보지 않은 것으로 보고 그대로 믿는다.
     */
    fun decide(
        prop: HvacProp, capability: List<Int>?, capabilityError: String?,
        capabilityRead: ProbeRead?, probes: List<ProbeRead>,
    ): HvacArea {
        val fromCap = pickFromCapability(capability)
        if (fromCap != null && capabilityRead?.kind != ProbeKind.UNSUPPORTED) {
            return HvacArea(prop, fromCap, HvacArea.Source.CAPABILITY, pending = capabilityRead?.kind == ProbeKind.UNAVAILABLE, capability, capabilityError, probes)
        }
        val picked = pickFromProbes(probes)
        return if (picked != null) HvacArea(prop, picked.first, HvacArea.Source.PROBE, picked.second, capability, capabilityError, probes)
        else HvacArea(prop, null, HvacArea.Source.NONE, false, capability, capabilityError, probes)
    }

    /** 로그와 자가 진단의 한 줄 값: "0x75 (capability)" / "0x75 (탐색, 지금은 읽을 수 없음)" / "지원 안 함" */
    fun describe(a: HvacArea): String = when {
        a.mask == null -> "지원 안 함"
        a.pending -> "${hex(a.mask)} (${a.source.label}, 지금은 읽을 수 없음)"
        else -> "${hex(a.mask)} (${a.source.label})"
    }

    /** capability 원문 한 줄: "[0x75] ROW_1_LEFT+ROW_1_RIGHT+..." / "조회 실패: ..." / "빈 목록" */
    fun capabilityText(a: HvacArea): String = when {
        a.capability == null -> "조회 실패: ${a.capabilityError ?: "사유 없음"}"
        a.capability.isEmpty() -> "빈 목록"
        else -> a.capability.joinToString(", ") { m -> if (m > 0) "${hex(m)}=${seatNames(m).joinToString("+")}" else "${hex(m)}(SeatArea 로 못 부름)" }
    }

    /** 후보별 읽기 결과 한 줄: "0x75=꺼짐 · 0x31=영역 미지원 · 0x1=지금 못 읽음" */
    fun probesText(reads: List<ProbeRead>): String =
        if (reads.isEmpty()) "읽어 보지 않음" else reads.joinToString(" · ") { "${hex(it.mask)}=${probeShort(it)}" }

    fun probeShort(r: ProbeRead): String = when (r.kind) {
        ProbeKind.VALUE -> r.text
        ProbeKind.UNSUPPORTED -> "영역 미지원"
        ProbeKind.UNAVAILABLE -> "지금 못 읽음"
        ProbeKind.ERROR -> r.text.replace('\n', ' ').take(60)
    }
}

/** 창문을 percent 이하로만 여는 위치(순수 함수). 범위가 0~10 처럼 작을 때 반올림하면 15% 가 20% 가 된다. 닫힘 쪽으로 내린다 */
object WindowLimit {
    /**
     * percent 를 넘지 않는 가장 큰 열림 위치. 방향 반전이면 max 가 닫힘이다.
     * 한 칸이 percent 보다 크면(범위 0~5 의 15% 등) 닫힘 위치가 나온다. 그때는 열지 않는다(opens 로 확인).
     */
    fun positionAtMost(percent: Int, min: Int, max: Int, inverted: Boolean = false): Int {
        if (max <= min) return min
        val steps = Math.floorDiv((max - min).toLong() * percent.coerceIn(0, 100), 100L).toInt()
        return if (inverted) max - steps else min + steps
    }

    /** positionAtMost 가 닫힘 위치가 아닌가(실제로 열리는가) */
    fun opens(percent: Int, min: Int, max: Int): Boolean =
        max > min && Math.floorDiv((max - min).toLong() * percent.coerceIn(0, 100), 100L) > 0
}

/** 트립 주행시간 지원 여부 판정(순수 함수). 실측 문장: "ERROR_NOT_SUPPORTED_PROPERTY(60001): ..." */
object TripTimeSupport {
    fun isUnsupported(error: String): Boolean = error.contains("NOT_SUPPORTED")
}

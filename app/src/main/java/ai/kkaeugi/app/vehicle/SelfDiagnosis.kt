package ai.kkaeugi.app.vehicle

import ai.kkaeugi.app.signals.MonoClock
import ai.kkaeugi.app.signals.PleosNavSource
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 자가 진단 한 줄.
 *  OK: 성공. FAIL: 실패. HOLD: 보류(지금은 판단할 수 없다. 공조 전원이 꺼져 값을 못 읽는 경우 등).
 *  ALT: 대체(이 차에 없거나 선택 항목이라 다른 경로로 동작한다. 실패로 세지 않는다). SECTION: 구획 제목(집계에 안 든다)
 */
data class DiagLine(val name: String, val value: String?, val error: String? = null, val kind: Kind = if (error == null) Kind.OK else Kind.FAIL) {
    enum class Kind { OK, FAIL, HOLD, ALT, SECTION }

    /** 성공 줄인가(보류, 대체, 구획 제목은 아니다) */
    val ok: Boolean get() = kind == Kind.OK
    val failed: Boolean get() = kind == Kind.FAIL

    /** "항목: 값" / "항목: 실패 (사유)" / "항목: 보류 (사유)" / "항목: 값(대체 설명)" / "[구획]" */
    fun text(): String = when (kind) {
        Kind.FAIL -> "$name: 실패 ($error)"
        Kind.HOLD -> "$name: 보류 ($error)"
        Kind.SECTION -> "[$name]"
        Kind.OK, Kind.ALT -> "$name: ${value ?: ""}".trimEnd()
    }

    companion object {
        fun ok(name: String, value: String) = DiagLine(name, value)
        fun fail(name: String, reason: String) = DiagLine(name, null, reason.ifBlank { "사유 없음" })
        /** 보류: 지금은 읽을 수 없다. 실패로 세지 않는다 */
        fun hold(name: String, reason: String) = DiagLine(name, null, reason.ifBlank { "사유 없음" }, Kind.HOLD)
        /** 대체: 없어도 다른 경로로 동작한다. 실패로 세지 않는다 */
        fun alt(name: String, value: String) = DiagLine(name, value, null, Kind.ALT)
        fun section(name: String) = DiagLine(name, null, null, Kind.SECTION)
    }
}

/**
 * 자가 진단 보고서 형식(순수 함수, JVM 테스트 대상).
 * 첫 줄은 머리말, 둘째 줄은 "성공 N / 실패 M"(보류, 대체가 있으면 뒤에 붙는다), 그 아래 항목마다 한 줄.
 * 화면, Logcat(Kkaeugi.Diag), diag_latest.txt 가 같은 줄을 쓴다
 */
object DiagReport {
    const val TAG = "Kkaeugi.Diag"
    const val FILE_NAME = "diag_latest.txt"
    /** 없어도 앱이 도는 Pleos 퍼미션(짧은 이름). 거부여도 실패로 세지 않는다. 위치는 내비가 주 경로다 */
    val OPTIONAL_PERMISSIONS = setOf("FUSED_LOCATION")

    fun summary(lines: List<DiagLine>): String = buildString {
        append("성공 ${lines.count { it.ok }} / 실패 ${lines.count { it.failed }}")
        val hold = lines.count { it.kind == DiagLine.Kind.HOLD }
        val alt = lines.count { it.kind == DiagLine.Kind.ALT }
        if (hold > 0) append(" / 보류 $hold")
        if (alt > 0) append(" / 대체 $alt")
    }

    fun header(version: String, time: String): String = "숨 자가 진단 v$version · $time"

    /** 보고서 전체(복사, 파일 저장용) */
    fun format(header: String, lines: List<DiagLine>): String =
        (listOf(header, summary(lines)) + lines.map { it.text() }).joinToString("\n")

    /** 화면용: 항목 줄을 n 개 단으로 위에서 아래로 나눈다. 앞 단부터 채운다 */
    fun columns(lines: List<DiagLine>, n: Int): List<List<DiagLine>> {
        if (lines.isEmpty() || n <= 1) return listOf(lines)
        val per = (lines.size + n - 1) / n
        return lines.chunked(per)
    }

    /** 퍼미션 이름에서 앞머리를 뗀 짧은 이름: "pleos.car.permission.CAR_DRIVE" → "CAR_DRIVE" */
    fun shortPermission(name: String): String = name.substringAfterLast('.')

    /**
     * 퍼미션 한 줄. protection 은 기기가 알려 준 보호 수준(dangerous 면 런타임 요청이 필요한 종류). 모르면 null.
     * 선택 퍼미션(OPTIONAL_PERMISSIONS)의 거부는 대체로 적는다.
     */
    fun permissionLine(name: String, granted: Boolean, protection: String? = null): DiagLine {
        val short = shortPermission(name)
        val level = protection?.let { ", 보호 수준 $it" } ?: ""
        return when {
            granted -> DiagLine.ok("퍼미션 $short", "허용")
            short in OPTIONAL_PERMISSIONS -> DiagLine.alt("퍼미션 $short", "거부(선택 항목, 내비 위치로 대체$level)")
            else -> DiagLine.fail("퍼미션 $short", "거부$level")
        }
    }

    /** 이 속성이 없으면 앱의 핵심(내외기 읽기와 전환, 풍량)이 안 되는가 */
    fun isCoreHvac(prop: HvacProp): Boolean = prop == HvacProp.RECIRC || prop == HvacProp.FAN

    /**
     * "HVAC 영역 탐색" 섹션의 속성 하나: 채택한 영역, capability 가 준 영역 목록 원문, 후보별 읽기 결과.
     * 다음 실측에서 이 세 줄만 보면 영역을 확정할 수 있다.
     */
    fun hvacAreaLines(a: HvacArea): List<DiagLine> {
        val name = "${a.prop.label} 영역"
        val head = when {
            a.mask != null -> DiagLine.ok(name, HvacAreaRule.describe(a))
            isCoreHvac(a.prop) -> DiagLine.fail(name, "지원 안 함. capability 와 후보 영역 어디에서도 읽히지 않음")
            else -> DiagLine.alt(name, "지원 안 함(이 값 없이 동작)")
        }
        return listOf(
            head,
            DiagLine.ok("  ${a.prop.label} capability", HvacAreaRule.capabilityText(a)),
            DiagLine.ok("  ${a.prop.label} 후보별 읽기", HvacAreaRule.probesText(a.probes)),
        )
    }

    /**
     * HVAC 값 읽기가 실패했을 때의 줄. "지금은 읽을 수 없음"(PropertyNotAvailable)은 보류, 영역이 없는 속성은 핵심이면 실패, 아니면 대체.
     */
    fun hvacReadFailure(name: String, prop: HvacProp, error: String): DiagLine = when {
        error.startsWith(PleosCalls.AREA_UNSUPPORTED) -> if (isCoreHvac(prop)) DiagLine.fail(name, "이 차에서는 읽을 수 없음(영역 없음)") else DiagLine.alt(name, "이 차에 없음(이 값 없이 동작)")
        HvacAreaRule.isUnavailable(error) -> DiagLine.hold(name, "지금은 읽을 수 없음. 공조 전원이 꺼져 있으면 이렇게 답해요: ${error.take(120)}")
        else -> DiagLine.fail(name, error)
    }

    /** 트립 시간 읽기 실패. 미지원이면 앱 타이머로 대체(실패로 세지 않는다) */
    fun tripFailure(error: String): DiagLine =
        if (TripTimeSupport.isUnsupported(error)) DiagLine.alt("트립 시간 raw", "미지원, 앱 타이머 사용") else DiagLine.fail("트립 시간 raw", error)

    /** FusedLocation 을 못 쓸 때. 내비 위치가 있으면 대체, 없으면 실패 */
    fun fusedFallback(reason: String, navHasLocation: Boolean): DiagLine =
        if (navHasLocation) DiagLine.alt("FusedLocation", "선택 항목, 내비 위치로 대체 ($reason)")
        else DiagLine.fail("FusedLocation", "$reason. 내비 위치도 없음")

    /** 탑승 인원 집계 한 줄 값 */
    fun occupantText(count: Int, detail: String): String = "집계 결과 ${count}명(운전석 보정 포함) · $detail"
}

/**
 * 한 번에 자가 진단. Pleos 에뮬레이터에서 버튼 한 번으로 "무엇이 읽히고 무엇이 안 읽히는지"를 한 화면에 모은다.
 *
 * 읽기 전용 호출만 순서대로 부른다(차 값을 바꾸는 호출은 없다. 그건 "제어 검증"에 있다).
 * 항목마다 STEP_TIMEOUT_MS(3초) 안에 답이 없으면 실패로 적고 다음으로 간다. 하나가 예외를 던져도 계속한다.
 * 결과는 화면, Logcat(태그 Kkaeugi.Diag), diag_latest.txt(앱 내부와 앱 전용 외부 저장소)에 같은 줄로 남는다.
 */
class SelfDiagnosis(
    private val context: Context,
    private val calls: PleosCalls,
    private val nav: PleosNavSource,
    private val fused: FusedLocationCalls,
    /** "바깥 공기" 한 줄을 만드는 함수(값, 출처, 나이). 없으면 그 줄을 적지 않는다 */
    private val outdoor: (suspend () -> DiagLine)? = null,
    private val info: () -> Info,
) {
    /** 호출 없이 앱이 이미 알고 있는 상태 */
    data class Info(
        /** 연결 판정 결과와 설명("연결 gear=GEAR_PARK" / "모의: 응답 없음") */
        val connected: Boolean, val connectionDetail: String,
        /** 리스너 등록 실패 목록(PleosVehicleSource.permissionFailures) */
        val registerFailures: List<String>,
        val tripUnit: String,
        /** Pleos TTS, STT 초기화: null = 콜백이 아직 없음, "" = 성공, 그 밖 = 실패 사유 */
        val ttsError: String?, val sttError: String?,
        val micGranted: Boolean,
        /** 화면 밖 유지(실험): 꺼짐 / 켜짐 / 실패 사유 */
        val keepAliveOn: Boolean, val keepAliveError: String?,
        val windowInverted: Boolean,
    )

    private class Failed(reason: String) : Exception(reason)
    private fun <T> Outcome<T>.orFail(): T = when (this) { is Outcome.Ok -> value; is Outcome.Fail -> throw Failed(error) }

    /** onFail: 호출이 실패로 답했을 때(Outcome.Fail)의 줄. 기본은 실패. 보류나 대체로 분류할 항목만 넘긴다 */
    private suspend fun step(name: String, onFail: (String) -> DiagLine = { DiagLine.fail(name, it) }, block: suspend () -> String): DiagLine {
        val line = try {
            withTimeoutOrNull(STEP_TIMEOUT_MS) { DiagLine.ok(name, block()) } ?: DiagLine.fail(name, "응답 없음 ${STEP_TIMEOUT_MS / 1000}초")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Failed) {
            onFail(e.message ?: "실패")
        } catch (e: Throwable) {
            DiagLine.fail(name, PleosCalls.describe(e))
        }
        Log.i(DiagReport.TAG, line.text())
        return line
    }

    private fun note(line: DiagLine): DiagLine { Log.i(DiagReport.TAG, line.text()); return line }

    /** 전부 돌린다. 한 줄이 끝날 때마다 onProgress(지금까지의 줄) */
    suspend fun run(onProgress: (List<DiagLine>) -> Unit = {}): List<DiagLine> {
        val out = mutableListOf<DiagLine>()
        suspend fun add(name: String, onFail: (String) -> DiagLine = { DiagLine.fail(name, it) }, block: suspend () -> String) { out += step(name, onFail, block); onProgress(out.toList()) }
        fun hvacFail(name: String, prop: HvacProp): (String) -> DiagLine = { DiagReport.hvacReadFailure(name, prop, it) }
        fun add(line: DiagLine) { out += note(line); onProgress(out.toList()) }
        val i = info()

        add(if (i.connected) DiagLine.ok("연결 판정", i.connectionDetail) else DiagLine.fail("연결 판정", i.connectionDetail))

        // 매니페스트에 선언한 pleos.car.permission.* 전부
        val declared = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
        }.getOrNull().orEmpty().filter { it.startsWith(PLEOS_PERMISSION_PREFIX) }
        if (declared.isEmpty()) add(DiagLine.fail("퍼미션", "선언된 Pleos 퍼미션을 못 읽음"))
        for (p in declared) {
            val granted = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            add(DiagReport.permissionLine(p, granted, if (granted) null else PermissionLevel.of(context, p)))
        }
        // SDK AAR 이 넣은 서비스 바인딩 퍼미션(FusedLocation, Vehicle). 거부면 그 서비스에 붙지 못한다. 참고용이라 실패로 세지 않는다
        for (p in bindingPermissions()) {
            val granted = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            add(if (granted) DiagLine.ok("바인딩 ${DiagReport.shortPermission(p)}", "허용") else DiagLine.alt("바인딩 ${DiagReport.shortPermission(p)}", "거부(보호 수준 ${PermissionLevel.of(context, p)})"))
        }

        add("기어") { calls.getGear().orFail().name }

        // HVAC 영역 탐색: 속성별로 capability 원문과 후보 영역을 전부 읽은 결과. 여기서 정한 영역을 앱 전체가 쓴다
        add(DiagLine.section("HVAC 영역 탐색"))
        val areas = try {
            withTimeoutOrNull(AREA_BUDGET_MS) { calls.resolveHvacAreas(refresh = true, probeAll = true) }
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { Log.w(DiagReport.TAG, "영역 탐색 예외: ${PleosCalls.describe(e)}"); null }
        if (areas == null || areas.isEmpty()) add(DiagLine.fail("HVAC 영역 탐색", if (areas == null) "응답 없음 ${AREA_BUDGET_MS / 1000}초 또는 예외" else "Vehicle SDK 없음"))
        else for (a in areas.values) for (l in DiagReport.hvacAreaLines(a)) add(l)
        add(DiagLine.section("HVAC 값"))
        add("공조 전원", hvacFail("공조 전원", HvacProp.POWER)) { if (calls.getHvacPower().orFail()) "켜짐" else "꺼짐" }
        add("내기순환", hvacFail("내기순환", HvacProp.RECIRC)) { if (calls.getRecirculation().orFail()) "켜짐(내기)" else "꺼짐(외기)" }
        add("자동 내기순환", hvacFail("자동 내기순환", HvacProp.AUTO_RECIRC)) { if (calls.getAutoRecirculation().orFail()) "켜짐" else "꺼짐" }
        add("풍량", hvacFail("풍량", HvacProp.FAN)) { "현재 ${calls.getFanSpeed().orFail()} / 최대 ${calls.fanMaxText()}" }
        add("설정 온도", hvacFail("설정 온도", HvacProp.TEMP)) { "${calls.getTemperature().orFail()}C (범위 ${calls.temperatureRangeText()})" }
        add(DiagLine.section("창문, 좌석, 그 밖"))
        val windowBounds = runCatching { withTimeoutOrNull(STEP_TIMEOUT_MS) { (calls.windowBounds() as? Outcome.Ok)?.value } }.getOrNull()
        for (w in PleosCalls.SIDE_WINDOWS) add("창문 ${w.name}") {
            val raw = calls.getWindowAt(w).orFail()
            val b = windowBounds?.entries?.firstOrNull { it.key == WindowArea(w) }?.value
            if (b == null) "raw $raw (min/max 못 읽음)"
            else "raw $raw (min ${b.minValue}, max ${b.maxValue}, 열림 ${WindowScale.percentOf(raw, b.minValue, b.maxValue, i.windowInverted)}%)"
        }
        for (s in PleosCalls.OCCUPANT_SEATS) add("좌석 점유 ${s.name}") { if (calls.getSeatOccupancy(s).orFail()) "있음" else "없음" }
        for (s in PleosCalls.OCCUPANT_SEATS) add("벨트 ${s.name}") { if (calls.getSeatBelt(s).orFail()) "체결" else "풀림" }
        // 주행 중 규칙(운전석은 값과 무관하게 1)으로 센 결과. 에뮬레이터 기본값(운전석 없음, 나머지 있음)이면 5명이 된다
        add("탑승 인원 집계") { calls.readOccupants(driving = true).let { DiagReport.occupantText(it.count, it.detail) } }
        add("핸들 각도") { "${calls.getSteeringAngle().orFail()}도" }
        add("핸들 각도 범위") { calls.steeringAngleBounds().orFail().let { "${it.minValue}~${it.maxValue}" } }
        add("하향등") { calls.getLowBeam().orFail().name }
        add("와이퍼") { calls.getWiper().orFail().name }
        add("텔테일 등록") {
            i.registerFailures.firstOrNull { it.startsWith("telltales") }?.let { throw Failed(it.substringAfter(": ")) }
            val on = calls.getTelltales().orFail().telltales.count { it.state != TelltaleState.OFF }
            "성공, 켜진 경고등 ${on}개"
        }
        add("트립 시간 raw", { DiagReport.tripFailure(it) }) { "${calls.getTripTimeRaw().orFail()} (단위: ${i.tripUnit})" }

        add(when {
            nav.navi == null -> DiagLine.fail("내비 초기화", "NaviHelper 없음")
            nav.initError != null -> DiagLine.fail("내비 초기화", nav.initError ?: "")
            nav.ready -> DiagLine.ok("내비 초기화", "준비됨")
            else -> DiagLine.fail("내비 초기화", "준비 콜백이 아직 없음")
        })
        add("내비 현재 위치") {
            val asked = MonoClock.now()
            nav.requestLocation()
            waitUntil { nav.lastPointAt >= asked }
            val p = nav.lastPoint ?: throw Failed("응답 없음")
            "%.2f, %.2f (heading %s)%s".format(p.lat, p.lng, p.heading?.let { "%.0f".format(it) } ?: "없음. 주행 방향을 알면 쉼터를 안내해요", if (nav.lastPointAt >= asked) "" else " 이전 값")
        }
        add("목적지 남은 시간·거리") {
            val asked = MonoClock.now()
            nav.requestDestination()
            waitUntil { nav.lastDestinationAt >= asked }
            val raw = nav.lastDestinationRaw ?: throw Failed("응답 없음. 내비에 목적지를 넣은 뒤 다시 눌러 주세요")
            EtaJudge.describe(raw.first, raw.second, nav.etaMinutes) + if (nav.lastDestinationAt >= asked) "" else " 이전 값"
        }
        // FusedLocation 은 선택 경로다. 위치는 내비가 주 경로이고, 내비 위치가 있으면 이 항목은 실패로 세지 않는다
        val navHasLocation = nav.lastPoint != null
        if (!fused.permissionGranted()) add(DiagReport.fusedFallback("FUSED_LOCATION 퍼미션 거부", navHasLocation))
        else add("FusedLocation", { DiagReport.fusedFallback(it, navHasLocation) }) {
            val p = fused.lastKnown() ?: throw Failed("위치 없음 또는 응답 없음")
            "%.2f, %.2f (heading %s). 속도는 SDK 값이 없어 위치 차이로 추정".format(p.lat, p.lng, p.heading?.let { "%.0f".format(it) } ?: "없음")
        }

        add(initLine("Pleos TTS 초기화", i.ttsError))
        add(initLine("Pleos STT 초기화", i.sttError))
        add(if (i.micGranted) DiagLine.ok("마이크 권한", "허용") else DiagLine.fail("마이크 권한", "거부. 음성 답 대신 화면 버튼만 써요"))
        add(notificationLine(i.keepAliveOn))
        add(when {
            i.keepAliveError != null -> DiagLine.fail("화면 밖 유지(실험)", i.keepAliveError)
            else -> DiagLine.ok("화면 밖 유지(실험)", if (i.keepAliveOn) "켜짐" else "꺼짐")
        })
        // 바깥 미세먼지(v0.7.0): 인터넷이 되는지 여기서 드러난다. 못 받아도 앱은 CO2 추정만으로 돌기 때문에 실패로 세지 않는다
        outdoor?.let { probe ->
            val line = try {
                withTimeoutOrNull(OUTDOOR_TIMEOUT_MS) { probe() } ?: DiagLine.alt("바깥 공기", "응답 없음 ${OUTDOOR_TIMEOUT_MS / 1000}초. 환기 판단은 CO2 추정만으로 해요")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DiagLine.alt("바깥 공기", "값 없음(${e::class.simpleName}). 환기 판단은 CO2 추정만으로 해요")
            }
            add(line)
        }
        return out
    }

    private fun initLine(name: String, error: String?): DiagLine = when {
        error == null -> DiagLine.fail(name, "초기화 콜백이 아직 없음")
        error.isEmpty() -> DiagLine.ok(name, "성공")
        else -> DiagLine.fail(name, error)
    }

    /** 알림 권한은 "화면 밖 유지"를 켤 때만 필요하다. 꺼져 있으면 거부여도 실패로 세지 않는다 */
    private fun notificationLine(keepAliveOn: Boolean): DiagLine {
        if (Build.VERSION.SDK_INT < 33) return DiagLine.ok("알림 권한", "요청 불필요(API ${Build.VERSION.SDK_INT})")
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return when {
            granted -> DiagLine.ok("알림 권한", "허용")
            keepAliveOn -> DiagLine.fail("알림 권한", "거부. 화면 밖 유지의 진행 알림이 안 보여요")
            else -> DiagLine.ok("알림 권한", "아직 요청 안 함(화면 밖 유지를 켤 때만 물어요)")
        }
    }

    /** SDK AAR 매니페스트가 합쳐 넣은 서비스 바인딩 퍼미션 */
    private fun bindingPermissions(): List<String> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
    }.getOrNull().orEmpty().filter { it.startsWith(PLEOS_BINDING_PREFIX) }

    private suspend fun waitUntil(done: () -> Boolean) {
        val until = MonoClock.now() + WAIT_MS
        while (!done() && MonoClock.now() < until) delay(100)
    }

    /** 보고서를 앱 내부 저장소와 앱 전용 외부 저장소에 남긴다(release 빌드는 adb 로 내부 파일을 못 읽는다). 실패해도 진단은 끝난 것이다 */
    fun save(report: String): ai.kkaeugi.app.gleo.ReportFiles.Saved = ai.kkaeugi.app.gleo.ReportFiles.write(context, DiagReport.FILE_NAME, report)

    companion object {
        const val STEP_TIMEOUT_MS = 3_000L
        /** 바깥 공기 값을 받아 보는 시간. HTTP 타임아웃(5초)보다 조금 길게 */
        const val OUTDOOR_TIMEOUT_MS = 8_000L
        /** 내비처럼 답이 리스너로 오는 호출을 기다리는 시간. STEP_TIMEOUT_MS 보다 짧아야 "이전 값" 을 적을 틈이 있다 */
        const val WAIT_MS = 2_500L
        const val PLEOS_PERMISSION_PREFIX = "pleos.car.permission."
        const val PLEOS_BINDING_PREFIX = "ai.pleos.playground.service."
        /** HVAC 영역 탐색 전체에 주는 시간(속성 5개, 후보 8개) */
        const val AREA_BUDGET_MS = 45_000L
    }
}

/** 퍼미션 보호 수준(기기가 알려 주는 값). dangerous 면 런타임에 요청해야 하는 종류다 */
object PermissionLevel {
    /** PermissionInfo.protectionLevel 의 기본 수준(아래 4비트)과 플래그를 글로 */
    fun describe(protectionLevel: Int): String {
        val base = when (protectionLevel and 0xf) {
            0 -> "normal"; 1 -> "dangerous"; 2 -> "signature"; 3 -> "signatureOrSystem"; 4 -> "internal"
            else -> "알 수 없음(${protectionLevel and 0xf})"
        }
        val flags = buildList {
            if (protectionLevel and 0x10 != 0) add("privileged")
            if (protectionLevel and 0x20 != 0) add("development")
            if (protectionLevel and 0x40 != 0) add("appop")
        }
        return if (flags.isEmpty()) base else "$base|${flags.joinToString("|")}"
    }

    fun isDangerous(protectionLevel: Int): Boolean = protectionLevel and 0xf == 1

    /** 기기에 정의돼 있지 않으면 "기기에 정의 안 됨" */
    fun of(context: Context, permission: String): String = raw(context, permission)?.let { describe(it) } ?: "기기에 정의 안 됨"

    @Suppress("DEPRECATION")
    fun raw(context: Context, permission: String): Int? =
        runCatching { context.packageManager.getPermissionInfo(permission, 0).protectionLevel }.getOrNull()
}

/** 런타임에 물어야 하는 퍼미션 고르기(순수 함수) */
object RuntimePermissions {
    /** protectionLevel: 기기가 알려 준 값. 정의가 없으면 null */
    data class State(val name: String, val granted: Boolean, val protectionLevel: Int?)

    /** 아직 허용되지 않았고 dangerous 로 정의된 것만 */
    fun toRequest(states: List<State>): List<String> =
        states.filter { !it.granted && it.protectionLevel != null && PermissionLevel.isDangerous(it.protectionLevel) }.map { it.name }
}

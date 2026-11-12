package ai.kkaeugi.app.gleo

import ai.kkaeugi.app.vehicle.ControlVerifier
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * adb 로 부르는 실측 명령의 순수 로직(JVM 테스트 대상). 수신기는 VerifyReceiver 하나이고 문자열 extra what 으로 가른다.
 *   fresh, fan, window, all   제어 검증(차 값을 바꿨다가 되돌린다)
 *   result                    마지막 제어 검증 결과를 다시 보여 준다
 *   diag                      자가 진단(읽기 전용)을 돌린다. diagresult 는 마지막 진단 결과를 다시 보여 준다
 *   state                     지금 읽은 값을 한 번에 적는다(읽기 전용)
 *   seats                     좌석 센서 대신 직접 입력. --es value "1,0,1,0,0"(운전석, 조수석, 뒷줄 왼쪽, 가운데, 오른쪽). value 가 sensor 면 센서 값으로 돌아간다
 *   help                      명령 목록
 * 결과는 세 곳에 남는다: 방송 결과(adb 가 `data="..."` 로 바로 보여 준다), Logcat(Kkaeugi.Verify), 파일(앱 내부와 앱 전용 외부 저장소).
 */
object AdbCommand {
    const val RESULT = "result"
    const val DIAG = "diag"
    const val DIAG_RESULT = "diagresult"
    const val STATE = "state"
    const val SEATS = "seats"
    const val HELP = "help"
    const val STATE_FILE = "state_latest.txt"

    val VERIFY: List<String> = ControlVerifier.KINDS
    val ALL: List<String> = VERIFY + listOf(RESULT, DIAG, DIAG_RESULT, STATE, SEATS, HELP)

    /** what 이 없으면 all(v0.7.3 과 같다). 앞뒤 공백과 대소문자는 가리지 않는다 */
    fun normalize(what: String?): String = what?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: ControlVerifier.ALL

    fun isKnown(what: String): Boolean = what in ALL

    /** adb 한 줄. extras 는 "--es what all" 꼴 */
    fun line(pkg: String, extras: String): String = "adb shell am broadcast -n $pkg/.gleo.VerifyReceiver -a ${VerifyReceiver.ACTION} $extras"

    fun help(pkg: String): String = listOf(
        "숨 adb 명령 (what 값)",
        "  all | fresh | fan | window  제어 검증: 값을 바꿨다가 되돌려요",
        "  result                      마지막 제어 검증 결과",
        "  diag                        자가 진단(읽기 전용). diagresult 는 마지막 진단 결과",
        "  state                       지금 읽은 값(읽기 전용)",
        "  seats --es value \"1,0,1,0,0\"  좌석 센서 대신 직접 입력(운전석, 조수석, 뒷줄 왼쪽, 가운데, 오른쪽). value sensor 면 센서 값으로",
        "예: ${line(pkg, "--es what state")}",
    ).joinToString("\n")

    /** seats 명령의 value */
    sealed interface SeatsArg {
        /** 다섯 자리(운전석, 조수석, 뒷줄 왼쪽, 가운데, 오른쪽)를 직접 넣는다 */
        data class Manual(val seats: List<Boolean>) : SeatsArg
        /** 좌석 센서 값으로 돌아간다 */
        data object Sensor : SeatsArg
        data class Bad(val reason: String) : SeatsArg
    }

    private val SENSOR_WORDS = setOf("sensor", "off", "auto", "센서")
    private val YES = setOf("1", "o", "y", "t", "true")
    private val NO = setOf("0", "x", "n", "f", "false")

    /**
     * "1,0,1,0,0" 을 다섯 칸으로 읽는다. 쉼표나 공백으로 나누고, 쉼표 없이 "10100" 도 받는다.
     * 다섯 칸이 아니거나 모르는 글자가 있으면 Bad. 운전석이 0 이어도 그대로 받는다(주행 중에는 엔진이 운전석을 탄 것으로 센다)
     */
    fun parseSeats(value: String?): SeatsArg {
        val raw = value?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return SeatsArg.Bad("value 가 없어요. 예: --es value \"1,0,1,0,0\" 또는 --es value sensor")
        if (raw in SENSOR_WORDS) return SeatsArg.Sensor
        val parts = raw.split(',', ' ', ';').filter { it.isNotEmpty() }.let { if (it.size == 1 && it[0].length == 5 && it[0].all { c -> c == '0' || c == '1' }) it[0].map { c -> c.toString() } else it }
        if (parts.size != 5) return SeatsArg.Bad("자리는 다섯 칸이에요(운전석, 조수석, 뒷줄 왼쪽, 가운데, 오른쪽). 받은 것: \"$value\"")
        val seats = parts.map { p -> when (p) { in YES -> true; in NO -> false; else -> return SeatsArg.Bad("자리 값은 1 이나 0 만 받아요. 받은 것: \"$value\"") } }
        return SeatsArg.Manual(seats)
    }

    /** 자리 값을 "1,0,1,0,0" 꼴로 */
    fun seatsText(seats: List<Boolean?>): String = seats.joinToString(",") { when (it) { true -> "1"; false -> "0"; null -> "?" } }
}

/** 상태 덤프의 글(순수 함수). 머리말 한 줄, 그 아래 "이름: 값" 한 줄씩 */
object StateReport {
    fun format(header: String, rows: List<Pair<String, String>>): String = (listOf(header) + rows.map { (k, v) -> "$k: $v" }).joinToString("\n")
}

/**
 * 보고서 파일을 두 곳에 쓴다: 앱 내부 저장소(files/)와 앱 전용 외부 저장소(getExternalFilesDir(null)).
 * release 빌드는 `run-as` 로 내부 파일을 읽을 수 없어서(v0.7.3 실측: "run-as: package not debuggable") 외부에도 쓴다.
 * 외부 경로는 보통 /sdcard/Android/data/<패키지>/files/ 이지만, 사용자가 여럿인 차량용 안드로이드에서는
 * /storage/emulated/<사용자 번호>/... 일 수 있다. 그래서 실제 경로를 결과와 Logcat 에 그대로 적는다
 */
object ReportFiles {
    data class Saved(val internal: Boolean, val externalPath: String?, val externalError: String?) {
        /** "내부 저장 O · 외부 /sdcard/Android/data/..." 한 줄 */
        fun text(): String = "내부 저장 ${if (internal) "됨" else "실패"} · " +
            (externalPath?.let { "읽기: adb shell cat ${adbPath(it)}" } ?: "외부 저장 실패(${externalError ?: "저장소 없음"}). --es what result 로 읽어 주세요")
    }

    fun write(context: Context, name: String, text: String): Saved {
        val internal = runCatching { File(context.filesDir, name).writeText(text) }.isSuccess
        var path: String? = null
        var failure: String? = null
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: throw IllegalStateException("외부 저장소 없음")
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).also { it.writeText(text) }.absolutePath
        }.onSuccess { path = it }.onFailure { failure = "${it.javaClass.simpleName}: ${it.message}" }
        return Saved(internal, path, failure)
    }

    /** 내부 파일을 먼저 읽고 없으면 외부 파일 */
    fun read(context: Context, name: String): String? =
        runCatching { File(context.filesDir, name).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: runCatching { context.getExternalFilesDir(null)?.let { File(it, name) }?.takeIf { it.exists() }?.readText() }.getOrNull()

    /** adb shell 에서 칠 경로. 첫 사용자(0)의 외부 저장소는 /sdcard 로 줄여 적는다. 다른 사용자면 받은 경로 그대로 */
    fun adbPath(absolutePath: String): String =
        if (absolutePath.startsWith("/storage/emulated/0/")) "/sdcard/" + absolutePath.removePrefix("/storage/emulated/0/") else absolutePath
}

/**
 * 화면(ViewModel)보다 오래 사는 코루틴 스코프. 제어 검증은 여기서 돈다:
 * 방송 수신기의 onReceive 가 끝나거나 화면이 닫혀도 원복까지 끝낼 수 있게 한다
 */
object AppScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

/**
 * 방송에 답하는 통로. finish 는 한 번만 먹는다. adb 는 방송이 끝나야 결과를 찍으므로, 일이 수신기가 붙들 수 있는 시간(HOLD_MS)
 * 안에 끝나면 끝난 결과를, 아니면 그때까지의 글(interim)을 돌려준다. 나머지는 Logcat 과 파일에 남는다
 */
class AdbReply(private val pending: BroadcastReceiver.PendingResult?) {
    private val finished = AtomicBoolean(false)
    /** 아직 도는 중일 때 돌려줄 글 */
    @Volatile var interim: String = ""
    val isFinished: Boolean get() = finished.get()

    fun finish(text: String, code: Int = CODE_DONE) {
        if (!finished.compareAndSet(false, true)) return
        runCatching { pending?.resultCode = code; pending?.resultData = text }
        runCatching { pending?.finish() }
    }

    /** 붙들 수 있는 시간이 다 됐다. 지금까지의 글로 끝낸다 */
    fun timeout() = finish(interim, CODE_RUNNING)

    companion object {
        /** 방송 결과 코드: 0 못 함, 1 끝남, 2 아직 도는 중(결과는 Logcat 과 파일에) */
        const val CODE_REFUSED = 0
        const val CODE_DONE = 1
        const val CODE_RUNNING = 2
        /** onReceive 뒤에 수신기를 붙들어 두는 시간. 방송 제한 시간(앞쪽 방송 10초)보다 짧게 둔다 */
        const val HOLD_MS = 8_500L
    }
}

/**
 * 제어 검증이 도는 동안 프로세스를 붙드는 서비스(알림 없음, 하는 일 없음). 검증이 수신기의 제한 시간보다 길 때,
 * 화면이 뒤로 가도 시스템이 프로세스를 멈추지 않게 한다. 앱이 뒤에 있으면 시작이 거절될 수 있다. 그때는 사유만 적고 검증은 그대로 돈다
 */
class VerifyHoldService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        /** 시작했으면 null, 못 했으면 사유 */
        fun begin(context: Context): String? = try {
            context.startService(Intent(context, VerifyHoldService::class.java))
            null
        } catch (e: Throwable) {
            Log.w(VerifyReceiver.TAG, "유지 서비스를 시작하지 못함: ${e.javaClass.simpleName}: ${e.message}")
            e.javaClass.simpleName
        }

        fun end(context: Context) { runCatching { context.stopService(Intent(context, VerifyHoldService::class.java)) } }
    }
}

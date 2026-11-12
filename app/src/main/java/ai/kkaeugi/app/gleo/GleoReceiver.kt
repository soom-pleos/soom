package ai.kkaeugi.app.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 앱 프로세스 안에서 수신기와 화면(ViewModel)을 잇는 다리. ViewModel 이 살아 있는 동안 처리 함수를 걸어 둔다.
 * 수신기는 매니페스트에 등록돼 있어 앱 화면이 없어도 불릴 수 있다. 그때는 엔진이 없으니 "앱을 먼저 열어 주세요"라고 답한다.
 * 마지막 호출과 답은 자가 진단이 읽는다
 */
object GleoBridge {
    /** 함수 이름과 파라미터를 받아 답을 돌려준다. 메인 스레드에서 부른다 */
    @Volatile var handler: ((function: String, params: Map<String, String>) -> GleoResponse)? = null
    /**
     * adb 명령(제어 검증, 자가 진단, 상태 덤프, 좌석 직접 입력)을 처리한다. what 은 AdbCommand 의 값, value 는 덧붙인 값.
     * 처리하는 쪽은 reply.finish 를 한 번 부른다(오래 걸리면 reply.interim 에 그때까지의 글을 넣어 둔다)
     */
    @Volatile var adb: ((what: String, value: String?, reply: AdbReply) -> Unit)? = null

    @Volatile var lastCall: String? = null
        private set
    @Volatile var lastResponse: String? = null
        private set
    @Volatile var calls = 0
        private set

    fun record(call: String, response: String) { lastCall = call; lastResponse = response; calls++ }
}

/**
 * Gleo AI 가 보낸 액션을 받는 수신기(component = "service" 경로: sendBroadcast).
 * Intent 의 action 이 함수 이름이고, 파라미터는 문자열 extra "ACTION_PARAM"(JSON)에 온다.
 * 문서의 변환 규칙에는 파라미터를 extra 하나씩 넣는다는 설명도 있어서 "topic" extra 도 함께 읽는다(adb 로 부를 때도 편하다).
 * 처리 결과는 방송 APP_ACTION_RESPONSE 로 돌려주고 Logcat(Kkaeugi.Gleo)에 같은 내용을 남긴다.
 *
 * adb 로 같은 경로를 부를 수 있다(실측용):
 *   adb shell am broadcast -n ai.kkaeugi.app/.gleo.GleoReceiver -a CHECK_CABIN_STATUS --es topic air
 */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = intent.action ?: return
        if (function !in Gleo.FUNCTIONS) return
        val raw = intent.getStringExtra(Gleo.EXTRA_PARAM)
        val params = GleoParams.parse(raw).toMutableMap()
        intent.getStringExtra(Gleo.TOPIC)?.let { params.putIfAbsent(Gleo.TOPIC, it) }
        Log.i(Gleo.TAG, "받음 $function ${Gleo.EXTRA_PARAM}=${raw ?: "(없음)"} params=$params")
        val response = try {
            GleoBridge.handler?.invoke(function, params) ?: GleoLogic.notRunning(function)
        } catch (e: Throwable) {
            Log.w(Gleo.TAG, "처리 중 예외 ${e.javaClass.simpleName}: ${e.message}")
            GleoLogic.refused(function)
        }
        val json = response.toJson()
        runCatching {
            context.sendBroadcast(Intent(Gleo.RESPONSE_ACTION).putExtra(Gleo.EXTRA_PACKAGE, context.packageName).putExtra(Gleo.EXTRA_RESPONSE, json))
        }.onFailure { Log.w(Gleo.TAG, "응답 방송 실패 ${it.javaClass.simpleName}: ${it.message}") }
        GleoBridge.record("$function $params", "${response.status.wire}: ${response.korean}")
        Log.i(Gleo.TAG, "응답 $json")
    }
}

/**
 * adb 로 부르는 실측 수신기. 화면을 누르지 않고 터미널 한 줄로 제어 검증, 자가 진단, 상태 덤프, 좌석 직접 입력을 한다:
 *   adb shell am broadcast -n ai.kkaeugi.app/.gleo.VerifyReceiver -a ai.kkaeugi.app.VERIFY --es what all
 * what 값과 뜻은 AdbCommand 에 있다(all, fresh, fan, window, result, diag, diagresult, state, seats, help).
 *
 * 결과가 남는 곳은 셋이다.
 *  - 방송 결과: adb 가 "Broadcast completed: result=1, data=..." 로 바로 찍는다. 일이 HOLD_MS(8.5초) 안에 끝나면 끝난 결과가,
 *    더 걸리면 그때까지의 글이 온다(result=2). 그때는 `--es what result` 로 다시 읽는다
 *  - Logcat 태그 Kkaeugi.Verify 에 한 줄씩
 *  - 파일: 앱 내부 files/ 와 앱 전용 외부 저장소(adb shell cat /sdcard/Android/data/ai.kkaeugi.app/files/verify_latest.txt)
 *
 * 수명: 일은 수신기가 아니라 앱 프로세스의 스코프(AppScope)에서 돈다. onReceive 는 goAsync 로 붙들고, 그보다 길어질 검증에는
 * VerifyHoldService 를 띄워 프로세스가 멈추지 않게 한다. 화면이 닫혀도 원복까지 끝낸다.
 * 차 값을 바꾸는 수신기라 매니페스트에서 android.permission.DUMP 를 가진 쪽(adb shell, 시스템)만 보낼 수 있게 막아 두었다
 */
class VerifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val what = AdbCommand.normalize(intent.getStringExtra(EXTRA_WHAT))
        val value = intent.getStringExtra(EXTRA_VALUE)
        val reply = AdbReply(runCatching { goAsync() }.getOrNull())
        Log.i(TAG, "[adb] 받음 what=$what${value?.let { " value=$it" } ?: ""}")
        val app = context.applicationContext
        val handler = GleoBridge.adb
        when {
            !AdbCommand.isKnown(what) -> {
                val text = "모르는 what=$what\n" + AdbCommand.help(app.packageName)
                text.lines().forEach { Log.i(TAG, it) }
                reply.finish(text, AdbReply.CODE_REFUSED)
            }
            what == AdbCommand.HELP -> reply.finish(AdbCommand.help(app.packageName))
            // 마지막 결과는 화면이 없어도 파일에서 읽어 준다
            what == AdbCommand.RESULT -> reply.finish(ReportFiles.read(app, ai.kkaeugi.app.vehicle.ControlVerifier.FILE_NAME) ?: "아직 제어 검증 결과가 없어요")
            what == AdbCommand.DIAG_RESULT -> reply.finish(ReportFiles.read(app, ai.kkaeugi.app.vehicle.DiagReport.FILE_NAME) ?: "아직 자가 진단 결과가 없어요")
            handler == null -> {
                val text = "숨 화면이 떠 있지 않아 시작하지 못했어요(what=$what). 앱을 먼저 열고 다시 보내 주세요: adb shell am start -n ${app.packageName}/.MainActivity"
                Log.i(TAG, "[adb] $text")
                if (what in AdbCommand.VERIFY) ReportFiles.write(app, ai.kkaeugi.app.vehicle.ControlVerifier.FILE_NAME, text)
                reply.finish(text, AdbReply.CODE_REFUSED)
            }
            else -> {
                // 붙들 수 있는 시간이 다 되면 그때까지의 글로 끝낸다. 일은 계속 돈다
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ reply.timeout() }, AdbReply.HOLD_MS)
                runCatching { handler(what, value, reply) }.onFailure { e ->
                    Log.w(TAG, "[adb] 처리 중 예외 ${e.javaClass.simpleName}: ${e.message}")
                    reply.finish("처리 중 예외 ${e.javaClass.simpleName}: ${e.message}", AdbReply.CODE_REFUSED)
                }
            }
        }
    }

    companion object {
        const val ACTION = "ai.kkaeugi.app.VERIFY"
        const val EXTRA_WHAT = "what"
        const val EXTRA_VALUE = "value"
        const val TAG = "Kkaeugi.Verify"
    }
}

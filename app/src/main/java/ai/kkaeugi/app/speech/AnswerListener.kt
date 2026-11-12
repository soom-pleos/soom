package ai.kkaeugi.app.speech

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * 운전자의 "응 / 괜찮아" 한마디를 듣는 역할. 제안 오버레이가 떠 있는 동안만 켠다.
 * 결과: true = 동의, false = 거부. 못 알아들으면 아무것도 부르지 않는다(버튼이나 타임아웃이 처리).
 */
interface AnswerListener {
    val name: String
    fun start(onAnswer: (Boolean) -> Unit)
    fun stop()
    fun release()

    companion object {
        /** 분류 규칙은 AnswerClassifier(순수 함수, 단위 테스트 있음) */
        fun classify(text: String): Boolean? = AnswerClassifier.classify(text)
    }
}

/** Pleos SpeechToText SDK (ai.pleos.playground.stt). 차량 마이크와 오디오 포커스는 플랫폼이 맡는다. */
class PleosAnswerListener private constructor(
    private val stt: ai.pleos.playground.stt.SpeechToText,
) : AnswerListener {
    override val name = "Pleos STT"
    private var onAnswer: ((Boolean) -> Unit)? = null
    private var listening = false

    private val resultListener = object : ai.pleos.playground.stt.listener.ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            if (text.isBlank()) return
            Log.i(TAG, "stt: \"$text\" final=$isFinal")
            // 중간(partial) 결과는 "어디..." 처럼 앞부분만 와서 오탐이 난다. 최종 결과만 판정한다.
            if (!isFinal) return
            val verdict = AnswerClassifier.classify(text)
            if (verdict == null) {
                Log.i(TAG, "stt: 판정 안 함(\"$text\"), 계속 듣는다")
                // 최종 결과가 왔는데 판정이 안 되면 인식 세션이 끝났을 수 있으니 다시 요청한다
                if (onAnswer != null) restart()
                return
            }
            val cb = onAnswer ?: return
            onAnswer = null
            stop()
            cb(verdict)
        }
        override fun onUpdatedEpdData(p0: Long, p1: Long) {}
        override fun onStartedRecognition() { Log.d(TAG, "recognition started") }
        override fun onEndedRecognition() { Log.d(TAG, "recognition ended"); listening = false }
        override fun onError(e: ai.pleos.playground.stt.constant.ErrorType) { Log.w(TAG, "stt error: $e"); listening = false }
        override fun onReady() {}
        override fun onUpdatedRms(p0: Double) {}
    }

    override fun start(onAnswer: (Boolean) -> Unit) {
        this.onAnswer = onAnswer
        if (listening) return
        listening = true
        runCatching { stt.request() }.onFailure { Log.w(TAG, "request failed: ${it.message}"); listening = false }
    }

    /** 판정 안 된 최종 결과 뒤 다시 듣기 */
    private fun restart() {
        listening = false
        runCatching { stt.stop() }
        listening = true
        runCatching { stt.request() }.onFailure { Log.w(TAG, "re-request failed: ${it.message}"); listening = false }
    }

    override fun stop() {
        onAnswer = null
        if (!listening) return
        listening = false
        runCatching { stt.stop() }
    }

    override fun release() {
        runCatching { stt.removeListener(resultListener); stt.release() }
    }

    companion object {
        const val TAG = "Kkaeugi.STT"

        fun tryCreate(context: Context, onReady: (PleosAnswerListener) -> Unit, onFail: (reason: String) -> Unit) {
            try {
                val stt = ai.pleos.playground.stt.SpeechToText(context, ai.pleos.playground.stt.constant.Mode.HYBRID)
                stt.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                    override fun onSuccess() {
                        runCatching { stt.setLocale(Locale.KOREAN) }
                        val l = PleosAnswerListener(stt)
                        runCatching { stt.addListener(l.resultListener) }
                        onReady(l)
                    }
                    override fun onFailure(e: ai.pleos.playground.stt.constant.ErrorType) {
                        Log.w(TAG, "Pleos STT init failed: $e"); onFail("초기화 실패 $e")
                    }
                })
            } catch (e: Throwable) {
                Log.w(TAG, "Pleos STT unavailable: ${e.message}"); onFail("SDK 없음: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}

/** STT 가 없는 기기용. 아무것도 듣지 않는다(버튼만). */
object NoAnswerListener : AnswerListener {
    override val name = "STT 없음"
    override fun start(onAnswer: (Boolean) -> Unit) {}
    override fun stop() {}
    override fun release() {}
}

package ai.kkaeugi.app.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 한 문장을 읽어 주는 역할. 주행 중에는 짧은 문장만 들어온다.
 * onDone 은 발화가 끝났을 때(완료/중단/오류 모두) 메인 스레드에서 한 번 불린다.
 * 엔진은 이 시점(SpeechFinished)부터 동의 대기 시간을 세고, STT 도 이때 켠다(TTS 자기 목소리를 듣지 않게).
 */
interface Speaker {
    val name: String
    fun speak(text: String, onDone: (() -> Unit)? = null)
    fun stop()
    fun release()

    companion object {
        /** 완료 콜백이 없는 환경에서 쓰는 추정: 글자 수 × 0.18초 + 0.5초 */
        fun estimateMs(text: String): Long = (text.length * 180L) + 500L
    }
}

/**
 * 완료 콜백을 한 번만, 메인 스레드에서 부르게 하는 도우미. SDK 콜백이 안 오면 추정 시간 뒤 강제로 부른다.
 */
internal class DoneOnce(text: String, private val cb: (() -> Unit)?) {
    private val main = Handler(Looper.getMainLooper())
    private var fired = false
    private val fallback = Runnable { fire("estimate") }
    init {
        // SDK 가 onDone 을 안 주는 경우 대비. 추정 시간 + 여유 2초
        main.postDelayed(fallback, Speaker.estimateMs(text) + 2_000L)
    }
    fun fire(reason: String) {
        main.post {
            if (fired) return@post
            fired = true
            main.removeCallbacks(fallback)
            Log.d("Kkaeugi.TTS", "speech done ($reason)")
            cb?.invoke()
        }
    }
    fun cancel() { fired = true; main.removeCallbacks(fallback) }
}

/**
 * 발화 세대 카운터(순수 로직, JVM 테스트 대상). TTS SDK 의 완료 콜백에는 "어느 문장의 완료인지"가 없다.
 * 문장 A 를 읽는 중에 B 를 시작하면 A 의 onDone/onStop 이 B 의 완료로 둔갑해 SpeechFinished 가 일찍 나간다.
 * 그래서 speak 마다 세대를 올리고, 그 세대의 onStart 를 본 뒤에 온 완료만 인정한다.
 */
class SpeechGeneration {
    @Volatile var generation = 0
        private set
    @Volatile private var startedGeneration = -1
    /** begin 뒤 아직 끝나지 않았는가 */
    @Volatile var speaking = false
        private set

    fun begin(): Int { generation++; speaking = true; return generation }
    fun onStart() { startedGeneration = generation }
    /** 완료/중단 콜백을 지금 세대의 것으로 받아도 되는가. 받으면 그 세대는 끝난 것으로 표시한다 */
    fun acceptEnd(): Boolean {
        if (startedGeneration != generation) return false
        startedGeneration = -1; speaking = false
        return true
    }
    fun end() { startedGeneration = -1; speaking = false }
}

/** Pleos TextToSpeech SDK. 차량 스피커·오디오 포커스를 플랫폼이 맡는다. 완료는 EventListener.onDone 으로 받는다. */
class PleosSpeaker private constructor(
    private val tts: ai.pleos.playground.tts.TextToSpeech,
) : Speaker {
    override val name = "Pleos TTS"
    @Volatile private var current: DoneOnce? = null
    private val gate = SpeechGeneration()

    private val eventListener = object : ai.pleos.playground.tts.listener.EventListener {
        override fun onStart() { gate.onStart() }
        override fun onReady() {}
        override fun onDone() { if (gate.acceptEnd()) current?.fire("onDone") else Log.d(TAG, "앞 문장의 onDone 무시") }
        override fun onStop() { if (gate.acceptEnd()) current?.fire("onStop") else Log.d(TAG, "앞 문장의 onStop 무시") }
        override fun onError(e: ai.pleos.playground.tts.constant.ErrorType) { Log.w(TAG, "tts error: $e"); current?.fire("onError") }
        override fun onUpdatedRms(p0: Double) {}
    }

    override fun speak(text: String, onDone: (() -> Unit)?) {
        // 앞 문장이 아직 읽히는 중이면 먼저 멈춘다. 그 문장의 onStop/onDone 은 새 세대의 onStart 전이라 버려진다
        current?.cancel()
        if (gate.speaking) runCatching { tts.stop() }
        gate.begin()
        current = DoneOnce(text, onDone)
        runCatching { tts.speak(text, false) }.onFailure { Log.w(TAG, "speak failed: ${it.message}"); current?.fire("exception") }
    }
    override fun stop() { runCatching { tts.stop() }; gate.end(); current?.fire("stop()") }
    override fun release() { runCatching { tts.removeEventListener(eventListener); tts.release() } }

    companion object {
        const val TAG = "Kkaeugi.TTS"

        /** 초기화에 실패하면 onFail. 호출자는 AndroidSpeaker 를 유지한다. */
        fun tryCreate(context: Context, onReady: (PleosSpeaker) -> Unit, onFail: (reason: String) -> Unit) {
            try {
                val tts = ai.pleos.playground.tts.TextToSpeech(context, ai.pleos.playground.tts.constant.Mode.HYBRID)
                tts.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                    override fun onSuccess() {
                        runCatching { tts.setLocale(Locale.KOREAN) }
                        val s = PleosSpeaker(tts)
                        runCatching { tts.addEventListener(s.eventListener) }
                        onReady(s)
                    }
                    override fun onFailure(e: ai.pleos.playground.tts.constant.ErrorType) {
                        Log.w(TAG, "Pleos TTS init failed: $e"); onFail("초기화 실패 $e")
                    }
                })
            } catch (e: Throwable) {
                Log.w(TAG, "Pleos TTS unavailable: ${e.message}"); onFail("SDK 없음: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}

/** Pleos 가 아닌 기기용 폴백. 데모 영상 촬영에도 쓴다. 완료는 UtteranceProgressListener 로 받는다. */
class AndroidSpeaker(context: Context) : Speaker {
    override val name = "Android TTS"
    private var ready = false
    private val pending = ArrayDeque<Pair<String, (() -> Unit)?>>()
    /** UtteranceProgressListener 는 다른 스레드에서 불린다 */
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, DoneOnce>()
    private lateinit var tts: android.speech.tts.TextToSpeech

    init {
        tts = android.speech.tts.TextToSpeech(context) { status ->
            ready = status == android.speech.tts.TextToSpeech.SUCCESS
            if (ready) {
                runCatching { tts.setLanguage(Locale.KOREAN) }
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { inflight.remove(id)?.fire("onDone") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { inflight.remove(id)?.fire("onError") }
                    override fun onStop(id: String?, interrupted: Boolean) { inflight.remove(id)?.fire("onStop") }
                })
                while (pending.isNotEmpty()) { val (t, cb) = pending.removeFirst(); speak(t, cb) }
            } else {
                // TTS 엔진이 없는 기기: 추정 시간으로 완료를 흉내 낸다
                while (pending.isNotEmpty()) { val (t, cb) = pending.removeFirst(); DoneOnce(t, cb) }
            }
        }
    }

    override fun speak(text: String, onDone: (() -> Unit)?) {
        if (!ready) { pending += text to onDone; return }
        val id = "kkaeugi-${System.nanoTime()}"
        inflight[id] = DoneOnce(text, onDone)
        val r = tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, id)
        if (r != android.speech.tts.TextToSpeech.SUCCESS) inflight.remove(id)?.fire("speak!=SUCCESS")
    }
    override fun stop() {
        runCatching { tts.stop() }
        inflight.values.toList().forEach { it.fire("stop()") }
        inflight.clear()
    }
    override fun release() { runCatching { tts.shutdown() } }
}

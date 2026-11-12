package ai.kkaeugi.app.signals

import ai.kkaeugi.engine.ActionResult
import ai.kkaeugi.engine.Signal
import ai.kkaeugi.engine.UserAnswer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 모든 신호가 모이는 한 줄기. Pleos SDK 소스와 데모 콘솔이 같은 버스에 쏜다.
 * 엔진은 어디서 온 신호인지 모른다. 그래서 Pleos 에뮬레이터에서도, 일반 폰에서도 같은 코드가 돈다.
 *
 * 버퍼(BUFFER)가 차면 새 신호는 버리고 onDrop 으로 알린다(로그). 다만 운전자의 답(UserAnswer)과 제어 결과(ActionResult)는
 * 버리면 엔진이 답이나 결과를 영영 기다리게 되므로 따로 들고 있다가 다음 emit 이나 flushPending 때 순서대로 다시 넣는다.
 */
class SignalBus(
    capacity: Int = BUFFER,
    private val onDrop: (Signal, kept: Boolean) -> Unit = { _, _ -> },
) {
    private val _signals = MutableSharedFlow<Signal>(extraBufferCapacity = capacity)
    val signals: SharedFlow<Signal> = _signals
    /** 버퍼가 차서 못 넣은 답과 제어 결과 */
    private val pending = ArrayDeque<Signal>()

    /** 구독자 수(테스트와 진단용) */
    val subscriberCount: Int get() = _signals.subscriptionCount.value

    /** 지금 들고 있는(아직 못 넣은) 신호 수 */
    val pendingCount: Int get() = synchronized(pending) { pending.size }

    fun emit(s: Signal) {
        synchronized(pending) {
            drain()
            // 앞서 못 넣은 것이 남아 있으면 순서를 지키려고 뒤에 세운다
            if (pending.isEmpty() && _signals.tryEmit(s)) return
            val keep = critical(s)
            if (keep) pending.addLast(s)
            runCatching { onDrop(s, keep) }
        }
    }

    /** 들고 있던 답과 제어 결과를 다시 넣어 본다. 1초마다 부른다 */
    fun flushPending() = synchronized(pending) { drain() }

    private fun drain() {
        while (pending.isNotEmpty() && _signals.tryEmit(pending.first())) pending.removeFirst()
    }

    companion object {
        const val BUFFER = 256
        /** 버리면 안 되는 신호: 운전자의 답과 제어 결과 */
        fun critical(s: Signal): Boolean = s is UserAnswer || s is ActionResult
    }
}

/** 신호 소스. start() 는 연결에 성공하면 true. 실패해도 앱은 데모 소스로 계속 돈다. */
interface SignalSource {
    val name: String
    fun start(): Boolean
    fun stop()
}

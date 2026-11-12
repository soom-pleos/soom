package ai.kkaeugi.app.signals

import android.os.SystemClock

/**
 * 앱 전체가 쓰는 단조 시계. 계약(Contract.kt)대로 Signal.t 와 engine.tick(now) 는 전부 이 값이다.
 * 벽시계(System.currentTimeMillis)는 NTP 보정으로 튀어서 카운트다운이 어긋나므로 쓰지 않는다.
 * 사람이 읽는 시각(로그의 HH:mm)은 화면 표시에서만 벽시계를 쓴다.
 */
object MonoClock {
    fun now(): Long = SystemClock.elapsedRealtime()
}

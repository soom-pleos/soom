package ai.kkaeugi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 화면 밖에서도 유지(실험, 기본 꺼짐).
 *
 * 내비 같은 다른 앱이 앞에 있는 동안 시스템이 숨 프로세스를 정리하지 않도록 포그라운드 서비스 하나로 프로세스만 붙든다.
 * 엔진은 옮기지 않는다(WakeViewModel 에 그대로 있다). 진행 알림 1개, 버튼 없음.
 *
 * 개발자 토글이 켜져 있을 때만 MainActivity 가 화면이 보이는 동안 시작한다. 꺼져 있으면 이 파일의 코드는 한 줄도 실행되지 않는다.
 * 시작은 전부 try/catch 로 감싼다. 실패하면 onFailure 로 사유를 알리고(앱이 토글을 끄고 자가 진단에 사유를 적는다) 스스로 멈춘다.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "숨 유지", NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("숨이 켜져 있어요")
                .setContentText("다른 앱을 보는 동안에도 실내 공기 추정을 이어 가요")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "화면 밖 유지 시작")
        } catch (e: Throwable) {
            KeepAlive.fail("서비스 시작 실패: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { stopSelf() }
        }
        // 시스템이 정리하면 다시 띄우지 않는다. 화면이 다시 보일 때 MainActivity 가 시작한다
        return START_NOT_STICKY
    }

    companion object {
        const val TAG = "Kkaeugi.KeepAlive"
        const val CHANNEL_ID = "kkaeugi_keep_alive"
        const val NOTIFICATION_ID = 61
    }
}

/** KeepAliveService 를 켜고 끄는 입구. 토글이 켜져 있을 때만 MainActivity 가 부른다 */
object KeepAlive {
    /** 시작 실패 사유를 받을 곳(앱이 토글을 끄고 자가 진단에 적는다) */
    @Volatile var onFailure: ((String) -> Unit)? = null
    @Volatile var started = false
        private set

    /** 화면이 보이는 동안 부른다. 실패하면 onFailure 로 사유를 알리고 false */
    fun start(context: Context): Boolean = try {
        context.startForegroundService(Intent(context, KeepAliveService::class.java))
        started = true
        true
    } catch (e: Throwable) {
        fail("시작 요청 실패: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    fun stop(context: Context) {
        if (!started) return
        started = false
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }

    internal fun fail(reason: String) {
        Log.w(KeepAliveService.TAG, reason)
        started = false
        onFailure?.invoke(reason)
    }
}

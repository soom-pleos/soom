package ai.kkaeugi.app

import ai.kkaeugi.app.vehicle.PermissionLevel
import ai.kkaeugi.app.vehicle.RuntimePermissions
import ai.kkaeugi.app.vehicle.SelfDiagnosis
import ai.kkaeugi.app.ui.KkaeugiApp
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: WakeViewModel by viewModels()

    /** 마이크 권한 결과. 거부돼도 앱은 돈다(화면 버튼으로 답한다) */
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.onMicPermission(granted); askDangerousPleos() }

    /**
     * Pleos 퍼미션 가운데 기기가 dangerous(런타임 요청이 필요한 종류)로 정의한 것만 묻는다.
     * 2026-11-11 에뮬레이터 실측에서 FUSED_LOCATION 만 거부였다. 보호 수준이 dangerous 가 아니면 묻지 않는다(물어도 허용되지 않는다).
     * 거부돼도 앱은 돈다(위치는 내비가 주 경로다)
     */
    private val askPleos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> vm.onPleosPermissions(result) }
    private fun askDangerousPleos() {
        runCatching {
            @Suppress("DEPRECATION")
            val declared = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
                .filter { it.startsWith(SelfDiagnosis.PLEOS_PERMISSION_PREFIX) }
            val need = RuntimePermissions.toRequest(declared.map { p ->
                RuntimePermissions.State(p, checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED, PermissionLevel.raw(this, p))
            })
            if (need.isNotEmpty()) askPleos.launch(need.toTypedArray())
        }
    }

    /** 알림 권한 결과(화면 밖 유지를 켰을 때만 묻는다). 거부돼도 서비스는 시작해 본다(알림만 안 보인다) */
    private val askNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startKeepAlive() }
    private var askedNotification = false
    /** 이 화면이 화면 밖 유지 서비스를 시작했는가. 한 번도 시작하지 않았으면 멈추는 코드도 부르지 않는다 */
    private var keepAliveStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KkaeugiApp(vm) }
        // RECORD_AUDIO 는 위험 권한이라 매니페스트 선언만으로는 안 된다. "응/괜찮아" 음성 답을 들으려면 런타임에 받아야 한다
        // 권한 창은 한 번에 하나만 띄운다. 마이크 답이 온 뒤(또는 이미 있으면 바로) Pleos 의 런타임 퍼미션을 묻는다
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { vm.onMicPermission(true); askDangerousPleos() }
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
        // 개발자 토글 "화면 밖에서도 유지(실험)": 화면이 보이는 동안에만 켜고 끈다. 토글이 꺼져 있으면 서비스 쪽 코드는 부르지 않는다
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.map { it.keepAlive }.distinctUntilChanged().collect { on -> if (on) ensureKeepAlive() else stopKeepAlive() }
            }
        }
    }

    // 화면이 보이는지 엔진에 알린다. 다른 앱이 앞에 있는 동안 넘어간 제안은 거절로 세지 않는다
    override fun onStart() { super.onStart(); vm.onAppVisibility(true) }
    override fun onStop() { vm.onAppVisibility(false); super.onStop() }

    override fun onDestroy() {
        // 화면을 아주 닫을 때는 붙들어 둘 엔진도 없다
        if (isFinishing) stopKeepAlive()
        super.onDestroy()
    }

    /** 토글이 켜져 있을 때만 온다. 알림 권한(API 33 이상)을 한 번 묻고 서비스를 시작한다 */
    private fun ensureKeepAlive() {
        try {
            if (Build.VERSION.SDK_INT >= 33 && !askedNotification &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askedNotification = true
                askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            startKeepAlive()
        } catch (e: Throwable) {
            vm.onKeepAliveFailed("권한 요청 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startKeepAlive() {
        if (!vm.ui.value.keepAlive) return
        try {
            KeepAlive.onFailure = { reason -> runOnUiThread { vm.onKeepAliveFailed(reason) } }
            if (KeepAlive.start(this)) keepAliveStarted = true
        } catch (e: Throwable) {
            vm.onKeepAliveFailed("시작 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun stopKeepAlive() {
        // 화면이 다시 만들어지면(회전 등) 이 화면의 표시는 지워지지만 서비스는 돌고 있을 수 있다. 프로세스 쪽 표시도 본다
        if (!keepAliveStarted && !KeepAlive.started) return
        keepAliveStarted = false
        runCatching { KeepAlive.stop(this) }
    }
}

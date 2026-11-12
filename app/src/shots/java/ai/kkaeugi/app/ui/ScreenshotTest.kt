package ai.kkaeugi.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.TimeZone

/**
 * 화면을 JVM(layoutlib, Paparazzi)에서 렌더링해 docs/shots/<이름>.png 로 쓴다.
 * 낮 테마 22장(1280x720), 밤 테마 7장(이름 끝 "_밤"), 넓은 창 2장(1700x900, 1350x1000). 움직임은 끄고 찍는다.
 * `gradle :app:testReleaseUnitTest -Pscreenshots=true` 로만 돈다(app/build.gradle.kts 가 그때만 src/shots 를 테스트 소스에 넣는다).
 * 상태 표본은 ui/Previews.kt 의 PreviewStates 와 같다.
 */
class ScreenshotTest {

    /**
     * 스냅샷을 원본 크기 그대로 <dir>/<name>.png 로 쓴다. Paparazzi 기본 리포터는 1000px 로 줄이기 때문.
     * Android 유닛 테스트의 Kotlin 은 java.awt / javax.imageio 를 컴파일 때 못 보므로(android.jar 기준) 리플렉션으로 부른다. 실행은 JVM 이라 있다.
     */
    private class FullSizePngHandler(private val dir: File) : SnapshotHandler {
        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int): SnapshotHandler.FrameHandler {
            val type = SnapshotHandler.FrameHandler::class.java
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    // 이름이 "점검_" 으로 시작하는 장은 문서에 싣지 않는다. 빌드 폴더에만 쓴다(눈으로 확인하는 용도)
                    "handle" -> { write(args!![0], File(if (snapshot.name.orEmpty().startsWith(CHECK_PREFIX)) checkDir else dir, "${snapshot.name}.png")); null }
                    "close" -> null
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "toString" -> "FullSizePngHandler(${snapshot.name})"
                    else -> null
                }
            } as SnapshotHandler.FrameHandler
        }

        private fun write(image: Any, out: File) {
            out.parentFile.mkdirs()
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val rendered = Class.forName("java.awt.image.RenderedImage")
            imageIo.getMethod("write", rendered, String::class.java, File::class.java).invoke(null, image, "PNG", out)
            val w = image.javaClass.getMethod("getWidth").invoke(image)
            val h = image.javaClass.getMethod("getHeight").invoke(image)
            println("shot ${out.absolutePath} ${w}x$h")
        }

        override fun close() {}
    }

    private companion object {
        const val CHECK_PREFIX = "점검_"
        val checkDir = File(System.getProperty("kkaeugi.checkDir") ?: "build/check-shots")
    }

    private val shotsDir = File(System.getProperty("kkaeugi.shotsDir") ?: "docs/shots")

    /** 가로 화면 한 장의 크기. mdpi 라 1dp = 1px (Previews.kt 의 widthDp/heightDp 와 같은 크기) */
    private fun window(width: Int, height: Int) = DeviceConfig(
        screenHeight = height, screenWidth = width, xdpi = 160, ydpi = 160,
        orientation = ScreenOrientation.LANDSCAPE, density = Density.MEDIUM,
        softButtons = false, locale = "ko-rKR",
    )

    @get:Rule
    val paparazzi = Paparazzi(
        // 헤드유닛 가로 화면 1280x720
        deviceConfig = window(1280, 720),
        theme = "android:Theme.Material.NoActionBar.Fullscreen",
        renderingMode = SessionParams.RenderingMode.NORMAL,
        showSystemUi = false,
        maxPercentDifference = 100.0,
        snapshotHandler = FullSizePngHandler(shotsDir),
        useDeviceResolution = true, // 1000px 썸네일로 줄이지 않고 1280x720 그대로
    )

    @Test
    fun allScreens() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        // 15, 16 은 v0.7.0 "숨 설계"에서 더한 두 장이다
        // 20~24 는 v0.7.3 "자리별로 줄인다"에서 더한 다섯 장이다
        val shots = PreviewStates.all + PreviewStates.breathShots + PreviewStates.extraShots + PreviewStates.seatShots + PreviewStates.coughShots
        for ((name, state) in shots) {
            paparazzi.snapshot(name) { KkaeugiScreen(state, dark = false, motion = false) }
        }
        paparazzi.snapshot(PreviewStates.COMPARE_NAME) { OccupantCompare(PreviewStates.aloneHome, PreviewStates.fourHome, dark = false) }
        // 밤 테마
        for ((name, state) in PreviewStates.nightShots) {
            paparazzi.snapshot(name) { KkaeugiScreen(state, dark = true, motion = false) }
        }
        // 넓은 창: 창 크기를 바꿔 가며 찍는다
        for (w in PreviewStates.wideShots) {
            paparazzi.unsafeUpdateConfig(deviceConfig = window(w.widthDp, w.heightDp))
            paparazzi.snapshot(w.name) { KkaeugiScreen(w.state, dark = false, motion = false) }
        }
        // 문서에는 싣지 않는 점검용 장(개발자 영역을 펼친 정차 화면, 직접 입력, 모의 모드 등)
        paparazzi.unsafeUpdateConfig(deviceConfig = window(1024, 600))
        paparazzi.snapshot(CHECK_PREFIX + "좁은창_홈") { KkaeugiScreen(PreviewStates.breathHome, dark = false, motion = false) }
        paparazzi.snapshot(CHECK_PREFIX + "좁은창_제안") { KkaeugiScreen(PreviewStates.proposalPassenger, dark = false, motion = false) }
        paparazzi.snapshot(CHECK_PREFIX + "좁은창_도착") { KkaeugiScreen(PreviewStates.arrivalThreeWay, dark = false, motion = false) }
        paparazzi.snapshot(CHECK_PREFIX + "좁은창_쉼") { KkaeugiScreen(PreviewStates.resting, dark = false, motion = false) }
        paparazzi.snapshot(CHECK_PREFIX + "좁은창_정차") { KkaeugiScreen(PreviewStates.idle, dark = false, motion = false) }
        paparazzi.snapshot(CHECK_PREFIX + "좁은창_자리설정") { KkaeugiScreen(PreviewStates.profileSheet, dark = false, motion = false) }
        paparazzi.snapshot(CHECK_PREFIX + "좁은창_도착_자리별") { KkaeugiScreen(PreviewStates.arrivalSeats, dark = false, motion = false) }
        paparazzi.snapshot(CHECK_PREFIX + "좁은창_살피는것") { KkaeugiScreen(PreviewStates.careHome, dark = false, motion = false) }
        paparazzi.unsafeUpdateConfig(deviceConfig = window(1280, 720))
        for ((name, state) in PreviewStates.checkShots) {
            paparazzi.snapshot(CHECK_PREFIX + name) { KkaeugiScreen(state, dark = name.endsWith("_밤"), motion = name.endsWith("_움직임켬")) }
        }
        val names = shots.map { it.first } + PreviewStates.COMPARE_NAME + PreviewStates.nightShots.map { it.first } + PreviewStates.wideShots.map { it.name }
        for (name in names) check(File(shotsDir, "$name.png").length() > 0) { "스크린샷 없음: $name" }
    }
}

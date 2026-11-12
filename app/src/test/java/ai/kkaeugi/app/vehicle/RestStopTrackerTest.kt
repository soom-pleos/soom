package ai.kkaeugi.app.vehicle

import ai.kkaeugi.app.signals.SignalBus
import ai.kkaeugi.engine.RestStopCandidate
import ai.kkaeugi.engine.RestStopInfo
import ai.kkaeugi.engine.RestStopProximity
import ai.kkaeugi.engine.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RestStopTracker 근접 판정: within / 밖으로 / passed(경유지 삭제) / RestEnded 로 종료 / 모의 위치 무시 / 후보 발행.
 * 주기 루프는 돌리지 않고 onLocation 으로 위치를 직접 넣는다(Unconfined 디스패처라 버스 발행이 즉시 일어난다).
 */
class RestStopTrackerTest {

    private val bus = SignalBus()
    private val received = mutableListOf<Signal>()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val collector = bus.signals.onEach { received += it }.launchIn(scope)

    // 석적 졸음쉼터(경부선 서울방향)
    private val seokjeok = RestStop("석적", "경부선", "부산기점 + 서울종점", 36.05163274, 128.4080556)
    private val stopInfo = seokjeok.toInfo(12.0)
    private var removed = 0
    private val logs = mutableListOf<String>()
    private var lastKm: Double? = Double.NaN
    private var lastRoute: String? = "?"

    private val index = object : RestStopIndex {
        override fun nearestAhead(from: GeoPoint) = seokjeok to haversineKm(from.lat, from.lng, seokjeok.lat, seokjeok.lng)
        override fun currentRoute(from: GeoPoint) = "경부선"
    }

    private val tracker = RestStopTracker(
        bus, scope, index, locationProvider = { null },
        removeWaypoint = { removed++; Result.success(Unit) },
        onLog = { logs += it }, onProximityKm = { lastKm = it }, onRouteChanged = { lastRoute = it },
        now = { 1_000L }, dispatcher = Dispatchers.Unconfined,
    )

    @After fun tearDown() { collector.cancel(); scope.cancel() }

    private fun prox() = received.filterIsInstance<RestStopProximity>()

    /** 석적에서 남쪽으로 km 만큼 떨어진 점(위도 1도 ≈ 111km) */
    private fun southOf(km: Double) = GeoPoint(seokjeok.lat - km / 111.0, seokjeok.lng, heading = 0.0)

    @Test
    fun `300m 안에 들어오면 within=true 한 번, 밖으로 나가면 within=false`() {
        tracker.startTracking(stopInfo)
        tracker.onLocation(southOf(2.0))
        assertTrue(prox().isEmpty())
        assertEquals(2.0, lastKm!!, 0.05)
        tracker.onLocation(southOf(0.2))
        tracker.onLocation(southOf(0.1)) // 안에서 더 가까워져도 다시 보내지 않는다
        assertEquals(listOf(true), prox().map { it.within })
        assertTrue(tracker.withinNow)
        tracker.onLocation(southOf(0.5))
        assertEquals(listOf(true, false), prox().map { it.within })
        assertFalse(prox().any { it.passed })
    }

    @Test
    fun `1km 안까지 갔다가 1_5km 밖으로 멀어지면 passed 와 경유지 삭제, 추적 종료`() {
        tracker.startTracking(stopInfo)
        tracker.onLocation(southOf(3.0))
        tracker.onLocation(southOf(0.8))   // 1km 안 진입(300m 밖이라 within 은 없음)
        tracker.onLocation(southOf(0.9))
        assertTrue(prox().isEmpty())
        tracker.onLocation(southOf(1.6))   // 다시 멀어짐
        val last = prox().last()
        assertTrue(last.passed); assertFalse(last.within)
        assertEquals(1, removed)
        assertNull(tracker.tracking)
        assertNull(lastKm)
        // 추적이 끝났으니 더 와도 무시
        tracker.onLocation(southOf(0.1))
        assertEquals(1, prox().size)
    }

    @Test
    fun `멀리서 접근만 하는 중(1km 안 진입 전)에는 passed 가 아니다`() {
        tracker.startTracking(stopInfo)
        tracker.onLocation(southOf(5.0))
        tracker.onLocation(southOf(3.0))
        tracker.onLocation(southOf(1.6))
        assertTrue(prox().isEmpty())
        assertEquals(0, removed)
    }

    @Test
    fun `RestEnded 면 within=false 를 보내지 않고 추적만 끝낸다`() {
        tracker.startTracking(stopInfo)
        tracker.onLocation(southOf(0.1))
        assertEquals(listOf(true), prox().map { it.within })
        tracker.onRestEnded()
        assertNull(tracker.tracking)
        assertFalse(tracker.withinNow)
        assertEquals("RestEnded 뒤 추가 신호 없음", 1, prox().size)
        // 다음 쉼은 새 AddRestStop 뒤에만: 추적 없이 300m 안에 있어도 신호 없음
        tracker.onLocation(southOf(0.05))
        assertEquals(1, prox().size)
        assertEquals(0, removed)
    }

    @Test
    fun `추적 시작 시 이미 아는 위치로 바로 판정한다`() {
        tracker.onLocation(southOf(0.2)) // 추적 전 위치
        assertTrue(prox().isEmpty())
        tracker.startTracking(stopInfo)
        assertEquals(listOf(true), prox().map { it.within })
    }

    @Test
    fun `모의 위치(한국 밖 GPS)는 근접 판정을 하지 않는다`() {
        tracker.startTracking(stopInfo)
        tracker.onLocation(GeoPoint(37.42, -122.08, heading = 0.0)) // 에뮬레이터 기본값 → DemoLocation 으로 대체
        assertTrue(tracker.usingDemoLocation)
        assertTrue(prox().isEmpty())
        assertNull(lastKm)
        // 데모 콘솔 버튼으로만 발행
        tracker.mockWithin()
        assertEquals(listOf(true), prox().map { it.within })
        // 버튼 뒤에는 실제 위치가 와도 within=false 로 뒤집지 않는다
        tracker.onLocation(southOf(12.0))
        assertEquals(1, prox().size)
    }

    @Test
    fun `후보 계산은 노선과 쉼터를 발행한다`() {
        tracker.computeCandidate(southOf(12.0))
        assertEquals("경부선", lastRoute)
        assertEquals("경부선", tracker.currentRoute)
        val c = received.filterIsInstance<RestStopCandidate>().single()
        assertEquals("석적", c.stop!!.name)
        assertEquals("경부선 서울방향", c.stop!!.routeLabel)
        assertEquals(12.0, c.stop!!.distanceKm, 0.05)
    }

    @Test
    fun `데모 지나침 버튼은 passed 와 경유지 삭제`() {
        tracker.startTracking(stopInfo)
        tracker.mockPassed()
        assertTrue(prox().single().passed)
        assertEquals(1, removed)
        assertNull(tracker.tracking)
    }
}

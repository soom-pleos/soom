package ai.kkaeugi.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** RestStopMatcher 순수 함수 테스트. 상·하행 구분, 거리 한계, 전방 없음, 한국 밖 좌표. */
class RestStopMatcherTest {

    // 경부선 추풍령: 상·하행 쉼터가 길 양쪽에 거의 같은 좌표로 붙어 있다
    private val chupungToBusan = RestStop("추풍령", "경부선", "서울기점 + 부산종점", 36.21062321, 127.9886414)
    private val chupungToSeoul = RestStop("추풍령", "경부선", "부산기점 + 서울종점", 36.21098462, 127.9885463)
    // 봉산: 추풍령에서 남동쪽 약 7km
    private val bongsanToBusan = RestStop("봉산", "경부선", "서울기점 + 부산종점", 36.15643219, 128.025680)
    private val bongsanToSeoul = RestStop("봉산", "경부선", "부산기점 + 서울종점", 36.15651708, 128.0264901)
    private val pair = listOf(chupungToBusan, chupungToSeoul, bongsanToBusan, bongsanToSeoul)

    @Test
    fun `종점 도시를 direction 문자열에서 뽑는다`() {
        assertEquals("부산", RestStopMatcher.terminusOf("서울기점 + 부산종점"))
        assertEquals("서울", RestStopMatcher.terminusOf("부산기점 + 서울종점"))
        assertEquals("서울", RestStopMatcher.terminusOf("서울방향"))
        assertEquals("서울", RestStopMatcher.terminusOf("서울 방향"))
        assertEquals("상행", RestStopMatcher.terminusOf("상행"))
        assertEquals("양양", RestStopMatcher.terminusOf("서울기점 + 양앙종점")) // CSV 오타 보정
        assertNull(RestStopMatcher.terminusOf("알 수 없음"))
    }

    @Test
    fun `routeLabel 은 노선 종점방향 꼴`() {
        assertEquals("경부선 서울방향", chupungToSeoul.routeLabel)
        assertEquals("경부선 부산방향", chupungToBusan.routeLabel)
        assertEquals("중부선 하남방향", RestStop("오창", "중부선", "통영기점 + 하남종점", 36.73, 127.45).routeLabel)
        assertEquals("경부선 상행", RestStop("x", "경부선", "상행", 36.0, 128.0).routeLabel)
    }

    @Test
    fun `노선 대표 방위 - 경부선 서울방향은 북서, 부산방향은 남동`() {
        val toSeoul = RestStopMatcher.routeBearing(chupungToSeoul)!!
        val toBusan = RestStopMatcher.routeBearing(chupungToBusan)!!
        assertTrue("서울방향 $toSeoul", toSeoul in 300.0..360.0)
        assertTrue("부산방향 $toBusan", toBusan in 120.0..180.0)
        assertTrue(angleDiff(toSeoul, toBusan) > 170.0)
    }

    @Test
    fun `순환선은 노선 방위 없음`() {
        assertNull(RestStopMatcher.routeBearing(RestStop("x", "서울외곽선", "판교기점 + 일산종점", 37.5, 127.0)))
        assertTrue(RestStopMatcher.isLoop("대구외곽순환선"))
        assertTrue(RestStopMatcher.isLoop("서울외곽선"))
        assertFalse(RestStopMatcher.isLoop("경부선"))
    }

    @Test
    fun `현재 노선은 최근접 쉼터의 노선 - 거리와 방향 무관`() {
        val other = RestStop("칠곡", "대구외곽순환선", "동대구기점 + 달서종점", 35.9049, 128.4795)
        val stops = pair + other
        // 추풍령 바로 옆: 경부선
        assertEquals("경부선", RestStopMatcher.currentRoute(stops, GeoPoint(36.21, 127.99, heading = 150.0)))
        // 칠곡 쉼터 근처(뒤쪽 방향이어도): 대구외곽순환선
        assertEquals("대구외곽순환선", RestStopMatcher.currentRoute(stops, GeoPoint(35.90, 128.47, heading = 0.0)))
        assertNull(RestStopMatcher.currentRoute(emptyList(), GeoPoint(36.0, 128.0, heading = 0.0)))
    }

    @Test
    fun `진행 종점 - 경부선에서 북상이면 서울, 남하면 부산, 직각이면 가까운 쪽`() {
        assertEquals("서울", RestStopMatcher.headingTerminus(pair, 330.0))
        assertEquals("부산", RestStopMatcher.headingTerminus(pair, 150.0))
        // 동쪽(90도): 서울방향(약 321도) 과 129도 차, 부산방향(약 141도) 과 51도 차 → 부산
        assertEquals("부산", RestStopMatcher.headingTerminus(pair, 90.0))
        // 서쪽(270도): 서울방향과 51도 차 → 서울
        assertEquals("서울", RestStopMatcher.headingTerminus(pair, 270.0))
    }

    @Test
    fun `상하행 쌍이 붙어 있을 때 북상 차량은 서울방향만 고른다`() {
        // 봉산 남동쪽 10km 지점에서 북서(330도)로 달리는 중. 전방에 봉산(10km)·추풍령(17km) 상하행이 모두 있다.
        val from = GeoPoint(36.08, 128.09, heading = 330.0)
        val pick = RestStopMatcher.nearestAhead(pair, from)
        assertNotNull(pick)
        assertEquals("봉산", pick!!.first.name)
        assertEquals("서울", pick.first.terminus)
    }

    @Test
    fun `상하행 쌍이 붙어 있을 때 남하 차량은 부산방향만 고른다`() {
        // 추풍령 북서쪽에서 남동(150도)으로 달리는 중
        val from = GeoPoint(36.29, 127.90, heading = 150.0)
        val pick = RestStopMatcher.nearestAhead(pair, from)
        assertNotNull(pick)
        assertEquals("추풍령", pick!!.first.name)
        assertEquals("부산", pick.first.terminus)
    }

    @Test
    fun `3km 미만은 제외하고 그 다음 쉼터를 고른다`() {
        // 추풍령 바로 옆(약 1km 북서)에서 남동으로 달리는 중. 추풍령은 전방이지만 3km 미만이라 제외, 봉산(약 8km)이 뽑힌다.
        val from = GeoPoint(36.218, 127.980, heading = 150.0)
        val pick = RestStopMatcher.nearestAhead(pair, from)
        assertNotNull(pick)
        assertEquals("봉산", pick!!.first.name)
        assertTrue(pick.second >= 3.0)
    }

    @Test
    fun `전방에 없으면 null - 후방으로 완화하지 않는다`() {
        // 봉산 남동쪽 10km 지점에서 남동(150도)으로 달리는 중. 네 쉼터 전부 뒤에 있다.
        val from = GeoPoint(36.08, 128.09, heading = 150.0)
        assertNull(RestStopMatcher.nearestAhead(pair, from))
    }

    @Test
    fun `80km 밖은 제외`() {
        val from = GeoPoint(35.20, 129.00, heading = 330.0) // 부산 근처, 추풍령까지 130km 이상
        assertNull(RestStopMatcher.nearestAhead(pair, from))
    }

    @Test
    fun `heading 을 모르면 방향 판정 불가로 null`() {
        assertNull(RestStopMatcher.nearestAhead(pair, GeoPoint(36.08, 128.09, heading = null)))
    }

    @Test
    fun `한국 밖 좌표 판정`() {
        assertTrue(RestStopMatcher.isInKorea(35.95, 128.45))
        assertTrue(RestStopMatcher.isInKorea(37.56, 126.98))
        assertFalse(RestStopMatcher.isInKorea(37.42, -122.08)) // Android 에뮬레이터 기본값(마운틴뷰)
        assertFalse(RestStopMatcher.isInKorea(0.0, 0.0))
        assertFalse(RestStopMatcher.isInKorea(35.68, 139.69)) // 도쿄
    }

    // ---- 실제 CSV(253곳) 로 2차 리뷰가 지적한 분기점 구간 ----

    private fun loadCsv(): List<RestStop>? {
        val csv = File("src/main/res/raw/rest_stops.csv")
        if (!csv.exists()) return null // 다른 작업 디렉터리에서 돌 때는 건너뜀
        return csv.bufferedReader().useLines { RestStopMatcher.parse(it) }.also { assertTrue(it.size > 200) }
    }

    @Test
    fun `실제 CSV 로 모의 위치에서 경부선 서울방향 석적을 고른다`() {
        val all = loadCsv() ?: return
        assertEquals("경부선", RestStopMatcher.currentRoute(all, DemoLocation.POINT))
        val pick = RestStopMatcher.nearestAhead(all, DemoLocation.POINT)
        assertNotNull("모의 위치 전방에 쉼터가 있어야 데모가 된다", pick)
        assertEquals("석적", pick!!.first.name)
        assertEquals("경부선", pick.first.route)
        assertEquals("서울", pick.first.terminus)
        assertTrue(pick.second in 3.0..80.0)
    }

    @Test
    fun `호남선 동광주에서 순천 방향 동진 - 광주대구선 담양이 아니라 호남선 삼기`() {
        val all = loadCsv() ?: return
        // 동광주 쉼터 위치에서 동쪽(85도)으로. 호남선 대표 방위(천안→순천, 약 169도)와는 84도 차라 옛 허용각 70도에서는 호남선이 전부 빠졌다.
        val from = GeoPoint(35.2086, 126.9394, heading = 85.0)
        assertEquals("호남선", RestStopMatcher.currentRoute(all, from))
        val pick = RestStopMatcher.nearestAhead(all, from)
        assertNotNull(pick)
        assertEquals("삼기", pick!!.first.name)
        assertEquals("순천", pick.first.terminus)
        assertTrue("거리 ${pick.second}", pick.second in 20.0..30.0)
    }

    @Test
    fun `경부선 검단에서 부산 방향 동진 - 대구외곽순환선 봉무가 아니라 경부선 북안`() {
        val all = loadCsv() ?: return
        // 검단 쉼터 위치에서 동쪽(85도). 전방 3.9km 에 순환선 봉무가 있지만 노선이 다르다.
        val from = GeoPoint(35.915, 128.6135, heading = 85.0)
        assertEquals("경부선", RestStopMatcher.currentRoute(all, from))
        val pick = RestStopMatcher.nearestAhead(all, from)
        assertNotNull(pick)
        assertEquals("북안", pick!!.first.name)
        assertEquals("부산", pick.first.terminus)
        assertTrue("거리 ${pick.second}", pick.second in 30.0..40.0)
    }

    @Test
    fun `경부선 하빈에서 부산 방향 - 3km 앞 순환선 칠곡이 아니라 경부선 검단`() {
        val all = loadCsv() ?: return
        // 하빈 쉼터 위치에서 남동(120도). 전방 cone 안 3.1km 에 대구외곽순환선 칠곡, 1.4km 에 지천(3km 미만), 12.7km 에 검단.
        val from = GeoPoint(35.9323, 128.4745, heading = 120.0)
        assertEquals("경부선", RestStopMatcher.currentRoute(all, from))
        val pick = RestStopMatcher.nearestAhead(all, from)
        assertNotNull(pick)
        assertEquals("검단", pick!!.first.name)
        assertEquals("부산", pick.first.terminus)
        assertTrue("거리 ${pick.second}", pick.second in 10.0..15.0)
    }

    @Test
    fun `순환선 위에서는 노선 잠금과 전방 cone 만 - 대구외곽순환선 칠곡에서 봉무`() {
        val all = loadCsv() ?: return
        // 칠곡(순환선) 쉼터 바로 옆에서 동쪽(90도). 경부선 검단(12km, 동쪽)이 더 가까이 전방에 있어도 노선이 다르다.
        val from = GeoPoint(35.9049, 128.4795, heading = 90.0)
        assertEquals("대구외곽순환선", RestStopMatcher.currentRoute(all, from))
        val pick = RestStopMatcher.nearestAhead(all, from)
        assertNotNull(pick)
        assertEquals("대구외곽순환선", pick!!.first.route)
        assertEquals("봉무", pick.first.name)
    }

    @Test
    fun `노선 방위를 아는데 어느 종점 방향도 아니면 후보 없음`() {
        // 경부선 쉼터 옆에서 북동(60도): 서울방향(321도)과 99도, 부산방향(141도)과 81도 → 부산. 허용각을 70도로 좁히면 둘 다 밖 → null
        val from = GeoPoint(36.21, 127.99, heading = 60.0)
        assertEquals("부산", RestStopMatcher.headingTerminus(pair, 60.0))
        assertNull(RestStopMatcher.nearestAhead(pair, from, routeToleranceDeg = 70.0))
    }
}

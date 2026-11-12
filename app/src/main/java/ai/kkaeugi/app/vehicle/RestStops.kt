package ai.kkaeugi.app.vehicle

import ai.kkaeugi.app.R
import ai.kkaeugi.engine.RestStopInfo
import android.content.Context
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 위도/경도와 진행 방향(도, 북쪽 0, 시계 방향). heading 을 모르면 null. */
data class GeoPoint(val lat: Double, val lng: Double, val heading: Double? = null)

/**
 * 졸음쉼터 한 곳. direction 은 CSV 원문("서울기점 + 부산종점", "서울방향", "상행" 등).
 * terminus 는 거기서 뽑은 종점 도시("부산"). routeLabel 은 화면/멘트용 "경부선 부산방향".
 */
data class RestStop(val name: String, val route: String, val direction: String, val lat: Double, val lng: Double) {
    val terminus: String? = RestStopMatcher.terminusOf(direction)
    val origin: String? = RestStopMatcher.originOf(direction)
    val routeLabel: String = RestStopMatcher.routeLabel(route, direction)
    fun toInfo(distanceKm: Double) = RestStopInfo(name = name, routeLabel = routeLabel, distanceKm = distanceKm, lat = lat, lng = lng)
}

fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/** 1 → 2 방위각(도, 0~360) */
fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lng2 - lng1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** 두 방위각의 차이(0~180) */
fun angleDiff(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

/**
 * 쉼터 선택 규칙(순수 함수, JVM 테스트 대상). Android 의존 없음.
 *
 * 1) 노선 잠금: 현재 위치에서 가장 가까운 쉼터(거리·방향 무관)의 route 를 "현재 노선"으로 본다.
 *    분기점·곡선 구간에서 방위만 보고 다른 고속도로 쉼터를 고르던 문제(호남선 동광주, 경부선 검단)를 막는다.
 * 2) 진행 종점: 그 노선 쉼터들의 대표 방위(기점 도시 → 종점 도시)와 heading 이 ±routeToleranceDeg(90도) 안인 종점을 고른다.
 *    두 종점이 다 들어오면 각도 차가 작은 쪽. 어느 쪽도 안 들어오면(노선을 벗어나는 중) 후보 없음.
 * 3) 후보: 같은 노선 + 같은 종점 + 전방 ±coneDeg(60도) + minKm(3)~maxKm(80). 최근접 한 곳.
 * 4) 순환선(route 에 "순환"/"외곽")이거나 도시 좌표를 몰라 방위를 못 구하는 노선은 노선 잠금 + 전방 cone 만 본다.
 * 전방에 없으면 null. 후방으로 완화하지 않는다.
 */
object RestStopMatcher {
    const val MIN_KM = 3.0
    const val MAX_KM = 80.0
    const val CONE_DEG = 60.0
    const val ROUTE_TOLERANCE_DEG = 90.0

    /** 한국 본토 대략 범위. 밖이면 에뮬레이터 기본 GPS(미국 등)로 보고 모의 위치를 쓴다. */
    fun isInKorea(lat: Double, lng: Double) = lat in 33.0..39.0 && lng in 124.0..132.0

    /** CSV 본문 파싱. 주석(#)과 헤더는 건너뛴다. 형식: name,route,direction,lat,lng */
    fun parse(lines: Sequence<String>): List<RestStop> = lines
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 5) return@mapNotNull null
            val lat = c[3].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lng = c[4].trim().toDoubleOrNull() ?: return@mapNotNull null
            RestStop(c[0].trim(), c[1].trim(), c[2].trim(), lat, lng)
        }.toList()

    /** "서울기점 + 부산종점" → "부산", "서울방향" → "서울", "상행"/"하행" → 그대로. 모르면 null */
    fun terminusOf(direction: String): String? {
        val d = normalizeCity(direction.replace(" ", ""))
        Regex("([가-힣]+)종점").find(d)?.let { return it.groupValues[1] }
        Regex("([가-힣]+)방향").find(d)?.let { return it.groupValues[1] }
        if (d.contains("상행")) return "상행"
        if (d.contains("하행")) return "하행"
        return null
    }

    /** "서울기점 + 부산종점" → "서울". 없으면 null */
    fun originOf(direction: String): String? {
        val d = normalizeCity(direction.replace(" ", ""))
        return Regex("([가-힣]+)기점").find(d)?.groupValues?.get(1)?.takeIf { it.length >= 2 }
    }

    /** "경부선 부산방향" 꼴. 종점을 모르면 노선명만 */
    fun routeLabel(route: String, direction: String): String {
        val t = terminusOf(direction) ?: return route
        return if (t == "상행" || t == "하행") "$route $t" else "$route ${t}방향"
    }

    /** CSV 오타 보정 */
    private fun normalizeCity(s: String) = s.replace("양앙", "양양").replace("회턱", "회덕")

    /** 순환선 여부. 순환선은 종점 방위가 의미 없어 전방 cone 만 본다 */
    fun isLoop(route: String) = route.contains("순환") || route.contains("외곽")

    /**
     * 노선 대표 방위: 기점 도시 → 종점 도시의 방위각. 도시 좌표를 모르거나 순환선이면 null.
     * 종점만 아는 경우(예: "서울방향")는 ROUTE_ENDPOINTS 에서 반대편을 찾는다.
     */
    fun routeBearing(stop: RestStop): Double? {
        if (isLoop(stop.route)) return null
        val terminus = stop.terminus ?: return null
        if (terminus == "상행") return 0.0
        if (terminus == "하행") return 180.0
        val to = CITY[terminus] ?: return null
        val originName = stop.origin ?: ROUTE_ENDPOINTS[stop.route]?.firstOrNull { it != terminus } ?: return null
        val from = CITY[originName] ?: return null
        return bearingDeg(from.first, from.second, to.first, to.second)
    }

    /** 현재 노선: 현재 위치 최근접 쉼터의 route(거리·방향 무관). 쉼터 표가 비어 있으면 null */
    fun currentRoute(stops: List<RestStop>, from: GeoPoint): String? =
        stops.minByOrNull { haversineKm(from.lat, from.lng, it.lat, it.lng) }?.route

    /**
     * 진행 종점: 잠근 노선의 종점 중 대표 방위가 heading 과 routeToleranceDeg 안인 것(각도 차 최소).
     * 순환선이거나 방위를 모르는 노선이면 null(방향 구분 없음). 방위를 아는데 어느 종점도 안 맞으면 null 이고,
     * 그 경우 nearestAhead 는 후보를 내지 않는다.
     */
    fun headingTerminus(onRoute: List<RestStop>, heading: Double, routeToleranceDeg: Double = ROUTE_TOLERANCE_DEG): String? =
        onRoute.asSequence()
            .mapNotNull { s -> s.terminus?.let { t -> routeBearing(s)?.let { b -> t to angleDiff(b, heading) } } }
            .filter { (_, diff) -> diff <= routeToleranceDeg }
            .minByOrNull { it.second }?.first

    /** 노선 방위를 구할 수 있는 노선인가(하나라도 routeBearing 이 있으면) */
    private fun isDirectional(onRoute: List<RestStop>) = onRoute.any { routeBearing(it) != null }

    /**
     * 전방 같은 방향 최근접 쉼터. heading 이 없으면 방향을 판정할 수 없으므로 null.
     * 반환: 쉼터와 현재 위치로부터의 거리(km).
     */
    fun nearestAhead(
        stops: List<RestStop>,
        from: GeoPoint,
        minKm: Double = MIN_KM,
        maxKm: Double = MAX_KM,
        coneDeg: Double = CONE_DEG,
        routeToleranceDeg: Double = ROUTE_TOLERANCE_DEG,
    ): Pair<RestStop, Double>? {
        val heading = from.heading ?: return null
        val route = currentRoute(stops, from) ?: return null
        val onRoute = stops.filter { it.route == route }
        val terminus = headingTerminus(onRoute, heading, routeToleranceDeg)
        if (terminus == null && isDirectional(onRoute)) return null // 노선 방위는 아는데 어느 종점 쪽도 아니다(진출 중 등)
        return onRoute.asSequence()
            .filter { terminus == null || it.terminus == terminus }
            .map { it to haversineKm(from.lat, from.lng, it.lat, it.lng) }
            .filter { (_, km) -> km in minKm..maxKm }
            .filter { (s, _) -> angleDiff(bearingDeg(from.lat, from.lng, s.lat, s.lng), heading) <= coneDeg }
            .minByOrNull { it.second }
    }

    /** 도시 대표 좌표(위도, 경도). CSV 의 기점/종점에 나오는 이름만 */
    val CITY: Map<String, Pair<Double, Double>> = mapOf(
        "서울" to (37.56 to 126.98), "부산" to (35.18 to 129.08), "목포" to (34.81 to 126.39), "순천" to (34.95 to 127.49),
        "천안" to (36.81 to 127.11), "인천" to (37.46 to 126.71), "강릉" to (37.75 to 128.88), "하남" to (37.54 to 127.21),
        "통영" to (34.85 to 128.43), "춘천" to (37.88 to 127.73), "양평" to (37.49 to 127.49), "창원" to (35.23 to 128.68),
        "청주" to (36.64 to 127.49), "영덕" to (36.42 to 129.37), "양양" to (38.08 to 128.62), "평택" to (36.99 to 127.11),
        "제천" to (37.13 to 128.19), "논산" to (36.19 to 127.10), "회덕" to (36.38 to 127.42), "광주광역시" to (35.16 to 126.85),
        "광주" to (35.16 to 126.85), "대구" to (35.87 to 128.60), "포항" to (36.02 to 129.37), "익산" to (35.95 to 126.96),
        "장수" to (35.65 to 127.52), "속초" to (38.21 to 128.59), "삼척" to (37.45 to 129.17), "영암" to (34.80 to 126.70),
        "담양" to (35.32 to 126.99), "고창" to (35.44 to 126.70), "울산" to (35.54 to 129.31), "언양" to (35.57 to 129.12),
        "세종" to (36.48 to 127.29), "포천" to (37.89 to 127.20), "서천" to (36.08 to 126.69), "공주" to (36.45 to 127.12),
        "새만금" to (35.80 to 126.60), "당진" to (36.89 to 126.63), "대전" to (36.35 to 127.38), "산인" to (35.26 to 128.44),
        "구리" to (37.59 to 127.14),
    )

    /** 종점만 알 때 반대편을 찾기 위한 노선 양끝. CSV 에 있는 노선만 */
    val ROUTE_ENDPOINTS: Map<String, List<String>> = mapOf(
        "경부선" to listOf("서울", "부산"), "호남선" to listOf("천안", "순천"), "중부선" to listOf("하남", "통영"),
        "제2중부선" to listOf("하남", "통영"), "영동선" to listOf("인천", "강릉"), "서해안선" to listOf("서울", "목포"),
        "중앙선" to listOf("춘천", "부산"), "남해선" to listOf("순천", "부산"), "남해선(영암순천)" to listOf("영암", "순천"),
        "중부내륙선" to listOf("양평", "창원"), "통영대전선" to listOf("하남", "통영"), "청주영덕선" to listOf("청주", "영덕"),
        "서울양양선" to listOf("서울", "양양"), "평택제천선" to listOf("평택", "제천"), "호남지선" to listOf("논산", "회덕"),
        "광주대구선" to listOf("광주광역시", "대구"), "광주광역시대구선" to listOf("광주광역시", "대구"),
        "포항영덕선" to listOf("포항", "영덕"), "익산장수선" to listOf("익산", "장수"), "동해선" to listOf("속초", "삼척"),
        "고창담양선" to listOf("고창", "담양"), "울산선" to listOf("울산", "언양"), "세종포천선" to listOf("세종", "포천"),
        "서천공주선" to listOf("서천", "공주"), "새만금전주" to listOf("새만금", "장수"), "대구포항선" to listOf("대구", "포항"),
        "당진청주선" to listOf("당진", "청주"), "당진대전선" to listOf("당진", "대전"), "남해1지선" to listOf("창원", "산인"),
        "구리포천선" to listOf("구리", "포천"),
    )
}

/**
 * 고속도로 졸음쉼터 목록(res/raw/rest_stops.csv, 공공데이터포털 전국졸음쉼터표준데이터 253곳)을 읽어 RestStopMatcher 에 넘긴다.
 */
class RestStops(context: Context) : RestStopIndex {
    val all: List<RestStop> = context.resources.openRawResource(R.raw.rest_stops).bufferedReader().useLines { RestStopMatcher.parse(it) }

    override fun nearestAhead(from: GeoPoint): Pair<RestStop, Double>? = RestStopMatcher.nearestAhead(all, from)
    override fun currentRoute(from: GeoPoint): String? = RestStopMatcher.currentRoute(all, from)
}

/** RestStopTracker 가 쓰는 쉼터 표 인터페이스. 실제는 RestStops(CSV), 테스트는 작은 목록 */
interface RestStopIndex {
    fun nearestAhead(from: GeoPoint): Pair<RestStop, Double>?
    fun currentRoute(from: GeoPoint): String?
}

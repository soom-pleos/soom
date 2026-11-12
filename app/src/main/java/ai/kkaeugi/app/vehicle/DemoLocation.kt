package ai.kkaeugi.app.vehicle

/**
 * 모의 모드 기준 위치. 경부고속도로 상행(서울방향) 칠곡 부근, 북북동으로 진행.
 * 전방 약 12km 에 석적 졸음쉼터(경부선 서울방향)가 있어 데모에서 "경부선 서울방향 석적졸음쉼터 12km" 멘트가 나온다.
 * 실제 위치가 한국 밖(에뮬레이터 기본 GPS)이어도 이 좌표로 대체한다.
 */
object DemoLocation {
    val POINT = GeoPoint(35.95, 128.45, heading = 20.0)

    /** 실제 위치가 없거나 한국 밖이면 모의 위치로 바꾼다 */
    fun resolve(real: GeoPoint?): GeoPoint =
        if (real != null && RestStopMatcher.isInKorea(real.lat, real.lng)) real else POINT
}

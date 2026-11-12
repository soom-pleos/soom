# 확인된 Pleos SDK 사실 (AAR 디컴파일 기준, 2026-11-09, 2026-11-10 보강, v0.6.1)

여기 적은 것은 AAR(javap)과 앱 코드가 컴파일된다는 사실까지다. Pleos 에뮬레이터에서의 실제 동작은 아직 확인하지 않았다.

Maven: https://nexus-playground.pleos.ai/repository/maven-releases/ , group ai.pleos.playground
- Vehicle 2.3.2 (+ vehicle-data 1.1.11), NaviHelper 3.0.5 (+ navi-data 3.0.4), TextToSpeech 3.3.1, SpeechToText 3.3.1, FusedLocation 1.1.3, LLM 2.1.3.2

## Vehicle SDK 진입
`Vehicle(context).initialize()` → `getHVAC()/getWindow()/getOdometer()/getSafety()/getGear()/getLight()/getBrake()/getTurnSignal()/getWiper()/getSeat()/getSteeringWheel()` → `release()`
모든 콜백 `(onComplete: (T)->Unit, onFailed: (Exception)->Unit)`. 리스너는 `VehicleListener { onFailed(Exception) }` 상속.
서비스 바인딩이 비동기(IPC)라 initialize 직후 호출은 실패할 수 있음. 1.5초 지연 후 등록.

## HVAC (퍼미션 CAR_CLIMATE / 제어 CONTROL_CAR_CLIMATE)
- `getHvacRecirculationState(SeatArea, (Boolean)->Unit, fail)` true=내기순환
- `setHvacRecirculation(SeatArea, Boolean, (SeatArea,Boolean)->Unit, fail)`
- `registerHvacRecirculationState(SeatArea, HvacRecirculationStateListener{ onHvacAutoRecirculationStateUpdated(Boolean) })`
- `getHvacTemperature(SeatArea, TemperatureUnit.CELSIUS, (Float)->Unit, fail)` / `setHvacTemperature(SeatArea, Float, TemperatureUnit, (SeatArea,Float)->Unit, fail)`
- `getHvacFanSpeed(SeatArea, (Int)->Unit, fail)` / `setHvacFanSpeed(SeatArea, Int, (SeatArea,Int)->Unit, fail)` / `registerHvacFanSpeed(SeatArea, HvacFanSpeedListener{ onHvacFanSpeedUpdated(Int) })`
- `getHvacAutoRecirculationState(SeatArea, (Boolean)->Unit, fail)` / `registerHvacAutoRecirculationState(SeatArea, HvacRecirculationStateListener)` / `unregisterHvacAutoRecirculationState(...)`: 차가 내외기를 스스로 제어하는 중인지. 리스너 타입이 일반 내기순환과 같아서(콜백 이름도 onHvacAutoRecirculationStateUpdated) 앱은 리스너 객체를 따로 만들어 붙인다. 켜져 있으면 숨은 제어하지 않고 화면에만 표시한다(v0.6)
- `getHvacPowerState / setHvacPower(SeatArea, Boolean, ...)`
- SeatArea: `SeatArea(SeatValue.ROW_1_LEFT)` (운전석)

## Window (퍼미션 CAR_WINDOWS / 제어 CONTROL_CAR_WINDOWS)
- `getCurrentWindowPosition(WindowArea, (Int)->Unit, fail)` / `setWindowPosition(WindowArea, Int, (WindowArea,Int)->Unit, fail)`
- `checkWindowPositionCapabilities((Map<WindowArea, AreaBounds<Int>>)->Unit, fail)` 로 범위 확인 후 사용
- v0.6 부터 앱은 창문을 읽기만 한다(숨 모델 입력). setWindowPosition 은 개발자 설정 "졸음 개입에 창문 포함"을 켰을 때만 부른다. 닫힘이 min 인지 max 인지 실측 전이다
- `registerWindowPosition(WindowArea, WindowPositionListener)`
- WindowArea: `WindowArea(WindowValue.ROW_1_LEFT)` (운전석 창)

## Seat (CAR_SEATS)
- `getSeat().checkSeatOccupancyCapabilities((List<SeatArea>)->Unit, fail)` 점유 값을 주는 좌석 목록
- `getSeat().checkSeatBeltBuckledCapabilities((List<SeatArea>)->Unit, fail)` 벨트 값을 주는 좌석 목록
- `getSeat().getCurrentSeatOccupancy(SeatArea, (Boolean)->Unit, fail)` / `getCurrentSeatBeltBuckledState(SeatArea, (Boolean)->Unit, fail)`
- 앱은 ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT 중 지원 좌석만 5초마다 읽는다. 좌석별로 점유 → 벨트. 두 번 연속 같은 값일 때 반영. 좌석 값이 아예 안 오면 정차 화면 스테퍼(1~5)로 직접 넣고, 넣지 않으면 1명
- ROW_1_RIGHT 의 점유(또는 벨트)로 조수석 탑승 여부를 따로 본다(답 버튼 위치, "옆자리에서 눌러 주셔도 돼요.")

## SteeringWheel (CAR_DRIVE)
- `getSteeringWheel().registerSteeringWheelAngle(SteeringWheelAngleListener{ onSteeringWheelAngleUpdated(Float?) })` / `unregisterSteeringWheelAngle(...)`
- `getCurrentSteeringWheelAngle((Float)->Unit, fail)`, `checkSteeringWheelAngleCapabilities((area, AreaBounds<Float>)->Unit, fail)` 로 범위(단위·부호) 확인
- v0.6 부터 핸들 보조 신호는 실험이고 기본 꺼짐(개발자 설정). 꺼져 있으면 엔진이 각도를 무시한다

## TurnSignal (CAR_LIGHTS)
- `getTurnSignal().registerTurnSignalState(TurnSignalStateListener)` / `unregisterTurnSignalState(...)`

## Odometer (CAR_DRIVE)
- `getTripCurrentDrivingTime((Int)->Unit, fail)` 단위 확인 필요(분으로 추정), `registerTripCurrentDrivingTime(DrivingTimeListener{ onDrivingTimeUpdated(Int) })`
- `getTripCurrentDrivingDistance((Float)->Unit, fail)`

## Safety (텔테일은 PRIVATE_CAR_SAFETY)
- `registerTelltales(TelltaleInfoListener{ onTelltaleInfoUpdated(Telltales) })`, `Telltales.telltales: List<TelltaleData{type: TelltaleType, state: TelltaleState(OFF/ON/BLINK)}>`
- `registerTelltales`, `getTelltalesInfo` 는 CAR_SAFETY 가 아니라 PRIVATE_CAR_SAFETY 를 요구한다(Vehicle AAR javap 확인). 매니페스트에 둘 다 선언. 거부되면 졸음 쪽 제안은 나오지 않는다(개발자 설정의 핸들 보조 신호를 켠 경우만 예외). 야간 보조 조건은 v0.6 에서 없앴다
- 쓰는 TelltaleType: DRIVER_ATTENTION, LKA, LFA, FCA, SEAT_BELT, PARKING_BRAKE, ELECTRONIC_PARKING_BRAKE, ICY_ROAD

## Gear (CAR_DRIVE) / Light (CAR_LIGHTS)
- `getGear().registerGearSelection(GearSelectionListener{ onSelected(VehicleGear) })`, VehicleGear.GEAR_PARK/GEAR_DRIVE/...
- `getLight().registerLowBeamLightState(LightStateListener{ onLightStateUpdated(LightState OFF/ON/DAYTIME_RUNNING) })`

## NaviHelper
- `NaviHelper(context).initialize(onReady, onError)`, `addListener(NaviHelperEventListener)` (모두 default 메서드, 필요한 것만 override)
- 이벤트: onTBTInfo(List<TBTInfo{type: TurnType, distance: Double}>), onCautionPointInfo(List<CautionPoint{type, remainDistance}>), onCurrentLocationInfo(CurrentLocationInfo{latitude, longitude, heading}), onCurrentRoadSpeedLimit(Int), onDrivingInfo(DrivingInfo{destination: DrivingState{name, duration, distance}}), onNavigationStatus(INACTIVE/INITIALIZING/FREE_DRIVE/ROUTE_DRIVE), onRouteStarted, onDestinationArrived
- 경유지 추가: `addWaypoint(RequestWaypointInfo(latitude, longitude, poiName, waypointIndex: WaypointIndex, poiId, address, poiSubId, poiProvider))` 생성자 인자 순서는 아래 javap 출력 참고. `removeWaypoint(WaypointIndex)`
- 경로 없을 때 목적지 설정: `requestRoute(RouteInfo(...))`
- 목적지 정보: `getDestinationInfo()` 를 부르면 `onDestinationInfo(DestinationInfo{remainTime: Int, remainDistance: Int})` 로 온다. SDK 에 단위 표기가 없다. 앱(EtaJudge)은 남은 거리(m 로 가정)와 남은 시간의 비율이 말이 되는 속도 범위에 드는 쪽으로 초인지 분인지 가린다. 한 번 정한 단위는 반대 판정이 두 번 연속일 때만 바꾸고, 남은 거리 300m 미만인 표본은 판정에 쓰지 않는다. 주행 중 1분마다, 경로가 바뀔 때 다시 부른다. 답이 올 때마다 엔진에 다시 알리고, 엔진은 받은 지 10분이 지난 값을 쓰지 않는다. 도착 5분 전 판정과 "N분 뒤 도착이에요" 문장에 쓴다

## TTS: `TextToSpeech(context, Mode.HYBRID).initialize(OnInitializeListener{onSuccess,onFailure})`, `setLocale(Locale.KOREAN)`, `speak(text, false)`, `stop()`, `release()`
## STT: 클래스 구조는 TTS와 대칭(ai.pleos.playground.stt). 앱은 STT 서비스가 준비되고 RECORD_AUDIO 권한이 있을 때만 음성 답을 받는다.

## 버추얼 콘솔(에뮬레이터 http://127.0.0.1:8088)
Motion: 기어, 브레이크, 조향각, 주행거리 / Climate: 공조 전원, 온도, 풍량, 송풍 방향, 내기순환, 제상 / Convenience: 도어·창문, 시트, 조명, 와이퍼 / Safety: 주차브레이크, 안전벨트, ABS 등 경고등 켜기/끄기/점멸 / ADAS: 크루즈

## SDK 에 없는 것 (javap 로 확인, 2026-11-10)
- 시동(점화) 상태를 주는 API 가 없다. 세션의 시작과 끝은 기어와 주차브레이크 텔테일로 본다.
- 차량 속도를 주는 API 가 없다. 속도는 위치 차이로만 어림하고(핸들 보조 신호의 60km/h 조건), 숨 모델에는 쓰지 않는다.
- 바깥 온도를 주는 API 가 없다.
- 터널, 바깥 공기질을 알려 주는 신호가 없다. 숨은 터널이나 바깥 공기질을 알 수 없다. 그래서 묻고, 맡긴 뒤에도 예고하고 취소를 받는다.

## Android 퍼미션 (v0.6 추가)
- FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS: 개발자 설정 "화면 밖에서도 유지(실험)" 토글용(KeepAliveService, foregroundServiceType specialUse). 기본 꺼짐이고 에뮬레이터 확인 전이다. Pleos 퍼미션 14개는 그대로다.

## 한 번에 자가 진단 (SelfDiagnosis.kt)
정차 화면 [자가 진단]. 읽기 전용 호출만 순서대로 부르고 항목마다 3초 안에 답이 없으면 실패로 적는다. 약 49줄: 연결 판정, Pleos 퍼미션 14개, 기어, 내기순환, 자동 내기순환, 풍량, 설정 온도, 창문 4, 좌석 점유 5, 벨트 5, 핸들 각도와 범위, 하향등, 와이퍼, 텔테일 등록, 트립 시간 raw, 내비 초기화, 내비 현재 위치, 목적지 남은 시간·거리, FusedLocation, TTS, STT, 마이크 권한, 알림 권한, 화면 밖 유지. 결과는 화면(성공 N / 실패 M, 복사), Logcat `Kkaeugi.Diag`, 앱 내부 저장소 diag_latest.txt 에 같은 줄로 남는다. 차 값을 바꾸는 호출은 "제어 검증"에 따로 있다.

## 실측(2026-11-11, Pleos Connect v2.1.0 에뮬레이터)
arm64, API 34, 앱 v0.6.1, 기어 P 에서 자가 진단을 처음 돌린 결과와 그 뒤 SDK 를 다시 본 내용이다.

### 진단 결과 요약 (성공 42 / 실패 7)
- 연결 판정: 연결 gear=GEAR_PARK. Pleos 퍼미션 13개 허용, FUSED_LOCATION 만 거부.
- 내기순환, 자동 내기순환, 풍량: 실패. `IllegalArgumentException: area ID: 0x1 not supported for property ID: HVAC_RECIRC_ON` (HVAC_AUTO_RECIRC_ON, HVAC_FAN_SPEED 도 같은 문장). 앱이 `SeatArea(ROW_1_LEFT)` = 0x1 로 불렀는데 이 차의 VHAL 은 그 영역에 이 속성을 두지 않는다.
- 설정 온도: 실패. `PropertyNotAvailableException: Property ID: HVAC_TEMPERATURE_SET area ID: 0x1 - is not available because of vendor error code: 0`. 영역 0x1 은 있는데 지금 값을 못 준다는 뜻이다(공조 전원이 꺼져 있을 때 AAOS 기본 VHAL 이 이렇게 답한다). 즉 온도는 좌석별 영역, 내기순환과 풍량은 합친 영역으로 보인다.
- 창문 네 곳: raw 0, 범위 min 0 / max 10 (0~100 이 아니다).
- 좌석 점유: ROW_1_LEFT 없음, 나머지 4석 있음. 벨트 5석 전부 풀림. 에뮬레이터 기본값으로 보인다(버추얼 콘솔에서 바꿀 수 있다).
- 핸들 각도 0.0도, 범위 -470.0~500.0. 하향등 OFF, 와이퍼 OFF, 텔테일 등록 성공(켜진 경고등 0개).
- 트립 시간 raw: 실패 `ERROR_NOT_SUPPORTED_PROPERTY(60001)`. 이 차는 트립 주행시간을 주지 않는다. 앱 타이머만 쓴다.
- 내비: 초기화 준비됨, 현재 위치 37.41, 127.09 (heading 없음). 목적지 남은 시간 raw 0, 거리 raw 0 (목적지 없음, 단위 판정 불가).
- FusedLocation: 실패(위치 없음 또는 응답 없음). 퍼미션 거부와 같은 원인으로 본다.
- Pleos TTS, STT 초기화 성공. 마이크 권한 허용.

### SDK 재조사 (javap, Vehicle-2.3.2 / vehicle-data-1.1.11)
- `SeatArea(vararg SeatValue)`: 좌석 여러 개를 한 영역으로 만들 수 있다. 공개 생성자는 이것 하나다(임의 int 를 받는 생성자, ALL/GLOBAL 류 상수는 없다). `getValues(): Array<SeatValue>`. `equals` 는 `Arrays.equals` 라서 좌석 순서가 다르면 다른 영역으로 본다. 앱은 비교를 마스크(int)로 한다.
- `SeatValue.getId()` 는 공개다. 값은 AAOS VehicleAreaSeat 과 같다: ROW_1_LEFT 0x1, ROW_1_CENTER 0x2, ROW_1_RIGHT 0x4, ROW_2_LEFT 0x10, ROW_2_CENTER 0x20, ROW_2_RIGHT 0x40, ROW_3_LEFT 0x100, ROW_3_CENTER 0x200, ROW_3_RIGHT 0x400 (`VehicleAreaValue$SeatConstant`).
- SDK 가 영역 id 를 만드는 법(`vehicle.utils.d.a(Area)` 바이트코드): 좌석 id 를 전부 OR 한다. 빈 배열이면 -1, `GlobalArea` 면 0. 그래서 `SeatArea(ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT)` = 0x75, `SeatArea(ROW_1_LEFT, ROW_2_LEFT, ROW_2_CENTER)` = 0x31, `SeatArea(ROW_1_LEFT, ROW_1_RIGHT)` = 0x5 를 공개 API 만으로 만들 수 있다. 리플렉션이 필요 없다.
- HVAC 호출은 전부 `SeatArea` 만 받는다. `GlobalArea`(영역 0)로는 부를 수 없다. 속성이 전역(0)으로 정의된 차라면 이 SDK 로는 못 읽는다.
- capability 는 지원 영역을 돌려준다. `checkHvacRecirculationCapabilities`, `checkHvacAutoRecirculationCapabilities`, `checkHvacPowerCapabilities` → `List<SeatArea>`. `checkHvacFanSpeedCapabilities` → `Map<SeatArea, AreaBounds<Int>>`, `checkHvacTemperatureCapabilities` → `Map<SeatArea, AreaBounds<Float>>` (키가 영역). 구현은 `CapabilityConfig.getAreas(): IntArray` 의 영역 id 하나마다 `SeatValue.values()` 순서로 비트를 풀어 `SeatArea` 하나를 만든다. 0x75 는 좌석 다섯 개짜리 `SeatArea` 한 개로 온다.
- 리스너 콜백은 영역을 알려 주지 않는다. `HvacRecirculationStateListener.onHvacAutoRecirculationStateUpdated(Boolean?)`, `HvacFanSpeedListener.onHvacFanSpeedUpdated(Int?)`, `HvacPowerStateListener.onHvacPowerStateUpdated(Boolean?)`, `HvacTemperatureListener.onHvacTemperatureUpdated(Float?)`. 등록할 때 넘긴 영역의 값만 온다.
- FusedLocation AAR 매니페스트가 요구하는 것은 `ai.pleos.playground.service.location.fused.FUSED_LOCATION_BINDING` 하나다. `android.permission.ACCESS_FINE_LOCATION` 은 요구하지 않는다. `pleos.car.permission.FUSED_LOCATION` 이 왜 거부인지는 보호 수준을 봐야 안다(자가 진단이 보호 수준을 같이 적는다).

### v0.6.2 에서 바꾼 것
- HVAC 영역을 하드코딩하지 않는다. 속성별(내기순환, 자동 내기순환, 풍량, 설정 온도, 공조 전원)로 (a) capability 가 준 영역 중 운전석(0x1)을 포함하는 것, 없으면 첫 영역 (b) capability 로 못 정하면 후보 0x75, 0x31, 0x5, 0x1, 0x77, 0x7, 0x777, 0x44 를 차례로 읽어 값이 오는 첫 후보. 값은 안 오지만 "지금은 읽을 수 없음"(PropertyNotAvailable)으로 답한 후보는 영역은 맞는 것으로 보고 보류로 채택한다. "area ID not supported" 는 건너뛴다.
- capability 가 준 영역도 한 번 읽어 보고 "area ID not supported" 면 버리고 후보 탐색으로 간다.
- 창문 15%: 범위 0~10 에서 반올림 2(20%)가 아니라 내림 1(10%).
- 트립 시간 미지원, FUSED_LOCATION 거부는 자가 진단에서 실패로 세지 않는다(앱 타이머, 내비 위치로 대체).

### v0.6.2 자가 진단에서 볼 줄
- 둘째 줄이 "성공 N / 실패 M / 보류 K / 대체 J" 로 바뀌었다. 보류는 지금은 판단할 수 없는 것(공조 전원이 꺼져 값을 못 읽는 경우), 대체는 이 차에 없거나 선택 항목이라 다른 경로로 도는 것(트립 시간 → 앱 타이머, FusedLocation → 내비 위치)이다. 둘 다 실패로 세지 않는다.
- `[HVAC 영역 탐색]` 아래 속성마다 세 줄: "내기순환 영역: 0x75 (capability)" 또는 "(탐색)", "  내기순환 capability: 0x75=ROW_1_LEFT+..." (조회 실패면 사유), "  내기순환 후보별 읽기: 0x75=꺼짐(외기) · 0x31=영역 미지원 · ...". 속성은 내기순환, 자동 내기순환, 풍량, 설정 온도, 공조 전원. 진단은 후보 8개를 전부 읽는다(읽기 전용).
- `[HVAC 값]` 아래 공조 전원, 내기순환, 자동 내기순환, 풍량, 설정 온도. 설정 온도가 PropertyNotAvailable 이면 "보류 (지금은 읽을 수 없음...)".
- "탑승 인원 집계: 집계 결과 N명(운전석 보정 포함) · 좌석별 값". 주행 중 규칙(운전석은 값과 무관하게 1)으로 센다.
- 거부된 퍼미션에는 기기가 알려 준 보호 수준이 붙는다(dangerous 면 런타임 요청 대상이고 앱이 시작할 때 묻는다). SDK AAR 이 넣은 바인딩 퍼미션(`...FUSED_LOCATION_BINDING`, `...VEHICLE_BINDING`)도 한 줄씩 나온다.
- 같은 탐색 결과가 Logcat `Kkaeugi.Vehicle` 에 "내기순환 영역: 0x75 (capability)" 와 후보별 예외 원문으로 남는다.

## v0.7.0 "숨 설계"에서 더한 것 (2026-11-11)
- 바깥 공기질을 주는 SDK 신호는 여전히 없다. 초미세먼지(PM2.5)만 Open-Meteo Air Quality API 의 격자 모델값을 받아 쓴다(`OutdoorAirSource.kt`). 엔진에는 신호 `OutdoorAir(pm25UgM3, source, ageMin)` 로 들어간다. 값이 없거나 60분 넘게 묵으면 엔진은 v0.6.2 와 같이 움직인다.
- 요청: `GET https://air-quality-api.open-meteo.com/v1/air-quality?latitude=..&longitude=..&current=pm2_5,pm10`. HttpURLConnection, 연결과 읽기 5초, 30분 캐시(실패하거나 모의 위치로 받았으면 5분 뒤 다시). 좌표는 소수 첫째 자리로 반올림해 보낸다. 위치는 내비 위치 우선이고 없거나 한국 밖이면 모의 위치(출처에 "모의 위치"라고 붙는다). 새 의존성과 새 퍼미션은 없다(INTERNET 은 이미 있었다).
- 에뮬레이터(v2.1.0)에서 인터넷이 되는지는 확인하지 못했다. 자가 진단 맨 아래 "바깥 공기" 줄이 지금 한 번 받아 보고 값, 출처, 나이를 적는다. 못 받으면 사유(UnknownHostException 등)를 "대체"로 적고 실패로 세지 않는다. 이때는 정차 화면 설정에서 등급(좋음, 보통, 나쁨, 매우 나쁨)을 직접 고를 수 있다.
- 이 작업 환경에서는 API 의 정상 응답 본문을 받아 보지 못했다. 응답 형식은 `{"current":{"pm2_5":값,"pm10":값}}` 를 기준으로 읽고, 다르면 값 없음으로 처리한다.
- 지금 시각은 앱이 벽시계에서 읽어 신호 `LocalClock(minuteOfDay)` 로 1분마다 넘긴다. "졸리기 쉬운 시간대" 표시와 졸음 제안의 실행 순서에만 쓴다.
- 새 WakeEvent 는 없다. 설계, 예보, 그림자 곡선, 낮잠 안내는 `WakeEngine.breath(now)` 로 읽는다(브라우저 시뮬레이터의 이벤트 변환과 기존 테스트가 이벤트 목록을 그대로 견주기 때문). 도착 카드의 세 갈래 비교는 `ArrivalSummary.threeWay` 에 실린다.

## 실측(2026-11-12, Pleos Connect v2.1.0 에뮬레이터, 앱 v0.7.3 release)과 v0.7.4 에서 바꾼 것
처음으로 release APK 를 설치하고 `adb shell am broadcast -n ai.kkaeugi.app/.gleo.VerifyReceiver -a ai.kkaeugi.app.VERIFY --es what all` 을 보냈다("Broadcast completed: result=0").

### 본 것
- `adb shell run-as ai.kkaeugi.app cat files/verify_latest.txt` 는 "run-as: package not debuggable" 로 실패한다. release 빌드의 내부 파일은 adb 로 읽을 수 없다.
- Virtual Console(http://127.0.0.1:8088/) 기준으로 실행 뒤 상태가 공조 Power ON, Recirculation 꺼짐(외기), 풍량 3, 창문 넷 Closed 였다. 실행 전에는 차 화면 기준 내기순환 켜짐, 공조 꺼짐이었다. 제어는 먹혔고(내기 → 외기) 처음 상태로는 돌아오지 않았다. 그때의 로그는 받지 못했다(아래 원인 가운데 어느 것이었는지는 v0.7.4 로 다시 돌려 봐야 안다).
- Virtual Console 에 있는 조작: Climate(Power, Auto, A/C, Sync, Recirculation, 앞뒤 서리 제거, 송풍 방향, 운전석·동승석 온도와 시트 열선·통풍, Fan speed), Body(프렁크, 트렁크, 문 넷, 잠금, 창문 넷), Systems(기어 P R N D, 속도, 조향, 라이트 Off Auto Low High, 와이퍼 Off Auto Low High, 방향지시등, 앰비언트), Telltales. 좌석 점유와 안전띠를 바꾸는 조작은 없다. 좌석 기본값은 운전석 없음, 나머지 네 자리 있음으로 고정이다.

### 코드에서 확인한 것 (v0.7.3)
- 앱 어디에도 공조 전원을 쓰는 호출이 없었다. `HVAC.setHvacPower(SeatArea, Boolean, (SeatArea, Boolean) -> Unit, (Exception) -> Unit)` 는 Vehicle 2.3.2 에 있다(javap 로 확인). 전원이 꺼진 차가 내외기나 풍량 명령을 받고 전원을 켜면 그대로 남는다. 제어 검증과 주행 경로(환기 → 2분 뒤 원복) 둘 다 그랬다.
- 제어 검증은 명령 0.6초 뒤 한 번만 읽었다. 원복 명령도 한 번 보내고 0.6초 뒤 한 번 읽고 끝이었다. 값이 처음과 달라도 다시 보내지 않았다.
- 제어 검증은 항목 셋(내외기 → 풍량 → 창문)을 따로 되돌렸다. 뒤 항목의 명령이 앞 항목의 값을 바꿔도(전원이 켜지면서 내외기가 바뀌는 차) 다시 보지 않았다.
- 제어 검증은 화면(ViewModel)의 스코프에서 돌았다. 방송 수신기는 붙들지 않았고(goAsync 없음) 프로세스를 붙드는 서비스도 없었다. 도는 중에 화면이 닫히면 원복 전에 끊긴다.
- 주행 경로의 원복은 "운전자가 그 사이 바꾼 항목은 두기" 규칙을 쓴다. 내기순환은 우리 명령 뒤 리스너로 다른 값이 한 번이라도 오면 운전자 조작으로 보고 원복에서 뺐고, 풍량과 온도는 지금 값이 우리가 명령한 값에서 허용 오차 밖이면 뺐다. 명령 직후 차가 스스로 고친 값과 운전자 조작을 가리지 않았다. 원복 명령 뒤 값을 확인하지도 않았다.
- 제어 검증 경로에는 "운전자 조작" 규칙이 없다(그 규칙이 검증에서 잘못 발동한 것은 아니다).

### v0.7.4 에서 바꾼 것
- `VehicleControls.getHvacPower / setHvacPower` 를 넣었다. 환기 전에 전원을 읽고, 꺼져 있으면 켠 뒤 환기하고, 원복 때 다른 값을 다 돌린 다음 맨 끝에 끈다. 그 사이 운전자가 공조를 만졌으면 켠 채로 둔다. 전원을 읽을 수 없는 차에서는 건드리지 않는다.
- 명령 뒤 확인: 0.5초 간격으로 최대 3초까지 다시 읽는다(`Confirm.until`). 원복이 확인되지 않으면 한 번 다시 보낸다. 그래도 안 되면 실패로 알린다(엔진이 "일부는 못 돌렸어요. 직접 확인해 주세요."라고 말한다).
- 내기순환: 우리 명령 뒤 3초 안에 온 값은 차의 반응으로 보고 운전자 조작으로 세지 않는다. 다시 명령하면 앞의 표시는 지운다. 풍량과 온도: 명령 직후 차가 실제로 둔 값도 우리 것으로 기억한다.
- 제어 검증: 항목마다 읽기 → 명령 → 확인 → 2초 유지 → 원복 → 확인. 처음에 읽어 둔 전원, 내외기, 풍량, 창문과 끝 값을 견줘 다른 것을 한 번 더 돌리고 `[끝 상태] … : 처음과 같음 / 처음과 다름` 을 적는다. 앱 프로세스의 스코프에서 돌고, 수신기는 goAsync 로 8.5초까지 붙들고, 도는 동안 `VerifyHoldService` 를 띄운다. 취소돼도 마무리(원복)는 돈다.
- 화면이 닫힐 때 환기나 졸음 개입이 유지 중이면(되돌릴 값이 남아 있으면) 닫기 전에 원복한다.
- 결과 읽기: 방송 결과(`data="…"`), Logcat `Kkaeugi.Verify`, 파일(앱 내부와 `getExternalFilesDir(null)`). adb 명령은 `--es what` 에 all, fresh, fan, window, result, diag, diagresult, state, seats, help.

### 아직 모르는 것
- 2026-11-12 에 원복이 안 된 직접 원인. 가설: (1) 전원이 꺼진 채 풍량이나 내외기 명령을 받은 에뮬레이터가 전원을 켰고, 전원이 켜지면서 내외기가 외기로 바뀌었다 (2) 원복 명령이 0.6초 안에 반영되지 않았거나 무시됐다 (3) 전원이 꺼져 있어 내외기 읽기가 실패했고 뒤 항목만 돌았다. v0.7.4 의 `[처음 상태]`, 항목 줄, `[마무리]`, `[끝 상태]` 가 이것을 가른다.
- 에뮬레이터에서 앱 전용 외부 저장소의 실제 경로(`/sdcard/Android/data/…` 인지 `/storage/emulated/10/…` 인지)와 adb shell 이 그 파일을 읽을 수 있는지. 앱이 실제 경로를 결과에 적는다. 안 읽히면 `--es what result` 로 방송 결과에서 읽는다.
- `am broadcast` 가 방송 결과의 `data` 를 그대로 찍는지(AOSP 는 찍는다. 이 에뮬레이터에서는 아직 못 봤다).

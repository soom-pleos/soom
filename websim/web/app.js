/*
 * 깨우기 시뮬레이터 페이지 로직.
 * 판단(언제 묻고, 무엇을 하고, 언제 되돌릴지)은 전부 Kotlin/JS 로 컴파일한 엔진이 한다.
 * 이 파일이 맡는 것: 가상 차량(콘솔 값과 제어 실행·원복), 화면 그리기, 말하기, 자동 시연.
 * 이벤트 처리 순서는 앱 WakeViewModel.handle, 제어·원복 규칙은 앱 VehicleActuator 를 따른다.
 */
(function () {
  'use strict';

  var Sim = globalThis['kkaeugi:websim'].ai.kkaeugi.websim.Sim;
  var $ = function (id) { return document.getElementById(id); };
  var params = new URLSearchParams(location.search);
  /** 시험용 시간 배율(?ts=4). 평소에는 1 */
  var TS = clamp(parseFloat(params.get('ts')) || 1, 1, 30);
  /** 엔진 기본은 핸들 신호와 창문 개입이 꺼져 있다. ?steer=1, ?window=1 로 켠다 */
  var OPT = { steer: params.get('steer') === '1', window: params.get('window') === '1' };

  // ---------------------------------------------------------------- 공용

  function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function mmss(sec) { return Math.floor(sec / 60) + ':' + pad2(sec % 60); }
  function fmtInt(n) { return Math.round(n).toLocaleString('en-US'); }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function setText(el, s) { if (el.textContent !== s) el.textContent = s; }
  function show(el, on) { if (el.hidden === on) el.hidden = !on; }

  /** "처음으로"를 누를 때마다 올린다. 예약해 둔 일은 자기 번호가 아니면 버린다 */
  var epoch = 0;
  /** 단조 시계(ms). performance.now 의 차이를 쌓는다. 엔진의 Signal.t 와 tick(now) 에 같은 값을 쓴다 */
  var boost = 1;
  var clock = (function () {
    var acc = 0, last = performance.now();
    return { now: function () { var n = performance.now(); acc += Math.max(0, n - last) * TS * boost; last = n; return Math.floor(acc); } };
  })();
  /** boost: 빨리 감기 배율. 맡기기 시연이 기다리는 구간(탁해질 때까지, 환기 유지)에서만 올린다. 답을 기다리는 동안은 항상 1 */
  function setBoost(b) { clock.now(); boost = b; }
  function later(ms, fn) { var my = epoch; return setTimeout(function () { if (my === epoch) fn(); }, ms / (TS * boost)); }
  function sleep(ms) { var my = epoch; return new Promise(function (res) { setTimeout(function () { res(my === epoch); }, ms / (TS * boost)); }); }

  // ---------------------------------------------------------------- 문구 (KO / EN)

  var lang = params.get('lang') === 'en' ? 'en' : 'ko';
  var T = {
    ko: {
      title: '깨우기 시뮬레이터',
      desc: '앱과 같은 판단 엔진을 브라우저에서 돌립니다. 좌석을 눌러 사람을 태워 보세요.',
      planTitle: '차 실내', stripName: '운전석 조작', hvac: '공조',
      btnDemo: '자동 시연', btnDemoStop: '자동 시연 멈추기', btnTrust: '맡기기 시연', btnTrustStop: '맡기기 시연 멈추기', btnReset: '처음으로',
      speedReal: '실제 속도', speedDemo: '60배',
      voiceOn: '음성 안내 끄기', voiceOff: '음성 안내 켜기',
      noteDemo: '60배: 1분을 1초로 줄입니다. 답을 기다리는 동안만 실제 속도로 흐릅니다.',
      noteReal: '실제 속도: 4명이 내기순환이면 약 4분, 혼자면 약 20분 뒤에 묻습니다.',
      simPill: '브라우저 시뮬레이터',
      stIdle: '대기', stDriving: '주행 중', stResting: '쉬는 중', stEnded: '도착', gear: '기어',
      gaugeTitle: '실내 공기 (CO2 추정)', ppmEst: 'ppm 추정', limit: '한도', people: function (n) { return n + '명'; },
      lineWarm: function (n) { return n + '명 탑승, 추정 준비 중'; },
      lineVent: '바깥 공기 들어오는 중', lineVentFall: '바깥 공기 들어오는 중, 내려가는 중',
      lineRest: '쉬는 중, 추정은 잠시 멈춰요', lineWin: '창문 열림, 바깥 공기 들어오는 중', lineWinFall: '창문 열림, 내려가는 중',
      lineWeak: '바깥 공기인데 바람이 약해요(추정)',
      lineQuiet: '이번 주행은 화면에만 표시해요', lineAuto: '차가 내외기를 제어하는 중이라 묻지 않아요', lineArriving: '곧 도착이라 환기는 묻지 않아요',
      lineQuietOver: function (n) { return n + '명 탑승, 한도를 넘었어요(추정). 묻지 않는 중이에요'; },
      freshBtn: '바깥 공기로', runCancel: '취소',
      lineCooldown: function (n, left, demo) { return n + '명 탑승, ' + left + (demo ? '초' : '분') + ' 뒤 다시 물을게요'; },
      lineNoLimit: function (n) { return n + '명 탑승, 지금은 한도에 닿지 않아요'; },
      lineOver: function (n) { return n + '명 탑승, 한도를 넘었어요(추정)'; },
      lineIn: function (n, m, demo) { return n + '명 탑승, 약 ' + m + (demo ? '초' : '분') + ' 뒤 환기 제안'; },
      tripTitle: '트립 시간', mins: function (m) { return m >= 60 ? Math.floor(m / 60) + '시간 ' + (m % 60) + '분' : m + '분'; },
      pillNight: '야간 장거리', pillRain: '비: 창문은 닫아 둬요', pillRest: '쉬는 중',
      stopPlain: '다음 쉼터', stopSim: '다음 쉼터, 모의 위치', stopWp: '다음 쉼터, 경유지로 넣음',
      stopNone: '전방 80km 안에 같은 방향 쉼터 없음', stopName: function (n) { return sim.stopDisplayName(n); },
      route: function (r) { return r; },
      todayTitle: '오늘',
      today1: function (v, f, demo) { return '환기 ' + v + '회, 바깥 공기 ' + f + (demo ? '초' : '분'); },
      todayPeak: function (p) { return '최고 ' + p + 'ppm 추정'; }, todayNoPeak: '최고 추정치 아직 없음',
      yes: '응', no: '괜찮아', speaking: '말하는 중...', cancel: '취소',
      announceIn: function (n) { return n + '초 뒤 바꿔요. 싫으면 취소를 누르세요'; },
      restNote: '깨우기는 사람이 내쉰 숨(이산화탄소)만 추정해요. 배기가스(일산화탄소)는 알 수 없어요.',
      trustYes: '맡길게요', trustNo: '계속 물어봐 주세요', trustOn: '맡겨 둘게요. 다음부터는 말만 하고 바꿔요.', trustKept: '계속 물어볼게요.',
      countdown: function (n) { return n + '초 뒤 넘어가요. 버튼으로 답해 주세요'; },
      runTail: function (t) { return ', ' + t + ' 뒤 원래대로'; }, executing: '실행 중...', reverting: '되돌리는 중...',
      evWaypoint: function (km) { return '   |   경유지까지 ' + km + 'km'; },
      restLine: function (name, n, demo, speech) { return demo ? name + '. ' + n + '초만 쉬고 가세요' + (speech ? '' : '(데모)') : name + '. ' + n + '분만 쉬고 가세요'; },
      restHint: '기어 D 로 바꾸면 돌아갑니다',
      arrTop: '오늘의 주행', ok: '확인',
      mDrive: '주행', mVent: '환기', mFresh: '바깥 공기', mDrowsy: '졸음 경고', mRest: '쉼터', mPassed: '지나침', mDeclined: '거절',
      times: function (n) { return n + '회'; }, span: function (n, demo) { return n + (demo ? '초' : '분'); }, restVal: function (c, m, demo) { return c + '회 ' + m + (demo ? '초' : '분'); },
      arrAir: function (peak, over, occ, demo) { return (peak > 0 ? '최고 ' + fmtInt(peak) + 'ppm 추정, 한도 위 ' + over + (demo ? '초' : '분') + '(추정), ' : '') + occ + '명 탑승'; },
      idleBig: '기어를 D 로 바꾸면 시작합니다',
      idleSub: function (n) { return '화면 아래 기어 D 를 누르세요. 지금 탑승 ' + n + '명.'; },
      idleTripS: '대기', idleTripEnded: '도착',
      idleAirT: function (n) { return '실내 공기 (CO2 추정), 탑승 ' + n + '명'; },
      idleAirS: function (lim) { return '탄 사람 수와 환기 상태로 계산한 추정값이에요. 센서 값이 아니에요. 한도 ' + lim + 'ppm'; },
      idleStopNone: '전방에 없음', idleStopS: '주행 중 졸음 신호가 오면 경유지로 제안해요',
      lastSaid: function (s) { return '마지막 안내: ' + s; },
      subNoVoice: '이 브라우저에는 한국어 음성이 없어 글로만 보여 줍니다.',
      holdText: function (r) { return '지금은 말하지 않고 미룹니다: ' + r; },
      holdTurn: '방향지시등이 켜져 있음', holdWarn: '안전 경고등이 켜져 있음', holdTurnNear: '회전 80m 전', holdCooldown: '직전 제안 뒤 20초가 안 지남', holdSpeaking: '앞 문장을 읽는 중',
      holdArriving: '도착 5분 전이라 환기는 묻지 않음', holdCarAuto: '차가 내외기를 스스로 제어하는 중', holdLights: '하향등을 막 켬',
      seatDriver: '운전석, 운전자가 앉아 있음', seatFront: '동승석', seatRearL: '뒷좌석 왼쪽', seatRearM: '뒷좌석 가운데', seatRearR: '뒷좌석 오른쪽',
      recircOn: '내기순환', freshOn: '외기', hvacFresh: '지금 외기. 누르면 내기순환으로', hvacRecirc: '지금 내기순환. 누르면 외기로', fan: '풍량', temp: '온도', fanDn: '풍량 내리기', fanUp: '풍량 올리기', tempDn: '온도 내리기', tempUp: '온도 올리기',
      winLabel: '운전석 창문', winDn: '창문 내리기', winUp: '창문 올리기',
      wiper: '와이퍼', lowBeam: '하향등', turn: '깜빡이', turnL: '왼쪽 깜빡이', turnR: '오른쪽 깜빡이', attn: '졸음 경고',
      steerBtn: '핸들 급보정', steerRun: function (i) { return '핸들 급보정 ' + i + '/3'; },
      tripBtn: '+2시간 주행', stopWithin: '쉼터 도착', limit: '한도',
      trustBtn: '맡기기', autoBtn: '차 자동 공조', etaBtn: '도착 4분 전',
      hintSeat: '뒷좌석을 눌러 보세요', hintGear: '아래에서 기어 D 를 누르면 출발합니다',
      sdkTitle: '차가 받은 제어', sdkEmpty: '앱이 차를 제어하면 여기에 호출이 쌓여요',
      evShow: '엔진 기록 보기', evHide: '엔진 기록 닫기',
      nRevert: '원래대로', nFresh: '외기로', nAlready: '이미 외기', nFan: function (a, b) { return '풍량 ' + a + '에서 ' + b; }, nFanMax: '풍량은 이미 최대',
      nTemp: function (a, b) { return '온도 ' + a + '에서 ' + b; }, nTempMin: '온도는 이미 하한', nWin: function (a, b) { return '창문 ' + a + '%에서 ' + b + '%'; },
      nPassed: '지나쳐서 경유지 삭제', nKeepWp: '경유지는 유지',
      nDriver: function (what) { return what + ' 운전자가 바꿔서 그대로 둠'; }, nCancel: '원복 요청: 남은 액션 취소',
      wRecirc: '내기순환은', wFan: '풍량은', wTemp: '온도는', wWin: '창문은',
      d1: '운전자 혼자 탔습니다. 지난 주행까지 환기 제안에 두 번 "응" 한 것으로 하고 시작합니다.',
      d2: '기어 D. 혼자 출발합니다.',
      d3: '내기순환을 켰습니다. 혼자라서 천천히 탁해집니다.',
      d5: '한도에 닿자 차가 먼저 묻습니다. 혼자라서 20초만 바꾸자고 합니다.',
      d6: '"응" 을 대신 눌렀습니다.',
      d7: '앱이 외기로 바꾸고 풍량을 한 단 올렸습니다.',
      d8: '공기가 맑아집니다. 말한 시간이 지나면 원래대로 돌립니다.',
      d9: '내기순환과 풍량이 원래대로 돌아왔습니다.',
      d4: '뒷좌석에 3명이 탔습니다. 같은 차인데 훨씬 빨리 탁해집니다.',
      d4b: '4명이면 20초로는 모자랍니다. 이번에는 바꿔 두자고 묻습니다.',
      d5b: '답을 기다리는 동안에는 실제 속도로 흐릅니다.',
      d6b: '"응". 외기로 바꾸고 그대로 둡니다.',
      d11: '밤길 장거리: 하향등을 켜고 2시간을 더 달렸습니다. 한도가 1,600 으로 내려갑니다.',
      d11b: '운전자가 내기순환으로 돌려놓았습니다.',
      d12: '졸음 경고등이 켜졌습니다.',
      d13: '앞쪽 졸음쉼터를 경유지로 넣자고 묻습니다.',
      d14: function (win) { return win ? '"응". 온도 2도 내림, 창문 15%, 쉼터 경유지 추가.' : '"응". 바깥 공기, 온도 2도 내림, 쉼터 경유지 추가. 창문은 건드리지 않습니다.'; },
      d15: '쉼터로 가는 중입니다.',
      d16: '쉼터 300m 앞에 왔습니다.',
      d17: '기어 P. 쉬는 동안에는 추정을 멈춥니다.',
      d17b: '내기순환으로 쉬게 되어, 바깥 공기로 둘지 한 번 묻습니다.',
      d17c: '"응". 쉬는 동안만 바깥 공기로 둡니다.',
      d18: '기어 D. 다시 출발하면서 공조를 원래대로 돌립니다.',
      d19: '쉼터 밖에서 기어 P. 오늘의 주행을 보여 줍니다.',
      d19b: '세 번 연달아 "응" 했으니, 다음부터 맡길지 묻습니다.',
      d20: '자동 시연이 끝났습니다. 좌석과 버튼을 직접 눌러 보세요.',
      t1: '맡기기를 켜 둔 채 혼자 출발합니다.',
      t2: '내기순환을 켰습니다. 탁해질 때까지 빨리 감습니다.',
      t3: '맡겨 두었으니 묻지 않고 예고합니다. 5초 안에 취소할 수 있습니다.',
      t4: '답이 없어서 스스로 바꿨습니다. 20초 동안은 빨리 감습니다.',
      t5: '원래대로 돌렸습니다. 다시 탁해질 때까지 빨리 감습니다.',
      t6: '다시 예고합니다. 이번에는 취소를 누릅니다.',
      t7: '취소했습니다. 차는 그대로이고, 이번 주행의 남은 환기는 다시 묻습니다.',
      t8: '맡기기 시연이 끝났습니다. 아래 맡기기 버튼으로 직접 켜고 꺼 보세요.',
      dStopped: '직접 조작해서 자동 시연을 멈췄습니다.',
      dFail: '기다리던 단계가 오지 않아 자동 시연을 멈췄습니다.'
    },
    en: {
      title: 'Kkaeugi simulator',
      desc: 'The same decision engine as the app, running in your browser. Click a seat to add a passenger.',
      planTitle: 'Cabin', stripName: 'Driver controls', hvac: 'Climate',
      btnDemo: 'Auto demo', btnDemoStop: 'Stop auto demo', btnTrust: 'Hand-over demo', btnTrustStop: 'Stop hand-over demo', btnReset: 'Start over',
      speedReal: 'Real speed', speedDemo: '60x',
      voiceOn: 'Turn voice off', voiceOff: 'Turn voice on',
      noteDemo: '60x: a minute becomes a second, except while it waits for your answer.',
      noteReal: 'Real speed: with four aboard on recirculation it asks after about 4 minutes, alone after about 20.',
      simPill: 'Browser simulator',
      stIdle: 'Idle', stDriving: 'Driving', stResting: 'Resting', stEnded: 'Arrived', gear: 'gear',
      gaugeTitle: 'Cabin air (CO2 estimate)', ppmEst: 'ppm est.', limit: 'limit', people: function (n) { return n + (n === 1 ? ' person' : ' people'); },
      lineWarm: function (n) { return n + ' aboard, estimate starting'; },
      lineVent: 'Outside air coming in', lineVentFall: 'Outside air coming in, dropping',
      lineRest: 'Resting, estimate paused', lineWin: 'Window open, outside air coming in', lineWinFall: 'Window open, dropping',
      lineWeak: 'Outside air is on but the fan is weak (est.)',
      lineQuiet: 'On screen only for this drive', lineAuto: 'The car is managing air intake, so no questions', lineArriving: 'Arriving soon, so no airing questions',
      lineQuietOver: function (n) { return n + ' aboard, over the limit (est.). Not asking'; },
      freshBtn: '바깥 공기로 (Outside air)', runCancel: '취소 (Cancel)',
      lineCooldown: function (n, left, demo) { return n + ' aboard, will ask again in ' + left + (demo ? ' s' : ' min'); },
      lineNoLimit: function (n) { return n + ' aboard, will not reach the limit as is'; },
      lineOver: function (n) { return n + ' aboard, over the limit (est.)'; },
      lineIn: function (n, m, demo) { return n + ' aboard, airing suggestion in about ' + m + (demo ? ' s' : ' min'); },
      tripTitle: 'Trip time', mins: function (m) { return m >= 60 ? Math.floor(m / 60) + ' h ' + (m % 60) + ' min' : m + ' min'; },
      pillNight: 'Night, long trip', pillRain: 'Rain: window stays shut', pillRest: 'Resting',
      stopPlain: 'Next rest stop', stopSim: 'Next rest stop, simulated position', stopWp: 'Next rest stop, set as waypoint',
      stopNone: 'No rest stop this way within 80 km', stopName: function (n) { return enStop(n); },
      route: function (r) { return ROUTE_EN[r] || r; },
      todayTitle: 'Today',
      today1: function (v, f, demo) { return 'Aired out ' + v + 'x, outside air ' + f + (demo ? ' s' : ' min'); },
      todayPeak: function (p) { return 'Peak ' + p + ' ppm est.'; }, todayNoPeak: 'No peak estimate yet',
      yes: '응 (Yes)', no: '괜찮아 (No thanks)', speaking: 'Speaking...', cancel: '취소 (Cancel)',
      announceIn: function (n) { return 'Switching in ' + n + ' s. Press cancel if you would rather not'; },
      restNote: 'Kkaeugi only estimates the breath people exhale (carbon dioxide). It cannot detect exhaust gas (carbon monoxide).',
      trustYes: '맡길게요 (Go ahead)', trustNo: '계속 물어봐 주세요 (Keep asking)', trustOn: 'Handed over. From now on it tells you, then switches.', trustKept: 'It will keep asking.',
      countdown: function (n) { return 'Moves on in ' + n + ' s. Answer with the buttons'; },
      runTail: function (t) { return ', ' + t + ' 뒤 원래대로'; }, executing: '실행 중...', reverting: '되돌리는 중...',
      evWaypoint: function (km) { return '   |   waypoint in ' + km + ' km'; },
      restLine: function (name, n, demo, speech) { return demo ? name + '. ' + n + '초만 쉬고 가세요' + (speech ? '' : '(데모)') : name + '. ' + n + '분만 쉬고 가세요'; },
      restHint: 'Shift to D to return',
      arrTop: "Today's drive", ok: 'OK',
      mDrive: 'Drive', mVent: 'Aired out', mFresh: 'Outside air', mDrowsy: 'Drowsiness warnings', mRest: 'Rest stops', mPassed: 'Passed', mDeclined: 'Declined',
      times: function (n) { return n + 'x'; }, span: function (n, demo) { return n + (demo ? ' s' : ' min'); }, restVal: function (c, m, demo) { return c + 'x, ' + m + (demo ? ' s' : ' min'); },
      arrAir: function (peak, over, occ, demo) { return (peak > 0 ? 'Peak ' + fmtInt(peak) + ' ppm est., over the limit ' + over + (demo ? ' s' : ' min') + ' (est.), ' : '') + occ + ' aboard'; },
      idleBig: 'Shift to D to start',
      idleSub: function (n) { return 'Press D below the screen. ' + n + ' aboard now.'; },
      idleTripS: 'Idle', idleTripEnded: 'Arrived',
      idleAirT: function (n) { return 'Cabin air (CO2 estimate), ' + n + ' aboard'; },
      idleAirS: function (lim) { return 'An estimate computed from how many people are aboard and the ventilation state. It is not a sensor reading. Limit ' + lim + ' ppm.'; },
      idleStopNone: 'None ahead', idleStopS: 'Offered as a waypoint when a drowsiness cue comes in',
      lastSaid: function (s) { return 'Last message: ' + s; },
      subNoVoice: 'This browser has no Korean voice, so text only.',
      holdText: function (r) { return 'Holding the message for now: ' + r; },
      holdTurn: 'turn signal is on', holdWarn: 'a safety warning lamp is on', holdTurnNear: 'turn within 80 m', holdCooldown: 'less than 20 s since the last suggestion', holdSpeaking: 'the previous sentence is still being read',
      holdArriving: 'within 5 minutes of arrival, so no airing question', holdCarAuto: 'the car is managing air intake itself', holdLights: 'low beams were just turned on',
      seatDriver: 'Driver seat, always occupied', seatFront: 'Front passenger seat', seatRearL: 'Rear left seat', seatRearM: 'Rear middle seat', seatRearR: 'Rear right seat',
      recircOn: 'Recirculate', freshOn: 'Outside air', hvacFresh: 'Outside air now. Press for recirculation', hvacRecirc: 'Recirculating now. Press for outside air', fan: 'Fan', temp: 'Temp', fanDn: 'Fan down', fanUp: 'Fan up', tempDn: 'Temperature down', tempUp: 'Temperature up',
      winLabel: 'Driver window', winDn: 'Lower window', winUp: 'Raise window',
      wiper: 'Wipers', lowBeam: 'Low beams', turn: 'Signal', turnL: 'Left turn signal', turnR: 'Right turn signal', attn: 'Drowsiness warning',
      steerBtn: 'Sudden steering', steerRun: function (i) { return 'Sudden steering ' + i + '/3'; },
      tripBtn: '+2 hours driving', stopWithin: 'Reach rest stop', limit: 'limit',
      trustBtn: 'Hand-over', autoBtn: 'Car auto climate', etaBtn: '4 min to arrival',
      hintSeat: 'Try clicking a rear seat', hintGear: 'Press D below to start driving',
      sdkTitle: 'Controls the car received', sdkEmpty: 'Calls stack up here when the app controls the car',
      evShow: 'Show engine log', evHide: 'Hide engine log',
      nRevert: 'restore', nFresh: 'to outside air', nAlready: 'already outside air', nFan: function (a, b) { return 'fan ' + a + ' to ' + b; }, nFanMax: 'fan already at max',
      nTemp: function (a, b) { return 'temp ' + a + ' to ' + b; }, nTempMin: 'temp already at minimum', nWin: function (a, b) { return 'window ' + a + '% to ' + b + '%'; },
      nPassed: 'passed, waypoint removed', nKeepWp: 'waypoint kept',
      nDriver: function (what) { return what + ' left as is, the driver changed it'; }, nCancel: 'restore requested: remaining actions cancelled',
      wRecirc: 'recirculation', wFan: 'fan', wTemp: 'temp', wWin: 'window',
      d1: 'Just the driver aboard. We start as if the last two airing suggestions on earlier drives got a 응 (yes).',
      d2: 'Shifted to D. Setting off alone.',
      d3: 'Recirculation on. Alone, the air goes stale slowly.',
      d5: 'At the limit the car asks first. Alone, it offers outside air for 20 seconds.',
      d6: 'Pressed 응 (yes) for you.',
      d7: 'The app switched to outside air and raised the fan one step.',
      d8: 'The air is clearing. When the stated time is up, your settings come back.',
      d9: 'Recirculation and fan are back as they were.',
      d4: 'Three people got in the back. Same car, and it goes stale much faster.',
      d4b: 'With four aboard, 20 seconds is not enough. This time it asks to switch and leave it.',
      d5b: 'While it waits for an answer, time runs at real speed.',
      d6b: '응 (yes). Outside air, and it stays that way.',
      d11: 'Long night drive: low beams on, two more hours on the clock. The limit drops to 1,600.',
      d11b: 'The driver switched back to recirculation.',
      d12: 'The drowsiness warning lamp came on.',
      d13: 'It offers the rest stop ahead as a waypoint.',
      d14: function (win) { return win ? '응 (yes). Temp down 2 degrees, window to 15%, rest stop added.' : '응 (yes). Outside air, temp down 2 degrees, rest stop added. The window is left alone.'; },
      d15: 'Heading to the rest stop.',
      d16: '300 m from the rest stop.',
      d17: 'Gear P. The estimate pauses while you rest.',
      d17b: 'Resting on recirculation, so it asks once about outside air.',
      d17c: '응 (yes). Outside air, only while resting.',
      d18: 'Back to D. Setting off again, and the climate settings come back.',
      d19: "Gear P away from a rest stop. Here is today's drive.",
      d19b: 'Three 응 (yes) answers in a row, so it asks whether to hand this over.',
      d20: 'Demo finished. Try the seats and buttons yourself.',
      t1: 'Setting off alone with hand-over switched on.',
      t2: 'Recirculation on. Fast-forwarding until the air goes stale.',
      t3: 'Handed over, so it announces instead of asking. You have 5 seconds to cancel.',
      t4: 'No answer, so it switched by itself. Fast-forwarding through the 20 seconds.',
      t5: 'Settings are back. Fast-forwarding until the air goes stale again.',
      t6: 'It announces again. This time we press cancel.',
      t7: 'Cancelled. The car is untouched, and for the rest of this drive it asks again.',
      t8: 'Hand-over demo finished. Try the hand-over button below yourself.',
      dStopped: 'You took over, so the auto demo stopped.',
      dFail: 'An expected step did not happen, so the auto demo stopped.'
    }
  };
  function tr(key) {
    var v = T[lang][key];
    if (typeof v === 'function') return v.apply(null, Array.prototype.slice.call(arguments, 1));
    return v == null ? key : v;
  }

  // ---- 엔진의 한국어 문장에 붙이는 영어 풀이. 문장 꼴(엔진 WakeEngine 의 문자열)별 템플릿 ----
  var STOP_EN = { '석적': 'Seokjeok', '선기': 'Seongi', '대신': 'Daesin' };
  var ROUTE_EN = { '경부선 서울방향': 'Gyeongbu Expressway, Seoul-bound', '경부선': 'Gyeongbu Expressway' };
  /** "석적 졸음쉼터" / "석적" → "Seokjeok rest stop" */
  function enStop(name) { var base = String(name).replace(/\s*졸음쉼터$/, ''); return (STOP_EN[base] || base) + ' rest stop'; }
  /** "경부선 석적 졸음쉼터" → "Seokjeok rest stop (Gyeongbu Expressway)". 노선이 없으면 이름만 */
  function enWhere(where) {
    var i = where.indexOf(' '), route = i > 0 ? where.slice(0, i) : '';
    return ROUTE_EN[route] ? enStop(where.slice(i + 1)) + ' (' + ROUTE_EN[route] + ')' : enStop(where);
  }
  function enSpan(n, unit) { return n + ' ' + (unit === '분' ? (n === '1' ? 'minute' : 'minutes') : (n === '1' ? 'second' : 'seconds')); }
  function enTimes(n) { return n === '1' ? 'once' : n + ' times'; }
  var RUN_EN = { '바깥 공기로 바꾸는 중': 'Switching to outside air', '찬 바람 넣는 중': 'Cool air on', '찬 바람과 창문 열어 둔 상태': 'Cool air on, window slightly open', '실행 중...': 'Applying...', '되돌리는 중...': 'Restoring...' };
  var KIND_EN = { '환기': 'airing', '졸음': 'drowsiness', '쉬어 가기': 'break' };
  var LOG_EN = [
    [/^바깥 공기로 환기$/, function () { return 'aired out with outside air'; }],
    [/^바깥 공기로 계속 두기$/, function () { return 'left on outside air'; }],
    [/^풍량 올리기$/, function () { return 'fan turned up'; }],
    [/^바깥 공기로 두던 중 내기순환으로 바뀜$/, function () { return 'switched back to recirculation while on outside air'; }],
    [/^졸음 개입 실행$/, function () { return 'drowsiness response carried out'; }],
    [/^쉬어 가기 실행$/, function () { return 'break response carried out'; }],
    [/^제안 거절\((환기|졸음|쉬어 가기)\)$/, function (m) { return 'suggestion declined (' + KIND_EN[m[1]] + ')'; }],
    [/^제안 무응답\((환기|졸음|쉬어 가기)\)$/, function (m) { return 'no answer to suggestion (' + KIND_EN[m[1]] + ')'; }],
    [/^실행 실패\((환기|졸음|쉬어 가기)\)$/, function (m) { return 'control failed (' + KIND_EN[m[1]] + ')'; }],
    [/^(.+) 경유지 추가$/, function (m) { return enStop(m[1]) + ' added as waypoint'; }],
    [/^(.+)에서 (\d+)(분|초) 휴식$/, function (m) { return m[2] + (m[3] === '분' ? ' min' : ' s') + ' rest at ' + enStop(m[1]); }],
    [/^(.+) 지나침$/, function (m) { return 'drove past ' + enStop(m[1]); }],
    [/^졸음 경고\(운전자 주의 경고\)$/, function () { return 'drowsiness warning (driver attention warning)'; }],
    [/^핸들 보정이 잦아져 쉬어 가기 제안$/, function () { return 'frequent steering corrections, suggested a break'; }],
    [/^맡겨 둔 환기 실행$/, function () { return 'handed-over airing carried out'; }],
    [/^예고한 환기 취소$/, function () { return 'announced airing cancelled'; }],
    [/^환기 제안을 두 번 거절해 더 묻지 않음$/, function () { return 'two airing suggestions declined, no more questions on this drive'; }],
    [/^쉬는 동안 바깥 공기로 두기$/, function () { return 'outside air while resting'; }],
    [/^환기 중 내기순환으로 직접 바꿈$/, function () { return 'driver switched back to recirculation during airing'; }]
  ];
  function glossRunning(label) {
    if (RUN_EN[label]) return RUN_EN[label];
    var m = /^(.+) 유지 중$/.exec(label);
    if (!m) return '';
    return 'Holding: ' + m[1].split(', ').map(function (k) { return { '바깥 공기': 'outside air', '찬 바람': 'cool air', '창문': 'window' }[k] || k; }).join(', ');
  }
  /** 도착 카드 기록 줄: "출발 N분 뒤 {한 일}" */
  function glossLine(line) {
    var m = /^(출발 직후|출발 (\d+)분 뒤) (.+)$/.exec(line);
    if (!m) return '';
    var when = m[2] ? m[2] + ' min after departure' : 'Right after departure';
    for (var i = 0; i < LOG_EN.length; i++) { var mm = LOG_EN[i][0].exec(m[3]); if (mm) return when + ': ' + LOG_EN[i][1](mm); }
    return '';
  }
  function gloss(text) {
    if (!text) return '';
    var ph = ' 옆자리에서 눌러 주셔도 돼요.';
    if (text.slice(-ph.length) === ph) { var g0 = gloss(text.slice(0, -ph.length)); return g0 ? g0 + ' The front passenger can press it too.' : ''; }
    var s = text, pre = '', m;
    var night = '오래 달려서 조금 일찍 물어요. ';
    if (s.indexOf(night) === 0) { pre = "It's been a long drive, so I'm asking a little early. "; s = s.slice(night.length); }

    // 환기 제안(전부 질문). "N분만/N초만" 이 있으면 원복하는 제안, "바꿔 둘까요" 면 원복 없는 제안
    if ((m = /^(\d+)명이 타서 공기가 빨리 탁해져요\(추정 ([\d,]+)ppm\)\. (\d+)(분|초)만 바깥 공기로 바꿀까요\?$/.exec(s)))
      return pre + m[1] + ' people aboard, cabin air is getting stale fast (est. ' + m[2] + ' ppm). Switch to outside air for ' + enSpan(m[3], m[4]) + '?';
    if ((m = /^차 안 공기가 탁해진 것 같아요\(추정 ([\d,]+)ppm\)\. (\d+)(분|초)만 바깥 공기로 바꿀까요\?$/.exec(s)))
      return pre + 'Cabin air seems stale (est. ' + m[1] + ' ppm). Switch to outside air for ' + enSpan(m[2], m[3]) + '?';
    if ((m = /^(\d+)명이 타서 공기가 빨리 탁해져요\(추정 ([\d,]+)ppm\)\. 바깥 공기로 바꿔 둘까요\?$/.exec(s)))
      return pre + m[1] + ' people aboard, cabin air is getting stale fast (est. ' + m[2] + ' ppm). Switch to outside air and leave it there?';
    if ((m = /^차 안 공기가 빨리 탁해져요\(추정 ([\d,]+)ppm\)\. 바깥 공기로 바꿔 둘까요\?$/.exec(s)))
      return pre + 'Cabin air is getting stale fast (est. ' + m[1] + ' ppm). Switch to outside air and leave it there?';
    if (s === '여럿이 탈 땐 바깥 공기로 두는 게 좋아요. 계속 바깥 공기로 둘까요?')
      return pre + 'With several people aboard, outside air works better. Keep it on outside air?';
    if ((m = /^바깥 공기인데 바람이 약해요\(추정 ([\d,]+)ppm\)\. 풍량을 올릴까요\?$/.exec(s)))
      return pre + 'Outside air is on but the fan is weak (est. ' + m[1] + ' ppm). Turn the fan up?';
    if ((m = /^내기순환으로 (\d+)분째예요\. (\d+)(분|초)만 바깥 공기로 바꿀까요\?$/.exec(s)))
      return pre + 'Recirculation has been on for ' + enSpan(m[1], '분') + '. Switch to outside air for ' + enSpan(m[2], m[3]) + '?';

    // 맡겨 둔 환기의 예고, 쉼 모드의 질문, 조용 모드, 맡기기
    if ((m = /^(\d+)(분|초)만 바깥 공기로 바꿀게요\. 싫으면 '아니'라고 하세요\.$/.exec(s)))
      return pre + 'Switching to outside air for ' + enSpan(m[1], m[2]) + ". Say 아니 (no) if you'd rather not.";
    if ((m = /^(\d+)(분|초)만 바깥 공기로 바꿀게요\. 싫으면 취소를 누르세요\.$/.exec(s)))
      return pre + 'Switching to outside air for ' + enSpan(m[1], m[2]) + ". Press cancel if you'd rather not.";
    if (s === '알겠어요. 원래대로 돌렸어요.') return 'Okay. Back to your settings.';
    if ((m = /^차 안 공기가 많이 탁해요\(추정 ([\d,]+)ppm\)\. 화면에서 바깥 공기로 바꿀 수 있어요\.$/.exec(s)))
      return 'Cabin air is very stale (est. ' + m[1] + ' ppm). You can switch to outside air on screen.';
    if (s === '알겠어요. 그대로 둘게요.') return 'Okay. Leaving it as it is.';
    if (s === '쉬는 동안 바깥 공기로 둘까요?') return 'Keep outside air on while you rest?';
    if (s === '이번 주행에서는 더 묻지 않을게요. 화면에만 표시할게요.') return "I won't ask again on this drive. I'll only show it on screen.";
    if ((m = /^오늘까지 환기 제안 (\d+)번에 모두 '응' 하셨어요\. 다음부터 (\d+)(분|초) 환기는 말만 하고 바꿀까요\?$/.exec(s)))
      return "You've said 응 (yes) to all " + m[1] + ' airing suggestions so far. From now on, should the ' + m[2] + (m[3] === '분' ? '-minute' : '-second') + ' airing just be announced and done?';
    if (s === '맡기기를 껐어요. 다시 물어볼게요.') return 'Hand-over is off. It will ask again.';

    // 졸음 개입 제안: 첫머리(차의 경고, 핸들) + 질문
    var leads = [['졸음 경고가 떴어요. ', 'A drowsiness warning came on.'], ['핸들 보정이 잦아졌어요. ', 'Steering corrections are getting frequent.']];
    for (var i = 0; i < leads.length; i++) {
      if (s.indexOf(leads[i][0]) !== 0) continue;
      var rest = s.slice(leads[i][0].length), body = '';
      if ((m = /^비가 와서 창문은 두고, 찬 바람과 (\d+)km 앞 (.+) 경유까지 할까요\?$/.exec(rest)))
        body = "It's raining, so the window stays shut. Cool air, plus a stop at " + enWhere(m[2]) + ', ' + m[1] + ' km ahead?';
      else if ((m = /^찬 바람과 창문 조금, (\d+)km 앞 (.+) 경유까지 할까요\?$/.exec(rest)))
        body = 'Cool air, the window open a little, and a stop at ' + enWhere(m[2]) + ', ' + m[1] + ' km ahead?';
      else if ((m = /^찬 바람 넣고, (\d+)km 앞 (.+) 경유까지 할까요\?$/.exec(rest)))
        body = 'Cool air, plus a stop at ' + enWhere(m[2]) + ', ' + m[1] + ' km ahead?';
      else {
        if ((m = /^(.+)는 경유지에 있어요\. /.exec(rest))) { body = enStop(m[1]) + ' is already set as a waypoint. '; rest = rest.slice(m[0].length); }
        else if ((m = /^(\d+)분 뒤 도착이에요\. /.exec(rest))) { body = 'You arrive in ' + enSpan(m[1], '분') + '. '; rest = rest.slice(m[0].length); }
        else if (rest.indexOf('곧 도착이에요. ') === 0) { body = 'You are about to arrive. '; rest = rest.slice('곧 도착이에요. '.length); }
        if (rest === '찬 바람을 넣을까요?') body += 'Turn on cool air?';
        else if (rest === '비가 와서 창문은 두고 찬 바람만 넣을까요?') body += "It's raining, so leave the window shut and just turn on cool air?";
        else if (rest === '찬 바람 넣고 창문 조금 열까요?') body += 'Turn on cool air and open the window a little?';
      }
      return (pre + leads[i][1] + ' ' + body).trim();
    }

    if (s === '다음에 다시 물을게요.') return "I'll ask again later.";
    if ((m = /^알겠어요\. (\d+)(분|초) 뒤에 다시 볼게요\.$/.exec(s))) return "Okay. I'll check again in " + enSpan(m[1], m[2]) + '.';
    if ((m = /^추정 ([\d,]+)ppm ?까지 내려갔어요\. 원래대로 돌렸어요\.$/.exec(s))) return 'Down to an estimated ' + m[1] + ' ppm. Your settings are back as they were.';
    if (s === '원래대로 돌렸어요.') return 'Your settings are back as they were.';
    if (s === '일부는 못 돌렸어요. 직접 확인해 주세요.') return 'Some settings could not be restored. Please check them yourself.';
    if (s === '원복을 확인하지 못했어요.') return 'Could not confirm that the settings were restored.';
    if (s === '바깥 공기로 둘게요.') return 'Leaving it on outside air.';
    if (s === '풍량을 올려 둘게요.') return 'Leaving the fan turned up.';
    var wpAdded = ' 쉼터 경유지는 넣었어요.', tail = '';
    if (s.slice(-wpAdded.length) === wpAdded) { tail = ' The rest stop waypoint was added.'; s = s.slice(0, -wpAdded.length); }
    if (s === '주행 중엔 공조를 못 바꿔요. 조금 뒤에 다시 볼게요.') return "Climate controls can't be changed while driving. I'll check again shortly." + tail;
    if (s === '지금은 차를 바꿀 수 없어요. 조금 뒤에 다시 볼게요.') return "The car can't be adjusted right now. I'll check again shortly." + tail;

    if ((m = /^(\d+)(분|초) 푹 쉬셨어요\. 조심히 가요$/.exec(s))) return 'You rested a full ' + enSpan(m[1], m[2]) + '. Drive safe.';
    if (s === '잠깐 쉬셨어요. 다음 쉼터에서 더 쉬어도 좋아요') return 'A short break. You can rest more at the next stop.';
    if ((m = /^(\d+)(분|초) 쉬셨어요\. 다음 쉼터에서 더 쉬어도 좋아요$/.exec(s))) return 'You rested ' + enSpan(m[1], m[2]) + '. You can rest more at the next stop.';
    if ((m = /^(.+)\. (\d+)(분|초)만 쉬고 가세요(\(데모\))?$/.exec(s))) return enStop(m[1]) + '. Take a ' + m[2] + (m[3] === '분' ? ' minute' : ' second') + ' break' + (m[4] ? ' (demo).' : '.');

    // 도착 카드 첫 문장(60배 데모에서는 쉰 시간과 한도 초과 시간이 초로 바뀌어 온다)
    var unit = function (u) { return u === '분' ? ' min' : ' s'; };
    if ((m = /^오늘 환기 (\d+)번, 쉼터에서 (\d+)(분|초) 쉬었어요\.$/.exec(s))) return 'Today: aired out ' + enTimes(m[1]) + ' and rested ' + m[2] + unit(m[3]) + ' at a rest stop.';
    if ((m = /^오늘 쉼터에서 (\d+)(분|초) 쉬었어요\.$/.exec(s))) return 'Today: rested ' + m[1] + unit(m[2]) + ' at a rest stop.';
    if ((m = /^오늘 환기 (\d+)번, 졸음 경고 (\d+)번이 있었어요\.$/.exec(s))) return 'Today: aired out ' + enTimes(m[1]) + ', and there ' + (m[2] === '1' ? 'was 1 drowsiness warning.' : 'were ' + m[2] + ' drowsiness warnings.');
    if ((m = /^오늘 차 안 공기를 추정 ([\d,]+)ppm 아래로 지켰어요\.$/.exec(s))) return 'Today the cabin air stayed under an estimated ' + m[1] + ' ppm.';
    if ((m = /^오늘 환기 (\d+)번, 추정 ([\d,]+)ppm 을 넘긴 건 (\d+)(분|초)이었어요\.$/.exec(s))) return 'Today: aired out ' + enTimes(m[1]) + ', and spent ' + m[3] + unit(m[4]) + ' above an estimated ' + m[2] + ' ppm.';
    if ((m = /^오늘 환기 (\d+)번 했어요\.$/.exec(s))) return 'Today: aired out ' + enTimes(m[1]) + '.';
    if ((m = /^오늘 졸음 경고 (\d+)번이 있었어요\.$/.exec(s))) return 'Today: ' + (m[1] === '1' ? '1 drowsiness warning.' : m[1] + ' drowsiness warnings.');
    if ((m = /^오늘은 추정 ([\d,]+)ppm 위로 (\d+)(분|초) 달렸어요\. 무사히 도착했어요\.$/.exec(s))) return 'Today you drove ' + m[2] + unit(m[3]) + ' above an estimated ' + m[1] + ' ppm. You arrived safely.';
    if ((m = /^오늘은 제안 (\d+)번을 넘기셨어요\. 그래도 무사히 도착했어요\.$/.exec(s))) return 'Today you passed on ' + m[1] + (m[1] === '1' ? ' suggestion' : ' suggestions') + '. You still arrived safely.';
    if (s === '오늘은 개입 없이 무사히 도착했어요.') return 'Today you arrived safely with no intervention.';
    return '';
  }

  // ---------------------------------------------------------------- 상태

  /** 모의 위치 전방의 같은 방향 졸음쉼터(경부선 서울방향). km 은 출발 지점에서의 대략적인 거리 */
  var STOPS = [
    { name: '석적', routeLabel: '경부선 서울방향', km: 12.0, lat: 36.05163274, lng: 128.4080556 },
    { name: '선기', routeLabel: '경부선 서울방향', km: 24.5, lat: 36.13627142, lng: 128.3165002 },
    { name: '대신', routeLabel: '경부선 서울방향', km: 36.0, lat: 36.15281944, lng: 128.1907565 }
  ];
  var FAN_MAX = 8, TEMP_MIN = 16, TEMP_MAX = 30, WINDOW_CAP = 15, SPEED_KPH = 100;
  /** 모의 출발 지점(경부선 칠곡 부근, 앱 DemoLocation 과 같은 좌표) */
  var START = { km: 0, lat: 35.95, lng: 128.45 };
  function positionAt(km) {
    var pts = [START].concat(STOPS), i = 1;
    while (i < pts.length - 1 && km > pts[i].km) i++;
    var a = pts[i - 1], b = pts[i], f = clamp((km - a.km) / (b.km - a.km), 0, 1.5);
    return [a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f];
  }
  /** 가상 차량의 속도(km/h): 주행 중 기어 D 면 100, 쉼터 300m 안이나 정차면 0 */
  function speedNow() { return car.gear === 'DRIVE' && driving() && !ui.rest && !car.within ? SPEED_KPH : 0; }

  function freshCar() {
    return {
      gear: 'PARK', seats: [true, false, false, false, false], recirc: false, fan: 3, temp: 24, window: 0,
      wiper: false, lowBeam: false, turn: 'OFF', attention: false, lka: false, tripOdo: 0, autoClimate: false, eta: null,
      posKm: 0, tracking: null, within: false, steerStep: 0, steerDeg: 0
    };
  }
  function freshUi() {
    return {
      tripState: 'IDLE', proposal: null, running: null, rest: null, restToast: null, arrival: null, lastSaid: null, deferred: null,
      air: null, waypoint: null, nextStop: null, proximityKm: null, tripStartedMs: null, vents: 0, peak: 0, maxOcc: 1,
      evidence: null, speaking: false, voiceMissing: false, staleCooldownUntil: null, trustAsk: null
    };
  }
  var car = freshCar(), ui = freshUi(), sim = null;
  var demoFast = params.get('speed') !== 'real', voice = false;
  var sdkLog = [], evLog = [];
  /** 평면도 아래 한 줄. 자동 시연 중에는 지금 단계, 끝난 직후 몇 초는 마지막 문장 */
  var caption = { key: null, demo: false, until: 0 };
  /** 처음 연 사람에게만 주는 힌트: seat(뒷좌석을 눌러 보세요) → gear(기어 D) → done */
  var hint = 'seat';
  /** 돌고 있는 자동 시연. kind 는 'main'(자동 시연) 또는 'trust'(맡기기 시연) */
  var demo = null;
  /** 맡기기 상태(엔진이 들고 있는 값). 페이지 메모리에만 있어서 새로고침하거나 처음으로 돌아가면 꺼진다 */
  function trust() { return JSON.parse(sim.trust); }

  /**
   * 바깥 공기로 달린 시간("오늘" 카드). 앱 FreshAirTally 와 같은 기준으로 센다.
   * 구간은 환기가 성공한 때 열리고 원복이 끝난 때 닫힌다. 원복 없이 두기로 했으면 운전자가 내기순환으로 되돌리거나 주행이 끝날 때까지 이어진다.
   * 닫힌 구간은 단위(분, 60배 데모면 초)로 반올림해 더하고(최소 1), 열려 있는 구간은 지금까지 흐른 만큼을 내림해 보여 준다
   */
  var fresh = {
    since: null, closed: 0, keeping: false,
    unit: function () { return sim.demoFast ? 1000 : 60000; },
    open: function (now) { if (this.since == null) this.since = now; },
    close: function (now) {
      if (this.since != null) this.closed += Math.max(1, Math.round(Math.max(0, now - this.since) / this.unit()));
      this.since = null;
    },
    /** 원복 없이 바깥 공기로 두기로 했다. 구간은 열어 둔다 */
    keep: function (now) { this.keeping = true; this.open(now); },
    /** 운전자가 내기순환으로 되돌렸다. 두기로 한 중이었으면 구간을 닫는다. 반환: 유지가 풀렸는가 */
    onRecircOn: function (now) { if (!this.keeping) return false; this.keeping = false; this.close(now); return true; },
    /** 원복까지 끝났다. 바깥 공기로 두는 중이 아니면 구간을 닫는다 */
    onReverted: function (now) { if (!(this.keeping && !car.recirc)) this.close(now); },
    /** 쉬는 시간은 넣지 않는다. 쉼이 끝나고 여전히 바깥 공기로 두는 중이면 다시 연다 */
    onRestStarted: function (now) { this.close(now); },
    onRestEnded: function (now) { if (this.keeping && !car.recirc) this.open(now); },
    units: function (now) { return this.closed + (this.since == null ? 0 : Math.floor(Math.max(0, now - this.since) / this.unit())); },
    reset: function () { this.since = null; this.closed = 0; this.keeping = false; }
  };

  function occupants() { return car.seats.filter(Boolean).length; }
  function driving() { return ui.tripState === 'DRIVING'; }

  // ---------------------------------------------------------------- 로그

  function stamp(t) { var s = t / 1000; return '+' + Math.floor(s / 60) + ':' + (s % 60 < 10 ? '0' : '') + (s % 60).toFixed(1); }
  function logEv(cls, t, text, coalesceKey) {
    var last = evLog[evLog.length - 1];
    // 매 초 오는 같은 종류(추정치, 카운트다운)는 한 줄을 고쳐 쓴다
    if (coalesceKey && last && last.key === coalesceKey) { last.t = t; last.text = text; last.dirty = true; return; }
    evLog.push({ cls: cls, t: t, text: text, key: coalesceKey || null, dirty: true });
    if (evLog.length > 300) evLog.splice(0, evLog.length - 300);
    logsDirty = true;
  }
  function sdk(call, note, isRevert) {
    var t = clock.now();
    sdkLog.push({ t: t, call: call, note: note || '', revert: !!isRevert, fresh: true });
    if (sdkLog.length > 120) sdkLog.shift();
    if (call) ui.evidence = call + ' → ok';
    logEv('say', t, 'SDK  ' + (call ? call + ' → ok' : note));
    sdkDirty = true;
  }
  var logsDirty = true, sdkDirty = true;
  function renderLogs() {
    if (sdkDirty) renderSdk();
    if (!logsDirty && !evLog.some(function (l) { return l.dirty; })) return;
    logsDirty = false;
    if (!$('evBox').open) { logsDirty = true; return; }
    var e = $('evLog');
    var stick = e.scrollHeight - e.scrollTop - e.clientHeight < 40;
    e.innerHTML = evLog.map(function (l) { l.dirty = false; return '<li class="' + l.cls + '"><span class="t">' + stamp(l.t) + '</span><span>' + esc(l.text) + '</span></li>'; }).join('');
    if (stick) e.scrollTop = e.scrollHeight;
  }
  /** 오른쪽 "차가 받은 제어": 호출 한 줄씩. 방금 들어온 줄은 한 번 파랗게 */
  function renderSdk() {
    sdkDirty = false;
    var s = $('sdkLog');
    // 여섯 줄까지: 오른쪽 열이 평면도보다 길어져 아래 버튼 띠를 밀지 않게
    s.innerHTML = sdkLog.length ? sdkLog.slice(-6).map(function (l) {
      var cls = (l.revert ? 'revert' : '') + (l.fresh ? ' new' : '');
      l.fresh = false;
      return '<li class="' + cls.trim() + '">' + (l.call ? '<span class="c">' + esc(l.call) + '</span><span class="ok">ok</span>' : '') +
        (l.note ? '<span class="n">' + esc(l.note) + '</span>' : '') + '</li>';
    }).join('') : '<li class="empty">' + esc(tr('sdkEmpty')) + '</li>';
  }
  function describe(e) {
    switch (e.type) {
      case 'Propose': return 'Propose ' + e.kind + ' [' + e.actions.map(function (a) { return a.type; }).join(', ') + ']' + (e.passengerMayAnswer ? ' 조수석' : '') + ' "' + e.text + '"';
      case 'Announce': return 'Announce ' + e.kind + ' [' + e.actions.map(function (a) { return a.type; }).join(', ') + '] ' + e.cancelWindowSec + 's "' + e.text + '"';
      case 'Quiet': return 'Quiet "' + e.text + '"';
      case 'TrustChanged': return 'TrustChanged 맡김=' + e.state.delegated + ' 연속 동의=' + e.state.consecutiveYes + ' 취소=' + e.state.cancelCount;
      case 'ProposalExpired': return 'ProposalExpired "' + e.text + '"';
      case 'Execute': return 'Execute ' + e.kind + ' [' + e.actions.map(function (a) { return a.type; }).join(', ') + ']';
      case 'Running': return 'Running ' + e.kind + ' ' + e.remainingSec + 's "' + e.label + '"';
      case 'Done': return 'Done ' + e.kind + (e.text ? ' "' + e.text + '"' : ' (말 없음)');
      case 'Deferred': return 'Deferred ' + e.kind + ' ' + e.reason;
      case 'TripStateChanged': return 'TripStateChanged ' + e.state;
      case 'RestStarted': return 'RestStarted ' + e.stopName + ' ' + e.targetMinutes;
      case 'RestTick': return 'RestTick ' + e.remainingSec + 's';
      case 'RestEnded': return 'RestEnded ' + e.restedMinutes + ' "' + e.text + '"';
      case 'AirEstimate': return 'AirEstimate ' + e.ppm + 'ppm ' + e.occupants + '명 한도까지 ' + (e.minutesToLimit == null ? '-' : e.minutesToLimit) + (e.ventilating ? ' 환기 중' : '');
      case 'ArrivalCard': return 'ArrivalCard "' + e.card.headline + '"' + (e.card.trustOffer ? ' 맡기기 질문' : '');
    }
    return e.type;
  }

  // ---------------------------------------------------------------- 엔진 호출(순서대로 하나씩)

  var queue = [], pumping = false;
  /** 마지막으로 받은 Execute 가 원복이 아니었고 아직 Done 이 오지 않았으면 그 액션 목록. 원복이었으면 reverting */
  var execOpen = null, reverting = false;
  /** 지금 떠 있는(또는 방금 닫힌) 제안의 종류 */
  var proposalKind = null;
  /** 신호 하나를 엔진에 넣는다. 처리 중에 들어온 신호는 줄을 서서 차례로 들어간다(앱의 SignalBus 와 같은 순서 보장) */
  function emit(label, call, coalesceKey) {
    queue.push([label, call, epoch, coalesceKey || null]);
    if (!pumping) pump();
  }
  function pump() {
    pumping = true;
    try {
      while (queue.length) {
        var item = queue.shift();
        if (item[2] !== epoch) continue;
        var t = clock.now();
        if (item[0]) logEv('in', t, '→ ' + item[0], item[3] || (/^SteeringAngle/.test(item[0]) ? 'steer' : null));
        var events;
        try { events = JSON.parse(item[1](t)); } catch (err) { logEv('err', t, String(err)); continue; }
        handle(events, t);
      }
    } finally { pumping = false; }
    render();
  }

  var sig = {
    gear: function () { emit('GearChanged ' + car.gear, function (t) { return sim.gear(t, car.gear); }); },
    recirc: function () {
      actuator.observeRecirc(car.recirc);
      // 바깥 공기로 두기로 한 뒤 운전자가 직접 내기순환을 켠 것은 거절로 본다(엔진과 같은 쿨다운)
      if (car.recirc && sim && !(ui.running && ui.running.remainingSec > 0) && fresh.onRecircOn(clock.now())) markStaleCooldown(clock.now());
      emit('RecirculationChanged ' + (car.recirc ? '내기순환' : '외기'), function (t) { return sim.recirc(t, car.recirc); });
    },
    passenger: function () { var v = !!car.seats[1]; emit('FrontPassengerChanged ' + v, function (t) { return sim.frontPassenger(t, v); }); },
    autoClimate: function () { var v = car.autoClimate; emit('AutoClimateChanged ' + v, function (t) { return sim.autoClimate(t, v); }); },
    eta: function () { var v = car.eta; emit('DestinationEta ' + (v == null ? '없음' : v + '분'), function (t) { return sim.destinationEta(t, v); }); },
    occupants: function () {
      var n = occupants();
      ui.maxOcc = Math.max(ui.maxOcc, n);
      emit('OccupantsChanged ' + n, function (t) { return sim.occupants(t, n); });
    },
    fan: function () { var v = car.fan; emit('FanSpeedChanged ' + v + '/' + FAN_MAX, function (t) { return sim.fan(t, v, FAN_MAX); }); },
    window: function () { var v = car.window; emit('WindowOpenChanged ' + v + '%', function (t) { return sim.windowOpen(t, v); }); },
    wiper: function () { var v = car.wiper; emit('WiperChanged ' + v, function (t) { return sim.wiper(t, v); }); },
    lowBeam: function () { var v = car.lowBeam; emit('LowBeamChanged ' + v, function (t) { return sim.lowBeam(t, v); }); },
    turn: function () { var v = car.turn; emit('TurnSignalChanged ' + v, function (t) { return sim.turnSignal(t, v); }); },
    telltale: function (type, on, blink) { emit('TelltaleChanged ' + type + ' ' + (on ? (blink ? 'BLINK' : 'ON') : 'OFF'), function (t) { return sim.telltale(t, type, on, blink); }); },
    tripTime: function () { var v = car.tripOdo; emit('TripTimeUpdate ' + v, function (t) { return sim.tripTime(t, v); }); },
    steering: function (deg) { car.steerDeg = deg; emit('SteeringAngle ' + deg, function (t) { return sim.steering(t, deg); }); },
    /** 위치와 속도. 모의 위치는 출발 지점과 전방 쉼터 좌표 사이를 거리 비율로 잇는다 */
    location: function () {
      var p = positionAt(car.posKm), kph = speedNow();
      emit('LocationUpdate ' + p[0].toFixed(4) + ',' + p[1].toFixed(4) + ' ' + kph + 'km/h', function (t) { return sim.location(t, p[0], p[1], kph); }, 'loc');
    },
    proximity: function (within, passed) { emit('RestStopProximity within=' + within + (passed ? ' passed' : ''), function (t) { return sim.restProximity(t, within, passed); }); },
    candidate: function (stop) {
      if (stop) emit('RestStopCandidate ' + stop.name + ' ' + stop.distanceKm.toFixed(1) + 'km', function (t) { return sim.restStopCandidate(t, stop.name, stop.routeLabel, stop.distanceKm, stop.lat, stop.lng); });
      else emit('RestStopCandidate 없음', function (t) { return sim.noRestStop(t); });
    }
  };

  // ---------------------------------------------------------------- 엔진 이벤트 처리 (앱 WakeViewModel.handle 과 같은 순서)

  function handle(events, t) {
    for (var i = 0; i < events.length; i++) {
      var e = events[i];
      var key = e.type === 'AirEstimate' || e.type === 'RestTick' || e.type === 'Running' ? e.type : null;
      logEv('ev', t, '← ' + describe(e), key);
      switch (e.type) {
        case 'Propose': onPropose(e); break;
        case 'Announce': onAnnounce(e); break;
        case 'Quiet':
          // 홈 문구는 엔진의 quietMode 를 읽어 바꾼다. 여기서는 한 번 읽어 주기만 한다
          ui.proposal = null;
          say(e.text);
          break;
        case 'TrustChanged': break;
        case 'ProposalExpired':
          ui.proposal = null;
          if (proposalKind === 'STALE_AIR') markStaleCooldown(t);
          say(e.text);
          break;
        case 'Execute':
          // 원복 없이 끝나는 실행인지 가리기 위해, 마지막 Execute 가 원복이었는지 기억한다
          execOpen = e.revert ? null : e.actions; reverting = e.revert;
          ui.proposal = null;
          // 동의 직후 Running 이 오기 전까지 화면이 비지 않게 바로 바를 띄운다
          ui.running = { kind: e.kind, remainingSec: 0, label: e.revert ? tr('reverting') : tr('executing'), pending: e.revert ? 'reverting' : 'executing' };
          actuator.execute(e.actions);
          break;
        case 'Running':
          ui.running = { kind: e.kind, remainingSec: e.remainingSec, label: e.label };
          break;
        case 'Done':
          // 실행 뒤 원복(Execute([Revert])) 없이 Done 이 오면 그대로 두기로 한 것이다(바깥 공기 유지, 풍량 올려 두기).
          // 저장해 둔 실행 전 값을 버려 나중 원복이 되돌리지 않게 한다
          var hadPrompt = !!ui.proposal || !!(ui.rest && ui.rest.ask);
          if (ui.rest) {
            // 쉼 모드의 바깥 공기 질문이 닫혔다. 동의해서 바꿔 둔 값은 쉼이 끝날 때 되돌려야 하니 실행 전 값을 버리지 않는다
            ui.rest.ask = null;
          } else if (execOpen) {
            actuator.keepChanges(execOpen);
            if (execOpen.some(function (a) { return a.type === 'Ventilate'; })) fresh.keep(t);
          } else if (reverting) fresh.onReverted(t);
          execOpen = null; reverting = false;
          ui.proposal = null; ui.running = null; ui.deferred = null;
          // 말없이 닫는 Done: 읽던 질문이 있으면 끊는다. 다른 문장(쉼 끝 한마디 등)은 끊지 않는다
          if (e.text) say(e.text); else if (ui.speaking && hadPrompt) cancelSpeech();
          break;
        case 'Deferred':
          ui.deferred = e.reason; ui.deferredAt = t;
          break;
        case 'TripStateChanged': onTripState(e.state, t); break;
        case 'RestStarted':
          // 화면과 음성 모두 같은 쉼터 표시 이름을 쓴다
          var restName = sim.stopDisplayName(e.stopName);
          ui.rest = { stopName: restName, targetMinutes: e.targetMinutes, remainingSec: e.targetMinutes * (sim.demoFast ? 1 : 60), demo: sim.demoFast, ask: null };
          ui.proposal = null; ui.running = null;
          execOpen = null; reverting = false;
          fresh.onRestStarted(t);
          say(T.ko.restLine(restName, e.targetMinutes, sim.demoFast, true));
          break;
        case 'RestTick':
          if (ui.rest) ui.rest.remainingSec = e.remainingSec;
          break;
        case 'RestEnded':
          var text = demoUnits(e.text, ui.rest ? ui.rest.demo : sim.demoFast);
          tracker.stop();
          fresh.onRestEnded(t);
          ui.rest = null; ui.waypoint = null; ui.restToast = text;
          say(text);
          later(2500, function () { ui.restToast = null; render(); });
          tracker.sendCandidate(true);
          break;
        case 'AirEstimate':
          var prev = ui.air;
          ui.air = { ppm: e.ppm, occupants: e.occupants, minutesToLimit: roundedMinutes(e, sim.effectiveLimitPpm), ventilating: e.ventilating, falling: prev ? (e.ppm < prev.ppm || (e.ppm === prev.ppm && prev.falling)) : false };
          ui.peak = Math.max(ui.peak, e.ppm); ui.maxOcc = Math.max(ui.maxOcc, e.occupants);
          break;
        case 'ArrivalCard':
          ui.arrival = e.card; ui.proposal = null; ui.running = null; ui.rest = null;
          ui.arrivalFresh = fresh.units(t); ui.arrivalDemo = sim.demoFast;
          ui.trustAsk = e.card.trustOffer ? 'ask' : null;
          // 60배 데모면 문장 안의 분도 초로 읽는다
          say(demoUnits(e.card.headline, sim.demoFast));
          break;
      }
    }
  }

  /** 환기 제안을 거절했거나 답이 없었다. 엔진이 그동안 다시 묻지 않으니 홈 문구도 그렇게 알린다(앱 markStaleCooldown) */
  function markStaleCooldown(now) { ui.staleCooldownUntil = now + sim.declineCooldownMinutes * (sim.demoFast ? 1000 : 60000); }

  /**
   * 화면의 "약 N분 뒤". 엔진 값은 올림한 정수라 계산값보다 크게 보인다. 화면에는 반올림해서 보여 준다(앱 AirMath 와 같은 식).
   * 엔진 값이 없거나 0 이거나 환기 중이면 그대로 따르고, 반올림 값이 엔진 값과 1 넘게 벌어지면 엔진 값을 쓴다
   */
  function roundedMinutes(e, limit) {
    var m = e.minutesToLimit;
    if (m == null || m <= 0 || e.ventilating) return m;
    var gen = Math.max(1, e.occupants) * sim.co2PerPersonLph * 1e6 / Math.max(1, sim.cabinVolumeL), ach = sim.leakAch, exact;
    if (e.ppm >= limit) exact = 0;
    else if (ach < 1e-9) exact = gen > 0 ? (limit - e.ppm) / gen * 60 : null;
    else { var css = sim.outdoorPpm + gen / ach; exact = css <= limit ? null : Math.log((css - e.ppm) / (css - limit)) / ach * 60; }
    if (exact == null) return m;
    var r = Math.max(1, Math.round(exact));
    return r >= m - 1 && r <= m ? r : m;
  }

  function onPropose(e) {
    setBoost(1);
    if (ui.rest) {
      // 쉼 모드의 바깥 공기 질문. 쉼 카드 안에서 묻고 화면에서 눌러 답한다. 쉼 안내에 이어서 읽는다
      var ask = ui.rest.ask = { text: e.text, timeoutSec: e.timeoutSec, countdownFrom: null, passenger: !!e.passengerMayAnswer };
      speak(T.ko.restLine(ui.rest.stopName, ui.rest.targetMinutes, ui.rest.demo, true) + ' ' + e.text, function () {
        if (!ui.rest || ui.rest.ask !== ask) return;
        ask.countdownFrom = clock.now();
        emit('SpeechFinished', function (t) { return sim.speechFinished(t); });
      });
      return;
    }
    proposalKind = e.kind;
    ui.proposal = { kind: e.kind, text: e.text, timeoutSec: e.timeoutSec, countdownFrom: null, passenger: !!e.passengerMayAnswer, announce: false };
    ui.deferred = null;
    // 문장을 다 읽으면 SpeechFinished. 엔진은 그때부터 timeoutSec 를 센다
    speak(e.text, function () {
      if (!ui.proposal) return;
      ui.proposal.countdownFrom = clock.now();
      emit('SpeechFinished', function (t) { return sim.speechFinished(t); });
    });
  }

  /** 이 페이지에는 음성 인식이 없다. 앱이 말로 답할 수 없는 환경에서 하듯 예고 문장의 "아니라고 하세요" 를 "취소를 누르세요" 로 바꿔 보여 주고 읽는다(앱 announceText) */
  function announceText(text) { return String(text).replace("싫으면 '아니'라고 하세요", '싫으면 취소를 누르세요'); }

  /** 맡겨 둔 환기의 예고. 문장을 다 읽은 뒤 cancelWindowSec 안에 취소가 없으면 엔진이 Execute 를 낸다 */
  function onAnnounce(e) {
    setBoost(1);
    proposalKind = e.kind;
    var text = announceText(e.text);
    var p = ui.proposal = { kind: e.kind, text: text, timeoutSec: e.cancelWindowSec, countdownFrom: null, passenger: false, announce: true };
    ui.deferred = null;
    speak(text, function () {
      if (ui.proposal !== p) return;
      p.countdownFrom = clock.now();
      emit('SpeechFinished', function (t) { return sim.speechFinished(t); });
    });
  }

  /** 60배 데모에서 엔진이 "분"으로 적은 시간(쉰 시간 등)은 실제로 초였다. 숫자 뒤의 "분"만 "초"로 바꾼다(앱 DemoText.minutesToSeconds) */
  function demoUnits(text, demo) { return demo ? String(text).replace(/(\d+)분/g, '$1초') : text; }

  function onTripState(state, now) {
    ui.tripState = state;
    ui.deferred = null; ui.staleCooldownUntil = null;
    execOpen = null; reverting = false;
    if (state === 'DRIVING') {
      ui.tripStartedMs = now;
      car.tripOdo = 0;
      ui.lastSaid = null; ui.arrival = null; ui.rest = null; ui.restToast = null; ui.waypoint = null; ui.proximityKm = null;
      ui.vents = 0; ui.peak = 0; ui.maxOcc = occupants(); ui.air = null; ui.evidence = null;
      fresh.reset();
      tracker.stop();
      tracker.sendCandidate(true);
      // 엔진은 새 주행을 시작할 때 지난 남은 시간을 버린다. 내비에 목적지가 있으면 다시 알린다(앱도 같다)
      if (car.eta != null) { var eta0 = car.eta; emit(null, function (t) { return sim.destinationEta(t, eta0); }); }
    } else if (state === 'ENDED') {
      fresh.close(now);
      ui.rest = null;
      tracker.stop();
    }
  }

  function answer(yes) {
    var restAsk = ui.rest && ui.rest.ask;
    if (!ui.proposal && !restAsk) return;
    // 쉼 모드 질문의 거절은 어디에도 세지 않는다. 그 밖의 환기 거절과 예고 취소는 쿨다운이 걸린다
    if (!yes && ui.proposal && ui.proposal.kind === 'STALE_AIR') markStaleCooldown(clock.now());
    var word = yes ? '응' : (ui.proposal && ui.proposal.announce ? '취소' : '괜찮아');
    cancelSpeech();
    emit('UserAnswer ' + word, function (t) { return sim.answer(t, yes); });
  }

  /** 실행 중 바의 [취소]. 예고로 시작한 환기와 직접 요청한 환기는 유지 시간 내내 취소할 수 있다(엔진이 바로 원복한다) */
  function cancelRun() {
    if (!sim.cancellableRun) return;
    if (proposalKind === 'STALE_AIR') markStaleCooldown(clock.now());
    cancelSpeech();
    emit('UserAnswer 취소(실행 중)', function (t) { return sim.answer(t, false); });
  }

  /** 홈의 "바깥 공기로"(조용 모드에서 한도를 넘었을 때만 보인다). 묻지 않고 바로 환기하고 말한 시간 뒤 원복한다 */
  function requestFreshAir() {
    if (!sim.overLimitWhileQuiet) return;
    proposalKind = null;
    emit('requestVentilation (화면 버튼)', function (t) { return sim.requestVentilation(t); });
  }

  /** 도착 카드의 맡기기 질문에 답한다 */
  function trustAnswer(accept) {
    if (ui.trustAsk !== 'ask') return;
    ui.trustAsk = accept ? 'on' : 'kept';
    emit('TrustAnswer ' + (accept ? '맡길게요' : '계속 물어봐 주세요'), function (t) { return sim.trustAnswer(t, accept); });
  }

  /** 도착 카드 "확인". 이미 다시 달리는 중이면 카드만 닫는다 */
  function dismissArrival() {
    if (sim.tripState !== 'DRIVING') {
      sim.reset();
      ui.tripState = 'IDLE'; ui.lastSaid = null; ui.deferred = null; ui.running = null; ui.proposal = null; ui.tripStartedMs = null;
      car.tripOdo = 0;
    }
    ui.arrival = null; ui.trustAsk = null;
    render();
  }

  function tripMinutes() {
    var timer = ui.tripStartedMs == null ? 0 : Math.floor((clock.now() - ui.tripStartedMs) / 60000);
    return Math.max(timer, car.tripOdo);
  }

  // ---------------------------------------------------------------- 말하기

  var speechSeq = 0;
  function koVoice() {
    try {
      if (!('speechSynthesis' in window)) return null;
      return speechSynthesis.getVoices().filter(function (v) { return /^ko([-_]|$)/i.test(v.lang); })[0] || null;
    } catch (err) { return null; }
  }
  function cancelSpeech() {
    speechSeq++;
    ui.speaking = false;
    try { if ('speechSynthesis' in window) speechSynthesis.cancel(); } catch (err) { /* 무시 */ }
  }
  /**
   * 소리로 읽을 문장(앱 speechText 와 같은 규칙). 괄호와 그 안(화면용 추정 수치)은 읽지 않는다.
   * 괄호가 겹쳐 있으면 바깥 괄호까지, 닫는 괄호가 없으면 끝까지 뺀다. 남은 겹친 공백과 문장부호 앞 공백은 정리한다
   */
  function spoken(text) {
    var out = '', depth = 0;
    String(text).split('').forEach(function (ch) {
      if (ch === '(' || ch === '\uFF08') depth++;
      else if (ch === ')' || ch === '\uFF09') { if (depth > 0) depth--; else out += ch; }
      else if (depth === 0) out += ch;
    });
    return out.replace(/\s+/g, ' ').replace(/ ([.,?!])/g, '$1').trim();
  }
  /** 문장을 읽는다(또는 읽은 셈 친다). 끝나면 onDone. 새 문장이 오면 앞 문장은 끊긴다 */
  function speak(text, onDone) {
    cancelSpeech();
    var id = ++speechSeq, my = epoch;
    ui.speaking = true; ui.lastSaid = text;
    var finished = false;
    var finish = function () {
      if (finished || id !== speechSeq || my !== epoch) return;
      finished = true;
      ui.speaking = false;
      if (onDone) onDone();
      render();
    };
    var v = voice ? koVoice() : null;
    ui.voiceMissing = voice && !v;
    if (v) {
      try {
        var u = new SpeechSynthesisUtterance(spoken(text));
        u.lang = 'ko-KR'; u.voice = v;
        u.onend = finish; u.onerror = finish;
        speechSynthesis.speak(u);
        // 일부 브라우저는 끝 이벤트를 놓친다. 넉넉한 상한을 둔다
        later(spoken(text).length * 400 + 4000, finish);
        return;
      } catch (err) { /* 아래 타이머로 */ }
    }
    // 음성이 없으면 읽는 시간만큼 기다린다: 글자 수 x 0.18초 + 0.5초
    later(spoken(text).length * 180 + 500, finish);
  }
  function say(text) { speak(text, null); }

  // ---------------------------------------------------------------- 가상 차량: 제어 실행과 원복 (앱 VehicleActuator 와 같은 규칙)

  var flashTimers = {};
  /** 앱이 바꾼 자리(평면도의 공조 버튼, 창문)는 파란 테두리로 한 번 반짝인다 */
  function flash(ctl) {
    var el = document.querySelector('[data-ctl="' + ctl + '"]');
    if (!el) return;
    el.classList.remove('flash');
    void el.offsetWidth;
    el.classList.add('flash');
    clearTimeout(flashTimers[ctl]);
    flashTimers[ctl] = setTimeout(function () { el.classList.remove('flash'); }, 1400);
  }
  /** 차량 값을 바꾸고 해당 신호를 엔진에 보낸다. byApp 이면 강조 표시 */
  function setCar(ctl, value, byApp) {
    car[ctl] = value;
    if (byApp) flash(ctl);
    if (ctl === 'recirc') sig.recirc();
    else if (ctl === 'fan') sig.fan();
    else if (ctl === 'window') sig.window();
    render();
  }

  var actuator = (function () {
    var saved = {}, current = null, chain = Promise.resolve();
    var STEP_MS = 300;
    /** 정수 값(풍량, 창문)의 원복 허용 오차: 범위의 5%, 최소 1. 온도는 최소 0.5도 */
    function tolInt(min, max) { return Math.max(1, Math.round((max - min) * 0.05)); }
    function tolTemp(min, max) { return Math.max(0.5, (max - min) * 0.05); }

    async function perform(a) {
      if (a.type === 'Ventilate') {
        if (!(await sleep(STEP_MS))) return null;
        var before = car.recirc;
        sdk('setHvacRecirculation(false)', before ? tr('nFresh') : tr('nAlready'));
        if (!saved.recirc) saved.recirc = { before: before, set: false };
        setCar('recirc', false, true);
        if (a.fanDelta) {
          if (!(await sleep(STEP_MS))) return null;
          var cur = car.fan, target = clamp(cur + a.fanDelta, 0, FAN_MAX);
          if (target !== cur) {
            sdk('setHvacFanSpeed(' + target + ')', tr('nFan', cur, target));
            if (!saved.fan) saved.fan = { before: cur, set: target };
            setCar('fan', target, true);
          } else sdk('', tr('nFanMax'));
        }
        // 쉬는 동안 바깥 공기로 둔 것은 환기 횟수와 바깥 공기로 달린 시간에 넣지 않는다
        if (!ui.rest) { ui.vents++; fresh.open(clock.now()); }
        return [true, 'ok'];
      }
      if (a.type === 'CoolDown') {
        if (!(await sleep(STEP_MS))) return null;
        var t0 = car.temp, t1 = Math.round(clamp(t0 + a.celsiusDelta, TEMP_MIN, TEMP_MAX) * 2) / 2;
        if (Math.abs(t1 - t0) < 0.25) { sdk('', tr('nTempMin')); return [true, 'ok']; }
        sdk('setHvacTemperature(' + t1.toFixed(1) + ')', tr('nTemp', t0.toFixed(1), t1.toFixed(1)));
        if (!saved.temp) saved.temp = { before: t0, set: t1 };
        setCar('temp', t1, true);
        return [true, 'ok'];
      }
      if (a.type === 'OpenWindow') {
        if (!(await sleep(STEP_MS))) return null;
        // 창문은 15% 를 넘겨 열지 않는다
        var w0 = car.window, w1 = clamp(a.percent, 0, WINDOW_CAP);
        sdk('setWindowPosition(' + w1 + ')', tr('nWin', w0, w1));
        if (!saved.window) saved.window = { before: w0, set: w1 };
        setCar('window', w1, true);
        return [true, 'ok'];
      }
      if (a.type === 'AddRestStop') {
        if (!(await sleep(STEP_MS))) return null;
        sdk('addWaypoint(' + sim.stopDisplayName(a.stop.name) + ')', a.stop.routeLabel + ' ' + a.stop.distanceKm.toFixed(1) + 'km');
        saved.waypoint = true;
        tracker.start(a.stop);
        return [true, 'ok'];
      }
      // Revert: 풍량·온도·창문은 지금 값이 우리가 넣은 값 근처일 때만 되돌린다(운전자가 바꿨으면 둔다).
      // 내기순환은 실행 중 운전자 조작이 보였을 때만 그대로 둔다. 경유지는 지우지 않는다
      var s = saved;
      if (s.recirc) {
        if (!(await sleep(STEP_MS))) return null;
        if (s.recircTouched) sdk('', tr('nDriver', tr('wRecirc')));
        else { sdk('setHvacRecirculation(' + s.recirc.before + ')', tr('nRevert'), true); setCar('recirc', s.recirc.before, true); }
      }
      if (s.fan) {
        if (!(await sleep(STEP_MS))) return null;
        if (Math.abs(car.fan - s.fan.set) > tolInt(0, FAN_MAX)) sdk('', tr('nDriver', tr('wFan')));
        else { sdk('setHvacFanSpeed(' + s.fan.before + ')', tr('nRevert'), true); setCar('fan', s.fan.before, true); }
      }
      if (s.temp) {
        if (!(await sleep(STEP_MS))) return null;
        if (Math.abs(car.temp - s.temp.set) > tolTemp(TEMP_MIN, TEMP_MAX)) sdk('', tr('nDriver', tr('wTemp')));
        else { sdk('setHvacTemperature(' + s.temp.before.toFixed(1) + ')', tr('nRevert'), true); setCar('temp', s.temp.before, true); }
      }
      if (s.window) {
        if (!(await sleep(STEP_MS))) return null;
        if (Math.abs(car.window - s.window.set) > tolInt(0, 100)) sdk('', tr('nDriver', tr('wWin')));
        else { sdk('setWindowPosition(' + s.window.before + ')', tr('nRevert'), true); setCar('window', s.window.before, true); }
      }
      if (s.waypoint) sdk('', tr('nKeepWp'));
      saved = {};
      return [true, 'ok'];
    }

    async function run(actions, batch) {
      for (var i = 0; i < actions.length; i++) {
        var a = actions[i];
        // 원복 요청이 오면 아직 실행 안 한 남은 액션은 건너뛴다
        if (a.type !== 'Revert' && batch.cancelled) continue;
        var r = await perform(a);
        if (!r) return;
        (function (type, ok, detail) {
          emit('ActionResult ' + type + (ok ? ' ok' : ' 실패'), function (t) { return sim.actionResult(t, type, ok, detail); });
        })(a.type, r[0], r[1]);
      }
      if (current === batch) current = null;
    }

    return {
      execute: function (actions) {
        var isRevert = actions.length === 1 && actions[0].type === 'Revert';
        if (isRevert && current && !current.cancelled) { current.cancelled = true; sdk('', tr('nCancel')); }
        var batch = { cancelled: false };
        if (!isRevert) current = batch;
        chain = chain.then(function () { return run(actions, batch); }).catch(function (err) { logEv('err', clock.now(), String(err)); });
      },
      /** 우리가 외기로 바꿔 둔 동안 다른 값이 보이면 운전자가 바꾼 것으로 본다 */
      observeRecirc: function (on) { if (saved.recirc && on !== saved.recirc.set) saved.recircTouched = true; },
      /** 원복 없이 끝난 실행: 그 실행이 저장해 둔 실행 전 값을 버린다(앱 VehicleActuator.discardSnapshots) */
      keepChanges: function (actions) {
        actions.forEach(function (a) {
          if (a.type === 'Ventilate') { saved.recirc = null; saved.fan = null; saved.recircTouched = false; }
          else if (a.type === 'CoolDown') saved.temp = null;
          else if (a.type === 'OpenWindow') saved.window = null;
        });
      },
      forgetWaypoint: function () { saved.waypoint = false; },
      reset: function () { saved = {}; current = null; chain = Promise.resolve(); }
    };
  })();

  // ---------------------------------------------------------------- 졸음쉼터: 전방 후보와 경유지까지 거리

  var tracker = {
    lastSent: undefined,
    /** 전방 3~80km 의 가장 가까운 쉼터 */
    candidate: function () {
      for (var i = 0; i < STOPS.length; i++) {
        var d = STOPS[i].km - car.posKm;
        if (d >= 3 && d <= 80) return { name: STOPS[i].name, routeLabel: STOPS[i].routeLabel, distanceKm: d, lat: STOPS[i].lat, lng: STOPS[i].lng, km: STOPS[i].km };
      }
      return null;
    },
    sendCandidate: function (force) {
      var c = this.candidate();
      var key = c ? c.name + ':' + Math.floor(c.distanceKm) : 'none';
      ui.nextStop = c;
      if (!force && key === this.lastSent) return;
      this.lastSent = key;
      sig.candidate(c);
    },
    start: function (stop) {
      var found = STOPS.filter(function (s) { return s.name === stop.name; })[0];
      car.tracking = { name: stop.name, routeLabel: stop.routeLabel, km: found ? found.km : car.posKm + stop.distanceKm, distanceKm: stop.distanceKm };
      car.within = false;
      ui.waypoint = car.tracking;
      ui.proximityKm = car.tracking.km - car.posKm;
    },
    stop: function () { car.tracking = null; car.within = false; ui.proximityKm = null; },
    /** 경유지로 넣은 쉼터를 향해 달린다. 300m 안에 들면 한 번 알리고 멈춘다 */
    advance: function (dtMs) {
      var tr0 = car.tracking;
      if (!tr0 || car.within || car.gear !== 'DRIVE' || !driving() || ui.rest) return;
      car.posKm += SPEED_KPH * (dtMs * (sim.demoFast ? 60 : 1)) / 3600000;
      if (tr0.km - car.posKm <= 0.3) this.arrive();
      else { ui.proximityKm = tr0.km - car.posKm; this.sendCandidate(false); }
    },
    arrive: function () {
      var tr0 = car.tracking;
      if (!tr0 || car.within) return;
      car.posKm = tr0.km - 0.3; car.within = true; ui.proximityKm = 0.3;
      sig.proximity(true, false);
      this.sendCandidate(false);
    },
    pass: function () {
      var tr0 = car.tracking;
      if (!tr0) return;
      car.posKm = tr0.km + 1.5;
      sig.proximity(false, true);
      sdk('removeWaypoint(FIRST)', tr('nPassed'));
      actuator.forgetWaypoint();
      this.stop();
      ui.waypoint = null;
      this.sendCandidate(true);
    }
  };

  // ---------------------------------------------------------------- 콘솔 조작 (사용자와 자동 시연이 같은 함수를 쓴다)

  var user = {
    gear: function (g) { if (car.gear === g) return; car.gear = g; sig.gear(); sig.location(); },
    seat: function (i, on) { if (i === 0 || car.seats[i] === on) return; car.seats[i] = on; sig.occupants(); if (i === 1) sig.passenger(); },
    autoClimate: function (on) { if (car.autoClimate === on) return; car.autoClimate = on; sig.autoClimate(); },
    /** 내비 목적지까지 남은 분. null 이면 목적지 없음 */
    eta: function (min) { if (car.eta === min) return; car.eta = min; sig.eta(); },
    /** 맡기기를 켜고 끈다. 연속 동의 횟수는 그대로 두고, 켤 때는 취소 횟수를 0 부터 */
    trust: function (on) {
      var st = trust();
      if (st.delegated === on) return;
      // 끄는 순간 예고가 떠 있으면 엔진이 닫는다(Done). 그 이벤트도 같은 길로 처리한다
      emit('setTrust 맡김=' + on, function () { return sim.setTrust(on, st.consecutiveYes, on ? 0 : st.cancelCount); });
      render();
    },
    recirc: function (on) { if (car.recirc === on) return; setCar('recirc', on, false); },
    fan: function (v) { v = clamp(Math.round(v), 0, FAN_MAX); if (car.fan === v) return; setCar('fan', v, false); },
    temp: function (v) { car.temp = clamp(Math.round(v * 2) / 2, TEMP_MIN, TEMP_MAX); render(); },
    window: function (v) { v = clamp(Math.round(v), 0, 100); if (car.window === v) return; setCar('window', v, false); },
    wiper: function (on) { car.wiper = on; sig.wiper(); },
    lowBeam: function (on) { car.lowBeam = on; sig.lowBeam(); },
    turn: function (s) { if (car.turn === s) return; car.turn = s; sig.turn(); },
    attention: function (on) { car.attention = on; sig.telltale('DRIVER_ATTENTION', on, false); },
    lka: function (on) { car.lka = on; sig.telltale('LKA', on, on); },
    tripAdd: function (m) { car.tripOdo += m; sig.tripTime(); },
    /** 엔진이 세는 꼴 그대로: 직진으로 무조작(steerQuietSec 초 넘게 0도) 뒤 한 번에 12도, 곧바로 되돌림. 세 번 */
    steerJerks: async function () {
      if (car.steerStep || !OPT.steer) return;
      var quietMs = sim.steerQuietSec * 1000 + 400;
      for (var i = 1; i <= 3; i++) {
        car.steerStep = i; render();
        var until = clock.now() + quietMs;
        while (clock.now() < until) { sig.steering(0); if (!(await sleep(200))) return; }
        sig.steering(12);
        if (!(await sleep(400))) return;
      }
      sig.steering(0);
      car.steerStep = 0; render();
    }
  };

  // ---------------------------------------------------------------- 화면 그리기

  function lerpColor(a, b, f) {
    var pa = [parseInt(a.slice(1, 3), 16), parseInt(a.slice(3, 5), 16), parseInt(a.slice(5, 7), 16)];
    var pb = [parseInt(b.slice(1, 3), 16), parseInt(b.slice(3, 5), 16), parseInt(b.slice(5, 7), 16)];
    return 'rgb(' + pa.map(function (v, i) { return Math.round(v + (pb[i] - v) * f); }).join(',') + ')';
  }
  /** 추정 ppm 색: 1,000 미만 초록, 1,000~한도는 흰색에서 노랑으로, 한도 이상 주황 (앱 ppmColor) */
  function ppmColor(ppm, limit) {
    if (ppm < 1000) return '#3DBF7A';
    if (ppm < limit) return lerpColor('#ECEFF3', '#FFD54A', clamp((ppm - 1000) / Math.max(1, limit - 1000), 0, 1));
    return '#FF8A3D';
  }
  var GAUGE_MAX = 3000;
  /** 게이지에 그려 둔 한도 값과, 한도선을 옮기는 함수(buildGauge 가 채운다) */
  var gaugeLimit = null, placeLimit = function () {};
  function gaugeFraction(ppm) { return clamp((ppm - sim.outdoorPpm) / Math.max(1, GAUGE_MAX - sim.outdoorPpm), 0, 1); }
  var PERSON = '<circle cx="11" cy="7" r="5.9"/><path d="M1 30A10 15.7 0 0 1 21 30Z"/>';

  /** 반원 게이지(바깥 공기 ppm ~ 3,000, 한도 표시선). 앱 AirGauge 와 같은 치수 */
  function buildGauge() {
    // 작은 헤드유닛에서는 눈금 글자를 키운다
    var cx = 351, cy = 324, r = 263.5, sw = 34, fs = huSmall ? 30 : 18;
    var pt = function (f, rad) { var a = Math.PI * (1 + f); return [(cx + rad * Math.cos(a)).toFixed(1), (cy + rad * Math.sin(a)).toFixed(1)]; };
    var f1 = gaugeFraction(1000);
    var a1 = pt(f1, r + sw / 2 + 2), b1 = pt(f1, r + sw / 2 + 10), l1 = pt(f1, r + sw / 2 + 36);
    var arc = 'M' + (cx - r) + ' ' + cy + 'A' + r + ' ' + r + ' 0 0 1 ' + (cx + r) + ' ' + cy;
    $('gauge').innerHTML =
      '<path d="' + arc + '" fill="none" stroke="#1F2630" stroke-width="' + sw + '"/>' +
      '<path id="gFill" d="' + arc + '" fill="none" stroke="#8B96A5" stroke-width="' + sw + '" pathLength="1000" stroke-dasharray="0 1000"/>' +
      '<g fill="#8B96A5" font-size="' + fs + '" text-anchor="middle" dominant-baseline="middle">' +
      '<text x="' + (cx - r) + '" y="' + (cy + 17) + '">' + fmtInt(sim.outdoorPpm) + '</text>' +
      '<text x="' + (cx + r) + '" y="' + (cy + 17) + '">' + fmtInt(GAUGE_MAX) + '</text>' +
      '<text x="' + l1[0] + '" y="' + l1[1] + '">1,000</text></g>' +
      '<line x1="' + a1[0] + '" y1="' + a1[1] + '" x2="' + b1[0] + '" y2="' + b1[1] + '" stroke="#8B96A5" stroke-width="2"/>' +
      '<line id="gLimitLine" stroke="#FF8A3D" stroke-width="5"/>' +
      '<text id="gLimit" fill="#FF8A3D" font-size="' + fs + '" font-weight="600" text-anchor="middle" dominant-baseline="middle"></text>' +
      '<text id="gNum" x="' + cx + '" y="' + (cy - 56) + '" font-size="95" font-weight="700" text-anchor="middle" fill="#8B96A5">--</text>' +
      '<text id="gUnit" x="' + cx + '" y="' + (cy - 12) + '" font-size="' + (huSmall ? 32 : 24) + '" text-anchor="middle" fill="#8B96A5"></text>';
    gaugeLimit = null;
    placeLimit = function (limit) {
      if (gaugeLimit === limit) return;
      gaugeLimit = limit;
      var fl = gaugeFraction(limit);
      var a2 = pt(fl, r - sw / 2 - 6), b2 = pt(fl, r + sw / 2 + 8), l2 = pt(fl, r + sw / 2 + 26);
      var ln = $('gLimitLine'), tx = $('gLimit');
      ln.setAttribute('x1', a2[0]); ln.setAttribute('y1', a2[1]); ln.setAttribute('x2', b2[0]); ln.setAttribute('y2', b2[1]);
      tx.setAttribute('x', String(parseFloat(l2[0]) + 30)); tx.setAttribute('y', l2[1]);
    };
    $('occ').innerHTML = [0, 1, 2, 3].map(function () { return '<svg viewBox="0 0 22 30" aria-hidden="true">' + PERSON + '</svg>'; }).join('') + '<span class="n" id="occN"></span>';
  }

  /** 외기(창문 닫힘)인데 추정치가 한도 위에서 내려오지 않는 상태. 앱이 막 바꿔서 아직 내려가기 전(실행 중)은 빼고 본다 */
  function weakFreshAir(limit) { var a = ui.air; return !!a && a.ventilating && car.window < 5 && a.ppm >= limit && !a.falling && !ui.running; }
  /**
   * 게이지 아래 한 줄(앱 airStatusLine 과 같은 순서). 위에서부터 먼저 맞는 것:
   * 쉼 모드 → 추정 전 → 창문 열림 → 외기인데 한도 위 → 외기 → 묻지 않는 때(조용 모드, 차 자동 공조, 도착 임박) → 거절 뒤 쿨다운 → 한도까지 남은 시간
   */
  function airLine(limit) {
    var a = ui.air, n = occupants();
    if (ui.rest) return tr('lineRest');
    if (!a) return tr('lineWarm', n);
    if (a.ventilating && car.window >= 5) return a.falling ? tr('lineWinFall') : tr('lineWin');
    if (weakFreshAir(limit)) return tr('lineWeak');
    if (a.ventilating) return a.falling ? tr('lineVentFall') : tr('lineVent');
    // 환기 제안을 내지 않는 때: 조용 모드, 차가 내외기를 제어하는 중, 도착 임박.
    // 조용 모드여도 한도 이상이면 그 사실을 먼저 알린다(그 아래에 "바깥 공기로" 버튼이 뜬다)
    if (sim.overLimitWhileQuiet) return tr('lineQuietOver', a.occupants);
    if (sim.quietMode) return tr('lineQuiet');
    if (sim.carAutoActive) return tr('lineAuto');
    if (sim.arrivingSoon) return tr('lineArriving');
    var unit = sim.demoFast ? 1000 : 60000;
    var left = ui.staleCooldownUntil == null ? 0 : Math.floor((ui.staleCooldownUntil - clock.now() + unit - 1) / unit);
    if (left > 0) return tr('lineCooldown', a.occupants, left, sim.demoFast);
    if (a.minutesToLimit == null) return tr('lineNoLimit', a.occupants);
    if (a.minutesToLimit <= 0) return tr('lineOver', a.occupants);
    return tr('lineIn', a.occupants, a.minutesToLimit, sim.demoFast);
  }
  /** "바깥 공기로" 버튼을 보여 줄지(앱 showFreshAirButton 과 같다) */
  function showFreshButton() {
    var a = ui.air;
    return ui.tripState === 'DRIVING' && !ui.rest && !ui.running && !ui.proposal && !!a && !a.ventilating && !!sim.overLimitWhileQuiet;
  }
  function kmText(km) { return km < 1 ? Math.floor(km * 1000 / 100) * 100 + 'm' : km.toFixed(0) + 'km'; }
  function gearLabel(g) { return { PARK: 'P', REVERSE: 'R', NEUTRAL: 'N', DRIVE: 'D' }[g] || '?'; }
  function stateLine() {
    var st = ui.tripState === 'DRIVING' ? (ui.rest ? tr('stResting') : tr('stDriving')) : ui.tripState === 'ENDED' ? tr('stEnded') : tr('stIdle');
    return st + ', ' + tr('gear') + ' ' + gearLabel(car.gear);
  }
  function holdReason(r) {
    if (r === 'turn_signal') return tr('holdTurn');
    if (r === 'turn_near') return tr('holdTurnNear');
    if (r === 'cooldown') return tr('holdCooldown');
    if (r === 'speaking') return tr('holdSpeaking');
    if (r === 'arriving') return tr('holdArriving');
    if (r === 'car-auto') return tr('holdCarAuto');
    if (r === 'lights-just-on') return tr('holdLights');
    return tr('holdWarn') + (r.indexOf(':') > 0 ? ' (' + r.split(':')[1] + ')' : '');
  }
  /** 헤드유닛 논리 폭(1280 또는 960)과 작은 화면 여부. 입력 연결의 fit 이 채운다 */
  var huLW = 1280, huSmall = false;
  function narrow() { return window.matchMedia('(max-width: 699px)').matches; }

  function render() {
    if (!sim) return;
    // 한도는 엔진이 지금 쓰는 값(야간 장거리면 기본값보다 낮다)
    var en = lang === 'en', limit = sim.effectiveLimitPpm, isDriving = driving();
    if (ui.staleCooldownUntil != null && ui.staleCooldownUntil <= clock.now()) ui.staleCooldownUntil = null;

    // ---- 차량 화면 ----
    show($('home'), isDriving); show($('idle'), !isDriving);
    if (isDriving) {
      setText($('hState'), stateLine());
      setText($('hSaid'), ui.lastSaid && !ui.proposal ? ui.lastSaid : '');
      var a = ui.air, color = a ? ppmColor(a.ppm, limit) : '#8B96A5';
      var fill = $('gFill');
      fill.style.strokeDasharray = (a ? gaugeFraction(a.ppm) * 1000 : 0).toFixed(1) + ' 1000';
      fill.style.stroke = color;
      var num = $('gNum');
      setText(num, a ? fmtInt(a.ppm) : '--'); num.style.fill = color;
      setText($('gUnit'), tr('ppmEst'));
      placeLimit(limit);
      setText($('gLimit'), tr('limit') + ' ' + fmtInt(limit));
      var occ = a ? a.occupants : occupants();
      Array.prototype.forEach.call($('occ').querySelectorAll('svg'), function (el, i) { el.classList.toggle('in', i < occ); });
      setText($('occN'), tr('people', occ));
      var line = $('gLine'), weak = weakFreshAir(limit) && !ui.rest;
      setText(line, airLine(limit));
      // 묻지 않는 중에도 직접 바꿀 수 있는 버튼. 조용 모드에서 한도를 넘었고 다른 개입이 떠 있지 않을 때만
      var freshOn = showFreshButton();
      line.classList.toggle('vent', !!(a && a.ventilating) && !weak); line.classList.toggle('weak', weak || freshOn); line.classList.toggle('long', freshOn);
      show($('btnFresh'), freshOn); setText($('btnFresh'), tr('freshBtn'));

      var tm = tripMinutes();
      setText($('tripV'), tr('mins', tm));
      var pills = '';
      if (car.lowBeam && tm >= sim.longTripMinutes) pills += '<span class="pill warn">' + esc(tr('pillNight')) + '</span>';
      if (car.wiper) pills += '<span class="pill muted">' + esc(tr('pillRain')) + '</span>';
      if (ui.rest) pills += '<span class="pill accent">' + esc(tr('pillRest')) + '</span>';
      if ($('tripPills').innerHTML !== pills) $('tripPills').innerHTML = pills;

      var wp = ui.waypoint, stop = wp || ui.nextStop;
      $('stopCard').classList.toggle('wp', !!wp);
      setText($('stopTitle'), wp ? tr('stopWp') : tr('stopSim'));
      show($('stopRow'), !!stop); show($('stopNone'), !stop);
      if (stop) {
        setText($('stopRoute'), tr('route', stop.routeLabel));
        setText($('stopName'), tr('stopName', stop.name));
        setText($('stopKm'), kmText(wp ? (ui.proximityKm != null ? ui.proximityKm : stop.distanceKm) : stop.distanceKm));
      }
      setText($('today1'), tr('today1', ui.vents, fresh.units(clock.now()), sim.demoFast));
      var t2 = $('today2');
      setText(t2, ui.peak > 0 ? tr('todayPeak', fmtInt(ui.peak)) : tr('todayNoPeak'));
      t2.style.color = ui.peak > 0 ? ppmColor(ui.peak, limit) : '#8B96A5';
    } else {
      setText($('iState'), stateLine());
      setText($('iTrip'), tr('mins', ui.tripState === 'IDLE' ? 0 : tripMinutes()));
      setText($('iTripS'), ui.tripState === 'ENDED' ? tr('idleTripEnded') : tr('idleTripS'));
      setText($('iAirT'), tr('idleAirT', occupants()));
      var ip = $('iPpm');
      setText(ip, ui.air ? fmtInt(ui.air.ppm) : '--'); ip.style.color = ui.air ? ppmColor(ui.air.ppm, limit) : '#8B96A5';
      setText($('iAirS'), tr('idleAirS', fmtInt(limit)));
      var c = tracker.candidate();
      setText($('iStop'), c ? tr('stopName', c.name) + ' ' + kmText(c.distanceKm) : tr('idleStopNone'));
      setText($('iStopS'), c ? tr('route', c.routeLabel) + '. ' + tr('idleStopS') : tr('idleStopS'));
      setText($('idleSub'), tr('idleSub', occupants()));
      setText($('iSaid'), ui.lastSaid ? tr('lastSaid', ui.lastSaid) : '');
    }

    // 실행 중 바, 증거 띠
    var run = ui.running;
    show($('runbar'), !!run && isDriving);
    if (run) {
      var label = run.pending ? tr(run.pending) : run.label;
      var bar = esc(label + (run.remainingSec > 0 ? T.ko.runTail(mmss(run.remainingSec)) : ''));
      if (en) { var g = glossRunning(run.pending ? T.ko[run.pending] : run.label); if (g) bar += '<span class="en">' + esc(g + (run.remainingSec > 0 ? ', back to your settings in ' + mmss(run.remainingSec) : '')) + '</span>'; }
      if ($('runText').innerHTML !== bar) $('runText').innerHTML = bar;
      // 예고로 시작한 환기와 직접 요청한 환기는 유지 시간 내내 [취소] 를 둔다
      show($('runCancel'), !!sim.cancellableRun); setText($('runCancel'), tr('runCancel'));
    }
    show($('evidence'), !!ui.evidence && isDriving);
    if (ui.evidence) setText($('evidence'), 'SDK  ' + ui.evidence + (ui.waypoint && ui.proximityKm != null ? tr('evWaypoint', ui.proximityKm.toFixed(1)) : ''));

    // 제안 오버레이
    var p = ui.proposal;
    show($('proposal'), !!p);
    if (p) {
      setText($('pText'), p.text);
      var pg = en ? gloss(p.text) : '';
      show($('pEn'), !!pg); setText($('pEn'), pg);
      // 예고(맡겨 둔 환기)는 [취소] 하나와 줄어드는 막대. 조수석에 사람이 있으면 답 버튼을 화면 오른쪽에 세로로 둔다
      var box = $('proposal');
      box.classList.toggle('announce', p.announce); box.classList.toggle('pass', p.passenger && !p.announce);
      show($('pYes'), !p.announce);
      setText($('pYes'), tr('yes')); setText($('pNoT'), p.announce ? tr('cancel') : tr('no'));
      $('pYes').style.fontSize = en ? '34px' : ''; $('pNo').style.fontSize = en ? '26px' : '';
      var elapsed = p.countdownFrom == null ? null : clock.now() - p.countdownFrom;
      var remaining = elapsed == null ? null : clamp(p.timeoutSec - Math.floor(elapsed / 1000), 0, p.timeoutSec);
      setText($('pInfo'), remaining == null ? tr('speaking') : p.announce ? tr('announceIn', remaining) : tr('countdown', remaining));
      show($('pBar'), p.announce);
      if (p.announce) $('pBar').style.width = (elapsed == null ? 100 : clamp(100 - elapsed / (p.timeoutSec * 10), 0, 100)).toFixed(1) + '%';
    }

    // 쉼 카드, 쉼 끝 한마디
    var rest = ui.rest;
    show($('rest'), !!rest);
    if (rest) {
      var title = T.ko.restLine(T.ko.stopName(rest.stopName), rest.targetMinutes, rest.demo, false);
      setText($('rTitle'), title);
      var rg = en ? gloss(title) : '';
      show($('rEn'), !!rg); setText($('rEn'), rg);
      setText($('rCount'), mmss(rest.remainingSec));
      // 쉼 진입 때의 바깥 공기 질문은 카드 안 아래쪽에서 묻는다
      var ask = rest.ask;
      $('rest').classList.toggle('asking', !!ask);
      show($('rAsk'), !!ask);
      if (ask) {
        setText($('rQ'), ask.text);
        var qg = en ? gloss(ask.text) : '';
        show($('rQEn'), !!qg); setText($('rQEn'), qg);
        setText($('rYes'), tr('yes')); setText($('rNo'), tr('no'));
        var rLeft = ask.countdownFrom == null ? null : clamp(ask.timeoutSec - Math.floor((clock.now() - ask.countdownFrom) / 1000), 0, ask.timeoutSec);
        setText($('rInfo'), rLeft == null ? tr('speaking') : tr('countdown', rLeft));
      }
    }
    show($('toastWrap'), !!ui.restToast);
    if (ui.restToast) {
      setText($('toastT'), ui.restToast);
      var tg = en ? gloss(ui.restToast) : '';
      show($('toastEn'), !!tg); setText($('toastEn'), tg);
    }

    // 도착 카드
    var card = ui.arrival;
    show($('arrival'), !!card);
    if (card) {
      var aDemo = ui.arrivalDemo, head = demoUnits(card.headline, aDemo);
      setText($('aHead'), head);
      var hg = en ? gloss(head) : '';
      show($('aHeadEn'), !!hg); setText($('aHeadEn'), hg);
      // 60배 데모에서는 엔진의 바깥 공기 분이 구간마다 1 로 뭉개진다. 앱처럼 같은 기준으로 센 초를 쓴다
      var ms = [[tr('mDrive'), tr('mins', card.tripMinutes)], [tr('mVent'), tr('times', card.ventilations)], [tr('mFresh'), tr('span', aDemo ? ui.arrivalFresh : card.freshAirMinutes, aDemo)],
        [tr('mDrowsy'), tr('times', card.drowsySignals)], [tr('mRest'), tr('restVal', card.restStopsVisited, card.restMinutes, aDemo)]];
      if (card.restStopsPassed > 0) ms.push([tr('mPassed'), tr('times', card.restStopsPassed)]);
      ms.push([tr('mDeclined'), tr('times', card.declined)]);
      var mh = ms.map(function (m) { return '<div><div class="k">' + esc(m[0]) + '</div><div class="v">' + esc(m[1]) + '</div></div>'; }).join('');
      if ($('aMetrics').innerHTML !== mh) $('aMetrics').innerHTML = mh;
      var air = $('aAir');
      setText(air, tr('arrAir', card.peakPpm, card.minutesOverLimit, Math.max(1, ui.maxOcc), aDemo));
      air.style.color = card.peakPpm > 0 ? ppmColor(card.peakPpm, limit) : '#8B96A5';
      // 공기 줄은 위에서 한 번만 보여 준다. "출발 N분 뒤" 머리말은 그대로 두고 그 뒤의 분만 초로 바꾼다
      // 맡기기 질문이 있으면 기록 줄을 줄여 자리를 낸다
      var maxLines = ui.trustAsk ? (huSmall && en ? 0 : 2) : (huSmall ? 3 : 4);
      var lh = card.lines.filter(function (l) { return l.indexOf('최고 추정') < 0; }).slice(0, maxLines).map(function (l) {
        var hm = /^(출발 직후|출발 \d+분 뒤) /.exec(l), hd = hm ? hm[0] : '';
        var shown = hd + demoUnits(l.slice(hd.length), aDemo), lg = en ? (glossLine(shown) || gloss(shown)) : '';
        return '<div>' + esc(shown) + (lg ? '<span class="en">' + esc(lg) + '</span>' : '') + '</div>';
      }).join('');
      if ($('aLines').innerHTML !== lh) $('aLines').innerHTML = lh;
      // 맡기기 질문: 엔진이 준 문장과 두 버튼. 답한 뒤에는 결과 한 줄
      var asking = ui.trustAsk === 'ask';
      show($('aTrust'), !!ui.trustAsk);
      $('arrival').classList.toggle('trust', asking);
      if (ui.trustAsk) {
        setText($('aTrustQ'), asking ? card.trustOffer : tr(ui.trustAsk === 'on' ? 'trustOn' : 'trustKept'));
        var tq = en && asking ? gloss(card.trustOffer) : '';
        show($('aTrustEn'), !!tq); setText($('aTrustEn'), tq);
      }
      show($('aTrustYes'), asking); show($('aTrustNo'), asking);
      setText($('aTrustYes'), tr('trustYes')); setText($('aTrustNo'), tr('trustNo'));
    }

    // 화면이 작으면(화면 안 버튼이 40px 보다 낮아지면) 같은 버튼을 화면 아래에 낸다
    var smallScreen = $('screenWrap').clientWidth * 96 / huLW < 40;
    var restAsk = rest && rest.ask, anyAsk = !!p || !!restAsk;
    var needBar = smallScreen && (anyAsk || !!card);
    show($('actbar'), needBar);
    if (needBar) {
      var trustBar = !anyAsk && !!card && ui.trustAsk === 'ask';
      show($('xYes'), (!!p && !p.announce) || (!p && !!restAsk)); show($('xNo'), anyAsk); show($('xOk'), !anyAsk && !!card);
      show($('xTrustYes'), trustBar); show($('xTrustNo'), trustBar);
      setText($('xYes'), tr('yes')); setText($('xNo'), p && p.announce ? tr('cancel') : tr('no'));
      setText($('xTrustYes'), tr('trustYes')); setText($('xTrustNo'), tr('trustNo'));
    }

    // ---- 헤드유닛 아래 한 줄: 말을 미루는 이유, 영어 풀이, 음성 없음 ----
    // 말을 미루던 조건(방향지시등, 경고등 점멸, 직전 안내 뒤 20초)이 풀렸으면 보류 표시를 지운다
    if (ui.deferred) {
      var d = ui.deferred, blocked = d === 'cooldown' ? clock.now() - ui.deferredAt < 21000
        : d === 'arriving' ? sim.arrivingSoon : d === 'car-auto' ? sim.carAutoActive
        : d === 'lights-just-on' ? clock.now() - ui.deferredAt < 3 * (sim.demoFast ? 1000 : 60000)
        : (car.turn !== 'OFF' || car.lka);
      if (!blocked) ui.deferred = null;
    }
    var hold = ui.deferred && isDriving && !ui.proposal && !ui.speaking;
    var sg = hold ? tr('holdText', holdReason(ui.deferred)) : '';
    // 영어 화면: 헤드유닛 맨 위 한국어 문장(제안 오버레이가 없을 때)의 풀이
    if (!hold && en && ui.lastSaid && !ui.proposal && !ui.rest && !ui.arrival) sg = gloss(ui.lastSaid);
    if (ui.voiceMissing) sg = (sg ? sg + '  ' : '') + tr('subNoVoice');
    setText($('subtitle'), sg);

    renderControls();
    renderPlan();
    renderLogs();
  }

  function pressed(el, on) { var v = on ? 'true' : 'false'; if (el.getAttribute('aria-pressed') !== v) el.setAttribute('aria-pressed', v); }
  function attr(el, k, v) { v = String(v); if (el.getAttribute(k) !== v) el.setAttribute(k, v); }
  function disable(el, on) { if (el.disabled !== on) el.disabled = on; }

  // ---------------------------------------------------------------- 평면도: 위에서 내려다본 세단 5석, 앞이 오른쪽
  // 좌표는 가로 배치 기준(1000 x 470). 좁은 화면에서는 같은 좌표를 돌려 앞이 위로 오게 한다: (x, y) → (y, 1000 - x)
  // 그림은 x = X0 부터 쓴다(트렁크를 짧게 그려 가로세로 비를 880:470 으로 맞췄다)
  var PW = 1000, PH = 470, X0 = 120, PL = PW - X0, vert = false;
  var SEATS = [
    { x: 560, y: 136, w: 102, h: 110 },  // 0 운전석(차의 왼쪽 = 그림 위쪽)
    { x: 560, y: 294, w: 102, h: 110 },  // 1 동승석
    { x: 214, y: 136, w: 90, h: 80 },    // 2 뒷좌석 왼쪽
    { x: 214, y: 230, w: 90, h: 80 },    // 3 뒷좌석 가운데
    { x: 214, y: 324, w: 90, h: 80 }     // 4 뒷좌석 오른쪽
  ];
  // 숫자 자리: 가로 배치에서는 바람 줄기가 위아래로 지나가게 낮게, 세로 배치에서는 폭이 모자라 실내 높이를 다 쓴다
  var BOX = { readout: [304, 198, 256, 144], readoutV: [304, 140, 256, 260], hvac: [756, 168, 206, 204], win: [470, 8, 300, 78] };
  var WIN_X0 = 476, WIN_X1 = 730, WIN_Y = 112, WHEEL = [712, 191];
  var CABIN = 'M 212 125 L 735 125 Q 772 270 735 415 L 212 415 Q 178 270 212 125 Z';
  /** 바깥 공기 줄기: 그릴에서 들어와 공조 패널 위아래로 갈라져 실내로 퍼진다 [그릴 쪽 y, 대시보드 y, 뒷좌석 y] */
  var LANES = [[140, 160, 168], [400, 380, 372], [154, 178, 188], [386, 362, 352], [128, 146, 150]];

  function place(el, b) {
    var x = b[0], y = b[1], w = b[2], h = b[3], st = el.style;
    if (!vert) { st.left = (x - X0) / PL * 100 + '%'; st.top = y / PH * 100 + '%'; st.width = w / PL * 100 + '%'; st.height = h / PH * 100 + '%'; }
    else { st.left = y / PH * 100 + '%'; st.top = (PW - x - w) / PL * 100 + '%'; st.width = h / PH * 100 + '%'; st.height = w / PL * 100 + '%'; }
  }
  function seatPath(b) {
    // 방석(둥근 사각형)과 뒤쪽 등받이
    return '<rect x="' + (b.x + 16) + '" y="' + (b.y + 6) + '" width="' + (b.w - 16) + '" height="' + (b.h - 12) + '" rx="12"/>' +
      '<rect x="' + b.x + '" y="' + b.y + '" width="24" height="' + b.h + '" rx="9"/>';
  }
  /** 위에서 본 사람: 등받이 앞의 어깨(좌우로 긴 둥근 막대)와 그 위의 머리 */
  function personPath(b) {
    // 어깨는 흐린 잉크, 머리는 잉크. 같은 색이면 한 덩어리로 뭉쳐 보인다
    var cx = b.x + 44, cy = b.y + b.h / 2, half = Math.min(b.h * 0.42, 40);
    return '<rect x="' + (cx - 15) + '" y="' + (cy - half).toFixed(1) + '" width="30" height="' + (half * 2).toFixed(1) + '" rx="15" fill="#6B7782"/>' +
      '<ellipse cx="' + (cx + 5) + '" cy="' + cy + '" rx="15" ry="13"/>';
  }
  function buildPlan() {
    vert = narrow();
    var plan = $('plan'), svg = $('planSvg');
    plan.classList.toggle('v', vert);
    attr(svg, 'viewBox', vert ? '0 0 ' + PH + ' ' + PL : X0 + ' 0 ' + PL + ' ' + PH);
    var g = '';
    // 차체
    g += '<path d="M 222 100 L 835 100 C 935 100 982 150 982 270 C 982 390 935 440 835 440 L 222 440 C 162 440 138 400 138 270 C 138 140 162 100 222 100 Z" fill="none" stroke="#1E2933" stroke-width="2.5"/>';
    // 사이드미러
    g += '<path d="M 704 100 L 716 82 L 742 82 L 738 100" fill="none" stroke="#1E2933" stroke-width="2"/>';
    g += '<path d="M 704 440 L 716 458 L 742 458 L 738 440" fill="none" stroke="#1E2933" stroke-width="2"/>';
    // 앞유리, 뒷유리, 실내 바닥 선
    g += '<path d="M 748 112 Q 802 270 748 428" fill="none" stroke="#1E2933" stroke-width="2"/>';
    g += '<path d="M 198 112 Q 154 270 198 428" fill="none" stroke="#1E2933" stroke-width="2"/>';
    g += '<path d="' + CABIN + '" fill="none" stroke="#6B7782" stroke-width="1.5"/>';
    // 문 틈, 옆 유리(뒷문, 조수석 앞문)
    g += '<path d="M 469 100 V 124 M 469 416 V 440" stroke="#1E2933" stroke-width="2"/>';
    g += '<path d="M 225 112 H 462 M 225 428 H 462 M 476 428 H 730" stroke="#6B7782" stroke-width="4" stroke-linecap="round"/>';
    // 운전석 창문: 열린 만큼 유리 선이 앞쪽부터 비어 간다
    g += '<line id="pGlass" x1="' + WIN_X0 + '" y1="' + WIN_Y + '" x2="' + WIN_X1 + '" y2="' + WIN_Y + '" stroke="#1E2933" stroke-width="4" stroke-linecap="round"/>';
    g += '<g id="pGap" stroke="#1E2933" stroke-width="1.5"></g>';
    // 그릴
    g += '<path d="M 974 228 V 312" stroke="#1E2933" stroke-width="5" stroke-linecap="round"/>';
    // 좌석
    SEATS.forEach(function (b, i) { g += '<g class="pseat" id="pSeat' + i + '" fill="#E3E6E9" stroke="#1E2933" stroke-width="2">' + seatPath(b) + '</g>'; });
    // 사람
    SEATS.forEach(function (b, i) { g += '<g class="pperson" id="pPerson' + i + '" fill="#1E2933">' + personPath(b) + '</g>'; });
    // 실내 공기층(추정 ppm 에 따라 호박색 안개). 사람 위에 덮여 탁해질수록 사람도 흐려 보인다
    g += '<path class="fog" id="pFog" d="' + CABIN + '" fill="#C79A3E" fill-opacity="0"/>';
    // 핸들
    g += '<g id="pWheel" fill="none" stroke="#1E2933"><circle cx="' + WHEEL[0] + '" cy="' + WHEEL[1] + '" r="27" stroke-width="5"/>' +
      '<path d="M ' + (WHEEL[0] - 27) + ' ' + WHEEL[1] + ' H ' + (WHEEL[0] + 27) + ' M ' + WHEEL[0] + ' ' + WHEEL[1] + ' V ' + (WHEEL[1] + 27) + '" stroke-width="4"/>' +
      '<circle cx="' + WHEEL[0] + '" cy="' + WHEEL[1] + '" r="7" fill="#1E2933" stroke="none"/></g>';
    // 내기순환: 실내 안에서 도는 작은 화살표
    g += '<g id="pRecirc" fill="none" stroke="#1E2933" stroke-width="2.5" stroke-linecap="round"><path d="M 625 270 A 14 14 0 1 1 611 256"/><path d="M 605 250 L 612 256 L 605 262" stroke-linejoin="round"/></g>';
    // 바깥 공기 줄기
    g += '<g class="wind" id="pWind" fill="none" stroke="#3D78AE" stroke-width="2.5" stroke-linecap="round"></g>';
    g += '<g class="wind" id="pWinWind" fill="none" stroke="#3D78AE" stroke-width="2.5" stroke-linecap="round"></g>';
    svg.innerHTML = '<g' + (vert ? ' transform="matrix(0,-1,1,0,0,' + PW + ')"' : '') + '>' + g + '</g>';
    Array.prototype.forEach.call(document.querySelectorAll('.seat'), function (el) { place(el, (function (b) { return [b.x, b.y, b.w, b.h]; })(SEATS[+el.dataset.seat])); });
    place($('readout'), vert ? BOX.readoutV : BOX.readout); place($('hvac'), BOX.hvac); place($('winCtl'), BOX.win);
    planKey = {};
  }
  var planKey = {};

  /** 추정 ppm → 안개 불투명도. 430 거의 투명, 1,000 옅게, 2,000 짙게, 3,000 에서 0.55 */
  var FOG = [[430, 0], [650, 0.04], [1000, 0.17], [1500, 0.34], [2000, 0.5], [2500, 0.54], [3000, 0.55]];
  function fogOpacity(ppm) {
    if (ppm <= FOG[0][0]) return 0;
    for (var i = 1; i < FOG.length; i++) if (ppm <= FOG[i][0]) { var a = FOG[i - 1], b = FOG[i]; return a[1] + (b[1] - a[1]) * (ppm - a[0]) / (b[0] - a[0]); }
    return 0.55;
  }
  function fogColor(ppm, limit) { return ppm < limit ? '#C79A3E' : lerpColor('#C79A3E', '#B0552F', clamp((ppm - limit) / 600, 0, 1)); }

  function renderPlan() {
    if (!$('pFog')) return;
    var limit = sim.effectiveLimitPpm, a = ui.air, ppm = a ? a.ppm : null, isDriving = driving();
    // 안개
    var fo = ppm == null ? 0 : fogOpacity(ppm), fc = ppm == null ? '#C79A3E' : fogColor(ppm, limit);
    var fog = $('pFog');
    attr(fog, 'fill-opacity', fo.toFixed(3)); attr(fog, 'fill', fc);
    // 사람과 좌석
    for (var i = 0; i < 5; i++) {
      var on = !!car.seats[i];
      $('pPerson' + i).style.display = on ? '' : 'none';
      attr($('pSeat' + i), 'stroke', on ? '#1E2933' : '#6B7782');
    }
    // 내기순환 화살표, 바깥 공기 줄기(풍량에 따라 수와 속도)
    $('pRecirc').style.display = car.recirc ? '' : 'none';
    var n = !car.recirc && isDriving && !ui.rest ? clamp(Math.ceil(car.fan / 2) + 1, 1, 5) : 0;
    if (car.fan === 0) n = 0;
    var wk = n + ':' + car.fan;
    if (planKey.wind !== wk) {
      planKey.wind = wk;
      var w = '';
      for (var k = 0; k < n; k++) {
        var L = LANES[k];
        w += '<path d="M 990 ' + L[0] + ' C 900 ' + L[0] + ' 820 ' + L[0] + ' 760 ' + L[1] + ' S 420 ' + L[2] + ' 250 ' + L[2] + '" style="animation-delay:-' + (k * 0.37).toFixed(2) + 's"/>';
      }
      var pw = $('pWind');
      pw.innerHTML = w;
      pw.style.setProperty('--wind-s', (2.6 - car.fan * 0.2).toFixed(2) + 's');
    }
    // 창문: 열린 만큼 유리 선을 줄이고, 열려 있으면 그 틈으로 바깥 공기
    var open = (WIN_X1 - WIN_X0) * clamp(car.window, 0, 100) / 100;
    attr($('pGlass'), 'x2', (WIN_X1 - open).toFixed(1));
    var gk = Math.round(open) + ':' + (isDriving && !ui.rest);
    if (planKey.gap !== gk) {
      planKey.gap = gk;
      var gx0 = WIN_X1 - open, gap = '', ww = '';
      if (open > 1) gap = '<path d="M ' + gx0.toFixed(1) + ' ' + (WIN_Y - 7) + ' V ' + (WIN_Y + 7) + ' M ' + WIN_X1 + ' ' + (WIN_Y - 7) + ' V ' + (WIN_Y + 7) + '"/>';
      if (car.window >= 5 && isDriving && !ui.rest) {
        var mid = gx0 + open / 2;
        ww = '<path d="M ' + (mid + 6).toFixed(1) + ' 92 Q ' + mid.toFixed(1) + ' 130 ' + (mid - 40).toFixed(1) + ' 186"/><path d="M ' + (mid + 22).toFixed(1) + ' 94 Q ' + (mid + 14).toFixed(1) + ' 140 ' + (mid - 22).toFixed(1) + ' 210"/>';
      }
      $('pGap').innerHTML = gap; $('pWinWind').innerHTML = ww;
    }
    // 핸들 각도
    attr($('pWheel'), 'transform', 'rotate(' + (car.steerDeg || 0) + ' ' + WHEEL[0] + ' ' + WHEEL[1] + ')');

    // 지붕 위 숫자와 한도 막대. 추정이 시작되기 전에는 비워 둔다(빈 자리 표시 "--" 를 두지 않는다)
    $('readout').style.visibility = ppm == null ? 'hidden' : '';
    setText($('ppmNum'), ppm == null ? '' : fmtInt(ppm));
    var frac = ppm == null ? 0 : clamp((ppm - sim.outdoorPpm) / Math.max(1, limit - sim.outdoorPpm), 0, 1);
    $('limFill').style.width = (frac * 100).toFixed(1) + '%';
    $('readout').querySelector('.lim').classList.toggle('over', ppm != null && ppm >= limit);
    setText($('limText'), tr('limit') + ' ' + fmtInt(limit));

    // 평면도 아래 한 줄
    if (hint === 'gear' && isDriving) hint = 'done';
    var foot = $('planFoot'), text = '', isHint = false, now = clock.now();
    if (caption.key && (caption.demo || caption.until > now)) text = tr(caption.key, caption.arg);
    else if (!demo && hint === 'seat') { text = tr('hintSeat'); isHint = true; }
    else if (!demo && hint === 'gear') { text = tr('hintGear'); isHint = true; }
    setText(foot, text);
    foot.classList.toggle('hint', isHint);
  }

  function renderControls() {
    Array.prototype.forEach.call($('segGear').children, function (b) { pressed(b, b.dataset.gear === car.gear); });
    var labels = [tr('seatDriver'), tr('seatFront'), tr('seatRearL'), tr('seatRearM'), tr('seatRearR')];
    Array.prototype.forEach.call(document.querySelectorAll('.seat'), function (b) {
      var i = +b.dataset.seat;
      pressed(b, car.seats[i]);
      attr(b, 'aria-label', labels[i]);
    });
    // 공조 버튼은 지금 상태를 글자로 말한다: 외기(파란 표시등) 또는 내기순환
    var hv = $('hvRecirc');
    attr(hv, 'data-air', car.recirc ? 'recirc' : 'fresh');
    setText($('hvT'), car.recirc ? tr('recircOn') : tr('freshOn'));
    attr(hv, 'aria-label', car.recirc ? tr('hvacRecirc') : tr('hvacFresh'));
    setText($('fanV'), String(car.fan));
    setText($('tempV'), car.temp.toFixed(1) + '°');
    disable($('fanDn'), car.fan <= 0); disable($('fanUp'), car.fan >= FAN_MAX);
    disable($('tempDn'), car.temp <= TEMP_MIN); disable($('tempUp'), car.temp >= TEMP_MAX);
    setText($('winV'), car.window + '%');
    disable($('winDn'), car.window >= 100); disable($('winUp'), car.window <= 0);
    pressed($('swWiper'), car.wiper); pressed($('swBeam'), car.lowBeam);
    pressed($('turnL'), car.turn === 'LEFT'); pressed($('turnR'), car.turn === 'RIGHT');
    pressed($('swAttn'), car.attention);
    pressed($('swAuto'), car.autoClimate); pressed($('btnEta'), car.eta != null); pressed($('swTrust'), trust().delegated);
    show($('btnSteer'), OPT.steer);
    disable($('btnSteer'), !!car.steerStep);
    setText($('btnSteer'), car.steerStep ? tr('steerRun', car.steerStep) : tr('steerBtn'));
    disable($('btnWithin'), !car.tracking || car.within);
  }

  function applyLang() {
    document.documentElement.lang = lang;
    document.title = tr('title');
    Array.prototype.forEach.call(document.querySelectorAll('[data-i18n]'), function (el) { el.textContent = tr(el.dataset.i18n); });
    Array.prototype.forEach.call($('segLang').children, function (b) { pressed(b, b.dataset.lang === lang); });
    [['fanDn', 'fanDn'], ['fanUp', 'fanUp'], ['tempDn', 'tempDn'], ['tempUp', 'tempUp'], ['winDn', 'winDn'], ['winUp', 'winUp'], ['turnL', 'turnL'], ['turnR', 'turnR']]
      .forEach(function (p) { attr($(p[0]), 'aria-label', tr(p[1])); });
    attr($('segSpeed'), 'aria-label', lang === 'en' ? 'Speed' : '속도');
    attr($('segGear'), 'aria-label', lang === 'en' ? 'Gear' : '기어');
    renderChrome();
    logsDirty = true; sdkDirty = true;
    render();
  }
  /** 상단 버튼, 속도 설명(마우스를 올리거나 포커스했을 때만 보인다), 음성, 엔진 기록 링크 */
  function renderChrome() {
    var mainOn = !!demo && demo.kind === 'main', trustOn = !!demo && demo.kind === 'trust';
    setText($('btnDemo'), mainOn ? tr('btnDemoStop') : tr('btnDemo')); pressed($('btnDemo'), mainOn);
    setText($('btnTrust'), trustOn ? tr('btnTrustStop') : tr('btnTrust')); pressed($('btnTrust'), trustOn);
    Array.prototype.forEach.call($('segSpeed').children, function (b) { pressed(b, (b.dataset.speed === 'demo') === demoFast); });
    setText($('speedNote'), demoFast ? tr('noteDemo') : tr('noteReal'));
    setText($('btnVoiceT'), voice ? tr('voiceOn') : tr('voiceOff')); pressed($('btnVoice'), voice);
    setText($('evSum'), $('evBox').open ? tr('evHide') : tr('evShow'));
  }
  /** 자동 시연 단계 문장. 시연이 끝난 문장(끝, 멈춤)은 6초 동안만 남긴다 */
  function setCaption(key, isDemo, arg) { caption = { key: key, demo: !!isDemo, until: isDemo ? 0 : clock.now() + 6000, arg: arg }; render(); }

  // ---------------------------------------------------------------- 처음으로, 속도

  function boot() {
    sim = new Sim(demoFast);
    if (OPT.steer || OPT.window) sim.configure(OPT.steer, OPT.window, clock.now());
    buildGauge();
    buildPlan();
    car = freshCar(); ui = freshUi();
    sdkLog = []; evLog = []; logsDirty = true; sdkDirty = true; fresh.reset();
    actuator.reset(); tracker.lastSent = undefined; execOpen = null; reverting = false; proposalKind = null;
    caption = { key: null, demo: false, until: 0 };
    Array.prototype.forEach.call(document.querySelectorAll('.flash'), function (el) { el.classList.remove('flash'); });
    // 처음 값: 기어 P, 혼자, 외기, 풍량 3/8, 창문 닫힘. 엔진은 대기 중에도 기억한다
    sig.gear(); sig.recirc(); sig.occupants(); sig.fan(); sig.window(); sig.wiper(); sig.lowBeam();
    tracker.sendCandidate(true);
  }
  function hardReset(opts) {
    epoch++; demo = null; queue.length = 0;
    setBoost(1);
    cancelSpeech();
    if (opts && opts.demoFast != null) demoFast = opts.demoFast;
    boot();
    renderChrome(); render();
  }
  function setSpeed(fast) {
    if (demoFast === fast) return;
    demoFast = fast;
    emit(null, function (t) { return sim.setDemoFast(fast, t); });
    logEv('in', clock.now(), '→ updateConfig ' + (fast ? '60배 데모' : '실제 속도'));
    renderChrome(); render();
  }

  // ---------------------------------------------------------------- 자동 시연

  function stopDemo(key) {
    if (!demo) return;
    demo = null;
    setBoost(1);
    setCaption(key || 'dStopped', false);
    renderChrome();
  }
  /** 시연 공통: 처음 상태(60배)로 돌리고, 살아 있는 동안만 다음 단계로 가는 도우미를 돌려준다 */
  function demoKit(kind) {
    hardReset({ demoFast: true });
    var my = demo = { kind: kind };
    hint = 'done';
    var alive = function () { return demo === my; };
    var kit = {
      alive: alive,
      cap: function (key, arg) { if (alive()) setCaption(key, true, arg); },
      wait: async function (ms) { return (await sleep(ms)) && alive(); },
      until: async function (pred, maxMs) {
        var end = clock.now() + maxMs;
        while (alive()) {
          if (pred()) return true;
          if (clock.now() > end) break;
          if (!(await sleep(100))) return false;
        }
        if (alive()) stopDemo('dFail');
        return false;
      },
      finish: function (key) { if (alive()) { demo = null; setBoost(1); setCaption(key, false); renderChrome(); } }
    };
    renderChrome();
    return kit;
  }
  var proposalWith = function (kind) { return function () { return ui.proposal && !ui.proposal.announce && ui.proposal.kind === kind; }; };
  var readOut = function () { return ui.proposal && ui.proposal.countdownFrom != null; };
  var idle = function () { return !ui.running && !ui.proposal && sim.phase === 'IDLE'; };

  /**
   * 자동 시연(약 2분): 혼자 출발 → 내기순환 → 첫 제안("20초만", 원복까지) → 뒷좌석 3명 → "바꿔 둘까요?" → 유지
   * → 하향등, 트립 +120분, 운전자가 내기순환으로 → 졸음 경고 → 졸음 제안(쉼터 경유) → 쉼터 도착 → P(쉼 카드와 쉼 질문) → D → P(도착 카드와 맡기기 질문)
   */
  async function runDemo() {
    var k = demoKit('main'), cap = k.cap, wait = k.wait, until = k.until;
    // 도착 카드의 맡기기 질문까지 보여 주려고, 지난 주행까지 두 번 연달아 동의한 상태에서 시작한다
    sim.setTrust(false, 2, 0);
    var lastProposalAt = 0;

    cap('d1');
    if (!(await wait(2600))) return;
    cap('d2'); user.gear('DRIVE');
    if (!(await wait(1500))) return;
    cap('d3'); user.recirc(true);
    if (!(await until(proposalWith('STALE_AIR'), 45000))) return;
    lastProposalAt = clock.now();
    cap('d5');
    if (!(await until(readOut, 25000))) return;
    if (!(await wait(1200))) return;
    cap('d6'); answer(true);
    if (!(await until(function () { return ui.running && ui.running.remainingSec > 0; }, 8000))) return;
    cap('d7');
    if (!(await wait(5000))) return;
    cap('d8');
    if (!(await until(idle, 60000))) return;
    cap('d9');
    // 원복 문장을 읽을 시간과 환기 직후 간격이 지난 뒤에 뒷좌석을 태운다
    if (!(await wait(5500))) return;
    cap('d4');
    for (var i = 2; i <= 4; i++) { user.seat(i, true); if (!(await wait(500))) return; }
    if (!(await until(proposalWith('STALE_AIR'), 30000))) return;
    lastProposalAt = clock.now();
    cap('d4b');
    if (!(await wait(3800))) return;
    cap('d5b');
    if (!(await until(readOut, 25000))) return;
    if (!(await wait(1200))) return;
    cap('d6b'); answer(true);
    if (!(await until(function () { return idle() && !car.recirc; }, 10000))) return;
    if (!(await wait(3500))) return;
    cap('d11'); user.lowBeam(true); user.tripAdd(120);
    // 직전 제안 뒤 20초 동안은 엔진이 새 제안을 내지 않는다. 그 시간이 지난 뒤에 경고를 켠다
    if (!(await wait(Math.max(3500, 18500 - (clock.now() - lastProposalAt))))) return;
    // 쉼터에서 쉼 질문이 나오도록, 운전자가 내기순환으로 돌려놓은 것으로 한다(바깥 공기로 두기의 거절로 세고 20초 동안 다시 묻지 않는다)
    cap('d11b'); user.recirc(true);
    if (!(await wait(2200))) return;
    cap('d12'); user.attention(true);
    if (!(await until(proposalWith('DROWSY'), 30000))) return;
    if (!(await wait(1500))) return;
    cap('d13');
    if (!(await until(readOut, 30000))) return;
    user.attention(false);
    if (!(await wait(1200))) return;
    cap('d14', OPT.window); answer(true);
    if (!(await until(function () { return car.tracking && ui.running && ui.running.remainingSec > 0; }, 10000))) return;
    if (!(await wait(2500))) return;
    cap('d15');
    if (!(await until(function () { return car.within; }, 40000))) return;
    cap('d16');
    if (!(await wait(1800))) return;
    cap('d17'); user.gear('PARK');
    if (!(await until(function () { return ui.rest; }, 5000))) return;
    if (!(await wait(2600))) return;
    if (ui.rest && ui.rest.ask) {
      cap('d17b');
      if (!(await until(function () { return !ui.rest || !ui.rest.ask || ui.rest.ask.countdownFrom != null; }, 20000))) return;
      if (!(await wait(1800))) return;
      cap('d17c'); answer(true);
      if (!(await until(function () { return !car.recirc; }, 6000))) return;
      if (!(await wait(3200))) return;
    } else if (!(await wait(3600))) return;
    cap('d18'); user.gear('DRIVE');
    if (!(await wait(3800))) return;
    cap('d19'); user.gear('PARK');
    if (!(await until(function () { return ui.arrival; }, 5000))) return;
    if (!(await wait(3200))) return;
    if (ui.trustAsk === 'ask') {
      cap('d19b');
      if (!(await wait(4200))) return;
      trustAnswer(true);
      if (!(await wait(2200))) return;
    }
    k.finish('d20');
  }

  /**
   * 맡기기 시연(약 40초): 맡기기를 켠 채 혼자 출발 → 예고 → 5초 뒤 스스로 실행 → 원복 → 다시 예고 → 이번에는 취소.
   * 기다리기만 하는 구간(탁해질 때까지, 환기 유지 20초)은 빨리 감는다. 예고와 취소 창은 실제 속도다
   */
  async function runTrustDemo() {
    var k = demoKit('trust'), cap = k.cap, wait = k.wait, until = k.until;
    var announced = function () { return ui.proposal && ui.proposal.announce; };
    user.trust(true);
    cap('t1');
    if (!(await wait(1500))) return;
    user.gear('DRIVE');
    if (!(await wait(1000))) return;
    cap('t2'); user.recirc(true);
    setBoost(6);
    if (!(await until(announced, 60000))) return;
    cap('t3');
    if (!(await until(function () { return ui.running && ui.running.remainingSec > 0; }, 30000))) return;
    cap('t4');
    setBoost(4);
    if (!(await until(function () { return idle() && car.recirc; }, 60000))) return;
    setBoost(1);
    cap('t5');
    if (!(await wait(2500))) return;
    setBoost(6);
    if (!(await until(announced, 90000))) return;
    cap('t6');
    if (!(await until(readOut, 20000))) return;
    if (!(await wait(1800))) return;
    answer(false);
    cap('t7');
    if (!(await wait(3500))) return;
    k.finish('t8');
  }

  // ---------------------------------------------------------------- 입력 연결

  function wire() {
    $('btnDemo').addEventListener('click', function () { if (demo && demo.kind === 'main') stopDemo('dStopped'); else runDemo(); });
    $('btnTrust').addEventListener('click', function () { if (demo && demo.kind === 'trust') stopDemo('dStopped'); else runTrustDemo(); });
    $('btnReset').addEventListener('click', function () { hardReset(); });
    $('runCancel').addEventListener('click', function () { cancelRun(); });
    $('btnFresh').addEventListener('click', function () { requestFreshAir(); });
    // 탭이 가려지면 앱 화면이 안 보이는 것과 같다. 엔진은 그동안 예고 대신 묻고, 넘어간 제안을 거절로 세지 않는다
    document.addEventListener('visibilitychange', function () {
      var visible = !document.hidden;
      emit('AppVisibility ' + (visible ? '보임' : '가려짐'), function (t) { return sim.appVisibility(t, visible); });
    });
    $('segSpeed').addEventListener('click', function (e) { var b = e.target.closest('button'); if (!b) return; stopDemo('dStopped'); setSpeed(b.dataset.speed === 'demo'); });
    $('btnVoice').addEventListener('click', function () {
      voice = !voice;
      if (!voice) { try { if ('speechSynthesis' in window) speechSynthesis.cancel(); } catch (err) { /* 무시 */ } ui.voiceMissing = false; }
      else ui.voiceMissing = !koVoice();
      renderChrome(); render();
    });
    $('segLang').addEventListener('click', function (e) { var b = e.target.closest('button'); if (!b) return; lang = b.dataset.lang; applyLang(); });

    try {
      if ('speechSynthesis' in window) {
        speechSynthesis.getVoices();
        speechSynthesis.addEventListener('voiceschanged', function () { if (voice) { ui.voiceMissing = !koVoice(); render(); } });
      }
    } catch (err) { /* 음성 없는 브라우저 */ }
    $('evBox').addEventListener('toggle', function () { logsDirty = true; renderChrome(); renderLogs(); });

    // 사용자가 평면도, 버튼 띠, 헤드유닛에 손대면 자동 시연을 멈춘다(자동 시연은 DOM 이벤트를 쓰지 않는다)
    var touched = function (e) { if (demo && e.isTrusted) stopDemo('dStopped'); };
    ['pointerdown', 'keydown'].forEach(function (type) {
      ['plan', 'strip', 'screenWrap', 'actbar'].forEach(function (id) { $(id).addEventListener(type, touched, true); });
    });
    var click = function (id, fn) { $(id).addEventListener('click', fn); };

    $('segGear').addEventListener('click', function (e) { var b = e.target.closest('button'); if (b) user.gear(b.dataset.gear); });
    $('seats').addEventListener('click', function (e) {
      var b = e.target.closest('.seat');
      if (!b || b.disabled) return;
      var i = +b.dataset.seat;
      user.seat(i, !car.seats[i]);
      if (hint === 'seat') { hint = driving() ? 'done' : 'gear'; render(); }
    });
    click('hvRecirc', function () { user.recirc(!car.recirc); });
    click('fanDn', function () { user.fan(car.fan - 1); });
    click('fanUp', function () { user.fan(car.fan + 1); });
    click('tempDn', function () { user.temp(car.temp - 0.5); });
    click('tempUp', function () { user.temp(car.temp + 0.5); });
    click('winDn', function () { user.window(car.window + 10); });
    click('winUp', function () { user.window(car.window - 10); });
    click('swWiper', function () { user.wiper(!car.wiper); });
    click('swBeam', function () { user.lowBeam(!car.lowBeam); });
    click('turnL', function () { user.turn(car.turn === 'LEFT' ? 'OFF' : 'LEFT'); });
    click('turnR', function () { user.turn(car.turn === 'RIGHT' ? 'OFF' : 'RIGHT'); });
    click('swAttn', function () { user.attention(!car.attention); });
    click('btnSteer', function () { user.steerJerks(); });
    click('swAuto', function () { user.autoClimate(!car.autoClimate); });
    click('btnEta', function () { user.eta(car.eta == null ? 4 : null); });
    click('swTrust', function () { user.trust(!trust().delegated); });
    click('btnTrip', function () { user.tripAdd(120); });
    click('btnWithin', function () { tracker.arrive(); render(); });

    click('pYes', function () { answer(true); });
    click('pNo', function () { answer(false); });
    click('aOk', dismissArrival);
    click('xYes', function () { answer(true); });
    click('xNo', function () { answer(false); });
    click('xOk', dismissArrival);
    click('rYes', function () { answer(true); });
    click('rNo', function () { answer(false); });
    click('aTrustYes', function () { trustAnswer(true); });
    click('aTrustNo', function () { trustAnswer(false); });
    click('xTrustYes', function () { trustAnswer(true); });
    click('xTrustNo', function () { trustAnswer(false); });

    // 차량 화면을 폭에 맞춰 축소. 평면도는 좁은 화면에서 세로로 돌린다
    var wrap = $('screenWrap');
    // 폭이 560px 보다 좁으면 논리 크기를 960x540 으로 줄여 글자가 상대적으로 커지게 한다
    var fit = function () {
      var w = wrap.clientWidth, was = huSmall;
      huSmall = w < 560; huLW = huSmall ? 960 : 1280;
      if (was !== huSmall && sim) buildGauge();
      $('car').classList.toggle('s', huSmall);
      $('car').style.setProperty('--k', String(w / huLW));
    };
    if ('ResizeObserver' in window) new ResizeObserver(fit).observe(wrap);
    window.addEventListener('resize', function () { fit(); if (narrow() !== vert) buildPlan(); renderChrome(); render(); });
    fit();
  }

  // ---------------------------------------------------------------- 시작

  var lastFast = clock.now();
  wire();
  boot();
  applyLang();

  // 엔진 tick 은 시계로 1초마다(빨리 감는 동안에도 엔진이 보는 간격은 1초). 그 사이에는 경유지까지 거리와 카운트다운 표시만 고친다
  var tickCount = 0, lastTickAt = clock.now();
  setInterval(function () {
    var now = clock.now(), dt = now - lastFast;
    lastFast = now;
    tracker.advance(dt);
    if (now - lastTickAt >= 990) {
      lastTickAt = now;
      // 주행 중에는 5초마다 위치와 속도를 보낸다
      if (driving() && ++tickCount % 5 === 0) {
        sig.location();
        // 내비가 살아 있는 것처럼 남은 시간을 다시 알린다. 엔진은 받은 지 10분(데모 10초)이 지난 값을 쓰지 않는다
        if (car.eta != null) { var eta = car.eta; emit(null, function (t) { return sim.destinationEta(t, eta); }); }
      }
      emit(null, function (t) { return sim.tick(t); });
    } else render();
  }, 250 / TS);

  if (params.get('demo') === '1') runDemo();
  else if (params.get('demo') === 'trust') runTrustDemo();

  // 시험용 손잡이(자동 검증 스크립트가 상태를 읽는다)
  window.__kkaeugi = {
    state: function () { return { ui: ui, car: car, phase: sim.phase, ppm: sim.ppm, tripState: sim.tripState, demo: !!demo, caption: caption.key, sdk: sdkLog.length, lang: lang, trust: trust(), quiet: sim.quietMode, variant: sim.lastProposalVariant, boost: boost }; },
    sdkLog: function () { return sdkLog.slice(); },
    evLog: function () { return evLog.map(function (l) { return l.text; }); },
    user: user, answer: answer, trustAnswer: trustAnswer, runDemo: runDemo, runTrustDemo: runTrustDemo, tracker: tracker,
    cancelRun: cancelRun, requestFreshAir: requestFreshAir,
    /** 탭이 가려진 것처럼 엔진에 알린다(자동 검증용) */
    setVisible: function (v) { emit('AppVisibility ' + (v ? '보임' : '가려짐'), function (t) { return sim.appVisibility(t, v); }); }
  };
})();

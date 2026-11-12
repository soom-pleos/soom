// 스모크 테스트: Kotlin/JS 로 컴파일한 엔진을 node 에서 불러 SimBridge 로 시나리오를 돌린다.
// 실행: node websim/test/smoke.mjs  (먼저 gradle :websim:compileProductionExecutableKotlinJs)
// 인자로 다른 엔진 JS 경로를 줄 수 있다: node websim/test/smoke.mjs path/to/kkaeugiSim.js
// .html 을 주면 그 안의 첫 번째 script(인라인된 엔진)를 꺼내 같은 시나리오를 돌린다: node websim/test/smoke.mjs websim/dist/kkaeugi-sim.html
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';

const here = path.dirname(fileURLToPath(import.meta.url));
const enginePath = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(here, '../build/compileSync/js/main/productionExecutable/kotlin/kkaeugiSim.js');
if (!fs.existsSync(enginePath)) {
  console.error('엔진 JS 가 없습니다: ' + enginePath + '\n먼저 gradle :websim:compileProductionExecutableKotlinJs 를 실행하세요.');
  process.exit(2);
}
let loadPath = enginePath;
if (enginePath.endsWith('.html')) {
  const m = /<script>\n([\s\S]*?)\n<\/script>/.exec(fs.readFileSync(enginePath, 'utf8'));
  if (!m) { console.error('HTML 안에서 엔진 script 를 찾지 못했습니다: ' + enginePath); process.exit(2); }
  loadPath = path.join(os.tmpdir(), `kkaeugi-engine-${process.pid}.cjs`);
  fs.writeFileSync(loadPath, m[1]);
  process.on('exit', () => { try { fs.unlinkSync(loadPath); } catch { /* 이미 없음 */ } });
}
const Sim = createRequire(import.meta.url)(loadPath).ai.kkaeugi.websim.Sim;

const STOP = { name: '석적', routeLabel: '경부선 서울방향', km: 12.0, lat: 36.05163274, lng: 128.4080556 };

/** 시계와 이벤트 기록을 든 작은 테스트 도우미 */
class Rig {
  constructor(demoFast = true, opts = {}) {
    this.sim = new Sim(demoFast);
    // 엔진 기본은 핸들 신호와 창문 개입이 꺼져 있다. 페이지의 ?steer=1, ?window=1 과 같은 길로 켠다
    if (opts.steer || opts.window) this.sim.configure(!!opts.steer, !!opts.window, 0);
    this.t = 10_000;
    this.events = [];
  }
  /** 브리지 호출 결과(JSON 문자열)를 풀어 기록하고 돌려준다 */
  take(json) {
    const list = JSON.parse(json);
    assert.ok(Array.isArray(list), '브리지는 배열 JSON 을 돌려준다');
    for (const e of list) this.events.push({ t: this.t, ...e });
    return list;
  }
  advance(ms) { this.t += ms; return this.t; }
  tick() { this.advance(1000); return this.take(this.sim.tick(this.t)); }
  /** pred 를 만족하는 이벤트가 나올 때까지 1초씩 tick. 못 찾으면 실패 */
  tickUntil(pred, maxSec, what) {
    for (let i = 0; i < maxSec; i++) {
      const hit = this.tick().find(pred);
      if (hit) return hit;
    }
    assert.fail(`${maxSec}초 안에 오지 않음: ${what}`);
  }
  /** 차에 타고 출발: 인원, 풍량 3/8, 창문 닫힘, 기어 D */
  start(occupants) {
    const s = this.sim;
    this.take(s.occupants(this.t, occupants));
    this.take(s.fan(this.t, 3, 8));
    this.take(s.windowOpen(this.t, 0));
    const ev = this.take(s.gear(this.t, 'DRIVE'));
    assert.equal(ev[0].type, 'TripStateChanged');
    assert.equal(ev[0].state, 'DRIVING');
    assert.equal(s.tripState, 'DRIVING');
  }
  /** 가상 차량이 환기를 실행하고 결과를 알린다. 돌려주는 값은 결과 신호가 낸 이벤트 */
  ventilate() {
    this.advance(300); this.take(this.sim.recirc(this.t, false));
    this.advance(300); this.take(this.sim.fan(this.t, 4, 8));
    return this.take(this.sim.actionResult(this.t, 'Ventilate', true, null));
  }
  /** 가상 차량이 원복하고 결과를 알린다 */
  revert() {
    this.advance(300); this.take(this.sim.recirc(this.t, true));
    this.advance(300); this.take(this.sim.fan(this.t, 3, 8));
    return this.take(this.sim.actionResult(this.t, 'Revert', true, null));
  }
  /** 제안 문장을 다 읽은 것으로 하고 답한다. readMs: 읽는 데 걸린 시간(예고는 문장 길이 x 0.1초보다 길어야 읽은 것으로 본다) */
  answer(yes, readMs = 700) {
    this.advance(readMs);
    this.take(this.sim.speechFinished(this.t));
    this.advance(300);
    return this.take(this.sim.answer(this.t, yes));
  }
}

const isType = (type) => (e) => e.type === type;
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// ---------------------------------------------------------------------------

test('환기 체인(혼자): Propose → Execute → Running → Execute(Revert) → Done, 말한 시간만 유지', () => {
  const r = new Rig(true);
  r.start(1);
  r.advance(500);
  r.take(r.sim.recirc(r.t, true));
  const p = r.tickUntil(isType('Propose'), 30, '환기 제안');
  assert.equal(p.kind, 'STALE_AIR');
  // 60배 데모에서는 1초에 100ppm 가까이 오르므로 한도를 넘긴 직후의 추정치가 문장에 들어간다
  assert.match(p.text, /^차 안 공기가 탁해진 것 같아요\(추정 2,[01]00ppm\)\. 20초만 바깥 공기로 바꿀까요\?$/);
  assert.deepEqual(p.actions, [{ type: 'Ventilate', fanDelta: 1 }]);
  assert.equal(p.timeoutSec, 8);
  assert.equal(r.sim.phase, 'AWAITING_ANSWER');
  assert.equal(r.sim.lastProposalText, p.text);
  assert.equal(r.sim.lastProposalVariant, 'REVERT');

  const ex = r.answer(true).find(isType('Execute'));
  assert.ok(ex, '동의하면 Execute');
  assert.equal(ex.revert, false);
  assert.deepEqual(ex.actions.map((a) => a.type), ['Ventilate']);
  assert.equal(r.sim.phase, 'EXECUTING');

  // 가상 차량: 외기 전환, 풍량 +1, 결과 보고
  const ppmBefore = r.sim.ppm;
  r.advance(300); r.take(r.sim.recirc(r.t, false));
  r.advance(300); r.take(r.sim.fan(r.t, 4, 8));
  const run = r.take(r.sim.actionResult(r.t, 'Ventilate', true, '외기 전환, 풍량 3→4')).find(isType('Running'));
  assert.ok(run, '제어 결과가 오면 Running');
  assert.equal(run.label, '바깥 공기로 바꾸는 중');
  assert.equal(run.remainingSec, 20, '말한 시간(20초)만 유지한다');
  assert.equal(r.sim.phase, 'RUNNING');

  const startedAt = r.t;
  const rev = r.tickUntil((e) => e.type === 'Execute' && e.revert, 25, '원복 Execute');
  assert.deepEqual(rev.actions, [{ type: 'Revert' }]);
  const held = (r.t - startedAt) / 1000;
  assert.ok(held >= 19.5 && held <= 21, `유지 구간은 20초, 늘리지 않는다: ${held}`);
  assert.ok(r.sim.ppm < ppmBefore - 500, `환기 중 추정치가 내려간다: ${ppmBefore} → ${r.sim.ppm}`);
  assert.equal(r.sim.phase, 'REVERTING');

  // 가상 차량: 실행 전 값으로 되돌리고 결과 보고
  r.advance(300); r.take(r.sim.recirc(r.t, true));
  r.advance(300); r.take(r.sim.fan(r.t, 3, 8));
  const done = r.take(r.sim.actionResult(r.t, 'Revert', true, null)).find(isType('Done'));
  assert.ok(done, '원복 결과가 오면 Done');
  assert.equal(done.kind, 'STALE_AIR');
  assert.match(done.text, /^추정 [\d,]+ppm까지 내려갔어요\. 원래대로 돌렸어요\.$/);
  assert.equal(r.sim.phase, 'IDLE');

  const order = r.events.map((e) => (e.type === 'Execute' && e.revert ? 'Execute(Revert)' : e.type))
    .filter((t) => ['Propose', 'Execute', 'Running', 'Execute(Revert)', 'Done'].includes(t));
  const collapsed = order.filter((t, i) => t !== order[i - 1]);
  assert.deepEqual(collapsed, ['Propose', 'Execute', 'Running', 'Execute(Revert)', 'Done']);
});

test('인원 1명 대 4명: 제안 시점과 제안 꼴이 다르다 (데모 속도, 1초 = 1분)', () => {
  const firstProposal = (occupants) => {
    const r = new Rig(true);
    r.start(occupants);
    r.take(r.sim.recirc(r.t, true));
    const from = r.t;
    const p = r.tickUntil(isType('Propose'), 40, `${occupants}명 환기 제안`);
    return { sec: (r.t - from) / 1000, text: p.text, variant: r.sim.lastProposalVariant };
  };
  const one = firstProposal(1);
  const four = firstProposal(4);
  assert.ok(one.sec >= 18 && one.sec <= 23, `1명은 약 20초(=20분): ${one.sec}`);
  assert.ok(four.sec >= 3 && four.sec <= 6, `4명은 약 4초(=4분): ${four.sec}`);
  assert.ok(one.sec >= four.sec * 3, `1명이 4명보다 3배 이상 늦다: ${one.sec} 대 ${four.sec}`);
  // 혼자면 "20초만(실제 2분만)" 바꾸고 원복. 4명이면 금방 다시 차므로 바꿔 둘지 묻는다
  assert.equal(one.variant, 'REVERT');
  assert.match(one.text, /^차 안 공기가 탁해진 것 같아요\(추정 [\d,]+ppm\)\. 20초만 바깥 공기로 바꿀까요\?$/);
  assert.equal(four.variant, 'KEEP');
  assert.match(four.text, /^4명이 타서 공기가 빨리 탁해져요\(추정 [\d,]+ppm\)\. 바깥 공기로 바꿔 둘까요\?$/);
});

test('바꿔 두기(4명): Execute → 결과 → 곧바로 Done, Running 과 원복이 없다', () => {
  const r = new Rig(true);
  r.start(4);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Propose'), 15, '환기 제안');
  assert.equal(r.sim.lastProposalVariant, 'KEEP', '4명이면 첫 제안이 KEEP 변형');
  const ex = r.answer(true).find(isType('Execute'));
  assert.deepEqual(ex.actions, [{ type: 'Ventilate', fanDelta: 1 }]);
  r.advance(300); r.take(r.sim.recirc(r.t, false));
  r.advance(300); r.take(r.sim.fan(r.t, 4, 8));
  const after = r.take(r.sim.actionResult(r.t, 'Ventilate', true, null));
  assert.deepEqual(after.filter((e) => e.type !== 'AirEstimate').map((e) => [e.type, e.text]), [['Done', '바깥 공기로 둘게요.']]);
  assert.equal(r.sim.phase, 'IDLE');
  // 그 뒤로는 원복도, 같은 제안도 없다
  for (let i = 0; i < 40; i++) {
    const ev = r.tick();
    assert.ok(!ev.find((e) => e.type === 'Execute' || e.type === 'Propose' || e.type === 'Running'), '외기로 둔 뒤에는 조용하다');
  }
  assert.ok(r.sim.ppm < 1200, `외기로 두면 추정치가 낮게 머문다: ${r.sim.ppm}`);
  assert.ok(!r.events.some((e) => e.type === 'Execute' && e.revert), 'Execute([Revert]) 가 한 번도 없다');
  assert.ok(!r.events.some((e) => e.type === 'Running'), 'Running 이 없다');
});

test('풍량 올리기: 외기인데 바람이 약하면 풍량을 올릴지 묻고, 원복 없이 끝난다', () => {
  const r = new Rig(true);
  r.start(4);
  r.take(r.sim.fan(r.t, 0, 8));
  const p = r.tickUntil(isType('Propose'), 60, '풍량 제안');
  assert.equal(r.sim.lastProposalVariant, 'FAN_UP');
  assert.match(p.text, /^바깥 공기인데 바람이 약해요\(추정 [\d,]+ppm\)\. 풍량을 올릴까요\?$/);
  assert.deepEqual(p.actions, [{ type: 'Ventilate', fanDelta: 2 }]);
  assert.ok(r.answer(true).find(isType('Execute')));
  r.advance(300); r.take(r.sim.fan(r.t, 2, 8));
  const done = r.take(r.sim.actionResult(r.t, 'Ventilate', true, null)).find(isType('Done'));
  assert.equal(done.text, '풍량을 올려 둘게요.');
  assert.equal(r.sim.phase, 'IDLE');
});

test('실제 속도(WakeConfig 기본값): 2명이면 약 8분 뒤 "2분만", 4명이면 약 4분 뒤 "바꿔 둘까요"', () => {
  const run = (occupants, maxSec) => {
    const r = new Rig(false);
    assert.equal(r.sim.demoFast, false);
    assert.equal(r.sim.ventilateSeconds, 120);
    assert.equal(r.sim.restMinutes, 15);
    r.start(occupants);
    r.take(r.sim.recirc(r.t, true));
    const from = r.t;
    const p = r.tickUntil(isType('Propose'), maxSec, '실제 속도 환기 제안');
    return { sec: (r.t - from) / 1000, text: p.text };
  };
  const two = run(2, 700);
  assert.ok(two.sec >= 430 && two.sec <= 540, `2명은 약 8분: ${two.sec}초`);
  assert.equal(two.text, '2명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?');
  const four = run(4, 400);
  assert.ok(four.sec >= 200 && four.sec <= 300, `4명은 약 4분: ${four.sec}초`);
  assert.equal(four.text, '4명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?');
});

test('야간 장거리: 한도가 1,600 으로 내려가고 첫 제안에 한마디가 붙는다', () => {
  const r = new Rig(false);
  // 하향등은 출발 때 이미 켜져 있었다(주행 중에 막 켠 등은 3분 동안 야간으로 보지 않는다)
  r.take(r.sim.lowBeam(r.t, true));
  r.start(1);
  assert.equal(r.sim.limitPpm, 2000);
  assert.equal(r.sim.effectiveLimitPpm, 2000);
  r.take(r.sim.tripTime(r.t, 120));
  r.tick();
  assert.equal(r.sim.effectiveLimitPpm, 1600);
  r.take(r.sim.recirc(r.t, true));
  const from = r.t;
  const p = r.tickUntil(isType('Propose'), 1200, '야간 환기 제안');
  assert.equal(p.text, '오래 달려서 조금 일찍 물어요. 차 안 공기가 탁해진 것 같아요(추정 1,600ppm). 2분만 바깥 공기로 바꿀까요?');
  const min = (r.t - from) / 60000;
  assert.ok(min > 11 && min < 16, `한도가 낮아져 낮(약 20분)보다 일찍 묻는다: ${min.toFixed(1)}분`);
});

test('졸음 개입 기본값: 창문은 건드리지 않는다. ?window=1(allowWindow)일 때만 연다', () => {
  const r = new Rig(true);
  assert.equal(r.sim.allowWindow, false);
  assert.equal(r.sim.steeringAssist, false);
  r.start(1);
  r.take(r.sim.restStopCandidate(r.t, STOP.name, STOP.routeLabel, STOP.km, STOP.lat, STOP.lng));
  r.tick();
  const p = r.take(r.sim.telltale(r.t, 'DRIVER_ATTENTION', true, false)).find(isType('Propose'));
  assert.equal(p.text, '졸음 경고가 떴어요. 찬 바람 넣고, 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?');
  assert.deepEqual(p.actions.map((a) => a.type), ['Ventilate', 'AddRestStop', 'CoolDown']);
  assert.equal(p.passengerMayAnswer, false);
  assert.ok(r.answer(true).find(isType('Execute')));
  r.take(r.sim.actionResult(r.t, 'Ventilate', true, null));
  r.take(r.sim.actionResult(r.t, 'AddRestStop', true, null));
  const run = r.take(r.sim.actionResult(r.t, 'CoolDown', true, null)).find(isType('Running'));
  assert.equal(run.label, '찬 바람 넣는 중');

  const q = new Rig(true);
  q.start(1);
  q.take(q.sim.noRestStop(q.t));
  q.tick();
  const p2 = q.take(q.sim.telltale(q.t, 'DRIVER_ATTENTION', true, false)).find(isType('Propose'));
  assert.equal(p2.text, '졸음 경고가 떴어요. 찬 바람을 넣을까요?');
  assert.deepEqual(p2.actions.map((a) => a.type), ['Ventilate', 'CoolDown']);
});

test('졸음 경고 → 쉼터 경유지 → 쉼 모드(추정 정지) → 도착 카드 (창문 허용)', () => {
  const r = new Rig(true, { window: true });
  assert.equal(r.sim.allowWindow, true);
  r.start(4);
  r.take(r.sim.restStopCandidate(r.t, STOP.name, STOP.routeLabel, STOP.km, STOP.lat, STOP.lng));
  r.tick();
  r.take(r.sim.lowBeam(r.t, true));
  r.take(r.sim.tripTime(r.t, 120));
  r.advance(500);
  const p = r.take(r.sim.telltale(r.t, 'DRIVER_ATTENTION', true, false)).find(isType('Propose'));
  assert.ok(p, '운전자 주의 경고가 켜지면 졸음 제안');
  assert.equal(p.kind, 'DROWSY');
  assert.equal(p.text, '졸음 경고가 떴어요. 찬 바람과 창문 조금, 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?');
  assert.deepEqual(p.actions.map((a) => a.type), ['Ventilate', 'AddRestStop', 'CoolDown', 'OpenWindow']);
  assert.equal(p.actions[1].stop.name, '석적');
  assert.equal(p.actions[1].stop.distanceKm, 12);
  assert.equal(p.actions[2].celsiusDelta, -2);
  assert.equal(p.actions[3].percent, 15);

  assert.ok(r.answer(true).find(isType('Execute')));
  r.advance(300); r.take(r.sim.fan(r.t, 4, 8));
  r.take(r.sim.actionResult(r.t, 'Ventilate', true, null));
  r.advance(300); r.take(r.sim.actionResult(r.t, 'AddRestStop', true, null));
  r.advance(300); r.take(r.sim.actionResult(r.t, 'CoolDown', true, null));
  r.advance(300); r.take(r.sim.windowOpen(r.t, 15));
  const run = r.take(r.sim.actionResult(r.t, 'OpenWindow', true, null)).find(isType('Running'));
  assert.ok(run, '네 결과가 다 오면 Running');
  assert.equal(run.label, '찬 바람과 창문 열어 둔 상태');
  assert.equal(run.remainingSec, 30);
  assert.equal(r.sim.waypointName, '석적');

  // 쉼터 300m 안에서 기어 P → 세션은 끝나지 않고 쉼 모드
  r.tick(); r.tick();
  r.take(r.sim.restProximity(r.t, true, false));
  r.advance(500);
  const park = r.take(r.sim.gear(r.t, 'PARK'));
  assert.ok(park.find((e) => e.type === 'Execute' && e.revert), '진행 중이던 개입은 원복');
  const rest = park.find(isType('RestStarted'));
  assert.ok(rest, '쉼 모드 시작');
  assert.equal(rest.stopName, '석적 졸음쉼터');
  assert.equal(rest.targetMinutes, 15);
  assert.equal(r.sim.resting, true);
  assert.equal(r.sim.tripState, 'DRIVING');
  assert.ok(!park.find(isType('ArrivalCard')), '쉼터 안 P 는 도착이 아니다');

  // 쉬는 동안 추정은 멈춘다
  const ppmAtRest = r.sim.ppm;
  const ticks = [];
  for (let i = 0; i < 6; i++) {
    const ev = r.tick();
    ticks.push(ev.find(isType('RestTick')).remainingSec);
    assert.ok(!ev.find(isType('AirEstimate')), '쉬는 동안 AirEstimate 가 없다');
  }
  assert.deepEqual(ticks, [14, 13, 12, 11, 10, 9]);
  assert.equal(r.sim.ppm, ppmAtRest, '쉬는 동안 추정치가 그대로다');

  const drive = r.take(r.sim.gear(r.t, 'DRIVE'));
  const ended = drive.find(isType('RestEnded'));
  assert.ok(ended, '기어 D 로 쉼 끝');
  assert.equal(ended.restedMinutes, 6);
  assert.equal(ended.text, '6분 쉬셨어요. 다음 쉼터에서 더 쉬어도 좋아요');
  assert.equal(r.sim.resting, false);
  assert.equal(r.sim.waypointName, null);
  assert.equal(r.sim.ppm, 630, '쉼이 끝나면 추정치는 처음 값(바깥 430 + 200)부터');

  // 쉼터 밖에서 기어 P → 도착 카드
  r.tick(); r.tick();
  const end = r.take(r.sim.gear(r.t, 'PARK'));
  assert.equal(end.find(isType('TripStateChanged')).state, 'ENDED');
  const card = end.find(isType('ArrivalCard')).card;
  assert.equal(card.headline, '오늘 환기 1번, 쉼터에서 6분 쉬었어요.');
  assert.equal(card.ventilations, 1);
  assert.equal(card.drowsySignals, 1);
  assert.equal(card.restStopsAdded, 1);
  assert.equal(card.restStopsVisited, 1);
  assert.equal(card.restMinutes, 6);
  assert.equal(card.declined, 0);
  assert.equal(card.tripMinutes, 120);
  assert.ok(card.lines.some((l) => l.includes('석적 졸음쉼터 경유지 추가')), card.lines.join(' / '));
  assert.ok(card.lines.some((l) => l.includes('석적 졸음쉼터에서 6분 휴식')), card.lines.join(' / '));
  assert.ok(!card.lines.some((l) => l.includes('최고 추정')), '공기 줄은 lines 에 없다(peakPpm 으로 따로 보여 준다)');
  assert.equal(r.sim.tripState, 'ENDED');

  r.sim.reset();
  assert.equal(r.sim.tripState, 'IDLE');
});

test('거절: 제어 없이 끝나고 쿨다운 문장', () => {
  const r = new Rig(true);
  r.start(4);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Propose'), 15, '환기 제안');
  const ev = r.answer(false);
  assert.ok(!ev.find(isType('Execute')), '거절하면 Execute 가 없다');
  assert.equal(ev.find(isType('Done')).text, '알겠어요. 20초 뒤에 다시 볼게요.');
  assert.equal(r.sim.phase, 'IDLE');
  assert.equal(r.sim.declineCooldownMinutes, 20);
  // 쿨다운 20초(데모) 동안 다시 묻지 않는다
  for (let i = 0; i < 15; i++) assert.ok(!r.tick().find(isType('Propose')), '쿨다운 중에는 제안 없음');
  // 바꿔 두기를 거절한 다음에는 다시 "20초만" 으로 묻는다
  const again = r.tickUntil(isType('Propose'), 15, '쿨다운 뒤 다시 제안');
  assert.match(again.text, /20초만 바깥 공기로 바꿀까요\?$/);
});

test('무응답: 문장을 다 읽은 뒤 8초가 지나면 넘어간다', () => {
  const r = new Rig(true);
  r.start(4);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Propose'), 15, '환기 제안');
  r.advance(900);
  r.take(r.sim.speechFinished(r.t));
  const from = r.t;
  const ex = r.tickUntil(isType('ProposalExpired'), 12, '무응답 만료');
  assert.equal(ex.text, '다음에 다시 물을게요.');
  const sec = (r.t - from) / 1000;
  assert.ok(sec >= 8 && sec <= 9.5, `8초 뒤 만료: ${sec}`);
});

test('비(와이퍼 ON, 창문 허용): 창문은 열지 않는다', () => {
  const r = new Rig(true, { window: true });
  r.start(1);
  r.take(r.sim.noRestStop(r.t));
  r.take(r.sim.wiper(r.t, true));
  r.tick();
  const p = r.take(r.sim.telltale(r.t, 'DRIVER_ATTENTION', true, false)).find(isType('Propose'));
  assert.equal(p.text, '졸음 경고가 떴어요. 비가 와서 창문은 두고 찬 바람만 넣을까요?');
  assert.deepEqual(p.actions.map((a) => a.type), ['Ventilate', 'CoolDown']);

  const q = new Rig(true, { window: true });
  q.start(1);
  q.take(q.sim.restStopCandidate(q.t, STOP.name, STOP.routeLabel, STOP.km, STOP.lat, STOP.lng));
  q.take(q.sim.wiper(q.t, true));
  q.tick();
  const p2 = q.take(q.sim.telltale(q.t, 'DRIVER_ATTENTION', true, false)).find(isType('Propose'));
  assert.equal(p2.text, '졸음 경고가 떴어요. 비가 와서 창문은 두고, 찬 바람과 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?');
  assert.deepEqual(p2.actions.map((a) => a.type), ['Ventilate', 'AddRestStop', 'CoolDown']);
});

test('발화 게이트: 방향지시등이 켜져 있으면 미루고, 끄면 말한다', () => {
  const r = new Rig(true);
  r.start(1);
  r.take(r.sim.noRestStop(r.t));
  r.tick();
  r.take(r.sim.turnSignal(r.t, 'LEFT'));
  const d = r.take(r.sim.telltale(r.t, 'DRIVER_ATTENTION', true, false));
  assert.ok(!d.find(isType('Propose')));
  assert.equal(d.find(isType('Deferred')).reason, 'turn_signal');
  assert.ok(!r.tick().find(isType('Propose')), '켜져 있는 동안은 말하지 않는다');
  // 끄는 신호가 들어온 그 자리에서 미뤄 둔 제안이 나간다
  const off = r.take(r.sim.turnSignal(r.t, 'OFF'));
  const p = off.find(isType('Propose'));
  assert.equal(p.kind, 'DROWSY');
  assert.equal(p.text, '졸음 경고가 떴어요. 찬 바람을 넣을까요?');
});

test('핸들 급보정 3회(?steer=1, 트립 30분 이후, 고속 주행): 단정하지 않는 문장으로 제안. 저속에서는 세지 않고, 기본값에서는 아예 보지 않는다', () => {
  // 직진으로 6초 넘게 가만히 있다가 12도를 꺾고 곧바로 되돌리는 동작
  const jerks = (r, times) => {
    let proposal = null;
    for (let n = 0; n < times && !proposal; n++) {
      const until = r.t + r.sim.steerQuietSec * 1000 + 400;
      while (r.t < until) { r.take(r.sim.steering(r.t, 0)); r.advance(200); }
      proposal = r.take(r.sim.steering(r.t, 12)).find(isType('Propose')) || null;
      r.advance(400);
    }
    return proposal;
  };
  const slow = new Rig(true, { steer: true });
  slow.start(1);
  slow.take(slow.sim.noRestStop(slow.t));
  slow.take(slow.sim.tripTime(slow.t, 40));
  slow.take(slow.sim.location(slow.t, 35.95, 128.45, 30));
  assert.equal(jerks(slow, 3), null, '시속 30km 에서는 제안으로 이어지지 않는다');
  assert.equal(slow.sim.steerEvents, 0);

  const off = new Rig(true);
  off.start(1);
  off.take(off.sim.noRestStop(off.t));
  off.take(off.sim.tripTime(off.t, 40));
  off.take(off.sim.location(off.t, 35.95, 128.45, 100));
  assert.equal(jerks(off, 3), null, '기본값(steeringAssist 꺼짐)에서는 핸들 신호를 무시한다');
  assert.equal(off.sim.steerEvents, 0);

  const r = new Rig(true, { steer: true });
  assert.equal(r.sim.steeringAssist, true);
  r.start(1);
  r.take(r.sim.noRestStop(r.t));
  r.take(r.sim.tripTime(r.t, 40));
  r.take(r.sim.location(r.t, 35.95, 128.45, 100));
  assert.equal(jerks(r, 2), null);
  assert.equal(r.sim.steerEvents, 2, '급보정 2회까지는 세기만 한다');
  const proposal = jerks(r, 1);
  assert.ok(proposal, '세 번째 급보정에서 제안');
  assert.equal(proposal.kind, 'DROWSY');
  assert.equal(proposal.text, '핸들 보정이 잦아졌어요. 찬 바람을 넣을까요?');
});

test('자동 개입 OFF, 속도 전환, 쉼터 표시 이름, JSON 이스케이프', () => {
  const r = new Rig(true);
  r.start(4);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Propose'), 15, '환기 제안');
  const off = r.take(r.sim.setEnabled(false, r.t));
  assert.deepEqual(off.map((e) => [e.type, e.text]), [['Done', null]]);
  assert.equal(r.sim.enabled, false);
  r.take(r.sim.setEnabled(true, r.t));
  assert.equal(r.sim.enabled, true);

  assert.equal(r.sim.demoFast, true);
  assert.equal(r.sim.ventilateSeconds, 20);
  assert.equal(r.sim.longTripMinutes, 60);
  r.take(r.sim.setDemoFast(false, r.t));
  assert.equal(r.sim.demoFast, false);
  assert.equal(r.sim.ventilateSeconds, 120);
  assert.equal(r.sim.longTripMinutes, 120);
  assert.equal(r.sim.limitPpm, 2000);
  assert.equal(r.sim.outdoorPpm, 430);
  assert.equal(r.sim.leakAch, 2);
  assert.equal(r.sim.co2PerPersonLph, 18);
  assert.equal(r.sim.cabinVolumeL, 3000);

  assert.equal(r.sim.stopDisplayName('석적'), '석적 졸음쉼터');
  assert.equal(r.sim.stopDisplayName('석적졸음쉼터'), '석적졸음쉼터');
  assert.equal(r.sim.stopDisplayName('칠곡휴게소'), '칠곡휴게소');

  // 이름에 따옴표, 역슬래시, 줄바꿈이 있어도 JSON 이 깨지지 않는다
  const odd = '쉼"터\\ 1\n2';
  const q = new Rig(true);
  q.start(1);
  q.take(q.sim.restStopCandidate(q.t, odd, '시험선 "서울"방향', 9.5, 36.1, 128.1));
  q.tick();
  const p = q.take(q.sim.telltale(q.t, 'DRIVER_ATTENTION', true, false)).find(isType('Propose'));
  assert.equal(p.actions.find((a) => a.type === 'AddRestStop').stop.name, odd);
  assert.ok(p.text.includes(odd));
});

// ---------------------------------------------------------------------------
// 계약 v4: 맡기기, 조용 모드, 묻지 않는 때, 쉼 모드의 바깥 공기 질문

const trustOf = (r) => JSON.parse(r.sim.trust);
const ANNOUNCE = "20초만 바깥 공기로 바꿀게요. 싫으면 '아니'라고 하세요.";
/** 예고 문장(32자)을 읽는 데 걸린 시간. 문장 길이 x 0.1초(3.2초)보다 길어야 엔진이 읽은 것으로 본다 */
const READ_MS = 3500;

test('맡기기 켠 1명: Announce → 무응답 5초 → Execute → Revert → Done', () => {
  const r = new Rig(true);
  r.sim.setTrust(true, 0, 0);
  assert.deepEqual(trustOf(r), { delegated: true, consecutiveYes: 0, cancelCount: 0 });
  r.start(1);
  r.take(r.sim.recirc(r.t, true));
  const a = r.tickUntil((e) => e.type === 'Announce' || e.type === 'Propose', 30, '예고');
  assert.equal(a.type, 'Announce', '맡겨 두면 묻지 않고 예고한다');
  assert.equal(a.kind, 'STALE_AIR');
  assert.equal(a.text, ANNOUNCE);
  assert.equal(a.cancelWindowSec, 5);
  assert.deepEqual(a.actions, [{ type: 'Ventilate', fanDelta: 1 }]);
  assert.equal(r.sim.phase, 'ANNOUNCING');
  assert.equal(r.sim.lastProposalVariant, 'ANNOUNCE');
  r.advance(READ_MS);
  r.take(r.sim.speechFinished(r.t));
  const from = r.t;
  const ex = r.tickUntil(isType('Execute'), 8, '취소 창 뒤 Execute');
  const sec = (r.t - from) / 1000;
  assert.ok(sec >= 5 && sec <= 6.5, `답이 없으면 5초 뒤 실행: ${sec}`);
  assert.equal(ex.revert, false);
  assert.ok(r.ventilate().find(isType('Running')), '실행 뒤는 동의한 제안과 같다');
  r.tickUntil((e) => e.type === 'Execute' && e.revert, 25, '원복 Execute');
  const done = r.revert().find(isType('Done'));
  assert.match(done.text, /원래대로 돌렸어요\.$/);
  assert.equal(r.sim.phase, 'IDLE');
  const order = r.events.map((e) => (e.type === 'Execute' && e.revert ? 'Execute(Revert)' : e.type))
    .filter((t) => ['Propose', 'Announce', 'Execute', 'Running', 'Execute(Revert)', 'Done'].includes(t));
  assert.deepEqual(order.filter((t, i) => t !== order[i - 1]), ['Announce', 'Execute', 'Running', 'Execute(Revert)', 'Done']);
  assert.equal(trustOf(r).delegated, true);
});

test('예고 취소: Done("알겠어요. 그대로 둘게요."), 취소 1회, 두 번째 취소에 맡기기가 꺼진다', () => {
  const r = new Rig(true);
  r.sim.setTrust(true, 0, 0);
  r.start(1);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Announce'), 30, '예고');
  const ev = r.answer(false, READ_MS);
  assert.ok(!ev.find(isType('Execute')), '취소하면 차를 건드리지 않는다');
  assert.equal(ev.find(isType('Done')).text, '알겠어요. 그대로 둘게요.');
  const tc = ev.find(isType('TrustChanged'));
  assert.ok(tc, '취소 횟수가 바뀌면 TrustChanged');
  assert.deepEqual(tc.state, { delegated: true, consecutiveYes: 0, cancelCount: 1 });
  assert.equal(r.sim.phase, 'IDLE');
  // 그 주행의 남은 환기는 다시 묻는다
  const again = r.tickUntil((e) => e.type === 'Announce' || e.type === 'Propose', 40, '취소 뒤 다음 환기');
  assert.equal(again.type, 'Propose');

  const q = new Rig(true);
  q.sim.setTrust(true, 0, 1);
  q.start(1);
  q.take(q.sim.recirc(q.t, true));
  q.tickUntil(isType('Announce'), 30, '예고');
  const ev2 = q.answer(false, READ_MS);
  assert.equal(ev2.find(isType('TrustChanged')).state.delegated, false, '취소 2회면 맡기기가 꺼진다');
  assert.equal(trustOf(q).delegated, false);
});

test('예고 창의 "응": 기다리지 않고 바로 실행', () => {
  const r = new Rig(true);
  r.sim.setTrust(true, 0, 0);
  r.start(1);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Announce'), 30, '예고');
  assert.ok(r.answer(true, READ_MS).find(isType('Execute')));
  assert.equal(r.sim.cancellableRun, true, '예고로 시작한 실행은 취소할 수 있다');
});

test('환기 제안 2연속 거절 → Quiet, 그 주행에서는 더 묻지 않는다', () => {
  const r = new Rig(true);
  r.start(1);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Propose'), 30, '첫 환기 제안');
  assert.equal(r.answer(false).find(isType('Done')).text, '알겠어요. 20초 뒤에 다시 볼게요.');
  assert.equal(r.sim.quietMode, false);
  r.tickUntil(isType('Propose'), 40, '두 번째 환기 제안');
  const ev = r.answer(false);
  const quiet = ev.find(isType('Quiet'));
  assert.ok(quiet, '두 번째 거절에 Quiet');
  assert.equal(quiet.text, '이번 주행에서는 더 묻지 않을게요. 화면에만 표시할게요.');
  assert.equal(ev.find(isType('Done')).text, null, '들리는 말은 Quiet 하나');
  assert.equal(r.sim.quietMode, true);
  for (let i = 0; i < 60; i++) assert.ok(!r.tick().find((e) => e.type === 'Propose' || e.type === 'Announce'), '조용 모드에서는 환기 제안이 없다');
  assert.equal(r.events.filter(isType('Quiet')).length, 1, 'Quiet 는 한 번');
  // 졸음 제안은 조용 모드에서도 낸다
  r.take(r.sim.noRestStop(r.t));
  assert.equal(r.take(r.sim.telltale(r.t, 'DRIVER_ATTENTION', true, false)).find(isType('Propose')).kind, 'DROWSY');
});

test('도착 4분 전: 환기 제안을 내지 않는다(Deferred arriving 한 번). 목적지를 지우면 묻는다', () => {
  const r = new Rig(true);
  r.start(4);
  r.take(r.sim.destinationEta(r.t, 4));
  assert.equal(r.sim.arrivingSoon, true);
  r.take(r.sim.recirc(r.t, true));
  // 데모 속도에서는 받은 지 10초가 지난 남은 시간을 쓰지 않는다. 내비가 살아 있는 것처럼 5초마다 다시 알린다
  for (let i = 0; i < 30; i++) {
    assert.ok(!r.tick().find((e) => e.type === 'Propose' || e.type === 'Announce'), '도착 임박이면 제안 없음');
    if (i % 5 === 4) r.take(r.sim.destinationEta(r.t, 4));
  }
  assert.ok(r.sim.ppm > 2000, `한도는 넘었다: ${r.sim.ppm}`);
  const held = r.events.filter((e) => e.type === 'Deferred' && e.reason === 'arriving');
  assert.equal(held.length, 1, '보류 사유는 한 번만');
  // 보류가 풀리는 그 신호에서 미뤄 둔 제안이 나간다
  assert.ok(r.take(r.sim.destinationEta(r.t, null)).find(isType('Propose')), '목적지를 지우면 환기 제안');
  assert.equal(r.sim.arrivingSoon, false);
  const far = new Rig(true);
  far.take(far.sim.destinationEta(far.t, 6));
  assert.equal(far.sim.arrivingSoon, false, '6분 남았으면 임박이 아니다');
});

test('차 자동 공조 중: 환기 제안을 내지 않는다(Deferred car-auto). 끄면 묻는다', () => {
  const r = new Rig(true);
  r.start(4);
  r.take(r.sim.autoClimate(r.t, true));
  assert.equal(r.sim.carAutoActive, true);
  r.take(r.sim.recirc(r.t, true));
  for (let i = 0; i < 30; i++) assert.ok(!r.tick().find((e) => e.type === 'Propose' || e.type === 'Announce'), '차가 내외기를 제어하는 중이면 제안 없음');
  assert.equal(r.events.filter((e) => e.type === 'Deferred' && e.reason === 'car-auto').length, 1);
  assert.ok(r.take(r.sim.autoClimate(r.t, false)).find(isType('Propose')), '자동 공조를 끄면 환기 제안');
  assert.equal(r.sim.carAutoActive, false);
});

test('쉼 질문 동의: 쉬는 동안 바깥 공기, 쉼이 끝나면 Revert', () => {
  const r = new Rig(true);
  r.start(1);
  r.take(r.sim.restStopCandidate(r.t, STOP.name, STOP.routeLabel, STOP.km, STOP.lat, STOP.lng));
  r.take(r.sim.recirc(r.t, true));
  r.tick();
  // 경유지를 넣으려고 졸음 제안을 거친다
  assert.ok(r.take(r.sim.telltale(r.t, 'DRIVER_ATTENTION', true, false)).find(isType('Propose')));
  assert.ok(r.answer(true).find(isType('Execute')));
  r.advance(300); r.take(r.sim.recirc(r.t, false));
  r.take(r.sim.actionResult(r.t, 'Ventilate', true, null));
  r.take(r.sim.actionResult(r.t, 'AddRestStop', true, null));
  assert.ok(r.take(r.sim.actionResult(r.t, 'CoolDown', true, null)).find(isType('Running')));
  r.tickUntil((e) => e.type === 'Execute' && e.revert, 35, '졸음 개입 원복');
  r.advance(300); r.take(r.sim.recirc(r.t, true));
  assert.ok(r.take(r.sim.actionResult(r.t, 'Revert', true, null)).find(isType('Done')));
  r.take(r.sim.restProximity(r.t, true, false));
  r.advance(500);
  const park = r.take(r.sim.gear(r.t, 'PARK'));
  const types = park.map((e) => e.type);
  assert.ok(types.indexOf('RestStarted') >= 0 && types.indexOf('Propose') > types.indexOf('RestStarted'), 'RestStarted 뒤에 질문: ' + types.join(','));
  const ask = park.find(isType('Propose'));
  assert.equal(ask.text, '쉬는 동안 바깥 공기로 둘까요?');
  assert.equal(ask.timeoutSec, 20);
  assert.deepEqual(ask.actions.map((a) => a.type), ['Ventilate']);
  assert.equal(r.sim.lastProposalVariant, 'REST');
  r.tick(); r.tick();
  const yes = r.take(r.sim.answer(r.t, true));
  const ex = yes.find(isType('Execute'));
  assert.ok(ex && !ex.revert, '동의하면 Execute(Ventilate)');
  r.advance(300); r.take(r.sim.recirc(r.t, false));
  const res = r.take(r.sim.actionResult(r.t, 'Ventilate', true, null));
  assert.deepEqual(res.filter((e) => e.type !== 'RestTick').map((e) => [e.type, e.text]), [['Done', null]], '결과 뒤 말없이 닫는다');
  for (let i = 0; i < 5; i++) assert.ok(!r.tick().find((e) => e.type === 'Running' || e.type === 'Execute'), '쉬는 동안 그대로 둔다');
  const drive = r.take(r.sim.gear(r.t, 'DRIVE'));
  const seq = drive.filter((e) => ['RestEnded', 'Execute', 'Done'].includes(e.type)).map((e) => (e.type === 'Execute' && e.revert ? 'Execute(Revert)' : e.type));
  assert.deepEqual(seq, ['RestEnded', 'Execute(Revert)', 'Done'], '쉼이 끝나면 조용히 되돌린다');
  assert.equal(drive.find(isType('Done')).text, null);
});

test('쉼 질문 거절·외기: 거절은 Done(null) 뿐이고, 외기로 쉬면 묻지 않는다', () => {
  const toRest = (recirc) => {
    const r = new Rig(true);
    r.start(1);
    r.take(r.sim.restStopCandidate(r.t, STOP.name, STOP.routeLabel, STOP.km, STOP.lat, STOP.lng));
    r.tick();
    r.take(r.sim.telltale(r.t, 'DRIVER_ATTENTION', true, false));
    r.answer(true);
    r.take(r.sim.actionResult(r.t, 'Ventilate', true, null));
    r.take(r.sim.actionResult(r.t, 'AddRestStop', true, null));
    r.take(r.sim.actionResult(r.t, 'CoolDown', true, null));
    r.tickUntil((e) => e.type === 'Execute' && e.revert, 35, '졸음 개입 원복');
    r.take(r.sim.actionResult(r.t, 'Revert', true, null));
    if (recirc) r.take(r.sim.recirc(r.t, true));
    r.take(r.sim.restProximity(r.t, true, false));
    r.advance(500);
    return [r, r.take(r.sim.gear(r.t, 'PARK'))];
  };
  const [a, parkA] = toRest(true);
  assert.ok(parkA.find(isType('Propose')));
  const no = a.take(a.sim.answer(a.t + 1000, false));
  assert.deepEqual(no.map((e) => [e.type, e.text]), [['Done', null]]);
  const [, parkB] = toRest(false);
  assert.ok(parkB.find(isType('RestStarted')));
  assert.ok(!parkB.find(isType('Propose')), '외기로 쉬면 묻지 않는다');
});

test('4명 KEEP 은 맡겨도 Propose (원복 없는 변형은 항상 묻는다)', () => {
  const r = new Rig(true);
  r.sim.setTrust(true, 0, 0);
  r.start(4);
  r.take(r.sim.recirc(r.t, true));
  const p = r.tickUntil((e) => e.type === 'Announce' || e.type === 'Propose', 15, '4명 환기');
  assert.equal(p.type, 'Propose');
  assert.equal(r.sim.lastProposalVariant, 'KEEP');
  assert.match(p.text, /바깥 공기로 바꿔 둘까요\?$/);
});

test('조수석: Propose.passengerMayAnswer 와 첫 제안의 한마디', () => {
  const r = new Rig(true);
  r.start(2);
  r.take(r.sim.frontPassenger(r.t, true));
  r.take(r.sim.recirc(r.t, true));
  const p = r.tickUntil(isType('Propose'), 20, '환기 제안');
  assert.equal(p.passengerMayAnswer, true);
  assert.ok(p.text.endsWith(' 옆자리에서 눌러 주셔도 돼요.'), p.text);
});

test('맡기기 권유: 연속 3번째 동의 뒤 도착 카드에 trustOffer, TrustAnswer 로 켜진다', () => {
  const r = new Rig(true);
  r.sim.setTrust(false, 2, 0);
  r.start(1);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Propose'), 30, '환기 제안');
  r.answer(true);
  r.ventilate();
  r.tickUntil((e) => e.type === 'Execute' && e.revert, 25, '원복');
  const rv = r.revert();
  assert.equal(rv.find(isType('TrustChanged')).state.consecutiveYes, 3);
  r.tick();
  const card = r.take(r.sim.gear(r.t, 'PARK')).find(isType('ArrivalCard')).card;
  assert.equal(card.trustOffer, "오늘까지 환기 제안 3번에 모두 '응' 하셨어요. 다음부터 2분 환기는 말만 하고 바꿀까요?");
  const tc = r.take(r.sim.trustAnswer(r.t, true)).find(isType('TrustChanged'));
  assert.equal(tc.state.delegated, true);
  assert.equal(trustOf(r).delegated, true);

  const plain = new Rig(true);
  plain.start(1);
  plain.tick();
  assert.equal(plain.take(plain.sim.gear(plain.t, 'PARK')).find(isType('ArrivalCard')).card.trustOffer, null);
  assert.deepEqual(plain.take(plain.sim.appVisibility(plain.t, false)), []);
});

// ---------------------------------------------------------------------------
// v0.6.1: 4차 리뷰 재현

test('리뷰 재현: 맡겼어도 화면이 가려져 있으면 예고 대신 묻는다', () => {
  const r = new Rig(true);
  r.sim.setTrust(true, 0, 0);
  r.start(1);
  r.take(r.sim.appVisibility(r.t, false));
  r.take(r.sim.recirc(r.t, true));
  const first = r.tickUntil((e) => e.type === 'Announce' || e.type === 'Propose', 30, '환기');
  assert.equal(first.type, 'Propose', '화면 밖이면 취소할 길이 없으니 묻는다');
  assert.match(first.text, /20초만 바깥 공기로 바꿀까요\?$/);
  // 답이 없으면 차를 건드리지 않고 넘어간다. 거절로 세지 않는다
  r.tickUntil(isType('ProposalExpired'), 30, '화면 밖 만료');
  assert.ok(!r.events.find(isType('Execute')), '실행 없음');
  assert.deepEqual(trustOf(r), { delegated: true, consecutiveYes: 0, cancelCount: 0 });
  // 화면이 다시 보이면 다음은 예고다
  r.take(r.sim.appVisibility(r.t, true));
  const next = r.tickUntil((e) => e.type === 'Announce' || e.type === 'Propose', 30, '다음 환기');
  assert.equal(next.type, 'Announce');
});

test('리뷰 재현: 예고 중 차 자동 공조가 켜지면 실행하지 않고 조용히 닫는다', () => {
  const r = new Rig(true);
  r.sim.setTrust(true, 0, 0);
  r.start(1);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Announce'), 30, '예고');
  r.advance(READ_MS);
  r.take(r.sim.speechFinished(r.t));
  r.take(r.sim.autoClimate(r.t, true));
  const closed = r.tickUntil(isType('Done'), 8, '예고 닫힘');
  assert.equal(closed.text, null, '말없이 닫는다');
  assert.ok(!r.events.find(isType('Execute')), '차를 건드리지 않는다');
  assert.equal(r.sim.phase, 'IDLE');
  assert.deepEqual(trustOf(r), { delegated: true, consecutiveYes: 0, cancelCount: 0 }, '취소로 세지 않는다');
  assert.ok(!r.events.find(isType('TrustChanged')));
});

test('리뷰 재현: 예고를 읽지 못했으면(너무 이른 SpeechFinished) 실행하지 않고 묻는다', () => {
  const r = new Rig(true);
  r.sim.setTrust(true, 0, 0);
  r.start(1);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Announce'), 30, '예고');
  r.advance(900);
  const p = r.take(r.sim.speechFinished(r.t)).find(isType('Propose'));
  assert.ok(p, '0.9초 만에 끝난 예고는 읽지 못한 것으로 보고 묻는다');
  assert.equal(r.sim.phase, 'AWAITING_ANSWER');
});

test('리뷰 재현: 받은 지 10분이 지난 도착 예정은 쓰지 않는다', () => {
  const r = new Rig(false);
  r.start(1);
  r.take(r.sim.destinationEta(r.t, 3));
  assert.equal(r.sim.arrivingSoon, true);
  for (let i = 0; i < 599; i++) r.tick();
  assert.equal(r.sim.arrivingSoon, true, '10분 안에는 쓴다');
  for (let i = 0; i < 3; i++) r.tick();
  assert.equal(r.sim.arrivingSoon, false, '10분이 지나면 낡은 값이다');
  // 새 주행을 시작하면 지난 주행의 값은 버린다
  r.take(r.sim.destinationEta(r.t, 3));
  r.take(r.sim.gear(r.t, 'PARK'));
  assert.equal(r.sim.arrivingSoon, false);
});

test('예고로 시작한 실행 중 취소: 바로 원복하고 "알겠어요. 원래대로 돌렸어요."', () => {
  const r = new Rig(true);
  r.sim.setTrust(true, 0, 0);
  r.start(1);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Announce'), 30, '예고');
  r.advance(READ_MS);
  r.take(r.sim.speechFinished(r.t));
  r.tickUntil(isType('Execute'), 8, '취소 창 뒤 Execute');
  assert.ok(r.ventilate().find(isType('Running')));
  assert.equal(r.sim.cancellableRun, true);
  r.tick(); r.tick();
  const ev = r.take(r.sim.answer(r.t, false));
  assert.ok(ev.find((e) => e.type === 'Execute' && e.revert), '바로 원복');
  assert.deepEqual(ev.find(isType('TrustChanged')).state, { delegated: true, consecutiveYes: 0, cancelCount: 1 });
  assert.equal(r.revert().find(isType('Done')).text, '알겠어요. 원래대로 돌렸어요.');
  assert.equal(r.sim.cancellableRun, false);
});

test('조용 모드에서 한도 초과: overLimitWhileQuiet, 직접 요청한 환기는 Execute → Running → Revert', () => {
  const r = new Rig(true);
  r.start(4);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Propose'), 30, '첫 환기 제안');
  r.answer(false);
  r.tickUntil(isType('Propose'), 40, '두 번째 환기 제안');
  assert.ok(r.answer(false).find(isType('Quiet')));
  assert.equal(r.sim.quietMode, true);
  assert.equal(r.sim.overLimitWhileQuiet, true);
  // 조용해진 뒤 5초(데모)가 지나면 한도의 2배를 넘었다고 한 번만 알린다
  const notice = r.tickUntil(isType('Quiet'), 30, '한도 2배 알림');
  assert.match(notice.text, /^차 안 공기가 많이 탁해요\(추정 [\d,]+ppm\)\. 화면에서 바깥 공기로 바꿀 수 있어요\.$/);
  for (let i = 0; i < 30; i++) r.tick();
  assert.equal(r.events.filter(isType('Quiet')).length, 2, '주행당 한 번');
  const ex = r.take(r.sim.requestVentilation(r.t)).find(isType('Execute'));
  assert.deepEqual(ex.actions, [{ type: 'Ventilate', fanDelta: 1 }]);
  assert.ok(r.ventilate().find(isType('Running')));
  assert.equal(r.sim.overLimitWhileQuiet, false, '바깥 공기가 들어오는 동안에는 버튼을 숨긴다');
  r.tickUntil((e) => e.type === 'Execute' && e.revert, 25, '원복 Execute');
  assert.match(r.revert().find(isType('Done')).text, /원래대로 돌렸어요\.$/);
  // 주행 중이 아니면 아무것도 하지 않는다
  r.take(r.sim.gear(r.t, 'PARK'));
  assert.deepEqual(r.take(r.sim.requestVentilation(r.t)), []);
});

test('setTrust 로 맡기기를 끄면 떠 있던 예고를 닫는 Done 이 돌아온다', () => {
  const r = new Rig(true);
  assert.deepEqual(r.take(r.sim.setTrust(true, 0, 0)), []);
  r.start(1);
  r.take(r.sim.recirc(r.t, true));
  r.tickUntil(isType('Announce'), 30, '예고');
  const ev = r.take(r.sim.setTrust(false, 0, 0));
  assert.deepEqual(ev.map((e) => [e.type, e.text]), [['Done', null]]);
  assert.equal(r.sim.phase, 'IDLE');
  assert.equal(r.tickUntil((e) => e.type === 'Announce' || e.type === 'Propose', 10, '다시 물음').type, 'Propose');
});

// ---------------------------------------------------------------------------

let failed = 0;
for (const [name, fn] of tests) {
  try {
    fn();
    console.log('  통과  ' + name);
  } catch (e) {
    failed++;
    console.log('  실패  ' + name + '\n        ' + String(e && e.message).split('\n').join('\n        '));
  }
}
console.log(`\n스모크 테스트 ${tests.length - failed}/${tests.length} 통과 (${path.relative(process.cwd(), enginePath)})`);
process.exit(failed ? 1 : 0);

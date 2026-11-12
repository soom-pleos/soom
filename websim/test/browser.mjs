// 브라우저 확인(선택): 헤드리스 Chrome 으로 dist/kkaeugi-sim.html 을 열어 두 자동 시연을 끝까지 돌리고 스크린샷을 남긴다.
// 실행: node websim/test/browser.mjs [--ts=1] [--extra]
//   --ts=N   시험용 시간 배율(기본 1 = 실제 속도로 자동 시연 약 2분, 맡기기 시연 약 40초)
//   --extra  영어 화면, 좁은 화면, 손으로 조작하는 시나리오, 움직임 줄이기까지 확인
// 스크린샷(websim/dist):
//   shot-a-first.png      처음 연 화면
//   shot-b-four.png       4명 태우고 2,000 근처, 안개 짙음, 헤드유닛에 제안
//   shot-c-fresh.png      외기 전환 직후 바람 줄기
//   shot-d-rest.png       쉼 카드와 쉼 질문
//   shot-e-arrival.png    도착 카드와 맡기기 질문
//   shot-f-narrow.png     폭 420px 세로 배치 (--extra)
//   shot-g-en.png         영어 화면 (--extra)
//   shot-h-announce.png   예고 카드(맡기기 시연)
// 그 밖의 중간 화면은 websim/build/shots 에 남긴다.
// playwright 와 Chrome 이 깔려 있어야 한다. 없으면 건너뛴다(종료 코드 0, "건너뜀" 출력).
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
const websim = path.resolve(here, '..');
const html = path.join(websim, 'dist/kkaeugi-sim.html');
const args = process.argv.slice(2);
const ts = Number((args.find((a) => a.startsWith('--ts=')) || '--ts=1').slice(5)) || 1;
const extra = args.includes('--extra');

const require = createRequire(import.meta.url);
let chromium = null;
for (const id of ['playwright', 'playwright-core', '/opt/npm-tools/node_modules/playwright']) {
  try { chromium = require(id).chromium; break; } catch { /* 다음 후보 */ }
}
const chrome = [process.env.CHROME_BIN, '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'].find((p) => p && fs.existsSync(p));
if (!chromium || !chrome) {
  console.log('건너뜀: playwright 또는 Chrome 을 찾지 못했습니다.');
  process.exit(0);
}
if (!fs.existsSync(html)) {
  console.error('먼저 websim/build.sh 로 dist/kkaeugi-sim.html 을 만드세요.');
  process.exit(2);
}

const distShots = path.join(websim, 'dist');
const extraShots = path.join(websim, 'build/shots');
fs.mkdirSync(extraShots, { recursive: true });
const shot = (page, name, opts = {}) => page.screenshot({ path: path.join(name.startsWith('shot-') ? distShots : extraShots, name + '.png'), ...opts });

const browser = await chromium.launch({ executablePath: chrome, args: ['--no-sandbox', '--disable-background-networking', '--disable-component-update', '--no-first-run'] });
const problems = [];
const requests = [];

async function open(query, viewport, more = {}) {
  const page = await browser.newPage({ viewport, deviceScaleFactor: 1, ...more });
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') problems.push('console.error: ' + m.text()); });
  page.on('request', (r) => { if (!r.url().startsWith('data:')) requests.push(r.url()); });
  await page.goto(pathToFileURL(html).href + query);
  await page.waitForFunction(() => window.__kkaeugi && window.__kkaeugi.state().tripState === 'IDLE');
  return page;
}
const state = (page) => page.evaluate(() => window.__kkaeugi.state());
const until = (page, fn, sec, what) => page.waitForFunction(fn, null, { timeout: (sec * 1000) / ts + 5000 })
  .catch(() => { throw new Error('기다리던 상태가 오지 않음: ' + what); });
const readOut = () => (k) => { const p = window.__kkaeugi.state().ui.proposal; return p && p.kind === k && p.countdownFrom != null; };
/** 보이는 버튼 가운데 40px 보다 낮거나 좁은 것(헤드유닛 화면 안은 앱 치수라 뺀다) */
const smallButtons = (page) => page.evaluate(() => [...document.querySelectorAll('.page button')]
  .filter((b) => b.offsetParent && !b.closest('#car'))
  .map((b) => { const r = b.getBoundingClientRect(); return { id: b.id || b.dataset.seat || b.textContent.trim().slice(0, 12), h: Math.round(r.height), w: Math.round(r.width) }; })
  .filter((b) => b.h < 40 || b.w < 40));
const fogOpacity = (page) => page.evaluate(() => +document.getElementById('pFog').getAttribute('fill-opacity'));
const windLines = (page) => page.evaluate(() => document.querySelectorAll('#pWind path').length);

let failed = false;
try {
  // ---------- 1. 처음 연 화면, 그리고 자동 시연 끝까지 ----------
  const page = await open(`?ts=${ts}`, { width: 1440, height: 900 });
  assert.equal(await page.textContent('#planFoot'), '뒷좌석을 눌러 보세요', '처음 열면 평면도 아래 힌트 하나');
  assert.equal(await fogOpacity(page), 0, '처음에는 안개 없음');
  await shot(page, 'shot-a-first');
  // 가로 배치: 평면도가 뷰포트 높이의 55~65%, 오른쪽 열과 버튼 띠까지 한 화면
  const fold = await page.evaluate(() => {
    const r = (el) => el.getBoundingClientRect();
    const plan = r(document.getElementById('plan')), strip = r(document.getElementById('strip')), hu = r(document.querySelector('.hu-col'));
    return { plan: plan.height / window.innerHeight, stripBottom: strip.bottom, huBottom: hu.bottom, huWidth: document.getElementById('screenWrap').clientWidth, vh: window.innerHeight, scroll: document.documentElement.scrollHeight,
      small: document.getElementById('car').classList.contains('s'), sdk: document.getElementById('sdkLog').textContent, hv: document.getElementById('hvT').textContent, steer: document.getElementById('btnSteer').hidden };
  });
  assert.ok(fold.plan >= 0.55 && fold.plan <= 0.65, '평면도 높이가 뷰포트의 55~65%: ' + fold.plan.toFixed(3));
  assert.ok(fold.stripBottom <= fold.vh && fold.huBottom <= fold.vh && fold.scroll <= fold.vh, `오른쪽 열과 버튼 띠가 한 화면에: 띠 ${fold.stripBottom}, 열 ${fold.huBottom}, 문서 ${fold.scroll}`);
  assert.equal(fold.small, fold.huWidth < 560, '헤드유닛 폭이 560px 미만이면 논리 크기 960');
  assert.equal(fold.sdk, '앱이 차를 제어하면 여기에 호출이 쌓여요', '제어 기록이 비어 있으면 안내 한 줄만');
  assert.equal(fold.hv, '외기', '외기 상태에서는 공조 버튼 글자가 외기');
  assert.equal(fold.steer, true, '핸들 급보정 버튼은 ?steer=1 일 때만');
  assert.ok(!(await page.evaluate(() => document.getElementById('car').textContent.includes('\u00B7'))), '헤드유닛 안에 가운뎃점이 없다');
  // 처음 화면의 글: 제목 아래 한 문장 말고 상시 설명문이 없다
  const words = await page.evaluate(() => {
    const vis = (el) => el && el.offsetParent !== null && getComputedStyle(el).visibility !== 'hidden';
    return { tip: vis(document.getElementById('speedNote')), ev: document.getElementById('evBox').open };
  });
  assert.equal(words.tip, false, '속도 설명은 마우스를 올리기 전에는 숨어 있다');
  assert.equal(words.ev, false, '엔진 기록은 접혀 있다');
  await page.hover('#segSpeed');
  assert.ok(await page.isVisible('#speedNote'), '속도 토글에 마우스를 올리면 설명 한 줄');
  await page.mouse.move(5, 5);

  const t0 = Date.now();
  await page.click('#btnDemo');
  assert.match(await page.textContent('#planFoot'), /^운전자 혼자 탔습니다\./, '자동 시연 중에는 평면도 아래에 지금 단계 한 줄');

  // 첫 제안: 혼자라서 "20초만(실제 2분만)" 바꾸고 원복
  await page.waitForFunction(readOut(), 'STALE_AIR', { timeout: 60000 / ts + 5000 });
  let s = await state(page);
  assert.match(s.ui.proposal.text, /^차 안 공기가 탁해진 것 같아요\(추정 2,[01]00ppm\)\. 20초만 바깥 공기로 바꿀까요\?$/);
  await shot(page, '02-first-proposal');

  // 앱이 외기로 바꾸면 평면도의 공조 버튼이 파랗게 한 번, 그리고 바람 줄기
  await page.waitForFunction(() => document.querySelector('#hvRecirc.flash, [data-ctl="fan"].flash'), null, { timeout: 30000 / ts + 5000 })
    .catch(() => { throw new Error('앱 제어 때 공조 버튼 반짝임이 없음'); });
  await until(page, () => { const s = window.__kkaeugi.state(); return s.ui.running && s.ui.running.remainingSec > 0 && s.ui.air && s.ui.air.ventilating && s.car.fan === 4; }, 30, '환기 실행 중');
  s = await state(page);
  assert.equal(s.car.recirc, false, '앱이 외기로 바꿨다');
  assert.equal(await page.textContent('#hvT'), '외기', '공조 버튼 글자가 외기로 바뀐다');
  assert.ok((await windLines(page)) >= 3, '외기 + 풍량 4: 바람 줄기 3가닥 이상');
  assert.ok(await page.evaluate(() => document.getElementById('pRecirc').style.display === 'none'), '외기면 순환 화살표는 없다');
  assert.ok((await page.evaluate(() => document.querySelectorAll('#sdkLog li:not(.empty)').length)) >= 2, '오른쪽 기록에 호출이 한 줄씩');
  await page.waitForTimeout(700 / ts);
  await shot(page, 'shot-c-fresh');

  await until(page, () => { const s = window.__kkaeugi.state(); return !s.ui.running && s.phase === 'IDLE' && s.sdk >= 4; }, 40, '원복');
  s = await state(page);
  assert.equal(s.car.recirc, true, '원복: 내기순환이 실행 전 값으로');
  assert.equal(await page.textContent('#hvT'), '내기순환', '공조 버튼 글자가 내기순환으로 돌아온다');
  assert.equal(s.car.fan, 3, '원복: 풍량이 실행 전 값으로');
  assert.match(s.ui.lastSaid, /^추정 [\d,]+ppm까지 내려갔어요\. 원래대로 돌렸어요\.$/);

  // 뒷좌석 3명 → 이번에는 "바꿔 둘까요?". 안개가 짙다
  await page.waitForFunction(readOut(), 'STALE_AIR', { timeout: 60000 / ts + 5000 });
  s = await state(page);
  assert.match(s.ui.proposal.text, /^4명이 타서 공기가 빨리 탁해져요\(추정 [\d,]+ppm\)\. 바깥 공기로 바꿔 둘까요\?$/);
  assert.deepEqual(s.car.seats, [true, false, true, true, true]);
  assert.ok((await fogOpacity(page)) >= 0.45, '2,000 근처: 안개가 짙다 ' + (await fogOpacity(page)));
  await shot(page, 'shot-b-four');
  await until(page, () => { const s = window.__kkaeugi.state(); return s.ui.lastSaid === '바깥 공기로 둘게요.' && !s.ui.running && s.phase === 'IDLE'; }, 30, '바깥 공기로 두기');
  s = await state(page);
  assert.equal(s.car.recirc, false, '외기로 남는다');
  assert.equal(s.car.fan, 4);
  await page.waitForTimeout(1500 / ts);
  await shot(page, '05-kept');

  // 야간 장거리: 한도선이 1,600 으로(헤드유닛과 평면도 둘 다)
  await until(page, () => window.__kkaeugi.state().car.lowBeam && document.getElementById('gLimit').textContent === '한도 1,600' && document.getElementById('limText').textContent === '한도 1,600', 30, '야간 한도 1,600');

  await page.waitForFunction(readOut(), 'DROWSY', { timeout: 80000 / ts + 5000 });
  s = await state(page);
  // 창문은 기본으로 건드리지 않는다
  assert.equal(s.ui.proposal.text, '졸음 경고가 떴어요. 찬 바람 넣고, 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?');
  assert.equal(s.car.recirc, true, '졸음 제안 전에 운전자가 내기순환으로 돌려놓았다');
  await shot(page, '06-drowsy');

  await until(page, () => { const s = window.__kkaeugi.state(); return s.car.tracking && s.car.temp === 22 && s.ui.running && s.ui.running.remainingSec > 0; }, 30, '졸음 개입 실행');
  s = await state(page);
  assert.equal(s.car.window, 0, '창문은 그대로');
  assert.equal(s.car.recirc, false);
  assert.equal(s.ui.running.label, '찬 바람 넣는 중');
  await shot(page, '07-drowsy-running');

  // 쉼 카드: 내기순환으로 쉬게 되어 바깥 공기 질문이 카드 안 아래쪽에 뜬다
  await until(page, () => { const r = window.__kkaeugi.state().ui.rest; return r && r.ask && r.ask.countdownFrom != null; }, 60, '쉼 카드와 쉼 질문');
  await page.waitForTimeout(300 / ts);
  await shot(page, 'shot-d-rest');
  assert.equal(await page.textContent('#rTitle'), '석적 졸음쉼터. 15초만 쉬고 가세요(데모)');
  assert.equal(await page.textContent('#rQ'), '쉬는 동안 바깥 공기로 둘까요?');
  assert.equal(await page.textContent('.r-note'), '깨우기는 사람이 내쉰 숨(이산화탄소)만 추정해요. 배기가스(일산화탄소)는 알 수 없어요.');
  const restBox = await page.evaluate(() => {
    const r = (id) => document.getElementById(id).getBoundingClientRect(), n = document.querySelector('.r-note').getBoundingClientRect(), w = r('screenWrap');
    return { askBelowCount: r('rAsk').top >= r('rCount').bottom - 1, noteBelowAsk: n.top >= r('rAsk').bottom - 1, noteInside: n.bottom <= w.bottom + 1, yesH: r('rYes').height };
  });
  assert.ok(restBox.askBelowCount && restBox.noteBelowAsk && restBox.noteInside, '쉼 질문은 카드 안 아래쪽, 그 아래 고정 문구: ' + JSON.stringify(restBox));
  assert.ok(restBox.yesH >= 40, '쉼 질문 버튼 40px 이상: ' + restBox.yesH);
  assert.equal(await windLines(page), 0, '쉬는 동안에는 바람 줄기를 그리지 않는다');
  s = await state(page);
  assert.equal(s.car.recirc, true, '쉼에 들어갈 때 졸음 개입은 원복됐다');
  assert.equal(s.car.temp, 24);
  const ppmResting = s.ppm;
  await until(page, () => { const s = window.__kkaeugi.state(); return s.ui.rest && !s.ui.rest.ask && s.car.recirc === false; }, 20, '쉼 질문 동의 뒤 외기');
  await until(page, () => { const s = window.__kkaeugi.state(); return !s.ui.rest && s.car.recirc === true && s.car.fan === 4; }, 30, '쉼이 끝나면 공조 원복');

  // 도착 카드와 맡기기 질문
  await until(page, () => { const s = window.__kkaeugi.state(); return s.ui.arrival && s.ui.trustAsk === 'ask'; }, 40, '도착 카드의 맡기기 질문');
  await page.waitForTimeout(500 / ts);
  await shot(page, 'shot-e-arrival');
  assert.equal(await page.textContent('#aTrustQ'), "오늘까지 환기 제안 3번에 모두 '응' 하셨어요. 다음부터 2분 환기는 말만 하고 바꿀까요?");
  assert.equal(await page.textContent('#aTrustYes'), '맡길게요');
  assert.equal(await page.textContent('#aTrustNo'), '계속 물어봐 주세요');
  const arrBox = await page.evaluate(() => { const c = document.querySelector('.a-card').getBoundingClientRect(), w = document.getElementById('screenWrap').getBoundingClientRect(); return { fits: c.bottom <= w.bottom + 1 && c.top >= w.top - 1, btn: document.getElementById('aTrustYes').getBoundingClientRect().height }; });
  assert.ok(arrBox.fits, '도착 카드가 헤드유닛 안에 다 들어온다');
  assert.ok(arrBox.btn >= 40, '맡기기 버튼 40px 이상: ' + arrBox.btn);
  assert.equal((await state(page)).trust.delegated, false, '답하기 전에는 맡기지 않았다');
  await until(page, () => !window.__kkaeugi.state().demo, 20, '자동 시연 끝');
  await page.waitForTimeout(300);
  s = await state(page);
  const seconds = ((Date.now() - t0) / 1000) * ts;
  assert.equal(s.caption, 'd20', '자동 시연이 마지막 단계까지 갔다');
  assert.equal(s.trust.delegated, true, '[맡길게요] 뒤에는 맡기기가 켜진다');
  assert.equal(await page.getAttribute('#swTrust', 'aria-pressed'), 'true', '버튼 띠의 맡기기 표시등이 켜진다');
  assert.equal(await page.textContent('#aTrustQ'), '맡겨 둘게요. 다음부터는 말만 하고 바꿔요.');
  assert.match(s.ui.arrival.headline, /^오늘 환기 3번, 쉼터에서 \d+분 쉬었어요\.$/);
  assert.match(await page.textContent('#aHead'), /^오늘 환기 3번, 쉼터에서 \d+초 쉬었어요\.$/, '60배 데모: 화면에는 초로');
  assert.equal(s.ui.arrival.restStopsVisited, 1);
  assert.ok(ppmResting < 1500, `쉬는 동안 추정치가 멈춰 있었다: ${ppmResting}`);
  assert.equal(s.car.window, 0);
  assert.equal(s.car.temp, 24, '온도가 실행 전 값으로 돌아왔다');
  assert.equal(s.car.recirc, true, '쉼이 끝나고 내기순환으로 돌아왔다');
  assert.equal(s.car.fan, 4);
  const calls = (await page.evaluate(() => window.__kkaeugi.sdkLog())).map((l) => l.call).filter(Boolean);
  assert.deepEqual(calls, [
    // 혼자 "20초만": 외기, 풍량 +1, 그리고 원복
    'setHvacRecirculation(false)', 'setHvacFanSpeed(4)', 'setHvacRecirculation(true)', 'setHvacFanSpeed(3)',
    // 4명 "바꿔 둘까요": 외기, 풍량 +1 (원복 없음)
    'setHvacRecirculation(false)', 'setHvacFanSpeed(4)',
    // 졸음 개입(창문 제외): 외기, 풍량 +1, 경유지, 온도 -2, 그리고 쉼 진입 때 원복
    'setHvacRecirculation(false)', 'setHvacFanSpeed(5)', 'addWaypoint(석적 졸음쉼터)', 'setHvacTemperature(22.0)',
    'setHvacRecirculation(true)', 'setHvacFanSpeed(4)', 'setHvacTemperature(24.0)',
    // 쉬는 동안 바깥 공기(서 있는 동안이라 풍량은 올리지 않는다), 쉼이 끝나면 원복
    'setHvacRecirculation(false)', 'setHvacRecirculation(true)',
  ]);
  assert.ok(!calls.some((c) => c.startsWith('setWindowPosition')), '창문 제어 호출이 없다');
  console.log(`  통과  자동 시연 끝까지 (${seconds.toFixed(0)}초 분량, SDK 호출 ${calls.length}건, 순서 그대로)`);

  // 도착 카드 확인 → 대기 화면
  await page.click('#aOk');
  s = await state(page);
  assert.equal(s.tripState, 'IDLE');
  assert.equal(s.ui.arrival, null);
  // 엔진 기록은 맨 아래 링크로 펼친다
  await page.click('#evSum');
  await page.waitForFunction(() => document.querySelectorAll('#evLog li').length > 10, null, { timeout: 3000 }).catch(() => { throw new Error('엔진 기록이 펼쳐지지 않음'); });
  assert.equal(await page.textContent('#evSum'), '엔진 기록 닫기');
  await page.close();

  // ---------- 1b. 맡기기 시연 끝까지 ----------
  const tp = await open(`?ts=${ts}`, { width: 1440, height: 900 });
  const t1 = Date.now();
  await tp.click('#btnTrust');
  assert.equal(await tp.textContent('#planFoot'), '맡기기를 켜 둔 채 혼자 출발합니다.');
  assert.equal(await tp.getAttribute('#swTrust', 'aria-pressed'), 'true', '맡기기 표시등이 켜진 채 시작');
  const announceUp = () => { const p = window.__kkaeugi.state().ui.proposal; return p && p.announce && p.countdownFrom != null; };
  await until(tp, announceUp, 60, '첫 예고');
  let a = await state(tp);
  assert.equal(a.ui.proposal.text, "20초만 바깥 공기로 바꿀게요. 싫으면 취소를 누르세요.");
  assert.equal(a.ui.proposal.timeoutSec, 5);
  assert.equal(a.boost, 1, '예고와 취소 창은 실제 속도');
  await tp.waitForTimeout(1200 / ts);
  await shot(tp, 'shot-h-announce');
  const ann = await tp.evaluate(() => ({ yes: document.getElementById('pYes').hidden, no: document.getElementById('pNoT').textContent, info: document.getElementById('pInfo').textContent, bar: !document.getElementById('pBar').hidden, h: document.getElementById('pNo').getBoundingClientRect().height }));
  assert.deepEqual([ann.yes, ann.no, ann.bar], [true, '취소', true], '예고 카드: [취소] 하나와 줄어드는 막대');
  assert.match(ann.info, /^[0-5]초 뒤 바꿔요\. 싫으면 취소를 누르세요$/);
  assert.ok(ann.h >= 40, '취소 버튼 40px 이상: ' + ann.h);
  // 답이 없으면 5초 뒤 스스로 실행
  await until(tp, () => { const s = window.__kkaeugi.state(); return s.ui.running && s.ui.running.remainingSec > 0 && s.car.recirc === false; }, 20, '예고 뒤 스스로 실행');
  await until(tp, () => { const s = window.__kkaeugi.state(); return !s.ui.running && s.phase === 'IDLE' && s.car.recirc === true && s.car.fan === 3; }, 40, '맡긴 환기 원복');
  await until(tp, announceUp, 60, '두 번째 예고');
  await until(tp, () => window.__kkaeugi.state().ui.lastSaid === '알겠어요. 그대로 둘게요.', 15, '예고 취소');
  a = await state(tp);
  assert.equal(a.car.recirc, true, '취소하면 차는 그대로');
  assert.deepEqual(a.trust, { delegated: true, consecutiveYes: 0, cancelCount: 1 }, '취소 1회, 맡기기는 아직 켜져 있다');
  await until(tp, () => !window.__kkaeugi.state().demo, 20, '맡기기 시연 끝');
  a = await state(tp);
  const trustSeconds = ((Date.now() - t1) / 1000) * ts;
  assert.equal(a.caption, 't8', '맡기기 시연이 마지막 단계까지 갔다');
  assert.equal(a.boost, 1, '끝나면 빨리 감기를 푼다');
  const tcalls = (await tp.evaluate(() => window.__kkaeugi.sdkLog())).map((l) => l.call).filter(Boolean);
  assert.deepEqual(tcalls, ['setHvacRecirculation(false)', 'setHvacFanSpeed(4)', 'setHvacRecirculation(true)', 'setHvacFanSpeed(3)']);
  assert.ok(trustSeconds < 60, `맡기기 시연은 1분 안쪽: ${trustSeconds.toFixed(0)}초`);
  await tp.close();
  console.log(`  통과  맡기기 시연 끝까지 (${trustSeconds.toFixed(0)}초 분량, 예고 → 스스로 실행 → 원복 → 예고 → 취소)`);

  if (extra) {
    // ---------- 2. 영어 화면 ----------
    const en = await open(`?ts=${Math.max(ts, 4)}&lang=en`, { width: 1440, height: 900 });
    assert.equal(await en.textContent('#planFoot'), 'Try clicking a rear seat');
    await en.click('#btnDemo');
    await en.waitForFunction(readOut(), 'STALE_AIR', { timeout: 40000 });
    await en.waitForTimeout(200);
    assert.match(await en.textContent('#pEn'), /^Cabin air seems stale \(est\. 2,[01]00 ppm\)\. Switch to outside air for 20 seconds\?$/);
    await en.waitForFunction((k) => { const p = window.__kkaeugi.state().ui.proposal; return p && p.kind === k && /바꿔 둘까요/.test(p.text) && p.countdownFrom != null; }, 'STALE_AIR', { timeout: 60000 });
    assert.match(await en.textContent('#pEn'), /^4 people aboard, cabin air is getting stale fast \(est\. [\d,]+ ppm\)\. Switch to outside air and leave it there\?$/);
    await en.waitForTimeout(200);
    await shot(en, 'shot-g-en');
    // 헤드유닛 맨 위 한국어 문장의 풀이는 헤드유닛 아래 한 줄로
    await until(en, () => document.getElementById('subtitle').textContent === 'Leaving it on outside air.', 30, '영어 풀이 한 줄');
    await en.waitForFunction(readOut(), 'DROWSY', { timeout: 60000 });
    assert.equal(await en.textContent('#pEn'), 'A drowsiness warning came on. Cool air, plus a stop at Seokjeok rest stop (Gyeongbu Expressway), 12 km ahead?');
    await shot(en, '11-en-drowsy');
    await until(en, () => { const r = window.__kkaeugi.state().ui.rest; return r && r.ask; }, 60, '영어 화면 쉼 질문');
    await en.waitForTimeout(200);
    assert.equal(await en.textContent('#rQEn'), 'Keep outside air on while you rest?');
    assert.equal(await en.textContent('.r-note'), 'Kkaeugi only estimates the breath people exhale (carbon dioxide). It cannot detect exhaust gas (carbon monoxide).');
    await shot(en, '12-en-rest');
    await until(en, () => { const s = window.__kkaeugi.state(); return s.ui.arrival && s.ui.trustAsk === 'ask'; }, 150, '영어 화면 도착 카드');
    await en.waitForTimeout(300);
    assert.match(await en.textContent('#aHeadEn'), /^Today: aired out 3 times and rested \d+ s at a rest stop\.$/);
    assert.equal(await en.textContent('#aTrustEn'), "You've said 응 (yes) to all 3 airing suggestions so far. From now on, should the 2-minute airing just be announced and done?");
    await shot(en, '13-en-arrival', { fullPage: true });
    // 영어 풀이가 빠진 한국어 문장이 없는지: 도착 카드 기록 줄마다 풀이가 붙는다
    assert.ok(await en.evaluate(() => [...document.querySelectorAll('#aLines > div')].every((d) => d.querySelector('.en'))), '도착 카드 기록 줄마다 영어 풀이');
    await en.close();
    // 예고, 취소, 조용 모드의 영어 풀이
    const en2 = await open(`?ts=${Math.max(ts, 4)}&lang=en`, { width: 1440, height: 900 });
    await en2.click('#btnTrust');
    await until(en2, () => { const p = window.__kkaeugi.state().ui.proposal; return p && p.announce; }, 60, '영어 화면 예고');
    await en2.waitForTimeout(200);
    assert.equal(await en2.textContent('#pEn'), "Switching to outside air for 20 seconds. Press cancel if you'd rather not.");
    assert.equal(await en2.textContent('#pNoT'), '취소 (Cancel)');
    await shot(en2, '14-en-announce');
    await until(en2, () => document.getElementById('subtitle').textContent === 'Okay. Leaving it as it is.', 120, '영어 화면 예고 취소 풀이');
    await en2.click('#btnReset');
    await en2.evaluate(() => { const u = window.__kkaeugi.user; u.gear('DRIVE'); u.recirc(true); });
    for (let i = 0; i < 2; i++) {
      await en2.waitForFunction(readOut(), 'STALE_AIR', { timeout: 60000 });
      await en2.click('#pNo');
    }
    await until(en2, () => document.getElementById('subtitle').textContent === "I won't ask again on this drive. I'll only show it on screen.", 10, '영어 화면 조용 모드 풀이');
    assert.equal(await en2.textContent('#gLine'), '1 aboard, over the limit (est.). Not asking');
    await en2.close();
    console.log('  통과  영어 화면: 라벨 영어, 엔진 문장은 한국어 그대로, 아래 영어 풀이');

    // ---------- 3. 손으로 조작: 사용자가 바꾼 값은 원복에서 건드리지 않는다 ----------
    const hand = await open(`?ts=${Math.max(ts, 4)}&steer=1`, { width: 1440, height: 900 });
    assert.ok(await hand.isVisible('#btnSteer'), '?steer=1 이면 핸들 급보정 버튼이 보인다');
    await hand.click('.seat[data-seat="1"]');
    assert.equal(await hand.textContent('#planFoot'), '아래에서 기어 D 를 누르면 출발합니다', '좌석을 누르면 다음 힌트');
    await hand.click('#segGear [data-gear="DRIVE"]');
    assert.equal(await hand.textContent('#planFoot'), '', '출발하면 힌트가 사라진다');
    await hand.click('#hvRecirc');
    assert.ok(await hand.evaluate(() => document.getElementById('pRecirc').style.display !== 'none'), '내기순환이면 순환 화살표');
    await hand.waitForFunction(readOut(), 'STALE_AIR', { timeout: 40000 });
    let h = await state(hand);
    assert.match(h.ui.proposal.text, /^2명이 타서 공기가 빨리 탁해져요\(추정 [\d,]+ppm\)\. 20초만 바깥 공기로 바꿀까요\? 옆자리에서 눌러 주셔도 돼요\.$/);
    // 조수석에 사람이 있으면 답 버튼이 헤드유닛 오른쪽으로 간다
    assert.equal(h.ui.proposal.passenger, true);
    const side = await hand.evaluate(() => { const w = document.getElementById('screenWrap').getBoundingClientRect(), y = document.getElementById('pYes').getBoundingClientRect(), n = document.getElementById('pNo').getBoundingClientRect(), t = document.getElementById('pText').getBoundingClientRect(); return { yes: (y.left - w.left) / w.width, no: (n.left - w.left) / w.width, textRight: (t.right - w.left) / w.width, h: Math.min(y.height, n.height) }; });
    assert.ok(side.yes > 0.55 && side.no > 0.55 && side.textRight <= side.yes, '조수석: 답 버튼 둘 다 화면 오른쪽 ' + JSON.stringify(side));
    assert.ok(side.h >= 40, '조수석 배치 버튼 40px 이상');
    await shot(hand, '19-hand-passenger');
    await hand.click('#pYes');
    await until(hand, () => { const s = window.__kkaeugi.state(); return s.ui.running && s.ui.running.remainingSec > 0 && s.car.fan === 4; }, 20, '손 조작 실행 중');
    // 실행 중에 사용자가 풍량을 7 로 올린다
    for (let i = 0; i < 3; i++) await hand.click('#fanUp');
    assert.equal((await state(hand)).car.fan, 7);
    await until(hand, () => { const s = window.__kkaeugi.state(); return !s.ui.running && s.phase === 'IDLE' && s.sdk >= 3; }, 60, '손 조작 원복');
    h = await state(hand);
    assert.equal(h.car.fan, 7, '사용자가 바꾼 풍량은 그대로');
    assert.equal(h.car.recirc, true, '내기순환은 실행 전 값으로');
    await shot(hand, '20-hand-revert', { fullPage: true });
    assert.equal(h.trust.consecutiveYes, 1, '물어서 동의한 20초 환기는 연속 동의로 센다');
    // 창문 버튼: 내리기 10%, 올리기
    await hand.click('#winDn');
    assert.equal((await state(hand)).car.window, 10);
    await hand.click('#winUp');
    assert.equal((await state(hand)).car.window, 0);
    // 발화 게이트: 방향지시등을 켠 채 주의 경고 → 보류, 끄면 제안
    await hand.click('#hvRecirc');
    await hand.click('#turnL');
    await hand.click('#swAttn');
    h = await state(hand);
    assert.equal(h.ui.proposal, null);
    assert.equal(h.ui.deferred, 'turn_signal');
    await until(hand, () => /^지금은 말하지 않고 미룹니다: 방향지시등이 켜져 있음/.test(document.getElementById('subtitle').textContent), 10, '헤드유닛 아래 보류 이유');
    await hand.click('#turnL');
    assert.equal((await state(hand)).car.turn, 'OFF', '깜빡이는 한 번 더 누르면 꺼진다');
    await hand.waitForFunction(readOut(), 'DROWSY', { timeout: 40000 });
    await hand.click('#pNo');
    h = await state(hand);
    assert.equal(h.ui.proposal, null);
    assert.match(h.ui.lastSaid, /^알겠어요\. 20초 뒤에 다시 볼게요\.$/);
    // 핸들 급보정 3회: 트립이 30초(데모) 넘게 흐른 뒤라 제안으로 이어진다. 평면도 핸들이 돌아간다
    await hand.click('#swAttn');
    await hand.click('#btnSteer');
    await hand.waitForFunction(() => /rotate\(12 /.test(document.getElementById('pWheel').getAttribute('transform')), null, { timeout: 40000 });
    await hand.waitForFunction((k) => { const p = window.__kkaeugi.state().ui.proposal; return p && p.kind === k; }, 'DROWSY', { timeout: 60000 });
    h = await state(hand);
    assert.match(h.ui.proposal.text, /^핸들 보정이 잦아졌어요\. /);
    await hand.close();
    console.log('  통과  손 조작: 좌석 힌트, 조수석 버튼 배치, 사용자가 바꾼 풍량 유지, 창문, 발화 보류, 거절, 핸들 급보정(?steer=1)');

    // ---------- 3b. 버튼 띠의 새 버튼: 도착 4분 전, 차 자동 공조, 맡기기, 그리고 조용 모드 ----------
    const v4 = await open(`?ts=${Math.max(ts, 4)}`, { width: 1440, height: 900 });
    const noAsk = async (ms, what) => { await v4.waitForTimeout(ms / Math.max(ts, 4)); const s = await state(v4); assert.equal(s.ui.proposal, null, what); assert.ok(s.ppm > 2000, what + ' (한도는 넘었다 ' + s.ppm + ')'); };
    await v4.evaluate(() => { const u = window.__kkaeugi.user; [2, 3, 4].forEach((i) => u.seat(i, true)); });
    await v4.click('#btnEta');
    assert.equal(await v4.getAttribute('#btnEta', 'aria-pressed'), 'true');
    await v4.click('#segGear [data-gear="DRIVE"]');
    await v4.click('#hvRecirc');
    await noAsk(12000, '도착 4분 전이면 환기 제안 없음');
    assert.equal(await v4.textContent('#gLine'), '곧 도착이라 환기는 묻지 않아요');
    await until(v4, () => /도착 5분 전/.test(document.getElementById('subtitle').textContent), 5, '보류 이유 한 줄');
    await v4.click('#swAuto');
    await v4.click('#btnEta');
    assert.equal(await v4.getAttribute('#btnEta', 'aria-pressed'), 'false', '다시 누르면 목적지 없음');
    await noAsk(6000, '차 자동 공조 중이면 환기 제안 없음');
    assert.equal(await v4.textContent('#gLine'), '차가 내외기를 제어하는 중이라 묻지 않아요');
    await v4.click('#swAuto');
    // 맡기기를 켜도 4명(바꿔 두기)은 묻는다
    await v4.click('#swTrust');
    assert.equal((await state(v4)).trust.delegated, true);
    await v4.waitForFunction(readOut(), 'STALE_AIR', { timeout: 30000 });
    let q = await state(v4);
    assert.equal(q.ui.proposal.announce, false, '4명 바꿔 두기는 맡겨도 묻는다');
    assert.equal(q.variant, 'KEEP');
    await v4.click('#swTrust');
    assert.equal((await state(v4)).trust.delegated, false, '맡기기 버튼을 다시 누르면 꺼진다');
    // 두 번 연속 거절하면 조용 모드: 홈 문구가 바뀌고 더 묻지 않는다
    await v4.click('#pNo');
    await v4.waitForFunction(readOut(), 'STALE_AIR', { timeout: 60000 });
    await v4.click('#pNo');
    q = await state(v4);
    assert.equal(q.quiet, true);
    assert.equal(q.ui.lastSaid, '이번 주행에서는 더 묻지 않을게요. 화면에만 표시할게요.');
    // 4명이라 이미 한도 위다. 묻지 않는 중이라는 말보다 한도 초과를 먼저 보여 주고, 그 아래 "바깥 공기로" 버튼이 뜬다
    assert.equal(await v4.textContent('#gLine'), '4명 탑승, 한도를 넘었어요(추정). 묻지 않는 중이에요', '조용 모드에서 한도 초과의 홈 문구');
    assert.ok(await v4.isVisible('#btnFresh'), '조용 모드에서 한도를 넘으면 바깥 공기로 버튼');
    assert.equal(await v4.textContent('#btnFresh'), '바깥 공기로');
    await shot(v4, '21-quiet');
    await noAsk(30000, '조용 모드에서는 환기 제안 없음');
    // 화면에서 직접 누르면 묻지 않고 바로 환기한다. 실행 중에는 [취소] 가 붙고, 누르면 바로 원래대로
    await v4.click('#btnFresh');
    await until(v4, () => { const s = window.__kkaeugi.state(); return s.ui.running && s.ui.running.remainingSec > 0 && s.car.recirc === false; }, 20, '직접 요청한 환기 실행 중');
    assert.ok(await v4.isVisible('#runCancel'), '직접 요청한 환기는 실행 중에도 취소 버튼');
    assert.ok(!(await v4.isVisible('#btnFresh')), '환기 중에는 바깥 공기로 버튼을 숨긴다');
    await shot(v4, '22-fresh-running');
    await v4.click('#runCancel');
    await until(v4, () => { const s = window.__kkaeugi.state(); return !s.ui.running && s.car.recirc === true && s.ui.lastSaid === '알겠어요. 원래대로 돌렸어요.'; }, 20, '실행 중 취소 뒤 원복');
    // 새로 고치면 맡기기는 꺼져 있다(페이지 메모리에만 둔다)
    await v4.click('#swTrust');
    await v4.reload();
    await v4.waitForFunction(() => window.__kkaeugi && window.__kkaeugi.state().tripState === 'IDLE');
    assert.equal((await state(v4)).trust.delegated, false, '새로고침하면 맡기기는 초기화');
    await v4.close();
    console.log('  통과  버튼 띠: 도착 4분 전, 차 자동 공조, 맡기기 표시등, 조용 모드 한도 초과 문구와 바깥 공기로 버튼, 실행 중 취소');

    // ---------- 4. 좁은 화면(세로 배치) ----------
    const narrow = await open(`?ts=${Math.max(ts, 4)}`, { width: 420, height: 900 });
    const box = await narrow.evaluate(() => {
      const r = (id) => document.getElementById(id).getBoundingClientRect();
      return { plan: r('plan'), bezel: document.querySelector('.bezel').getBoundingClientRect(), strip: r('strip'), vert: document.getElementById('plan').classList.contains('v'), overflow: document.documentElement.scrollWidth - window.innerWidth };
    });
    assert.ok(box.vert, '좁은 화면에서는 평면도를 세로로 돌린다');
    assert.ok(box.bezel.top >= box.plan.bottom && box.strip.top >= box.bezel.bottom, '평면도, 헤드유닛, 버튼 띠 순서');
    assert.ok(box.overflow <= 0, '가로로 넘치지 않는다: ' + box.overflow);
    assert.deepEqual(await smallButtons(narrow), [], '버튼 40px 이상(좁은 화면, 좌석 포함)');
    await narrow.click('.seat[data-seat="3"]');
    assert.equal((await state(narrow)).car.seats[3], true, '좁은 화면에서도 좌석을 눌러 태운다');
    await shot(narrow, 'shot-f-narrow', { fullPage: true });
    // 화면이 작아 화면 안 버튼이 낮아지면 베젤 아래에 같은 버튼이 따로 나온다
    await narrow.evaluate(() => { const u = window.__kkaeugi.user; [1, 2, 4].forEach((i) => u.seat(i, true)); u.gear('DRIVE'); u.recirc(true); });
    await until(narrow, () => window.__kkaeugi.state().ui.proposal, 40, '좁은 화면 제안');
    const bar = await narrow.evaluate(() => { const b = document.getElementById('xYes').getBoundingClientRect(); return { h: b.height, shown: !document.getElementById('actbar').hidden, inBezel: !!document.getElementById('actbar').closest('.bezel') }; });
    assert.ok(bar.shown && bar.h >= 40 && bar.inBezel, '좁은 화면: 베젤 아래 답 버튼 40px 이상');
    await shot(narrow, '31-narrow-proposal', { fullPage: true });
    await narrow.click('#xYes');
    await until(narrow, () => { const s = window.__kkaeugi.state(); return s.car.recirc === false && s.ui.lastSaid === '바깥 공기로 둘게요.'; }, 20, '좁은 화면 실행');
    await narrow.close();
    // 좁은 화면의 예고: 베젤 아래에는 [취소] 하나만
    const narrow2 = await open(`?ts=${Math.max(ts, 4)}`, { width: 420, height: 900 });
    await narrow2.evaluate(() => { const u = window.__kkaeugi.user; u.trust(true); u.gear('DRIVE'); u.recirc(true); });
    await until(narrow2, () => { const p = window.__kkaeugi.state().ui.proposal; return p && p.announce; }, 60, '좁은 화면 예고');
    const bar2 = await narrow2.evaluate(() => ({ shown: !document.getElementById('actbar').hidden, yes: document.getElementById('xYes').hidden, no: document.getElementById('xNo').textContent, h: document.getElementById('xNo').getBoundingClientRect().height }));
    assert.deepEqual([bar2.shown, bar2.yes, bar2.no], [true, true, '취소'], '좁은 화면 예고: 베젤 아래 [취소] 하나');
    assert.ok(bar2.h >= 40);
    await shot(narrow2, '32-narrow-announce', { fullPage: true });
    await narrow2.click('#xNo');
    await until(narrow2, () => window.__kkaeugi.state().ui.lastSaid === '알겠어요. 그대로 둘게요.', 10, '좁은 화면 예고 취소');
    await narrow2.close();
    for (const width of [1280, 1440]) {
      const wide = await open('?ts=6', { width, height: 900 });
      assert.deepEqual(await smallButtons(wide), [], `버튼 40px 이상(폭 ${width})`);
      assert.ok(await wide.evaluate(() => document.getElementById('actbar').hidden), `폭 ${width}: 헤드유닛 안 버튼이 충분히 커서 따로 내지 않는다`);
      // 제안이 떠 있을 때도 화면 안 버튼이 40px 이상이라 베젤 아래 줄이 없다
      await wide.evaluate(() => { const u = window.__kkaeugi.user; [2, 3, 4].forEach((i) => u.seat(i, true)); u.gear('DRIVE'); u.recirc(true); });
      await until(wide, () => window.__kkaeugi.state().ui.proposal, 40, `폭 ${width} 제안`);
      const inScreen = await wide.evaluate(() => ({ bar: document.getElementById('actbar').hidden, h: Math.min(document.getElementById('pYes').getBoundingClientRect().height, document.getElementById('pNo').getBoundingClientRect().height) }));
      assert.ok(inScreen.bar && inScreen.h >= 40, `폭 ${width}: 화면 안 답 버튼 40px 이상 ` + JSON.stringify(inScreen));
      await wide.close();
    }
    console.log('  통과  좁은 화면 세로 배치, 버튼 크기');

    // ---------- 5. 움직임 줄이기 ----------
    const calm = await open(`?ts=${Math.max(ts, 4)}`, { width: 1440, height: 900 }, { reducedMotion: 'reduce' });
    await calm.evaluate(() => { const u = window.__kkaeugi.user; u.gear('DRIVE'); u.fan(6); });
    await until(calm, () => document.querySelectorAll('#pWind path').length > 0, 10, '움직임 줄이기: 바람 줄기');
    const anim = await calm.evaluate(() => ({ wind: getComputedStyle(document.querySelector('#pWind path')).animationName, fog: getComputedStyle(document.getElementById('pFog')).transitionDuration }));
    assert.equal(anim.wind, 'none', '움직임 줄이기: 바람 줄기는 멈춘 그림');
    assert.equal(anim.fog, '0s', '움직임 줄이기: 안개 전환 없음');
    await calm.close();
    console.log('  통과  움직임 줄이기: 바람 줄기와 안개 전환을 끈다');
  }

  assert.deepEqual(problems, [], '스크립트 오류 없음');
  const external = requests.filter((u) => !u.startsWith('file:'));
  assert.deepEqual(external, [], '외부 요청 없음');
  assert.ok(requests.every((u) => u.startsWith(pathToFileURL(html).href)), '페이지 파일 말고는 아무것도 읽지 않는다: ' + [...new Set(requests)].join(', '));
  console.log(`  통과  스크립트 오류 0건, 외부 요청 0건 (문서 요청 ${requests.length}건)`);
} catch (e) {
  failed = true;
  console.log('  실패  ' + String(e && e.message));
  if (problems.length) console.log('        ' + problems.join('\n        '));
} finally {
  await browser.close();
}
console.log(failed ? '\n브라우저 확인 실패' : '\n브라우저 확인 통과');
process.exit(failed ? 1 : 0);

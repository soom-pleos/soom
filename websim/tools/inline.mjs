// 단일 HTML 만들기: template.html 에 CSS, 엔진 JS(Kotlin/JS 산출물), 페이지 JS 를 그대로 넣는다.
// 실행: node websim/tools/inline.mjs   (먼저 gradle :websim:compileProductionExecutableKotlinJs)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const websim = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.resolve(websim, '..');
const read = (p) => fs.readFileSync(path.join(websim, p), 'utf8');

const enginePath = 'build/compileSync/js/main/productionExecutable/kotlin/kkaeugiSim.js';
if (!fs.existsSync(path.join(websim, enginePath))) {
  console.error('엔진 JS 가 없습니다: websim/' + enginePath + '\n먼저 gradle :websim:compileProductionExecutableKotlinJs 를 실행하세요.');
  process.exit(2);
}

let engine = read(enginePath)
  // 소스맵은 같이 배포하지 않는다(개발자 도구가 파일을 찾으러 가지 않게)
  .replace(/^\/\/# sourceMappingURL=.*$/m, '')
  // 표준 라이브러리 주석 안의 출처 주소는 남기되 주소 앞부분(scheme)만 뗀다. 페이지 안에 외부 주소 꼴이 하나도 없게
  .replace(/^(\s*\/\/.*?)https?:\/\//gm, '$1')
  .trimEnd();
const css = read('web/style.css').trimEnd();
const app = read('web/app.js').trimEnd();
const template = read('web/template.html');

// script 안에 닫는 태그 꼴이 있으면 HTML 이 깨진다
const safe = (js) => js.replace(/<\/script/gi, '<\\/script');
const parts = { '/*__CSS__*/': css, '/*__ENGINE__*/': safe(engine), '/*__APP__*/': safe(app) };
let html = template;
for (const [mark, body] of Object.entries(parts)) {
  if (html.split(mark).length !== 2) throw new Error('template.html 에 표시가 정확히 한 번 있어야 합니다: ' + mark);
  html = html.replace(mark, () => body);
}

// 내보내기 전 점검
const problems = [];
const urls = html.match(/https?:\/\/[^\s"'<>)]*/g) || [];
if (urls.length) problems.push('외부 주소 ' + urls.length + '건: ' + [...new Set(urls)].slice(0, 5).join(', '));
if (/[–—]/.test(html)) problems.push('긴 대시가 들어 있음');
for (const word of ['측정', '세계 최초', '유일']) if (html.includes(word)) problems.push('금지 표현: ' + word);
if (/\b(?:src|href)\s*=\s*["'](?!data:|#)/i.test(template)) problems.push('template.html 에 외부 참조(src/href)가 있음');
if (problems.length) {
  console.error('점검 실패:\n  ' + problems.join('\n  '));
  process.exit(1);
}

const out = path.join(websim, 'dist/kkaeugi-sim.html');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, html);
const copy = path.join(repo, 'docs/sim/index.html');
fs.mkdirSync(path.dirname(copy), { recursive: true });
fs.copyFileSync(out, copy);

const kb = (n) => (n / 1024).toFixed(1) + ' KB';
console.log('websim/dist/kkaeugi-sim.html  ' + kb(Buffer.byteLength(html)) +
  '  (엔진 ' + kb(Buffer.byteLength(engine)) + ', 페이지 JS ' + kb(Buffer.byteLength(app)) + ', CSS ' + kb(Buffer.byteLength(css)) + ')');
console.log('docs/sim/index.html           사본');

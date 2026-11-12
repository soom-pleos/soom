#!/usr/bin/env bash
# 브라우저 시뮬레이터 만들기.
#   1) engine/src/main/kotlin 을 Kotlin/JS 로 컴파일 (websim 모듈)
#   2) CSS, 엔진 JS, 페이지 JS 를 template.html 에 넣어 파일 하나로
#   3) websim/dist/kkaeugi-sim.html 로 쓰고 docs/sim/index.html 에 복사
#   4) node 스모크 테스트 (컴파일한 엔진, 그리고 HTML 에 들어간 엔진)
# 사용: websim/build.sh [--browser]
#   --browser  헤드리스 Chrome 으로 자동 시연과 맡기기 시연을 끝까지 확인하고 스크린샷 8장을 websim/dist 에 남긴다(playwright 필요, 약 7분)
# 필요한 것: JDK 17, Node 18 이상, Android SDK 위치(ANDROID_HOME 또는 local.properties. 같은 Gradle 빌드에 :app 이 있어서 필요)
set -euo pipefail
cd "$(dirname "$0")/.."
export LC_ALL="${LC_ALL:-C.UTF-8}"

if [ -n "${GRADLE:-}" ]; then :; elif command -v gradle >/dev/null 2>&1; then GRADLE=gradle; else GRADLE=./gradlew; fi

echo "[1/4] 엔진을 Kotlin/JS 로 컴파일"
"$GRADLE" -q --console=plain :websim:compileProductionExecutableKotlinJs

echo "[2/4] 단일 HTML 로 인라인, [3/4] dist 와 docs/sim 에 복사"
node websim/tools/inline.mjs

echo "[4/4] 스모크 테스트"
node websim/test/smoke.mjs
node websim/test/smoke.mjs websim/dist/kkaeugi-sim.html

if [ "${1:-}" = "--browser" ]; then
  echo "[추가] 브라우저 확인"
  node websim/test/browser.mjs --extra
fi

echo
echo "산출물: websim/dist/kkaeugi-sim.html ($(wc -c < websim/dist/kkaeugi-sim.html) bytes), 사본 docs/sim/index.html"
echo "외부 주소(http://, https://) 개수: $(grep -c 'https\?://' websim/dist/kkaeugi-sim.html || true)"

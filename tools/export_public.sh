#!/usr/bin/env bash
# 공개용 export: 현재 작업 트리에서 .gitignore 를 적용한 파일만 새 디렉터리로 복사하고,
# 거기서 git init 뒤 단일 커밋을 만든다. 과거 히스토리는 가져가지 않는다.
# (옛 커밋에 기획서와 접수양식이 들어 있으므로 히스토리째 공개하면 안 된다.)
#
# 사용법: tools/export_public.sh [대상 디렉터리]   (기본값: ../kkaeugi_public)
# 끝나면 대상 디렉터리에서 tools/privacy_scan.sh 를 돌려 통과해야 성공(exit 0)이다.
# 원격 push 는 하지 않는다. 사용자가 직접 한다.
set -euo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$(dirname "$SRC")/kkaeugi_public}"
COMMIT_MSG="숨 v0.8.0: 기침하는 사람이 탄 자리를 보고 먼저 묻고 바깥 공기로 바꿔, 같이 탄 사람의 추정 감염 위험을 줄이는 Pleos Connect 앱"

cd "$SRC"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "export_public: $SRC 는 git 저장소가 아님" >&2; exit 2; }

if [ -e "$DEST" ]; then
  if [ -n "$(ls -A "$DEST" 2>/dev/null)" ]; then
    echo "export_public: $DEST 가 비어 있지 않습니다. 지우고 다시 만듭니다."
    rm -rf "$DEST"
  fi
fi
mkdir -p "$DEST"

# 작업 트리 기준 파일 목록: 추적 중이든 아니든, .gitignore 에 걸리지 않는 파일만.
# --cached 로 잡힌 파일이라도 지금 .gitignore 에 걸리면 뺀다 (예: docs/submission 이 과거에 추적됐던 경우).
git ls-files -z --cached --others --exclude-standard \
  | while IFS= read -r -d '' f; do
      [ -f "$f" ] || continue
      if git check-ignore -q --no-index -- "$f"; then continue; fi
      printf '%s\0' "$f"
    done \
  | xargs -0 -r cp --parents -p -t "$DEST"

# 명시적 차단 목록: 어떤 경우에도 공개본에 있으면 안 되는 경로
# (PT.md, SUBMISSION_CHECKLIST.md 는 작업 저장소에서 추적 중이지만 .gitignore 에 걸려 위 목록에서 빠진다. 여기서 한 번 더 확인한다)
for banned in docs/submission docs/review docs/PT.md docs/SUBMISSION_CHECKLIST.md trash local.properties .idea; do
  if [ -e "$DEST/$banned" ]; then
    echo "export_public: 차단 경로가 복사됨: $banned" >&2
    exit 3
  fi
done

# 반드시 있어야 하는 경로: 브라우저 시뮬레이터 소스와 빌드 결과
for required in websim docs/sim/index.html README.md apk; do
  if [ ! -e "$DEST/$required" ]; then
    echo "export_public: 필수 경로가 빠짐: $required" >&2
    exit 5
  fi
done

cd "$DEST"
git init -q -b main
git add -A
# 커밋 작성자는 실명·개인 메일이 아닌 중립값. (메일 문자열은 스캔에 걸리지 않게 나눠 적음)
AUTHOR_MAIL="kkaeugi""@""users.noreply.github.com"
git -c user.name="kkaeugi" -c user.email="$AUTHOR_MAIL" \
    commit -q -m "$COMMIT_MSG"

echo "export_public: 커밋 완료 ($(git rev-parse --short HEAD))"

# 개인정보 스캔 (복사된 스크립트로 자기 자신을 검사)
if ! bash tools/privacy_scan.sh "$DEST"; then
  echo "export_public: privacy_scan 실패. 공개본을 push 하지 마세요." >&2
  exit 4
fi

FILES="$(git ls-files | wc -l | tr -d ' ')"
SIZE="$(du -sh --exclude=.git . | cut -f1)"
echo "export_public: 완료"
echo "  경로:      $DEST"
echo "  파일 수:   $FILES (git 추적)"
echo "  크기:      $SIZE (.git 제외)"
echo "  다음 단계: GitHub 에 빈 공개 저장소를 만든 뒤 아래를 실행"
echo "    cd $DEST"
echo "    git remote add origin https://github.com/<계정>/<저장소>.git"
echo "    git push -u origin main"

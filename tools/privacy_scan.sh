#!/usr/bin/env bash
# 개인정보 스캔: git 이 추적하는 텍스트 파일 전체에서
#   - 휴대전화 번호 (010-XXXX-XXXX)
#   - 이메일 주소
#   - 다른 사업 브랜드명 (BRAND 변수, 코드포인트로만 적음. 스크립트 자신은 스캔에서 제외)
#   - 주민등록번호 패턴 (YYMMDD-NXXXXXX)
# 를 찾는다. 0건이면 exit 0, 1건 이상이면 파일:줄 을 출력하고 exit 1.
#
# 사용법: tools/privacy_scan.sh [저장소 경로]   (기본값: 스크립트가 들어 있는 저장소)
set -u

REPO="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO" || { echo "privacy_scan: 저장소 경로를 열 수 없음: $REPO" >&2; exit 2; }

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "privacy_scan: git 저장소가 아님: $REPO" >&2
  exit 2
fi

# 다른 사업 브랜드명은 스크립트 본문에 글자로 적지 않고 유니코드 코드포인트로만 둔다(공개본에 평문이 남지 않게).
BRAND="$(printf '\uc2dd\ube75\uc601\uc5b4')"
[ -n "$BRAND" ] && [ "${#BRAND}" -ge 2 ] || { echo "privacy_scan: 브랜드 패턴을 만들지 못함(bash 4.2 이상 필요)" >&2; exit 2; }
PATTERN="010-[0-9]{4}-[0-9]{4}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|${BRAND}|[0-9]{2}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])-[1-4][0-9]{6}"

# 추적 파일 + 아직 커밋 안 된 비무시 파일. 바이너리는 grep -I 로 건너뛴다. 스크립트 자신은 제외.
# 허용 목록: 공모전 주최 측 접수 메일 주소(개인정보 아님). 한 줄에 하나씩.
ALLOW='appdevchallenge@hyundai-partners\.com'

# Kotlin 의 레이블 붙은 this(예: this@MockSignalSource.gear)는 이메일 꼴이라 걸린다.
# 그 표기를 지운 뒤에도 패턴이 남는 줄만 남긴다(진짜 이메일이 같은 줄에 있으면 그대로 걸린다).
KOTLIN_LABEL='(this|super)@[A-Za-z_][A-Za-z0-9_]*'
RAW_HITS="$(git ls-files -z --cached --others --exclude-standard \
  | grep -zv '^tools/privacy_scan\.sh$' \
  | xargs -0 grep -nIHE "$PATTERN" -- 2>/dev/null \
  | grep -vE "$ALLOW" || true)"
HITS=""
while IFS= read -r line; do
  [ -n "$line" ] || continue
  body="${line#*:*:}"
  if printf '%s\n' "$body" | sed -E "s/$KOTLIN_LABEL//g" | grep -qE "$PATTERN"; then
    HITS="${HITS}${HITS:+
}${line}"
  fi
done <<< "$RAW_HITS"

# Office 문서(docx/xlsx/pptx)는 zip 이라 grep -I 가 건너뛴다. 안의 XML 을 풀어서 같은 패턴을 찾는다.
if command -v unzip >/dev/null 2>&1; then
  while IFS= read -r -d '' f; do
    if unzip -p -- "$f" 2>/dev/null | grep -aoE "$PATTERN" | grep -vE "$ALLOW" | grep -q .; then
      HITS="${HITS}${HITS:+
}${f}:(docx 본문) 패턴 일치"
    fi
  done < <(git ls-files -z --cached --others --exclude-standard -- '*.docx' '*.xlsx' '*.pptx')
fi

# PDF 는 본문이 압축돼 있어 자동 스캔이 안 된다. 추적 중이면 파일명을 알려 수동 확인을 요구한다.
PDFS="$(git ls-files --cached --others --exclude-standard -- '*.pdf' || true)"

if [ -z "$HITS" ]; then
  echo "privacy_scan: 0건 (전화번호, 이메일, 브랜드명, 주민번호 패턴 없음)"
  if [ -n "$PDFS" ]; then
    echo "privacy_scan: 참고. 추적 중인 PDF 는 자동 스캔 대상이 아니라 직접 열어 확인해야 합니다:"
    printf '  %s\n' $PDFS
  fi
  exit 0
fi

echo "privacy_scan: 다음 항목이 걸렸습니다." >&2
printf '%s\n' "$HITS" | cut -c1-200 >&2
echo "privacy_scan: $(printf '%s\n' "$HITS" | wc -l | tr -d ' ')건" >&2
exit 1

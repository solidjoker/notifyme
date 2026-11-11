#!/usr/bin/env bash
# notifyme 本地全量自检（Linux / macOS）
# 用法：
#   bash docs/scripts/local_check.sh
#   bash docs/scripts/local_check.sh --skip-android
set -u
set -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

SKIP_ANDROID=0
SKIP_SERVER=0
for arg in "$@"; do
  case "$arg" in
    --skip-android) SKIP_ANDROID=1 ;;
    --skip-server) SKIP_SERVER=1 ;;
  esac
done

PY=""
for cand in python3 python; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" --version >/dev/null 2>&1; then
    PY="$cand"
    break
  fi
done
if [ -z "$PY" ]; then
  echo "找不到可用的 python 解释器，先装 Python 3 并加入 PATH" >&2
  exit 1
fi
LOG_DIR="${TMPDIR:-/tmp}/notifyme-local-check-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOG_DIR"
FAILED=0

run_step() {
  local name="$1"; shift
  echo ""
  echo "=== [$name] $*"
  if "$@" 2>&1 | tee "$LOG_DIR/$name.log"; then
    :
  else
    FAILED=$((FAILED + 1))
    echo "    [FAIL] 详见 $LOG_DIR/$name.log"
  fi
}

if [ "$SKIP_SERVER" -eq 0 ]; then
  run_step server-tests   "$PY" -m pytest server/ -q
  run_step python-syntax  "$PY" -m compileall -q server
  run_step privacy-guard  "$PY" docs/scripts/privacy_guard.py
  run_step version-check  "$PY" docs/scripts/version_check.py
fi

if [ "$SKIP_ANDROID" -eq 0 ]; then
  run_step android-test-assemble-lint ./gradlew testOpenDebugUnitTest assembleOpenDebug assembleBetaDebug lintOpenDebug --console=plain
  LINT_XML="app/build/reports/lint-results-openDebug.xml"
  if [ -f "$LINT_XML" ]; then
    ISSUES=$(grep -c '<issue' "$LINT_XML" || true)
    ERRORS=$(grep -c 'severity="Error"' "$LINT_XML" || true)
    echo ""
    echo "=== [lint-summary] issues=$ISSUES errors=$ERRORS"
    if [ "$ERRORS" -ne 0 ]; then FAILED=$((FAILED + 1)); fi
  fi
fi

echo ""
echo "================ 本地自检汇总 ================"
if [ "$FAILED" -ne 0 ]; then
  echo "  有 $FAILED 步失败，别开 PR，先修。"
  exit 1
fi
echo "  全部通过，可以开 PR。"
exit 0
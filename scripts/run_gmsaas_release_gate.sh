#!/usr/bin/env bash
set -euo pipefail

# Release gate runner on Genymotion SaaS.
#
# Runs a small, stable suite intended to catch regressions before publishing
# to afteroid/F-Droid.
#
# Usage:
#   scripts/run_gmsaas_release_gate.sh [RECIPE_UUID]
# Env:
#   GMSAAS_RECIPE_UUID, GMSAAS_MAX_RUN_MINUTES, CONNECTBOT_TERMLIB_VERSION
#   CONNECTBOT_GRADLE_TASK (default :app:connectedGoogleDebugAndroidTest)
# Notes:
#   - If you need an unreleased termlib, publish it to mavenLocal and set CONNECTBOT_TERMLIB_VERSION
#     (wired to -PconnectbotTermlibVersion in app/build.gradle.kts).

# Workaround for gmsaas ADB tunnel flakiness:
# - gmadbtunneld uses a fixed local socket name (md5("gmadbtunneld")) under $TMPDIR.
# - Some environments leave a stale socket in /tmp owned by another user, breaking adbconnect.
# Force a per-user TMPDIR so the socket is always created somewhere we can clean up.
if [[ -z "${TMPDIR:-}" ]]; then
  TMPDIR="${HOME}/.Genymobile/gmsaas/tmp_${USER}_gmadbtunneld"
fi
mkdir -p "${TMPDIR}"
export TMPDIR

RECIPE_UUID="${1:-${GMSAAS_RECIPE_UUID:-d212b329-aacd-4fe5-aa76-3480f12a6200}}"
MAX_RUN_MINUTES="${GMSAAS_MAX_RUN_MINUTES:-60}"
TERMLIB_VERSION="${CONNECTBOT_TERMLIB_VERSION:-}"
GRADLE_TASK="${CONNECTBOT_GRADLE_TASK:-:app:connectedGoogleDebugAndroidTest}"

if [[ "${GRADLE_TASK}" != *"connectedGoogle"* ]]; then
  echo "Error: only the google flavor is supported for our release gate (got CONNECTBOT_GRADLE_TASK='${GRADLE_TASK}')." >&2
  echo "Set CONNECTBOT_GRADLE_TASK=':app:connectedGoogleDebugAndroidTest' (or omit it)." >&2
  exit 2
fi

INSTANCE_UUID=""
ADB_SERIAL=""

cleanup() {
  if [[ -n "${INSTANCE_UUID}" ]]; then
    gmsaas instances adbdisconnect "${INSTANCE_UUID}" >/dev/null 2>&1 || true
    gmsaas instances stop "${INSTANCE_UUID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

INSTANCE_NAME="connectbot-releasegate-$(date +%Y%m%d-%H%M%S)"

wait_for_boot() {
  local serial="$1"
  local timeout_seconds="${2:-240}"
  local start
  start="$(date +%s)"

  echo "Waiting for device ${serial} to be ready…"
  adb -s "${serial}" wait-for-device >/dev/null 2>&1 || true

  while true; do
    local state
    state="$(adb -s "${serial}" get-state 2>/dev/null || true)"
    if [[ "${state}" == "device" ]]; then
      local boot_completed
      boot_completed="$(adb -s "${serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
      if [[ "${boot_completed}" == "1" ]]; then
        return 0
      fi
    fi

    if (( $(date +%s) - start > timeout_seconds )); then
      echo "Timed out waiting for device ${serial} to boot." >&2
      adb devices >&2 || true
      return 1
    fi
    sleep 5
  done
}

echo "Starting Genymotion SaaS instance from recipe ${RECIPE_UUID} (${INSTANCE_NAME})…"
START_JSON="$(gmsaas --format compactjson instances start "${RECIPE_UUID}" "${INSTANCE_NAME}" --max-run-duration "${MAX_RUN_MINUTES}")"
INSTANCE_UUID="$(
  python3 - <<'PY' "${START_JSON}"
import json, sys
data = json.loads(sys.argv[1])
inst = data.get("instance")
if isinstance(inst, dict) and inst.get("uuid"):
    print(inst["uuid"])
    raise SystemExit(0)
instances = data.get("instances")
if isinstance(instances, list) and instances and instances[0].get("uuid"):
    print(instances[0]["uuid"])
    raise SystemExit(0)
raise SystemExit("Could not parse instance UUID from gmsaas output")
PY
)"

echo "Connecting ADB to instance ${INSTANCE_UUID}…"
ADB_SERIAL="$(gmsaas instances adbconnect "${INSTANCE_UUID}" | tail -n 1 | tr -d '\r')"
if [[ -z "${ADB_SERIAL}" ]]; then
  ADB_SERIAL="$(adb devices | awk 'NR>1 && $2==\"device\" {print $1}' | head -n 1)"
fi
if [[ -z "${ADB_SERIAL}" ]]; then
  echo "Error: could not determine ADB serial after adbconnect for instance ${INSTANCE_UUID}." >&2
  adb devices >&2 || true
  exit 1
fi

export ANDROID_SERIAL="${ADB_SERIAL}"
echo "Using ADB serial: ${ANDROID_SERIAL}"
wait_for_boot "${ANDROID_SERIAL}"

TERMLIB_ARG=()
if [[ -n "${TERMLIB_VERSION}" ]]; then
  TERMLIB_ARG+=("-PconnectbotTermlibVersion=${TERMLIB_VERSION}")
fi

run_test_class() {
  local test_class="$1"
  echo "Running ${GRADLE_TASK} (class=${test_class})…"
  ./gradlew --no-daemon "${GRADLE_TASK}" "${TERMLIB_ARG[@]}" \
    -Pandroid.testInstrumentationRunnerArguments.class="${test_class}" \
    -Pandroid.testInstrumentationRunnerArguments.connectbot_enable_terminal_clipboard_test=true
}

# Keep this suite intentionally small and deterministic.
run_test_class "org.connectbot.terminal.TerminalClipboardSelectionTest"
run_test_class "org.connectbot.terminal.TerminalLinkTapTest"
run_test_class "org.connectbot.terminal.TerminalReadlineKeybindingsTest"

echo "Release gate passed ✅"

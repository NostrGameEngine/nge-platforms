#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SDK_CANDIDATES=()
if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then SDK_CANDIDATES+=("${ANDROID_SDK_ROOT}"); fi
if [[ -n "${ANDROID_HOME:-}" ]]; then SDK_CANDIDATES+=("${ANDROID_HOME}"); fi
SDK_CANDIDATES+=("$HOME/Android/Sdk" "$HOME/Android")

SDK_DIR=""
for CANDIDATE in "${SDK_CANDIDATES[@]}"; do
  if [[ -x "${CANDIDATE}/emulator/emulator" && -x "${CANDIDATE}/platform-tools/adb" ]]; then
    SDK_DIR="${CANDIDATE}"
    break
  fi
done

if [[ -z "${SDK_DIR}" ]]; then
  echo "Unable to locate Android SDK with emulator+adb. Tried: ${SDK_CANDIDATES[*]}" >&2
  exit 1
fi

EMULATOR_BIN="${SDK_DIR}/emulator/emulator"
ADB_BIN="${SDK_DIR}/platform-tools/adb"
TEST_FILTER="${ANDROID_RTC_TEST_FILTER:-org.ngengine.platform.android.AndroidRTCTransportInstrumentedTest}"
EMULATOR_GPU_MODE="${ANDROID_EMULATOR_GPU_MODE:-auto-no-window}"
EMULATOR_SNAPSHOT_FLAGS=("${ANDROID_EMULATOR_SNAPSHOT_FLAGS:-"-no-snapshot"}")
ANDROID_SIGNAL_BASE="${ANDROID_RTC_SIGNAL_BASE:-}"
ANDROID_WS_URL="${ANDROID_RTC_WS_URL:-}"
ANDROID_HTTP_PARITY_URL="${ANDROID_RTC_HTTP_PARITY_URL:-}"

if [[ ! -x "${EMULATOR_BIN}" ]]; then
  echo "Emulator binary not found: ${EMULATOR_BIN}" >&2
  exit 1
fi
if [[ ! -x "${ADB_BIN}" ]]; then
  echo "adb binary not found: ${ADB_BIN}" >&2
  exit 1
fi

cleanup() {
  if [[ "${STARTED_EMULATOR_BY_HARNESS:-0}" == "1" && -n "${EMULATOR_SERIAL:-}" ]]; then
    echo "Stopping emulator started by harness: ${EMULATOR_SERIAL}" >&2
    "${ADB_BIN}" -s "${EMULATOR_SERIAL}" emu kill >/dev/null 2>&1 || true

    for _ in $(seq 1 30); do
      if ! "${ADB_BIN}" devices | awk '{print $1}' | grep -Fx "${EMULATOR_SERIAL}" >/dev/null 2>&1; then
        break
      fi
      sleep 1
    done

    if [[ -n "${EMULATOR_PID:-}" ]] && kill -0 "${EMULATOR_PID}" >/dev/null 2>&1; then
      kill "${EMULATOR_PID}" >/dev/null 2>&1 || true
      wait "${EMULATOR_PID}" >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup EXIT

echo "Using SDK: ${SDK_DIR}"
mapfile -t AVAILABLE_AVDS < <("${EMULATOR_BIN}" -list-avds)
if [[ -n "${ANDROID_AVD_NAME:-}" ]]; then
  AVD_NAME="${ANDROID_AVD_NAME}"
else
  AVD_NAME=""
  for CANDIDATE in Generic_AOSP Pixel_9a Small_Desktop Medium_Phone_API_36.0; do
    if printf '%s\n' "${AVAILABLE_AVDS[@]}" | grep -Fx "${CANDIDATE}" >/dev/null; then
      AVD_NAME="${CANDIDATE}"
      break
    fi
  done
  if [[ -z "${AVD_NAME}" && ${#AVAILABLE_AVDS[@]} -gt 0 ]]; then
    AVD_NAME="${AVAILABLE_AVDS[0]}"
  fi
fi

if [[ -z "${AVD_NAME}" ]]; then
  echo "No AVDs available on this machine." >&2
  exit 1
fi

echo "Using AVD: ${AVD_NAME}"

printf '%s\n' "${AVAILABLE_AVDS[@]}" | grep -Fx "${AVD_NAME}" >/dev/null || {
  echo "AVD '${AVD_NAME}' not found. Available AVDs:" >&2
  printf '%s\n' "${AVAILABLE_AVDS[@]}" >&2
  exit 1
}

"${ADB_BIN}" start-server

RUNNING_SERIAL="$("${ADB_BIN}" devices | awk '/^emulator-[0-9]+\s+device$/ {print $1; exit}')"
if [[ -n "${RUNNING_SERIAL}" ]]; then
  echo "Reusing running emulator: ${RUNNING_SERIAL}"
  EMULATOR_SERIAL="${RUNNING_SERIAL}"
  STARTED_EMULATOR_BY_HARNESS=0
else
  echo "Starting emulator ${AVD_NAME}..."
  "${EMULATOR_BIN}" \
    -avd "${AVD_NAME}" \
    -no-window \
    -no-boot-anim \
    "${EMULATOR_SNAPSHOT_FLAGS[@]}" \
    -noaudio \
    -gpu "${EMULATOR_GPU_MODE}" \
    -netdelay none \
    -netspeed full \
    >/tmp/nge-android-emulator.log 2>&1 &
  EMULATOR_PID=$!

  for _ in $(seq 1 60); do
    if ! kill -0 "${EMULATOR_PID}" >/dev/null 2>&1; then
      echo "Emulator process exited during startup. See /tmp/nge-android-emulator.log" >&2
      tail -200 /tmp/nge-android-emulator.log >&2 || true
      exit 1
    fi
    EMULATOR_SERIAL="$("${ADB_BIN}" devices | awk '/^emulator-[0-9]+\s+device$/ {print $1; exit}')"
    [[ -n "${EMULATOR_SERIAL}" ]] && break
    sleep 2
  done

  if [[ -z "${EMULATOR_SERIAL:-}" ]]; then
    echo "Failed to detect emulator device. See /tmp/nge-android-emulator.log" >&2
    exit 1
  fi
  STARTED_EMULATOR_BY_HARNESS=1
  echo "Emulator started: ${EMULATOR_SERIAL} (pid=${EMULATOR_PID})"
fi

echo "Waiting for Android boot..."
"${ADB_BIN}" -s "${EMULATOR_SERIAL}" wait-for-device
for _ in $(seq 1 120); do
  if [[ -n "${EMULATOR_PID:-}" ]] && ! kill -0 "${EMULATOR_PID}" >/dev/null 2>&1; then
    echo "Emulator process exited before boot completed. See /tmp/nge-android-emulator.log" >&2
    tail -200 /tmp/nge-android-emulator.log >&2 || true
    exit 1
  fi
  BOOT_COMPLETED="$("${ADB_BIN}" -s "${EMULATOR_SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [[ "${BOOT_COMPLETED}" == "1" ]]; then
    break
  fi
  sleep 2
done

"${ADB_BIN}" -s "${EMULATOR_SERIAL}" shell input keyevent 82 >/dev/null 2>&1 || true

cd "${ROOT_DIR}"
GRADLE_ARGS=(
  :nge-platform-interop-test:android:connectedDebugAndroidTest
  "-Pandroid.testInstrumentationRunnerArguments.class=${TEST_FILTER}"
)
if [[ -n "${ANDROID_SIGNAL_BASE}" ]]; then
  GRADLE_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.signalBase=${ANDROID_SIGNAL_BASE}")
fi
if [[ -n "${ANDROID_WS_URL}" ]]; then
  GRADLE_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.wsUrl=${ANDROID_WS_URL}")
fi
if [[ -n "${ANDROID_HTTP_PARITY_URL}" ]]; then
  GRADLE_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.httpParityUrl=${ANDROID_HTTP_PARITY_URL}")
fi
./gradlew "${GRADLE_ARGS[@]}"

#!/usr/bin/env bash
set -euo pipefail

APP_APK="${1:?usage: verify_external_playback_api34.sh <app-apk> <receiver-apk> [candidate|baseline]}"
RECEIVER_APK="${2:?usage: verify_external_playback_api34.sh <app-apk> <receiver-apk> [candidate|baseline]}"
PROFILE="${3:-candidate}"

if [[ "$PROFILE" != "candidate" && "$PROFILE" != "baseline" ]]; then
  echo "ERROR: profile must be candidate or baseline, got: $PROFILE" >&2
  exit 2
fi

test -s "$APP_APK"
test -s "$RECEIVER_APK"

TMP_ROOT="${RUNNER_TEMP:-/tmp}"
OUT="$TMP_ROOT/external-playback-diagnostic"
EMULATOR_LOG="$TMP_ROOT/fc2-external-emulator.log"
AVD_NAME=fc2-external-api34
export ANDROID_AVD_HOME="$TMP_ROOT/android-avd-api34"
APP_PACKAGE=com.shaterguy.fc2weeklyranker.dev
RECEIVER_PACKAGE=com.shaterguy.fc2weeklyranker.externalreceiver
FIXTURE_SOURCE='https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4'
FIXTURE_MEDIA="$OUT/fixture.mp4"
FIXTURE_LOG="$OUT/fixture-server.log"
FIXTURE_PID=''

cleanup() {
  if [[ -n "$FIXTURE_PID" ]]; then
    kill "$FIXTURE_PID" >/dev/null 2>&1 || true
  fi
  adb emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$OUT" "$ANDROID_AVD_HOME"
printf '%s\n' "$FIXTURE_SOURCE" > "$OUT/fixture-source.txt"

# The previous compatibility step uses API 29. Ensure its emulator is fully gone
# before starting the exact API 34 runtime used by this regression gate.
adb emu kill >/dev/null 2>&1 || true
adb kill-server >/dev/null 2>&1 || true
sleep 2

sdkmanager \
  "platform-tools" \
  "emulator" \
  "system-images;android-34;google_apis;x86_64"

echo no | avdmanager create avd \
  --force \
  --name "$AVD_NAME" \
  --package "system-images;android-34;google_apis;x86_64" \
  --device pixel \
  --path "$ANDROID_AVD_HOME/$AVD_NAME.avd"

sudo chmod 666 /dev/kvm 2>/dev/null || true
nohup "$ANDROID_HOME/emulator/emulator" \
  -avd "$AVD_NAME" \
  -no-window \
  -noaudio \
  -no-boot-anim \
  -no-snapshot \
  -gpu swiftshader_indirect \
  >"$EMULATOR_LOG" 2>&1 &

if ! timeout 180s adb wait-for-device; then
  echo 'ERROR: Android 14 emulator did not expose an adb device within 180 seconds.' >&2
  cat "$EMULATOR_LOG" >&2 || true
  exit 1
fi
for _ in $(seq 1 120); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 2
done
if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; then
  echo 'ERROR: Android 14 emulator did not complete boot.' >&2
  cat "$EMULATOR_LOG" >&2 || true
  exit 1
fi
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == "34" ]]
[[ "$(adb shell getprop ro.build.version.release | tr -d '\r')" == "14" ]]

curl --fail --location --retry 3 --retry-delay 2 --output "$FIXTURE_MEDIA" "$FIXTURE_SOURCE"
test -s "$FIXTURE_MEDIA"
python3 scripts/delayed_short206_server.py \
  --file "$FIXTURE_MEDIA" \
  --host 0.0.0.0 \
  --port 18080 \
  --max-body-bytes 8192 \
  --delay-ms 12 \
  >"$FIXTURE_LOG" 2>&1 &
FIXTURE_PID=$!
for _ in $(seq 1 50); do
  if curl --fail --silent --head http://127.0.0.1:18080/video.mp4 >/dev/null; then
    break
  fi
  sleep 0.2
done
curl --fail --silent --head http://127.0.0.1:18080/video.mp4 >/dev/null

adb install -r "$APP_APK"
adb install -r "$RECEIVER_APK"
adb reverse tcp:18080 tcp:18080
adb shell am force-stop "$APP_PACKAGE" || true
adb shell am force-stop "$RECEIVER_PACKAGE" || true
adb logcat -c
adb shell am start -W \
  -n "$APP_PACKAGE/com.shaterguy.fc2weeklyranker.media.ExternalStreamDiagnosticActivity" \
  --es sourceUrl 'http://localhost:18080/video.mp4' \
  | tee "$OUT/launch.txt"

CURRENT_USER="$(adb shell am get-current-user | tr -d '\r')"
UID_LINE="$(adb shell cmd package list packages -U --user "$CURRENT_USER" "$APP_PACKAGE" | tr -d '\r')"
APP_UID="$(sed -n 's/^package:.* uid:\([0-9][0-9]*\)$/\1/p' <<<"$UID_LINE" | sed -n '1p')"
if [[ ! "$APP_UID" =~ ^[0-9]+$ ]]; then
  echo "ERROR: unable to resolve numeric app UID for $APP_PACKAGE; package-manager output: $UID_LINE" >&2
  exit 1
fi

: > "$OUT/process-state.txt"
: > "$OUT/filtered-logcat.txt"
RESULT_FOUND=0
for second in $(seq 0 170); do
  {
    printf 't=%ss uid=%s uidState=' "$second" "$APP_UID"
    adb shell cmd activity get-uid-state "$APP_UID" 2>/dev/null | tr -d '\r' || true
    adb shell dumpsys activity lru 2>/dev/null | grep -F "$APP_PACKAGE" | head -n 2 || true
    adb shell dumpsys activity processes 2>/dev/null | grep -F -A 16 -B 2 "$APP_PACKAGE" | head -n 24 || true
    printf '\n'
  } >> "$OUT/process-state.txt"

  adb logcat -d -v monotonic \
    -s FC2ExternalLaunch:I FC2ExternalTest:I '*:S' \
    > "$OUT/filtered-logcat.txt" || true
  if grep -Fq 'result scenario=long-decoder' "$OUT/filtered-logcat.txt"; then
    RESULT_FOUND=1
    break
  fi
  sleep 1
done

cat "$OUT/filtered-logcat.txt"
echo '--- provider process state samples ---'
cat "$OUT/process-state.txt"
echo '--- delayed short-206 fixture tail ---'
tail -n 80 "$FIXTURE_LOG" || true
[[ "$RESULT_FOUND" == '1' ]]

RESULT_LINE="$(grep -F 'result scenario=long-decoder' "$OUT/filtered-logcat.txt" | tail -n 1)"
printf '%s\n' "$RESULT_LINE" > "$OUT/result.txt"
if grep -Fq 'ok=false' <<<"$RESULT_LINE"; then
  printf 'REPRODUCED_FAILURE\n' > "$OUT/outcome.txt"
  cat "$OUT/outcome.txt"
  exit 1
elif ! grep -Fq 'ok=true' <<<"$RESULT_LINE"; then
  printf 'UNCLASSIFIED_RESULT\n' > "$OUT/outcome.txt"
  cat "$OUT/outcome.txt"
  exit 1
fi

metric() {
  local key="$1"
  grep -o "${key}=[-0-9]*" <<<"$RESULT_LINE" | tail -n 1 | cut -d= -f2
}
STARTUP_MS="$(metric startupMs)"
STALL_COUNT="$(metric stallCount)"
STALL_DURATION_MS="$(metric stallDurationMs)"
MAX_NO_PROGRESS_MS="$(metric maxNoProgressMs)"
BUFFERING_COUNT="$(metric bufferingCount)"
BUFFERING_DURATION_MS="$(metric bufferingDurationMs)"
for value in "$STARTUP_MS" "$STALL_COUNT" "$STALL_DURATION_MS" "$MAX_NO_PROGRESS_MS" "$BUFFERING_COUNT" "$BUFFERING_DURATION_MS"; do
  [[ "$value" =~ ^-?[0-9]+$ ]]
done

cat > "$OUT/smoothness-metrics.txt" <<EOF
profile=$PROFILE
startup_ms=$STARTUP_MS
stall_count=$STALL_COUNT
stall_duration_ms=$STALL_DURATION_MS
max_no_progress_ms=$MAX_NO_PROGRESS_MS
buffering_count=$BUFFERING_COUNT
buffering_duration_ms=$BUFFERING_DURATION_MS
EOF

python3 - "$FIXTURE_LOG" <<'PY'
import re
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(errors='replace')
rows = re.findall(r"requested=(\d+)-(\d+) returned=(\d+)-(\d+)", text)
if len(rows) < 32:
    raise SystemExit(f"expected at least 32 delayed range responses, got {len(rows)}")
short = sum(1 for rs, re_, ss, se in rows if int(se) - int(ss) < int(re_) - int(rs))
if short < 16:
    raise SystemExit(f"short-206 behavior was not exercised enough: {short}")
print(f"FC2_SHORT206_METRIC range_responses={len(rows)} short_responses={short}")
PY

if [[ "$PROFILE" == 'baseline' ]]; then
  printf 'BASELINE_MEASURED\n' > "$OUT/outcome.txt"
  cat "$OUT/smoothness-metrics.txt"
  cat "$OUT/outcome.txt"
  exit 0
fi

GATE_FAILED=0
if (( STARTUP_MS < 0 || STARTUP_MS > 8000 )); then
  echo "SMOOTHNESS_FAIL startup_ms=$STARTUP_MS limit=8000" >&2
  GATE_FAILED=1
fi
if (( STALL_COUNT > 2 )); then
  echo "SMOOTHNESS_FAIL stall_count=$STALL_COUNT limit=2" >&2
  GATE_FAILED=1
fi
if (( STALL_DURATION_MS > 3000 )); then
  echo "SMOOTHNESS_FAIL stall_duration_ms=$STALL_DURATION_MS limit=3000" >&2
  GATE_FAILED=1
fi
if (( MAX_NO_PROGRESS_MS > 2000 )); then
  echo "SMOOTHNESS_FAIL max_no_progress_ms=$MAX_NO_PROGRESS_MS limit=2000" >&2
  GATE_FAILED=1
fi
if (( BUFFERING_DURATION_MS > 5000 )); then
  echo "SMOOTHNESS_FAIL buffering_duration_ms=$BUFFERING_DURATION_MS limit=5000" >&2
  GATE_FAILED=1
fi

if (( GATE_FAILED != 0 )); then
  printf 'SMOOTHNESS_GATE_FAIL\n' > "$OUT/outcome.txt"
  cat "$OUT/smoothness-metrics.txt"
  cat "$OUT/outcome.txt"
  exit 1
fi

printf 'SMOOTHNESS_GATE_PASS\n' > "$OUT/outcome.txt"
cat "$OUT/smoothness-metrics.txt"
cat "$OUT/outcome.txt"

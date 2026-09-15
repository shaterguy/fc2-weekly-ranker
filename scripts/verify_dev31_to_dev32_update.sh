#!/usr/bin/env bash
set -euo pipefail

CURRENT_APK="${1:?usage: verify_dev31_to_dev32_update.sh <current-signed-apk>}"
: "${GH_TOKEN:?GH_TOKEN is required to read the pinned DEV31 Actions artifact}"
: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

test -s "$CURRENT_APK"

BT="$ANDROID_HOME/build-tools/37.0.0"
DEV_PACKAGE='com.shaterguy.fc2weeklyranker.dev'
STABLE_PACKAGE='com.shaterguy.fc2weeklyranker'
DEV31_ARTIFACT_ID='10385628071'
DEV31_ARTIFACT_NAME='fc2-weekly-ranker-test-v0.2.0-dev31.apk'
DEV31_SOURCE_SHA='9f31aa53fca98904d6ccb2abbfea7829aa6f6bbe'
DEV31_ARTIFACT_DIGEST='sha256:89dbddea50ff764bc2e5472a18cd555962783e76fc8415d00952906354de8633'
PINNED_TEST_CERT_SHA256='ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd'

CURRENT_BADGING="$("$BT/aapt" dump badging "$CURRENT_APK")"
if ! grep -Fq "package: name='$DEV_PACKAGE' versionCode='56' versionName='0.2.0-dev32'" <<<"$CURRENT_BADGING"; then
  echo 'ERROR: current TEST APK identity is not DEV32/versionCode 56.' >&2
  exit 1
fi
"$BT/apksigner" verify --min-sdk-version 29 "$CURRENT_APK"

META="$RUNNER_TEMP/fc2-dev31-artifact.json"
DEV31_APK="$RUNNER_TEMP/$DEV31_ARTIFACT_NAME"

curl --fail --silent --show-error --location --retry 3 \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/shaterguy/fc2-weekly-ranker/actions/artifacts/$DEV31_ARTIFACT_ID" \
  > "$META"

python3 - "$META" "$DEV31_ARTIFACT_ID" "$DEV31_ARTIFACT_NAME" "$DEV31_SOURCE_SHA" "$DEV31_ARTIFACT_DIGEST" <<'PY'
import json
import sys
from pathlib import Path
meta = json.loads(Path(sys.argv[1]).read_text())
expected_id = int(sys.argv[2])
expected_name = sys.argv[3]
expected_sha = sys.argv[4]
expected_digest = sys.argv[5]
if meta.get("id") != expected_id:
    raise SystemExit(f"DEV31 artifact id mismatch: {meta.get('id')}")
if meta.get("name") != expected_name:
    raise SystemExit(f"DEV31 artifact name mismatch: {meta.get('name')}")
if meta.get("expired") is not False:
    raise SystemExit("DEV31 artifact is expired")
if (meta.get("workflow_run") or {}).get("head_sha") != expected_sha:
    raise SystemExit("DEV31 artifact source SHA mismatch")
if meta.get("digest") != expected_digest:
    raise SystemExit("DEV31 artifact digest mismatch")
PY

curl --fail --silent --show-error --location --retry 3 \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/shaterguy/fc2-weekly-ranker/actions/artifacts/$DEV31_ARTIFACT_ID/zip" \
  --output "$DEV31_APK"

test -s "$DEV31_APK"
ACTUAL_DEV31_DIGEST="sha256:$(sha256sum "$DEV31_APK" | awk '{print $1}')"
if [[ "$ACTUAL_DEV31_DIGEST" != "$DEV31_ARTIFACT_DIGEST" ]]; then
  echo "ERROR: pinned DEV31 artifact byte digest mismatch: $ACTUAL_DEV31_DIGEST" >&2
  exit 1
fi

DEV31_BADGING="$("$BT/aapt" dump badging "$DEV31_APK")"
if ! grep -Fq "package: name='$DEV_PACKAGE' versionCode='55' versionName='0.2.0-dev31'" <<<"$DEV31_BADGING"; then
  echo 'ERROR: pinned DEV31 APK identity is not DEV31/versionCode 55.' >&2
  exit 1
fi
"$BT/apksigner" verify --min-sdk-version 29 "$DEV31_APK"

extract_cert_sha256() {
  local apk="$1"
  local prefix="$2"
  local cert_output="$RUNNER_TEMP/${prefix}-cert.txt"
  local cert_pem="$RUNNER_TEMP/${prefix}-signer.pem"
  "$BT/apksigner" verify --min-sdk-version 29 --print-certs-pem "$apk" > "$cert_output"
  awk '/-----BEGIN CERTIFICATE-----/{capture=1} capture{print} /-----END CERTIFICATE-----/{exit}' "$cert_output" > "$cert_pem"
  test -s "$cert_pem"
  openssl x509 -in "$cert_pem" -outform DER | sha256sum | awk '{print $1}' | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]'
}

DEV31_CERT_SHA256="$(extract_cert_sha256 "$DEV31_APK" fc2-dev31)"
CURRENT_CERT_SHA256="$(extract_cert_sha256 "$CURRENT_APK" fc2-dev32)"
[[ "$DEV31_CERT_SHA256" == "$PINNED_TEST_CERT_SHA256" ]]
[[ "$CURRENT_CERT_SHA256" == "$PINNED_TEST_CERT_SHA256" ]]
echo 'DEV31 baseline artifact identity, digest, and signer verified.'

AVD_NAME='fc2-dev31-update'
export ANDROID_AVD_HOME="$RUNNER_TEMP/android-avd-dev31-update"
rm -rf "$ANDROID_AVD_HOME"
mkdir -p "$ANDROID_AVD_HOME"
sdkmanager "platform-tools" "emulator" "system-images;android-29;google_apis;x86_64" >/dev/null
echo no | avdmanager create avd --force --name "$AVD_NAME" --package "system-images;android-29;google_apis;x86_64" --device pixel --path "$ANDROID_AVD_HOME/$AVD_NAME.avd" >/dev/null
"$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fxq "$AVD_NAME"
trap 'adb emu kill >/dev/null 2>&1 || true' EXIT
"$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$RUNNER_TEMP/fc2-dev31-update-emulator.log" 2>&1 &
if ! timeout 180s adb wait-for-device; then
  cat "$RUNNER_TEMP/fc2-dev31-update-emulator.log" >&2 || true
  exit 1
fi
for _ in $(seq 1 120); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == '1' ]] && break
  sleep 2
done
[[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == '1' ]]
for _ in $(seq 1 60); do
  adb shell pm path android >/dev/null 2>&1 && break
  sleep 2
done
adb shell pm path android >/dev/null

STABLE_APK="$RUNNER_TEMP/fc2-weekly-ranker-v0.1.0.apk"
if [[ ! -s "$STABLE_APK" ]]; then
  curl --fail --silent --show-error --location --retry 3 \
    --output "$STABLE_APK" \
    "https://github.com/shaterguy/fc2-weekly-ranker/releases/download/v0.1.0/fc2-weekly-ranker-v0.1.0.apk"
fi

timeout 180s adb install --no-streaming "$STABLE_APK" >/dev/null
timeout 180s adb install --no-streaming "$DEV31_APK" >/dev/null
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 4
adb shell am force-stop "$DEV_PACKAGE"
sleep 1
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev31'

adb shell "run-as $DEV_PACKAGE sh -c 'mkdir -p files && printf dev31-update-marker > files/dev32-update-marker.txt'"
[[ "$(adb shell "run-as $DEV_PACKAGE cat files/dev32-update-marker.txt" | tr -d '\r')" == 'dev31-update-marker' ]]

timeout 180s adb install --no-streaming -r "$CURRENT_APK" >/dev/null
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 5
adb shell am force-stop "$DEV_PACKAGE"
sleep 1

adb shell dumpsys package "$STABLE_PACKAGE" | grep -Fq 'versionName=0.1.0'
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev32'
MARKER_AFTER="$(adb shell "run-as $DEV_PACKAGE cat files/dev32-update-marker.txt" | tr -d '\r')"
if [[ "$MARKER_AFTER" != 'dev31-update-marker' ]]; then
  echo 'ERROR: DEV31 application data marker did not survive DEV32 in-place update.' >&2
  exit 1
fi

echo 'DEV31 to DEV32 in-place TEST update and stable co-install verified.'

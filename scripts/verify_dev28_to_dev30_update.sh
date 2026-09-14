#!/usr/bin/env bash
set -euo pipefail

CURRENT_APK="${1:?usage: verify_dev28_to_dev30_update.sh <current-signed-apk>}"
: "${GH_TOKEN:?GH_TOKEN is required to read the pinned DEV28 Actions artifact}"
: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

test -s "$CURRENT_APK"

BT="$ANDROID_HOME/build-tools/37.0.0"
DEV_PACKAGE='com.shaterguy.fc2weeklyranker.dev'
STABLE_PACKAGE='com.shaterguy.fc2weeklyranker'
DEV28_ARTIFACT_ID='10331049183'
DEV28_ARTIFACT_NAME='fc2-weekly-ranker-test-v0.2.0-dev28.apk'
DEV28_SOURCE_SHA='4bd01be61a1f513cac754d285d79d0e542464c07'
DEV28_ARTIFACT_DIGEST='sha256:4863e455503eb4400d0195a9d09e8901cb058e63438fb12b1d56a51637eb3b1c'
PINNED_TEST_CERT_SHA256='ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd'

CURRENT_BADGING="$("$BT/aapt" dump badging "$CURRENT_APK")"
if ! grep -Fq "package: name='$DEV_PACKAGE' versionCode='55' versionName='0.2.0-dev31'" <<<"$CURRENT_BADGING"; then
  echo 'ERROR: current TEST APK identity is not DEV31/versionCode 55.' >&2
  exit 1
fi
"$BT/apksigner" verify --min-sdk-version 29 "$CURRENT_APK"

META="$RUNNER_TEMP/fc2-dev28-artifact.json"
DEV28_APK="$RUNNER_TEMP/$DEV28_ARTIFACT_NAME"

curl --fail --silent --show-error --location --retry 3 \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/shaterguy/fc2-weekly-ranker/actions/artifacts/$DEV28_ARTIFACT_ID" \
  > "$META"

python3 - "$META" "$DEV28_ARTIFACT_ID" "$DEV28_ARTIFACT_NAME" "$DEV28_SOURCE_SHA" "$DEV28_ARTIFACT_DIGEST" <<'PY'
import json
import sys
from pathlib import Path
meta = json.loads(Path(sys.argv[1]).read_text())
expected_id = int(sys.argv[2])
expected_name = sys.argv[3]
expected_sha = sys.argv[4]
expected_digest = sys.argv[5]
if meta.get("id") != expected_id:
    raise SystemExit(f"DEV28 artifact id mismatch: {meta.get('id')}")
if meta.get("name") != expected_name:
    raise SystemExit(f"DEV28 artifact name mismatch: {meta.get('name')}")
if meta.get("expired") is not False:
    raise SystemExit("DEV28 artifact is expired")
if (meta.get("workflow_run") or {}).get("head_sha") != expected_sha:
    raise SystemExit("DEV28 artifact source SHA mismatch")
if meta.get("digest") != expected_digest:
    raise SystemExit("DEV28 artifact digest mismatch")
PY

curl --fail --silent --show-error --location --retry 3 \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/shaterguy/fc2-weekly-ranker/actions/artifacts/$DEV28_ARTIFACT_ID/zip" \
  --output "$DEV28_APK"

if [[ ! -s "$DEV28_APK" ]]; then
  echo 'ERROR: pinned DEV28 artifact download is empty.' >&2
  exit 1
fi
ACTUAL_DEV28_DIGEST="sha256:$(sha256sum "$DEV28_APK" | awk '{print $1}')"
if [[ "$ACTUAL_DEV28_DIGEST" != "$DEV28_ARTIFACT_DIGEST" ]]; then
  echo "ERROR: pinned DEV28 artifact byte digest mismatch: $ACTUAL_DEV28_DIGEST" >&2
  exit 1
fi

DEV28_BADGING="$("$BT/aapt" dump badging "$DEV28_APK")"
if ! grep -Fq "package: name='$DEV_PACKAGE' versionCode='52' versionName='0.2.0-dev28'" <<<"$DEV28_BADGING"; then
  echo 'ERROR: pinned DEV28 APK identity is not DEV28/versionCode 52.' >&2
  exit 1
fi
"$BT/apksigner" verify --min-sdk-version 29 "$DEV28_APK"

extract_cert_sha256() {
  local apk="$1"
  local prefix="$2"
  local cert_output="$RUNNER_TEMP/${prefix}-cert.txt"
  local cert_pem="$RUNNER_TEMP/${prefix}-signer.pem"
  "$BT/apksigner" verify --min-sdk-version 29 --print-certs-pem "$apk" > "$cert_output"
  awk '/-----BEGIN CERTIFICATE-----/{capture=1} capture{print} /-----END CERTIFICATE-----/{exit}' "$cert_output" > "$cert_pem"
  if [[ ! -s "$cert_pem" ]]; then
    echo "ERROR: signer certificate PEM missing for $prefix." >&2
    exit 1
  fi
  openssl x509 -in "$cert_pem" -outform DER | sha256sum | awk '{print $1}' | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]'
}

DEV28_CERT_SHA256="$(extract_cert_sha256 "$DEV28_APK" fc2-dev28)"
CURRENT_CERT_SHA256="$(extract_cert_sha256 "$CURRENT_APK" fc2-dev31)"
if [[ "$DEV28_CERT_SHA256" != "$PINNED_TEST_CERT_SHA256" ]]; then
  echo "ERROR: DEV28 signer mismatch: $DEV28_CERT_SHA256" >&2
  exit 1
fi
if [[ "$CURRENT_CERT_SHA256" != "$PINNED_TEST_CERT_SHA256" ]]; then
  echo "ERROR: DEV31 signer mismatch: $CURRENT_CERT_SHA256" >&2
  exit 1
fi

echo 'DEV28 baseline artifact identity, digest, and signer verified.'

AVD_NAME='fc2-dev28-update'
export ANDROID_AVD_HOME="$RUNNER_TEMP/android-avd-dev28-update"
rm -rf "$ANDROID_AVD_HOME"
mkdir -p "$ANDROID_AVD_HOME"
sdkmanager "platform-tools" "emulator" "system-images;android-29;google_apis;x86_64" >/dev/null
echo no | avdmanager create avd --force --name "$AVD_NAME" --package "system-images;android-29;google_apis;x86_64" --device pixel --path "$ANDROID_AVD_HOME/$AVD_NAME.avd" >/dev/null
if ! "$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fxq "$AVD_NAME"; then
  echo 'ERROR: isolated DEV28 update AVD was not created.' >&2
  exit 1
fi
trap 'adb emu kill >/dev/null 2>&1 || true' EXIT
"$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$RUNNER_TEMP/fc2-dev28-update-emulator.log" 2>&1 &
if ! timeout 180s adb wait-for-device; then
  echo 'ERROR: Android emulator did not expose an adb device within 180 seconds.' >&2
  cat "$RUNNER_TEMP/fc2-dev28-update-emulator.log" >&2 || true
  exit 1
fi
for _ in $(seq 1 120); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == '1' ]] && break
  sleep 2
done
if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != '1' ]]; then
  echo 'ERROR: Android emulator did not complete boot within 240 seconds after adb became available.' >&2
  cat "$RUNNER_TEMP/fc2-dev28-update-emulator.log" >&2 || true
  exit 1
fi
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
timeout 180s adb install --no-streaming "$DEV28_APK" >/dev/null
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 4
adb shell am force-stop "$DEV_PACKAGE"
sleep 1
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev28'

adb shell "run-as $DEV_PACKAGE sh -c 'mkdir -p files && printf dev28-update-marker > files/dev30-update-marker.txt'"
MARKER_BEFORE="$(adb shell "run-as $DEV_PACKAGE cat files/dev30-update-marker.txt" | tr -d '\r')"
[[ "$MARKER_BEFORE" == 'dev28-update-marker' ]]

timeout 180s adb install --no-streaming -r "$CURRENT_APK" >/dev/null
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 5
adb shell am force-stop "$DEV_PACKAGE"
sleep 1

adb shell dumpsys package "$STABLE_PACKAGE" | grep -Fq 'versionName=0.1.0'
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev31'
MARKER_AFTER="$(adb shell "run-as $DEV_PACKAGE cat files/dev30-update-marker.txt" | tr -d '\r')"
if [[ "$MARKER_AFTER" != 'dev28-update-marker' ]]; then
  echo 'ERROR: DEV28 application data marker did not survive DEV31 in-place update.' >&2
  exit 1
fi

echo 'DEV28 to DEV31 in-place TEST update and stable co-install verified.'

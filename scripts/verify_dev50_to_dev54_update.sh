#!/usr/bin/env bash
set -euo pipefail

[[ "${GITHUB_ACTIONS:-}" == 'true' ]] || { echo 'This verification requires disposable GitHub CI.' >&2; exit 1; }

CURRENT_APK="${1:?usage: verify_dev50_to_dev54_update.sh <current-signed-apk>}"
: "${SIGNING_PASSPHRASE:?SIGNING_PASSPHRASE is required}"
: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

test -s "$CURRENT_APK"

BT="$ANDROID_HOME/build-tools/37.0.0"
DEV_PACKAGE='com.shaterguy.fc2weeklyranker.dev'
PINNED_TEST_CERT_SHA256='ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd'
PASS_FILE="$RUNNER_TEMP/fc2-dev50-signing-pass"
REUSE_ADB="${REUSE_ADB:-0}"

cleanup() {
  rm -f "$PASS_FILE"
  if [[ "$REUSE_ADB" != '1' ]]; then
    adb emu kill >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

CURRENT_BADGING="$("$BT/aapt" dump badging "$CURRENT_APK")"
grep -Fq "package: name='$DEV_PACKAGE' versionCode='78' versionName='0.2.0-dev54'" <<<"$CURRENT_BADGING"
"$BT/apksigner" verify --min-sdk-version 29 "$CURRENT_APK"

# Reuse the exact signed DEV50 artifact from its successful canonical run.
# No previous source rebuild or signing-key regeneration is needed for this fixture.
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GH_TOKEN:?GH_TOKEN is required for the existing Actions read permission}"
BASELINE_PAYLOAD="$RUNNER_TEMP/fc2-dev50-baseline-download"
OLD_APK="$RUNNER_TEMP/fc2-weekly-ranker-test-v0.2.0-dev50.apk"
gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/11495839927/zip" > "$BASELINE_PAYLOAD"
python3 - "$BASELINE_PAYLOAD" "$OLD_APK" <<'BASELINE_PY'
import hashlib, io, sys, zipfile
from pathlib import Path
payload = Path(sys.argv[1]).read_bytes()
with zipfile.ZipFile(io.BytesIO(payload)) as archive:
    names = archive.namelist()
    if "AndroidManifest.xml" in names:
        apk = payload
    else:
        files = [name for name in names if not name.endswith("/")]
        if files != ["fc2-weekly-ranker-test-v0.2.0-dev50.apk"]:
            raise SystemExit("unexpected DEV50 artifact contents")
        apk = archive.read(files[0])
if hashlib.sha256(apk).hexdigest() != "c7b71797c1092080aa352eeb969a3edeba1bce5ab3623bc31aac45175a9ed5de":
    raise SystemExit("DEV50 canonical APK checksum mismatch")
Path(sys.argv[2]).write_bytes(apk)
BASELINE_PY
"$BT/apksigner" verify --min-sdk-version 29 "$OLD_APK"
umask 077
printf '%s' "$SIGNING_PASSPHRASE" > "$PASS_FILE"

OLD_BADGING="$("$BT/aapt" dump badging "$OLD_APK")"
grep -Fq "package: name='$DEV_PACKAGE' versionCode='74' versionName='0.2.0-dev50'" <<<"$OLD_BADGING"

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

OLD_CERT_SHA256="$(extract_cert_sha256 "$OLD_APK" fc2-dev50)"
CURRENT_CERT_SHA256="$(extract_cert_sha256 "$CURRENT_APK" fc2-dev54)"
[[ "$OLD_CERT_SHA256" == "$PINNED_TEST_CERT_SHA256" ]]
[[ "$CURRENT_CERT_SHA256" == "$PINNED_TEST_CERT_SHA256" ]]

if [[ "$REUSE_ADB" == '1' ]]; then
  adb get-state | grep -Fxq device
  [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == '1' ]]
else
  export ANDROID_AVD_HOME="$RUNNER_TEMP/android-avd-dev50-update"
  rm -rf "$ANDROID_AVD_HOME"
  mkdir -p "$ANDROID_AVD_HOME"
  sdkmanager "platform-tools" "emulator" "system-images;android-29;google_apis;x86_64" >/dev/null
  echo no | avdmanager create avd --force --name fc2-dev50-update --package "system-images;android-29;google_apis;x86_64" --device pixel --path "$ANDROID_AVD_HOME/fc2-dev50-update.avd" >/dev/null
  "$ANDROID_HOME/emulator/emulator" -avd fc2-dev50-update -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$RUNNER_TEMP/fc2-dev50-update-emulator.log" 2>&1 &
  timeout 180s adb wait-for-device
  for _ in $(seq 1 120); do
    [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == '1' ]] && break
    sleep 2
  done
  [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == '1' ]]
fi

python3 scripts/ci_source_fixture.py assert-emulator
adb uninstall "$DEV_PACKAGE" >/dev/null 2>&1 || true
timeout 180s adb install --no-streaming "$OLD_APK" >/dev/null
python3 scripts/ci_source_fixture.py seed
python3 scripts/ci_source_fixture.py verify
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 4
adb shell am force-stop "$DEV_PACKAGE"
sleep 1
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev50'

CURRENT_TEST_UNSIGNED="$(find app/build/outputs/apk/androidTest/debug -maxdepth 1 -type f -name '*.apk' -print -quit)"
test -s "$CURRENT_TEST_UNSIGNED"
CURRENT_TEST_ALIGNED="$RUNNER_TEMP/fc2-dev54-androidTest-aligned.apk"
CURRENT_TEST_APK="$RUNNER_TEMP/fc2-dev54-androidTest.apk"
"$BT/zipalign" -p -f 4 "$CURRENT_TEST_UNSIGNED" "$CURRENT_TEST_ALIGNED"
APKSIGNER="$BT/apksigner" bash tools/sign_test.sh "$CURRENT_TEST_ALIGNED" "$PASS_FILE" "$CURRENT_TEST_APK" >/dev/null
"$BT/apksigner" verify --min-sdk-version 29 "$CURRENT_TEST_APK"
adb install -r -t "$CURRENT_TEST_APK" >/dev/null
VISITED_SEED_OUTPUT="$RUNNER_TEMP/dev54-visited-seed.txt"
python3 scripts/ci_source_fixture.py verify
adb shell am instrument -w \
  -e visitedPersistencePhase seed \
  -e class 'com.shaterguy.fc2weeklyranker.data.SettingsStoreInstrumentedTest#visitedPostPersistsAcrossStoreWrappers' \
  "$DEV_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" \
  | tee "$VISITED_SEED_OUTPUT"
grep -Fq 'OK (1 test)' "$VISITED_SEED_OUTPUT"

adb shell "run-as $DEV_PACKAGE sh -c 'mkdir -p files && printf dev50-to-dev54-marker > files/dev54-update-marker.txt'"

OLD_DB_DIR="$RUNNER_TEMP/dev50-update-db"
mkdir -p "$OLD_DB_DIR"
adb exec-out run-as "$DEV_PACKAGE" cat databases/ranker.db > "$OLD_DB_DIR/ranker.db"
for suffix in -wal -shm; do
  sidecar="$OLD_DB_DIR/ranker.db${suffix}"
  if ! adb exec-out run-as "$DEV_PACKAGE" cat "databases/ranker.db${suffix}" > "$sidecar" 2>/dev/null; then
    rm -f "$sidecar"
  elif [[ ! -s "$sidecar" ]]; then
    rm -f "$sidecar"
  fi
done
python3 - "$OLD_DB_DIR/ranker.db" <<'PY'
import sqlite3
import sys
path = sys.argv[1]
con = sqlite3.connect(path)
if con.execute("PRAGMA user_version").fetchone()[0] != 4:
    raise SystemExit("DEV50 ranker database is not v4")
con.execute("PRAGMA foreign_keys=ON")
con.execute("DELETE FROM favorites WHERE postId='dev50-update-marker'")
con.execute("DELETE FROM posts WHERE id='dev50-update-marker'")
con.execute(
    "INSERT INTO posts(id,url,title,postedAtEpochMillis,recommendationCount,dailyRate,snapshotKey,fetchedAtEpochMillis,sourceKey) VALUES(?,?,?,?,?,?,?,?,?)",
    ("dev50-update-marker", "https://example.test/dev50-update", "dev50-update-marker", 1, 7, 7.0, "dev50-update", 2, "FC2"),
)
con.execute("INSERT INTO favorites(postId,createdAtEpochMillis) VALUES(?,?)", ("dev50-update-marker", 3))
con.commit()
con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
con.close()
PY
adb shell run-as "$DEV_PACKAGE" rm -f databases/ranker.db-wal databases/ranker.db-shm
adb exec-in run-as "$DEV_PACKAGE" dd of=databases/ranker.db < "$OLD_DB_DIR/ranker.db"

timeout 180s adb install --no-streaming -r "$CURRENT_APK" >/dev/null
python3 scripts/ci_source_fixture.py verify
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 5
adb shell am force-stop "$DEV_PACKAGE"
sleep 1
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev54'
[[ "$(adb shell "run-as $DEV_PACKAGE cat files/dev54-update-marker.txt" | tr -d '\r')" == 'dev50-to-dev54-marker' ]]

VISITED_VERIFY_OUTPUT="$RUNNER_TEMP/dev54-visited-verify.txt"
python3 scripts/ci_source_fixture.py verify
adb shell am instrument -w \
  -e visitedPersistencePhase verify \
  -e class 'com.shaterguy.fc2weeklyranker.data.SettingsStoreInstrumentedTest#visitedPostPersistsAcrossStoreWrappers' \
  "$DEV_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" \
  | tee "$VISITED_VERIFY_OUTPUT"
grep -Fq 'OK (1 test)' "$VISITED_VERIFY_OUTPUT"
python3 scripts/ci_source_fixture.py verify

NEW_DB_DIR="$RUNNER_TEMP/dev54-update-db"
mkdir -p "$NEW_DB_DIR"
adb exec-out run-as "$DEV_PACKAGE" cat databases/ranker.db > "$NEW_DB_DIR/ranker.db"
for suffix in -wal -shm; do
  sidecar="$NEW_DB_DIR/ranker.db${suffix}"
  if ! adb exec-out run-as "$DEV_PACKAGE" cat "databases/ranker.db${suffix}" > "$sidecar" 2>/dev/null; then
    rm -f "$sidecar"
  elif [[ ! -s "$sidecar" ]]; then
    rm -f "$sidecar"
  fi
done
python3 - "$NEW_DB_DIR/ranker.db" <<'PY'
import sqlite3
import sys
con = sqlite3.connect(sys.argv[1])
if con.execute("PRAGMA user_version").fetchone()[0] != 4:
    raise SystemExit("DEV53 ranker database is not v4")
post = con.execute(
    "SELECT title,recommendationCount,sourceKey FROM posts WHERE id='dev50-update-marker'"
).fetchone()
if post != ("dev50-update-marker", 7, "FC2"):
    raise SystemExit(f"DEV50 post marker changed during DEV53 update: {post}")
if con.execute("SELECT COUNT(*) FROM favorites WHERE postId='dev50-update-marker'").fetchone()[0] != 1:
    raise SystemExit("DEV50 favorite marker lost during DEV53 update")
con.close()
PY

echo 'DEV50 to DEV53 in-place TEST update, signer continuity, file retention, visited preference retention, and ranker DB retention verified.'

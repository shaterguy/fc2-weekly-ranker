#!/usr/bin/env bash
set -euo pipefail

CURRENT_APK="${1:?usage: verify_dev32_to_dev33_update.sh <current-signed-apk>}"
: "${SIGNING_PASSPHRASE:?SIGNING_PASSPHRASE is required}"
: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

test -s "$CURRENT_APK"

BT="$ANDROID_HOME/build-tools/37.0.0"
DEV_PACKAGE='com.shaterguy.fc2weeklyranker.dev'
PINNED_TEST_CERT_SHA256='ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd'
OLD="$RUNNER_TEMP/fc2-dev32-source"
PASS_FILE="$RUNNER_TEMP/fc2-dev32-signing-pass"
AVD_NAME='fc2-dev32-update'
REUSE_ADB="${REUSE_ADB:-0}"

cleanup() {
  rm -f "$PASS_FILE"
  if [[ "$REUSE_ADB" != '1' ]]; then
    adb emu kill >/dev/null 2>&1 || true
  fi
  git worktree remove --force "$OLD" >/dev/null 2>&1 || true
}
trap cleanup EXIT

CURRENT_BADGING="$("$BT/aapt" dump badging "$CURRENT_APK")"
grep -Fq "package: name='$DEV_PACKAGE' versionCode='58' versionName='0.2.0-dev34'" <<<"$CURRENT_BADGING"
"$BT/apksigner" verify --min-sdk-version 29 "$CURRENT_APK"

git fetch --no-tags origin refs/heads/v0.2.0-dev32:refs/remotes/origin/v0.2.0-dev32
git worktree prune
git worktree add --detach "$OLD" refs/remotes/origin/v0.2.0-dev32
gradle --no-daemon --stacktrace -p "$OLD" :app:assembleDebug
OLD_UNSIGNED="$(find "$OLD/app/build/outputs/apk/debug" -maxdepth 1 -type f -name '*-unsigned.apk' -print -quit)"
test -s "$OLD_UNSIGNED"
OLD_ALIGNED="$RUNNER_TEMP/fc2-weekly-ranker-test-v0.2.0-dev32-aligned.apk"
OLD_APK="$RUNNER_TEMP/fc2-weekly-ranker-test-v0.2.0-dev32.apk"
"$BT/zipalign" -p -f 4 "$OLD_UNSIGNED" "$OLD_ALIGNED"
umask 077
printf '%s' "$SIGNING_PASSPHRASE" > "$PASS_FILE"
APKSIGNER="$BT/apksigner" bash "$OLD/tools/sign_test.sh" "$OLD_ALIGNED" "$PASS_FILE" "$OLD_APK" >/dev/null
"$BT/apksigner" verify --min-sdk-version 29 "$OLD_APK"

OLD_BADGING="$("$BT/aapt" dump badging "$OLD_APK")"
grep -Fq "package: name='$DEV_PACKAGE' versionCode='56' versionName='0.2.0-dev32'" <<<"$OLD_BADGING"

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

OLD_CERT_SHA256="$(extract_cert_sha256 "$OLD_APK" fc2-dev32)"
CURRENT_CERT_SHA256="$(extract_cert_sha256 "$CURRENT_APK" fc2-dev34)"
[[ "$OLD_CERT_SHA256" == "$PINNED_TEST_CERT_SHA256" ]]
[[ "$CURRENT_CERT_SHA256" == "$PINNED_TEST_CERT_SHA256" ]]

if [[ "$REUSE_ADB" == '1' ]]; then
  adb get-state | grep -Fxq device
  [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == '1' ]]
else
  export ANDROID_AVD_HOME="$RUNNER_TEMP/android-avd-dev32-update"
  rm -rf "$ANDROID_AVD_HOME"
  mkdir -p "$ANDROID_AVD_HOME"
  sdkmanager "platform-tools" "emulator" "system-images;android-29;google_apis;x86_64" >/dev/null
  echo no | avdmanager create avd --force --name "$AVD_NAME" --package "system-images;android-29;google_apis;x86_64" --device pixel --path "$ANDROID_AVD_HOME/$AVD_NAME.avd" >/dev/null
  "$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fxq "$AVD_NAME"
  "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$RUNNER_TEMP/fc2-dev32-update-emulator.log" 2>&1 &
  if ! timeout 180s adb wait-for-device; then
    cat "$RUNNER_TEMP/fc2-dev32-update-emulator.log" >&2 || true
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
fi

adb uninstall "$DEV_PACKAGE" >/dev/null 2>&1 || true
timeout 180s adb install --no-streaming "$OLD_APK" >/dev/null
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 4
adb shell am force-stop "$DEV_PACKAGE"
sleep 1
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev32'
adb shell "run-as $DEV_PACKAGE sh -c 'mkdir -p files && printf dev32-search-migration-marker > files/dev33-update-marker.txt'"

OLD_DB_DIR="$RUNNER_TEMP/dev32-search-db"
mkdir -p "$OLD_DB_DIR"
adb exec-out run-as "$DEV_PACKAGE" cat databases/search.db > "$OLD_DB_DIR/search.db"
for suffix in -wal -shm; do
  sidecar="$OLD_DB_DIR/search.db${suffix}"
  if ! adb exec-out run-as "$DEV_PACKAGE" cat "databases/search.db${suffix}" > "$sidecar" 2>/dev/null; then
    rm -f "$sidecar"
  elif [[ ! -s "$sidecar" ]]; then
    rm -f "$sidecar"
  fi
done

python3 - "$OLD_DB_DIR/search.db" <<'PY'
import sqlite3
import sys
path = sys.argv[1]
con = sqlite3.connect(path)
version = con.execute("PRAGMA user_version").fetchone()[0]
if version != 2:
    raise SystemExit(f"expected DEV32 search database v2, got {version}")
columns = {row[1] for row in con.execute("PRAGMA table_info(search_results)")}
if "occurrenceCount" in columns:
    raise SystemExit("DEV32 search_results unexpectedly already has occurrenceCount")
con.execute("DELETE FROM search_results")
con.execute("DELETE FROM search_session")
con.execute(
    "INSERT INTO search_session(slot,token,query,baseUrl,status,nextPage,totalPages,errorMessage,updatedAtEpochMillis,sourceKey) VALUES(?,?,?,?,?,?,?,?,?,?)",
    (1, "dev32-migration-token", "legacy-query", "https://02.avsee.is", "COMPLETED", 2, 1, None, 1234, "JAV"),
)
con.execute(
    "INSERT INTO search_results(sessionToken,postId,url,title,sequence) VALUES(?,?,?,?,?)",
    ("dev32-migration-token", "jav:legacy-marker", "https://example.test/legacy", "legacy-title", 1000000),
)
con.commit()
con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
con.close()
PY

adb shell run-as "$DEV_PACKAGE" rm -f databases/search.db-wal databases/search.db-shm
adb exec-in run-as "$DEV_PACKAGE" dd of=databases/search.db < "$OLD_DB_DIR/search.db"

timeout 180s adb install --no-streaming -r "$CURRENT_APK" >/dev/null
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 5
adb shell am force-stop "$DEV_PACKAGE"
sleep 1
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev34'
[[ "$(adb shell "run-as $DEV_PACKAGE cat files/dev33-update-marker.txt" | tr -d '\r')" == 'dev32-search-migration-marker' ]]

NEW_DB_DIR="$RUNNER_TEMP/dev33-search-db"
mkdir -p "$NEW_DB_DIR"
adb exec-out run-as "$DEV_PACKAGE" cat databases/search.db > "$NEW_DB_DIR/search.db"
for suffix in -wal -shm; do
  sidecar="$NEW_DB_DIR/search.db${suffix}"
  if ! adb exec-out run-as "$DEV_PACKAGE" cat "databases/search.db${suffix}" > "$sidecar" 2>/dev/null; then
    rm -f "$sidecar"
  elif [[ ! -s "$sidecar" ]]; then
    rm -f "$sidecar"
  fi
done

python3 - "$NEW_DB_DIR/search.db" <<'PY'
import sqlite3
import sys
con = sqlite3.connect(sys.argv[1])
version = con.execute("PRAGMA user_version").fetchone()[0]
if version != 3:
    raise SystemExit(f"expected DEV33 search database v3, got {version}")
column_rows = {row[1]: row for row in con.execute("PRAGMA table_info(search_results)")}
occurrence = column_rows.get("occurrenceCount")
if occurrence is None:
    raise SystemExit("search_results.occurrenceCount missing after migration")
if occurrence[3] != 1:
    raise SystemExit(f"occurrenceCount is not NOT NULL: {occurrence}")
row = con.execute(
    "SELECT postId,url,title,sequence,occurrenceCount FROM search_results WHERE sessionToken=?",
    ("dev32-migration-token",),
).fetchone()
expected = ("jav:legacy-marker", "https://example.test/legacy", "legacy-title", 1000000, 1)
if row != expected:
    raise SystemExit(f"legacy search row changed during migration: {row}")
session = con.execute(
    "SELECT query,sourceKey,status,nextPage,totalPages FROM search_session WHERE token=?",
    ("dev32-migration-token",),
).fetchone()
if session != ("legacy-query", "JAV", "COMPLETED", 2, 1):
    raise SystemExit(f"legacy search session changed during migration: {session}")
con.close()
PY

echo 'DEV32 to DEV33 in-place TEST update and Search DB 2->3 migration verified.'

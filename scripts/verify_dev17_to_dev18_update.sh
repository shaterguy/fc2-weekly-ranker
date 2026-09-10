#!/usr/bin/env bash
set -euo pipefail

CURRENT_APK="${1:?usage: verify_dev17_to_dev18_update.sh <current-signed-apk>}"
: "${GH_TOKEN:?GH_TOKEN is required to read the pinned DEV17 Actions artifact}"
: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

test -s "$CURRENT_APK"

BT="$ANDROID_HOME/build-tools/37.0.0"
DEV_PACKAGE='com.shaterguy.fc2weeklyranker.dev'
STABLE_PACKAGE='com.shaterguy.fc2weeklyranker'
DEV17_ARTIFACT_ID='10085062604'
DEV17_ARTIFACT_NAME='fc2-weekly-ranker-test-v0.2.0-dev17.apk'
DEV17_SOURCE_SHA='7cb329f47191126fb9fe83ad667bad5621b4cb7e'
DEV17_ARTIFACT_DIGEST='sha256:a879a9b6a4d4fead6aee847f3312be5740fd0355e748f62ca1445e2134908fe8'
PINNED_TEST_CERT_SHA256='ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd'

CURRENT_BADGING="$("$BT/aapt" dump badging "$CURRENT_APK")"
grep -Fq "package: name='$DEV_PACKAGE' versionCode='42' versionName='0.2.0-dev18'" <<<"$CURRENT_BADGING"

META="$RUNNER_TEMP/fc2-dev17-artifact.json"
ARCHIVE="$RUNNER_TEMP/fc2-dev17-artifact.zip"
EXTRACT_DIR="$RUNNER_TEMP/fc2-dev17-artifact"

curl --fail --silent --show-error --location --retry 3 \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/shaterguy/fc2-weekly-ranker/actions/artifacts/$DEV17_ARTIFACT_ID" \
  > "$META"

python3 - "$META" "$DEV17_ARTIFACT_ID" "$DEV17_ARTIFACT_NAME" "$DEV17_SOURCE_SHA" "$DEV17_ARTIFACT_DIGEST" <<'PY'
import json
import sys
from pathlib import Path

meta = json.loads(Path(sys.argv[1]).read_text())
expected_id = int(sys.argv[2])
expected_name = sys.argv[3]
expected_sha = sys.argv[4]
expected_digest = sys.argv[5]
if meta.get("id") != expected_id:
    raise SystemExit(f"DEV17 artifact id mismatch: {meta.get('id')}")
if meta.get("name") != expected_name:
    raise SystemExit(f"DEV17 artifact name mismatch: {meta.get('name')}")
if meta.get("expired") is not False:
    raise SystemExit("DEV17 artifact is expired")
if (meta.get("workflow_run") or {}).get("head_sha") != expected_sha:
    raise SystemExit("DEV17 artifact source SHA mismatch")
if meta.get("digest") != expected_digest:
    raise SystemExit("DEV17 artifact digest mismatch")
PY

curl --fail --silent --show-error --location --retry 3 \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/shaterguy/fc2-weekly-ranker/actions/artifacts/$DEV17_ARTIFACT_ID/zip" \
  --output "$ARCHIVE"

test -s "$ARCHIVE"
rm -rf "$EXTRACT_DIR"
mkdir -p "$EXTRACT_DIR"
unzip -q "$ARCHIVE" -d "$EXTRACT_DIR"
mapfile -t DEV17_APKS < <(find "$EXTRACT_DIR" -type f -name "$DEV17_ARTIFACT_NAME" -print)
[[ "${#DEV17_APKS[@]}" -eq 1 ]]
DEV17_APK="${DEV17_APKS[0]}"
test -s "$DEV17_APK"

DEV17_BADGING="$("$BT/aapt" dump badging "$DEV17_APK")"
grep -Fq "package: name='$DEV_PACKAGE' versionCode='41' versionName='0.2.0-dev17'" <<<"$DEV17_BADGING"
"$BT/apksigner" verify --min-sdk-version 29 "$DEV17_APK"
DEV17_CERT_SHA256="$("$BT/apksigner" verify --print-certs "$DEV17_APK" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print $2; exit}' | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
[[ "$DEV17_CERT_SHA256" == "$PINNED_TEST_CERT_SHA256" ]]

AVD_NAME='fc2-compat'
export ANDROID_AVD_HOME="$RUNNER_TEMP/android-avd"
"$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fxq "$AVD_NAME"
trap 'adb emu kill >/dev/null 2>&1 || true' EXIT
"$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$RUNNER_TEMP/fc2-dev17-update-emulator.log" 2>&1 &
if ! timeout 180s adb wait-for-device; then
  echo 'ERROR: Android emulator did not expose an adb device within 180 seconds.' >&2
  cat "$RUNNER_TEMP/fc2-dev17-update-emulator.log" >&2 || true
  exit 1
fi
for _ in $(seq 1 120); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == '1' ]] && break
  sleep 2
done
if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != '1' ]]; then
  echo 'ERROR: Android emulator did not complete boot within 240 seconds after adb became available.' >&2
  cat "$RUNNER_TEMP/fc2-dev17-update-emulator.log" >&2 || true
  exit 1
fi

adb shell dumpsys package "$STABLE_PACKAGE" | grep -Fq 'versionName=0.1.0'
adb uninstall "$DEV_PACKAGE" >/dev/null
adb install "$DEV17_APK" >/dev/null
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 4
adb shell am force-stop "$DEV_PACKAGE"
sleep 1
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev17'

OLD_DB_DIR="$RUNNER_TEMP/dev17-update-db"
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
OLD_DB="$OLD_DB_DIR/ranker.db"
python3 - "$OLD_DB" <<'PY'
import sqlite3
import sys

con = sqlite3.connect(sys.argv[1])
version = con.execute("PRAGMA user_version").fetchone()[0]
if version != 3:
    raise SystemExit(f"expected DEV17 database v3, got {version}")
con.execute("PRAGMA foreign_keys=ON")
con.execute("INSERT OR REPLACE INTO posts(id,url,title,postedAtEpochMillis,recommendationCount,dailyRate,snapshotKey,fetchedAtEpochMillis) VALUES(?,?,?,?,?,?,?,?)", ("dev17-update-marker", "https://example.test/dev17-update", "dev17-update-marker", 10, 19, 1.9, "dev17-update", 20))
con.execute("INSERT OR REPLACE INTO favorites(postId,createdAtEpochMillis) VALUES(?,?)", ("dev17-update-marker", 30))
con.execute("INSERT OR REPLACE INTO videos(id,postId,url,referer,userAgent,sourceKind,ordinal,discoveredAtEpochMillis) VALUES(?,?,?,?,?,?,?,?)", ("dev17-update-video", "dev17-update-marker", "https://example.test/dev17-update.mp4", "https://example.test/dev17-update", "ua", "DIRECT", 0, 40))
con.execute("INSERT OR REPLACE INTO downloads(videoId,status,contentUri,downloadedBytes,totalBytes,errorCode,updatedAtEpochMillis,enqueueOrder,retryCount) VALUES(?,?,?,?,?,?,?,?,?)", ("dev17-update-video", "PAUSED", None, 456, 2000, None, 50, 888, 3))
con.commit()
con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
con.close()
PY
adb shell run-as "$DEV_PACKAGE" rm -f databases/ranker.db-wal databases/ranker.db-shm
adb exec-in run-as "$DEV_PACKAGE" dd of=databases/ranker.db < "$OLD_DB" >/dev/null

adb install -r "$CURRENT_APK" >/dev/null
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 5
adb shell am force-stop "$DEV_PACKAGE"
sleep 1

NEW_DB_DIR="$RUNNER_TEMP/dev18-update-db"
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
version = con.execute("PRAGMA user_version").fetchone()[0]
if version != 3:
    raise SystemExit(f"expected DEV18 database v3, got {version}")
if con.execute("SELECT COUNT(*) FROM posts WHERE id='dev17-update-marker'").fetchone()[0] != 1:
    raise SystemExit("post marker lost during DEV17 to DEV18 update")
if con.execute("SELECT COUNT(*) FROM favorites WHERE postId='dev17-update-marker'").fetchone()[0] != 1:
    raise SystemExit("favorite marker lost during DEV17 to DEV18 update")
if con.execute("SELECT COUNT(*) FROM videos WHERE id='dev17-update-video'").fetchone()[0] != 1:
    raise SystemExit("video marker lost during DEV17 to DEV18 update")
download = con.execute("SELECT downloadedBytes,totalBytes,enqueueOrder,retryCount FROM downloads WHERE videoId='dev17-update-video'").fetchone()
if download != (456, 2000, 888, 3):
    raise SystemExit(f"download marker changed during DEV17 to DEV18 update: {download}")
con.close()
PY

adb shell dumpsys package "$STABLE_PACKAGE" | grep -Fq 'versionName=0.1.0'
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev18'

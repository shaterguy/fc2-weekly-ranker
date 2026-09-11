#!/usr/bin/env bash
set -euo pipefail

CURRENT_APK="${1:?usage: verify_dev18_to_dev19_update.sh <current-signed-apk>}"
: "${GH_TOKEN:?GH_TOKEN is required to read the pinned DEV18 Actions artifact}"
: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

test -s "$CURRENT_APK"

BT="$ANDROID_HOME/build-tools/37.0.0"
DEV_PACKAGE='com.shaterguy.fc2weeklyranker.dev'
STABLE_PACKAGE='com.shaterguy.fc2weeklyranker'
DEV18_ARTIFACT_ID='10162583160'
DEV18_ARTIFACT_NAME='fc2-weekly-ranker-test-v0.2.0-dev18.apk'
DEV18_SOURCE_SHA='f9972179dd438892eaf057845cdcc3ff55ab19cb'
DEV18_ARTIFACT_DIGEST='sha256:50ec2b3891c2fbf91b3f4e250f1b52c67fd6099dc2012b35914865b8d0db1f34'
PINNED_TEST_CERT_SHA256='ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd'

CURRENT_BADGING="$("$BT/aapt" dump badging "$CURRENT_APK")"
if ! grep -Fq "package: name='$DEV_PACKAGE' versionCode='43' versionName='0.2.0-dev19'" <<<"$CURRENT_BADGING"; then
  echo 'ERROR: current TEST APK identity is not DEV19/versionCode 43.' >&2
  exit 1
fi

META="$RUNNER_TEMP/fc2-dev18-artifact.json"
DEV18_APK="$RUNNER_TEMP/$DEV18_ARTIFACT_NAME"

curl --fail --silent --show-error --location --retry 3 \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/shaterguy/fc2-weekly-ranker/actions/artifacts/$DEV18_ARTIFACT_ID" \
  > "$META"

python3 - "$META" "$DEV18_ARTIFACT_ID" "$DEV18_ARTIFACT_NAME" "$DEV18_SOURCE_SHA" "$DEV18_ARTIFACT_DIGEST" <<'PY'
import json
import sys
from pathlib import Path

meta = json.loads(Path(sys.argv[1]).read_text())
expected_id = int(sys.argv[2])
expected_name = sys.argv[3]
expected_sha = sys.argv[4]
expected_digest = sys.argv[5]
if meta.get("id") != expected_id:
    raise SystemExit(f"DEV18 artifact id mismatch: {meta.get('id')}")
if meta.get("name") != expected_name:
    raise SystemExit(f"DEV18 artifact name mismatch: {meta.get('name')}")
if meta.get("expired") is not False:
    raise SystemExit("DEV18 artifact is expired")
if (meta.get("workflow_run") or {}).get("head_sha") != expected_sha:
    raise SystemExit("DEV18 artifact source SHA mismatch")
if meta.get("digest") != expected_digest:
    raise SystemExit("DEV18 artifact digest mismatch")
PY

curl --fail --silent --show-error --location --retry 3 \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/shaterguy/fc2-weekly-ranker/actions/artifacts/$DEV18_ARTIFACT_ID/zip" \
  --output "$DEV18_APK"

if [[ ! -s "$DEV18_APK" ]]; then
  echo 'ERROR: pinned DEV18 artifact download is empty.' >&2
  exit 1
fi
ACTUAL_DEV18_DIGEST="sha256:$(sha256sum "$DEV18_APK" | awk '{print $1}')"
if [[ "$ACTUAL_DEV18_DIGEST" != "$DEV18_ARTIFACT_DIGEST" ]]; then
  echo "ERROR: pinned DEV18 artifact byte digest mismatch: $ACTUAL_DEV18_DIGEST" >&2
  exit 1
fi

DEV18_BADGING="$("$BT/aapt" dump badging "$DEV18_APK")"
if ! grep -Fq "package: name='$DEV_PACKAGE' versionCode='42' versionName='0.2.0-dev18'" <<<"$DEV18_BADGING"; then
  echo 'ERROR: pinned DEV18 APK identity is not DEV18/versionCode 42.' >&2
  exit 1
fi
"$BT/apksigner" verify --min-sdk-version 29 "$DEV18_APK"
DEV18_CERT_OUTPUT="$RUNNER_TEMP/fc2-dev18-cert.txt"
DEV18_CERT_PEM="$RUNNER_TEMP/fc2-dev18-signer.pem"
"$BT/apksigner" verify --min-sdk-version 29 --print-certs-pem "$DEV18_APK" > "$DEV18_CERT_OUTPUT"
awk '/-----BEGIN CERTIFICATE-----/{capture=1} capture{print} /-----END CERTIFICATE-----/{exit}' "$DEV18_CERT_OUTPUT" > "$DEV18_CERT_PEM"
if [[ ! -s "$DEV18_CERT_PEM" ]]; then
  echo 'ERROR: pinned DEV18 signer certificate PEM was not emitted.' >&2
  exit 1
fi
DEV18_CERT_SHA256="$(openssl x509 -in "$DEV18_CERT_PEM" -outform DER | sha256sum | awk '{print $1}' | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$DEV18_CERT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "ERROR: pinned DEV18 signer digest is malformed: $DEV18_CERT_SHA256" >&2
  exit 1
fi
if [[ "$DEV18_CERT_SHA256" != "$PINNED_TEST_CERT_SHA256" ]]; then
  echo "ERROR: pinned DEV18 signer mismatch: $DEV18_CERT_SHA256" >&2
  exit 1
fi

echo 'DEV18 baseline artifact identity, digest, and signer verified.'

AVD_NAME='fc2-dev18-update'
export ANDROID_AVD_HOME="$RUNNER_TEMP/android-avd-dev18-update"
rm -rf "$ANDROID_AVD_HOME"
mkdir -p "$ANDROID_AVD_HOME"
sdkmanager "platform-tools" "emulator" "system-images;android-29;google_apis;x86_64" >/dev/null
echo no | avdmanager create avd --force --name "$AVD_NAME" --package "system-images;android-29;google_apis;x86_64" --device pixel --path "$ANDROID_AVD_HOME/$AVD_NAME.avd" >/dev/null
if ! "$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fxq "$AVD_NAME"; then
  echo 'ERROR: isolated DEV18 update AVD was not created.' >&2
  exit 1
fi
trap 'adb emu kill >/dev/null 2>&1 || true' EXIT
"$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$RUNNER_TEMP/fc2-dev18-update-emulator.log" 2>&1 &
if ! timeout 180s adb wait-for-device; then
  echo 'ERROR: Android emulator did not expose an adb device within 180 seconds.' >&2
  cat "$RUNNER_TEMP/fc2-dev18-update-emulator.log" >&2 || true
  exit 1
fi
for _ in $(seq 1 120); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == '1' ]] && break
  sleep 2
done
if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != '1' ]]; then
  echo 'ERROR: Android emulator did not complete boot within 240 seconds after adb became available.' >&2
  cat "$RUNNER_TEMP/fc2-dev18-update-emulator.log" >&2 || true
  exit 1
fi

STABLE_APK="$RUNNER_TEMP/fc2-weekly-ranker-v0.1.0.apk"
if [[ ! -s "$STABLE_APK" ]]; then
  curl --fail --silent --show-error --location --retry 3 \
    --output "$STABLE_APK" \
    "https://github.com/shaterguy/fc2-weekly-ranker/releases/download/v0.1.0/fc2-weekly-ranker-v0.1.0.apk"
fi
adb install "$STABLE_APK" >/dev/null
adb shell dumpsys package "$STABLE_PACKAGE" | grep -Fq 'versionName=0.1.0'
adb install "$DEV18_APK" >/dev/null
adb shell am start -W -n "$DEV_PACKAGE/com.shaterguy.fc2weeklyranker.MainActivity" >/dev/null
sleep 4
adb shell am force-stop "$DEV_PACKAGE"
sleep 1
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev18'

OLD_DB_DIR="$RUNNER_TEMP/dev18-update-db"
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
    raise SystemExit(f"expected DEV18 database v3, got {version}")
con.execute("PRAGMA foreign_keys=ON")
con.execute("INSERT OR REPLACE INTO posts(id,url,title,postedAtEpochMillis,recommendationCount,dailyRate,snapshotKey,fetchedAtEpochMillis) VALUES(?,?,?,?,?,?,?,?)", ("dev18-update-marker", "https://example.test/dev18-update", "dev18-update-marker", 10, 19, 1.9, "dev18-update", 20))
con.execute("INSERT OR REPLACE INTO favorites(postId,createdAtEpochMillis) VALUES(?,?)", ("dev18-update-marker", 30))
con.execute("INSERT OR REPLACE INTO videos(id,postId,url,referer,userAgent,sourceKind,ordinal,discoveredAtEpochMillis) VALUES(?,?,?,?,?,?,?,?)", ("dev18-update-video", "dev18-update-marker", "https://example.test/dev18-update.mp4", "https://example.test/dev18-update", "ua", "DIRECT", 0, 40))
con.execute("INSERT OR REPLACE INTO downloads(videoId,status,contentUri,downloadedBytes,totalBytes,errorCode,updatedAtEpochMillis,enqueueOrder,retryCount) VALUES(?,?,?,?,?,?,?,?,?)", ("dev18-update-video", "PAUSED", None, 456, 2000, None, 50, 888, 3))
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

NEW_DB_DIR="$RUNNER_TEMP/dev19-update-db"
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
    raise SystemExit(f"expected DEV19 database v3, got {version}")
if con.execute("SELECT COUNT(*) FROM posts WHERE id='dev18-update-marker'").fetchone()[0] != 1:
    raise SystemExit("post marker lost during DEV18 to DEV19 update")
if con.execute("SELECT COUNT(*) FROM favorites WHERE postId='dev18-update-marker'").fetchone()[0] != 1:
    raise SystemExit("favorite marker lost during DEV18 to DEV19 update")
if con.execute("SELECT COUNT(*) FROM videos WHERE id='dev18-update-video'").fetchone()[0] != 1:
    raise SystemExit("video marker lost during DEV18 to DEV19 update")
download = con.execute("SELECT downloadedBytes,totalBytes,enqueueOrder,retryCount FROM downloads WHERE videoId='dev18-update-video'").fetchone()
if download != (456, 2000, 888, 3):
    raise SystemExit(f"download marker changed during DEV18 to DEV19 update: {download}")
con.close()
PY

adb shell dumpsys package "$STABLE_PACKAGE" | grep -Fq 'versionName=0.1.0'
adb shell dumpsys package "$DEV_PACKAGE" | grep -Fq 'versionName=0.2.0-dev19'

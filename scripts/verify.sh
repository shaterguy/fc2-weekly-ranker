#!/usr/bin/env bash
set -euo pipefail

python3 -m py_compile tools/derive_test_signing_identity.py tools/derive_stable_signing_identity.py
bash -n tools/sign_test.sh
bash -n tools/sign_stable.sh
bash -n scripts/verify_dev17_to_dev18_update.sh
bash -n scripts/verify_dev18_to_dev19_update.sh

MAIN='app/src/main/java/com/shaterguy/fc2weeklyranker/MainActivity.kt'
MANIFEST='app/src/main/AndroidManifest.xml'
grep -Fq 'Intent(Intent.ACTION_VIEW)' "$MAIN"
grep -Fq 'setDataAndType(Uri.parse(url), "video/*")' "$MAIN"
grep -Fq 'Text("외부 플레이어"' "$MAIN"
grep -Fq 'contentDescription = "영상 ${index + 1} 외부 플레이어로 열기"' "$MAIN"
grep -Fq 'android:name=".media.ExternalVideoStreamProvider"' "$MANIFEST"
grep -Fq 'android:exported="false"' "$MANIFEST"
grep -Fq 'android:grantUriPermissions="true"' "$MANIFEST"
if grep -Fq 'Text("내장플레이어 열기"' "$MAIN"; then
  echo 'ERROR: stale internal-player button label remains.' >&2
  exit 1
fi
if grep -Fq 'nativeVideoController.openFullscreen(video, autoPlay = true)' "$MAIN"; then
  echo 'ERROR: external-player action still routes to the internal fullscreen controller.' >&2
  exit 1
fi

gradle --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebugAndroidTest \
  :external-stream-receiver:assembleDebug

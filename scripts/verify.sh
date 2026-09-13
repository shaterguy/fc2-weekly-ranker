#!/usr/bin/env bash
set -euo pipefail

python3 -m py_compile tools/derive_test_signing_identity.py tools/derive_stable_signing_identity.py
bash -n tools/sign_test.sh
bash -n tools/sign_stable.sh
bash -n scripts/verify_dev17_to_dev18_update.sh
bash -n scripts/verify_dev18_to_dev19_update.sh
bash -n scripts/verify_dev18_to_dev20_update.sh
bash -n scripts/verify_dev21_to_dev22_update.sh
bash -n scripts/verify_dev22_to_dev23_update.sh
if [[ -f scripts/verify_dev23_to_dev24_update.sh ]]; then
  bash -n scripts/verify_dev23_to_dev24_update.sh
fi
if [[ -f scripts/verify_dev24_to_dev25_update.sh ]]; then
  bash -n scripts/verify_dev24_to_dev25_update.sh
fi
if [[ -f scripts/verify_dev24_to_dev26_update.sh ]]; then
  bash -n scripts/verify_dev24_to_dev26_update.sh
fi

MAIN='app/src/main/java/com/shaterguy/fc2weeklyranker/MainActivity.kt'
LAUNCHER='app/src/main/java/com/shaterguy/fc2weeklyranker/media/ExternalVideoPlayerLauncher.kt'
MANIFEST='app/src/main/AndroidManifest.xml'
grep -Fq 'createExternalVideoPlayerRequest(context, video)' "$MAIN"
grep -Fq 'context.startActivity(request.intent)' "$MAIN"
grep -Fq 'ExternalVideoStreamSessions.create(context, video)' "$LAUNCHER"
grep -Fq 'Intent(Intent.ACTION_VIEW)' "$LAUNCHER"
grep -Fq 'setDataAndType(handle.uri, handle.mimeType)' "$LAUNCHER"
grep -Fq 'addFlags(handle.intentFlags)' "$LAUNCHER"
grep -Fq 'Text("외부 플레이어"' "$MAIN"
grep -Fq 'contentDescription = "영상 ${index + 1} 외부 플레이어로 열기"' "$MAIN"
grep -Fq 'android:name=".media.ExternalVideoStreamProvider"' "$MANIFEST"
grep -Fq 'android:exported="false"' "$MANIFEST"
grep -Fq 'android:grantUriPermissions="true"' "$MANIFEST"
if grep -Fq 'setDataAndType(Uri.parse(url), "video/*")' "$MAIN"; then
  echo 'ERROR: external-player action still exposes the raw upstream URL.' >&2
  exit 1
fi
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

SMOOTHNESS_TEST='app/src/test/java/com/shaterguy/fc2weeklyranker/media/ExternalVideoTransportSmoothnessTest.kt'
if [[ -f "$SMOOTHNESS_TEST" ]]; then
  shopt -s nullglob
  smoothness_xml=(app/build/test-results/testDebugUnitTest/TEST-*.xml)
  shopt -u nullglob
  if (( ${#smoothness_xml[@]} == 0 )); then
    echo 'ERROR: smoothness test result XML is missing.' >&2
    exit 1
  fi
  for marker in FC2_SMOOTHNESS_METRIC FC2_READ_AHEAD_METRIC FC2_PRODUCTION_LATENCY_METRIC FC2_PRODUCTION_SHORT_RANGE_METRIC; do
    metric="$(grep -h -o "${marker}[^<]*" "${smoothness_xml[@]}" | tail -n 1 || true)"
    if [[ -z "$metric" ]]; then
      echo "ERROR: $marker was not emitted by the smoothness fixture." >&2
      exit 1
    fi
    printf '%s\n' "$metric"
  done
fi

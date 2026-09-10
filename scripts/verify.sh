#!/usr/bin/env bash
set -euo pipefail

python3 -m py_compile tools/derive_test_signing_identity.py tools/derive_stable_signing_identity.py
bash -n tools/sign_test.sh
bash -n tools/sign_stable.sh
bash -n scripts/verify_dev17_to_dev18_update.sh
gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug

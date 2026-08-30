#!/usr/bin/env bash
set -euo pipefail

python3 -m py_compile tools/derive_test_signing_identity.py
bash -n tools/sign_test.sh
gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug

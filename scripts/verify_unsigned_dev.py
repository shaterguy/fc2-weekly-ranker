"""Verify local-build identity before protected DEV signing and again after signing."""
import hashlib
import json
import re
import sys
from pathlib import Path

stage, expected_source, tag = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
identity = json.loads((stage / "UNSIGNED_IDENTITY.json").read_text())
config = Path("app/build.gradle.kts").read_text()
version = re.search(r'versionName = "([^"]+)"', config)[1]
suffix = re.search(r'versionNameSuffix = "([^"]+)"', config)[1]
code = int(re.search(r'versionCode = (\d+)', config)[1])
assert tag == "v" + version + suffix
assert identity["source_commit"] == expected_source
assert identity["tag"] == tag
assert identity["version_code"] == code
assert identity["application_id"] == "com.shaterguy.fc2weeklyranker.dev"
apk = stage / ("FC2WeeklyRanker-" + tag + ".unsigned.apk")
assert apk.stat().st_size == identity["size"]
assert hashlib.sha256(apk.read_bytes()).hexdigest() == identity["sha256"]
if "--signed" in sys.argv:
    badging = (stage / "APK_BADGING.txt").read_text()
    assert ("package: name='com.shaterguy.fc2weeklyranker.dev' versionCode='" + str(code)
            + "' versionName='" + version + suffix + "'") in badging
    assert "certificate_sha256=ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd" in (
        stage / "APKSIGNER_VERIFY.txt").read_text()
print("DEV_IDENTITY_VERIFIED source=" + expected_source + " tag=" + tag)

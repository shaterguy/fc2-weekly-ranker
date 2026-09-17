from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


old_script = Path("scripts/verify_dev34_to_dev35_update.sh").read_text()
script = old_script
script = script.replace("dev34", "__OLD_LOWER__").replace("DEV34", "__OLD_UPPER__")
script = script.replace("dev35", "__NEW_LOWER__").replace("DEV35", "__NEW_UPPER__")
script = script.replace("__OLD_LOWER__", "dev35").replace("__OLD_UPPER__", "DEV35")
script = script.replace("__NEW_LOWER__", "dev36").replace("__NEW_UPPER__", "DEV36")
script = script.replace("versionCode='59' versionName='0.2.0-dev36'", "versionCode='60' versionName='0.2.0-dev36'")
script = script.replace("versionCode='58' versionName='0.2.0-dev35'", "versionCode='59' versionName='0.2.0-dev35'")
Path("scripts/verify_dev35_to_dev36_update.sh").write_text(script)

replace_once(
    "scripts/verify.sh",
    "bash -n scripts/verify_dev34_to_dev35_update.sh",
    "bash -n scripts/verify_dev34_to_dev35_update.sh\nbash -n scripts/verify_dev35_to_dev36_update.sh",
)

workflow = Path(".github/workflows/android-test.yml")
text = workflow.read_text()
text = text.replace(
    "test -f scripts/verify_dev34_to_dev35_update.sh",
    "test -f scripts/verify_dev34_to_dev35_update.sh\n          test -f scripts/verify_dev35_to_dev36_update.sh",
    1,
)
text = text.replace("versionCode='59' versionName='0.2.0-dev35'", "versionCode='60' versionName='0.2.0-dev36'")
text = text.replace("fc2-weekly-ranker-test-v0.2.0-dev35", "fc2-weekly-ranker-test-v0.2.0-dev36")
text = text.replace("versionName=0.2.0-dev35", "versionName=0.2.0-dev36")
text = text.replace("REUSE_ADB=1 bash scripts/verify_dev34_to_dev35_update.sh", "REUSE_ADB=1 bash scripts/verify_dev35_to_dev36_update.sh")
text = text.replace('NEW_DB_DIR="$RUNNER_TEMP/dev35-db"', 'NEW_DB_DIR="$RUNNER_TEMP/dev36-db"')
workflow.write_text(text)

baseline = Path("app/src/test/java/com/shaterguy/fc2weeklyranker/network/AvseeClientMixedDateBaselineTest.kt")
if not baseline.exists():
    raise SystemExit("mixed-date baseline test missing")
perf = baseline.read_text()
perf = perf.replace("class AvseeClientMixedDateBaselineTest", "class AvseeClientMixedDatePerformanceTest", 1)
perf = perf.replace(
    "DEV36_MIXED_DATE_BASELINE source=dev35 scenario=latest24-mixed",
    "DEV36_MIXED_DATE_CANDIDATE source=dev36 scenario=latest24-mixed",
    1,
)
Path("app/src/test/java/com/shaterguy/fc2weeklyranker/network/AvseeClientMixedDatePerformanceTest.kt").write_text(perf)
baseline.unlink()

print("dev36 release/test consistency transformation applied")

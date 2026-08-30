#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <candidate-apk> <signing-pass-file> <output-apk>" >&2
  exit 2
fi

INPUT="$1"
PASS_FILE="$2"
OUTPUT="$3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
STAGE=init
cleanup() {
  rc=$?
  trap - EXIT
  if (( rc != 0 )); then
    echo "sign_test failed at stage=$STAGE" >&2
  fi
  rm -rf "$TMP"
  exit "$rc"
}
trap cleanup EXIT
APKSIGNER="${APKSIGNER:-apksigner}"

STAGE=validate-input
[[ -s "$INPUT" ]] || { echo "candidate APK not found or empty" >&2; exit 1; }
[[ -s "$PASS_FILE" ]] || { echo "signing passphrase file not found or empty" >&2; exit 1; }
command -v "$APKSIGNER" >/dev/null 2>&1 || { echo "apksigner not found" >&2; exit 1; }

STAGE=derive-identity
python3 "$ROOT/tools/derive_test_signing_identity.py" \
  --pass-file "$PASS_FILE" \
  --out-key "$TMP/fc2-weekly-ranker-test.pk8" \
  --out-cert "$TMP/fc2-weekly-ranker-test-cert.pem" \
  > "$TMP/cert-sha256.txt"

DERIVED_CERT_SHA256="$(tr -d '[:space:]' < "$TMP/cert-sha256.txt" | tr '[:upper:]' '[:lower:]')"
[[ "$DERIVED_CERT_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "invalid derived certificate fingerprint" >&2; exit 1; }

STAGE=apk-sign
rm -f "$OUTPUT"
"$APKSIGNER" sign \
  --key "$TMP/fc2-weekly-ranker-test.pk8" \
  --cert "$TMP/fc2-weekly-ranker-test-cert.pem" \
  --min-sdk-version 29 \
  --v1-signing-enabled false \
  --v2-signing-enabled false \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUTPUT" \
  "$INPUT"

VERIFY_LOG="$TMP/apksigner-verify.txt"
STAGE=apk-verify
"$APKSIGNER" verify --verbose --print-certs --min-sdk-version 29 "$OUTPUT" | tee "$VERIFY_LOG"
STAGE=verify-v3
grep -Fq "Verified using v3 scheme (APK Signature Scheme v3): true" "$VERIFY_LOG"
STAGE=verify-signer-count
grep -Fq "Number of signers: 1" "$VERIFY_LOG"
STAGE=verify-cert-digest
SIGNED_CERT_SHA256="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}' "$VERIFY_LOG" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')"
[[ "$SIGNED_CERT_SHA256" == "$DERIVED_CERT_SHA256" ]] || { echo "signed certificate does not match derived certificate" >&2; exit 1; }

STAGE=complete
sha256sum "$OUTPUT"
echo "certificate_sha256=$SIGNED_CERT_SHA256"
echo "signature_v3=true"
echo "signers=1"

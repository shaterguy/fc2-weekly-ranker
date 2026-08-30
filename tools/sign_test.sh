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
trap 'rm -rf "$TMP"' EXIT
EXPECTED_CERT_SHA256="3b9db303bc351b0f5b4c5076c6c4bb13c26ff2a4592c70f93c9ad5ee760e57f0"
APKSIGNER="${APKSIGNER:-apksigner}"

[[ -s "$INPUT" ]] || { echo "candidate APK not found or empty" >&2; exit 1; }
[[ -s "$PASS_FILE" ]] || { echo "signing passphrase file not found or empty" >&2; exit 1; }
command -v "$APKSIGNER" >/dev/null 2>&1 || { echo "apksigner not found" >&2; exit 1; }

python3 "$ROOT/tools/derive_test_signing_identity.py" \
  --pass-file "$PASS_FILE" \
  --out-p12 "$TMP/fc2-weekly-ranker-test.p12" \
  --out-cert "$TMP/fc2-weekly-ranker-test-cert.pem" \
  > "$TMP/cert-sha256.txt"

DERIVED_CERT_SHA256="$(tr -d '[:space:]' < "$TMP/cert-sha256.txt" | tr '[:upper:]' '[:lower:]')"
[[ "$DERIVED_CERT_SHA256" == "$EXPECTED_CERT_SHA256" ]] || { echo "derived certificate mismatch" >&2; exit 1; }

rm -f "$OUTPUT"
"$APKSIGNER" sign \
  --ks "$TMP/fc2-weekly-ranker-test.p12" \
  --ks-type PKCS12 \
  --ks-key-alias fc2-weekly-ranker-test \
  --ks-pass "file:$PASS_FILE" \
  --min-sdk-version 29 \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUTPUT" \
  "$INPUT"

VERIFY_LOG="$TMP/apksigner-verify.txt"
"$APKSIGNER" verify --verbose --print-certs --min-sdk-version 29 "$OUTPUT" | tee "$VERIFY_LOG"
grep -Fq "Verified using v2 scheme (APK Signature Scheme v2): true" "$VERIFY_LOG"
grep -Fq "Verified using v3 scheme (APK Signature Scheme v3): true" "$VERIFY_LOG"
grep -Fq "Number of signers: 1" "$VERIFY_LOG"
SIGNED_CERT_SHA256="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}' "$VERIFY_LOG" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')"
[[ "$SIGNED_CERT_SHA256" == "$EXPECTED_CERT_SHA256" ]] || { echo "signed certificate mismatch" >&2; exit 1; }
sha256sum "$OUTPUT"
echo "certificate_sha256=$SIGNED_CERT_SHA256"
echo "signature_v2=true"
echo "signature_v3=true"
echo "signers=1"

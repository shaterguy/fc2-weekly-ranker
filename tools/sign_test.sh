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
PINNED_TEST_CERT_SHA256="ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd"

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
STAGE=verify-pinned-derived-cert
[[ "$DERIVED_CERT_SHA256" == "$PINNED_TEST_CERT_SHA256" ]] || { echo "derived certificate does not match pinned TEST certificate" >&2; exit 1; }

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
"$APKSIGNER" verify --verbose --print-certs-pem --min-sdk-version 29 "$OUTPUT" | tee "$VERIFY_LOG"
STAGE=verify-v3
grep -Fq "Verified using v3 scheme (APK Signature Scheme v3): true" "$VERIFY_LOG"
STAGE=verify-signer-count
grep -Fq "Number of signers: 1" "$VERIFY_LOG"
STAGE=verify-cert-digest
SIGNED_CERT_SHA256="$(python3 - "$VERIFY_LOG" "$PINNED_TEST_CERT_SHA256" <<'PY'
import re
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes

data = Path(sys.argv[1]).read_bytes()
expected = sys.argv[2].lower()
blocks = re.findall(
    rb"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
    data,
    flags=re.S,
)
if not blocks:
    raise SystemExit("no signer certificate PEM found")
fingerprints = {
    x509.load_pem_x509_certificate(block + b"\n").fingerprint(hashes.SHA256()).hex()
    for block in blocks
}
if expected not in fingerprints:
    raise SystemExit("signed certificate does not match pinned TEST certificate")
print(expected)
PY
)"
[[ "$SIGNED_CERT_SHA256" == "$PINNED_TEST_CERT_SHA256" ]] || { echo "signed certificate does not match pinned TEST certificate" >&2; exit 1; }

STAGE=complete
sha256sum "$OUTPUT"
echo "certificate_sha256=$SIGNED_CERT_SHA256"
echo "signature_v3=true"
echo "signers=1"

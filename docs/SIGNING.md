# TEST signing identity

The TEST package is `com.shaterguy.fc2weeklyranker.dev`.

Its signing identity is deterministically derived from the private repository Actions secret with `tools/derive_test_signing_identity.py`. The passphrase, derived private key, PKCS12 keystore, and raw signing material must never be committed, logged, or uploaded as a GitHub artifact.

This repository is establishing its first TEST signing lineage. No certificate fingerprint is authoritative until a successful GitHub Actions run signs and verifies the installable TEST APK with the repository secret. That first successful run records the public certificate SHA-256 in `TEST_CERT_SHA256.txt`; the next signing-lineage commit must pin that exact fingerprint in the signing script, workflow verification, and this document before final TEST delivery.

The bootstrap signing run verifies that the APK is signed by the certificate derived from the configured secret, uses APK Signature Scheme v2 and v3, has exactly one signer, and preserves the expected TEST package/version identity.

Future TEST versions must retain the pinned certificate and increase `versionCode` so they can update the previous TEST installation in place.

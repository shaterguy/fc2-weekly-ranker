# TEST signing identity

The TEST package is `com.shaterguy.fc2weeklyranker.dev`.

Its signing identity is deterministically derived from a private out-of-repository passphrase using `tools/derive_test_signing_identity.py`. The passphrase, derived private key, PKCS12 keystore, and raw signing material must never be committed, logged, or uploaded as a GitHub artifact.

Expected TEST certificate SHA-256:

`3b9db303bc351b0f5b4c5076c6c4bb13c26ff2a4592c70f93c9ad5ee760e57f0`

Release packaging signs the exact successful GitHub Actions APK with `tools/sign_test.sh`. `apksigner` strips any pre-existing debug signature and applies the fixed TEST identity with APK Signature Scheme v2 and v3. Future TEST versions must retain this certificate and increase `versionCode` so they can update the previous TEST installation in place.

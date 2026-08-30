# TEST signing identity

The TEST package is `com.shaterguy.fc2weeklyranker.dev`.

Its signing identity is deterministically derived from a private out-of-repository passphrase using `tools/derive_test_signing_identity.py`. The passphrase and derived private signing material must never be committed, logged, or uploaded as a GitHub artifact. CI writes the derived PKCS #8 private key and X.509 certificate only inside its temporary runner directory, signs with `apksigner --key/--cert`, and removes the temporary files when the signing script exits.

The first successfully signed TEST APK establishes the certificate SHA-256 for this TEST lineage. That public fingerprint must then be pinned in the repository and verified for every later TEST build before it is delivered.

Release packaging signs the exact successful GitHub Actions APK with `tools/sign_test.sh`. The TEST APK uses APK Signature Scheme v2 and v3 with one signer. Future TEST versions must retain the pinned certificate and increase `versionCode` so they can update the previous TEST installation in place.

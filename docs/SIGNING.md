# TEST signing identity

The TEST package is `com.shaterguy.fc2weeklyranker.dev`.

Its signing identity is deterministically derived from a private out-of-repository passphrase using `tools/derive_test_signing_identity.py`. The passphrase and derived private signing material must never be committed, logged, or uploaded as a GitHub artifact. CI writes the derived PKCS #8 private key and X.509 certificate only inside its temporary runner directory, signs with `apksigner --key/--cert`, and removes the temporary files when the signing script exits.

The canonical TEST signing certificate SHA-256 is `ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd`. Every TEST build must prove that both the deterministically derived certificate and the certificate embedded in the signed APK match this pinned fingerprint before the APK is delivered.

The TEST app has `minSdk=29` (Android 10). Release packaging therefore requires APK Signature Scheme v3 with one signer; v2 is not required for the supported platform range. Future TEST versions must retain the pinned certificate and increase `versionCode` so they can update the previous TEST installation in place.

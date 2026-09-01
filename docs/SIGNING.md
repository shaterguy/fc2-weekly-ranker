# Android signing identities

## TEST signing identity

The TEST package is `com.shaterguy.fc2weeklyranker.dev`.

Its signing identity is deterministically derived from a private out-of-repository passphrase using `tools/derive_test_signing_identity.py`. The passphrase and derived private signing material must never be committed, logged, or uploaded as a GitHub artifact. CI writes the derived PKCS #8 private key and X.509 certificate only inside its temporary runner directory, signs with `apksigner --key/--cert`, and removes the temporary files when the signing script exits.

The canonical TEST signing certificate SHA-256 is `ff32473e516ff59ca24ada94fe22e8282ce70fd66bd94fb0975340106d981cfd`. Every TEST build must prove that both the deterministically derived certificate and the certificate embedded in the signed APK match this pinned fingerprint before the APK is delivered.

The TEST app has `minSdk=29` (Android 10). TEST packaging uses APK Signature Scheme v3 with one signer. Future TEST versions must retain the pinned certificate and increase `versionCode` so they can update the previous TEST installation in place.

## STABLE signing identity

The STABLE package is `com.shaterguy.fc2weeklyranker`.

The first STABLE release, `v0.1.0`, establishes a separate signing lineage. `tools/derive_stable_signing_identity.py` uses a STABLE-only derivation domain and certificate subject, so its private key and X.509 certificate are different from the TEST identity even when CI supplies the same protected root signing seed. `tools/sign_stable.sh` fails if the derived STABLE fingerprint equals the pinned TEST fingerprint and independently verifies that the certificate embedded in the signed APK matches the derived STABLE certificate.

The root signing seed and derived private signing material remain outside the repository and artifacts. CI writes them only to temporary runner files with restricted permissions and removes them when signing completes. Public release evidence contains only the STABLE certificate SHA-256 fingerprint, APK SHA-256, and source commit.

For `v0.1.0`, the successful RC packaging run establishes the canonical STABLE certificate SHA-256. That fingerprint must be recorded in the GitHub Release and Vibe Coding release-state metadata and becomes the update baseline for every later STABLE release. Later STABLE APKs must use the same package ID and signing certificate with a monotonically increasing `versionCode`; replacing the certificate or requiring uninstall/reinstall is not a valid normal update path.

The STABLE app also has `minSdk=29` and uses APK Signature Scheme v3 with one signer.

# DEV56 ranking refresh performance

Baseline: v0.2.0-dev55, 7b5a2a8c663ed322af59cda2e3b73e810a5a48f5. Candidate: v0.2.0-dev56, versionCode 80, com.shaterguy.fc2weeklyranker.dev, pinned TEST certificate unchanged.

Request: accelerate complete refresh on https://04.avsee.is. AC: remove rendering/resource waits from ranking requests, keep fresh comments and complete seven-day membership, deliver compatible signed DEV APK. NR: preserve DEV55 comment search, ordinary WebView/login/media behavior, cancellation, configured-origin restriction and stored data. No site DB access or content changes.

Observed: production serializes all requests through one WebView mutex; previous crawler benchmarks use OkHttp and omit that cost. Board/detail navigation waits for load-complete including images/frames. HTTP and existing Chromium return a Cloudflare challenge; automatic verification observed for 40 seconds did not expose the live board. No new parser selector, remote DB schema or page-size assumption is introduced. Existing fields are preserved and unsupported responses fall back to navigation.

Change: ranking-only same-origin HTML fetch inside the existing browser, no-store, redirect:error, bounded streamed body, cancellation abort/delete. Keep the mutex/session lifetime. Returned HTML never executes or enters the DOM. Non-ranking requests and unsuccessful fast requests retain the previous navigation path.

APPLICABLE_PREVENTION_RULES: PR-003, PR-008. VERIFICATION_MODE: INDEPENDENT. SECURITY_DELTA: LIMITED; configured origin and browser cookie boundary are preserved.

Verification: new production-transport routing/fallback/cancellation tests and actual Chromium execution of the production fetch script on neutral delayed-resource fixtures. Rerun affected crawl membership/date/cache tests. Verify APK package/version/signature/checksum and GitHub source. Real-site timing remains unverified while the site's challenge persists.


## Evidence and delivery plan

- Chromium production-script delayed-resource fixture: five alternating navigation/HTML samples, medians 915.49 ms / 87.26 ms (90.47% reduction per synthetic page). No response scripts executed; HTML requests caused no image/frame requests. HTTP403, cross-origin redirect rejection, non-HTML response, size cap, wrong origin, cancellation/slot deletion passed.
- Local network regression: 122 tests passed. Twenty-one Robolectric cases failed during application setup because Linux/aarch64 native runtime is unsupported, not due to product assertions. The supported x86 verification job runs the affected transport/policy/callback/candidate tests before protected signing. No emulator or APK rebuild is part of this remote job.
- Ranking navigation fallback requires a newly observed, positive finite timeOrigin after a positive finite previous-document sample. It cannot reuse a prior complete document at the same URL; ordinary search/media navigation remains unchanged.
- Local native build and updated JVM test sources compile successfully. No connected ADB target was available; real device upgrade/runtime and source-site timing are post-delivery checks. Pinned TEST signer, package and increasing versionCode are verified automatically.
- Build/packaging: preserve exact local unsigned APK and its source/size/hash in an authenticated draft prerelease; sign only those verified bytes using the existing protected signing secret. Publish the DEV prerelease only after focused x86 tests, final signature and downloaded-asset identity verification. No stable promotion or main change.

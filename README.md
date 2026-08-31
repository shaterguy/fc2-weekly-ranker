# FC2 Weekly Ranker

Android TEST app for browsing the configured `javfc2` board in fixed seven-day windows and ranking posts by recommendation rate.

## TEST channel

- Branch lineage starts at `v0.1.0-dev1`.
- Source version: `0.1.0-dev9`, `versionCode=9`.
- TEST application ID: `com.shaterguy.fc2weeklyranker.dev`.
- The configured default origin is `https://01.avsee.is`; users can replace it in Settings after a board-and-detail parsing connection check.
- Board/detail requests use the platform DNS resolver directly; application-level synchronous DNS retries are intentionally avoided so source resolution cannot multiply-block the crawl.
- The anchor instant is persisted in DataStore and changes only when the user explicitly refreshes it.
- Page `n` covers `anchorDate-(7n+6)` through `anchorDate-7n` in `Asia/Seoul`.
- Recent relative posting timestamps such as `3시간전` are resolved against the actual detail observation time; yearless timestamps such as `MM.dd HH:mm` use the ranking window only to infer the year.
- Posting metadata prefers the legacy post-info selector but can recover from selector drift by choosing the smallest header-shaped element with a recognized timestamp and at least three numeric metrics before it; comment-only timestamp rows do not qualify.
- Ranking rate is `recommendations / max(1 day, exact elapsed time)`.
- If every detail on a board page fails parsing, the crawl reports a source-format failure instead of silently returning an empty ranking.

## Media path

The detail screen renders media only. Static `video`, `source`, media links, and `iframe` sources are parsed first. Direct sources use Media3 with the same `Referer`, user agent, and runtime WebView cookie context. Iframe wrapper query values that expose an `http://` media URL are promoted to `https://` before the source is accepted; cleartext playback and downloads remain disabled. Iframe-only sources use a restricted WebView player that observes HTTPS media requests and `currentSrc`; discovered direct sources are stored without persisting cookies.

Downloads are unique WorkManager jobs and write to `MediaStore.Downloads`, with HTTP Range resume when the server returns `206`.

## Security boundary

- HTTPS origins only; local/IP/custom-scheme base URLs are rejected.
- Cleartext traffic is disabled.
- WebView file/content access and mixed content are disabled.
- No `addJavascriptInterface` bridge is exposed to remote pages.
- Session cookies and full request headers are not logged or committed.
- The app requests only `INTERNET`; shared downloads use scoped storage.

## Remote verification

GitHub Actions is the build authority. `scripts/verify.sh` is the canonical candidate/release verification entry point. Action dependencies are pinned to commit SHAs, and artifact identity includes both `github.run_id` and `github.run_attempt` so reruns cannot collide.

The repository intentionally does not contain real media, real session material, or copied source-site content fixtures; parser tests use synthetic HTML shaped like the supported page contract.

# FC2 Weekly Ranker

Android TEST app for browsing the configured `javfc2` board in fixed seven-day windows and ranking posts by recommendation rate.

## TEST channel

- Branch lineage starts at `v0.1.0-dev1`.
- Source version: `0.1.0-dev23`, `versionCode=23`. The crawler preserves the live board-card, published-time, recommendation-count, bounded-concurrency, and next-page-prefetch behavior from dev22 while detail media now waits for the current detail refresh before cached rows can become active.
- TEST application ID: `com.shaterguy.fc2weeklyranker.dev`.
- The configured default origin is `https://01.avsee.is`; users can replace it in Settings after a board-and-detail parsing connection check.
- The anchor instant is persisted in DataStore and changes only when the user explicitly refreshes it.
- Page `n` covers `anchorDate-(7n+6)` through `anchorDate-7n` in `Asia/Seoul`.
- Exact `itemprop=datePublished` KST timestamps are preferred; yearless fallback timestamps such as `MM.dd HH:mm` are resolved relative to the ranking window in `Asia/Seoul`.
- Ranking rate is `recommendations / max(1 day, exact elapsed time)`.
- If every detail on a board page fails parsing, the crawl reports a source-format failure instead of silently returning an empty ranking.
- Only cards inside `#fboardlist .list-item` are crawled, excluding sidebar recommendations and unrelated new-post widgets. The board is requested in descending posting-time order and crawling stops only after every successfully parsed card on a page is older than the target window.
- Board and detail HTML already fetched during the current app session is reused across adjacent seven-day pages; up to eight detail requests run concurrently, and the next board page is prefetched while the current page details are being parsed. Manual anchor refresh clears this crawl cache.
- Recommendation counts are read from the live `#wr_good` contract, with prior explicit-label and metadata fallbacks retained.
- Navigation Compose page transitions are disabled at the app level; the app does not override Android's system animation scale.
- Ranking cards show a passive `★ 즐겨찾기` marker when the post is currently saved; the marker does not add a separate favorite action to the ranking list.

## Media path

The detail screen renders media only. Static `video`, `source`, media links, and `iframe` sources are parsed first. Direct sources use Media3 with the same `Referer`, user agent, and runtime WebView cookie context. Iframe-only sources use a restricted WebView player that accumulates all observed HTTPS media requests and performs a bounded series of delayed DOM probes during the first detail visit, so a second video that becomes ready after the first probe can still be discovered. Before the current detail refresh completes, cached direct rows and iframe resolvers remain inactive; after refresh, visible direct media is deduplicated by canonical media path while distinct delayed media remains separate. Query variants of the same resolved media path remain deduplicated, while distinct iframe resolver URLs retain their query identity before resolution. Resolved iframe media keep stable resolver-slot identities; stale probe rows are hidden rather than deleted so repeated detail visits do not accumulate duplicate cards or cascade-delete download state. Media3 players expose full-screen viewing without recreating the playback session.

Downloads are unique WorkManager jobs and write to `MediaStore.Downloads`. Download state and byte progress are persisted in Room so navigation or app background/foreground transitions reattach to the current state. File downloads support pause/resume with HTTP Range when the server returns `206`, plus explicit stop that removes an unfinished MediaStore entry.

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

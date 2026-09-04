# FC2 Weekly Ranker

Android app for browsing the configured `javfc2` board in fixed seven-day windows and ranking posts by comment rate. The repository keeps separate STABLE and TEST install lineages from the same functional source.

## STABLE channel

- `v0.1.0` adopts the verified functional state of `v0.1.0-dev24`.
- STABLE application ID: `com.shaterguy.fc2weeklyranker`.
- STABLE version: `0.1.0`, `versionCode=24`.
- The STABLE APK uses a signing identity separate from TEST. The first `v0.1.0` RC packaging run establishes the canonical STABLE certificate fingerprint, which is recorded with the release evidence and used as the update baseline for later stable versions.
- Release packaging is performed only by GitHub Actions from a verified RC commit; private signing material is never committed or uploaded as an artifact.

## TEST channel

- Historical TEST lineage starts at `v0.1.0-dev1`; the current development target is `v0.2.0-dev10`.
- Source version: `0.2.0-dev10`, `versionCode=34`.
- TEST application ID: `com.shaterguy.fc2weeklyranker.dev`.
- The configured default origin is `https://01.avsee.is`; users can replace it in Settings after a board-and-detail parsing connection check.
- The anchor instant is persisted in DataStore and changes only when the user explicitly refreshes it.
- Page `n` covers `anchorDate-(7n+6)` through `anchorDate-7n` in `Asia/Seoul`.
- Exact `itemprop=datePublished` KST timestamps are preferred; yearless fallback timestamps such as `MM.dd HH:mm` are resolved relative to the ranking window in `Asia/Seoul`.
- Ranking rate is `comments / max(1 day, elapsed calendar days)`.
- Ranking post ID, URL, title, posting date, and latest observed comment count are retained in Room as a persistent local catalog. Ranking lists query this catalog by posting-date range instead of an anchor-specific snapshot key, so cached posts remain immediately visible after an anchor refresh and are locally re-ranked for the new anchor.
- Successfully crawled seven-day windows are retained in DataStore, including genuine zero-post windows. Revisiting a covered window uses the local catalog without another crawl; explicit anchor refresh still performs a live page-zero sync.
- Live sync passes persisted post dates into the crawler. Existing IDs therefore reuse their known posting dates while board-list comment counts are refreshed; only newly discovered IDs need detail-page date resolution.
- Historical first-load lookup no longer has a fixed 30-page reach. When the target window is older than page 1, the crawler uses exponential page probing followed by binary search to locate the first overlapping board page, then reads only the pages that overlap the requested seven-day interval. Safety limits fail loudly rather than silently returning a truncated history.
- If every detail needed for a new date boundary fails parsing, the crawl reports a source-format failure instead of silently returning an empty ranking.
- Only cards inside `#fboardlist .list-item` are crawled, excluding sidebar recommendations and unrelated new-post widgets. The board is requested in descending posting-time order and date-order violations abort automatic classification.
- Board and detail HTML already fetched during the current app session is reused while a crawl is in progress. Manual anchor refresh clears this transient HTML cache only; Room/DataStore ranking metadata remains intact.
- Ranking-only detail parsing skips media discovery because ranking needs only ID, title, posting date, and comment count. Full media discovery remains on the detail-screen path.
- After a foreground seven-day page is available, the app prefetches exactly one adjacent older seven-day page. A completed persistent-window prefetch is consumed by `이전 7일` without another foreground crawl; failures fall back to the normal foreground load.
- Navigation Compose page transitions are disabled at the app level; the app does not override Android's system animation scale.
- Ranking cards show a passive `★ 즐겨찾기` marker when the post is currently saved; the marker does not add a separate favorite action to the ranking list.

## Media path

The detail screen renders media only. Static `video`, `source`, media links, and `iframe` sources are parsed first. Direct sources use Media3 with the same `Referer`, user agent, and runtime WebView cookie context. Iframe-only sources use a restricted WebView player that accumulates all observed HTTPS media requests and performs a bounded series of delayed DOM probes during the first detail visit, so a second video that becomes ready after the first probe can still be discovered. Before the current detail refresh completes, cached direct rows and iframe resolvers remain inactive; after refresh, visible direct media is deduplicated by canonical media path while distinct delayed media remains separate. When a new detail entry for the same post begins, any still-pending probe registration jobs from the previous detail entry are cancelled before the refresh cutoff is captured, preventing a logically stale callback from being recorded as current media. Query variants of the same resolved media path remain deduplicated, while distinct iframe resolver URLs retain their query identity before resolution. Resolved iframe media keep stable resolver-slot identities; stale probe rows are hidden rather than deleted so repeated detail visits do not accumulate duplicate cards or cascade-delete download state. Media3 players expose full-screen viewing without recreating the playback session.

Downloads are unique WorkManager jobs and write to `MediaStore.Downloads`. Download state and byte progress are persisted in Room so navigation or app background/foreground transitions reattach to the current state. File downloads support pause/resume with HTTP Range when the server returns `206`, plus explicit cancel that removes an unfinished MediaStore entry. New downloads preserve the direct source filename when it is a safe usable basename. The Download tab shows active transfers and completed history; deleting a history row does not delete the completed MediaStore file.

## Security boundary

- HTTPS origins only; local/IP/custom-scheme base URLs are rejected.
- Cleartext traffic is disabled.
- WebView file/content access and mixed content are disabled.
- No `addJavascriptInterface` bridge is exposed to remote pages.
- Session cookies and full request headers are not logged or committed.
- The app requests only `INTERNET`; shared downloads use scoped storage.

## Remote verification

GitHub Actions is the build authority. `scripts/verify.sh` is the canonical functional candidate verification entry point. `Android TEST` verifies the debug/TEST lineage, and `Android STABLE RC` packages and verifies the release/STABLE lineage from an RC commit. Action dependencies are pinned to commit SHAs, and evidence artifact identity includes both `github.run_id` and `github.run_attempt` so reruns cannot collide.

The repository intentionally does not contain real media, real session material, or copied source-site content fixtures; parser tests use synthetic HTML shaped like the supported page contract.

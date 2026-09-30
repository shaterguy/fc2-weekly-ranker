package com.shaterguy.fc2weeklyranker.network

/** Accept parsed search results without waiting for unrelated images or frames. */
internal fun isUsableApplicationDocument(
    readyState: String,
    timeOrigin: Double,
    previousTimeOrigin: Double,
    domContentLoaded: Boolean = false,
    requestedPath: String = "/bbs/search.php",
    hasResultRows: Boolean = false,
    isFirstNavigation: Boolean = false,
): Boolean {
    // Other application documents keep their existing load-complete contract.
    if (requestedPath != "/bbs/search.php" && requestedPath != "/bbs/tag.php") {
        return readyState == "complete"
    }
    val isNewDocument = if (isFirstNavigation) {
        previousTimeOrigin == 0.0
    } else {
        previousTimeOrigin.isFinite() && previousTimeOrigin > 0.0 && timeOrigin != previousTimeOrigin
    }
    return timeOrigin.isFinite() && timeOrigin > 0.0 && isNewDocument &&
        (readyState == "complete" ||
            (readyState == "interactive" && domContentLoaded && hasResultRows))
}

internal fun hasReadyApplicationSearchContent(
    readyState: String,
    html: String,
    pageUrl: String,
    hasUsableResults: (String, String) -> Boolean,
): Boolean = readyState == "complete" || hasUsableResults(html, pageUrl)

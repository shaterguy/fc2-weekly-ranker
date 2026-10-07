package com.shaterguy.fc2weeklyranker.network

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

// shouldOverrideUrlLoading is not invoked for POST. Intercept the initial
// main-frame request as well; never inspect its headers or submission body.
internal fun rejectUnconfiguredMainFrame(
    configuredOrigin: String,
    request: WebResourceRequest,
): WebResourceResponse? = if (
    request.isForMainFrame && !isApplicationNavigationAllowed(configuredOrigin, request.url.toString())
) {
    WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
} else null

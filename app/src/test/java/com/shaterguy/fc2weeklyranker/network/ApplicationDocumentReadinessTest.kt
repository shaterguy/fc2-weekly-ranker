package com.shaterguy.fc2weeklyranker.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationDocumentReadinessTest {
    @Test fun freshCompleteDocumentIsReady() {
        assertTrue(isUsableApplicationDocument("complete", 2000.0, 1000.0))
    }
    @Test fun parsedFreshDocumentDoesNotWaitForUnrelatedResources() {
        assertTrue(isUsableApplicationDocument("interactive", 2000.0, 1000.0, domContentLoaded = true, hasResultRows = true))
    }
    @Test fun parsedFreshTagDocumentDoesNotWaitForUnrelatedResources() {
        assertTrue(isUsableApplicationDocument("interactive", 2000.0, 1000.0, domContentLoaded = true, requestedPath = "/bbs/tag.php", hasResultRows = true))
    }
    @Test fun interactiveDocumentWaitsForDeferredScripts() {
        assertFalse(isUsableApplicationDocument("interactive", 2000.0, 1000.0, hasResultRows = true))
    }
    @Test fun parsedDocumentWaitsForDelayedResultRows() {
        assertFalse(isUsableApplicationDocument("interactive", 2000.0, 1000.0, domContentLoaded = true))
    }
    @Test fun completeEmptyResultDocumentIsReady() {
        assertTrue(isUsableApplicationDocument("complete", 2000.0, 1000.0, domContentLoaded = true))
    }
    @Test fun previousCompleteDocumentCannotSatisfyRepeatedNavigation() {
        assertFalse(isUsableApplicationDocument("complete", 1000.0, 1000.0))
    }
    @Test fun previousInteractiveDocumentIsNotReady() {
        assertFalse(isUsableApplicationDocument("interactive", 1000.0, 1000.0, domContentLoaded = true, hasResultRows = true))
    }
    @Test fun loadingNewDocumentIsNotReady() {
        assertFalse(isUsableApplicationDocument("loading", 2000.0, 1000.0))
    }
    @Test fun unknownReadyStateIsNotAccepted() {
        assertFalse(isUsableApplicationDocument("unknown", 2000.0, 1000.0))
    }
    @Test fun missingNewDocumentIdentityIsNotAccepted() {
        assertFalse(isUsableApplicationDocument("complete", 0.0, 1000.0))
    }
    @Test fun invalidNewDocumentIdentityIsNotAccepted() {
        assertFalse(isUsableApplicationDocument("complete", Double.NaN, 1000.0))
    }
    @Test fun missingPreviousDocumentIdentityIsNotAccepted() {
        assertFalse(isUsableApplicationDocument("complete", 2000.0, 0.0))
    }
    @Test fun otherApplicationPagesStillWaitForComplete() {
        assertFalse(isUsableApplicationDocument("interactive", 2000.0, 1000.0, domContentLoaded = true, requestedPath = "/bbs/board.php", hasResultRows = true))
    }
    @Test fun completeOtherApplicationPagesKeepBaselineBehavior() {
        assertTrue(isUsableApplicationDocument("complete", 0.0, 0.0, requestedPath = "/bbs/board.php"))
    }
    @Test fun similarPathsCannotOptInToEarlyCompletion() {
        assertFalse(isUsableApplicationDocument("interactive", 2000.0, 1000.0, domContentLoaded = true, requestedPath = "/bbs/search.php/other", hasResultRows = true))
    }
    @Test fun pathMatchingIsCaseSensitive() {
        assertFalse(isUsableApplicationDocument("interactive", 2000.0, 1000.0, domContentLoaded = true, requestedPath = "/bbs/SEARCH.php", hasResultRows = true))
    }
    @Test fun brandNewSessionDoesNotNeedAnInitialJavascriptCallback() {
        assertTrue(isUsableApplicationDocument("complete", 2000.0, 0.0, isFirstNavigation = true))
    }
    @Test fun brandNewSessionCanAcceptParsedResults() {
        assertTrue(isUsableApplicationDocument("interactive", 2000.0, 0.0, domContentLoaded = true, hasResultRows = true, isFirstNavigation = true))
    }
    @Test fun brandNewSessionStillRequiresCurrentDocumentIdentity() {
        assertFalse(isUsableApplicationDocument("complete", 0.0, 0.0, isFirstNavigation = true))
    }
}

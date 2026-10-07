package com.shaterguy.fc2weeklyranker.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchLoginRecoveryTest {
    @Test fun currentAuthenticationAndStoredProtectedDiagnosticsOfferLogin() {
        for (marker in listOf("AUTHENTICATION_REQUIRED", "HTTP_401", "HTTP_403")) {
            assertTrue(searchNeedsAuthentication("검색 실패: [DOCUMENT/$marker]"))
        }
    }

    @Test fun sameFailedRequestDoesNotReopenAfterBackOrDismiss() {
        val failure = "[DOCUMENT/HTTP_403]"
        val opened = SearchLoginPromptState().observe("request-1", failure, false, true)
        assertTrue(opened.visible)
        val dismissed = opened.dismiss()
        repeat(3) { assertFalse(dismissed.observe("request-1", failure, false, true).visible) }
    }

    @Test fun stateRestorationKeepsDismissedFailureConsumed() {
        val failure = "[DOCUMENT/HTTP_403]"
        val dismissed = SearchLoginPromptState().observe("request-1", failure, false, true).dismiss()
        val restored = SearchLoginPromptState.restore(dismissed.savedValue())
        assertEquals(dismissed, restored)
        assertFalse(restored.observe("request-1", failure, false, true).visible)
    }

    @Test fun repeatedSearchCanPromptForItsNewRequestEvenWithSameMessage() {
        val failure = "[DOCUMENT/HTTP_403]"
        val dismissed = SearchLoginPromptState().observe("request-1", failure, false, true).dismiss()
        assertTrue(dismissed.observe("request-2", failure, false, true).visible)
        assertTrue(dismissed.observe("request-1", null, false, true).observe("request-3", failure, false, true).visible)
    }

    @Test fun newRecoveryMessageCanPromptButSameFailedRequestStaysConsumedAfterDismiss() {
        val ordinary = SearchLoginPromptState().observe("request-1", "[DOCUMENT/HTTP_503]", false, true)
        assertFalse(ordinary.visible)
        val recovered = ordinary.observe("request-1", "[DOCUMENT/AUTHENTICATION_REQUIRED]", false, true)
        assertTrue(recovered.visible)
        assertFalse(recovered.dismiss().observe("request-1", "[DOCUMENT/HTTP_401]", false, true).visible)
    }

    @Test fun pendingCancellationAndForeignOriginDoNotPrompt() {
        val failure = "[DOCUMENT/HTTP_403]"
        val state = SearchLoginPromptState()
        assertFalse(state.observe("request-1", failure, true, true).visible)
        assertFalse(state.observe("request-1", failure, false, false).visible)
        assertFalse(state.observe("request-1", "[UNKNOWN/CANCELLED]", false, true).visible)
    }

    @Test fun loginReturnConsumesExactlyOneSearchRetry() {
        val (first, retryFirst) = SearchLoginPromptState().open().complete()
        val (second, retrySecond) = first.complete()
        assertTrue(retryFirst)
        assertFalse(retrySecond)
        assertFalse(first.visible)
        assertFalse(second.visible)
        assertFalse(SearchLoginPromptState().open().dismiss().complete().second)
    }

    @Test fun otherStagesStatusesAndUnstructuredMessagesDoNotTriggerLogin() {
        for (message in listOf(null, "HTTP_403", "[SESSION/HTTP_403]", "[DOCUMENT/HTTP_404]",
            "[DOCUMENT/HTTP_429]", "[DOCUMENT/HTTP_503]", "[DOCUMENT/TIMEOUT]", "[UNKNOWN/CANCELLED]")) {
            assertFalse(searchNeedsAuthentication(message))
        }
    }
}

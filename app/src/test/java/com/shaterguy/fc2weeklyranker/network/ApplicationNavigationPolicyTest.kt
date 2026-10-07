package com.shaterguy.fc2weeklyranker.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationNavigationPolicyTest {
    private val hosts = setOf("02.avsee.is")

    @Test fun explicitConfiguredHttpsOriginsHaveLoginWithoutFixedDomainList() {
        assertEquals("https://03.avsee.is/bbs/login.php", applicationLoginStartUrl("https://03.avsee.is"))
        assertEquals("https://new-search.example.test/bbs/login.php", applicationLoginStartUrl("https://new-search.example.test"))
    }

    @Test fun configuredGeneralSearchChallengeAndAuthenticationAreOriginAgnostic() {
        for (origin in listOf("https://03.avsee.is", "https://new-search.example.test")) {
            val search = "$origin/bbs/search.php?stx=neutral&onetable=javfc2&page=1"
            assertTrue(isAuthenticationRequiredSearchResponse(403, search, configuredOrigin = origin))
            assertTrue(shouldDeferSearchChallengeHttpFailure(403, search, false, true, configuredOrigin = origin))
            assertTrue(shouldResetDeferredSearchChallengeHttpFailure(403, search, search, configuredOrigin = origin))
        }
    }

    @Test fun protectedSearchReloadDoesNotTrustAnotherOrigin() {
        val requested = "https://02.avsee.is/bbs/search.php?stx=neutral"
        assertFalse(shouldResetDeferredSearchChallengeHttpFailure(403, requested, requested.replace("02.avsee.is", "01.avsee.is"), configuredOrigin = "https://02.avsee.is"))
        assertFalse(shouldResetDeferredSearchChallengeHttpFailure(403, requested, requested.replace("02.avsee.is", "03.avsee.is"), configuredOrigin = "https://02.avsee.is"))
    }

    @Test fun configuredLoginOriginIsValidatedHttps() {
        assertEquals("https://02.avsee.is/bbs/login.php", applicationLoginStartUrl("https://02.avsee.is"))
        assertNull(applicationLoginStartUrl("https://01.avsee.is/path"))
        assertNull(applicationLoginStartUrl("http://02.avsee.is"))
        assertEquals("https://example.invalid/bbs/login.php", applicationLoginStartUrl("https://example.invalid"))
        assertNull(applicationLoginStartUrl("https://user@03.avsee.is"))
        assertNull(applicationLoginStartUrl("https://03.avsee.is:444"))
        assertNull(applicationLoginStartUrl("https://03.avsee.is/?site=other"))
        assertNull(applicationLoginStartUrl("https://localhost"))
        assertNull(applicationLoginStartUrl("https://127.0.0.1"))
        assertNull(applicationLoginStartUrl("https:///bbs/login.php"))
        assertNull(applicationLoginStartUrl("https://bad_host.example.test"))
        assertNull(applicationLoginStartUrl("https://03.avsee.is\\@evil.test"))
        assertEquals("https://03.avsee.is/bbs/login.php", applicationLoginStartUrl("https://03.avsee.is:443"))
        assertEquals("https://03.avsee.is/bbs/login.php", applicationLoginStartUrl(" HTTPS://03.AVSEE.IS/ "))
        assertTrue(isApplicationNavigationAllowed("https://02.avsee.is", "https://02.avsee.is/bbs/login.php"))
        assertFalse(isApplicationNavigationAllowed("https://02.avsee.is", "http://02.avsee.is/bbs/login.php"))
        assertFalse(isApplicationNavigationAllowed("https://02.avsee.is", "https://example.invalid/bbs/login.php"))
        assertFalse(isApplicationNavigationAllowed("https://03.avsee.is", "https://03.avsee.is.evil.test/bbs/login.php"))
        assertFalse(isApplicationNavigationAllowed("https://03.avsee.is", "https://user@03.avsee.is/bbs/login.php"))
        assertFalse(isApplicationNavigationAllowed("https://03.avsee.is", "https://03.avsee.is:444/bbs/login.php"))
        assertFalse(isApplicationNavigationAllowed("https://03.avsee.is", "https:///bbs/login.php"))
        assertFalse(isApplicationNavigationAllowed("https://03.avsee.is", "https://bad_host.example.test/bbs/login.php"))
        assertFalse(isApplicationNavigationAllowed("https://03.avsee.is", "https://03.avsee.is\\@evil.test/bbs/login.php"))
        assertTrue(isApplicationNavigationAllowed("https://03.avsee.is", "https://03.avsee.is:443/bbs/login.php"))
    }

    @Test fun onlyProtectedGeneralSearch401Or403MapsToAuthentication() {
        val general = "https://02.avsee.is/bbs/search.php?stx=needle&onetable=javfc2&page=1"
        val tag = "https://02.avsee.is/bbs/tag.php?q=needle&page=1"
        assertTrue(isAuthenticationRequiredSearchResponse(401, general, configuredOrigin = "https://02.avsee.is"))
        assertTrue(isAuthenticationRequiredSearchResponse(403, general, configuredOrigin = "https://02.avsee.is"))
        assertFalse(isAuthenticationRequiredSearchResponse(403, tag, configuredOrigin = "https://02.avsee.is"))
        assertFalse(isAuthenticationRequiredSearchResponse(503, general, configuredOrigin = "https://02.avsee.is"))
    }

    @Test fun officialSearch403DefersOnlyWhileCurrentDocumentIsUnknownOrChallenge() {
        val general = "https://02.avsee.is/bbs/search.php?stx=needle&onetable=javfc2&page=1"
        val tag = "https://02.avsee.is/bbs/tag.php?q=needle&page=1"
        val foreign = "https://example.invalid/bbs/search.php?stx=needle"

        assertTrue(shouldDeferSearchChallengeHttpFailure(403, general, currentDocumentObserved = false, challenge = false, configuredOrigin = "https://02.avsee.is"))
        assertTrue(shouldDeferSearchChallengeHttpFailure(403, general, currentDocumentObserved = true, challenge = true, configuredOrigin = "https://02.avsee.is"))
        assertFalse(shouldDeferSearchChallengeHttpFailure(403, general, currentDocumentObserved = true, challenge = false, configuredOrigin = "https://02.avsee.is"))
        assertFalse(shouldDeferSearchChallengeHttpFailure(401, general, currentDocumentObserved = false, challenge = true, configuredOrigin = "https://02.avsee.is"))
        assertFalse(shouldDeferSearchChallengeHttpFailure(403, tag, currentDocumentObserved = false, challenge = true, configuredOrigin = "https://02.avsee.is"))
        assertFalse(shouldDeferSearchChallengeHttpFailure(403, foreign, currentDocumentObserved = false, challenge = true, configuredOrigin = "https://02.avsee.is"))
    }

    @Test fun deferredSearch403ResetsOnlyForEligibleSearchReload() {
        val general = "https://02.avsee.is/bbs/search.php?stx=needle&onetable=javfc2&page=1"

        assertTrue(shouldResetDeferredSearchChallengeHttpFailure(403, general, "https://02.avsee.is/bbs/search.php", configuredOrigin = "https://02.avsee.is"))
        assertFalse(shouldResetDeferredSearchChallengeHttpFailure(403, general, "https://01.avsee.is/bbs/search.php?stx=needle", configuredOrigin = "https://02.avsee.is"))
        assertFalse(shouldResetDeferredSearchChallengeHttpFailure(401, general, "https://02.avsee.is/bbs/search.php", configuredOrigin = "https://02.avsee.is"))
        assertFalse(shouldResetDeferredSearchChallengeHttpFailure(403, general, "https://02.avsee.is/bbs/login.php", configuredOrigin = "https://02.avsee.is"))
        assertFalse(shouldResetDeferredSearchChallengeHttpFailure(403, general, "https://example.invalid/bbs/search.php", configuredOrigin = "https://02.avsee.is"))
    }

    @Test fun protectedSearchChallengeKeepsHttpReasonForSiteRecovery() {
        val general = "https://02.avsee.is/bbs/search.php?stx=needle&onetable=javfc2&page=1"
        val tag = "https://02.avsee.is/bbs/tag.php?q=needle&page=1"
        val foreign = "https://example.invalid/bbs/search.php?stx=needle"

        assertEquals(PageFailureReason.HTTP, classifyApplicationDocumentFailure(401, general, challenge = true, configuredOrigin = "https://02.avsee.is"))
        assertEquals(PageFailureReason.HTTP, classifyApplicationDocumentFailure(403, general, challenge = true, configuredOrigin = "https://02.avsee.is"))
        assertEquals(PageFailureReason.HTTP, classifyApplicationDocumentFailure(403, tag, challenge = true, configuredOrigin = "https://02.avsee.is"))
        assertEquals(PageFailureReason.ERROR, classifyApplicationDocumentFailure(403, foreign, challenge = true, configuredOrigin = "https://02.avsee.is"))
        assertEquals(PageFailureReason.HTTP, classifyApplicationDocumentFailure(503, general, challenge = true, configuredOrigin = "https://02.avsee.is"))
        assertEquals(PageFailureReason.ERROR, classifyApplicationDocumentFailure(200, general, challenge = true, configuredOrigin = "https://02.avsee.is"))
        assertEquals(PageFailureReason.HTTP, classifyApplicationDocumentFailure(503, general, challenge = false, configuredOrigin = "https://02.avsee.is"))
        assertNull(classifyApplicationDocumentFailure(200, general, challenge = false, configuredOrigin = "https://02.avsee.is"))
    }

    @Test fun configuredLoginRedirectIsRecognizedWithoutAcceptingForeignOrCleartext() {
        assertTrue(isAuthenticationRedirect("https://02.avsee.is/bbs/login.php?url=%2Fbbs%2Fsearch.php", configuredOrigin = "https://02.avsee.is"))
        assertTrue(isAuthenticationRedirect("https://01.avsee.is/login/form", configuredOrigin = "https://01.avsee.is"))
        assertFalse(isAuthenticationRedirect("http://02.avsee.is/bbs/login.php", configuredOrigin = "https://02.avsee.is"))
        assertFalse(isAuthenticationRedirect("https://example.invalid/bbs/login.php", configuredOrigin = "https://02.avsee.is"))
    }

    @Test fun generalSearchCanonicalizationPreservesSearchBoardSortSizeAndPageIdentity() {
        val requested = "https://02.avsee.is/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=needle&sop=and&result_type=all&result_sort=newest&gr_id=&srows=1000&onetable=javfc2&page=2"
        val observed = "https://02.avsee.is/bbs/search.php?onetable=javfc2&page=2&stx=needle&sop=and&sfl=wr_subject%7C%7Cwr_content&result_type=all&result_sort=newest&gr_id=&srows=1000&server_default=1"
        assertTrue(isMatchingApplicationDocument(hosts, requested, observed))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("stx=needle", "stx=other")))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("onetable=javfc2", "onetable=javc")))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("result_sort=newest", "result_sort=oldest")))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("srows=1000", "srows=10")))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("page=2", "page=3")))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("02.avsee.is", "example.invalid")))
    }

    @Test fun tagCanonicalizationPreservesTagBoardSortAndPageIdentity() {
        val requested = "https://02.avsee.is/bbs/tag.php?q=%23tag&eq=&onetable=javc&result_sort=newest&page=2"
        val observed = "https://02.avsee.is/bbs/tag.php?page=2&q=%23tag&eq=&onetable=javc&result_sort=newest&server_default=1"
        assertTrue(isMatchingApplicationDocument(hosts, requested, observed))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("q=%23tag", "q=other")))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("onetable=javc", "onetable=javfc2")))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("result_sort=newest", "result_sort=oldest")))
        assertFalse(isMatchingApplicationDocument(hosts, requested, observed.replace("page=2", "page=3")))
    }
}

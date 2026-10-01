package com.shaterguy.fc2weeklyranker.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationNavigationPolicyTest {
    private val hosts = OFFICIAL_APPLICATION_HOSTS

    @Test fun officialLoginOriginIsHttpsAndAllowlisted() {
        assertEquals("https://02.avsee.is/bbs/login.php", officialApplicationStartUrl("https://02.avsee.is"))
        assertEquals("https://01.avsee.is/bbs/login.php", officialApplicationStartUrl("https://01.avsee.is/path"))
        assertNull(officialApplicationStartUrl("http://02.avsee.is"))
        assertNull(officialApplicationStartUrl("https://example.invalid"))
        assertTrue(isOfficialApplicationUrl("https://02.avsee.is/bbs/login.php"))
        assertFalse(isOfficialApplicationUrl("http://02.avsee.is/bbs/login.php"))
        assertFalse(isOfficialApplicationUrl("https://example.invalid/bbs/login.php"))
    }

    @Test fun onlyProtectedGeneralSearch401Or403MapsToAuthentication() {
        val general = "https://02.avsee.is/bbs/search.php?stx=needle&onetable=javfc2&page=1"
        val tag = "https://02.avsee.is/bbs/tag.php?q=needle&page=1"
        assertTrue(isAuthenticationRequiredSearchResponse(401, general))
        assertTrue(isAuthenticationRequiredSearchResponse(403, general))
        assertFalse(isAuthenticationRequiredSearchResponse(403, tag))
        assertFalse(isAuthenticationRequiredSearchResponse(503, general))
    }

    @Test fun officialLoginRedirectIsRecognizedWithoutAcceptingForeignOrCleartext() {
        assertTrue(isAuthenticationRedirect("https://02.avsee.is/bbs/login.php?url=%2Fbbs%2Fsearch.php"))
        assertTrue(isAuthenticationRedirect("https://01.avsee.is/login/form"))
        assertFalse(isAuthenticationRedirect("http://02.avsee.is/bbs/login.php"))
        assertFalse(isAuthenticationRedirect("https://example.invalid/bbs/login.php"))
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

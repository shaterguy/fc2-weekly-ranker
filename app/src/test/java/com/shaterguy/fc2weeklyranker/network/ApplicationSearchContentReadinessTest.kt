package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationSearchContentReadinessTest {
    private val parser = AvseeClient(OkHttpClient())
    private val searchUrl = "https://fixture.invalid/bbs/search.php?onetable=javfc2&page=1"
    private val tagUrl = "https://fixture.invalid/bbs/tag.php?q=fixture&page=1"
    private val searchPlaceholder = "<div id='at-main'><div class='search-media'><div class='media' id='row'></div></div></div>"
    private val tagPlaceholder = "<div class='tagbox-media'><div class='media' id='row'></div></div>"
    private val link = "<h4 class='media-heading'><a href='/bbs/board.php?bo_table=javfc2&amp;wr_id=42'>Synthetic result</a></h4>"
    private val metrics = "<div class='media-info'><i class='fa fa-comment'></i> 2 <i class='fa fa-eye'></i> 10</div>"

    private fun accepts(html: String, url: String, state: String = "interactive"): Boolean =
        hasReadyApplicationSearchContent(state, html, url, parser::hasUsableApplicationSearchResults)

    @Test fun searchPlaceholderWaitsForItsHeadingAndLink() {
        assertFalse(accepts(searchPlaceholder, searchUrl))
    }
    @Test fun searchRowWithLateLinkBecomesUsable() {
        assertTrue(accepts(searchPlaceholder.replace("id='row'>", "id='row'>" + link), searchUrl))
    }
    @Test fun searchLinkFromAnotherBoardDoesNotCompleteTheRequest() {
        assertFalse(accepts(searchPlaceholder.replace("id='row'>", "id='row'>" + link.replace("javfc2", "javc")), searchUrl))
    }
    @Test fun tagPlaceholderWaitsForItsLink() {
        assertFalse(accepts(tagPlaceholder, tagUrl))
    }
    @Test fun tagRowWithoutMetricsStillWaits() {
        assertFalse(accepts(tagPlaceholder.replace("id='row'>", "id='row'>" + link), tagUrl))
    }
    @Test fun tagRowWithLateLinkAndMetricsBecomesUsable() {
        assertTrue(accepts(tagPlaceholder.replace("id='row'>", "id='row'>" + link + metrics), tagUrl))
    }
    @Test fun eitherSupportedTagBoardCanProvideAUsableRow() {
        assertTrue(accepts(tagPlaceholder.replace("id='row'>", "id='row'>" + link.replace("javfc2", "javc") + metrics), tagUrl))
    }
    @Test(expected = IllegalStateException::class) fun parserSafetyFailuresAreNotHiddenByReadiness() {
        val excessivePage = "<a href='/bbs/search.php?onetable=javfc2&amp;page=1000001'>Later</a>"
        accepts(searchPlaceholder.replace("id='row'>", "id='row'>" + link) + excessivePage, searchUrl)
    }
    @Test fun completeEmptyPageKeepsTheExistingParserOutcome() {
        assertTrue(accepts(searchPlaceholder, searchUrl, "complete"))
        assertTrue(accepts(tagPlaceholder, tagUrl, "complete"))
    }

    @Test fun currentSearchResultRowBecomesUsable() {
        val html = "<ul class='search-results'><li class='search-result'><div class='search-result-title'><a href='/bbs/board.php?bo_table=javfc2&amp;wr_id=42'>Current result</a></div></li></ul>"
        assertTrue(accepts(html, searchUrl))
    }

    @Test fun currentTagResultRowWithLabeledMetricsBecomesUsable() {
        val html = "<ul class='tag-results'><li class='tag-result'><div class='tag-result-title'><a href='/bbs/board.php?bo_table=javc&amp;wr_id=42'>Current tag</a></div><div class='tag-result-meta'>댓글 2 조회 10 추천 1</div></li></ul>"
        assertTrue(accepts(html, tagUrl))
    }

}

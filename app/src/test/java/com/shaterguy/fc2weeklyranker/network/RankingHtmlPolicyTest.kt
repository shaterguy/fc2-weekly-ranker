package com.shaterguy.fc2weeklyranker.network

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RankingHtmlPolicyTest {
    @Test fun rankingRoutesRemainSeparateFromSearchLoginAndMedia() {
        for (url in listOf(
            "$ORIGIN/bbs/board.php?bo_table=javfc2&page=1",
            "$ORIGIN/bbs/board.php?bo_table=javc&wr_id=42",
            "$ORIGIN/javfc2/42", "$ORIGIN/javc/42/",
        )) assertTrue(url, isRankingHtmlTarget(url))
        for (url in listOf(
            "$ORIGIN/bbs/search.php?onetable=javfc2",
            "$ORIGIN/bbs/tag.php?q=fixture", "$ORIGIN/bbs/login.php",
            "$ORIGIN/bbs/board.php?bo_table=other", "$ORIGIN/javc/42/extra",
            "$ORIGIN/video.mp4", "$ORIGIN/bbs/board.php/extra?bo_table=javfc2",
        )) assertFalse(url, isRankingHtmlTarget(url))
    }

    @Test fun incompleteListsAndGenericDocumentsUseNavigationFallback() {
        for (html in listOf(
            "<form id='fboardlist'></form>",
            "<form id='fboardlist'><div class='list-item'></div></form>",
            "<html><body>Please sign in</body></html>",
            "<aside><meta itemprop=\'datePublished\' content=\'2026-11-04KST10:00:00\'></aside>",
            "<div class='list-item'><h2><a href='/javfc2/42'>Sidebar</a></h2></div>",
        )) assertFalse(html, isUsableRankingHtml(html, BOARD))
        assertTrue(isUsableRankingHtml(BOARD_HTML, BOARD))
        assertTrue(isUsableRankingHtml(
            "<article><meta itemprop='datePublished' content='2026-11-04KST10:00:00'></article>",
            "$ORIGIN/javfc2/42",
        ))
    }

    @Test fun scriptsKeepSessionInBrowserAndNeverInsertResponseIntoDocument() {
        val script = rankingHtmlStartScript(BOARD, "$ORIGIN/javfc2/42", "slot", 1234)
        assertTrue(script.contains("mode: \"same-origin\""))
        assertTrue(script.contains("credentials: \"same-origin\""))
        assertTrue(script.contains("redirect: \"error\""))
        assertTrue(script.contains("cache: \"no-store\""))
        assertTrue(script.contains("target.origin !== location.origin"))
        assertTrue(script.contains("body.length > 1234"))
        assertFalse(script.contains("document.cookie"))
        assertFalse(script.contains("innerHTML"))
        assertFalse(script.contains("outerHTML"))
        assertTrue(rankingHtmlCleanupScript("slot").contains("delete window[key]"))
        assertTrue(rankingHtmlCleanupScript("slot").contains("controller.abort()"))
    }

    companion object {
        const val ORIGIN = "https://fixture.invalid"
        const val BOARD = "$ORIGIN/bbs/board.php?bo_table=javfc2&page=2"
        const val BOARD_HTML = "<form id='fboardlist'><div class='list-item'><h2><a href='/javfc2/42'>Neutral fixture</a></h2><span class='comments'><b>3</b></span><span>20</span></div></form>"
    }
}

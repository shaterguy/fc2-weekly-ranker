package com.shaterguy.fc2weeklyranker.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AvseeClientDev36RedTest {
    private val client = AvseeClient(okhttp3.OkHttpClient())

    @Test
    fun `fc2 detail exposes tags just like jav detail`() {
        val post = client.parseDetail(
            detailHtml(recommendations = "98", comments = "114"),
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=2147998",
            Instant.parse("2026-09-17T00:00:00Z"),
            includeMedia = false,
        )

        assertEquals(listOf("테스트 태그"), post.tags.map { it.query })
    }

    @Test
    fun `fc2 tag page keeps only fc2 board rows`() {
        val page = client.parseTagPage(
            """
            <div class='tagbox-media'>
              <div class='media'>
                <div class='media-heading'><a href='/bbs/board.php?bo_table=javfc2&wr_id=10'>FC2 row</a></div>
                <div class='media-info'><i class='fa fa-comment'></i> 12 <i class='fa fa-eye'></i> 345</div>
              </div>
              <div class='media'>
                <div class='media-heading'><a href='/bbs/board.php?bo_table=javc&wr_id=11'>JAV row</a></div>
                <div class='media-info'><i class='fa fa-comment'></i> 99 <i class='fa fa-eye'></i> 999</div>
              </div>
            </div>
            """.trimIndent(),
            "https://example.test/bbs/tag.php?q=test&eq=&page=1",
            "javfc2",
        )

        assertEquals(listOf("10"), page.posts.map { it.id })
    }

    @Test
    fun `detail exposes nullable scoped recommendation and total comment metrics`() {
        val post = client.parseDetail(
            detailHtml(recommendations = "98", comments = "114"),
            "https://example.test/bbs/board.php?bo_table=javc&wr_id=2147998",
            Instant.parse("2026-09-17T00:00:00Z"),
            includeMedia = false,
        )

        val recommendationGetter = post.javaClass.methods.firstOrNull { it.name == "getDetailRecommendationCount" }
        val commentGetter = post.javaClass.methods.firstOrNull { it.name == "getDetailCommentCount" }
        assertNotNull("detail recommendation metric must be carried separately from legacy ranking fields", recommendationGetter)
        assertNotNull("detail total comment metric must be carried separately from legacy ranking fields", commentGetter)
        assertEquals(98, recommendationGetter!!.invoke(post))
        assertEquals(114, commentGetter!!.invoke(post))
    }

    @Test
    fun `missing detail metrics stay unknown instead of becoming zero`() {
        val post = client.parseDetail(
            "<meta itemprop='datePublished' content='2026-09-11KST17:02:18'><h1>No metrics</h1>",
            "https://example.test/bbs/board.php?bo_table=javc&wr_id=1",
            Instant.parse("2026-09-17T00:00:00Z"),
            includeMedia = false,
        )

        val recommendationGetter = post.javaClass.methods.firstOrNull { it.name == "getDetailRecommendationCount" }
        val commentGetter = post.javaClass.methods.firstOrNull { it.name == "getDetailCommentCount" }
        assertNotNull(recommendationGetter)
        assertNotNull(commentGetter)
        assertNull(recommendationGetter!!.invoke(post))
        assertNull(commentGetter!!.invoke(post))
    }

    private fun detailHtml(recommendations: String, comments: String): String = """
        <meta itemprop='datePublished' content='2026-09-11KST17:02:18'>
        <h1>Synthetic post</h1>
        <div class='view-head'><div class='panel-heading'><div class='ellipsis'>
          <i class='fa fa-comment'></i><b class='red'>$comments</b>
          <i class='fa fa-eye'></i><b>214159</b>
          <i class='fa fa-thumbs-up'></i><b>$recommendations</b>
        </div></div></div>
        <div class='view-good'><b id='wr_good'>$recommendations</b></div>
        <div class='view-comment'><i class='fa fa-commenting'></i><span class='orangered'>$comments</span> Comments</div>
        <div id='bo_vc'><strong id='c_good1'>777</strong></div>
        <aside>추천 99999</aside>
        <p class='view-tag view-padding'><a href='/bbs/tag.php?q=%ED%85%8C%EC%8A%A4%ED%8A%B8%20%ED%83%9C%EA%B7%B8'>테스트 태그</a></p>
    """.trimIndent()
}

package com.shaterguy.fc2weeklyranker.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSearchStreamerTest {
    @Test
    fun `completed tag pages stream immediately and final order is canonical`() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                when (page) {
                    2 -> Thread.sleep(180)
                    3 -> Thread.sleep(30)
                    else -> Thread.sleep(10)
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(tagPage(page, totalPages = 3).toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        val parser = AvseeClient(http)
        val snapshots = TagSearchStreamer(http, parser)
            .search("https://example.test", "sample", "javc")
            .toList()

        assertEquals(listOf(1, 2, 3), snapshots.map { it.posts.size })
        assertEquals(listOf("p1"), snapshots[0].posts.map { it.id })
        assertEquals(listOf("p1", "p3"), snapshots[1].posts.map { it.id })
        assertEquals(listOf("p1", "p2", "p3"), snapshots[2].posts.map { it.id })
        assertEquals(listOf(1, 2, 3), snapshots.map { it.completedPages })
        assertTrue(snapshots.all { it.totalPages == 3 })
        println("FC2_DEV37_TAG_STREAM_METRIC snapshots=${snapshots.size} second_snapshot_ids=${snapshots[1].posts.joinToString(",") { it.id }}")
    }

    private fun tagPage(page: Int, totalPages: Int): String {
        val pagination = (1..totalPages).joinToString("") { number ->
            "<a href='/bbs/tag.php?q=sample&eq=&page=$number'>$number</a>"
        }
        return """
            <div class='tagbox-media'>
              <div class='media'>
                <h4 class='media-heading'><a href='/bbs/board.php?bo_table=javc&wr_id=p$page'>Post $page</a></h4>
                <div class='media-info'><i class='fa fa-comment'></i> ${page * 10} <i class='fa fa-eye'></i> ${page * 100}</div>
              </div>
            </div>
            $pagination
        """.trimIndent()
    }
}

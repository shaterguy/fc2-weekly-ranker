package com.shaterguy.fc2weeklyranker.network

import java.net.URI
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSearchBoardPropagationTest {
    @Test
    fun `streamer carries board and sort identity on every application-page request`() = runBlocking {
        val seen = mutableListOf<String>()
        val transport = object : ApplicationPageTransport {
            override suspend fun fetch(url: String, referer: String?): String {
                seen += url
                val page = URI(url).rawQuery.orEmpty()
                    .split('&')
                    .firstOrNull { it.startsWith("page=") }
                    ?.substringAfter('=')
                    ?.toIntOrNull()
                    ?: 1
                return """
                    <ul class='tag-results'>
                      <li class='tag-result'>
                        <div class='tag-result-title'><a href='/javc/${100 + page}'>Synthetic result $page</a></div>
                        <div class='tag-result-meta'>댓글 $page 조회 ${page * 10} 추천 0</div>
                      </li>
                    </ul>
                    <a href='/bbs/tag.php?q=%23sample&eq=&onetable=javc&result_sort=newest&page=2'>2</a>
                """.trimIndent()
            }
        }
        val parser = AvseeClient(OkHttpClient(), applicationPageTransport = transport)
        val snapshots = TagSearchStreamer(OkHttpClient(), parser)
            .search("https://example.test", "#sample", "javc")
            .toList()

        assertEquals(2, snapshots.last().posts.size)
        assertTrue(seen.isNotEmpty())
        seen.forEach { url ->
            val query = URI(url).rawQuery.orEmpty()
            assertTrue(query.contains("onetable=javc"))
            assertTrue(query.contains("result_sort=newest"))
        }
    }
}

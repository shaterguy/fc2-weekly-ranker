package com.shaterguy.fc2weeklyranker.search

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaterguy.fc2weeklyranker.network.RemoteSearchPost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchDatabaseInstrumentedTest {
    @Test
    fun pageAggregationAndReplayAreIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SearchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.searchDao()
            val request = SearchRequest(
                token = "search-replay-token",
                query = "sample",
                baseUrl = "https://02.avsee.is",
                sourceKey = "JAV",
            )
            dao.prepareSession(request)

            assertTrue(
                dao.storePage(
                    token = request.token,
                    page = 1,
                    totalPages = 2,
                    posts = listOf(
                        RemoteSearchPost("jav:100", "https://example.test/100", "first", occurrenceCount = 2),
                        RemoteSearchPost("jav:200", "https://example.test/200", "second", occurrenceCount = 1),
                    ),
                    updatedAt = 10L,
                    sourceKey = "JAV",
                ),
            )
            assertTrue(
                dao.storePage(
                    token = request.token,
                    page = 2,
                    totalPages = 2,
                    posts = listOf(
                        RemoteSearchPost("jav:100", "https://example.test/100-new", "ignored", occurrenceCount = 3),
                    ),
                    updatedAt = 20L,
                    sourceKey = "JAV",
                ),
            )

            val beforeReplay = dao.observeResults("JAV").first()
            assertEquals(listOf("jav:100", "jav:200"), beforeReplay.map { it.postId })
            assertEquals(listOf(5, 1), beforeReplay.map { it.occurrenceCount })
            assertEquals("first", beforeReplay.first().title)
            assertEquals("https://example.test/100", beforeReplay.first().url)
            val sessionBeforeReplay = dao.currentSession("JAV")
            assertEquals(3, sessionBeforeReplay?.nextPage)

            assertTrue(
                dao.storePage(
                    token = request.token,
                    page = 1,
                    totalPages = 2,
                    posts = listOf(
                        RemoteSearchPost("jav:100", "https://example.test/replay", "replay", occurrenceCount = 99),
                    ),
                    updatedAt = 30L,
                    sourceKey = "JAV",
                ),
            )
            assertEquals(beforeReplay, dao.observeResults("JAV").first())
            assertEquals(sessionBeforeReplay, dao.currentSession("JAV"))

            assertFalse(
                dao.storePage(
                    token = request.token,
                    page = 4,
                    totalPages = 4,
                    posts = listOf(
                        RemoteSearchPost("jav:300", "https://example.test/300", "future", occurrenceCount = 1),
                    ),
                    updatedAt = 40L,
                    sourceKey = "JAV",
                ),
            )
            assertEquals(beforeReplay, dao.observeResults("JAV").first())
        } finally {
            database.close()
        }
    }
}
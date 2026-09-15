package com.shaterguy.fc2weeklyranker.ui

import com.shaterguy.fc2weeklyranker.domain.ContentMode
import com.shaterguy.fc2weeklyranker.network.RemoteSearchPost
import com.shaterguy.fc2weeklyranker.network.RemoteTagPost
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchAndTagSortTest {
    @Test
    fun `occurrence sort is descending and stable for ties`() {
        val first = RemoteSearchPost("1", "https://example.test/1", "first", occurrenceCount = 2)
        val second = RemoteSearchPost("2", "https://example.test/2", "second", occurrenceCount = 5)
        val third = RemoteSearchPost("3", "https://example.test/3", "third", occurrenceCount = 5)

        assertEquals(
            listOf(second, third, first),
            sortSearchResults(listOf(first, second, third), SearchSortMode.OCCURRENCE),
        )
        assertEquals(
            listOf(first, second, third),
            sortSearchResults(listOf(first, second, third), SearchSortMode.FIRST_SEEN),
        )
    }

    @Test
    fun `tag comments and views sorts are descending and stable`() {
        val first = RemoteTagPost("jav:1", "https://example.test/1", "first", commentCount = 3, viewCount = 30)
        val second = RemoteTagPost("jav:2", "https://example.test/2", "second", commentCount = 7, viewCount = 10)
        val third = RemoteTagPost("jav:3", "https://example.test/3", "third", commentCount = 7, viewCount = 50)

        assertEquals(
            listOf(second, third, first),
            sortTagResults(listOf(first, second, third), TagSortMode.COMMENTS),
        )
        assertEquals(
            listOf(third, first, second),
            sortTagResults(listOf(first, second, third), TagSortMode.VIEWS),
        )
        assertEquals(
            listOf(first, second, third),
            sortTagResults(listOf(first, second, third), TagSortMode.ORIGINAL),
        )
    }

    @Test
    fun `JAV tag navigation IDs stay source namespaced`() {
        assertEquals("jav:123", ContentMode.JAV.localPostId("123"))
        assertEquals("123", ContentMode.JAV.remotePostId("jav:123"))
        assertEquals("123", ContentMode.FC2.localPostId("123"))
    }
}
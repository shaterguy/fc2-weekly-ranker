package com.shaterguy.fc2weeklyranker.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchStorePolicyTest {
    @Test
    fun `page write policy makes retry resume idempotent`() {
        assertEquals(SearchPageWriteDecision.APPLY, SearchPageWritePolicy.decide(expectedNextPage = 2, incomingPage = 2))
        assertEquals(SearchPageWriteDecision.ALREADY_APPLIED, SearchPageWritePolicy.decide(expectedNextPage = 3, incomingPage = 2))
        assertEquals(SearchPageWriteDecision.REJECT_FUTURE, SearchPageWritePolicy.decide(expectedNextPage = 2, incomingPage = 3))
    }

    @Test
    fun `search database migration two to three adds occurrence count with legacy default one`() {
        assertEquals(2, SearchDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, SearchDatabase.MIGRATION_2_3.endVersion)
        assertEquals(
            "ALTER TABLE search_results ADD COLUMN occurrenceCount INTEGER NOT NULL DEFAULT 1",
            SEARCH_RESULT_OCCURRENCE_MIGRATION_SQL,
        )
    }
}

package com.shaterguy.fc2weeklyranker.ui

import kotlinx.coroutines.Job
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRegistrationLifecycleTest {
    @Test
    fun newDetailEntryCancelsPendingProbeRegistrationsFromPreviousEntry() {
        val first = Job()
        val second = Job()

        cancelPendingProbeRegistrations(listOf(first, second))

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }
}

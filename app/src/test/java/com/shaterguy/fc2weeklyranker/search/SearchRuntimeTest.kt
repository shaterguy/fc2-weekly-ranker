package com.shaterguy.fc2weeklyranker.search

import com.shaterguy.fc2weeklyranker.network.isTransientNetworkError
import com.shaterguy.fc2weeklyranker.network.retryTransientGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class SearchRuntimeTest {
    @Test
    fun `android 14 and newer use uidt while older versions use work manager`() {
        assertEquals(SearchSchedulerKind.WORK_MANAGER, SearchRuntimePolicy.schedulerKind(33))
        assertEquals(SearchSchedulerKind.UIDT, SearchRuntimePolicy.schedulerKind(34))
        assertEquals(SearchSchedulerKind.UIDT, SearchRuntimePolicy.schedulerKind(36))
    }

    @Test
    fun `large global searches and excessive runtime fail explicitly`() {
        SearchRuntimePolicy.validateTotalPages(500)
        try {
            SearchRuntimePolicy.validateTotalPages(501)
            fail("expected global page bound failure")
        } catch (actual: IllegalStateException) {
            assertTrue(actual.message.orEmpty().contains("검색 범위가 너무 큽니다"))
        }

        SearchRuntimePolicy.ensureWithinRuntime(0L, TimeUnit.MINUTES.toNanos(9))
        try {
            SearchRuntimePolicy.ensureWithinRuntime(0L, TimeUnit.MINUTES.toNanos(10))
            fail("expected runtime bound failure")
        } catch (actual: IllegalStateException) {
            assertTrue(actual.message.orEmpty().contains("10분"))
        }
    }

    @Test
    fun `stale running session is interrupted only when scheduler absence is confirmed`() {
        assertTrue(SearchRecoveryPolicy.shouldInterrupt(SearchStatus.RUNNING, false))
        assertFalse(SearchRecoveryPolicy.shouldInterrupt(SearchStatus.RUNNING, true))
        assertFalse(SearchRecoveryPolicy.shouldInterrupt(SearchStatus.RUNNING, null))
        assertFalse(SearchRecoveryPolicy.shouldInterrupt(SearchStatus.COMPLETED, false))
    }

    @Test
    fun `work manager search tags are token specific`() {
        assertEquals("fc2-search-token:alpha", SearchScheduler.workTag("alpha"))
        assertEquals("fc2-search-token:beta", SearchScheduler.workTag("beta"))
    }

    @Test
    fun `software caused connection abort is transient`() {
        val abort = SocketException("Software caused connection abort")
        assertTrue(isTransientNetworkError(abort))
        assertTrue(isTransientNetworkError(IllegalStateException("wrapped", abort)))
    }

    @Test
    fun `tls certificate and cancellation failures still override socket abort`() {
        val ssl = SSLException("tls").apply { initCause(SocketException("abort")) }
        val certificate = CertificateException("certificate", SocketException("abort"))
        val cancellation = CancellationException("cancel").apply { initCause(SocketException("abort")) }

        assertFalse(isTransientNetworkError(ssl))
        assertFalse(isTransientNetworkError(certificate))
        assertFalse(isTransientNetworkError(cancellation))
    }

    @Test
    fun `search get retries transient abort twice and preserves cancellation`() = runTest {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        val value = retryTransientGet(
            delaysMillis = listOf(10L, 20L),
            sleep = { sleeps += it },
        ) {
            attempts += 1
            if (attempts < 3) throw SocketException("Software caused connection abort")
            "ok"
        }

        assertEquals("ok", value)
        assertEquals(3, attempts)
        assertEquals(listOf(10L, 20L), sleeps)

        val cancellation = CancellationException("cancel")
        try {
            retryTransientGet(delaysMillis = listOf(1L), sleep = { _ -> }) { throw cancellation }
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `same search token network restart invalidates stale completion`() {
        val gate = SearchExecutionGate()
        val first = gate.begin("session")
        val second = gate.begin("session")

        assertFalse(gate.isCurrent("session", first))
        assertTrue(gate.isCurrent("session", second))

        gate.invalidate()
        assertFalse(gate.isCurrent("session", second))
    }
}

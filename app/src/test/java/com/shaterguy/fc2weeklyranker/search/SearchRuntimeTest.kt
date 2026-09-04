package com.shaterguy.fc2weeklyranker.search

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
import javax.net.ssl.SSLException

class SearchRuntimeTest {
    @Test
    fun `android 14 and newer use uidt while older versions use work manager`() {
        assertEquals(SearchSchedulerKind.WORK_MANAGER, SearchRuntimePolicy.schedulerKind(33))
        assertEquals(SearchSchedulerKind.UIDT, SearchRuntimePolicy.schedulerKind(34))
        assertEquals(SearchSchedulerKind.UIDT, SearchRuntimePolicy.schedulerKind(36))
    }

    @Test
    fun `software caused connection abort is transient`() {
        val abort = SocketException("Software caused connection abort")
        assertTrue(isSearchTransientNetworkError(abort))
        assertTrue(isSearchTransientNetworkError(IllegalStateException("wrapped", abort)))
    }

    @Test
    fun `tls certificate and cancellation failures are never treated as transient`() {
        val ssl = SSLException("tls").apply { initCause(SocketException("abort")) }
        val certificate = CertificateException("certificate", SocketException("abort"))
        val cancellation = CancellationException("cancel").apply { initCause(SocketException("abort")) }

        assertFalse(isSearchTransientNetworkError(ssl))
        assertFalse(isSearchTransientNetworkError(certificate))
        assertFalse(isSearchTransientNetworkError(cancellation))
    }

    @Test
    fun `search get retries transient abort twice and preserves cancellation`() = runTest {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        val value = retrySearchGet(
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
            retrySearchGet(delaysMillis = listOf(1L), sleep = { _ -> }) { throw cancellation }
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}

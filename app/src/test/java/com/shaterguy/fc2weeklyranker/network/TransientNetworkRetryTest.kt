package com.shaterguy.fc2weeklyranker.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

class TransientNetworkRetryTest {
    @Test
    fun `transient GET failure retries twice with injected delays and succeeds`() = runTest {
        var attempts = 0
        val sleeps = mutableListOf<Long>()

        val result = retryTransientGet(
            delaysMillis = listOf(10L, 20L),
            sleep = { sleeps += it },
        ) {
            attempts += 1
            if (attempts < 3) throw UnknownHostException("synthetic")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(listOf(10L, 20L), sleeps)
    }

    @Test
    fun `transient classification follows the cause chain`() {
        listOf(
            UnknownHostException(),
            ConnectException(),
            NoRouteToHostException(),
            SocketTimeoutException(),
        ).forEach { cause ->
            assertTrue(isTransientNetworkError(IllegalStateException("wrapped", cause)))
        }
        assertFalse(isTransientNetworkError(IllegalStateException("HTTP_503")))
    }

    @Test
    fun `security protocol and cancellation causes override transient causes`() {
        val ssl = SSLException("tls").apply { initCause(SocketTimeoutException("timeout")) }
        val protocol = ProtocolException("protocol").apply { initCause(UnknownHostException("host")) }
        val certificate = CertificateException("cert", ConnectException("connect"))
        val cancellation = CancellationException("cancel").apply { initCause(SocketTimeoutException("timeout")) }

        assertFalse(isTransientNetworkError(ssl))
        assertFalse(isTransientNetworkError(protocol))
        assertFalse(isTransientNetworkError(certificate))
        assertFalse(isTransientNetworkError(cancellation))
    }

    @Test
    fun `permanent failure is not retried`() = runTest {
        var attempts = 0
        val failure = SSLException("tls").apply { initCause(SocketTimeoutException("timeout")) }

        try {
            retryTransientGet(delaysMillis = listOf(1L, 1L), sleep = { _ -> }) {
                attempts += 1
                throw failure
            }
            fail("expected failure")
        } catch (actual: SSLException) {
            assertSame(failure, actual)
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `cancellation is rethrown without retry or delay`() = runTest {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        val cancellation = CancellationException("cancel")

        try {
            retryTransientGet(delaysMillis = listOf(1L, 1L), sleep = { sleeps += it }) {
                attempts += 1
                throw cancellation
            }
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(1, attempts)
        assertTrue(sleeps.isEmpty())
    }
    @Test
    fun `board date boundary cancellation is rethrown without wrapping`() = runTest {
        val cancellation = CancellationException("cancel")
        var attempts = 0

        try {
            resolveBoardDateBoundary("42") {
                attempts += 1
                throw cancellation
            }
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `board date boundary permanent failure preserves its cause`() = runTest {
        val cause = IllegalArgumentException("parse")

        try {
            resolveBoardDateBoundary("42") { throw cause }
            fail("expected boundary failure")
        } catch (actual: IllegalStateException) {
            assertEquals("게시일자 경계 판정 실패: 42", actual.message)
            assertSame(cause, actual.cause)
        }
    }

}

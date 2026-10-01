package com.shaterguy.fc2weeklyranker.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationFailureDiagnosticTest {
    @Test fun nestedTypedFailureKeepsOnlyAllowlistedDiagnostic() {
        val failure = PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.HTTP, 503)
        val wrapper = IllegalStateException("https://private.invalid/?query=secret Cookie=session", failure)
        assertEquals("페이지 요청에 실패했습니다. [DOCUMENT/HTTP_503]", safeApplicationFailureMessage(wrapper))
    }

    @Test fun arbitraryErrorTextCannotImpersonateADiagnostic() {
        val message = safeApplicationFailureMessage(IllegalStateException("[DOCUMENT/HTTP_401] token=secret"))
        assertEquals("작업을 완료하지 못했습니다. [UNKNOWN/ERROR]", message)
        assertFalse(message.contains("secret"))
    }

    @Test fun loadFailureRetainsCodeWithoutRawCause() {
        val failure = PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.WEBVIEW, -6,
            IllegalStateException("https://private.invalid secret"))
        assertEquals("페이지를 불러오지 못했습니다. [DOCUMENT/WEBVIEW_ERROR_-6]",
            safeApplicationFailureMessage(failure))
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test fun authenticationFailureHasSafeTypedMessage() {
        val failure = PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.AUTHENTICATION)
        assertEquals(
            "사이트 로그인이 필요합니다. [DOCUMENT/AUTHENTICATION_REQUIRED]",
            safeApplicationFailureMessage(failure),
        )
    }

    @Test fun invalidCodesAreNotDisplayed() {
        assertEquals("페이지 요청에 실패했습니다. [DOCUMENT/HTTP_UNKNOWN]",
            safeApplicationFailureMessage(PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.HTTP, 999999)))
        assertEquals("페이지를 불러오지 못했습니다. [DOCUMENT/WEBVIEW_ERROR_UNKNOWN]",
            safeApplicationFailureMessage(PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.WEBVIEW, 999999)))
    }

    @Test fun sessionTimeoutHasItsOwnStage() = runBlocking<Unit> {
        val failure = runCatching {
            withPageFailureStage(PageFailureStage.SESSION) { withTimeout(1) { delay(100) } }
        }.exceptionOrNull()
        assertTrue(failure is PageLoadException)
        assertEquals("페이지 응답 시간이 초과되었습니다. [SESSION/TIMEOUT]",
            safeApplicationFailureMessage(checkNotNull(failure)))
    }

    @Test fun documentTimeoutHasItsOwnStage() = runBlocking<Unit> {
        val failure = runCatching {
            withPageFailureStage(PageFailureStage.DOCUMENT) { withTimeout(1) { delay(100) } }
        }.exceptionOrNull()
        assertEquals("페이지 응답 시간이 초과되었습니다. [DOCUMENT/TIMEOUT]",
            safeApplicationFailureMessage(checkNotNull(failure)))
    }

    @Test fun ordinaryCancellationIsRethrownUnchanged() = runBlocking<Unit> {
        val cancellation = CancellationException("secret query")
        val failure = runCatching {
            withPageFailureStage(PageFailureStage.DOCUMENT) { throw cancellation }
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertEquals("작업이 취소되었습니다. [UNKNOWN/CANCELLED]",
            safeApplicationFailureMessage(cancellation))
    }

    @Test fun unknownSessionExceptionGetsSafeStage() = runBlocking<Unit> {
        val failure = runCatching {
            withPageFailureStage(PageFailureStage.SESSION) {
                throw IllegalArgumentException("Cookie=session https://private.invalid")
            }
        }.exceptionOrNull()
        assertEquals("페이지 처리를 완료하지 못했습니다. [SESSION/ERROR]",
            safeApplicationFailureMessage(checkNotNull(failure)))
    }

    @Test fun causeCyclesAndLongChainsAreBounded() {
        val first = IllegalStateException("private1")
        val second = IllegalStateException("private2", first)
        first.initCause(second)
        assertEquals("작업을 완료하지 못했습니다. [UNKNOWN/ERROR]", safeApplicationFailureMessage(first))
        var deep: Throwable = PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.HTTP, 404)
        repeat(40) { deep = IllegalStateException("secret", deep) }
        assertEquals("작업을 완료하지 못했습니다. [UNKNOWN/ERROR]", safeApplicationFailureMessage(deep))
    }

    @Test fun successfulStageReturnsTheSameValue() = runBlocking<Unit> {
        val value = Any()
        assertSame(value, withPageFailureStage(PageFailureStage.DOCUMENT) { value })
    }
}

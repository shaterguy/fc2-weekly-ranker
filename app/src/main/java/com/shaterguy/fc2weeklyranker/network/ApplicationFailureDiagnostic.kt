package com.shaterguy.fc2weeklyranker.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.Collections
import java.util.IdentityHashMap

internal enum class PageFailureStage { SESSION, DOCUMENT }
internal enum class PageFailureReason { HTTP, WEBVIEW, TIMEOUT, ERROR }

// Only enums and bounded numeric codes form the user-visible diagnostic.
// Never interpolate exception messages, addresses, page content, or request data.
internal class PageLoadException(
    val stage: PageFailureStage,
    val reason: PageFailureReason,
    code: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException(pageFailureMessage(stage, reason, code), cause) {
    val code: Int? = when (reason) {
        PageFailureReason.HTTP -> code?.takeIf { it in 400..599 }
        PageFailureReason.WEBVIEW -> code?.takeIf { it in -16..-1 }
        else -> null
    }
}

private fun pageFailureMessage(stage: PageFailureStage, reason: PageFailureReason, code: Int?): String {
    val safeCode = when (reason) {
        PageFailureReason.HTTP -> code?.takeIf { it in 400..599 }
        PageFailureReason.WEBVIEW -> code?.takeIf { it in -16..-1 }
        else -> null
    }
    val detail = when (reason) {
        PageFailureReason.HTTP -> "HTTP_${safeCode ?: "UNKNOWN"}"
        PageFailureReason.WEBVIEW -> "WEBVIEW_ERROR_${safeCode ?: "UNKNOWN"}"
        PageFailureReason.TIMEOUT -> "TIMEOUT"
        PageFailureReason.ERROR -> "ERROR"
    }
    val description = when (reason) {
        PageFailureReason.HTTP -> "페이지 요청에 실패했습니다."
        PageFailureReason.WEBVIEW -> "페이지를 불러오지 못했습니다."
        PageFailureReason.TIMEOUT -> "페이지 응답 시간이 초과되었습니다."
        PageFailureReason.ERROR -> "페이지 처리를 완료하지 못했습니다."
    }
    return "$description [${stage.name}/$detail]"
}

internal suspend fun <T> withPageFailureStage(stage: PageFailureStage, block: suspend () -> T): T =
    try {
        block()
    } catch (error: TimeoutCancellationException) {
        throw PageLoadException(stage, PageFailureReason.TIMEOUT, cause = error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: PageLoadException) {
        throw error
    } catch (error: Throwable) {
        throw PageLoadException(stage, PageFailureReason.ERROR, cause = error)
    }

internal fun safeApplicationFailureMessage(error: Throwable): String {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    repeat(16) {
        val failure = current ?: return@repeat
        if (!seen.add(failure)) return "작업을 완료하지 못했습니다. [UNKNOWN/ERROR]"
        when (failure) {
            is PageLoadException -> return pageFailureMessage(failure.stage, failure.reason, failure.code)
            is TimeoutCancellationException -> return "페이지 응답 시간이 초과되었습니다. [UNKNOWN/TIMEOUT]"
            is CancellationException -> return "작업이 취소되었습니다. [UNKNOWN/CANCELLED]"
        }
        current = failure.cause
    }
    return "작업을 완료하지 못했습니다. [UNKNOWN/ERROR]"
}

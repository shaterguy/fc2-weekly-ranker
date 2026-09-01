package com.shaterguy.fc2weeklyranker.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

data class DateWindow(val pageIndex: Int, val startDate: LocalDate, val endDate: LocalDate, val startInclusive: Instant, val upperInclusive: Instant) {
    fun contains(instant: Instant): Boolean = !instant.isBefore(startInclusive) && !instant.isAfter(upperInclusive)
}

data class RankCandidate<T>(val value: T, val postedAt: Instant, val commentCount: Int, val stableId: String)

fun windowFor(anchor: Instant, pageIndex: Int): DateWindow {
    require(pageIndex >= 0)
    val anchorDate = anchor.atZone(SEOUL).toLocalDate()
    val endDate = anchorDate.minusDays((pageIndex * 7).toLong())
    val startDate = endDate.minusDays(6)
    val start = startDate.atStartOfDay(SEOUL).toInstant()
    val upper = if (pageIndex == 0) anchor else endDate.plusDays(1).atStartOfDay(SEOUL).toInstant().minusNanos(1)
    return DateWindow(pageIndex, startDate, endDate, start, upper)
}

fun dailyRate(anchor: Instant, postedAt: Instant, commentCount: Int): Double {
    val anchorDate = anchor.atZone(SEOUL).toLocalDate()
    val postedDate = postedAt.atZone(SEOUL).toLocalDate()
    val elapsedDays = maxOf(1L, ChronoUnit.DAYS.between(postedDate, anchorDate))
    return commentCount.coerceAtLeast(0) / elapsedDays.toDouble()
}

fun <T> rank(anchor: Instant, candidates: List<RankCandidate<T>>): List<Pair<RankCandidate<T>, Double>> = candidates
    .map { it to dailyRate(anchor, it.postedAt, it.commentCount) }
    .sortedWith(
        compareByDescending<Pair<RankCandidate<T>, Double>> { it.second }
            .thenByDescending { it.first.commentCount }
            .thenByDescending { it.first.postedAt.atZone(SEOUL).toLocalDate() }
            .thenByDescending { it.first.stableId },
    )

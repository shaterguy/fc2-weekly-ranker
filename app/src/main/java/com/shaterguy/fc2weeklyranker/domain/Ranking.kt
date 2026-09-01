package com.shaterguy.fc2weeklyranker.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

data class DateWindow(val pageIndex: Int, val startDate: LocalDate, val endDate: LocalDate, val startInclusive: Instant, val upperInclusive: Instant) {
    fun contains(instant: Instant): Boolean = !instant.isBefore(startInclusive) && !instant.isAfter(upperInclusive)
}

data class RankCandidate<T>(val value: T, val postedAt: Instant, val recommendationCount: Int, val stableId: String)

fun windowFor(anchor: Instant, pageIndex: Int): DateWindow {
    require(pageIndex >= 0)
    val anchorDate = anchor.atZone(SEOUL).toLocalDate()
    val endDate = anchorDate.minusDays((pageIndex * 7).toLong())
    val startDate = endDate.minusDays(6)
    val start = startDate.atStartOfDay(SEOUL).toInstant()
    val upper = if (pageIndex == 0) anchor else endDate.plusDays(1).atStartOfDay(SEOUL).toInstant().minusNanos(1)
    return DateWindow(pageIndex, startDate, endDate, start, upper)
}

fun dailyRate(anchor: Instant, postedAt: Instant, recommendationCount: Int): Double {
    val elapsedSeconds = max(0L, Duration.between(postedAt, anchor).seconds)
    val elapsedDays = max(1.0, elapsedSeconds / 86_400.0)
    return recommendationCount.coerceAtLeast(0) / elapsedDays
}

fun <T> rank(anchor: Instant, candidates: List<RankCandidate<T>>): List<Pair<RankCandidate<T>, Double>> = candidates
    .map { it to dailyRate(anchor, it.postedAt, it.recommendationCount) }
    .sortedWith(compareByDescending<Pair<RankCandidate<T>, Double>> { it.second }.thenByDescending { it.first.recommendationCount }.thenByDescending { it.first.postedAt }.thenByDescending { it.first.stableId })

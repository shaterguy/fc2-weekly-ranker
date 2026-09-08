package com.shaterguy.fc2weeklyranker.domain

import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.data.RankObservationEntity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max

private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 86_400_000L
private const val HISTORY_DAYS = 90L
private const val CLOCK_SKEW_MILLIS = 5 * 60_000L
private const val MIN_TREND_INTERVAL_HOURS = 0.5
private const val MAX_TREND_INTERVAL_HOURS = 72.0
private const val TREND_LOOKBACK_MILLIS = 72L * HOUR_MILLIS
private const val LN_2 = 0.6931471805599453
private const val MAX_LOG_AGE_CORRECTION = 1.3862943611198906 // ln(4)

enum class RankingMode {
    POPULARITY,
    TRENDING,
}

data class RankedPost(
    val post: PostEntity,
    val commentCount: Int?,
    val popularityScore: Double,
    val trendingScore: Double,
    val popularityConfidence: Double,
    val trendingObserved: Boolean,
)

object AdaptiveRanking {
    fun rank(
        posts: List<PostEntity>,
        observations: List<RankObservationEntity>,
        mode: RankingMode,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): List<RankedPost> {
        if (posts.isEmpty()) return emptyList()

        val histories = observations.asSequence()
            .filter { isValidObservation(it, nowEpochMillis) }
            .groupBy { it.postId }
            .mapValues { (_, values) -> deduplicate(values) }
            .filterValues { it.isNotEmpty() }
        val latestByPost = histories.mapValues { (_, values) -> values.last() }
        val globalGrowthSlope = learnedGrowthSlope(histories)
        val intervals = collectIntervals(histories, nowEpochMillis)
        val intervalRateCap = robustRateCap(intervals.map { it.ratePerHour })
        val cappedIntervals = intervals.map { it.copy(ratePerHour = it.ratePerHour.coerceAtMost(intervalRateCap)) }
        val intervalPostCount = cappedIntervals.mapTo(hashSetOf()) { it.postId }.size
        val trendPrior = cappedIntervals.map { it.ratePerHour }
            .takeIf { intervalPostCount >= 3 && it.isNotEmpty() }
            ?.let(::median)

        val observedCommentRates = posts.mapNotNull { post ->
            latestByPost[post.id]?.let { observation ->
                coldDailyRate(post, observation.commentCount, observation.observedAtEpochMillis)
            }
        }
        val legacyRates = posts.map { it.dailyRate.coerceAtLeast(0.0) }

        data class Working(
            val post: PostEntity,
            val commentCount: Int?,
            val popularity: Double,
            val confidence: Double,
            val trendRaw: Double,
            val trendObserved: Boolean,
        )

        val working = posts.map { post ->
            val latest = latestByPost[post.id]
            val commentCount = latest?.commentCount
            val coldPopularity = if (latest != null && observedCommentRates.isNotEmpty()) {
                percentile(
                    coldDailyRate(post, latest.commentCount, latest.observedAtEpochMillis),
                    observedCommentRates,
                )
            } else {
                percentile(post.dailyRate.coerceAtLeast(0.0), legacyRates)
            }
            val learned = if (latest != null) {
                sameAgePopularity(
                    post = post,
                    target = latest,
                    histories = histories,
                    globalGrowthSlope = globalGrowthSlope,
                    nowEpochMillis = nowEpochMillis,
                )
            } else {
                null
            }
            val popularityConfidence = learned?.second ?: 0.0
            val popularity = 100.0 * (
                if (learned == null) coldPopularity
                else (1.0 - popularityConfidence) * coldPopularity + popularityConfidence * learned.first
            )

            val ownIntervals = cappedIntervals.filter { it.postId == post.id }
            val trendObserved = ownIntervals.isNotEmpty()
            val latestObservedAt = latest?.observedAtEpochMillis
            val freshness = freshnessDecay(latestObservedAt, nowEpochMillis)
            val trendRaw = if (trendObserved) {
                val weightedRate = weightedRecentRate(ownIntervals, nowEpochMillis)
                val acceleration = accelerationBoost(ownIntervals)
                weightedRate * (1.0 + 0.25 * acceleration) * freshness
            } else {
                val coldHourly = when {
                    latest != null -> coldDailyRate(post, latest.commentCount, latest.observedAtEpochMillis) / 24.0
                    else -> post.dailyRate.coerceAtLeast(0.0) / 24.0
                }
                val learnedWeight = if (trendPrior == null) 0.0 else intervalPostCount / (intervalPostCount + 20.0)
                ((1.0 - learnedWeight) * coldHourly + learnedWeight * (trendPrior ?: 0.0)) * freshness
            }
            Working(post, commentCount, popularity, popularityConfidence, trendRaw, trendObserved)
        }

        val positiveTrendValues = working.map { it.trendRaw.coerceAtLeast(0.0) }
        return working.map { item ->
            val trendScore = if (item.trendRaw <= 0.0) 0.0 else 100.0 * percentile(item.trendRaw, positiveTrendValues)
            RankedPost(
                post = item.post,
                commentCount = item.commentCount,
                popularityScore = item.popularity.coerceIn(0.0, 100.0),
                trendingScore = trendScore.coerceIn(0.0, 100.0),
                popularityConfidence = item.confidence.coerceIn(0.0, 1.0),
                trendingObserved = item.trendObserved,
            )
        }.sortedWith(
            compareByDescending<RankedPost> {
                if (mode == RankingMode.POPULARITY) it.popularityScore else it.trendingScore
            }
                .thenByDescending { it.commentCount ?: -1 }
                .thenByDescending { it.post.dailyRate }
                .thenByDescending { it.post.postedAtEpochMillis }
                .thenByDescending { it.post.id },
        )
    }

    private fun sameAgePopularity(
        post: PostEntity,
        target: RankObservationEntity,
        histories: Map<String, List<RankObservationEntity>>,
        globalGrowthSlope: Double,
        nowEpochMillis: Long,
    ): Pair<Double, Double>? {
        val targetX = logAge(target.postedAtEpochMillis, target.observedAtEpochMillis)
        data class Peer(val adjustedComments: Double, val weight: Double)
        val peers = histories.asSequence()
            .filter { (postId, _) -> postId != post.id }
            .mapNotNull { (_, history) ->
                val peer = history.minByOrNull { observation ->
                    abs(logAge(observation.postedAtEpochMillis, observation.observedAtEpochMillis) - targetX)
                } ?: return@mapNotNull null
                val peerX = logAge(peer.postedAtEpochMillis, peer.observedAtEpochMillis)
                val distance = abs(peerX - targetX)
                if (distance > 1.5) return@mapNotNull null
                val correction = (globalGrowthSlope * (targetX - peerX))
                    .coerceIn(-MAX_LOG_AGE_CORRECTION, MAX_LOG_AGE_CORRECTION)
                val adjusted = expm1((ln1p(peer.commentCount.toDouble()) + correction).coerceAtLeast(0.0))
                val freshnessDays = max(0.0, (nowEpochMillis - peer.observedAtEpochMillis) / DAY_MILLIS.toDouble())
                val weight = exp(-distance / 0.55) * exp(-LN_2 * freshnessDays / 30.0)
                Peer(adjusted, weight)
            }
            .filter { it.weight > 0.01 }
            .toList()
        if (peers.isEmpty()) return null

        val totalWeight = peers.sumOf { it.weight }
        if (totalWeight <= 0.0) return null
        val belowWeight = peers.sumOf { peer ->
            when {
                peer.adjustedComments < target.commentCount -> peer.weight
                peer.adjustedComments == target.commentCount.toDouble() -> peer.weight * 0.5
                else -> 0.0
            }
        }
        val learnedPercentile = (belowWeight / totalWeight).coerceIn(0.0, 1.0)
        val effectivePeers = totalWeight.coerceAtMost(peers.size.toDouble())
        val confidence = effectivePeers / (effectivePeers + 20.0)
        return learnedPercentile to confidence
    }

    private fun learnedGrowthSlope(histories: Map<String, List<RankObservationEntity>>): Double {
        val perPostSlopes = histories.values.mapNotNull { history ->
            val slopes = history.zipWithNext().mapNotNull { (first, second) ->
                if (second.commentCount < first.commentCount) return@mapNotNull null
                val x1 = logAge(first.postedAtEpochMillis, first.observedAtEpochMillis)
                val x2 = logAge(second.postedAtEpochMillis, second.observedAtEpochMillis)
                val dx = x2 - x1
                if (dx <= 0.02) return@mapNotNull null
                ((ln1p(second.commentCount.toDouble()) - ln1p(first.commentCount.toDouble())) / dx)
                    .takeIf { it.isFinite() && it >= 0.0 }
            }
            slopes.takeIf { it.isNotEmpty() }?.let(::median)
        }
        if (perPostSlopes.size < 5) return 0.0
        val shrinkage = perPostSlopes.size / (perPostSlopes.size + 20.0)
        return (median(perPostSlopes) * shrinkage).coerceAtLeast(0.0)
    }

    private data class TrendInterval(
        val postId: String,
        val endEpochMillis: Long,
        val ratePerHour: Double,
    )

    private fun collectIntervals(
        histories: Map<String, List<RankObservationEntity>>,
        nowEpochMillis: Long,
    ): List<TrendInterval> = buildList {
        histories.forEach { (postId, history) ->
            history.zipWithNext().forEach { (first, second) ->
                val elapsedHours = (second.observedAtEpochMillis - first.observedAtEpochMillis) / HOUR_MILLIS.toDouble()
                if (elapsedHours < MIN_TREND_INTERVAL_HOURS || elapsedHours > MAX_TREND_INTERVAL_HOURS) return@forEach
                if (second.observedAtEpochMillis < nowEpochMillis - TREND_LOOKBACK_MILLIS) return@forEach
                val delta = second.commentCount - first.commentCount
                if (delta < 0) return@forEach
                val rate = delta / elapsedHours
                if (rate.isFinite() && rate >= 0.0) add(TrendInterval(postId, second.observedAtEpochMillis, rate))
            }
        }
    }

    private fun weightedRecentRate(intervals: List<TrendInterval>, nowEpochMillis: Long): Double {
        if (intervals.isEmpty()) return 0.0
        var weighted = 0.0
        var weightSum = 0.0
        intervals.forEach { interval ->
            val hoursOld = max(0.0, (nowEpochMillis - interval.endEpochMillis) / HOUR_MILLIS.toDouble())
            val weight = exp(-LN_2 * hoursOld / 24.0)
            weighted += interval.ratePerHour * weight
            weightSum += weight
        }
        return if (weightSum > 0.0) weighted / weightSum else 0.0
    }

    private fun accelerationBoost(intervals: List<TrendInterval>): Double {
        val ordered = intervals.sortedBy { it.endEpochMillis }
        if (ordered.size < 2) return 0.0
        val previous = ordered[ordered.lastIndex - 1].ratePerHour
        val latest = ordered.last().ratePerHour
        if (latest <= previous) return 0.0
        return ((latest - previous) / max(previous, 1.0 / 24.0)).coerceIn(0.0, 1.0)
    }

    private fun robustRateCap(values: List<Double>): Double {
        val finite = values.filter { it.isFinite() && it >= 0.0 }
        if (finite.isEmpty()) return 10_000.0
        if (finite.size < 10) return max(100.0, finite.maxOrNull() ?: 100.0).coerceAtMost(10_000.0)
        val center = median(finite)
        val mad = median(finite.map { abs(it - center) })
        return (center + 12.0 * 1.4826 * mad).coerceAtLeast(1.0).coerceAtMost(10_000.0)
    }

    private fun isValidObservation(observation: RankObservationEntity, nowEpochMillis: Long): Boolean {
        if (observation.commentCount < 0 || observation.commentCount > 1_000_000) return false
        if (observation.postedAtEpochMillis <= 0L || observation.observedAtEpochMillis <= 0L) return false
        if (observation.observedAtEpochMillis + CLOCK_SKEW_MILLIS < observation.postedAtEpochMillis) return false
        if (observation.observedAtEpochMillis > nowEpochMillis + CLOCK_SKEW_MILLIS) return false
        if (observation.observedAtEpochMillis < nowEpochMillis - HISTORY_DAYS * DAY_MILLIS) return false
        return true
    }

    private fun deduplicate(values: List<RankObservationEntity>): List<RankObservationEntity> = values
        .groupBy { it.observedBucketEpochMillis }
        .values
        .mapNotNull { bucket -> bucket.maxByOrNull { it.observedAtEpochMillis } }
        .sortedBy { it.observedAtEpochMillis }

    private fun coldDailyRate(post: PostEntity, comments: Int, atEpochMillis: Long): Double {
        val elapsedDays = max(1.0, (atEpochMillis - post.postedAtEpochMillis).coerceAtLeast(0L) / DAY_MILLIS.toDouble())
        return comments.coerceAtLeast(0) / elapsedDays
    }

    private fun logAge(postedAtEpochMillis: Long, observedAtEpochMillis: Long): Double {
        val ageHours = max(0.0, (observedAtEpochMillis - postedAtEpochMillis) / HOUR_MILLIS.toDouble())
        return ln(1.0 + ageHours) / LN_2
    }

    private fun freshnessDecay(observedAtEpochMillis: Long?, nowEpochMillis: Long): Double {
        if (observedAtEpochMillis == null) return 1.0
        val hoursOld = max(0.0, (nowEpochMillis - observedAtEpochMillis) / HOUR_MILLIS.toDouble())
        return exp(-LN_2 * hoursOld / 48.0)
    }

    private fun percentile(value: Double, population: List<Double>): Double {
        val finite = population.filter { it.isFinite() }
        if (finite.isEmpty()) return 0.5
        var below = 0.0
        finite.forEach { peer ->
            below += when {
                peer < value -> 1.0
                peer == value -> 0.5
                else -> 0.0
            }
        }
        return (below / finite.size).coerceIn(0.0, 1.0)
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

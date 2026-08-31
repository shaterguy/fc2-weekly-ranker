package com.shaterguy.fc2weeklyranker.network

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

internal class RetryingDns(
    private val delegate: Dns = Dns.SYSTEM,
    retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Dns {
    private val retryDelaysMillis = retryDelaysMillis.toList()

    init {
        require(this.retryDelaysMillis.all { it >= 0L }) { "DNS retry delays must be non-negative." }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        var retryIndex = 0
        while (true) {
            try {
                return delegate.lookup(hostname)
            } catch (failure: UnknownHostException) {
                if (retryIndex >= retryDelaysMillis.size) throw failure
                val delayMillis = retryDelaysMillis[retryIndex++]
                if (delayMillis > 0L) sleeper(delayMillis)
            }
        }
    }

    companion object {
        internal val DEFAULT_RETRY_DELAYS_MILLIS = listOf(500L, 1_500L, 3_000L)
    }
}

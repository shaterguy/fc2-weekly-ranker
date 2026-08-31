package com.shaterguy.fc2weeklyranker.network

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

internal class FallbackDns(
    delegates: List<Dns>,
) : Dns {
    private val delegates = delegates.toList()

    init {
        require(this.delegates.isNotEmpty()) { "At least one DNS resolver is required." }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val failures = mutableListOf<UnknownHostException>()
        delegates.forEach { delegate ->
            try {
                val addresses = delegate.lookup(hostname)
                if (addresses.isNotEmpty()) return addresses
                failures += UnknownHostException("Resolver returned no addresses for $hostname")
            } catch (failure: UnknownHostException) {
                failures += failure
            }
        }

        throw UnknownHostException(
            "Unable to resolve host \"$hostname\" using system DNS or secure DNS",
        ).also { combined ->
            failures.forEach(combined::addSuppressed)
        }
    }
}

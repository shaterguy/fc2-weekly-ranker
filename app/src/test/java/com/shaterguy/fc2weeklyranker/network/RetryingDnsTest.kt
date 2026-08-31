package com.shaterguy.fc2weeklyranker.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class RetryingDnsTest {
    @Test
    fun `transient unknown host is retried and then resolved`() {
        val expected = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        var attempts = 0
        val delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                attempts += 1
                if (attempts == 1) throw UnknownHostException(hostname)
                return expected
            }
        }
        val sleeps = mutableListOf<Long>()
        val dns = RetryingDns(delegate, listOf(10L, 20L)) { sleeps += it }

        assertEquals(expected, dns.lookup("example.test"))
        assertEquals(2, attempts)
        assertEquals(listOf(10L), sleeps)
    }

    @Test
    fun `persistent unknown host stops after the bounded retries`() {
        var attempts = 0
        val delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                attempts += 1
                throw UnknownHostException(hostname)
            }
        }
        val sleeps = mutableListOf<Long>()
        val dns = RetryingDns(delegate, listOf(10L, 20L)) { sleeps += it }

        assertThrows(UnknownHostException::class.java) {
            dns.lookup("example.test")
        }
        assertEquals(3, attempts)
        assertEquals(listOf(10L, 20L), sleeps)
    }
}

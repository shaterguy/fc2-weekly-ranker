package com.shaterguy.fc2weeklyranker.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class FallbackDnsTest {
    @Test
    fun `uses the next resolver after system DNS fails`() {
        val expected = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val attempts = mutableListOf<String>()
        val failing = resolver("system", attempts) { throw UnknownHostException("blocked") }
        val working = resolver("secure", attempts) { expected }

        assertEquals(expected, FallbackDns(listOf(failing, working)).lookup("example.test"))
        assertEquals(listOf("system", "secure"), attempts)
    }

    @Test
    fun `reports failure after every resolver has been tried once`() {
        val attempts = mutableListOf<String>()
        val first = resolver("system", attempts) { throw UnknownHostException("system") }
        val second = resolver("secure", attempts) { throw UnknownHostException("secure") }

        val failure = assertThrows(UnknownHostException::class.java) {
            FallbackDns(listOf(first, second)).lookup("example.test")
        }

        assertEquals(listOf("system", "secure"), attempts)
        assertEquals(2, failure.suppressed.size)
    }

    private fun resolver(
        name: String,
        attempts: MutableList<String>,
        result: () -> List<InetAddress>,
    ): Dns = Dns {
        attempts += name
        result()
    }
}

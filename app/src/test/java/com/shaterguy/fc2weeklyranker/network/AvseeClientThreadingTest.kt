package com.shaterguy.fc2weeklyranker.network

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class AvseeClientThreadingTest {
    @Test
    fun `test connection runs blocking request on injected io dispatcher`() {
        val callerThread = Thread.currentThread()
        val observedThread = AtomicReference<Thread>()
        val http = OkHttpClient.Builder()
            .addInterceptor {
                observedThread.set(Thread.currentThread())
                throw IOException("synthetic stop")
            }
            .build()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "avsee-test-io") }

        executor.asCoroutineDispatcher().use { dispatcher ->
            val result = runBlocking {
                AvseeClient(http, dispatcher).testConnection("https://example.test")
            }
            assertTrue(result.isFailure)
        }

        val networkThread = requireNotNull(observedThread.get())
        assertNotSame(callerThread, networkThread)
        assertTrue(networkThread.name.startsWith("avsee-test-io"))
    }
}

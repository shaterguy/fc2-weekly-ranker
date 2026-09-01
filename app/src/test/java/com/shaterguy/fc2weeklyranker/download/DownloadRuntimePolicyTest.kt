package com.shaterguy.fc2weeklyranker.download

import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.data.DownloadEntity
import com.shaterguy.fc2weeklyranker.data.DownloadQueuePolicy
import com.shaterguy.fc2weeklyranker.data.DownloadStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRuntimePolicyTest {
    @Test
    fun schedulerKind_switchesToUidtAtAndroid14() {
        assertEquals(DownloadSchedulerKind.WORK_MANAGER, DownloadRuntimePolicy.schedulerKind(33))
        assertEquals(DownloadSchedulerKind.UIDT, DownloadRuntimePolicy.schedulerKind(34))
        assertEquals(DownloadSchedulerKind.UIDT, DownloadRuntimePolicy.schedulerKind(36))
    }

    @Test
    fun enqueueOrder_isStrictlyMonotonic() {
        assertEquals(1L, AppDatabase.nextEnqueueOrder(null))
        assertEquals(1L, AppDatabase.nextEnqueueOrder(0L))
        assertEquals(42L, AppDatabase.nextEnqueueOrder(41L))
    }

    @Test
    fun queuePolicy_preservesPauseResumeButRestartsGetNewOrder() {
        assertEquals(7L, DownloadQueuePolicy.enqueueOrder(DownloadStatus.PAUSED, 7L, 41L))
        assertEquals(2, DownloadQueuePolicy.retryCount(DownloadStatus.PAUSED, 2))

        assertEquals(42L, DownloadQueuePolicy.enqueueOrder(DownloadStatus.STOPPED, 7L, 41L))
        assertEquals(42L, DownloadQueuePolicy.enqueueOrder(DownloadStatus.FAILED, 7L, 41L))
        assertEquals(0, DownloadQueuePolicy.retryCount(DownloadStatus.STOPPED, 2))
        assertEquals(0, DownloadQueuePolicy.retryCount(DownloadStatus.FAILED, 2))
    }

    @Test
    fun transferConcurrency_isBoundedToThree() {
        assertEquals(3, DownloadRuntimePolicy.MAX_CONCURRENT_TRANSFERS)
    }

    @Test
    fun schedulerOperationGate_serializesConcurrentMutations() = runTest {
        val gate = SchedulerOperationGate()
        var active = 0
        var maxActive = 0

        (0 until 8).map {
            async {
                gate.run {
                    active += 1
                    maxActive = maxOf(maxActive, active)
                    yield()
                    active -= 1
                }
            }
        }.awaitAll()

        assertEquals(1, maxActive)
        assertEquals(0, active)
    }

    @Test
    fun uidtJobId_usesStableQueueOrder() {
        val state = DownloadEntity(
            videoId = "abcdef123456",
            status = DownloadStatus.QUEUED,
            contentUri = null,
            downloadedBytes = 0L,
            totalBytes = null,
            errorCode = null,
            updatedAtEpochMillis = 100L,
            enqueueOrder = 77L,
            retryCount = 0,
        )
        assertEquals(77, DownloadRuntimePolicy.jobId(state))
    }
}

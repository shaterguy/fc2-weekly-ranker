package com.shaterguy.fc2weeklyranker.ui

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.domain.ContentMode
import com.shaterguy.fc2weeklyranker.network.ApplicationPageTransport
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import com.shaterguy.fc2weeklyranker.search.SearchDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue

/** Real ViewModel/repository/DataStore/Room with neutral in-process HTML transport. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class BaseUrlSaveTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var searchDb: SearchDatabase
    private lateinit var vm: MainViewModel
    private val viewModels = ViewModelStore()
    private val requests = ConcurrentLinkedQueue<String>()
    private val blocked = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val cancelled = CompletableDeferred<Unit>()
    private var holdOldPrefetch = false
    private val prefetchStarted = CompletableDeferred<Unit>()
    private val prefetchCancelled = CompletableDeferred<Unit>()

    @Before fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        AppGraph.initialize(app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        searchDb = Room.inMemoryDatabaseBuilder(app, SearchDatabase::class.java).build()
        replaceGraph("database", db)
        replaceGraph("searchDatabase", searchDb)
        val http = OkHttpClient.Builder().addInterceptor { throw AssertionError("Real HTTP is forbidden") }.build()
        val source = AvseeClient(http, applicationPageTransport = object : ApplicationPageTransport {
            override suspend fun fetch(url: String, referer: String?): String {
                requests.add(url)
                val uri = URI(url)
                if (uri.host == URI(OLD).host && holdOldPrefetch && url.contains("page=")) {
                    prefetchStarted.complete(Unit)
                    try { CompletableDeferred<Unit>().await() }
                    catch (e: CancellationException) { prefetchCancelled.complete(Unit); throw e }
                }
                if (uri.host == "blocked.example.test") {
                    blocked.complete(Unit)
                    try { release.await() } catch (e: CancellationException) { cancelled.complete(Unit); throw e }
                }
                if (uri.host == "failed.example.test") return "<html><body>Neutral invalid board</body></html>"
                val table = if (url.contains("bo_table=javc&")) "javc" else "javfc2"
                return if (url.contains("wr_id=")) {
                    "<h1>Neutral fixture</h1><span itemprop='datePublished' content='2026-10-08KST12:00:00'></span>"
                } else if (url.contains("page=")) {
                    "<form id='fboardlist'></form>"
                } else {
                    "<form id='fboardlist'><div class='list-item'><h2><a href='/bbs/board.php?bo_table=$table&wr_id=123'>Neutral fixture</a></h2><span class='comments'><i class='fa fa-comment'></i><b>3</b></span></div></form>"
                }
            }
        })
        replaceGraph("httpClient", http)
        replaceGraph("sourceClient", source)
        replaceGraph("repository", AppRepository(app, db, AppGraph.settings, source))
        @Suppress("UNCHECKED_CAST")
        val preferences = AppGraph.settings.javaClass.getDeclaredField("dataStore").apply { isAccessible = true }
            .get(AppGraph.settings) as DataStore<Preferences>
        preferences.edit { it.clear() }
        AppGraph.settings.setSelectedContentMode(ContentMode.FC2)
        AppGraph.settings.setBaseUrl(OLD, ContentMode.FC2)
        AppGraph.settings.setBaseUrl(OLD, ContentMode.JAV)
        AppGraph.settings.setAnchor(1791439200000L, ContentMode.FC2)
        AppGraph.settings.setAnchor(1791439200000L, ContentMode.JAV)
        vm = ViewModelProvider.create(viewModels, ViewModelProvider.AndroidViewModelFactory(app)).get(MainViewModel::class.java)
        waitUntil { vm.baseUrl.value == OLD }
    }

    @After fun tearDown() {
        viewModels.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        AppGraph.searchScheduler.cancelActive()
        searchDb.close()
        db.close()
    }

    @Test fun saveOnlyValidatesAndPersistsWithoutCrawlDatabaseWritesOrPrefetch() = runBlocking {
        val before = db.openHelper.readableDatabase.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) }
        vm.saveBaseUrl(" https://saved.example.test/ ")
        waitUntil { vm.baseUrl.value == "https://saved.example.test" && !vm.isLoading.value }
        assertTrue(vm.message.value.orEmpty(), vm.message.value.orEmpty().contains("주소를 저장했습니다"))
        assertEquals("Only board and detail connection validation may run", 2, requests.size)
        assertTrue(requests.none { it.contains("page=") })
        val prefetch = MainViewModel::class.java.getDeclaredField("pagePrefetch").apply { isAccessible = true }.get(vm)
        assertNull("Saving must not restart prefetch", prefetch.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(prefetch))
        val after = db.openHelper.readableDatabase.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) }
        assertEquals("Saving must not write the ranking database", before, after)
        assertEquals("https://saved.example.test", AppGraph.settings.baseUrl(ContentMode.FC2).first())
        assertEquals("https://saved.example.test", AppGraph.settings.baseUrl(ContentMode.JAV).first())
        assertEquals(1791439200000L, AppGraph.settings.anchorEpochMillis(ContentMode.FC2).first())
        assertEquals(1791439200000L, AppGraph.settings.anchorEpochMillis(ContentMode.JAV).first())
    }

    @Test fun invalidAddressNeverRequestsOrChangesTheSavedAddress() = runBlocking {
        assertTrue(AppGraph.repository.setBaseUrl("http://invalid.example.test").isFailure)
        assertTrue(requests.isEmpty())
        assertOldAddress()
    }

    @Test fun saveCancelsTheRunningOldOriginPrefetch() {
        holdOldPrefetch = true
        val prefetch = MainViewModel::class.java.getDeclaredField("pagePrefetch").apply { isAccessible = true }
            .get(vm) as PagePrefetchCoordinator
        prefetch.start(ContentMode.FC2, 1)
        waitUntil { prefetchStarted.isCompleted }
        vm.saveBaseUrl("https://saved.example.test")
        waitUntil { vm.baseUrl.value == "https://saved.example.test" && !vm.isLoading.value }
        assertTrue("Existing prefetch must be cancelled before validation", prefetchCancelled.isCompleted)
        assertNull("Save must leave prefetch idle", prefetch.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(prefetch))
        assertEquals(2, requests.count { URI(it).host == "saved.example.test" })
    }

    @Test fun clearedViewModelCancelsValidationWithoutSaving() = runBlocking {
        vm.saveBaseUrl("https://blocked.example.test")
        waitUntil { blocked.isCompleted }
        viewModels.clear()
        waitUntil { cancelled.isCompleted }
        release.complete(Unit)
        assertOldAddress()
    }

    @Test fun failedConnectionPreservesBothSavedAddresses() = runBlocking {
        vm.saveBaseUrl("https://failed.example.test")
        waitUntil { vm.message.value?.startsWith("주소 저장 실패") == true && !vm.isLoading.value }
        assertOldAddress()
    }

    @Test fun newerSaveCancelsOldValidationAndCannotBeOverwritten() = runBlocking {
        vm.saveBaseUrl("https://blocked.example.test")
        waitUntil { blocked.isCompleted }
        vm.saveBaseUrl("https://newest.example.test")
        waitUntil { vm.baseUrl.value == "https://newest.example.test" && !vm.isLoading.value }
        release.complete(Unit)
        waitUntil { cancelled.isCompleted || requests.count { URI(it).host == "blocked.example.test" } > 1 }
        assertTrue("Replacing a save must cancel its pending connection validation", cancelled.isCompleted)
        assertEquals("https://newest.example.test", AppGraph.settings.baseUrl(ContentMode.FC2).first())
        assertEquals("https://newest.example.test", AppGraph.settings.baseUrl(ContentMode.JAV).first())
    }

    @Test fun modeSwitchCancelsPendingSaveAndPreservesThePreviousAddress() = runBlocking {
        vm.saveBaseUrl("https://blocked.example.test")
        waitUntil { blocked.isCompleted }
        vm.selectContentMode(ContentMode.JAV)
        release.complete(Unit)
        waitUntil { cancelled.isCompleted || requests.count { URI(it).host == "blocked.example.test" } > 1 }
        assertTrue("Mode switch must cancel the previous save", cancelled.isCompleted)
        assertOldAddress()
        waitUntil { vm.baseUrl.value == OLD }
        assertEquals(ContentMode.JAV, vm.currentApplicationOrigin()?.mode)
    }

    private suspend fun assertOldAddress() {
        for (mode in ContentMode.entries) assertEquals(OLD, AppGraph.settings.baseUrl(mode).first())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "Timed out waiting for fixture state: ${vm.message.value}" }
            Thread.yield()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun replaceGraph(name: String, value: Any) {
        AppGraph::class.java.getDeclaredField(name).apply { isAccessible = true }.set(AppGraph, value)
    }

    companion object { private const val OLD = "https://old.example.test" }
}

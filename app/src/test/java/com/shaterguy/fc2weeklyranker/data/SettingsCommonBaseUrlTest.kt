package com.shaterguy.fc2weeklyranker.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.shaterguy.fc2weeklyranker.domain.ContentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Real DataStore reads/writes; only legacy preference seeding uses reflection. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SettingsCommonBaseUrlTest {
    private lateinit var app: Application
    private lateinit var store: SettingsStore
    private lateinit var dataStore: DataStore<Preferences>
    private val fc2Key = stringPreferencesKey("base_url")
    private val javKey = stringPreferencesKey("jav_base_url")

    @Before fun setUp() = runBlocking<Unit> {
        // Robolectric replaces Application/filesDir per test, but the Kotlin delegate is static.
        resetSettingsDelegate()
        app = ApplicationProvider.getApplicationContext()
        store = SettingsStore(app)
        @Suppress("UNCHECKED_CAST")
        dataStore = SettingsStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
            .get(store) as DataStore<Preferences>
        dataStore.edit { it.clear() }
    }

    @After fun stopTestStore() = runBlocking<Unit> { resetSettingsDelegate() }

    private suspend fun resetSettingsDelegate() {
        val delegate = settingsDelegate()
        val scopeField = delegate.javaClass.getDeclaredField("scope").apply { isAccessible = true }
        (scopeField.get(delegate) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin()
        scopeField.set(delegate, CoroutineScope(Dispatchers.IO + SupervisorJob()))
        delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(delegate, null)
    }

    @Test fun existingFc2AddressWinsForBothModes() = runBlocking {
        dataStore.edit { it[fc2Key] = "https://fc2.example.test"; it[javKey] = "https://jav.example.test" }
        assertCommonAddress("https://fc2.example.test")
    }

    @Test fun fc2OnlyLegacyAddressIsShared() = runBlocking {
        dataStore.edit { it[fc2Key] = "https://fc2.example.test" }
        assertCommonAddress("https://fc2.example.test")
    }

    @Test fun javOnlyLegacyAddressIsShared() = runBlocking {
        dataStore.edit { it[javKey] = "https://jav.example.test" }
        assertCommonAddress("https://jav.example.test")
    }

    @Test fun missingAddressesUseTheExistingDefault() = runBlocking {
        assertCommonAddress(SettingsStore.DEFAULT_BASE_URL)
    }

    @Test fun legacyOfficialOriginStillCanonicalizes() = runBlocking {
        dataStore.edit { it[javKey] = "https://01.avsee.is" }
        assertCommonAddress(SettingsStore.DEFAULT_BASE_URL)
    }

    @Test fun savingEitherModeWritesBothKeysAndPreservesModeData() = runBlocking {
        store.setAnchor(1000L, ContentMode.FC2)
        store.setAnchor(2000L, ContentMode.JAV)
        store.toggleFavoriteTag("fc2-fixture", ContentMode.FC2)
        store.toggleFavoriteTag("jav-fixture", ContentMode.JAV)
        for (mode in ContentMode.entries) {
            val url = "https://${mode.sourceKey.lowercase()}.example.test"
            store.setSelectedContentMode(mode)
            store.setBaseUrl(url, mode)
            val preferences = dataStore.data.first()
            assertEquals(url, preferences[fc2Key])
            assertEquals(url, preferences[javKey])
            assertCommonAddress(url)
            assertEquals(url, SettingsStore(app).baseUrl.first())
        }
        assertEquals(1000L, store.anchorEpochMillis(ContentMode.FC2).first())
        assertEquals(2000L, store.anchorEpochMillis(ContentMode.JAV).first())
        assertEquals(setOf("fc2-fixture"), store.favoriteTags(ContentMode.FC2).first())
        assertEquals(setOf("jav-fixture"), store.favoriteTags(ContentMode.JAV).first())
    }

    @Test fun committedAddressReopensFromDiskWithBothLegacyKeys() = runBlocking {
        val url = "https://restart.example.test"
        store.setBaseUrl(url, ContentMode.JAV)
        val directory = Files.createTempDirectory("common-address-reopen-").toFile()
        val copy = File(directory, "reopened.preferences_pb")
        File(app.filesDir, "datastore/ranker_settings.preferences_pb").copyTo(copy)
        val job = SupervisorJob()
        try {
            val reopened = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job), produceFile = { copy },
            ).data.first()
            assertEquals(url, reopened[fc2Key])
            assertEquals(url, reopened[javKey])
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private suspend fun assertCommonAddress(expected: String) {
        for (mode in ContentMode.entries) assertEquals(expected, store.baseUrl(mode).first())
        assertEquals(expected, store.baseUrl.first())
    }

    private fun settingsDelegate(): Any = Class.forName("com.shaterguy.fc2weeklyranker.data.SettingsStoreKt")
        .getDeclaredField("settingsDataStore\$delegate").apply { isAccessible = true }.get(null)
}

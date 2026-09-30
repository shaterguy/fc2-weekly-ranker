package com.shaterguy.fc2weeklyranker.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CiSourcePreferencesFixtureTest {
    @Test
    fun syntheticSeedRoundTripsThroughPinnedDataStore() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("fc2-ci-preferences-").toFile()
        val file = File(directory, "ranker_settings.preferences_pb")
        val firstJob = SupervisorJob()
        val secondJob = SupervisorJob()
        try {
            val seed = requireNotNull(javaClass.getResourceAsStream("/ci-source-settings.preferences_pb"))
                .use { it.readBytes() }
            file.writeBytes(seed)
            val first = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + firstJob),
                produceFile = { file },
            )
            val preferences = withTimeout(10_000) { first.data.first() }
            assertEquals("https://fixture.invalid", preferences[stringPreferencesKey("base_url")])
            assertEquals("https://fixture.invalid", preferences[stringPreferencesKey("jav_base_url")])
            assertEquals("FC2", preferences[stringPreferencesKey("content_mode")])
            withTimeout(10_000) {
                first.updateData { current ->
                    current.toMutablePreferences().apply {
                        this[stringPreferencesKey("ci_roundtrip")] = "retained"
                        this[intPreferencesKey("ci_number")] = 42
                        this[booleanPreferencesKey("ci_flag")] = true
                    }
                }
            }
            firstJob.cancelAndJoin()
            val second = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + secondJob),
                produceFile = { file },
            )
            val reopened = withTimeout(10_000) { second.data.first() }
            assertEquals("https://fixture.invalid", reopened[stringPreferencesKey("base_url")])
            assertEquals("https://fixture.invalid", reopened[stringPreferencesKey("jav_base_url")])
            assertEquals("retained", reopened[stringPreferencesKey("ci_roundtrip")])
            assertEquals(42, reopened[intPreferencesKey("ci_number")])
            assertEquals(true, reopened[booleanPreferencesKey("ci_flag")])
            secondJob.cancelAndJoin()
            val output = File("build/ci-source-settings-roundtrip.preferences_pb")
            output.parentFile.mkdirs()
            file.copyTo(output, overwrite = true)
        } finally {
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}

package com.shaterguy.fc2weeklyranker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "ranker_settings")

class SettingsStore(context: Context, private val clockMillis: () -> Long = { System.currentTimeMillis() }) {
    private val dataStore = context.settingsDataStore
    val anchorEpochMillis: Flow<Long?> = dataStore.data.map { it[ANCHOR] }
    val baseUrl: Flow<String> = dataStore.data.map { it[BASE_URL] ?: DEFAULT_BASE_URL }
    val visitedPostIds: Flow<Set<String>> = dataStore.data.map { it[VISITED_POST_IDS].orEmpty() }

    suspend fun ensureAnchor(): Long {
        var chosen = 0L
        dataStore.edit { prefs -> chosen = prefs[ANCHOR] ?: clockMillis().also { prefs[ANCHOR] = it } }
        return chosen
    }

    suspend fun refreshAnchor(): Long {
        val now = clockMillis()
        setAnchor(now)
        return now
    }

    suspend fun setAnchor(anchorEpochMillis: Long) {
        require(anchorEpochMillis > 0L)
        dataStore.edit { it[ANCHOR] = anchorEpochMillis }
    }

    suspend fun setAnchorIf(
        anchorEpochMillis: Long,
        guardedWrite: (() -> Unit) -> Boolean,
    ): Boolean {
        require(anchorEpochMillis > 0L)
        var committed = false
        dataStore.edit { preferences ->
            committed = guardedWrite {
                preferences[ANCHOR] = anchorEpochMillis
            }
        }
        return committed
    }

    suspend fun setBaseUrl(normalizedBaseUrl: String) { dataStore.edit { it[BASE_URL] = normalizedBaseUrl } }

    suspend fun isRankingWindowCovered(key: String): Boolean =
        dataStore.data.first()[RANKING_COVERED_WINDOWS].orEmpty().contains(key)

    suspend fun markRankingWindowCovered(key: String) {
        if (key.isBlank()) return
        dataStore.edit { prefs ->
            prefs[RANKING_COVERED_WINDOWS] = prefs[RANKING_COVERED_WINDOWS].orEmpty() + key
        }
    }

    suspend fun markPostVisited(postId: String) {
        if (postId.isBlank()) return
        dataStore.edit { prefs -> prefs[VISITED_POST_IDS] = prefs[VISITED_POST_IDS].orEmpty() + postId }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://01.avsee.is"
        private val ANCHOR = longPreferencesKey("anchor_epoch_millis")
        private val BASE_URL = stringPreferencesKey("base_url")
        private val VISITED_POST_IDS = stringSetPreferencesKey("visited_post_ids")
        private val RANKING_COVERED_WINDOWS = stringSetPreferencesKey("ranking_covered_windows_v1")
    }
}

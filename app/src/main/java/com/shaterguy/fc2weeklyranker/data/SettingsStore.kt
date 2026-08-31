package com.shaterguy.fc2weeklyranker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
        dataStore.edit { it[ANCHOR] = now }
        return now
    }

    suspend fun setBaseUrl(normalizedBaseUrl: String) { dataStore.edit { it[BASE_URL] = normalizedBaseUrl } }

    suspend fun markPostVisited(postId: String) {
        if (postId.isBlank()) return
        dataStore.edit { prefs -> prefs[VISITED_POST_IDS] = prefs[VISITED_POST_IDS].orEmpty() + postId }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://01.avsee.is"
        private val ANCHOR = longPreferencesKey("anchor_epoch_millis")
        private val BASE_URL = stringPreferencesKey("base_url")
        private val VISITED_POST_IDS = stringSetPreferencesKey("visited_post_ids")
    }
}

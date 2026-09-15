package com.shaterguy.fc2weeklyranker.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shaterguy.fc2weeklyranker.domain.ContentMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "ranker_settings")

class SettingsStore(context: Context, private val clockMillis: () -> Long = { System.currentTimeMillis() }) {
    private val dataStore = context.settingsDataStore
    val anchorEpochMillis: Flow<Long?> = anchorEpochMillis(ContentMode.FC2)
    val selectedContentMode: Flow<ContentMode> =
        dataStore.data.map { ContentMode.fromSourceKey(it[CONTENT_MODE]) }
    val baseUrl: Flow<String> = baseUrl(ContentMode.FC2)
    val visitedPostIds: Flow<Set<String>> = dataStore.data.map { it[VISITED_POST_IDS].orEmpty() }
    val fullscreenGestureSensitivity: Flow<String> =
        dataStore.data.map { it[FULLSCREEN_GESTURE_SENSITIVITY] ?: DEFAULT_FULLSCREEN_GESTURE_SENSITIVITY }

    fun anchorEpochMillis(mode: ContentMode): Flow<Long?> =
        dataStore.data.map { it[anchorKey(mode)] }

    fun baseUrl(mode: ContentMode): Flow<String> =
        dataStore.data.map { it[baseUrlKey(mode)] ?: mode.defaultBaseUrl }

    suspend fun ensureAnchor(mode: ContentMode = ContentMode.FC2): Long {
        val key = anchorKey(mode)
        var chosen = 0L
        dataStore.edit { prefs -> chosen = prefs[key] ?: clockMillis().also { prefs[key] = it } }
        return chosen
    }

    suspend fun refreshAnchor(mode: ContentMode = ContentMode.FC2): Long {
        val now = clockMillis()
        setAnchor(now, mode)
        return now
    }

    suspend fun setAnchor(anchorEpochMillis: Long, mode: ContentMode = ContentMode.FC2) {
        require(anchorEpochMillis > 0L)
        dataStore.edit { it[anchorKey(mode)] = anchorEpochMillis }
    }

    suspend fun setAnchorIf(
        anchorEpochMillis: Long,
        mode: ContentMode = ContentMode.FC2,
        guardedWrite: (() -> Unit) -> Boolean,
    ): Boolean {
        require(anchorEpochMillis > 0L)
        var committed = false
        dataStore.edit { preferences ->
            committed = guardedWrite {
                preferences[anchorKey(mode)] = anchorEpochMillis
            }
        }
        return committed
    }

    suspend fun setSelectedContentMode(mode: ContentMode) {
        dataStore.edit { it[CONTENT_MODE] = mode.sourceKey }
    }

    suspend fun setBaseUrl(normalizedBaseUrl: String, mode: ContentMode = ContentMode.FC2) {
        dataStore.edit { it[baseUrlKey(mode)] = normalizedBaseUrl }
    }

    suspend fun setFullscreenGestureSensitivity(value: String) {
        dataStore.edit { it[FULLSCREEN_GESTURE_SENSITIVITY] = value }
    }

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

    private fun anchorKey(mode: ContentMode): Preferences.Key<Long> =
        if (mode == ContentMode.FC2) ANCHOR else JAV_ANCHOR

    private fun baseUrlKey(mode: ContentMode): Preferences.Key<String> =
        if (mode == ContentMode.FC2) BASE_URL else JAV_BASE_URL

    companion object {
        const val DEFAULT_BASE_URL = "https://01.avsee.is"
        const val DEFAULT_FULLSCREEN_GESTURE_SENSITIVITY = "NORMAL"
        private val ANCHOR = longPreferencesKey("anchor_epoch_millis")
        private val JAV_ANCHOR = longPreferencesKey("jav_anchor_epoch_millis")
        private val CONTENT_MODE = stringPreferencesKey("content_mode")
        private val BASE_URL = stringPreferencesKey("base_url")
        private val JAV_BASE_URL = stringPreferencesKey("jav_base_url")
        private val VISITED_POST_IDS = stringSetPreferencesKey("visited_post_ids")
        private val RANKING_COVERED_WINDOWS = stringSetPreferencesKey("ranking_covered_windows_v1")
        private val FULLSCREEN_GESTURE_SENSITIVITY = stringPreferencesKey("fullscreen_gesture_sensitivity")
    }
}

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.podoc.app.data.local

import android.content.Context
import com.podoc.app.service.MediaPlaybackManager
import com.podoc.app.service.VideoCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "podoc_preferences")

class DataStoreManager(private val context: Context) {

    companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000"
        val SERVER_URL = stringPreferencesKey("server_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val USERNAME = stringPreferencesKey("username")
        val THEME_MODE = stringPreferencesKey("theme_mode") // "system", "light", "dark"
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val DEFAULT_FONT_SIZE = intPreferencesKey("default_font_size")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")

        // Media Auto Sync Preferences
        val MEDIA_AUTO_SYNC_ENABLED = booleanPreferencesKey("media_auto_sync_enabled")
        val MEDIA_SYNC_LAST_TIME = longPreferencesKey("media_sync_last_time")
        val MEDIA_SYNC_LAST_SUMMARY = stringPreferencesKey("media_sync_last_summary")
        val MEDIA_SYNC_LAST_STATUS = stringPreferencesKey("media_sync_last_status")
        val MEDIA_SYNC_SYNCED_KEYS = stringSetPreferencesKey("media_sync_synced_keys")
    }

    val mediaAutoSyncEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[MEDIA_AUTO_SYNC_ENABLED] ?: true
    }

    val mediaSyncLastTimeFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[MEDIA_SYNC_LAST_TIME] ?: 0L
    }

    val mediaSyncLastSummaryFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[MEDIA_SYNC_LAST_SUMMARY] ?: ""
    }

    val mediaSyncLastStatusFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[MEDIA_SYNC_LAST_STATUS] ?: ""
    }

    val mediaSyncSyncedKeysFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[MEDIA_SYNC_SYNCED_KEYS] ?: emptySet()
    }

    val geminiApiKeyFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[GEMINI_API_KEY] ?: ""
    }

    val serverUrlFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL
    }

    val authTokenFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[AUTH_TOKEN] ?: ""
    }

    val usernameFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[USERNAME] ?: "admin"
    }

    val themeModeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_MODE] ?: "system"
    }

    val dynamicColorFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DYNAMIC_COLOR] ?: true
    }

    val accentColorFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ACCENT_COLOR] ?: "#4F46E5"
    }

    val defaultFontSizeFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_FONT_SIZE] ?: 16
    }

    suspend fun saveGeminiApiKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[GEMINI_API_KEY] = key.trim()
        }
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            var cleanUrl = url.trim().trimEnd('/')
            if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
                cleanUrl = if (cleanUrl.contains("localhost") || cleanUrl.matches(Regex("^[0-9.:]+.*"))) {
                    "http://$cleanUrl"
                } else {
                    "https://$cleanUrl"
                }
            }
            preferences[SERVER_URL] = cleanUrl
        }
    }

    suspend fun saveAuthData(serverUrl: String, token: String, username: String) {
        context.dataStore.edit { preferences ->
            if (serverUrl.isNotBlank()) {
                preferences[SERVER_URL] = serverUrl.trimEnd('/')
            }
            preferences[AUTH_TOKEN] = token
            preferences[USERNAME] = username
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAccentColor(colorHex: String) {
        context.dataStore.edit { it[ACCENT_COLOR] = colorHex }
    }

    suspend fun setDefaultFontSize(size: Int) {
        context.dataStore.edit { it[DEFAULT_FONT_SIZE] = size }
    }

    suspend fun setMediaAutoSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[MEDIA_AUTO_SYNC_ENABLED] = enabled }
    }

    suspend fun recordMediaSyncResult(
        timestamp: Long,
        summary: String,
        status: String,
        newSyncedKeys: Set<String> = emptySet()
    ) {
        context.dataStore.edit { preferences ->
            preferences[MEDIA_SYNC_LAST_TIME] = timestamp
            preferences[MEDIA_SYNC_LAST_SUMMARY] = summary
            preferences[MEDIA_SYNC_LAST_STATUS] = status
            if (newSyncedKeys.isNotEmpty()) {
                val current = preferences[MEDIA_SYNC_SYNCED_KEYS] ?: emptySet()
                preferences[MEDIA_SYNC_SYNCED_KEYS] = current + newSyncedKeys
            }
        }
    }

    suspend fun addSyncedMediaKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        context.dataStore.edit { preferences ->
            val current = preferences[MEDIA_SYNC_SYNCED_KEYS] ?: emptySet()
            preferences[MEDIA_SYNC_SYNCED_KEYS] = current + keys
        }
    }

    suspend fun clearAuth() {
        context.dataStore.edit { preferences ->
            preferences.remove(AUTH_TOKEN)
            preferences.remove(USERNAME)
        }
        withContext(Dispatchers.Main.immediate) {
            MediaPlaybackManager.serviceInstance?.stopForLogout()
            MediaPlaybackManager.videoFeedPreloader?.close()
            MediaPlaybackManager.videoFeedPreloader = null
        }
        VideoCacheStore.clear(context)
    }
}

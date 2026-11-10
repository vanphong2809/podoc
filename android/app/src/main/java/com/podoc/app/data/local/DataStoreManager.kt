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
import androidx.datastore.preferences.core.stringPreferencesKey
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

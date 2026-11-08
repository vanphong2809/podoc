package com.podoc.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.model.ServerSystemStats
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
    val accentColor: String = "#4F46E5",
    val defaultFontSize: Int = 16,
    val isExporting: Boolean = false,
    val exportSuccessMessage: String? = null,
    val errorMessage: String? = null,
    val serverStats: ServerSystemStats? = null,
    val isLoadingServerStats: Boolean = false,
    val serverStatsError: String? = null
)

class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val articleRepository: ArticleRepository,
    private val dataStoreManager: DataStoreManager
) : ViewModel() {

    private val _serverStats = MutableStateFlow<ServerSystemStats?>(null)
    private val _isLoadingServerStats = MutableStateFlow(false)
    private val _serverStatsError = MutableStateFlow<String?>(null)

    private data class BaseSettings(
        val server: String,
        val user: String,
        val theme: String,
        val dynamic: Boolean,
        val accent: String,
        val fontSize: Int
    )

    private val baseSettingsFlow = combine(
        authRepository.serverUrl,
        authRepository.username,
        dataStoreManager.themeModeFlow,
        dataStoreManager.dynamicColorFlow,
        combine(dataStoreManager.accentColorFlow, dataStoreManager.defaultFontSizeFlow) { a, f -> a to f }
    ) { server, user, theme, dynamic, (accent, fontSize) ->
        BaseSettings(server, user, theme, dynamic, accent, fontSize)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        baseSettingsFlow,
        _serverStats,
        _isLoadingServerStats,
        _serverStatsError
    ) { base, stats, isLoading, error ->
        SettingsUiState(
            serverUrl = base.server,
            username = base.user,
            themeMode = base.theme,
            dynamicColor = base.dynamic,
            accentColor = base.accent,
            defaultFontSize = base.fontSize,
            serverStats = stats,
            isLoadingServerStats = isLoading,
            serverStatsError = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    init {
        loadServerStats()
    }

    fun loadServerStats() {
        viewModelScope.launch {
            _isLoadingServerStats.value = true
            _serverStatsError.value = null
            articleRepository.getServerSystemStats().fold(
                onSuccess = { stats ->
                    _serverStats.value = stats
                    _isLoadingServerStats.value = false
                },
                onFailure = { err ->
                    _serverStatsError.value = err.message ?: "Không thể kết nối máy chủ"
                    _isLoadingServerStats.value = false
                }
            )
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { dataStoreManager.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { dataStoreManager.setDynamicColor(enabled) }
    }

    fun setAccentColor(colorHex: String) {
        viewModelScope.launch { dataStoreManager.setAccentColor(colorHex) }
    }

    fun setDefaultFontSize(size: Int) {
        viewModelScope.launch { dataStoreManager.setDefaultFontSize(size) }
    }

    fun exportDataToUri(context: Context, destinationUri: Uri) {
        viewModelScope.launch {
            articleRepository.exportData().fold(
                onSuccess = { responseBody ->
                    try {
                        context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                            responseBody.byteStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        // Handle write error
                    }
                },
                onFailure = {
                    // Handle failure
                }
            )
        }
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch {
            dataStoreManager.setServerUrl(url)
            loadServerStats()
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}

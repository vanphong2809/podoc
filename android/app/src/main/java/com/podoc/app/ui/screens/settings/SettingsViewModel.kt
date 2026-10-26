package com.podoc.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.local.DataStoreManager
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
    val errorMessage: String? = null
)

class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val articleRepository: ArticleRepository,
    private val dataStoreManager: DataStoreManager
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.serverUrl,
        authRepository.username,
        dataStoreManager.themeModeFlow,
        dataStoreManager.dynamicColorFlow,
        combine(dataStoreManager.accentColorFlow, dataStoreManager.defaultFontSizeFlow) { a, f -> a to f }
    ) { server, user, theme, dynamic, (accent, fontSize) ->
        SettingsUiState(
            serverUrl = server,
            username = user,
            themeMode = theme,
            dynamicColor = dynamic,
            accentColor = accent,
            defaultFontSize = fontSize
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

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

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}

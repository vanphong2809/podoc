package com.podoc.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val serverUrl: String = "http://localhost:8000",
    val username: String = "admin",
    val password: String = "podoc_admin_password_change_me",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
)

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onServerUrlChange(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url, errorMessage = null)
    }

    fun onUsernameChange(name: String) {
        _uiState.value = _uiState.value.copy(username = name, errorMessage = null)
    }

    fun onPasswordChange(pass: String) {
        _uiState.value = _uiState.value.copy(password = pass, errorMessage = null)
    }

    fun fillPreset(serverUrl: String) {
        _uiState.value = _uiState.value.copy(
            serverUrl = serverUrl,
            username = "admin",
            password = "podoc_admin_password_change_me",
            errorMessage = null
        )
    }

    fun login() {
        val state = _uiState.value
        var cleanUrl = state.serverUrl.trim().trimEnd('/')
        val cleanUser = state.username.trim()
        val pass = state.password

        if (cleanUrl.isBlank() || cleanUser.isBlank() || pass.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Vui lòng nhập đầy đủ thông tin máy chủ, tài khoản và mật khẩu")
            return
        }

        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            cleanUrl = if (cleanUrl.contains("localhost") || cleanUrl.matches(Regex("^[0-9.:]+.*"))) {
                "http://$cleanUrl"
            } else {
                "https://$cleanUrl"
            }
        }

        _uiState.value = state.copy(serverUrl = cleanUrl, isLoading = true, errorMessage = null)

        viewModelScope.launch {
            val result = authRepository.login(cleanUrl, cleanUser, pass)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                },
                onFailure = { error ->
                    val errorMsg = error.message ?: ""
                    val message = when {
                        errorMsg.contains("401") -> "Tên đăng nhập hoặc mật khẩu không chính xác"
                        errorMsg.contains("Failed to connect") || errorMsg.contains("failed to connect") || errorMsg.contains("Connection refused") ->
                            "Không thể kết nối tới $cleanUrl. Hãy chắc chắn backend đang chạy hoặc kiểm tra cáp USB"
                        else -> "Đăng nhập thất bại: ${error.localizedMessage ?: errorMsg}"
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = message)
                }
            )
        }
    }
}

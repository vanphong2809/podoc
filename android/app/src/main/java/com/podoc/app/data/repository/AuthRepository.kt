package com.podoc.app.data.repository

import com.podoc.app.data.api.PoDocApiService
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.model.LoginRequest
import com.podoc.app.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class AuthRepository(
    private val apiServiceProvider: () -> PoDocApiService?,
    private val dataStoreManager: DataStoreManager
) {
    val authToken: Flow<String> = dataStoreManager.authTokenFlow
    val serverUrl: Flow<String> = dataStoreManager.serverUrlFlow
    val username: Flow<String> = dataStoreManager.usernameFlow

    suspend fun isLoggedIn(): Boolean {
        val token = dataStoreManager.authTokenFlow.first()
        return token.isNotBlank()
    }

    suspend fun login(serverUrl: String, username: String, password: String): Result<User> {
        return try {
            var cleanUrl = serverUrl.trim().trimEnd('/')
            if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
                cleanUrl = if (cleanUrl.contains("localhost") || cleanUrl.matches(Regex("^[0-9.:]+.*"))) {
                    "http://$cleanUrl"
                } else {
                    "https://$cleanUrl"
                }
            }

            // Lưu tạm server URL để Retrofit tạo base URL
            dataStoreManager.saveAuthData(cleanUrl, "", "")

            val api = apiServiceProvider() ?: return Result.failure(IllegalStateException("Chưa khởi tạo API"))
            val tokenResponse = api.login(LoginRequest(username, password))

            // Lưu token thành công
            dataStoreManager.saveAuthData(cleanUrl, tokenResponse.accessToken, username)

            // Lấy thông tin tài khoản
            val user = api.getMe()
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        dataStoreManager.clearAuth()
    }
}

package com.podoc.app.di

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.podoc.app.data.api.AuthInterceptor
import com.podoc.app.data.api.PoDocApiService
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.AuthRepository
import com.podoc.app.data.repository.CategoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class AppContainer(private val context: Context) {

    val dataStoreManager = DataStoreManager(context)

    private val _tokenExpiredEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tokenExpiredEvent: SharedFlow<Unit> = _tokenExpiredEvent.asSharedFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private var currentApi: PoDocApiService? = null
    private var currentServerUrl: String = ""

    private val authInterceptor = AuthInterceptor(
        tokenProvider = {
            runBlocking { dataStoreManager.authTokenFlow.first().takeIf { it.isNotBlank() } }
        },
        onTokenExpired = {
            CoroutineScope(Dispatchers.IO).launch {
                dataStoreManager.clearAuth()
                _tokenExpiredEvent.emit(Unit)
            }
        }
    )

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getApiService(): PoDocApiService? {
        val serverUrl = runBlocking { dataStoreManager.serverUrlFlow.first() }
        if (serverUrl.isBlank()) return null

        val formattedUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"

        if (currentApi != null && currentServerUrl == formattedUrl) {
            return currentApi
        }

        return try {
            val retrofit = Retrofit.Builder()
                .baseUrl(formattedUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()

            currentServerUrl = formattedUrl
            currentApi = retrofit.create(PoDocApiService::class.java)
            currentApi
        } catch (e: Exception) {
            null
        }
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            apiServiceProvider = { getApiService() },
            dataStoreManager = dataStoreManager
        )
    }

    val categoryRepository: CategoryRepository by lazy {
        CategoryRepository(
            apiServiceProvider = { getApiService() }
        )
    }

    val articleRepository: ArticleRepository by lazy {
        ArticleRepository(
            apiServiceProvider = { getApiService() },
            context = context,
            serverUrlProvider = { currentServerUrl }
        )
    }
}

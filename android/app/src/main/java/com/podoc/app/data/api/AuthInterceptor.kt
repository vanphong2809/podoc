package com.podoc.app.data.api

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val onTokenExpired: () -> Unit
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenProvider()

        val requestBuilder = originalRequest.newBuilder()
        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }
        val path = originalRequest.url.encodedPath
        val isMediaRequest = path.contains("/thumbnail") || path.contains("/file") ||
                path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".mp4")
        if (originalRequest.header("Accept") == null && !isMediaRequest) {
            requestBuilder.header("Accept", "application/json")
        }

        return chain.proceed(requestBuilder.build())
    }
}

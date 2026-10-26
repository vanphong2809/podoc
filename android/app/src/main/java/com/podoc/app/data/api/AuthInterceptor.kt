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
            .header("Accept", "application/json")

        return chain.proceed(requestBuilder.build())
    }
}

package com.podoc.app.service

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One disk cache for both preloading and playback, surviving reader/service restarts. */
@UnstableApi
internal object VideoCacheStore {
    val preloadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mutex = Mutex()
    @Volatile private var instance: SimpleCache? = null
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()

    suspend fun get(context: Context): SimpleCache = mutex.withLock {
        instance ?: withContext(Dispatchers.IO) {
            val directory = File(context.applicationContext.cacheDir, "video_stream_cache")
            val free = context.cacheDir.usableSpace
            if (free < 64L * 1024 * 1024) throw IOException("Không đủ bộ nhớ trống để chuẩn bị video")
            val budget = minOf(512L * 1024 * 1024, maxOf(16L * 1024 * 1024, (free - 64L * 1024 * 1024) / 4))
            SimpleCache(directory, LeastRecentlyUsedCacheEvictor(budget), StandaloneDatabaseProvider(context.applicationContext))
                .also { instance = it }
        }
    }

    fun httpFactory(token: String): OkHttpDataSource.Factory = OkHttpDataSource.Factory(http)
        .setDefaultRequestProperties(if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token"))

    fun dataSource(cache: SimpleCache, upstream: OkHttpDataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(512L * 1024))
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun namespace(server: String, username: String): String = MessageDigest.getInstance("SHA-256")
        .digest("${server.trimEnd('/')}\n$username".toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun clear(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cache = instance
            if (cache != null) cache.keys.toList().forEach { cache.removeResource(it) }
            else SimpleCache.delete(File(context.applicationContext.cacheDir, "video_stream_cache"), StandaloneDatabaseProvider(context))
        }
    }
}

package com.podoc.app.service

import android.content.Context
import android.os.SystemClock
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.PreCacheHelper
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.podoc.app.data.model.VideoPlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.abs

internal data class VideoPreparation(val ready: Int = 0, val total: Int = 0, val error: String? = null)

/** Main-thread controller. Only compressed bytes go into the shared disk cache. */
@UnstableApi
internal class VideoFeedPreloader(
    private val context: Context,
    private val server: String,
    username: String,
    token: String,
    private val cache: SimpleCache,
    private val onProgress: (VideoPreparation) -> Unit
) {
    private data class Entry(val source: VideoPlaybackSource, val item: MediaItem, val index: Int, val key: String)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = VideoCacheStore.preloadExecutor
    private val namespace = VideoCacheStore.namespace(server, username)
    private val bandwidth = DefaultBandwidthMeter.getSingletonInstance(context)
    private val upstream = VideoCacheStore.httpFactory(token).setTransferListener(bandwidth)
    private val dataSource = VideoCacheStore.dataSource(cache, upstream)
    private val entries = linkedMapOf<Int, Entry>()
    private val completed = mutableMapOf<String, Long>()
    private val errors = mutableMapOf<String, String>()
    private val startup = MutableStateFlow(VideoPreparation())
    private var center = 0
    private var initial = true
    private var rapid = false
    private var movedAt = 0L
    private var pausedForPlayback = false
    private var closed = false
    val isClosed: Boolean get() = closed
    private var backgroundJob: Job? = null
    private var preferredIndex = 0
    private data class Caching(val ticket: Any, val entry: Entry, val targetMs: Long, val helper: PreCacheHelper)
    private var caching: Caching? = null
    @Volatile private var backgroundWriter: CacheWriter? = null
    private val sourceFactory = DefaultMediaSourceFactory(dataSource)
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
    private val cacheListener = object : Cache.Listener {
        override fun onSpanAdded(cache: Cache, span: CacheSpan) = Unit
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            scope.launch {
                completed.remove(span.key)
                if (!closed) { publishProgress(); schedule(); MediaPlaybackManager.serviceInstance?.warmNextVideo() }
            }
        }
    }

    fun updateAuth(token: String) {
        upstream.setDefaultRequestProperties(if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token"))
    }

    fun beginStartup() {
        initial = true
        pausedForPlayback = false
    }

    fun updateWindow(sources: List<Pair<Int, VideoPlaybackSource>>, selectedIndex: Int, force: Boolean = false) {
        check(!closed)
        cancelBackground()
        val now = SystemClock.elapsedRealtime()
        if (selectedIndex != center) {
            rapid = movedAt != 0L && now - movedAt < 750L * maxOf(1, abs(selectedIndex - center))
            movedAt = now
        }
        center = selectedIndex
        preferredIndex = selectedIndex
        val desired = sources.map { it.second.id }.toSet()
        entries.keys.toList().filter { it !in desired }.forEach(::remove)
        for ((index, source) in sources) {
            val key = "$namespace/${source.id}/${source.variant}/${source.revision}"
            val old = entries[source.id]
            if (force) { errors.remove(key); completed.remove(key) }
            if (old?.key == key && old.index == index) continue
            if (old != null) remove(source.id)
            val uri = if (source.url.startsWith("http")) source.url else server.trimEnd('/') + source.url
            val item = MediaItem.Builder().setMediaId(source.id.toString()).setUri(uri)
                .setMimeType(source.mimeType).setCustomCacheKey(key).build()
            entries[source.id] = Entry(source, item, index, key)
            cache.addListener(key, cacheListener)
        }
        publishProgress()
        schedule()
        MediaPlaybackManager.serviceInstance?.warmNextVideo()
    }

    fun predict(index: Int) {
        if (!closed) {
            preferredIndex = index
            schedule()
            MediaPlaybackManager.serviceInstance?.warmNextVideo()
        }
    }

    suspend fun awaitStartup() {
        val result = try {
            withTimeout(60_000) { startup.first { it.error != null || (it.total > 0 && it.ready == it.total) } }
        } catch (_: TimeoutCancellationException) {
            throw java.io.IOException("Chuẩn bị video quá lâu. Kiểm tra kết nối và chạm để thử lại.")
        }
        result.error?.let { throw IllegalStateException(it) }
        initial = false
        schedule()
        MediaPlaybackManager.serviceInstance?.warmNextVideo()
    }

    private fun schedule() {
        if (closed) return
        val active = caching
        if (active != null && (entries[active.entry.source.id] !== active.entry ||
            targetMs(active.entry.index) != active.targetMs || (!initial && pausedForPlayback))) {
            caching = null
            active.helper.release(false)
        }
        if (caching != null || (!initial && pausedForPlayback)) return
        val next = entries.values.filter { targetMs(it.index) > (completed[it.key] ?: 0L) && it.key !in errors }
            .minByOrNull { abs(it.index - preferredIndex) } ?: return
        cancelBackground()
        val target = targetMs(next.index)
        val ticket = Any()
        val helper = PreCacheHelper.Factory(cache, upstream, DefaultRenderersFactory(context), Looper.getMainLooper())
            .setDownloadExecutor(executor)
            .setListener(object : PreCacheHelper.Listener {
                override fun onPreCacheProgress(item: MediaItem, length: Long, downloaded: Long, percent: Float) {
                    if (caching?.ticket !== ticket) return
                    if (percent >= 100f || (length != C.LENGTH_UNSET.toLong() && length >= 0 && downloaded >= length)) {
                        completed[next.key] = maxOf(completed[next.key] ?: 0L, target)
                        caching?.helper?.release(false)
                        caching = null
                        publishProgress()
                        scope.launch { yield(); schedule(); MediaPlaybackManager.serviceInstance?.warmNextVideo() }
                    }
                }
                override fun onPrepareError(item: MediaItem, error: java.io.IOException) = failed()
                override fun onDownloadError(item: MediaItem, error: java.io.IOException) = failed()
                private fun failed() {
                    if (caching?.ticket !== ticket) return
                    errors[next.key] = "Không thể chuẩn bị video ${next.source.id}. Chạm để thử lại."
                    caching?.helper?.release(false)
                    caching = null
                    publishProgress()
                    schedule()
                }
            }).create(next.item)
        caching = Caching(ticket, next, target, helper)
        helper.preCache(0, target)
    }

    private fun targetMs(index: Int): Long = VideoPreloadPolicy.targetMs(
        index - center, initial, rapid, bandwidth.bitrateEstimate in 1..1_500_000
    )

    private fun publishProgress() {
        if (!initial) return
        val error = entries.values.firstNotNullOfOrNull { errors[it.key] }
        val ready = entries.values.count {
            (completed[it.key] ?: 0L) >= VideoPreloadPolicy.targetMs(it.index - center, true, false, false)
        }
        startup.value = VideoPreparation(ready, entries.size, error)
        onProgress(startup.value)
    }

    fun mediaSource(articleId: Int): MediaSource? = entries[articleId]?.let { sourceFactory.createMediaSource(it.item) }
    fun url(articleId: Int): String? = entries[articleId]?.item?.localConfiguration?.uri?.toString()
    fun cachedDurationMs(articleId: Int): Long = entries[articleId]?.let { completed[it.key] } ?: 0L
    fun cachedBytes(articleId: Int): Long = entries[articleId]?.let { cache.getCachedBytes(it.key, 0, it.source.fileSizeBytes) } ?: 0L
    fun buildPlayer(loadControl: LoadControl): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(sourceFactory).setBandwidthMeter(bandwidth).setLoadControl(loadControl).build()
    fun selectedId(): Int? = entries.values.firstOrNull { it.index == center }?.source?.id
    fun nextId(): Int? = entries.values.firstOrNull { it.index == preferredIndex && it.index != center }?.source?.id
        ?: entries.values.firstOrNull { it.index == center + 1 }?.source?.id

    /** Foreground buffering wins bandwidth; whole-file fill only uses otherwise idle time. */
    fun onPlaybackBuffer(bufferedMs: Long, buffering: Boolean, remainingMs: Long) {
        if (closed || initial) return
        val fullyBuffered = remainingMs >= 0 && bufferedMs >= remainingMs - 250
        val starved = !fullyBuffered && (buffering || bufferedMs < 3_000)
        val shouldPause = starved || (!fullyBuffered && pausedForPlayback && bufferedMs < 8_000)
        if (pausedForPlayback != shouldPause) {
            pausedForPlayback = shouldPause
            schedule()
            MediaPlaybackManager.serviceInstance?.warmNextVideo()
        }
        if (starved) { cancelBackground(); return }
        if (rapid && SystemClock.elapsedRealtime() - movedAt >= 2_000) rapid = false
        schedule()
        val entry = entries.values.firstOrNull { it.index == center } ?: return
        val warm = entries.values.all { (completed[it.key] ?: 0L) >= targetMs(it.index) }
        if (!warm || (!fullyBuffered && bufferedMs < 10_000) || backgroundJob?.isActive == true ||
            entry.source.fileSizeBytes > 64L * 1024 * 1024 ||
            rapid || SystemClock.elapsedRealtime() - movedAt < 2_000) return
        if (cache.getCachedBytes(entry.key, 0, entry.source.fileSizeBytes) >= entry.source.fileSizeBytes) return
        backgroundJob = scope.launch(Dispatchers.IO) {
            try {
                var offset = 0L
                while (offset < entry.source.fileSizeBytes && isActive) {
                    val size = minOf(512L * 1024, entry.source.fileSizeBytes - offset)
                    val spec = DataSpec.Builder().setUri(entry.item.localConfiguration!!.uri)
                        .setKey(entry.key).setPosition(offset).setLength(size).build()
                    val writer = CacheWriter(dataSource.createDataSourceForDownloading(), spec, ByteArray(64 * 1024), null)
                    backgroundWriter = writer
                    if (!isActive) { writer.cancel(); break }
                    writer.cache()
                    offset += size
                }
            } catch (_: Exception) {
                // Cached spans survive cancellation and can be resumed later.
            } finally { backgroundWriter = null }
        }
    }

    private fun cancelBackground() { backgroundWriter?.cancel(); backgroundJob?.cancel(); backgroundJob = null }
    private fun remove(id: Int) {
        entries.remove(id)?.let {
            if (caching?.entry === it) { caching?.helper?.release(false); caching = null }
            cache.removeListener(it.key, cacheListener)
            // Re-entering the window verifies the bytes again. Disk spans remain;
            // an old readiness marker must not survive an intervening LRU eviction.
            completed.remove(it.key)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        MediaPlaybackManager.serviceInstance?.releaseWarmForOwner(this)
        cancelBackground()
        caching?.helper?.release(false)
        caching = null
        entries.keys.toList().forEach(::remove)
        scope.cancel()
    }
}

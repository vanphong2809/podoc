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
    private var initial = false
    private var previewIndex: Int? = null
    private val retryAttempts = mutableMapOf<String, Int>()
    private val retryPending = mutableSetOf<String>()
    private var rapid = false
    private var movedAt = 0L
    private var bufferBudget = VideoPreloadPolicy.Budget.FULL
    private var direction = 1
    private var viewIntervalMs = 1_000L
    private var totalCount = 0
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
        bufferBudget = VideoPreloadPolicy.Budget.FULL
    }

    fun updateWindow(sources: List<Pair<Int, VideoPlaybackSource>>, selectedIndex: Int, total: Int, force: Boolean = false) {
        check(!closed)
        val moved = selectedIndex != center
        val now = SystemClock.elapsedRealtime()
        if (moved) {
            cancelBackground()
            if (!initial) bufferBudget = VideoPreloadPolicy.Budget.OPENINGS
            val gap = abs(selectedIndex - center)
            direction = if (selectedIndex > center) 1 else -1
            if (movedAt != 0L) {
                val interval = ((now - movedAt) / gap).coerceIn(250, 10_000)
                viewIntervalMs = (viewIntervalMs * 3 + interval) / 4
                rapid = interval < 750
            }
            movedAt = now
            preferredIndex = selectedIndex
            previewIndex = null
        }
        center = selectedIndex
        totalCount = total
        val previousIds = entries.keys.toSet()
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
        if (moved || previousIds != entries.keys) android.util.Log.i("VideoFeedPreloader",
            "VIDEO WINDOW | index=${center + 1}/$totalCount | id=${selectedId()} | before=${entries.values.count { it.index < center }} | after=${entries.values.count { it.index > center }} | direction=$direction")
        publishProgress()
        schedule()
        MediaPlaybackManager.serviceInstance?.warmNextVideo()
    }

    fun predict(index: Int, scrolling: Boolean) {
        if (!closed) {
            preferredIndex = index
            previewIndex = index.takeIf { scrolling && it != center }
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

    private data class Task(val entry: Entry, val targetMs: Long, val tier: Int, val dueMs: Long)
    private fun task(entry: Entry): Task? {
        if (entry.key in errors || entry.key in retryPending) return null
        val complete = completed[entry.key] ?: 0L
        val target = VideoPreloadPolicy.taskTargetMs(entry.index - center, complete, initial, rapid,
            bandwidth.bitrateEstimate in 1..1_500_000)
        if (target == 0L || (!initial && bufferBudget == VideoPreloadPolicy.Budget.PAUSED) ||
            (!initial && bufferBudget == VideoPreloadPolicy.Budget.OPENINGS && target > 1_000)) return null
        val tier = if (initial && entry.index == center) -1 else if (complete < 1_000) 0 else 1
        val predicted = previewIndex?.let { if (it > center) 1 else -1 } ?: direction
        val signedGap = (entry.index - center) * predicted
        val steps = if (entry.index == preferredIndex && entry.index != center) 0
            else if (signedGap >= 0) signedGap else VideoPreloadPolicy.RADIUS - signedGap
        return Task(entry, target, tier, steps * viewIntervalMs)
    }
    private val taskOrder = compareBy<Task> { it.tier }.thenBy { it.dueMs }

    private fun schedule() {
        if (closed) return
        val best = entries.values.mapNotNull(::task).minWithOrNull(taskOrder)
        val active = caching
        if (active != null) {
            val current = entries[active.entry.source.id]?.takeIf { it === active.entry }?.let(::task)
            val preempt = current == null || (best != null && best.entry !== active.entry && taskOrder.compare(best, current) < 0)
            if (preempt) {
                caching = null
                active.helper.release(false)
            } else return // A changed distance alone does not discard an in-flight range.
        }
        val nextTask = best ?: return
        cancelBackground()
        val next = nextTask.entry
        val target = nextTask.targetMs
        val ticket = Any()
        val helper = PreCacheHelper.Factory(cache, upstream, DefaultRenderersFactory(context), Looper.getMainLooper())
            .setDownloadExecutor(executor)
            .setListener(object : PreCacheHelper.Listener {
                override fun onPreCacheProgress(item: MediaItem, length: Long, downloaded: Long, percent: Float) {
                    if (caching?.ticket !== ticket) return
                    if (percent >= 100f || (length != C.LENGTH_UNSET.toLong() && length >= 0 && downloaded >= length)) {
                        completed[next.key] = maxOf(completed[next.key] ?: 0L, target)
                        retryAttempts.remove(next.key)
                        android.util.Log.i("VideoFeedPreloader", "VIDEO PREFETCH DONE | index=${next.index + 1} | id=${next.source.id} | opening_ms=$target | center=${center + 1}")
                        caching?.helper?.release(false)
                        caching = null
                        publishProgress()
                        scope.launch { yield(); schedule(); MediaPlaybackManager.serviceInstance?.warmNextVideo() }
                    }
                }
                override fun onPrepareError(item: MediaItem, error: java.io.IOException) = failed(error)
                override fun onDownloadError(item: MediaItem, error: java.io.IOException) = failed(error)
                private fun failed(error: java.io.IOException) {
                    if (caching?.ticket !== ticket) return
                    caching?.helper?.release(false)
                    caching = null
                    val attempt = (retryAttempts[next.key] ?: 0) + 1
                    retryAttempts[next.key] = attempt
                    if (attempt <= 2) {
                        retryPending.add(next.key)
                        android.util.Log.w("VideoFeedPreloader", "VIDEO PREFETCH RETRY | id=${next.source.id} | attempt=$attempt | cause=${error.javaClass.simpleName}")
                        scope.launch {
                            delay(250L * attempt)
                            retryPending.remove(next.key)
                            if (!closed && entries[next.source.id] === next) schedule()
                        }
                    } else errors[next.key] = "Không thể chuẩn bị video ${next.source.id}. Chạm để thử lại."
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
    fun buildPlayer(loadControl: LoadControl): ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setMediaSourceFactory(sourceFactory).setBandwidthMeter(bandwidth).setLoadControl(loadControl).build()
    fun shouldPlayPreview(id: Int): Boolean = previewIndex != null && entries[id]?.index == previewIndex
    internal fun currentIndex(): Int = center
    internal fun windowIds(): Set<Int> = entries.keys.toSet()
    internal fun preloadBudget(): VideoPreloadPolicy.Budget = bufferBudget
    fun selectedId(): Int? = entries.values.firstOrNull { it.index == center }?.source?.id
    fun nextId(): Int? = entries.values.firstOrNull { it.index == preferredIndex && it.index != center }?.source?.id
        ?: entries.values.firstOrNull { it.index == center + direction }?.source?.id
        ?: entries.values.firstOrNull { it.index == center - direction }?.source?.id

    /** Foreground buffering wins bandwidth; whole-file fill only uses otherwise idle time. */
    fun onPlaybackBuffer(articleId: Int?, bufferedMs: Long, buffering: Boolean, remainingMs: Long, loading: Boolean) {
        if (closed || initial || articleId != selectedId()) return
        val fullyBuffered = !buffering && remainingMs >= 0 && bufferedMs >= remainingMs - 250
        val previous = bufferBudget
        bufferBudget = VideoPreloadPolicy.playbackBudget(bufferedMs, buffering, remainingMs, loading, previous)
        if (previous != bufferBudget) {
            schedule()
            MediaPlaybackManager.serviceInstance?.warmNextVideo()
        }
        if (bufferBudget != VideoPreloadPolicy.Budget.FULL) cancelBackground()
        if (bufferBudget == VideoPreloadPolicy.Budget.PAUSED) return
        if (rapid && SystemClock.elapsedRealtime() - movedAt >= 2_000) rapid = false
        schedule()
        val entry = entries.values.firstOrNull { it.index == center } ?: return
        val warm = entries.values.all { (completed[it.key] ?: 0L) >= targetMs(it.index) }
        if (bufferBudget != VideoPreloadPolicy.Budget.FULL || !warm || (!fullyBuffered && bufferedMs < 10_000) || backgroundJob?.isActive == true ||
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
            retryAttempts.remove(it.key)
            retryPending.remove(it.key)
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

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.podoc.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.podoc.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trình quản lý trạng thái phát media toàn cục (Singleton)
 * Cho phép UI (InAppAudioViewer, InAppVideoViewer) theo dõi trạng thái tiến trình,
 * thời lượng, tên bài, và gắn/gỡ SurfaceView mượt mà khi tắt/bật màn hình.
 */
object MediaPlaybackManager {
    private const val TAG = "MediaPlaybackManager"

    internal val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    internal val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    internal val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()

    internal val _currentArticleId = MutableStateFlow<Int?>(null)
    val currentArticleId: StateFlow<Int?> = _currentArticleId.asStateFlow()

    internal val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    internal val _currentFilePath = MutableStateFlow<String?>(null)
    val currentFilePath: StateFlow<String?> = _currentFilePath.asStateFlow()

    internal val _isVideoMode = MutableStateFlow(false)
    val isVideoMode: StateFlow<Boolean> = _isVideoMode.asStateFlow()

    internal val _isRepeatMode = MutableStateFlow(false)
    val isRepeatMode: StateFlow<Boolean> = _isRepeatMode.asStateFlow()

    internal val _isCompleted = MutableStateFlow(false)
    val isCompleted: StateFlow<Boolean> = _isCompleted.asStateFlow()

    internal val _videoSize = MutableStateFlow(Pair(0, 0))
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    internal val _renderedArticleId = MutableStateFlow<Int?>(null)
    val renderedArticleId: StateFlow<Int?> = _renderedArticleId.asStateFlow()
    internal val _renderedVideoSurface = MutableStateFlow<VideoSurface?>(null)
    internal val renderedVideoSurface: StateFlow<VideoSurface?> = _renderedVideoSurface.asStateFlow()
    internal val _playbackGeneration = MutableStateFlow(0L)
    val playbackGeneration: StateFlow<Long> = _playbackGeneration.asStateFlow()
    internal val _firstFrameLatencyMs = MutableStateFlow<Long?>(null)
    val firstFrameLatencyMs: StateFlow<Long?> = _firstFrameLatencyMs.asStateFlow()
    internal val _failedArticleId = MutableStateFlow<Int?>(null)
    val failedArticleId: StateFlow<Int?> = _failedArticleId.asStateFlow()

    internal val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    internal val _authToken = MutableStateFlow<String?>(null)
    val authToken: StateFlow<String?> = _authToken.asStateFlow()

    fun setAuthToken(token: String?) {
        _authToken.value = token
    }

    internal data class VideoSurface(val articleId: Int, val surface: Surface, val generation: Long)
    @Volatile internal var videoSurface: VideoSurface? = null
    private val surfaces = mutableMapOf<Int, Surface>()
    private val _surfaceVideoSizes = MutableStateFlow<Map<Int, Pair<Int, Int>>>(emptyMap())
    internal val surfaceVideoSizes: StateFlow<Map<Int, Pair<Int, Int>>> = _surfaceVideoSizes.asStateFlow()
    internal fun surfaceFor(id: Int): Surface? = surfaces[id]?.takeIf { it.isValid }
    internal fun setSurfaceSize(id: Int, size: Pair<Int, Int>) {
        _surfaceVideoSizes.value = _surfaceVideoSizes.value + (id to size)
    }
    internal fun registerSurface(surface: Surface, id: Int, active: Boolean, generation: Long) {
        surfaces[id] = surface
        if (id == _currentArticleId.value && id !in _surfaceVideoSizes.value) setSurfaceSize(id, _videoSize.value)
        if (active) attachSurface(surface, id, generation)
        else if (id != _currentArticleId.value) detachSurface(surface)
        serviceInstance?.warmNextVideo()
    }
    @Volatile internal var videoFeedPreloader: VideoFeedPreloader? = null
    @Volatile internal var serviceInstance: MediaPlaybackService? = null
    @Volatile internal var selectedVideoArticleId: Int? = null
    @Volatile internal var selectedVideoAtMs: Long = 0

    /**
     * Bật / tắt chế độ lặp lại (Loop / Repeat)
     */
    fun toggleRepeatMode() {
        val next = !_isRepeatMode.value
        _isRepeatMode.value = next
        serviceInstance?.setRepeatMode(next)
    }

    /**
     * Phát lại từ đầu (Replay) khi tệp đã phát hết hoặc người dùng muốn nghe lại từ 00:00
     */
    fun replay(context: Context) {
        _isCompleted.value = false
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_REPLAY
        }
        context.startService(intent)
    }

    /** Each page owns its surface; disposing a neighbour must not detach the active page. */
    fun attachSurface(surface: Surface, articleId: Int, generation: Long) {
        if (articleId != _currentArticleId.value || generation != _playbackGeneration.value) return
        if (videoSurface?.surface === surface && videoSurface?.articleId == articleId && videoSurface?.generation == generation) return
        _renderedArticleId.value = null
        _renderedVideoSurface.value = null
        videoSurface = VideoSurface(articleId, surface, generation)
        serviceInstance?.refreshSurface()
    }

    fun detachSurface(surface: Surface) {
        if (videoSurface?.surface !== surface) return
        videoSurface = null
        _renderedArticleId.value = null
        _renderedVideoSurface.value = null
        serviceInstance?.refreshSurface()
    }

    fun releaseSurface(surface: Surface, texture: SurfaceTexture? = null) {
        val removed = surfaces.filterValues { it === surface }.keys
        removed.forEach { surfaces.remove(it) }
        _surfaceVideoSizes.value = _surfaceVideoSizes.value - removed
        detachSurface(surface)
        val service = serviceInstance
        if (service != null) service.releaseSurface(surface, texture) else {
            surface.release()
            texture?.release()
        }
    }

    fun onSurfaceFrameAvailable(surface: Surface, articleId: Int) {
        if (_renderedVideoSurface.value?.surface !== surface) {
            serviceInstance?.onSurfaceFrameAvailable(surface, articleId)
        }
    }

    fun selectVideo(articleId: Int) {
        if (selectedVideoArticleId != articleId || _currentArticleId.value == null || _failedArticleId.value == articleId) {
            selectedVideoAtMs = android.os.SystemClock.elapsedRealtime()
            _firstFrameLatencyMs.value = null
        }
        selectedVideoArticleId = articleId
    }

    fun pauseDirect() {
        serviceInstance?.pause()
    }

    fun resume(context: Context) {
        context.startService(Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_PLAY
        })
    }

    fun startPlay(context: Context, filePath: String, title: String, articleId: Int, isVideo: Boolean) {
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_START
            putExtra(MediaPlaybackService.EXTRA_FILE_PATH, filePath)
            putExtra(MediaPlaybackService.EXTRA_TITLE, title)
            putExtra(MediaPlaybackService.EXTRA_ARTICLE_ID, articleId)
            putExtra(MediaPlaybackService.EXTRA_IS_VIDEO, isVideo)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun togglePlayPause(context: Context) {
        if (_isCompleted.value) {
            replay(context)
            return
        }
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_TOGGLE
        }
        context.startService(intent)
    }

    fun seekTo(context: Context, positionMs: Int) {
        if (_isCompleted.value && positionMs < _duration.value) {
            _isCompleted.value = false
        }
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_SEEK
            putExtra(MediaPlaybackService.EXTRA_SEEK_POS, positionMs)
        }
        context.startService(intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_STOP
        }
        context.startService(intent)
    }

    /**
     * Dừng phát ngay lập tức nếu bài viết/video đang phát bị xoá
     */
    fun stopIfPlaying(articleId: Int, context: Context) {
        if (_currentArticleId.value == articleId) {
            stop(context)
        }
    }

    fun stopIfPlaying(articleIds: List<Int>, context: Context) {
        val curId = _currentArticleId.value
        if (curId != null && articleIds.contains(curId)) {
            stop(context)
        }
    }
}

/**
 * Service phát âm thanh và video trong nền.
 * Tích hợp MediaSessionCompat để kết nối trực tiếp với Trung tâm điều khiển hệ thống
 * (Quick Settings Media Player / HyperOS / One UI - ô "Đang không phát")
 * và duy trì phát nền WakeLock khi tắt màn hình.
 */
class MediaPlaybackService : Service() {
    companion object {
        const val ACTION_START = "com.podoc.app.action.START"
        const val ACTION_PLAY = "com.podoc.app.action.PLAY"
        const val ACTION_PAUSE = "com.podoc.app.action.PAUSE"
        const val ACTION_TOGGLE = "com.podoc.app.action.TOGGLE"
        const val ACTION_SEEK = "com.podoc.app.action.SEEK"
        const val ACTION_STOP = "com.podoc.app.action.STOP"
        const val ACTION_REPLAY = "com.podoc.app.action.REPLAY"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTICLE_ID = "article_id"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_SEEK_POS = "seek_pos"
        private const val CHANNEL_ID = "podoc_media_playback"
        private const val NOTIFICATION_ID = 2026
        private const val TAG = "MediaPlaybackService"
    }

    @Volatile private var mediaPlayer: ExoPlayer? = null
    private var warmPlayer: ExoPlayer? = null
    private var warmId: Int? = null
    private var warmPath: String? = null
    private var warmSurface: Surface? = null
    private var warmOwner: VideoFeedPreloader? = null
    private var warmRenderingStarted = false
    private var warmTextureSeen = false
    private var activeTextureSeen = false
    private var playerOwner: VideoFeedPreloader? = null
    private var activePrepared = false
    private var playWhenReady = true
    private var activeFilePath: String? = null
    private var boundSurface: Surface? = null
    private var videoRenderingStarted = false
    private val playerHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var mediaSession: MediaSessionCompat? = null
    private var latestPlaybackStartId = 0
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        MediaPlaybackManager.serviceInstance = this
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "PoDocMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onStop() = stopPlayback()
                override fun onSeekTo(pos: Long) = seek(pos.toInt())
                override fun onFastForward() = seek(position() + 10_000)
                override fun onRewind() = seek(position() - 10_000)
                override fun onSetRepeatMode(repeatMode: Int) {
                    MediaPlaybackManager._isRepeatMode.value = repeatMode != PlaybackStateCompat.REPEAT_MODE_NONE
                    setRepeatMode(MediaPlaybackManager._isRepeatMode.value)
                }
            }, playerHandler)
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        if (intent.action == ACTION_START) {
            latestPlaybackStartId = startId
            startForegroundNotification(intent.getStringExtra(EXTRA_TITLE) ?: "Tệp media", true)
        }
        playerHandler.post {
            if (!destroyed) try { handleCommand(intent, startId) } catch (e: Exception) { reportPlaybackError(e) }
        }
        return START_NOT_STICKY
    }

    private fun handleCommand(intent: Intent, startId: Int) {
        when (intent.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_FILE_PATH) ?: return
                val id = intent.getIntExtra(EXTRA_ARTICLE_ID, -1)
                val video = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                if (video && MediaPlaybackManager.selectedVideoArticleId != id) return
                if (activeFilePath == path && MediaPlaybackManager._currentArticleId.value == id) return
                MediaPlaybackManager._currentTitle.value = intent.getStringExtra(EXTRA_TITLE) ?: "Tệp media"
                MediaPlaybackManager._currentArticleId.value = id.takeIf { it != -1 }
                MediaPlaybackManager._currentFilePath.value = path
                MediaPlaybackManager._isVideoMode.value = video
                playFile(path)
            }
            ACTION_PLAY -> resumePlayback()
            ACTION_PAUSE -> pausePlayback()
            ACTION_TOGGLE -> if (playWhenReady) pausePlayback() else resumePlayback()
            ACTION_REPLAY -> replayPlayback()
            ACTION_SEEK -> seek(intent.getIntExtra(EXTRA_SEEK_POS, 0))
            ACTION_STOP -> if (startId >= latestPlaybackStartId) stopPlayback(stopStartId = startId)
        }
    }

    private fun createPlayer(owner: VideoFeedPreloader?): ExoPlayer {
        var created: ExoPlayer? = null
        val delegate = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 100, 1_000)
            .setTargetBufferBytes(16 * 1024 * 1024).build()
        // Explicitly forward Java interface defaults: Kotlin delegation only forwards
        // abstract methods, while Media3's lifecycle/buffer methods are defaults.
        val loadControl = object : LoadControl by delegate {
            override fun onPrepared(playerId: androidx.media3.exoplayer.analytics.PlayerId) = delegate.onPrepared(playerId)
            override fun onStopped(playerId: androidx.media3.exoplayer.analytics.PlayerId) = delegate.onStopped(playerId)
            override fun onReleased(playerId: androidx.media3.exoplayer.analytics.PlayerId) = delegate.onReleased(playerId)
            override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId) = delegate.getBackBufferDurationUs(playerId)
            override fun retainBackBufferFromKeyframe(playerId: androidx.media3.exoplayer.analytics.PlayerId) = delegate.retainBackBufferFromKeyframe(playerId)
            override fun onTracksSelected(parameters: LoadControl.Parameters,
                groups: androidx.media3.exoplayer.source.TrackGroupArray,
                selections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>) =
                delegate.onTracksSelected(parameters, groups, selections)
            override fun shouldStartPlayback(parameters: LoadControl.Parameters) = delegate.shouldStartPlayback(parameters)
            override fun shouldContinuePreloading(playerId: androidx.media3.exoplayer.analytics.PlayerId,
                timeline: androidx.media3.common.Timeline,
                mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
                bufferedDurationUs: Long) = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
            override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
                if (created != null && created !== mediaPlayer && parameters.bufferedDurationUs >= 750_000) return false
                return delegate.shouldContinueLoading(parameters)
            }
        }
        val player = owner?.buildPlayer(loadControl) ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this,
                DefaultHttpDataSource.Factory().setDefaultRequestProperties(
                    MediaPlaybackManager._authToken.value?.takeIf { it.isNotBlank() }
                        ?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()))))
            .setLoadControl(loadControl).build()
        created = player
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== mediaPlayer) return
                MediaPlaybackManager._isBuffering.value = state == Player.STATE_BUFFERING
                when (state) {
                    Player.STATE_READY -> {
                        activePrepared = true
                        MediaPlaybackManager._duration.value = duration()
                        updateMetadata(MediaPlaybackManager._currentTitle.value, duration().toLong())
                        maybeStart()
                    }
                    Player.STATE_ENDED -> {
                        playWhenReady = false
                        MediaPlaybackManager._isPlaying.value = false
                        MediaPlaybackManager._isCompleted.value = true
                        MediaPlaybackManager._currentPosition.value = duration()
                        updatePlaybackState(duration(), false)
                        updateNotification(MediaPlaybackManager._currentTitle.value, false)
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== mediaPlayer) return
                MediaPlaybackManager._isPlaying.value = isPlaying
                updatePlaybackState(position(), isPlaying)
                updateNotification(MediaPlaybackManager._currentTitle.value, isPlaying)
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (player !== mediaPlayer && player !== warmPlayer) return
                val dimensions = (size.width * size.pixelWidthHeightRatio).toInt() to size.height
                player.currentMediaItem?.mediaId?.toIntOrNull()?.let { MediaPlaybackManager.setSurfaceSize(it, dimensions) }
                if (player === mediaPlayer) MediaPlaybackManager._videoSize.value = dimensions
            }
            override fun onRenderedFirstFrame() {
                if (player === mediaPlayer) { videoRenderingStarted = true; publishFrameIfReady() }
                else if (player === warmPlayer) warmRenderingStarted = true
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === mediaPlayer) reportPlaybackError(error)
                else if (player === warmPlayer) {
                    Log.w(TAG, "Bỏ chuẩn bị bộ giải mã phụ: ${error.errorCodeName}")
                    releaseWarmPlayer()
                }
            }
        })
        return player
    }

    private fun playFile(path: String) {
        val outgoingRendered = videoRenderingStarted
        val outgoingTextureSeen = activeTextureSeen
        activePrepared = false
        playWhenReady = true
        activeFilePath = path
        videoRenderingStarted = false
        activeTextureSeen = false
        MediaPlaybackManager._isPlaying.value = false
        MediaPlaybackManager._isCompleted.value = false
        MediaPlaybackManager._currentPosition.value = 0
        MediaPlaybackManager._duration.value = 0
        MediaPlaybackManager._videoSize.value = 0 to 0
        MediaPlaybackManager._renderedArticleId.value = null
        MediaPlaybackManager._renderedVideoSurface.value = null
        MediaPlaybackManager._playbackGeneration.value += 1
        MediaPlaybackManager._failedArticleId.value = null
        MediaPlaybackManager._isBuffering.value = true
        val owner = if (MediaPlaybackManager._isVideoMode.value) MediaPlaybackManager.videoFeedPreloader else null
        if (owner == null) releaseWarmPlayer()
        val id = MediaPlaybackManager._currentArticleId.value ?: -1
        val promoting = owner != null && warmOwner === owner && warmId == id && warmPath == path &&
            warmPlayer?.playbackState == Player.STATE_READY && warmSurface?.isValid == true
        lastSelectionUsedPreparedVideo = promoting
        Log.i(TAG, "VIDEO SELECT | id=$id | prepared=$promoting")
        if (promoting) {
            val incoming = warmPlayer!!
            val incomingSurface = warmSurface!!
            val rendered = warmRenderingStarted
            val seen = warmTextureSeen
            val outgoing = mediaPlayer
            val outgoingOwner = playerOwner
            mediaPlayer = incoming
            playerOwner = owner
            boundSurface = incomingSurface
            warmPlayer = outgoing
            warmOwner = outgoingOwner
            warmId = outgoing?.currentMediaItem?.mediaId?.toIntOrNull()
            warmPath = outgoing?.currentMediaItem?.localConfiguration?.uri?.toString()
            warmSurface = warmId?.let { MediaPlaybackManager.surfaceFor(it) }
            warmRenderingStarted = outgoingRendered
            warmTextureSeen = outgoingTextureSeen
            outgoing?.pause()
            outgoing?.volume = 0f
            outgoing?.seekTo(0)
            videoRenderingStarted = rendered
            activeTextureSeen = seen
            activePrepared = true
            MediaPlaybackManager._isBuffering.value = false
            MediaPlaybackManager._duration.value = duration()
            MediaPlaybackManager._videoSize.value = MediaPlaybackManager.surfaceVideoSizes.value[id] ?: (0 to 0)
            MediaPlaybackManager.attachSurface(incomingSurface, id, MediaPlaybackManager._playbackGeneration.value)
            incoming.volume = 1f
            incoming.repeatMode = if (MediaPlaybackManager._isRepeatMode.value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            updateMetadata(MediaPlaybackManager._currentTitle.value, duration().toLong())
            publishFrameIfReady()
            maybeStart()
        } else {
            if (mediaPlayer == null || playerOwner !== owner) {
                val previous = mediaPlayer
                mediaPlayer = null
                previous?.release()
                boundSurface = null
                playerOwner = owner
                mediaPlayer = createPlayer(owner)
            }
            val player = mediaPlayer!!
            player.volume = 1f
            player.playWhenReady = false
            player.repeatMode = if (MediaPlaybackManager._isRepeatMode.value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.clearVideoSurface()
            boundSurface = null
            val source = owner?.mediaSource(id)
            if (source != null) player.setMediaSource(source)
            else player.setMediaItem(MediaItem.Builder().setMediaId(id.toString()).setUri(path).build())
            player.prepare()
            MediaPlaybackManager.surfaceFor(id)?.let {
                MediaPlaybackManager.attachSurface(it, id, MediaPlaybackManager._playbackGeneration.value)
            }
            refreshSurfaceOnPlayerThread()
        }
        val player = mediaPlayer!!
        warmNextVideo()
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                MediaPlaybackManager._currentPosition.value = position()
                MediaPlaybackManager._duration.value = duration()
                playerOwner?.onPlaybackBuffer((player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
                    player.playbackState == Player.STATE_BUFFERING,
                    if (duration() > 0) (duration() - position()).coerceAtLeast(0).toLong() else -1)
                delay(500)
            }
        }
    }

    private fun maybeStart() {
        val player = mediaPlayer ?: return
        val canStart = !MediaPlaybackManager._isVideoMode.value || boundSurface != null || videoRenderingStarted
        player.playWhenReady = playWhenReady && canStart
    }

    private fun position(): Int = (mediaPlayer?.currentPosition ?: 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    private fun duration(): Int = (mediaPlayer?.duration ?: 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    private fun seek(positionMs: Int) {
        if (!activePrepared) return
        val target = positionMs.coerceIn(0, duration())
        MediaPlaybackManager._isCompleted.value = false
        mediaPlayer?.seekTo(target.toLong())
        MediaPlaybackManager._currentPosition.value = target
        updatePlaybackState(target, MediaPlaybackManager._isPlaying.value)
    }
    private fun resumePlayback() {
        if (MediaPlaybackManager._isCompleted.value) { replayPlayback(); return }
        playWhenReady = true
        maybeStart()
    }
    private fun replayPlayback() {
        MediaPlaybackManager._isCompleted.value = false
        mediaPlayer?.seekTo(0)
        playWhenReady = true
        maybeStart()
    }
    private fun pausePlayback() {
        playWhenReady = false
        mediaPlayer?.pause()
        MediaPlaybackManager._isPlaying.value = false
        updatePlaybackState(position(), false)
        updateNotification(MediaPlaybackManager._currentTitle.value, false)
    }
    fun pause() { playerHandler.post { pausePlayback() } }
    internal fun stopForLogout() { stopPlayback() }
    fun setRepeatMode(repeat: Boolean) {
        playerHandler.post { mediaPlayer?.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    }
    fun refreshSurface() { playerHandler.post { refreshSurfaceOnPlayerThread(); maybeStart() } }
    private fun refreshSurfaceOnPlayerThread() {
        val target = MediaPlaybackManager.videoSurface?.takeIf {
            it.articleId == MediaPlaybackManager._currentArticleId.value &&
                it.generation == MediaPlaybackManager._playbackGeneration.value && it.surface.isValid
        }?.surface
        if (target === boundSurface) return
        activeTextureSeen = false
        mediaPlayer?.setVideoSurface(target)
        boundSurface = target
    }
    fun onSurfaceFrameAvailable(surface: Surface, articleId: Int) {
        playerHandler.post {
            if (surface === warmSurface && articleId == warmId) warmTextureSeen = true
            if (surface === boundSurface && articleId == MediaPlaybackManager._currentArticleId.value) {
                activeTextureSeen = true
                publishFrameIfReady()
            }
        }
    }
    private fun publishFrameIfReady() {
        val target = MediaPlaybackManager.videoSurface ?: return
        val id = MediaPlaybackManager._currentArticleId.value ?: return
        if (videoRenderingStarted && activeTextureSeen && target.surface === boundSurface &&
            target.generation == MediaPlaybackManager._playbackGeneration.value && target.articleId == id &&
            MediaPlaybackManager.selectedVideoArticleId == id && MediaPlaybackManager._videoSize.value.second > 0) {
            MediaPlaybackManager._renderedVideoSurface.value = target
            MediaPlaybackManager._renderedArticleId.value = id
            if (MediaPlaybackManager._firstFrameLatencyMs.value == null) {
                MediaPlaybackManager._firstFrameLatencyMs.value = android.os.SystemClock.elapsedRealtime() - MediaPlaybackManager.selectedVideoAtMs
                Log.i(TAG, "VIDEO FIRST FRAME | id=$id | elapsed_ms=${MediaPlaybackManager._firstFrameLatencyMs.value}")
            }
        }
    }
    internal var lastSelectionUsedPreparedVideo = false
        private set
    internal fun preparedVideoId(): Int? = warmId?.takeIf {
        warmPlayer?.playbackState == Player.STATE_READY && warmRenderingStarted && warmTextureSeen
    }
    internal fun warmNextVideo() {
        if (destroyed || !MediaPlaybackManager._isVideoMode.value) return
        val owner = playerOwner?.takeUnless { it.isClosed } ?: return
        // Preserve the prepared incoming player until a pending selection is promoted.
        if (owner.selectedId() != MediaPlaybackManager._currentArticleId.value ||
            MediaPlaybackManager.selectedVideoArticleId != MediaPlaybackManager._currentArticleId.value) return
        val id = owner.nextId() ?: return
        if (id == MediaPlaybackManager._currentArticleId.value || owner.cachedDurationMs(id) < 1_000) return
        val surface = MediaPlaybackManager.surfaceFor(id) ?: return
        val path = owner.url(id) ?: return
        if (warmOwner !== owner) { releaseWarmPlayer(); warmOwner = owner }
        if (warmPlayer == null) warmPlayer = createPlayer(owner)
        val player = warmPlayer!!
        val sameItem = warmId == id && warmPath == path
        if (sameItem && warmSurface === surface) return
        warmId = id
        warmPath = path
        warmSurface = surface
        warmRenderingStarted = false
        warmTextureSeen = false
        player.volume = 0f
        player.playWhenReady = false
        if (!sameItem) {
            val source = owner.mediaSource(id) ?: return
            player.clearVideoSurface()
            player.setMediaSource(source)
            player.prepare()
            player.setVideoSurface(surface)
        } else {
            player.setVideoSurface(surface)
            val size = player.videoSize
            MediaPlaybackManager.setSurfaceSize(id, (size.width * size.pixelWidthHeightRatio).toInt() to size.height)
            player.seekTo(0)
        }
    }
    private fun releaseWarmPlayer() {
        val previous = warmPlayer
        warmPlayer = null
        warmId = null
        warmPath = null
        warmSurface = null
        warmOwner = null
        warmRenderingStarted = false
        warmTextureSeen = false
        previous?.release()
    }
    internal fun releaseWarmForOwner(owner: VideoFeedPreloader) {
        if (warmOwner === owner) releaseWarmPlayer()
    }
    fun releaseSurface(surface: Surface, texture: SurfaceTexture? = null) {
        playerHandler.post {
            try {
                if (boundSurface === surface) { mediaPlayer?.clearVideoSurface(); boundSurface = null }
                if (warmSurface === surface) {
                    warmPlayer?.clearVideoSurface()
                    warmSurface = null
                    warmRenderingStarted = false
                    warmTextureSeen = false
                }
            } finally { surface.release(); texture?.release() }
        }
    }
    private fun reportPlaybackError(error: Exception) {
        Log.e(TAG, "Không thể phát media", error)
        activePrepared = false
        activeFilePath = null
        playWhenReady = false
        mediaPlayer?.pause()
        MediaPlaybackManager._isPlaying.value = false
        MediaPlaybackManager._isBuffering.value = false
        MediaPlaybackManager._renderedArticleId.value = null
        MediaPlaybackManager._renderedVideoSurface.value = null
        MediaPlaybackManager._failedArticleId.value = MediaPlaybackManager._currentArticleId.value
    }
    private fun stopPlayback(stopService: Boolean = true, stopStartId: Int? = null) {
        progressJob?.cancel()
        releaseWarmPlayer()
        val previous = mediaPlayer
        mediaPlayer = null
        previous?.release()
        playerOwner = null
        activePrepared = false
        activeFilePath = null
        boundSurface = null
        MediaPlaybackManager._isPlaying.value = false
        MediaPlaybackManager._isCompleted.value = false
        MediaPlaybackManager._isBuffering.value = false
        MediaPlaybackManager._currentArticleId.value = null
        MediaPlaybackManager._currentFilePath.value = null
        MediaPlaybackManager._renderedArticleId.value = null
        MediaPlaybackManager._renderedVideoSurface.value = null
        MediaPlaybackManager._failedArticleId.value = null
        MediaPlaybackManager._currentPosition.value = 0
        MediaPlaybackManager._duration.value = 0
        updatePlaybackState(0, false)
        mediaSession?.isActive = false
        if (stopService && (stopStartId == null || stopSelfResult(stopStartId))) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopStartId == null) stopSelf()
        }
    }

    private fun updateMetadata(title: String, durationMs: Long) {
        try {
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "PoDoc")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "PoDoc Media")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build()
            mediaSession?.setMetadata(metadata)
            mediaSession?.isActive = true
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi cập nhật MediaMetadata: ${e.message}")
        }
    }

    private fun updatePlaybackState(positionMs: Int, isPlaying: Boolean) {
        try {
            val actions = PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_STOP

            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            val speed = if (isPlaying) 1.0f else 0.0f

            val playbackState = PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, positionMs.toLong(), speed)
                .build()

            mediaSession?.setPlaybackState(playbackState)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi cập nhật PlaybackState: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phát âm thanh & video PoDoc",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Duy trì phát âm thanh và video khi tắt màn hình"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, isPlaying: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = when {
            isPlaying -> "Tạm dừng"
            MediaPlaybackManager._isCompleted.value -> "Phát lại"
            else -> "Tiếp tục"
        }

        val mediaStyle = MediaStyle()
            .setShowActionsInCompactView(0, 1)

        mediaSession?.let {
            mediaStyle.setMediaSession(it.sessionToken)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (title.isNotBlank()) title else "PoDoc Media Player")
            .setContentText("PoDoc")
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(playPauseIcon, playPauseText, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", stopPendingIntent)
            .setStyle(mediaStyle)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundNotification(title: String, isPlaying: Boolean) {
        val notification = buildNotification(title, isPlaying)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, isPlaying: Boolean) {
        val notification = buildNotification(title, isPlaying)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        destroyed = true
        stopPlayback(stopService = false)
        if (MediaPlaybackManager.serviceInstance === this) MediaPlaybackManager.serviceInstance = null
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

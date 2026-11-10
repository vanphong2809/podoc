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
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.podoc.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
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
    @Volatile internal var serviceInstance: MediaPlaybackService? = null
    @Volatile internal var selectedVideoArticleId: Int? = null

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
        if (videoSurface?.surface === surface && videoSurface?.articleId == articleId) return
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

    /**
     * Chuẩn bị sẵn (pre-prepare) MediaPlayer cho video kế tiếp trong nền
     */
    fun preloadNext(filePath: String) {
        serviceInstance?.preloadNext(filePath)
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

    private var mediaPlayer: MediaPlayer? = null
    private var preloadedPlayer: MediaPlayer? = null
    private var preloadedFilePath: String? = null
    private var preloadedPrepared = false
    private var activePrepared = false
    private var playWhenReady = true
    private var activeFilePath: String? = null
    private var boundSurface: Surface? = null
    private var videoRenderingStarted = false
    // MediaPlayer creation, callbacks and every access stay on this Looper.
    private val playerThread = HandlerThread("PoDocPlayback").apply { start() }
    private val playerHandler = Handler(playerThread.looper)
    @Volatile private var latestPlaybackStartId = 0
    private var mediaSession: MediaSessionCompat? = null
    private var progressJob: Job? = null
    private val serviceScope = CoroutineScope(playerHandler.asCoroutineDispatcher() + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MediaPlaybackManager.serviceInstance = this
        createNotificationChannel()
        initMediaSession()
    }

    private fun initMediaSession() {
        try {
            mediaSession = MediaSessionCompat(this, "PoDocMediaSession").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        if (MediaPlaybackManager._isCompleted.value) {
                            replayPlayback()
                        } else {
                            resumePlayback()
                        }
                    }

                    override fun onPause() {
                        pausePlayback()
                    }

                    override fun onSetRepeatMode(repeatMode: Int) {
                        val isLoop = repeatMode != PlaybackStateCompat.REPEAT_MODE_NONE
                        MediaPlaybackManager._isRepeatMode.value = isLoop
                        try {
                            if (activePrepared) mediaPlayer?.isLooping = isLoop
                        } catch (_: Exception) {}
                    }

                    override fun onSeekTo(pos: Long) {
                        if (!activePrepared) return
                        try {
                            mediaPlayer?.seekTo(pos.toInt())
                            MediaPlaybackManager._currentPosition.value = pos.toInt()
                            if (pos < (mediaPlayer?.duration ?: 0)) {
                                MediaPlaybackManager._isCompleted.value = false
                            }
                            updatePlaybackState(pos.toInt(), MediaPlaybackManager._isPlaying.value)
                        } catch (e: Exception) {
                            Log.w(TAG, "Lỗi seekTo: ${e.message}")
                        }
                    }

                    override fun onFastForward() {
                        if (!activePrepared) return
                        val cur = mediaPlayer?.currentPosition ?: 0
                        val dur = mediaPlayer?.duration ?: 0
                        val target = (cur + 10000).coerceAtMost(dur)
                        mediaPlayer?.seekTo(target)
                        MediaPlaybackManager._currentPosition.value = target
                        if (target < dur) {
                            MediaPlaybackManager._isCompleted.value = false
                        }
                        updatePlaybackState(target, MediaPlaybackManager._isPlaying.value)
                    }

                    override fun onRewind() {
                        if (!activePrepared) return
                        val cur = mediaPlayer?.currentPosition ?: 0
                        val target = (cur - 10000).coerceAtLeast(0)
                        mediaPlayer?.seekTo(target)
                        MediaPlaybackManager._currentPosition.value = target
                        MediaPlaybackManager._isCompleted.value = false
                        updatePlaybackState(target, MediaPlaybackManager._isPlaying.value)
                    }

                    override fun onStop() {
                        stopPlayback()
                    }
                }, playerHandler)
                isActive = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo MediaSessionCompat", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        // Meet the foreground-service deadline before queuing any codec work.
        if (intent.action == ACTION_START) {
            latestPlaybackStartId = startId
            startForegroundNotification(intent.getStringExtra(EXTRA_TITLE) ?: "Tệp media", true)
        }
        playerHandler.post {
            try {
                handleCommand(intent, startId)
            } catch (e: Exception) {
                reportPlaybackError(e)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleCommand(intent: Intent, startId: Int) {
        when (intent.action) {
            ACTION_START -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Tệp media"
                val articleId = intent.getIntExtra(EXTRA_ARTICLE_ID, -1)
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

                if (isVideo && MediaPlaybackManager.selectedVideoArticleId != articleId) return
                if (activeFilePath == filePath && MediaPlaybackManager._currentArticleId.value == articleId) return
                MediaPlaybackManager._currentTitle.value = title
                MediaPlaybackManager._currentArticleId.value = if (articleId != -1) articleId else null
                MediaPlaybackManager._currentFilePath.value = filePath
                MediaPlaybackManager._isVideoMode.value = isVideo

                playFile(filePath)
            }

            ACTION_PLAY -> {
                resumePlayback()
            }

            ACTION_PAUSE -> {
                pausePlayback()
            }

            ACTION_TOGGLE -> {
                if (playWhenReady) {
                    pausePlayback()
                } else {
                    resumePlayback()
                }
            }

            ACTION_REPLAY -> {
                replayPlayback()
            }

            ACTION_SEEK -> {
                if (!activePrepared) return
                val pos = intent.getIntExtra(EXTRA_SEEK_POS, 0)
                try {
                    mediaPlayer?.seekTo(pos)
                    MediaPlaybackManager._currentPosition.value = pos
                    val dur = mediaPlayer?.duration ?: 0
                    if (pos < dur) {
                        MediaPlaybackManager._isCompleted.value = false
                    }
                    updatePlaybackState(pos, MediaPlaybackManager._isPlaying.value)
                } catch (e: Exception) {
                    Log.w(TAG, "Lỗi seekTo: ${e.message}")
                }
            }

            ACTION_STOP -> {
                // Leaving one reader must not stop a newer playback request already delivered.
                if (startId >= latestPlaybackStartId) stopPlayback(stopStartId = startId)
            }
        }

    }

    private fun playFile(filePath: String) {
        progressJob?.cancel()
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
        activePrepared = false
        playWhenReady = true
        activeFilePath = filePath
        videoRenderingStarted = false
        try {
            val previous = mediaPlayer
            mediaPlayer = null
            try {
                previous?.setSurface(null)
            } finally {
                previous?.release()
            }
            boundSurface = null
            if (preloadedFilePath == filePath && preloadedPlayer != null) {
                mediaPlayer = preloadedPlayer
                activePrepared = preloadedPrepared
                preloadedPlayer = null
                preloadedFilePath = null
                preloadedPrepared = false
                refreshSurfaceOnPlayerThread()
                // A promoted player may still be Preparing. Its callback starts it later.
                if (activePrepared) startPreparedPlayer()
            } else {
                mediaPlayer = createPlayer(filePath)
                refreshSurfaceOnPlayerThread()
                mediaPlayer?.prepareAsync()
            }
        } catch (e: Exception) {
            reportPlaybackError(e)
        }
    }

    private fun createPlayer(filePath: String): MediaPlayer {
        val player = MediaPlayer()
        try {
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                val uri = Uri.parse(filePath)
                val headers = mutableMapOf<String, String>()
                val token = MediaPlaybackManager._authToken.value
                if (!token.isNullOrBlank()) {
                    headers["Authorization"] = "Bearer $token"
                }
                player.setDataSource(applicationContext, uri, headers)
            } else {
                player.setDataSource(filePath)
            }
            player.setOnPreparedListener { prepared ->
                when (prepared) {
                    mediaPlayer -> {
                        activePrepared = true
                        MediaPlaybackManager._isBuffering.value = false
                        try {
                            startPreparedPlayer()
                        } catch (e: Exception) {
                            reportPlaybackError(e)
                        }
                    }
                    preloadedPlayer -> preloadedPrepared = true
                }
            }
            player.setOnVideoSizeChangedListener { source, width, height ->
                if (source === mediaPlayer) MediaPlaybackManager._videoSize.value = width to height
            }
            player.setOnInfoListener { source, what, _ ->
                if (source === mediaPlayer) {
                    when (what) {
                        MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                            videoRenderingStarted = true
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            MediaPlaybackManager._isBuffering.value = true
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            MediaPlaybackManager._isBuffering.value = false
                        }
                    }
                }
                false
            }
            player.setOnCompletionListener { source ->
                if (source === mediaPlayer) {
                    MediaPlaybackManager._isPlaying.value = false
                    MediaPlaybackManager._isCompleted.value = true
                    MediaPlaybackManager._currentPosition.value = source.duration
                    updatePlaybackState(source.duration, false)
                    updateNotification(MediaPlaybackManager._currentTitle.value, false)
                }
            }
            player.setOnErrorListener { source, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                MediaPlaybackManager._isBuffering.value = false
                if (source === mediaPlayer) {
                    reportPlaybackError(IllegalStateException("MediaPlayer error $what/$extra"))
                } else if (source === preloadedPlayer) {
                    clearPreloadedPlayer()
                }
                true
            }
            return player
        } catch (e: Exception) {
            player.release()
            throw e
        }
    }

    private fun startPreparedPlayer() {
        val player = mediaPlayer ?: return
        player.isLooping = MediaPlaybackManager._isRepeatMode.value
        MediaPlaybackManager._videoSize.value = player.videoWidth to player.videoHeight
        MediaPlaybackManager._duration.value = player.duration
        refreshSurfaceOnPlayerThread()
        val canStart = playWhenReady && (!MediaPlaybackManager._isVideoMode.value || boundSurface != null)
        if (canStart) player.start()
        MediaPlaybackManager._isPlaying.value = canStart
        updateMetadata(MediaPlaybackManager._currentTitle.value, player.duration.toLong())
        updatePlaybackState(player.currentPosition, canStart)
        updateNotification(MediaPlaybackManager._currentTitle.value, canStart)
        startProgressTracker()
    }

    private fun reportPlaybackError(error: Exception) {
        Log.e(TAG, "Không thể phát media", error)
        activePrepared = false
        activeFilePath = null
        playWhenReady = false
        MediaPlaybackManager._isPlaying.value = false
        MediaPlaybackManager._renderedArticleId.value = null
        MediaPlaybackManager._renderedVideoSurface.value = null
        MediaPlaybackManager._failedArticleId.value = MediaPlaybackManager._currentArticleId.value
        updatePlaybackState(0, false)
        updateNotification(MediaPlaybackManager._currentTitle.value, false)
    }

    /** Only the pager's predicted next video gets a decoder, not every cached file. */
    fun preloadNext(filePath: String) {
        playerHandler.post {
            if (activeFilePath == filePath || preloadedFilePath == filePath) return@post
            clearPreloadedPlayer()
            try {
                preloadedPlayer = createPlayer(filePath)
                preloadedFilePath = filePath
                preloadedPlayer?.prepareAsync()
            } catch (e: Exception) {
                clearPreloadedPlayer()
                Log.w(TAG, "Lỗi preload video: ${e.message}")
            }
        }
    }

    private fun clearPreloadedPlayer() {
        val previous = preloadedPlayer
        preloadedPlayer = null
        preloadedFilePath = null
        preloadedPrepared = false
        previous?.release()
    }

    fun refreshSurface() {
        playerHandler.post {
            refreshSurfaceOnPlayerThread()
            // Start a new video only after its own output exists. Audio-only
            // playback and audio continuing in the background remain independent.
            if (activePrepared && playWhenReady && boundSurface != null && mediaPlayer?.isPlaying == false) {
                startPreparedPlayer()
            }
        }
    }

    fun onSurfaceFrameAvailable(surface: Surface, articleId: Int) {
        playerHandler.post {
            val size = MediaPlaybackManager._videoSize.value
            val target = MediaPlaybackManager.videoSurface
            if (videoRenderingStarted && activePrepared && surface === boundSurface &&
                target != null && target.surface === surface &&
                target.generation == MediaPlaybackManager._playbackGeneration.value &&
                MediaPlaybackManager._currentArticleId.value == articleId &&
                MediaPlaybackManager.selectedVideoArticleId == articleId &&
                size.first > 0 && size.second > 0) {
                MediaPlaybackManager._renderedVideoSurface.value = target
                MediaPlaybackManager._renderedArticleId.value = articleId
            }
        }
    }

    private fun refreshSurfaceOnPlayerThread() {
        val target = MediaPlaybackManager.videoSurface?.takeIf {
            it.articleId == MediaPlaybackManager._currentArticleId.value &&
                it.generation == MediaPlaybackManager._playbackGeneration.value && it.surface.isValid
        }?.surface
        if (target === boundSurface) return
        try {
            mediaPlayer?.setSurface(target)
            boundSurface = target
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi gắn surface: ${e.message}")
        }
    }

    fun releaseSurface(surface: Surface, texture: SurfaceTexture? = null) {
        playerHandler.post {
            try {
                if (boundSurface === surface) {
                    mediaPlayer?.setSurface(null)
                    boundSurface = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi gỡ surface: ${e.message}")
            } finally {
                surface.release()
                texture?.release()
            }
        }
    }

    fun setRepeatMode(repeat: Boolean) {
        playerHandler.post {
            if (activePrepared) mediaPlayer?.isLooping = repeat
        }
    }

    fun pause() {
        playerHandler.post { pausePlayback() }
    }

    private fun resumePlayback() {
        playWhenReady = true
        if (!activePrepared) return
        try {
            if (MediaPlaybackManager._isCompleted.value) {
                replayPlayback()
                return
            }
            mediaPlayer?.start()
            MediaPlaybackManager._isPlaying.value = true
            val pos = mediaPlayer?.currentPosition ?: 0
            updatePlaybackState(pos, isPlaying = true)
            updateNotification(MediaPlaybackManager._currentTitle.value, true)
            startProgressTracker()
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi resume: ${e.message}")
        }
    }

    private fun replayPlayback() {
        playWhenReady = true
        if (!activePrepared) return
        try {
            MediaPlaybackManager._isCompleted.value = false
            mediaPlayer?.seekTo(0)
            mediaPlayer?.start()
            MediaPlaybackManager._isPlaying.value = true
            MediaPlaybackManager._currentPosition.value = 0
            updatePlaybackState(0, isPlaying = true)
            updateNotification(MediaPlaybackManager._currentTitle.value, true)
            startProgressTracker()
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi replay: ${e.message}")
        }
    }

    private fun pausePlayback() {
        playWhenReady = false
        if (!activePrepared) return
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause()
            MediaPlaybackManager._isPlaying.value = false
            val pos = mediaPlayer?.currentPosition ?: 0
            updatePlaybackState(pos, isPlaying = false)
            updateNotification(MediaPlaybackManager._currentTitle.value, false)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi pause: ${e.message}")
        }
    }

    private fun stopPlayback(stopService: Boolean = true, stopStartId: Int? = null) {
        progressJob?.cancel()
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        activePrepared = false
        activeFilePath = null
        boundSurface = null
        clearPreloadedPlayer()
        if (MediaPlaybackManager.serviceInstance == null || MediaPlaybackManager.serviceInstance === this) {
            MediaPlaybackManager._isPlaying.value = false
            MediaPlaybackManager._isCompleted.value = false
            MediaPlaybackManager._currentArticleId.value = null
            MediaPlaybackManager._currentFilePath.value = null
            MediaPlaybackManager._renderedArticleId.value = null
            MediaPlaybackManager._renderedVideoSurface.value = null
            MediaPlaybackManager._failedArticleId.value = null
            MediaPlaybackManager._currentPosition.value = 0
            MediaPlaybackManager._duration.value = 0
        }

        updatePlaybackState(0, isPlaying = false)
        mediaSession?.isActive = false

        if (stopService) {
            if (stopStartId == null || stopSelfResult(stopStartId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (stopStartId == null) stopSelf()
            }
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

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null && MediaPlaybackManager._isPlaying.value) {
                    try {
                        val pos = mp.currentPosition
                        MediaPlaybackManager._currentPosition.value = pos
                        updatePlaybackState(pos, isPlaying = true)
                    } catch (_: Exception) {}
                }
                delay(1000)
            }
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
        if (MediaPlaybackManager.serviceInstance === this) MediaPlaybackManager.serviceInstance = null
        playerHandler.post {
            stopPlayback(stopService = false)
            serviceScope.cancel()
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            playerThread.quitSafely()
        }
        super.onDestroy()
    }
}

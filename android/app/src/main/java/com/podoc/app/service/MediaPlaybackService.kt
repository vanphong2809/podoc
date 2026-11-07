package com.podoc.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.SurfaceHolder
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

    internal var currentHolder: SurfaceHolder? = null
    internal var mediaPlayer: MediaPlayer? = null
    internal var serviceInstance: MediaPlaybackService? = null

    /**
     * Bật / tắt chế độ lặp lại (Loop / Repeat)
     */
    fun toggleRepeatMode() {
        val next = !_isRepeatMode.value
        _isRepeatMode.value = next
        try {
            mediaPlayer?.isLooping = next
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi toggle repeat: ${e.message}")
        }
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

    /**
     * Gắn SurfaceHolder để MediaPlayer render khung hình video.
     * Khi màn hình bật lại, SurfaceView được tái tạo và gọi hàm này.
     */
    fun attachSurface(holder: SurfaceHolder) {
        currentHolder = holder
        try {
            mediaPlayer?.setDisplay(holder)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi gắn surface: ${e.message}")
        }
    }

    /**
     * Gỡ SurfaceHolder khi màn hình tắt hoặc view bị ẩn.
     * MediaPlayer tiếp tục chạy trong nền (âm thanh vẫn phát liên tục).
     */
    fun detachSurface() {
        currentHolder = null
        try {
            mediaPlayer?.setDisplay(null)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi gỡ surface: ${e.message}")
        }
    }

    /**
     * Tạm dừng trực tiếp ngay lập tức MediaPlayer (0ms delay) và thông báo Service cập nhật
     */
    fun pauseDirect(context: Context? = null) {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
            _isPlaying.value = false
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi pauseDirect: ${e.message}")
        }
        context?.let { ctx ->
            try {
                val intent = Intent(ctx, MediaPlaybackService::class.java).apply {
                    action = MediaPlaybackService.ACTION_PAUSE
                }
                ctx.startService(intent)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun startPlay(context: Context, filePath: String, title: String, articleId: Int, isVideo: Boolean) {
        _isCompleted.value = false
        _currentTitle.value = title
        _currentArticleId.value = articleId
        _currentFilePath.value = filePath
        _isVideoMode.value = isVideo

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
    private var mediaSession: MediaSessionCompat? = null
    private var progressJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

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
                            mediaPlayer?.isLooping = isLoop
                        } catch (_: Exception) {}
                    }

                    override fun onSeekTo(pos: Long) {
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
                })
                isActive = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo MediaSessionCompat", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Tệp media"
                val articleId = intent.getIntExtra(EXTRA_ARTICLE_ID, -1)
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

                MediaPlaybackManager._currentTitle.value = title
                MediaPlaybackManager._currentArticleId.value = if (articleId != -1) articleId else null
                MediaPlaybackManager._currentFilePath.value = filePath
                MediaPlaybackManager._isVideoMode.value = isVideo

                startForegroundNotification(title, isPlaying = true)
                playFile(filePath, isVideo)
            }

            ACTION_PLAY -> {
                resumePlayback()
            }

            ACTION_PAUSE -> {
                pausePlayback()
            }

            ACTION_TOGGLE -> {
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                } else {
                    resumePlayback()
                }
            }

            ACTION_REPLAY -> {
                replayPlayback()
            }

            ACTION_SEEK -> {
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
                stopPlayback()
            }
        }

        return START_NOT_STICKY
    }

    private fun playFile(filePath: String, isVideo: Boolean) {
        try {
            // KIỂM TRA PRELOAD PLAYER ĐÃ SẴN SÀNG -> PHÁT TỨC THÌ TRONG 0MS!
            if (preloadedPlayer != null && preloadedFilePath == filePath) {
                mediaPlayer?.release()
                mediaPlayer = preloadedPlayer
                preloadedPlayer = null
                preloadedFilePath = null
                MediaPlaybackManager.mediaPlayer = mediaPlayer

                mediaPlayer?.apply {
                    isLooping = MediaPlaybackManager._isRepeatMode.value
                    if (videoWidth > 0 && videoHeight > 0) {
                        MediaPlaybackManager._videoSize.value = Pair(videoWidth, videoHeight)
                    }
                    if (isVideo && MediaPlaybackManager.currentHolder != null) {
                        try {
                            setDisplay(MediaPlaybackManager.currentHolder)
                        } catch (e: Exception) {
                            Log.w(TAG, "Lỗi gắn surface cho preloaded player: ${e.message}")
                        }
                    }
                    start()
                    MediaPlaybackManager._isPlaying.value = true
                    MediaPlaybackManager._isCompleted.value = false
                    MediaPlaybackManager._duration.value = duration

                    updateMetadata(MediaPlaybackManager._currentTitle.value, duration.toLong())
                    updatePlaybackState(0, isPlaying = true)
                    updateNotification(MediaPlaybackManager._currentTitle.value, true)
                    startProgressTracker()
                }
                return
            }

            preloadedPlayer?.release()
            preloadedPlayer = null
            preloadedFilePath = null

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    mp.isLooping = MediaPlaybackManager._isRepeatMode.value
                    if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                        MediaPlaybackManager._videoSize.value = Pair(mp.videoWidth, mp.videoHeight)
                    }
                    if (isVideo && MediaPlaybackManager.currentHolder != null) {
                        try {
                            mp.setDisplay(MediaPlaybackManager.currentHolder)
                        } catch (e: Exception) {
                            Log.w(TAG, "Lỗi gán surface listener: ${e.message}")
                        }
                    }
                    mp.start()
                    MediaPlaybackManager._isPlaying.value = true
                    MediaPlaybackManager._isCompleted.value = false
                    MediaPlaybackManager._duration.value = mp.duration

                    updateMetadata(MediaPlaybackManager._currentTitle.value, mp.duration.toLong())
                    updatePlaybackState(0, isPlaying = true)
                    updateNotification(MediaPlaybackManager._currentTitle.value, true)
                    startProgressTracker()
                }
                setOnCompletionListener { mp ->
                    if (MediaPlaybackManager._isRepeatMode.value) {
                        try {
                            mp.seekTo(0)
                            mp.start()
                            MediaPlaybackManager._isPlaying.value = true
                            MediaPlaybackManager._isCompleted.value = false
                            MediaPlaybackManager._currentPosition.value = 0
                            updatePlaybackState(0, isPlaying = true)
                            updateNotification(MediaPlaybackManager._currentTitle.value, true)
                            startProgressTracker()
                        } catch (e: Exception) {
                            Log.w(TAG, "Lỗi loop media: ${e.message}")
                        }
                    } else {
                        MediaPlaybackManager._isPlaying.value = false
                        MediaPlaybackManager._isCompleted.value = true
                        MediaPlaybackManager._currentPosition.value = mp.duration
                        updatePlaybackState(mp.duration, isPlaying = false)
                        updateNotification(MediaPlaybackManager._currentTitle.value, false)
                    }
                }
                setOnVideoSizeChangedListener { _, width, height ->
                    MediaPlaybackManager._videoSize.value = Pair(width, height)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    MediaPlaybackManager._isPlaying.value = false
                    updatePlaybackState(0, isPlaying = false)
                    false
                }
                prepareAsync()
            }
            MediaPlaybackManager.mediaPlayer = mediaPlayer
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo MediaPlayer", e)
        }
    }

    /**
     * Chuẩn bị sẵn (pre-prepare) MediaPlayer cho video kế tiếp trong nền
     */
    fun preloadNext(filePath: String) {
        if (preloadedFilePath == filePath && preloadedPlayer != null) return
        try {
            preloadedPlayer?.release()
            preloadedPlayer = null
            preloadedFilePath = null

            val p = MediaPlayer().apply {
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                        MediaPlaybackManager._videoSize.value = Pair(mp.videoWidth, mp.videoHeight)
                    }
                }
                setOnCompletionListener { mp ->
                    if (MediaPlaybackManager._isRepeatMode.value) {
                        try {
                            mp.seekTo(0)
                            mp.start()
                            MediaPlaybackManager._isPlaying.value = true
                        } catch (e: Exception) {}
                    } else {
                        MediaPlaybackManager._isPlaying.value = false
                        MediaPlaybackManager._isCompleted.value = true
                    }
                }
                setOnVideoSizeChangedListener { _, width, height ->
                    MediaPlaybackManager._videoSize.value = Pair(width, height)
                }
                setOnErrorListener { _, _, _ ->
                    preloadedPlayer?.release()
                    preloadedPlayer = null
                    preloadedFilePath = null
                    false
                }
                prepareAsync()
            }
            preloadedPlayer = p
            preloadedFilePath = filePath
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi preload video kế tiếp: ${e.message}")
        }
    }

    private fun resumePlayback() {
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
        try {
            mediaPlayer?.pause()
            MediaPlaybackManager._isPlaying.value = false
            val pos = mediaPlayer?.currentPosition ?: 0
            updatePlaybackState(pos, isPlaying = false)
            updateNotification(MediaPlaybackManager._currentTitle.value, false)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi pause: ${e.message}")
        }
    }

    private fun stopPlayback() {
        progressJob?.cancel()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        MediaPlaybackManager.mediaPlayer = null
        MediaPlaybackManager._isPlaying.value = false
        MediaPlaybackManager._isCompleted.value = false
        MediaPlaybackManager._currentArticleId.value = null
        MediaPlaybackManager._currentFilePath.value = null
        MediaPlaybackManager.currentHolder = null

        updatePlaybackState(0, isPlaying = false)
        mediaSession?.isActive = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        MediaPlaybackManager.serviceInstance = null
        stopPlayback()
        try {
            preloadedPlayer?.release()
            preloadedPlayer = null
            preloadedFilePath = null
        } catch (e: Exception) {
            // Ignore
        }
        serviceScope.cancel()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

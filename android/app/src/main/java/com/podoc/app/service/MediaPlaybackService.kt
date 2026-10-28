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
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.app.NotificationCompat
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

    internal val _videoSize = MutableStateFlow(Pair(0, 0))
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    internal var currentHolder: SurfaceHolder? = null
    internal var mediaPlayer: MediaPlayer? = null
    internal var serviceInstance: MediaPlaybackService? = null

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
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_TOGGLE
        }
        context.startService(intent)
    }

    fun seekTo(context: Context, positionMs: Int) {
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
}

/**
 * Service phát âm thanh và video trong nền.
 * Duy trì WakeLock và Foreground Notification để đảm bảo khi tắt màn hình,
 * âm thanh từ file .mp3 và .mp4 vẫn phát không bị ngắt quãng.
 */
class MediaPlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.podoc.app.action.START"
        const val ACTION_PLAY = "com.podoc.app.action.PLAY"
        const val ACTION_PAUSE = "com.podoc.app.action.PAUSE"
        const val ACTION_TOGGLE = "com.podoc.app.action.TOGGLE"
        const val ACTION_SEEK = "com.podoc.app.action.SEEK"
        const val ACTION_STOP = "com.podoc.app.action.STOP"

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
    private var progressJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MediaPlaybackManager.serviceInstance = this
        createNotificationChannel()
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

            ACTION_SEEK -> {
                val pos = intent.getIntExtra(EXTRA_SEEK_POS, 0)
                try {
                    mediaPlayer?.seekTo(pos)
                    MediaPlaybackManager._currentPosition.value = pos
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
                    if (isVideo && MediaPlaybackManager.currentHolder != null) {
                        try {
                            mp.setDisplay(MediaPlaybackManager.currentHolder)
                        } catch (e: Exception) {
                            Log.w(TAG, "Lỗi gán surface listener: ${e.message}")
                        }
                    }
                    mp.start()
                    MediaPlaybackManager._isPlaying.value = true
                    MediaPlaybackManager._duration.value = mp.duration
                    updateNotification(MediaPlaybackManager._currentTitle.value, true)
                    startProgressTracker()
                }
                setOnCompletionListener {
                    MediaPlaybackManager._isPlaying.value = false
                    MediaPlaybackManager._currentPosition.value = it.duration
                    updateNotification(MediaPlaybackManager._currentTitle.value, false)
                }
                setOnVideoSizeChangedListener { _, width, height ->
                    MediaPlaybackManager._videoSize.value = Pair(width, height)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    MediaPlaybackManager._isPlaying.value = false
                    false
                }
                prepareAsync()
            }
            MediaPlaybackManager.mediaPlayer = mediaPlayer
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo MediaPlayer", e)
        }
    }

    private fun resumePlayback() {
        try {
            mediaPlayer?.start()
            MediaPlaybackManager._isPlaying.value = true
            updateNotification(MediaPlaybackManager._currentTitle.value, true)
            startProgressTracker()
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi resume: ${e.message}")
        }
    }

    private fun pausePlayback() {
        try {
            mediaPlayer?.pause()
            MediaPlaybackManager._isPlaying.value = false
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
        MediaPlaybackManager._currentArticleId.value = null
        MediaPlaybackManager._currentFilePath.value = null
        MediaPlaybackManager.currentHolder = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null && MediaPlaybackManager._isPlaying.value) {
                    try {
                        MediaPlaybackManager._currentPosition.value = mp.currentPosition
                    } catch (_: Exception) {}
                }
                delay(500)
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
        val playPauseText = if (isPlaying) "Tạm dừng" else "Tiếp tục"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (title.isNotBlank()) title else "PoDoc Media Player")
            .setContentText(if (isPlaying) "Đang phát trong nền" else "Đang tạm dừng")
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(playPauseIcon, playPauseText, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", stopPendingIntent)
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
        serviceScope.cancel()
        super.onDestroy()
    }
}


package com.podoc.app.ui.screens.reader

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.podoc.app.service.MediaPlaybackManager

/** A texture participates in the pager's normal composition, clipping and movement. */
@Composable
internal fun FeedVideoSurface(articleId: Int, isActive: Boolean, playbackGeneration: Long, modifier: Modifier = Modifier) {
    // A newly selected player must never inherit a previously queued texture.
    key(articleId, isActive, if (isActive) playbackGeneration else 0L) {
        AndroidView(
            factory = { FeedTextureView(it) },
            update = { it.bind(if (isActive) articleId else null, playbackGeneration) },
            onRelease = { it.dispose() },
            modifier = modifier
        )
    }
}

private class FeedTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var output: Surface? = null
    private var articleId: Int? = null
    private var generation = 0L
    private var retiredTexture: SurfaceTexture? = null
    private val videoTransform = Matrix()
    private var fittedSize = listOf(0, 0, 0, 0)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun bind(id: Int?, playbackGeneration: Long) {
        articleId = id
        generation = playbackGeneration
        keepScreenOn = id != null
        output?.let { surface ->
            if (id != null) MediaPlaybackManager.attachSurface(surface, id, generation)
            else MediaPlaybackManager.detachSurface(surface)
        }
        fitVideo()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        output = Surface(texture)
        bind(articleId, generation)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        fitVideo()
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        // Decoder INFO only means a frame was queued. Wait until TextureView
        // actually latches it, with the final aspect transform already applied.
        fitVideo()
        val id = articleId ?: return
        output?.let { MediaPlaybackManager.onSurfaceFrameAvailable(it, id) }
    }

    private fun fitVideo() {
        if (articleId != MediaPlaybackManager.currentArticleId.value ||
            generation != MediaPlaybackManager.playbackGeneration.value) return
        val (videoWidth, videoHeight) = MediaPlaybackManager.videoSize.value
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val size = listOf(width, height, videoWidth, videoHeight)
        if (size == fittedSize) return
        fittedSize = size
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        videoTransform.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f)
        setTransform(videoTransform)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        dispose(texture)
        // The playback thread releases it after the producer has detached.
        return false
    }

    fun dispose(texture: SurfaceTexture? = surfaceTexture) {
        if (texture != null && retiredTexture === texture) return
        retiredTexture = texture
        output?.let { MediaPlaybackManager.releaseSurface(it, texture) }
        output = null
    }
}

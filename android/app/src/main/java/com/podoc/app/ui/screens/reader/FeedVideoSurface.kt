package com.podoc.app.ui.screens.reader

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.podoc.app.service.MediaPlaybackManager

/** A texture participates in the pager's normal composition, clipping and movement. */
@Composable
internal fun FeedVideoSurface(articleId: Int, isActive: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { FeedTextureView(it) },
        update = { it.bind(if (isActive) articleId else null) },
        onRelease = { it.dispose() },
        modifier = modifier
    )
}

private class FeedTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var output: Surface? = null
    private var articleId: Int? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun bind(id: Int?) {
        articleId = id
        keepScreenOn = id != null
        output?.let { surface ->
            if (id != null) MediaPlaybackManager.attachSurface(surface, id)
            else MediaPlaybackManager.detachSurface(surface)
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        output = Surface(texture)
        bind(articleId)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        dispose()
        return true
    }

    fun dispose() {
        output?.let(MediaPlaybackManager::releaseSurface)
        output = null
    }
}

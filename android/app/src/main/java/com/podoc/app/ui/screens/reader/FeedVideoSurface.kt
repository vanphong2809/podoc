package com.podoc.app.ui.screens.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.podoc.app.service.MediaPlaybackManager
import kotlin.math.roundToInt

/** Each article owns a fitted native output and its first-frame shutter. */
@Composable
internal fun FeedVideoSurface(articleId: Int, isActive: Boolean, playbackGeneration: Long, modifier: Modifier = Modifier) {
    key(articleId) {
        AndroidView(
            factory = { FittedVideoOutput(it) },
            update = { it.bind(articleId, isActive, playbackGeneration) },
            onRelease = { it.dispose() },
            modifier = modifier
        )
    }
}

/**
 * The TextureView itself has the video's display aspect ratio. No full-screen
 * texture matrix is used: a delayed GPU update cannot stretch it into portrait.
 * The shutter and output are in the same native draw tree, so visibility cannot
 * overtake layout through a separate Compose recomposition.
 */
private class FittedVideoOutput(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context).apply { isOpaque = false }
    private val shutter = View(context).apply { setBackgroundColor(Color.BLACK) }
    private var output: Surface? = null
    private var articleId: Int? = null
    private var generation = 0L
    private var active = false
    private var videoSize = 0 to 0
    private var frameEpoch = -1L
    private var hasTextureFrame = false
    private var retiredTexture: SurfaceTexture? = null
    private var geometryVersion = 0L
    private var committedVersion = -1L
    private var pendingVersion: Long? = null
    private var disposed = false

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        clipToPadding = true
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(shutter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = this
    }

    fun bind(id: Int, isActive: Boolean, playbackGeneration: Long) {
        articleId = id
        active = isActive
        generation = playbackGeneration
        keepScreenOn = isActive
        syncSourceEpoch()
        output?.let { MediaPlaybackManager.registerSurface(it, id, active, generation, ::applyVideoSize) }
    }

    private fun syncSourceEpoch() {
        val id = articleId ?: return
        val epoch = MediaPlaybackManager.presentationEpoch(id)
        if (frameEpoch != epoch) {
            frameEpoch = epoch
            hasTextureFrame = false
            invalidateGeometry()
        }
    }

    private fun applyVideoSize(size: Pair<Int, Int>) {
        syncSourceEpoch()
        if (videoSize != size) {
            videoSize = size
            invalidateGeometry()
            requestLayout()
        }
        if (hasTextureFrame && committedVersion != geometryVersion) invalidate()
    }

    private fun invalidateGeometry() {
        geometryVersion += 1
        committedVersion = -1
        pendingVersion = null
        shutter.visibility = VISIBLE
        val id = articleId ?: return
        output?.let { MediaPlaybackManager.invalidateGeometry(it, id) }
        invalidate()
    }

    private fun fittedBounds(w: Int = width, h: Int = height): Rect {
        val (vw, vh) = videoSize
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return Rect(0, 0, w, h)
        val scale = minOf(w.toDouble() / vw, h.toDouble() / vh)
        val fittedWidth = (vw * scale).roundToInt().coerceIn(1, w)
        val fittedHeight = (vh * scale).roundToInt().coerceIn(1, h)
        val left = (w - fittedWidth) / 2
        val top = (h - fittedHeight) / 2
        return Rect(left, top, left + fittedWidth, top + fittedHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val bounds = fittedBounds(w, h)
        texture.measure(MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.EXACTLY))
        shutter.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val bounds = fittedBounds()
        if (texture.left != bounds.left || texture.top != bounds.top ||
            texture.width != bounds.width() || texture.height != bounds.height()) invalidateGeometry()
        texture.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
        shutter.layout(0, 0, width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateGeometry()
    }

    private fun hasFittedLayout(): Boolean {
        if (videoSize.first <= 0 || videoSize.second <= 0 || width <= 0 || height <= 0 || isLayoutRequested) return false
        val bounds = fittedBounds()
        return texture.left == bounds.left && texture.top == bounds.top &&
            texture.width == bounds.width() && texture.height == bounds.height()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // This runs after the fitted texture and its opaque shutter were drawn.
        if (disposed || !hasTextureFrame || !hasFittedLayout() || committedVersion == geometryVersion ||
            pendingVersion == geometryVersion) return
        val version = geometryVersion
        pendingVersion = version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated) {
            viewTreeObserver.registerFrameCommitCallback { post { finishFittedDraw(version) } }
            // A registration during dispatchDraw may belong to the next frame.
            postInvalidateOnAnimation()
        } else {
            // API 26–28 has no frame-commit callback. Keep the native shutter
            // across the fitted draw and the following rendering traversal.
            postOnAnimation {
                postInvalidateOnAnimation()
                postOnAnimation { finishFittedDraw(version) }
            }
        }
    }

    private fun finishFittedDraw(version: Long) {
        if (pendingVersion == version) pendingVersion = null
        if (disposed || !isAttachedToWindow || version != geometryVersion || !hasTextureFrame || !hasFittedLayout()) return
        val id = articleId ?: return
        val surface = output ?: return
        if (frameEpoch != MediaPlaybackManager.presentationEpoch(id) || videoSize != MediaPlaybackManager.surfaceSizeFor(id)) return
        committedVersion = version
        MediaPlaybackManager.geometryApplied(surface, id, videoSize)
        if (!MediaPlaybackManager.isGeometryReady(surface, id)) return
        // The preceding committed frame already had these exact native bounds.
        shutter.visibility = INVISIBLE
        MediaPlaybackManager.onSurfaceFrameAvailable(surface, id)
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        disposed = false
        retiredTexture = null
        hasTextureFrame = false
        output = Surface(surfaceTexture)
        invalidateGeometry()
        articleId?.let { bind(it, active, generation) }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        invalidate()
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        syncSourceEpoch()
        hasTextureFrame = true
        if (committedVersion == geometryVersion && hasFittedLayout()) {
            val id = articleId ?: return
            output?.let { MediaPlaybackManager.onSurfaceFrameAvailable(it, id) }
        } else invalidate()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        dispose(surfaceTexture)
        return false
    }

    override fun onDetachedFromWindow() {
        geometryVersion += 1
        pendingVersion = null
        super.onDetachedFromWindow()
    }

    fun dispose(surfaceTexture: SurfaceTexture? = texture.surfaceTexture) {
        if (surfaceTexture != null && retiredTexture === surfaceTexture) return
        retiredTexture = surfaceTexture
        disposed = true
        geometryVersion += 1
        pendingVersion = null
        output?.let { MediaPlaybackManager.releaseSurface(it, surfaceTexture) }
        output = null
        hasTextureFrame = false
    }
}

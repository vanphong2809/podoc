package com.podoc.app.ui.screens.reader

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.service.MediaPlaybackManager
import com.podoc.app.service.MediaPlaybackService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic local clips isolate pager/decoder regressions from server and network latency. */
@RunWith(AndroidJUnit4::class)
class VideoFeedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val frameTimes = CopyOnWriteArrayList<Long>()
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        frameTimes.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
    }
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ViewModelStore()
    private lateinit var viewModel: ReaderViewModel
    private val feed = (0..2).map { index ->
        ArticleListItem(
            id = 9_000_001 + index, title = "QA video $index", sourceType = "FILE",
            categoryId = -1, fileName = "feed_qa_$index.mp4", mimeType = "video/mp4"
        )
    }

    @Before fun prepare() {
        compose.activity.window.addOnFrameMetricsAvailableListener(frameListener, Handler(Looper.getMainLooper()))
        context.stopService(Intent(context, MediaPlaybackService::class.java))
        compose.waitUntil(5000) { MediaPlaybackManager.currentArticleId.value == null }
        val testContext = InstrumentationRegistry.getInstrumentation().context
        feed.forEachIndexed { index, item ->
            val asset = if (index == 1) "landscape.mp4" else "portrait.mp4"
            testContext.assets.open("video/$asset").use { input ->
                cachedFile(item).outputStream().use { input.copyTo(it) }
            }
        }
        viewModel = ReaderViewModel(ArticleRepository({ null }, context), DataStoreManager(context))
        store.put("reader", viewModel)
        MediaPlaybackManager._isRepeatMode.value = true
    }

    @After fun cleanUp() {
        compose.activity.window.removeOnFrameMetricsAvailableListener(frameListener)
        val frames = frameTimes.sorted()
        if (frames.isNotEmpty()) {
            Log.i("VideoFeedQA", "frames=${frames.size}, p95Ms=${frames[((frames.size - 1) * 0.95).toInt()] / 1_000_000}, over50Ms=${frames.count { it > 50_000_000 }}")
        }
        compose.runOnIdle { store.clear() }
        context.stopService(Intent(context, MediaPlaybackService::class.java))
        compose.waitUntil(5000) { MediaPlaybackManager.currentArticleId.value == null }
        MediaPlaybackManager._isRepeatMode.value = false
        feed.forEach { cachedFile(it).delete() }
        File(context.cacheDir, "feed_qa_audio.wav").delete()
    }

    @Test fun swipeBothDirectionsAndCancelledDragKeepPlaybackCorrect() {
        showFeed(feed.first(), feed)
        waitForVideo(feed[0])
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 180) }
        waitForVideo(feed[1])
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 180) }
        waitForVideo(feed[2])
        compose.onRoot().performTouchInput { swipeDown(durationMillis = 180) }
        waitForVideo(feed[1])

        // An incomplete drag must keep the original player running.
        compose.onRoot().performTouchInput {
            swipe(center, center + Offset(0f, -30f), durationMillis = 180)
        }
        waitForVideo(feed[1])
        // A user pause survives an incomplete drag and tap resumes the same player.
        compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil(5000) { !MediaPlaybackManager.isPlaying.value }
        compose.onRoot().performTouchInput {
            swipe(center, center + Offset(0f, -30f), durationMillis = 180)
        }
        compose.runOnIdle { assertFalse(MediaPlaybackManager.isPlaying.value) }
        compose.onRoot().performTouchInput { click(center) }
        waitForVideo(feed[1])
        repeat(3) {
            compose.onRoot().performTouchInput { swipeDown(durationMillis = 100) }
            waitForVideo(feed[0])
            compose.onRoot().performTouchInput { swipeUp(durationMillis = 100) }
            waitForVideo(feed[1])
        }
    }

    @Test fun lateFeedPreservesOpenedVideoAndNeighbourDisposalKeepsSurface() {
        val loadedFeed = mutableStateOf(emptyList<ArticleListItem>())
        val item = feed[1]
        val initial = detail(item)
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            TikTokVideoFeedViewer(initial, state.copy(videoFeed = loadedFeed.value), viewModel, {}, {})
        }
        waitForVideo(item)
        compose.runOnIdle { loadedFeed.value = feed }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        waitForVideo(item)
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 150) }
        waitForVideo(feed[2])
        compose.runOnIdle {
            assertEquals(feed[2].id, MediaPlaybackManager.videoSurface?.articleId)
            assertTrue(MediaPlaybackManager.videoSurface?.surface?.isValid == true)
        }
        compose.onRoot().performTouchInput { swipeDown(durationMillis = 150) }
        waitForVideo(item)
    }

    @Test fun audioControlsStillSeekCompleteReplayAndPause() {
        compose.setContent { }
        // Three seconds of silent 8 kHz PCM; no network or user audio is involved.
        val size = 8000 * 3 * 2
        val wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        val audio = File(context.cacheDir, "feed_qa_audio.wav").apply { writeBytes(wav) }
        MediaPlaybackManager._isRepeatMode.value = false
        MediaPlaybackManager.startPlay(context, audio.path, "QA audio", 9_100_001, false)
        compose.waitUntil(10000) { MediaPlaybackManager.isPlaying.value && MediaPlaybackManager.duration.value > 0 }
        MediaPlaybackManager.seekTo(context, MediaPlaybackManager.duration.value - 150)
        compose.waitUntil(10000) { MediaPlaybackManager._isCompleted.value && !MediaPlaybackManager.isPlaying.value }
        MediaPlaybackManager.replay(context)
        compose.waitUntil(5000) { MediaPlaybackManager.isPlaying.value && !MediaPlaybackManager._isCompleted.value }
        MediaPlaybackManager.togglePlayPause(context)
        compose.waitUntil(5000) { !MediaPlaybackManager.isPlaying.value }
        MediaPlaybackManager.togglePlayPause(context)
        compose.waitUntil(5000) { MediaPlaybackManager.isPlaying.value }
        MediaPlaybackManager.stop(context)
        MediaPlaybackManager.startPlay(context, audio.path, "QA audio reopened", 9_100_002, false)
        compose.waitUntil(10000) {
            MediaPlaybackManager.currentArticleId.value == 9_100_002 && MediaPlaybackManager.isPlaying.value
        }
    }

    private fun showFeed(item: ArticleListItem, items: List<ArticleListItem>) {
        val initial = detail(item)
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            TikTokVideoFeedViewer(initial, state.copy(videoFeed = items), viewModel, {}, {})
        }
    }

    private fun waitForVideo(item: ArticleListItem) {
        compose.waitUntil(15000) {
            MediaPlaybackManager.currentArticleId.value == item.id &&
                MediaPlaybackManager.isPlaying.value &&
                MediaPlaybackManager.renderedArticleId.value == item.id
        }
    }

    private fun cachedFile(item: ArticleListItem) = File(context.cacheDir, "podoc_doc_${item.id}_${item.fileName}")
    private fun detail(item: ArticleListItem) = ArticleDetail(
        id = item.id, title = item.title, categoryId = item.categoryId,
        sourceType = item.sourceType, fileName = item.fileName, mimeType = item.mimeType
    )
}

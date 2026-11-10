@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.podoc.app.ui.screens.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.api.PoDocApiService
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.service.MediaPlaybackManager
import com.podoc.app.service.MediaPlaybackService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
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
    private lateinit var fixtureServer: VideoFixtureServer
    private lateinit var dataStore: DataStoreManager
    private lateinit var originalSettings: Triple<String, String, String>
    private val allVideos = (0..15).map { index ->
        ArticleListItem(
            id = 9_000_001 + index, title = "QA video $index", sourceType = "FILE",
            categoryId = -1, fileName = "feed_qa_$index.mp4", mimeType = "video/mp4"
        )
    }
    private val feed get() = allVideos.take(3)

    @Before fun prepare() {
        compose.activity.window.addOnFrameMetricsAvailableListener(frameListener, Handler(Looper.getMainLooper()))
        context.stopService(Intent(context, MediaPlaybackService::class.java))
        compose.waitUntil(5000) { MediaPlaybackManager.currentArticleId.value == null }
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val portrait = testContext.assets.open("video/portrait-hd.mp4").use { it.readBytes() }
        val landscape = testContext.assets.open("video/landscape-hd.mp4").use { it.readBytes() }
        val longClip = testContext.assets.open("video/prefetch-long.mp4").use { it.readBytes() }
        val clips = allVideos.mapIndexed { index, item ->
            item.id to if (index == 10) longClip else if (index % 2 == 1) landscape else portrait
        }.toMap()
        fixtureServer = VideoFixtureServer(clips)
        dataStore = DataStoreManager(context)
        runBlocking {
            originalSettings = Triple(dataStore.serverUrlFlow.first(), dataStore.authTokenFlow.first(), dataStore.usernameFlow.first())
            dataStore.saveAuthData(fixtureServer.url, fixtureServer.requiredToken, "fixture-user")
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${fixtureServer.requiredToken}").build())
        }.build()
        val api = Retrofit.Builder().baseUrl(fixtureServer.url + "/").client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(PoDocApiService::class.java)
        viewModel = ReaderViewModel(ArticleRepository({ api }, context) { fixtureServer.url }, dataStore)
        store.put("reader", viewModel)
        compose.waitUntil(5000) { viewModel.getMediaPlaybackSource(context, feed[0]).startsWith(fixtureServer.url) }
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
        fixtureServer.close()
        runBlocking { dataStore.saveAuthData(originalSettings.first, originalSettings.second, originalSettings.third) }
        File(context.cacheDir, "feed_qa_audio.wav").delete()
    }

    @Test fun loadingShowsBlackAndSpinnerUntilRealTextureFrame() {
        val item = feed.first()
        fixtureServer.hold(item.id)
        val thumbnail = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            TikTokVideoFeedViewer(detail(item), state.copy(videoFeed = feed, videoThumbnails = mapOf(item.id to thumbnail)), viewModel, {}, {}, {})
        }
        compose.waitUntil(5000) { item.id in fixtureServer.requested }
        compose.onNodeWithTag("video_loading_${item.id}").assertIsDisplayed()
        compose.onNodeWithTag("video_shutter_${item.id}", useUnmergedTree = true).assertIsDisplayed()
        val image = compose.onRoot().captureToImage()
        val pixel = image.toPixelMap()[image.width / 4, image.height * 45 / 100]
        assertTrue("Loading must cover the thumbnail with black", pixel.red < 0.03f && pixel.green < 0.03f && pixel.blue < 0.03f)
        saveProof("video-loading", image.asAndroidBitmap())
        fixtureServer.release(item.id)
        waitForVideo(item)
        assertQuadrants(portrait = true)
        compose.onNodeWithTag("video_shutter_${item.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("video_loading_${item.id}").assertDoesNotExist()
        saveProof("video-portrait", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    @Test fun bufferingFlagCannotKeepLoadingVisibleOverPlayingVideo() {
        val item = feed.first()
        showFeed(item, feed)
        waitForVideo(item)
        // Reproduce a late/stuck buffering notification while frames are visible.
        compose.runOnIdle { MediaPlaybackManager._isBuffering.value = true }
        compose.onNodeWithTag("video_loading_${item.id}").assertDoesNotExist()
        compose.onNodeWithTag("video_shutter_${item.id}", useUnmergedTree = true).assertDoesNotExist()
        assertQuadrants(portrait = true)
        compose.runOnIdle {
            assertTrue(MediaPlaybackManager.isPlaying.value)
            assertTrue(MediaPlaybackManager.isBuffering.value)
        }
        saveProof("video-playing-with-buffering-flag", compose.onRoot().captureToImage().asAndroidBitmap())
        compose.runOnIdle { MediaPlaybackManager._isBuffering.value = false }
    }

    @Test fun swipeBothDirectionsAndCancelledDragKeepPlaybackCorrect() {
        showFeed(feed.first(), feed)
        waitForVideo(feed[0])
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 180) }
        waitForVideo(feed[1])
        assertQuadrants(portrait = false)
        saveProof("video-landscape", compose.onRoot().captureToImage().asAndroidBitmap())
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

    @Test fun reselectingVideoKeepsArticleSurfaceAndIgnoresStaleCallbacks() {
        showFeed(feed.first(), feed)
        waitForVideo(feed[0])
        compose.waitUntil(10000) {
            compose.runOnIdle { MediaPlaybackManager.serviceInstance?.preparedVideoId() == feed[1].id }
        }
        val firstSurface = MediaPlaybackManager.videoSurface!!
        val firstRequests = fixtureServer.requestCounts[feed[0].id]?.get() ?: 0L
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 100) }
        waitForVideo(feed[1])
        val secondSurface = MediaPlaybackManager.videoSurface!!
        compose.runOnIdle {
            assertTrue("The adjacent video must use its already prepared native player",
                MediaPlaybackManager.serviceInstance?.lastSelectionUsedPreparedVideo == true)
            assertNotSame(firstSurface.surface, secondSurface.surface)
            MediaPlaybackManager.attachSurface(firstSurface.surface, firstSurface.articleId, firstSurface.generation)
            MediaPlaybackManager.onSurfaceFrameAvailable(firstSurface.surface, firstSurface.articleId)
            assertSame(secondSurface, MediaPlaybackManager.videoSurface)
        }
        assertQuadrants(portrait = false)
        compose.onRoot().performTouchInput { swipeDown(durationMillis = 100) }
        waitForVideo(feed[0])
        compose.runOnIdle {
            assertNotSame(secondSurface.surface, MediaPlaybackManager.videoSurface!!.surface)
            assertEquals(MediaPlaybackManager.playbackGeneration.value, MediaPlaybackManager.videoSurface!!.generation)
        }
        assertQuadrants(portrait = true)
        assertEquals("Revisiting a cached video must not download its bytes again", firstRequests,
            fixtureServer.requestCounts[feed[0].id]?.get() ?: 0L)
    }

    @Test fun firstVideoWaitsForAllElevenOpenings() {
        val far = allVideos[10]
        fixtureServer.hold(far.id)
        showFeed(allVideos[5], allVideos)
        compose.waitUntil(30_000) { far.id in fixtureServer.requested }
        compose.onNodeWithTag("video_preparation", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            assertNull("The first video must not start before the full window is warm", MediaPlaybackManager.currentArticleId.value)
        }
        saveProof("video-warming-eleven", compose.onRoot().captureToImage().asAndroidBitmap())
        fixtureServer.release(far.id)
        waitForVideo(allVideos[5])
        assertTrue(fixtureServer.sourceBatches.first().containsAll(allVideos.take(11).map { it.id }))
        assertTrue(fixtureServer.requested.containsAll(allVideos.take(11).map { it.id }))
        compose.onNodeWithTag("video_preparation", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun slidingWindowPreparesTwelveThroughSixteenBeforeTheyAreSelected() {
        showFeed(allVideos[5], allVideos)
        waitForVideo(allVideos[5])
        val longId = allVideos[10].id
        compose.waitUntil(30_000) { (MediaPlaybackManager.videoFeedPreloader?.cachedDurationMs(longId) ?: 0L) >= 2_000 }
        val cached = MediaPlaybackManager.videoFeedPreloader!!.cachedBytes(longId)
        val downloaded = fixtureServer.bytesSent[longId]?.get() ?: 0L
        Log.i("VideoFeedQA", "PREFETCH BYTES | source_bytes=6521155 | cached_bytes=$cached | wire_bytes=$downloaded")
        assertTrue("Only an opening should be cached for a far video", cached in 1..3_260_577)
        assertTrue("Preloading must not download the entire 6.5 MB fixture", downloaded < 3_260_577)
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 150) }
        waitForVideo(allVideos[6])
        compose.waitUntil(20_000) { allVideos[11].id in fixtureServer.requested }
        for (index in 7..10) {
            compose.onRoot().performTouchInput { swipeUp(durationMillis = 150) }
            waitForVideo(allVideos[index])
        }
        compose.waitUntil(20_000) { allVideos[15].id in fixtureServer.requested }
        compose.runOnIdle { assertEquals(allVideos[10].id, MediaPlaybackManager.currentArticleId.value) }
        // Video twelve has been cached since the selected position was seven.
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 150) }
        waitForVideo(allVideos[11])
        saveProof("video-after-twelve", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    @Test fun lateFeedPreservesOpenedVideoAndNeighbourDisposalKeepsSurface() {
        val loadedFeed = mutableStateOf(emptyList<ArticleListItem>())
        val item = feed[1]
        val initial = detail(item)
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            TikTokVideoFeedViewer(initial, state.copy(videoFeed = loadedFeed.value), viewModel, {}, {}, {})
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
            TikTokVideoFeedViewer(initial, state.copy(videoFeed = items), viewModel, {}, {}, {})
        }
    }

    private fun waitForVideo(item: ArticleListItem) {
        compose.waitUntil(15000) {
            MediaPlaybackManager.currentArticleId.value == item.id &&
                MediaPlaybackManager.isPlaying.value &&
                MediaPlaybackManager.renderedArticleId.value == item.id
        }
    }

    private fun saveProof(name: String, bitmap: Bitmap) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (output?.let { File(it) } ?: File(context.getExternalFilesDir(null), "video-qa")).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertQuadrants(portrait: Boolean) {
        val image = compose.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val ratio = if (portrait) 1080f / 1920 else 1920f / 1080
        val width = minOf(image.width.toFloat(), image.height * ratio)
        val height = width / ratio
        val left = (image.width - width) / 2
        val top = (image.height - height) / 2
        val expected = listOf(
            Triple(0.25f, 0.25f, floatArrayOf(0.88f, 0.125f, 0.125f)),
            Triple(0.75f, 0.25f, floatArrayOf(0.125f, 0.69f, 0.31f)),
            Triple(0.25f, 0.75f, floatArrayOf(0.125f, 0.31f, 0.82f)),
            Triple(0.75f, 0.75f, floatArrayOf(0.88f, 0.75f, 0.125f))
        )
        expected.forEach { (x, y, rgb) ->
            val pixel = pixels[(left + x * width).toInt(), (top + y * height).toInt()]
            assertEquals("red at $x,$y", rgb[0], pixel.red, 0.12f)
            assertEquals("green at $x,$y", rgb[1], pixel.green, 0.12f)
            assertEquals("blue at $x,$y", rgb[2], pixel.blue, 0.12f)
        }
    }
    private fun detail(item: ArticleListItem) = ArticleDetail(
        id = item.id, title = item.title, categoryId = item.categoryId,
        sourceType = item.sourceType, fileName = item.fileName, mimeType = item.mimeType
    )
}

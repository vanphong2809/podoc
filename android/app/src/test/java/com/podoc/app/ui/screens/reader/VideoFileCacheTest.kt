package com.podoc.app.ui.screens.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class VideoFileCacheTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun playbackAndPrefetchShareOneCompleteDownload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = VideoFileCache(scope)
            val file = File(directory.root, "video.mp4")
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val download: suspend (File) -> Unit = { partial ->
                calls.incrementAndGet()
                partial.writeText("first")
                started.complete(Unit)
                finish.await()
                partial.appendText("-last")
            }
            val prefetch = async { cache.get(file, download) }
            withTimeout(5000) { started.await() }
            assertFalse("A partially written video must never be playable", file.exists())
            val playback = async { cache.get(file, download) }
            finish.complete(Unit)
            assertEquals(prefetch.await(), playback.await())
            assertEquals("first-last", file.readText())
            assertEquals(1, calls.get())
            assertFalse(File(file.path + ".part").exists())
        } finally {
            scope.cancel()
        }
    }

    @Test fun failedDownloadIsRemovedAndCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = VideoFileCache(scope)
            val file = File(directory.root, "failed.mp4")
            try {
                cache.get(file) { partial ->
                    partial.writeText("truncated")
                    throw IOException("connection lost")
                }
                fail("Expected download failure")
            } catch (_: IOException) {
                assertFalse(file.exists())
                assertFalse(File(file.path + ".part").exists())
            }
            cache.get(file) { it.writeText("complete") }
            assertEquals("complete", file.readText())
        } finally {
            scope.cancel()
        }
    }

    @Test fun swipingAwayCancelsTheWaiterAndPreservesSharedPrefetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = VideoFileCache(scope)
            val file = File(directory.root, "slow.mp4")
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var stalePlaybackStarted = false
            val oldPage = async {
                cache.get(file) { partial ->
                    started.complete(Unit)
                    finish.await()
                    partial.writeText("complete")
                }
                stalePlaybackStarted = true
            }
            withTimeout(5000) { started.await() }
            oldPage.cancelAndJoin()
            finish.complete(Unit)
            val nextVisit = cache.get(file) { error("Must reuse the in-flight prefetch") }
            assertEquals("complete", nextVisit.readText())
            assertFalse(stalePlaybackStarted)
        } finally {
            scope.cancel()
        }
    }

    @Test fun existingCompleteFileNeedsNoNetwork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = directory.newFile("cached.mp4").apply { writeText("complete") }
            assertEquals(file, VideoFileCache(scope).get(file) { error("Unexpected network call") })
        } finally {
            scope.cancel()
        }
    }
}

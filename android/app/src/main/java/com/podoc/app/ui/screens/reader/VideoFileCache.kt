package com.podoc.app.ui.screens.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Shares downloads between playback and prefetch, publishing only complete files. */
internal class VideoFileCache(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val requests = mutableMapOf<String, Deferred<File>>()

    suspend fun get(file: File, download: suspend (File) -> Unit): File {
        val request = mutex.withLock {
            requests.getOrPut(file.absolutePath) {
                scope.async(Dispatchers.IO) {
                    if (file.isFile && file.length() > 0) return@async file
                    val partial = File(file.path + ".part")
                    try {
                        download(partial)
                        check(partial.length() > 0) { "Tệp video trống" }
                        check(partial.renameTo(file)) { "Không thể lưu video vào bộ nhớ đệm" }
                        file
                    } finally {
                        partial.delete()
                    }
                }
            }
        }
        try {
            // A cancelled page stops waiting; the shared prefetch can still finish.
            return request.await()
        } finally {
            mutex.withLock {
                if (request.isCompleted && requests[file.absolutePath] === request) {
                    requests.remove(file.absolutePath)
                }
            }
        }
    }
}

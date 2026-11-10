package com.podoc.app.ui.screens.reader

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Local HTTP/Range fixtures exercise the same streaming path as production. */
internal class VideoFixtureServer(private val clips: Map<Int, ByteArray>) : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var sourceGate: CountDownLatch? = null
    @Volatile var sourceDelayMs = 0L
    private val gates = ConcurrentHashMap<Int, CountDownLatch>()
    private val revisions = ConcurrentHashMap<Int, Int>()
    private fun revision(id: Int): String = MessageDigest.getInstance("MD5").run {
        update(clips.getValue(id)); update((revisions[id] ?: 0).toString().toByteArray())
        digest().joinToString("") { "%02x".format(it) }
    }
    fun replaceRevision(id: Int) { revisions[id] = (revisions[id] ?: 0) + 1 }
    private val failures = ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
    val bytesSent = ConcurrentHashMap<Int, AtomicLong>()
    val requestCounts = ConcurrentHashMap<Int, AtomicLong>()
    val sourceBatches = java.util.concurrent.CopyOnWriteArrayList<List<Int>>()
    val requiredToken = "fixture-token"
    val requested = ConcurrentHashMap.newKeySet<Int>()
    val url = "http://127.0.0.1:${socket.localPort}"

    init {
        executor.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                executor.execute serve@{
                    client.use {
                        try {
                            client.soTimeout = 5000
                            val reader = client.getInputStream().bufferedReader()
                            val request = reader.readLine() ?: return@serve
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            val output = client.getOutputStream()
                            if (headers["authorization"] != "Bearer $requiredToken") {
                                output.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                return@serve
                            }
                            if (request.startsWith("POST /api/articles/playback-sources")) {
                                val body = CharArray(headers["content-length"]!!.toInt())
                                var read = 0
                                while (read < body.size) { val n = reader.read(body, read, body.size - read); if (n < 0) break; read += n }
                                val ids = JSONObject(String(body)).getJSONArray("ids")
                                val items = JSONArray()
                                val missing = JSONArray()
                                val batch = (0 until ids.length()).map { ids.getInt(it) }
                                sourceBatches.add(batch)
                                sourceGate?.await()
                                if (sourceDelayMs > 0) Thread.sleep(sourceDelayMs)
                                batch.forEach { id ->
                                    val bytes = clips[id]
                                    if (bytes == null) missing.put(id)
                                    else {
                                        val rev = revision(id)
                                        items.put(JSONObject().put("id", id).put("variant", "optimized").put("revision", rev)
                                            .put("url", "/api/articles/$id/file?inline=true&variant=optimized&revision=$rev")
                                            .put("mime_type", "video/mp4").put("file_size_bytes", bytes.size))
                                    }
                                }
                                val bytes = JSONObject().put("items", items).put("unavailable_ids", missing).toString().toByteArray()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                output.write(bytes); output.flush()
                                return@serve
                            }
                            val id = Regex("/api/articles/(\\d+)/file").find(request)?.groupValues?.get(1)?.toIntOrNull() ?: return@serve
                            val bytes = clips[id] ?: return@serve
                            requestCounts.getOrPut(id) { AtomicLong() }.incrementAndGet()
                            requested.add(id)
                            if ((failures[id]?.getAndUpdate { (it - 1).coerceAtLeast(0) } ?: 0) > 0) {
                                output.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                output.flush()
                                return@serve
                            }
                            val pinned = Regex("revision=([a-f0-9]+)").find(request)?.groupValues?.get(1)
                            if (pinned != null && pinned != revision(id)) {
                                output.write("HTTP/1.1 412 Precondition Failed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                output.flush()
                                return@serve
                            }
                            gates[id]?.await()
                            val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                            val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val end = (range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                            if (start > end || start !in bytes.indices) {
                                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${bytes.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val status = if (range == null) "200 OK" else "206 Partial Content"
                                val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${bytes.size}\r\n"
                                output.write(("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
                                    "Content-Length: ${end - start + 1}\r\n${contentRange}Connection: close\r\n\r\n").toByteArray())
                                var offset = start
                                while (offset <= end) {
                                    val count = minOf(8192, end - offset + 1)
                                    output.write(bytes, offset, count)
                                    output.flush()
                                    bytesSent.getOrPut(id) { AtomicLong() }.addAndGet(count.toLong())
                                    offset += count
                                    if (bytes.size > 1_000_000) Thread.sleep(1)
                                }
                            }
                            output.flush()
                        } catch (_: Exception) {
                            // A swipe cancels obsolete requests; close() interrupts held ones.
                        }
                    }
                }
            }
        }
    }

    fun holdSources() { sourceGate = CountDownLatch(1) }
    fun releaseSources() { sourceGate?.countDown(); sourceGate = null }
    fun failNextRequests(id: Int, count: Int) { failures[id] = java.util.concurrent.atomic.AtomicInteger(count) }
    fun hold(id: Int) { gates[id] = CountDownLatch(1) }
    fun release(id: Int) { gates.remove(id)?.countDown() }

    override fun close() {
        socket.close()
        releaseSources()
        gates.values.forEach { it.countDown() }
        executor.shutdownNow()
    }
}

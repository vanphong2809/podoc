package com.podoc.app.ui.screens.reader

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
    private val gates = ConcurrentHashMap<Int, CountDownLatch>()
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
                            val id = Regex("/api/articles/(\\d+)/file").find(request)?.groupValues?.get(1)?.toIntOrNull() ?: return@serve
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            val bytes = clips[id] ?: return@serve
                            requested.add(id)
                            gates[id]?.await()
                            val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                            val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val end = (range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                            val output = client.getOutputStream()
                            if (start > end || start !in bytes.indices) {
                                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${bytes.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val status = if (range == null) "200 OK" else "206 Partial Content"
                                val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${bytes.size}\r\n"
                                output.write(("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
                                    "Content-Length: ${end - start + 1}\r\n${contentRange}Connection: close\r\n\r\n").toByteArray())
                                output.write(bytes, start, end - start + 1)
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

    fun hold(id: Int) { gates[id] = CountDownLatch(1) }
    fun release(id: Int) { gates.remove(id)?.countDown() }

    override fun close() {
        socket.close()
        gates.values.forEach { it.countDown() }
        executor.shutdownNow()
    }
}

package com.podoc.app.ui.screens.reader

import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Quản lý bộ nhớ đệm tệp hình ảnh, đảm bảo:
 * - Chia sẻ download giữa trang đang xem và các trang lân cận (không bao giờ tải trùng).
 * - Sử dụng file tạm (.part) và atomic rename để đảm bảo chỉ xuất bản tệp tin hoàn chỉnh 100%.
 * - Tự động phát hiện và xoá bỏ các file ảnh bị lỗi, cắt cụt (truncated) do download dở dang.
 */
internal class ImageFileCache(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val requests = mutableMapOf<String, Deferred<File>>()

    private fun isFileCompleteImage(file: File, isSvg: Boolean): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        if (isSvg) {
            return try {
                file.bufferedReader().use { r ->
                    val head = CharArray(256)
                    val n = r.read(head)
                    n > 0 && String(head, 0, n).contains("<")
                }
            } catch (_: Exception) { false }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    suspend fun get(file: File, isSvg: Boolean, download: suspend (File) -> Unit): File {
        val request = mutex.withLock {
            requests.getOrPut(file.absolutePath) {
                scope.async(Dispatchers.IO) {
                    if (file.isFile && file.length() > 0) {
                        if (isFileCompleteImage(file, isSvg)) {
                            return@async file
                        } else {
                            // File cũ trên máy bị lỗi/cắt cụt từ trước -> Xoá ngay để tải lại file chuẩn
                            file.delete()
                        }
                    }
                    val partial = File(file.path + ".part")
                    try {
                        if (partial.exists()) partial.delete()
                        download(partial)
                        check(partial.isFile && partial.length() > 0) { "Tệp ảnh tải về trống" }
                        check(isFileCompleteImage(partial, isSvg)) { "Tệp ảnh tải về bị lỗi cấu trúc" }
                        if (file.exists()) file.delete()
                        check(partial.renameTo(file)) { "Không thể lưu ảnh vào bộ nhớ đệm" }
                        file
                    } finally {
                        if (partial.exists()) {
                            partial.delete()
                        }
                    }
                }
            }
        }
        try {
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


package com.podoc.app.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import android.webkit.MimeTypeMap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import com.podoc.app.data.api.PoDocApiService
import com.podoc.app.data.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class StreamingUriRequestBody(
    private val contentResolver: android.content.ContentResolver,
    private val uri: Uri,
    private val mimeType: String?,
    private val size: Long,
    private val onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
) : RequestBody() {
    override fun contentType(): MediaType? = mimeType?.toMediaTypeOrNull()

    // Sử dụng -1L để OkHttp truyền chunked streaming an toàn, tránh lỗi ProtocolException
    // khi OpenableColumns.SIZE trên Android bị lệch so với byte thực tế trong stream
    override fun contentLength(): Long = -1L

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("Không thể đọc tệp tin: $uri")
        inputStream.use { stream ->
            val buffer = ByteArray(64 * 1024)
            var bytesWritten = 0L
            var read: Int
            var lastUpdate = 0L
            while (stream.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)
                bytesWritten += read
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 300) {
                    lastUpdate = now
                    onProgress?.invoke(bytesWritten, size)
                }
            }
            onProgress?.invoke(bytesWritten, size)
        }
    }
}

class ArticleRepository(
    private val apiServiceProvider: () -> PoDocApiService?,
    private val context: Context,
    private val serverUrlProvider: () -> String = { "" }
) {
    private fun getApi(): PoDocApiService =
        apiServiceProvider() ?: throw IllegalStateException("Chưa kết nối tới máy chủ API")

    private val durationPrefs by lazy {
        context.getSharedPreferences("podoc_video_durations", Context.MODE_PRIVATE)
    }
    private val memoryDurationCache = ConcurrentHashMap<String, Float>()
    private val unresolvableDurationKeys = ConcurrentHashMap.newKeySet<String>()

    private fun durationCacheKey(articleId: Int): String =
        "${serverUrlProvider().trimEnd('/')}:$articleId"

    fun getCachedVideoDuration(articleId: Int): Float? {
        val key = durationCacheKey(articleId)
        memoryDurationCache[key]?.let { return it }
        val stored = durationPrefs.getFloat(key, -1f)
        if (stored > 0f) {
            memoryDurationCache[key] = stored
            return stored
        }
        return null
    }

    fun cacheVideoDuration(articleId: Int, durationSeconds: Float) {
        if (durationSeconds <= 0f) return
        val key = durationCacheKey(articleId)
        unresolvableDurationKeys.remove(key)
        memoryDurationCache[key] = durationSeconds
        durationPrefs.edit().putFloat(key, durationSeconds).apply()
    }

    fun isVideoArticle(item: ArticleListItem): Boolean {
        val lower = (item.fileName ?: item.filePath ?: "").lowercase()
        return item.mimeType?.startsWith("video/") == true ||
            listOf(".mp4", ".mov", ".mkv", ".webm", ".avi", ".3gp", ".ts", ".flv", ".m4v")
                .any { lower.endsWith(it) }
    }

    private fun parseDurationFormattedSeconds(formatted: String?): Float? {
        if (formatted.isNullOrBlank()) return null
        val parts = formatted.trim().split(":")
        return try {
            when (parts.size) {
                2 -> (parts[0].toLong() * 60 + parts[1].toLong()).toFloat()
                3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()).toFloat()
                else -> null
            }?.takeIf { it > 0f }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Fetches duration via /file-info for video items whose list payload omitted duration_seconds
     * (e.g. while backend is on an older version or ffprobe backfill has not finished yet).
     */
    suspend fun hydrateMissingVideoDurations(
        items: List<ArticleListItem>,
        onDurationResolved: (articleId: Int, durationSeconds: Float) -> Unit
    ) {
        val missing = items.filter { item ->
            isVideoArticle(item) &&
                (item.durationSeconds == null || item.durationSeconds <= 0f) &&
                !unresolvableDurationKeys.contains(durationCacheKey(item.id))
        }
        if (missing.isEmpty()) return

        val semaphore = Semaphore(3)
        coroutineScope {
            missing.forEach { item ->
                val cached = getCachedVideoDuration(item.id)
                if (cached != null) {
                    onDurationResolved(item.id, cached)
                    return@forEach
                }
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val key = durationCacheKey(item.id)
                        val result = getArticleFileInfo(item.id)
                        result.onSuccess { info ->
                            val resolved = info.durationSeconds?.takeIf { it > 0f }
                                ?: parseDurationFormattedSeconds(info.durationFormatted)
                            if (resolved != null) {
                                cacheVideoDuration(item.id, resolved)
                                onDurationResolved(item.id, resolved)
                            } else {
                                unresolvableDurationKeys.add(key)
                            }
                        }.onFailure { err ->
                            if (err is CancellationException) throw err
                        }
                    }
                }
            }
        }
    }

    suspend fun getVideoPlaybackSources(ids: List<Int>): VideoPlaybackResponse = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.withTimeout(15_000) {
                val unique = ids.distinct()
                require(unique.isNotEmpty() && unique.size <= 21)
                getApi().getVideoPlaybackSources(VideoPlaybackRequest(unique))
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("Không thể lấy nguồn video. Kiểm tra kết nối và thử lại.")
        }
    }

    private fun resolveImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = serverUrlProvider().trimEnd('/')
        return if (base.isNotBlank()) "$base${if (url.startsWith("/")) "" else "/"}$url" else url
    }

    suspend fun getArticles(
        query: String? = null,
        categoryId: Int? = null,
        sourceType: String? = null,
        contentType: String? = null,
        favorite: Boolean? = null,
        sort: String? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): Result<List<ArticleListItem>> {
        return try {
            val list = getApi().getArticles(
                query = query?.ifBlank { null },
                categoryId = categoryId,
                sourceType = sourceType?.ifBlank { null },
                contentType = contentType?.ifBlank { null },
                favorite = favorite,
                sort = sort,
                page = page,
                pageSize = pageSize
            )
            val mapped = list.map { item ->
                val lower = (item.fileName ?: item.filePath ?: "").lowercase()
                val isMedia = item.mimeType?.startsWith("image/") == true ||
                        item.mimeType?.startsWith("video/") == true ||
                        listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp", ".mp4", ".mov", ".mkv", ".webm", ".avi", ".3gp").any { lower.endsWith(it) }
                val finalUrl = if (item.sourceType == "FILE" && isMedia) {
                    resolveImageUrl("/api/articles/${item.id}/thumbnail")
                } else {
                    resolveImageUrl(item.imageUrl)
                }
                val resolvedDuration = when {
                    item.durationSeconds != null && item.durationSeconds > 0f -> {
                        cacheVideoDuration(item.id, item.durationSeconds)
                        item.durationSeconds
                    }
                    isVideoArticle(item) -> getCachedVideoDuration(item.id)
                    else -> item.durationSeconds
                }
                item.copy(imageUrl = finalUrl, durationSeconds = resolvedDuration)
            }
            Result.success(mapped)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun getArticleDetail(id: Int): Result<ArticleDetail> {
        return try {
            val detail = getApi().getArticleDetail(id)
            val lower = (detail.fileName ?: detail.filePath ?: "").lowercase()
            val isMedia = detail.mimeType?.startsWith("image/") == true ||
                    detail.mimeType?.startsWith("video/") == true ||
                    listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp", ".mp4", ".mov", ".mkv", ".webm", ".avi", ".3gp").any { lower.endsWith(it) }
            val finalUrl = if (detail.sourceType == "FILE" && isMedia) {
                resolveImageUrl("/api/articles/${detail.id}/thumbnail")
            } else {
                resolveImageUrl(detail.imageUrl)
            }
            val resolvedDuration = detail.durationSeconds?.takeIf { it > 0f }
                ?.also { cacheVideoDuration(detail.id, it) }
                ?: getCachedVideoDuration(detail.id)
            Result.success(detail.copy(imageUrl = finalUrl, durationSeconds = resolvedDuration))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun getArticleFileInfo(id: Int): Result<ArticleFileInfo> = withContext(Dispatchers.IO) {
        try {
            val info = getApi().getArticleFileInfo(id)
            val resolved = info.durationSeconds?.takeIf { it > 0f }
                ?: parseDurationFormattedSeconds(info.durationFormatted)
            if (resolved != null) {
                cacheVideoDuration(id, resolved)
            }
            Result.success(info)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun extractUrl(url: String): Result<ArticleDraft> {
        return try {
            val draft = getApi().extractUrl(ExtractUrlRequest(url.trim()))
            Result.success(draft)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun extractFile(uri: Uri): Result<ArticleDraft> {
        return try {
            val contentResolver = context.contentResolver
            val fileName = queryFileName(uri) ?: "document"
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IllegalStateException("Không thể đọc tệp tin"))

            val requestFile = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", fileName, requestFile)

            val draft = getApi().extractFile(filePart)
            Result.success(
                draft.copy(
                    fileName = draft.fileName ?: fileName,
                    mimeType = draft.mimeType ?: mimeType,
                    filePath = draft.filePath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadFile(
        uri: Uri,
        categoryId: Int? = null,
        customTitle: String? = null,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<ArticleDetail> {
        return try {
            val contentResolver = context.contentResolver
            val (queriedName, fileSize) = queryFileInfo(uri)
            var fileName = queriedName ?: "file_${System.currentTimeMillis()}"
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

            // Đảm bảo tên file luôn có phần mở rộng nếu ContentResolver trả về tên không đuôi
            if (!fileName.contains(".")) {
                val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                if (!extFromMime.isNullOrBlank()) {
                    fileName = "$fileName.$extFromMime"
                } else if (mimeType.startsWith("image/")) {
                    val fallbackExt = when (mimeType) {
                        "image/jpeg" -> "jpg"
                        "image/png" -> "png"
                        "image/webp" -> "webp"
                        "image/svg+xml" -> "svg"
                        "image/gif" -> "gif"
                        else -> "jpg"
                    }
                    fileName = "$fileName.$fallbackExt"
                } else if (mimeType.startsWith("video/")) {
                    val fallbackExt = when (mimeType) {
                        "video/mp4" -> "mp4"
                        "video/webm" -> "webm"
                        "video/x-matroska" -> "mkv"
                        "video/quicktime" -> "mov"
                        "video/x-msvideo" -> "avi"
                        "video/3gpp" -> "3gp"
                        else -> "mp4"
                    }
                    fileName = "$fileName.$fallbackExt"
                }
            }

            val lowerName = fileName.lowercase()
            val isImage = mimeType.startsWith("image/") || lowerName.endsWith(".jpg") ||
                    lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") ||
                    lowerName.endsWith(".webp") || lowerName.endsWith(".gif") || lowerName.endsWith(".svg")

            val isVideo = mimeType.startsWith("video/") || lowerName.endsWith(".mp4") ||
                    lowerName.endsWith(".mkv") || lowerName.endsWith(".mov") ||
                    lowerName.endsWith(".webm") || lowerName.endsWith(".avi") ||
                    lowerName.endsWith(".3gp") || lowerName.endsWith(".ts") ||
                    lowerName.endsWith(".flv") || lowerName.endsWith(".m4v")

            val isAudio = mimeType.startsWith("audio/") || lowerName.endsWith(".mp3") ||
                    lowerName.endsWith(".wav") || lowerName.endsWith(".m4a") ||
                    lowerName.endsWith(".aac") || lowerName.endsWith(".flac") ||
                    lowerName.endsWith(".ogg") || lowerName.endsWith(".wma") ||
                    lowerName.endsWith(".opus")

            val durationSec = if (isVideo || isAudio) queryMediaDurationSeconds(uri) else null

            // Tạo tên tệp ASCII hợp lệ cho Header Content-Disposition để tránh lỗi OkHttp Unexpected char
            val safeHeaderFileName = buildString {
                for (ch in fileName) {
                    if (ch.code in 32..126 && ch != '"' && ch != '\\' && ch != ';') {
                        append(ch)
                    } else {
                        append('_')
                    }
                }
            }.trim().let { if (it.isBlank() || it.all { c -> c == '_' || c == '.' }) "upload_${System.currentTimeMillis()}.${fileName.substringAfterLast('.', "bin")}" else it }

            val thumbHeaderName = "thumb_${safeHeaderFileName.substringBeforeLast('.')}.jpg"

            var thumbnailPart: MultipartBody.Part? = null
            if (isVideo) {
                var thumbBitmap: Bitmap? = null
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            thumbBitmap = context.contentResolver.loadThumbnail(uri, Size(480, 480), null)
                        } catch (_: Throwable) {}
                    }
                    if (thumbBitmap == null) {
                        var retriever: MediaMetadataRetriever? = null
                        try {
                            retriever = MediaMetadataRetriever()
                            retriever.setDataSource(context, uri)
                            thumbBitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.frameAtTime
                        } catch (_: Throwable) {
                        } finally {
                            try { retriever?.release() } catch (_: Throwable) {}
                        }
                    }
                    if (thumbBitmap != null) {
                        val maxDim = 480
                        val width = thumbBitmap.width
                        val height = thumbBitmap.height
                        val scale = if (width > maxDim || height > maxDim) {
                            maxDim.toFloat() / maxOf(width, height)
                        } else 1f
                        val scaledBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(thumbBitmap, (width * scale).toInt(), (height * scale).toInt(), true)
                        } else thumbBitmap
                        val baos = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        if (scaledBitmap !== thumbBitmap) {
                            scaledBitmap.recycle()
                        }
                        val thumbBytes = baos.toByteArray()
                        val thumbBody = thumbBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        thumbnailPart = MultipartBody.Part.createFormData("thumbnail", thumbHeaderName, thumbBody)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try {
                        thumbBitmap?.recycle()
                    } catch (_: Exception) {}
                }
            } else if (isImage) {
                var bitmap: Bitmap? = null
                var scaledBitmap: Bitmap? = null
                try {
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, boundsOptions)
                    }
                    val origW = boundsOptions.outWidth
                    val origH = boundsOptions.outHeight
                    val maxDim = 1280
                    var sampleSize = 1
                    while (origW / (sampleSize * 2) >= maxDim && origH / (sampleSize * 2) >= maxDim) {
                        sampleSize *= 2
                    }
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    bitmap = contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, decodeOptions)
                    }
                    if (bitmap != null) {
                        val scale = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                            maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                        } else 1f
                        scaledBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        } else bitmap
                        val baos = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        val thumbBytes = baos.toByteArray()
                        val thumbBody = thumbBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        thumbnailPart = MultipartBody.Part.createFormData("thumbnail", thumbHeaderName, thumbBody)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try {
                        if (scaledBitmap != null && scaledBitmap !== bitmap) {
                            scaledBitmap.recycle()
                        }
                        bitmap?.recycle()
                    } catch (_: Exception) {}
                }
            }

            val requestFile = StreamingUriRequestBody(contentResolver, uri, mimeType, fileSize, onProgress)
            val filePart = MultipartBody.Part.createFormData("file", safeHeaderFileName, requestFile)

            val catBody = categoryId?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
            val titleBody = (customTitle ?: fileName).toRequestBody("text/plain".toMediaTypeOrNull())
            val fileNameBody = fileName.toRequestBody("text/plain".toMediaTypeOrNull())
            val durationBody = durationSec?.let {
                String.format(java.util.Locale.US, "%.1f", it).toRequestBody("text/plain".toMediaTypeOrNull())
            }

            val article = try {
                getApi().uploadArticleFile(filePart, thumbnailPart, catBody, titleBody, fileNameBody, durationBody)
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 413) {
                    val errDetail = try {
                        val body = e.response()?.errorBody()?.string()
                        if (!body.isNullOrBlank() && body.contains("detail")) {
                            org.json.JSONObject(body).optString("detail", "")
                        } else ""
                    } catch (_: Exception) { "" }
                    val msg = if (errDetail.isNotBlank()) errDetail else "Cổng máy chủ từ chối dung lượng tệp (413). Tệp chưa được lưu; cần tăng giới hạn upload trên máy chủ."
                    throw IllegalArgumentException(msg)
                } else if (e.code() == 405 || e.code() == 404) {
                    // Fallback cho backend chưa cập nhật route /api/articles/upload
                    val fallbackFilePart = MultipartBody.Part.createFormData("file", safeHeaderFileName, requestFile)
                    val draft = getApi().extractFile(fallbackFilePart)
                    val createReq = CreateArticleRequest(
                        title = customTitle ?: draft.title,
                        sourceType = "FILE",
                        fileName = draft.fileName ?: fileName,
                        filePath = draft.filePath,
                        mimeType = draft.mimeType ?: mimeType,
                        contentMarkdown = draft.contentMarkdown,
                        excerpt = draft.excerpt,
                        author = draft.author,
                        siteName = draft.siteName,
                        imageUrl = draft.imageUrl,
                        readingMinutes = draft.readingMinutes,
                        categoryId = categoryId ?: 1
                    )
                    getApi().createArticle(createReq)
                } else {
                    throw e
                }
            }

            // Lưu tệp vào bộ nhớ cache của thiết bị chỉ với tệp nhỏ (không phải video, <= 20MB)
            if (!isVideo && fileSize in 1..20_000_000L) {
                try {
                    val targetFileName = article.fileName?.let { if (it.contains('.')) it else "$it.${mimeType.substringAfterLast('/', "bin")}" }
                        ?: fileName
                    val cacheFile = java.io.File(context.cacheDir, "podoc_doc_${article.id}_$targetFileName")
                    contentResolver.openInputStream(uri)?.use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {}
            }

            val lower = (article.fileName ?: article.filePath ?: "").lowercase()
            val isMedia = article.mimeType?.startsWith("image/") == true ||
                    article.mimeType?.startsWith("video/") == true ||
                    listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp", ".mp4", ".mov", ".mkv", ".webm", ".avi", ".3gp").any { lower.endsWith(it) }
            val finalUrl = if (article.sourceType == "FILE" && isMedia) {
                resolveImageUrl("/api/articles/${article.id}/thumbnail")
            } else {
                resolveImageUrl(article.imageUrl)
            }
            val effectiveDuration = article.durationSeconds?.takeIf { it > 0f } ?: durationSec
            if (effectiveDuration != null && effectiveDuration > 0f) {
                cacheVideoDuration(article.id, effectiveDuration)
            }
            Result.success(article.copy(imageUrl = finalUrl, durationSeconds = effectiveDuration))
        } catch (e: Exception) {
            val userFriendlyMsg = when {
                e is java.net.SocketTimeoutException -> "Hết thời gian chờ kết nối (Tệp quá lớn hoặc mạng quá chậm)"
                e is java.net.UnknownHostException -> "Không thể kết nối máy chủ (Vui lòng kiểm tra mạng)"
                e is java.net.ConnectException -> "Không thể kết nối máy chủ"
                e is java.net.SocketException -> {
                    val msg = e.message ?: ""
                    if (msg.contains("Software caused connection abort", ignoreCase = true) ||
                        msg.contains("Broken pipe", ignoreCase = true) ||
                        msg.contains("Connection reset", ignoreCase = true)) {
                        "Đường truyền bị ngắt giữa chừng do mạng 4G/Wi-Fi chập chờn hoặc màn hình bị tắt. Hãy bật Wi-Fi, giữ màn hình sáng và thử lại."
                    } else {
                        "Lỗi kết nối mạng: $msg"
                    }
                }
                e is java.io.FileNotFoundException -> "Không thể đọc tệp trên thiết bị (Có thể tệp lưu trên Cloud chưa tải về máy)"
                e is java.net.ProtocolException -> "Lỗi truyền luồng dữ liệu (${e.message})"
                e is retrofit2.HttpException -> {
                    when (e.code()) {
                        408, 504 -> "Hết thời gian tải lên hoặc chờ máy chủ. Hãy kiểm tra thư viện trước khi tải lại."
                        502 -> "Máy chủ đang khởi động lại hoặc ngắt kết nối (502)"
                        413 -> "Dung lượng tệp vượt quá giới hạn máy chủ"
                        507 -> "Ổ cứng máy chủ đã đầy (Hết dung lượng lưu trữ)"
                        else -> extractErrorMessage(e)
                    }
                }
                else -> extractErrorMessage(e)
            }
            Result.failure(Exception(userFriendlyMsg, e))
        }
    }

    suspend fun createArticle(request: CreateArticleRequest): Result<ArticleDetail> {
        return try {
            val article = getApi().createArticle(request)
            Result.success(article.copy(imageUrl = resolveImageUrl(article.imageUrl)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateArticle(
        id: Int,
        title: String? = null,
        categoryId: Int? = null,
        contentMarkdown: String? = null,
        isFavorite: Boolean? = null,
        readProgress: Float? = null
    ): Result<ArticleDetail> {
        return try {
            val article = getApi().updateArticle(
                id,
                UpdateArticleRequest(title, categoryId, contentMarkdown, isFavorite, readProgress)
            )
            Result.success(article)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteArticle(id: Int): Result<Unit> {
        return try {
            val res = getApi().deleteArticle(id)
            if (res.isSuccessful || res.code() == 404) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Xoá thất bại: mã phản hồi ${res.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteArticles(ids: List<Int>): Result<Unit> {
        if (ids.isEmpty()) return Result.success(Unit)
        return try {
            val res = getApi().batchDeleteArticles(BatchDeleteRequest(ids))
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                var allSuccess = true
                for (id in ids) {
                    val singleRes = getApi().deleteArticle(id)
                    if (!singleRes.isSuccessful && singleRes.code() != 404) {
                        allSuccess = false
                    }
                }
                if (allSuccess) Result.success(Unit) else Result.failure(Exception("Xoá thất bại: mã ${res.code()}"))
            }
        } catch (e: Exception) {
            try {
                var allSuccess = true
                for (id in ids) {
                    val singleRes = getApi().deleteArticle(id)
                    if (!singleRes.isSuccessful && singleRes.code() != 404) {
                        allSuccess = false
                    }
                }
                if (allSuccess) Result.success(Unit) else Result.failure(e)
            } catch (fallbackEx: Exception) {
                Result.failure(fallbackEx)
            }
        }
    }

    suspend fun batchUpdateCategory(ids: List<Int>, categoryId: Int): Result<Unit> {
        if (ids.isEmpty()) return Result.success(Unit)
        return try {
            val res = getApi().batchUpdateCategory(BatchUpdateCategoryRequest(ids, categoryId))
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                for (id in ids) {
                    getApi().updateArticle(id, UpdateArticleRequest(categoryId = categoryId))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            try {
                for (id in ids) {
                    getApi().updateArticle(id, UpdateArticleRequest(categoryId = categoryId))
                }
                Result.success(Unit)
            } catch (fallbackEx: Exception) {
                Result.failure(fallbackEx)
            }
        }
    }

    suspend fun exportData(): Result<ResponseBody> {
        return try {
            val body = getApi().exportData()
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadArticleFile(id: Int, inline: Boolean = false, variant: String? = null): Result<ResponseBody> {
        return try {
            val body = getApi().downloadArticleFile(id, inline, variant)
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun previewArticleHtml(id: Int): Result<String> {
        return try {
            val body = getApi().previewArticleHtml(id)
            Result.success(body.string())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractErrorMessage(e: Exception): String {
        if (e is retrofit2.HttpException) {
            try {
                val body = e.response()?.errorBody()?.string()
                if (!body.isNullOrBlank() && body.contains("detail")) {
                    val detail = org.json.JSONObject(body).optString("detail", "")
                    if (detail.isNotBlank()) return detail
                }
            } catch (_: Exception) {}
        }
        return e.localizedMessage ?: e.message ?: "Đã xảy ra lỗi kết nối"
    }

    /**
     * Chuyển đổi tệp đính kèm của một tài liệu hiện có (doc, docx, xls, xlsx, ppt, pptx...)
     * sang định dạng PDF giữ nguyên 100% định dạng ban đầu.
     */
    suspend fun convertArticleToPdf(id: Int): Result<ArticleDetail> = withContext(Dispatchers.IO) {
        try {
            val article = getApi().convertArticleToPdf(id)
            Result.success(article)
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    /**
     * Tải trực tiếp tệp Office lên và chuyển đổi sang PDF ngay lập tức trên máy chủ.
     */
    suspend fun uploadAndConvertToPdf(
        uri: Uri,
        categoryId: Int? = null,
        title: String? = null
    ): Result<ArticleDetail> = withContext(Dispatchers.IO) {
        try {
            val fileName = queryFileName(uri) ?: "document.docx"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Không thể đọc tệp đã chọn")
            val bytes = inputStream.use { it.readBytes() }

            val requestFile = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", fileName, requestFile)
            val catBody = categoryId?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
            val titleBody = title?.toRequestBody("text/plain".toMediaTypeOrNull())

            val article = getApi().uploadAndConvertToPdf(filePart, catBody, titleBody)
            Result.success(article)
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    suspend fun getStorageStats(categoryId: Int? = null): Result<StorageStats> = withContext(Dispatchers.IO) {
        try {
            val stats = getApi().getStorageStats(categoryId = categoryId)
            Result.success(stats)
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    suspend fun getServerSystemStats(): Result<ServerSystemStats> = withContext(Dispatchers.IO) {
        try {
            val stats = getApi().getServerSystemStats()
            Result.success(stats)
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    suspend fun inspectLocalPdf(uri: Uri): LocalPdfMeta = withContext(Dispatchers.IO) {
        val (rawName, sizeBytes) = queryFileInfo(uri)
        val fileName = (rawName ?: "document.pdf").let {
            if (it.lowercase().endsWith(".pdf")) it else "$it.pdf"
        }
        var pageCount: Int? = null
        var isEncrypted = false
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount
                }
            }
        } catch (_: SecurityException) {
            isEncrypted = true
        } catch (_: Exception) {}
        LocalPdfMeta(
            fileName = fileName,
            fileSizeBytes = sizeBytes.coerceAtLeast(0L),
            pageCount = pageCount,
            isEncrypted = isEncrypted
        )
    }

    suspend fun getLibraryPdfInfo(articleId: Int): Result<PdfInfoResponse> = withContext(Dispatchers.IO) {
        try {
            Result.success(getApi().getLibraryPdfInfo(articleId))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    suspend fun mergePdfFiles(
        items: List<PdfMergeInput>,
        title: String?,
        categoryId: Int?
    ): Result<ArticleDetail> = withContext(Dispatchers.IO) {
        try {
            if (items.size < 2) {
                return@withContext Result.failure(IllegalArgumentException("Cần chọn ít nhất 2 tệp PDF để ghép."))
            }
            val fileParts = mutableListOf<MultipartBody.Part>()
            val sourceSpecs = mutableListOf<PdfMergeSourceSpec>()
            val textMediaType = "text/plain".toMediaTypeOrNull()

            for (item in items) {
                when (item) {
                    is PdfMergeInput.LocalUri -> {
                        val uploadIndex = fileParts.size
                        val (_, queriedSize) = queryFileInfo(item.uri)
                        val body = StreamingUriRequestBody(
                            context.contentResolver,
                            item.uri,
                            "application/pdf",
                            queriedSize.coerceAtLeast(item.fileSizeBytes)
                        )
                        val headerName = asciiSafeHeaderFileName(item.displayName, "pdf")
                        fileParts.add(MultipartBody.Part.createFormData("files", headerName, body))
                        sourceSpecs.add(PdfMergeSourceSpec(type = "upload", index = uploadIndex))
                    }
                    is PdfMergeInput.LibraryArticle -> {
                        sourceSpecs.add(PdfMergeSourceSpec(type = "article", id = item.articleId))
                    }
                }
            }

            val sourcesJsonBody = Json.encodeToString(sourceSpecs).toRequestBody(textMediaType)
            val titleBody = title?.trim()?.takeIf { it.isNotBlank() }?.toRequestBody(textMediaType)
            val categoryBody = categoryId?.toString()?.toRequestBody(textMediaType)

            val created = getApi().mergePdfFiles(
                files = fileParts,
                sourcesJson = sourcesJsonBody,
                title = titleBody,
                categoryId = categoryBody
            )
            Result.success(created.copy(imageUrl = resolveImageUrl(created.imageUrl)))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    suspend fun splitPdfFile(
        source: PdfSplitSource,
        mode: String,
        ranges: String?,
        mergeRanges: Boolean,
        pagesPerSplit: Int,
        titlePrefix: String?,
        categoryId: Int?
    ): Result<SplitPdfResponse> = withContext(Dispatchers.IO) {
        try {
            val textMediaType = "text/plain".toMediaTypeOrNull()
            var filePart: MultipartBody.Part? = null
            var articleIdBody: RequestBody? = null

            when (source) {
                is PdfSplitSource.LocalUri -> {
                    val (_, queriedSize) = queryFileInfo(source.uri)
                    val body = StreamingUriRequestBody(
                        context.contentResolver,
                        source.uri,
                        "application/pdf",
                        queriedSize
                    )
                    val headerName = asciiSafeHeaderFileName(source.displayName, "pdf")
                    filePart = MultipartBody.Part.createFormData("file", headerName, body)
                }
                is PdfSplitSource.LibraryArticle -> {
                    articleIdBody = source.articleId.toString().toRequestBody(textMediaType)
                }
            }

            val resp = getApi().splitPdfFile(
                file = filePart,
                articleId = articleIdBody,
                mode = mode.toRequestBody(textMediaType),
                ranges = ranges?.trim()?.takeIf { it.isNotBlank() }?.toRequestBody(textMediaType),
                mergeRanges = mergeRanges.toString().toRequestBody(textMediaType),
                pagesPerSplit = pagesPerSplit.toString().toRequestBody(textMediaType),
                titlePrefix = titlePrefix?.trim()?.takeIf { it.isNotBlank() }?.toRequestBody(textMediaType),
                categoryId = categoryId?.toString()?.toRequestBody(textMediaType)
            )
            val mappedArticles = resp.articles.map { art ->
                art.copy(imageUrl = resolveImageUrl(art.imageUrl))
            }
            Result.success(resp.copy(articles = mappedArticles))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    suspend fun probeLibraryVideoAudio(articleId: Int): Result<AudioProbeResponse> = withContext(Dispatchers.IO) {
        try {
            Result.success(getApi().probeLibraryVideoAudio(articleId))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    suspend fun inspectLocalVideoAudio(uri: Uri): LocalVideoAudioMeta = withContext(Dispatchers.IO) {
        val (name, size) = queryFileInfo(uri)
        var durationSec: Float? = null
        var hasAudio = true
        var mime: String? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (ms != null && ms > 0L) durationSec = ms / 1000f
            val hasAudioMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            if (hasAudioMeta != null) {
                hasAudio = hasAudioMeta.equals("yes", ignoreCase = true)
            }
            mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (_: Throwable) {
        } finally {
            try { retriever?.release() } catch (_: Throwable) {}
        }
        val durationFormatted = durationSec?.let { formatSecondsToMmSs(it) }
        LocalVideoAudioMeta(
            fileName = name ?: "video.mp4",
            fileSizeBytes = size.coerceAtLeast(0L),
            durationSeconds = durationSec,
            durationFormatted = durationFormatted,
            hasAudio = hasAudio,
            mimeType = mime ?: "video/mp4"
        )
    }

    suspend fun extractAudioFromVideo(
        source: AudioExtractSource,
        outputFormat: String,
        title: String?,
        categoryId: Int?
    ): Result<AudioExtractResultResponse> = withContext(Dispatchers.IO) {
        try {
            val textMediaType = "text/plain".toMediaTypeOrNull()
            var filePart: MultipartBody.Part? = null
            var articleIdBody: RequestBody? = null

            when (source) {
                is AudioExtractSource.LocalUri -> {
                    val (_, queriedSize) = queryFileInfo(source.uri)
                    val body = StreamingUriRequestBody(
                        context.contentResolver,
                        source.uri,
                        "video/*",
                        queriedSize.coerceAtLeast(source.fileSizeBytes)
                    )
                    val headerName = asciiSafeHeaderFileName(source.displayName, "mp4")
                    filePart = MultipartBody.Part.createFormData("file", headerName, body)
                }
                is AudioExtractSource.LibraryArticle -> {
                    articleIdBody = source.articleId.toString().toRequestBody(textMediaType)
                }
            }

            val formatBody = outputFormat.trim().toRequestBody(textMediaType)
            val titleBody = title?.trim()?.takeIf { it.isNotBlank() }?.toRequestBody(textMediaType)
            val categoryBody = categoryId?.toString()?.toRequestBody(textMediaType)

            val resp = getApi().extractAudioFromVideo(
                file = filePart,
                articleId = articleIdBody,
                outputFormat = formatBody,
                title = titleBody,
                categoryId = categoryBody
            )
            Result.success(resp)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    private fun formatSecondsToMmSs(seconds: Float): String {
        val totalSec = Math.round(seconds)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun asciiSafeHeaderFileName(fileName: String, defaultExt: String): String {
        val safe = buildString {
            for (ch in fileName) {
                if (ch.code in 32..126 && ch != '"' && ch != '\\' && ch != ';') append(ch)
                else append('_')
            }
        }.trim()
        return if (safe.isBlank() || safe.all { it == '_' || it == '.' }) {
            "pdf_${System.currentTimeMillis()}.$defaultExt"
        } else {
            safe
        }
    }

    fun queryFileInfo(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size: Long = -1L
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex)
                        }
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (size <= 0) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (_: Exception) {}
        }
        if (name == null) {
            name = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return Pair(name, size)
    }

    private fun queryMediaDurationSeconds(uri: Uri): Float? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (ms != null && ms > 0L) ms / 1000f else null
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever?.release() } catch (_: Throwable) {}
        }
    }

    private fun queryFileName(uri: Uri): String? = queryFileInfo(uri).first
}

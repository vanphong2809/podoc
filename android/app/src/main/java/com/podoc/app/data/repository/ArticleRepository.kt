package com.podoc.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.podoc.app.data.api.PoDocApiService
import com.podoc.app.data.model.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ArticleRepository(
    private val apiServiceProvider: () -> PoDocApiService?,
    private val context: Context,
    private val serverUrlProvider: () -> String = { "" }
) {
    private fun getApi(): PoDocApiService =
        apiServiceProvider() ?: throw IllegalStateException("Chưa kết nối tới máy chủ API")

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
                item.copy(imageUrl = finalUrl)
            }
            Result.success(mapped)
        } catch (e: Exception) {
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
            Result.success(detail.copy(imageUrl = finalUrl))
        } catch (e: Exception) {
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
        customTitle: String? = null
    ): Result<ArticleDetail> {
        return try {
            val contentResolver = context.contentResolver
            var fileName = queryFileName(uri) ?: "file_${System.currentTimeMillis()}"
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

            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IllegalStateException("Không thể đọc tệp tin"))

            val lowerName = fileName.lowercase()
            val isImage = mimeType.startsWith("image/") || lowerName.endsWith(".jpg") ||
                    lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") ||
                    lowerName.endsWith(".webp") || lowerName.endsWith(".gif") || lowerName.endsWith(".svg")

            val isVideo = mimeType.startsWith("video/") || lowerName.endsWith(".mp4") ||
                    lowerName.endsWith(".mkv") || lowerName.endsWith(".mov") ||
                    lowerName.endsWith(".webm") || lowerName.endsWith(".avi") ||
                    lowerName.endsWith(".3gp") || lowerName.endsWith(".ts")

            var thumbnailPart: MultipartBody.Part? = null
            if (isVideo) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                    if (frame != null) {
                        val maxDim = 480
                        val width = frame.width
                        val height = frame.height
                        val scale = if (width > maxDim || height > maxDim) {
                            maxDim.toFloat() / maxOf(width, height)
                        } else 1f
                        val scaledBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(frame, (width * scale).toInt(), (height * scale).toInt(), true)
                        } else frame
                        val baos = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        val thumbBytes = baos.toByteArray()
                        val thumbBody = thumbBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        thumbnailPart = MultipartBody.Part.createFormData("thumbnail", "thumb_${fileName}.jpg", thumbBody)
                    }
                    retriever.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else if (isImage) {
                try {
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                    val origW = boundsOptions.outWidth
                    val origH = boundsOptions.outHeight
                    val maxDim = 1280
                    var sampleSize = 1
                    while (origW / (sampleSize * 2) >= maxDim && origH / (sampleSize * 2) >= maxDim) {
                        sampleSize *= 2
                    }
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                    if (bitmap != null) {
                        val scale = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                            maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                        } else 1f
                        val scaledBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        } else bitmap
                        val baos = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        val thumbBytes = baos.toByteArray()
                        val thumbBody = thumbBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        thumbnailPart = MultipartBody.Part.createFormData("thumbnail", "thumb_${fileName}.jpg", thumbBody)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val requestFile = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", fileName, requestFile)

            val catBody = categoryId?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
            val titleBody = customTitle?.toRequestBody("text/plain".toMediaTypeOrNull())

            val article = try {
                getApi().uploadArticleFile(filePart, thumbnailPart, catBody, titleBody)
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 413) {
                    val errDetail = try {
                        val body = e.response()?.errorBody()?.string()
                        if (!body.isNullOrBlank() && body.contains("detail")) {
                            org.json.JSONObject(body).optString("detail", "")
                        } else ""
                    } catch (_: Exception) { "" }
                    val msg = if (errDetail.isNotBlank()) errDetail else "Dung lượng tệp vượt quá giới hạn cho phép."
                    throw IllegalArgumentException(msg)
                } else if (e.code() == 405 || e.code() == 404) {
                    // Fallback cho backend chưa cập nhật route /api/articles/upload
                    val fallbackFilePart = MultipartBody.Part.createFormData("file", fileName, requestFile)
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

            // Lưu tệp ngay vào bộ nhớ cache của thiết bị để mở xem tức thì không cần tải lại qua mạng
            try {
                val targetFileName = article.fileName?.let { if (it.contains('.')) it else "$it.${mimeType.substringAfterLast('/', "bin")}" }
                    ?: fileName
                val cacheFile = java.io.File(context.cacheDir, "podoc_doc_${article.id}_$targetFileName")
                cacheFile.outputStream().use { out -> out.write(bytes) }
            } catch (_: Exception) {}

            val lower = (article.fileName ?: article.filePath ?: "").lowercase()
            val isMedia = article.mimeType?.startsWith("image/") == true ||
                    article.mimeType?.startsWith("video/") == true ||
                    listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".bmp", ".mp4", ".mov", ".mkv", ".webm", ".avi", ".3gp").any { lower.endsWith(it) }
            val finalUrl = if (article.sourceType == "FILE" && isMedia) {
                resolveImageUrl("/api/articles/${article.id}/thumbnail")
            } else {
                resolveImageUrl(article.imageUrl)
            }
            Result.success(article.copy(imageUrl = finalUrl))
        } catch (e: Exception) {
            Result.failure(e)
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
        isFavorite: Boolean? = null,
        readProgress: Float? = null
    ): Result<ArticleDetail> {
        return try {
            val article = getApi().updateArticle(
                id,
                UpdateArticleRequest(title, categoryId, isFavorite, readProgress)
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

    suspend fun downloadArticleFile(id: Int, inline: Boolean = false): Result<ResponseBody> {
        return try {
            val body = getApi().downloadArticleFile(id, inline)
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

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return name
    }
}

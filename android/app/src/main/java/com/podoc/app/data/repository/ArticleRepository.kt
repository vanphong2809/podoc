package com.podoc.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.podoc.app.data.api.PoDocApiService
import com.podoc.app.data.model.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

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
            val mapped = list.map { it.copy(imageUrl = resolveImageUrl(it.imageUrl)) }
            Result.success(mapped)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArticleDetail(id: Int): Result<ArticleDetail> {
        return try {
            val detail = getApi().getArticleDetail(id)
            Result.success(detail.copy(imageUrl = resolveImageUrl(detail.imageUrl)))
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

            // Kiểm tra dung lượng theo yêu cầu:
            // - Hình ảnh: tối đa 20 MB
            // - Video: tối đa 50 MB
            // - Tệp tin: tối đa 50 MB
            val lowerName = fileName.lowercase()
            val isImage = mimeType.startsWith("image/") || lowerName.endsWith(".jpg") ||
                    lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") ||
                    lowerName.endsWith(".webp") || lowerName.endsWith(".gif") || lowerName.endsWith(".svg")

            val isVideo = mimeType.startsWith("video/") || lowerName.endsWith(".mp4") ||
                    lowerName.endsWith(".mkv") || lowerName.endsWith(".mov") ||
                    lowerName.endsWith(".webm") || lowerName.endsWith(".avi") ||
                    lowerName.endsWith(".3gp") || lowerName.endsWith(".ts")

            val fileSizeMb = bytes.size.toDouble() / (1024 * 1024)
            if (isImage && bytes.size > 20 * 1024 * 1024) {
                return Result.failure(
                    IllegalArgumentException("Hình ảnh '${fileName}' (${String.format("%.1f", fileSizeMb)} MB) vượt quá giới hạn 20 MB.")
                )
            }
            if (isVideo && bytes.size > 50 * 1024 * 1024) {
                return Result.failure(
                    IllegalArgumentException("Video '${fileName}' (${String.format("%.1f", fileSizeMb)} MB) vượt quá giới hạn 50 MB.")
                )
            }
            if (!isImage && !isVideo && bytes.size > 50 * 1024 * 1024) {
                return Result.failure(
                    IllegalArgumentException("Tệp tin '${fileName}' (${String.format("%.1f", fileSizeMb)} MB) vượt quá giới hạn 50 MB.")
                )
            }

            val requestFile = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", fileName, requestFile)

            val catBody = categoryId?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
            val titleBody = customTitle?.toRequestBody("text/plain".toMediaTypeOrNull())

            val article = try {
                getApi().uploadArticleFile(filePart, catBody, titleBody)
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
            Result.success(article.copy(imageUrl = resolveImageUrl(article.imageUrl)))
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
            getApi().deleteArticle(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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

package com.podoc.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.podoc.app.data.api.PoDocApiService
import com.podoc.app.data.model.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

class ArticleRepository(
    private val apiServiceProvider: () -> PoDocApiService?,
    private val context: Context
) {
    private fun getApi(): PoDocApiService =
        apiServiceProvider() ?: throw IllegalStateException("Chưa kết nối tới máy chủ API")

    suspend fun getArticles(
        query: String? = null,
        categoryId: Int? = null,
        sourceType: String? = null,
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
                favorite = favorite,
                sort = sort,
                page = page,
                pageSize = pageSize
            )
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArticleDetail(id: Int): Result<ArticleDetail> {
        return try {
            val detail = getApi().getArticleDetail(id)
            Result.success(detail)
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
            Result.success(draft.copy(fileName = fileName, mimeType = mimeType))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createArticle(request: CreateArticleRequest): Result<ArticleDetail> {
        return try {
            val article = getApi().createArticle(request)
            Result.success(article)
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

    suspend fun downloadArticleFile(id: Int): Result<ResponseBody> {
        return try {
            val body = getApi().downloadArticleFile(id)
            Result.success(body)
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

package com.podoc.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExtractUrlRequest(
    @SerialName("url") val url: String
)

@Serializable
data class ArticleDraft(
    @SerialName("title") val title: String,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("normalized_url") val normalizedUrl: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("reading_minutes") val readingMinutes: Int = 1,
    @SerialName("excerpt") val excerpt: String? = null,
    @SerialName("content_markdown") val contentMarkdown: String = "",
    @SerialName("is_duplicate") val isDuplicate: Boolean = false,
    @SerialName("existing_article_id") val existingArticleId: Int? = null,
    @SerialName("is_fallback_bookmark") val isFallbackBookmark: Boolean = false,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("mime_type") val mimeType: String? = null
)

@Serializable
data class CreateArticleRequest(
    @SerialName("title") val title: String,
    @SerialName("source_type") val sourceType: String = "URL",
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("normalized_url") val normalizedUrl: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("content_markdown") val contentMarkdown: String = "",
    @SerialName("excerpt") val excerpt: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("reading_minutes") val readingMinutes: Int = 0,
    @SerialName("category_id") val categoryId: Int,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("read_progress") val readProgress: Float = 0.0f
)

@Serializable
data class UpdateArticleRequest(
    @SerialName("title") val title: String? = null,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("is_favorite") val isFavorite: Boolean? = null,
    @SerialName("read_progress") val readProgress: Float? = null
)

@Serializable
data class ArticleListItem(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String,
    @SerialName("source_type") val sourceType: String = "URL",
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("excerpt") val excerpt: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("reading_minutes") val readingMinutes: Int = 0,
    @SerialName("category_id") val categoryId: Int,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("read_progress") val readProgress: Float = 0.0f,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class ArticleDetail(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String,
    @SerialName("source_type") val sourceType: String = "URL",
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("normalized_url") val normalizedUrl: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("content_markdown") val contentMarkdown: String = "",
    @SerialName("excerpt") val excerpt: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("reading_minutes") val readingMinutes: Int = 0,
    @SerialName("category_id") val categoryId: Int,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("read_progress") val readProgress: Float = 0.0f,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class BatchDeleteRequest(
    @SerialName("ids") val ids: List<Int>
)

@Serializable
data class BatchUpdateCategoryRequest(
    @SerialName("ids") val ids: List<Int>,
    @SerialName("category_id") val categoryId: Int
)

@Serializable
data class BatchDeleteResponse(
    @SerialName("deleted_count") val deletedCount: Int = 0
)

@Serializable
data class StorageItemStat(
    @SerialName("content_type") val contentType: String = "",
    @SerialName("label") val label: String = "",
    @SerialName("count") val count: Int = 0,
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("formatted_size") val formattedSize: String = "0 B"
)

@Serializable
data class CategoryStorageStat(
    @SerialName("category_id") val categoryId: Int = 0,
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("count") val count: Int = 0,
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("formatted_size") val formattedSize: String = "0 B"
)

@Serializable
data class StorageStats(
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("formatted_total_size") val formattedTotalSize: String = "0 B",
    @SerialName("total_files") val totalFiles: Int = 0,
    @SerialName("items") val items: List<StorageItemStat> = emptyList(),
    @SerialName("by_category") val byCategory: List<CategoryStorageStat> = emptyList()
) {
    val image: StorageItemStat
        get() = items.find { it.contentType == "image" } ?: StorageItemStat(contentType = "image", label = "Hình ảnh")

    val video: StorageItemStat
        get() = items.find { it.contentType == "video" } ?: StorageItemStat(contentType = "video", label = "Video")

    val audio: StorageItemStat
        get() = items.find { it.contentType == "audio" } ?: StorageItemStat(contentType = "audio", label = "Âm thanh")

    val file: StorageItemStat
        get() = items.find { it.contentType == "file" } ?: StorageItemStat(contentType = "file", label = "Tệp tin")
}



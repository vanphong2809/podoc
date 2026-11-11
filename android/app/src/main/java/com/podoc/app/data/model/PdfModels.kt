package com.podoc.app.data.model

import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PdfInfoResponse(
    @SerialName("id") val id: Int? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("page_count") val pageCount: Int = 0,
    @SerialName("is_encrypted") val isEncrypted: Boolean = false,
    @SerialName("file_size_bytes") val fileSizeBytes: Long? = null,
    @SerialName("file_size_formatted") val fileSizeFormatted: String? = null
)

@Serializable
data class SplitPdfResponse(
    @SerialName("created_count") val createdCount: Int = 0,
    @SerialName("source_page_count") val sourcePageCount: Int = 0,
    @SerialName("articles") val articles: List<ArticleDetail> = emptyList()
)

@Serializable
data class PdfMergeSourceSpec(
    @SerialName("type") val type: String, // "upload" or "article"
    @SerialName("index") val index: Int? = null,
    @SerialName("id") val id: Int? = null
)

data class LocalPdfMeta(
    val fileName: String,
    val fileSizeBytes: Long,
    val pageCount: Int?,
    val isEncrypted: Boolean
)

sealed class PdfMergeInput {
    abstract val key: String
    abstract val displayName: String
    abstract val pageCount: Int?
    abstract val fileSizeFormatted: String?
    abstract val isFromLibrary: Boolean

    data class LocalUri(
        override val key: String,
        val uri: Uri,
        override val displayName: String,
        override val pageCount: Int?,
        val fileSizeBytes: Long,
        override val fileSizeFormatted: String?
    ) : PdfMergeInput() {
        override val isFromLibrary: Boolean = false
    }

    data class LibraryArticle(
        override val key: String,
        val articleId: Int,
        override val displayName: String,
        override val pageCount: Int?,
        override val fileSizeFormatted: String?,
        val categoryName: String? = null
    ) : PdfMergeInput() {
        override val isFromLibrary: Boolean = true
    }
}

sealed class PdfSplitSource {
    abstract val displayName: String
    abstract val pageCount: Int?
    abstract val fileSizeFormatted: String?
    abstract val isFromLibrary: Boolean

    data class LocalUri(
        val uri: Uri,
        override val displayName: String,
        override val pageCount: Int?,
        override val fileSizeFormatted: String?
    ) : PdfSplitSource() {
        override val isFromLibrary: Boolean = false
    }

    data class LibraryArticle(
        val articleId: Int,
        override val displayName: String,
        override val pageCount: Int?,
        override val fileSizeFormatted: String?,
        val categoryId: Int? = null
    ) : PdfSplitSource() {
        override val isFromLibrary: Boolean = true
    }
}


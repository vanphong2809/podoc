package com.podoc.app.ui.screens.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.data.repository.ArticleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderUiState(
    val article: ArticleDetail? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val fontSizeSp: Int = 16,
    val isSerif: Boolean = false,
    val readProgress: Float = 0.0f,
    val isDownloadingFile: Boolean = false,
    // In-app file viewer states
    val isFileLoading: Boolean = false,
    val localFile: File? = null,
    val htmlContent: String? = null,
    val textContent: String? = null,
    val fileError: String? = null,
    val isConvertingToPdf: Boolean = false,
    val convertError: String? = null,
    // TikTok Video Feed states
    val videoFeed: List<ArticleListItem> = emptyList(),
    val currentVideoIndex: Int = 0,
    val videoFilesCache: Map<Int, File> = emptyMap(),
    val videoAspectRatios: Map<Int, Float> = emptyMap(),
    val videoThumbnails: Map<Int, android.graphics.Bitmap> = emptyMap()
)

class ReaderViewModel(
    private val articleRepository: ArticleRepository,
    private val dataStoreManager: DataStoreManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var progressSyncJob: Job? = null
    private val videoFileCache = VideoFileCache(viewModelScope)
    private val metadataRequests = mutableSetOf<Int>()

    init {
        viewModelScope.launch {
            val defaultSize = dataStoreManager.defaultFontSizeFlow.first()
            _uiState.value = _uiState.value.copy(fontSizeSp = defaultSize)
        }
    }

    fun loadArticle(id: Int, context: Context) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            articleRepository.getArticleDetail(id).fold(
                onSuccess = { detail ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        article = detail,
                        readProgress = detail.readProgress
                    )
                    // Video uses the shared feed cache so initial playback and prefetch never write the same file twice.
                    val rawExt = (detail.fileName ?: detail.filePath ?: "").let { if (it.contains('.')) it.substringAfterLast('.', "").lowercase() else "" }
                    val isVideo = rawExt in listOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "ts") || detail.mimeType?.startsWith("video/") == true
                    if (isVideo) {
                        loadVideoFeed(detail.id, detail.categoryId, context)
                    } else if (detail.sourceType == "FILE" || !detail.fileName.isNullOrBlank()) {
                        loadFileForInApp(context, detail)
                    }
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Không thể tải nội dung bài viết"
                    )
                }
            )
        }
    }

    fun loadFileForInApp(context: Context, detail: ArticleDetail) {
        val rawExt = (detail.fileName ?: detail.filePath ?: "").let { path ->
            if (path.contains('.')) path.substringAfterLast('.', "").lowercase() else ""
        }
        val ext = if (rawExt.isNotBlank()) rawExt
        else {
            when {
                detail.mimeType == "image/jpeg" -> "jpg"
                detail.mimeType == "image/png" -> "png"
                detail.mimeType == "image/webp" -> "webp"
                detail.mimeType == "image/svg+xml" -> "svg"
                detail.mimeType == "image/gif" -> "gif"
                detail.mimeType == "application/pdf" -> "pdf"
                detail.mimeType == "text/plain" -> "txt"
                detail.mimeType == "text/markdown" -> "md"
                detail.mimeType == "text/html" -> "html"
                detail.mimeType?.contains("wordprocessingml") == true -> "docx"
                detail.mimeType?.contains("msword") == true -> "doc"
                detail.mimeType?.startsWith("image/") == true -> "jpg"
                else -> ""
            }
        }
        val safeExt = if (ext.isNotBlank()) ".$ext" else ""
        val fileName = detail.fileName?.let { if (it.contains('.')) it else "$it$safeExt" } ?: "document_${detail.id}$safeExt"
        val cacheFile = File(context.cacheDir, "podoc_doc_${detail.id}_$fileName")

        _uiState.value = _uiState.value.copy(isFileLoading = true, fileError = null)

        viewModelScope.launch {
            // Với file Word (docx / doc), tải đồng thời HTML preview được convert từ backend
            if (ext in listOf("docx", "doc")) {
                articleRepository.previewArticleHtml(detail.id).onSuccess { html ->
                    _uiState.value = _uiState.value.copy(htmlContent = html)
                }
            }

            withContext(Dispatchers.IO) {
                // Kiểm tra cache nội bộ
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    setupLocalFile(cacheFile, ext)
                } else {
                    articleRepository.downloadArticleFile(detail.id, inline = true).fold(
                        onSuccess = { responseBody ->
                            try {
                                cacheFile.outputStream().use { output ->
                                    responseBody.byteStream().use { input ->
                                        input.copyTo(output)
                                    }
                                }
                                setupLocalFile(cacheFile, ext)
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    _uiState.value = _uiState.value.copy(
                                        isFileLoading = false,
                                        fileError = "Lỗi lưu file: ${e.message ?: e.javaClass.simpleName}"
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            withContext(Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(
                                    isFileLoading = false,
                                    fileError = error.message ?: "Không thể tải tệp tin từ máy chủ"
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    private suspend fun setupLocalFile(file: File, ext: String) {
        val text = if (ext in listOf("txt", "md", "mdf")) {
            try { file.readText() } catch (e: Exception) { null }
        } else null

        val html = if (ext in listOf("html", "htm")) {
            try { file.readText() } catch (e: Exception) { null }
        } else _uiState.value.htmlContent

        val isVid = ext in listOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "ts")
        val curId = _uiState.value.article?.id
        if (isVid && curId != null) {
            extractMetadataAndThumbnailInBackground(curId, file)
        }

        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                isFileLoading = false,
                localFile = file,
                textContent = text,
                htmlContent = html,
                videoFilesCache = if (isVid && curId != null) _uiState.value.videoFilesCache + (curId to file) else _uiState.value.videoFilesCache
            )
        }
    }

    private var lastSavedProgress = 0.0f

    fun updateReadProgress(progress: Float) {
        val clamped = progress.coerceIn(0.0f, 1.0f)
        lastSavedProgress = clamped

        // Debounce đồng bộ tiến độ đọc lên server
        progressSyncJob?.cancel()
        progressSyncJob = viewModelScope.launch {
            delay(1200)
            val articleId = _uiState.value.article?.id ?: return@launch
            articleRepository.updateArticle(articleId, readProgress = clamped)
        }
    }

    fun increaseFontSize() {
        if (_uiState.value.fontSizeSp < 28) {
            _uiState.value = _uiState.value.copy(fontSizeSp = _uiState.value.fontSizeSp + 2)
        }
    }

    fun decreaseFontSize() {
        if (_uiState.value.fontSizeSp > 12) {
            _uiState.value = _uiState.value.copy(fontSizeSp = _uiState.value.fontSizeSp - 2)
        }
    }

    fun toggleSerif() {
        _uiState.value = _uiState.value.copy(isSerif = !_uiState.value.isSerif)
    }

    fun openOriginalFile(context: Context) {
        val article = _uiState.value.article ?: return
        val local = _uiState.value.localFile

        if (local != null && local.exists()) {
            openFileWithIntent(context, local, article.mimeType)
            return
        }

        _uiState.value = _uiState.value.copy(isDownloadingFile = true)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                articleRepository.downloadArticleFile(article.id).fold(
                    onSuccess = { responseBody ->
                        try {
                            val fileName = article.fileName ?: "document_${article.id}"
                            val cacheFile = File(context.cacheDir, "podoc_doc_${article.id}_$fileName")
                            cacheFile.outputStream().use { output ->
                                responseBody.byteStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                openFileWithIntent(context, cacheFile, article.mimeType)
                            }
                        } catch (e: Exception) {
                            // Handle open error
                        } finally {
                            withContext(Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(isDownloadingFile = false)
                            }
                        }
                    },
                    onFailure = {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(isDownloadingFile = false)
                        }
                    }
                )
            }
        }
    }

    private fun openFileWithIntent(context: Context, file: File, mimeType: String?) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Mở tệp tin bằng"))
        } catch (e: Exception) {
            // Intent open fallback
        }
    }

    fun deleteArticle(context: Context, onSuccess: () -> Unit) {
        val articleId = _uiState.value.article?.id ?: return
        com.podoc.app.service.MediaPlaybackManager.stopIfPlaying(articleId, context)
        viewModelScope.launch {
            articleRepository.deleteArticle(articleId).fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        onSuccess()
                    }
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Không thể xoá mục này: ${error.message ?: "Lỗi kết nối máy chủ"}"
                    )
                }
            )
        }
    }

    fun convertToPdf(onSuccess: (ArticleDetail) -> Unit) {
        val article = _uiState.value.article ?: return
        _uiState.value = _uiState.value.copy(isConvertingToPdf = true, convertError = null)
        viewModelScope.launch {
            articleRepository.convertArticleToPdf(article.id).fold(
                onSuccess = { newArticle ->
                    _uiState.value = _uiState.value.copy(isConvertingToPdf = false)
                    withContext(Dispatchers.Main) {
                        onSuccess(newArticle)
                    }
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isConvertingToPdf = false,
                        convertError = error.message ?: "Chuyển đổi sang PDF thất bại"
                    )
                }
            )
        }
    }

    fun clearConvertError() {
        _uiState.value = _uiState.value.copy(convertError = null)
    }

    /**
     * Tải danh sách video feed cho chế độ lướt TikTok
     */
    fun loadVideoFeed(currentArticleId: Int, categoryId: Int?, context: Context) {
        viewModelScope.launch {
            // Tải video từ danh mục hiện tại nếu có
            val categoryVideos = if (categoryId != null && categoryId > 0) {
                articleRepository.getArticles(categoryId = categoryId, contentType = "video", pageSize = 100).getOrNull()
            } else null

            // Nếu trong danh mục có ít hơn 2 video, lấy toàn bộ video trong thư viện để lướt phong phú
            val baseList = if (!categoryVideos.isNullOrEmpty() && categoryVideos.size >= 2) {
                categoryVideos
            } else {
                articleRepository.getArticles(contentType = "video", pageSize = 100).getOrDefault(emptyList())
            }

            val finalFeed = baseList.toMutableList()
            val currentIndex = finalFeed.indexOfFirst { it.id == currentArticleId }
            val startIndex = if (currentIndex >= 0) currentIndex else {
                _uiState.value.article?.let { cur ->
                    finalFeed.add(0, ArticleListItem(
                        id = cur.id,
                        title = cur.title,
                        sourceType = cur.sourceType,
                        sourceUrl = cur.sourceUrl,
                        fileName = cur.fileName,
                        filePath = cur.filePath,
                        mimeType = cur.mimeType,
                        categoryId = cur.categoryId,
                        categoryName = cur.categoryName,
                        isFavorite = cur.isFavorite,
                        readProgress = cur.readProgress,
                        createdAt = cur.createdAt,
                        updatedAt = cur.updatedAt
                    ))
                }
                0
            }

            _uiState.value = _uiState.value.copy(
                videoFeed = finalFeed,
                currentVideoIndex = startIndex
            )

            // Fetch the selected file first; adjacent files are requested by the pager.
            preloadVideo(startIndex, finalFeed, context)
        }
    }

    fun preloadVideo(index: Int, feed: List<ArticleListItem>, context: Context) {
        if (index !in feed.indices) return
        getVideoFile(context, feed[index]) { }
    }

    fun getVideoFile(context: Context, item: ArticleListItem, onReady: (File) -> Unit) {
        viewModelScope.launch {
            try {
                onReady(awaitVideoFile(context, item))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Background prefetch is retried when the page is selected.
            }
        }
    }

    suspend fun awaitVideoFile(context: Context, item: ArticleListItem): File {
        val rawExt = (item.fileName ?: item.filePath ?: "").substringAfterLast('.', "mp4").lowercase()
        val safeExt = ".${rawExt.ifBlank { "mp4" }}"
        val fileName = item.fileName?.let { if (it.contains('.')) it else "$it$safeExt" }
            ?: "video_${item.id}$safeExt"
        val cacheFile = File(context.cacheDir, "podoc_doc_${item.id}_$fileName")
        val file = videoFileCache.get(cacheFile) { partial ->
            articleRepository.downloadArticleFile(item.id, inline = true).getOrThrow().use { body ->
                partial.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
        }
        _uiState.value = _uiState.value.copy(
            videoFilesCache = _uiState.value.videoFilesCache + (item.id to file),
            localFile = if (item.id == _uiState.value.article?.id) file else _uiState.value.localFile
        )
        extractMetadataAndThumbnailInBackground(item.id, file)
        return file
    }

    private fun extractMetadataAndThumbnailInBackground(articleId: Int, file: File) {
        if (_uiState.value.videoThumbnails.containsKey(articleId) || !metadataRequests.add(articleId)) return
        viewModelScope.launch {
            try {
                val (ratio, bitmap) = withContext(Dispatchers.IO) {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                        val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                        val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        val ratio = if (width > 0f && height > 0f) {
                            if (rotation == 90 || rotation == 270) height / width else width / height
                        } else 16f / 9f
                        // A full-resolution 4K frame per page can exhaust memory and stall rendering.
                        val scale = 640f / maxOf(width, height, 640f)
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                (width * scale).toInt().coerceAtLeast(1),
                                (height * scale).toInt().coerceAtLeast(1)
                            )
                        } else null // API 26 uses the server thumbnail instead of decoding a full-size frame.
                        ratio to bitmap
                    } finally {
                        retriever.release()
                    }
                }
                _uiState.value = _uiState.value.copy(
                    videoAspectRatios = _uiState.value.videoAspectRatios + (articleId to ratio),
                    videoThumbnails = if (bitmap != null) {
                        (_uiState.value.videoThumbnails + (articleId to bitmap)).entries.toList().takeLast(5)
                            .associate { it.toPair() }
                    } else _uiState.value.videoThumbnails
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The server thumbnail remains available if local frame extraction fails.
            } finally {
                metadataRequests.remove(articleId)
            }
        }
    }

    fun openItemFile(context: Context, item: ArticleListItem) {
        getVideoFile(context, item) { file ->
            openFileWithIntent(context, file, item.mimeType)
        }
    }

    fun deleteFeedItem(item: ArticleListItem, onDeleted: () -> Unit) {
        viewModelScope.launch {
            articleRepository.deleteArticle(item.id).fold(
                onSuccess = {
                    val updatedFeed = _uiState.value.videoFeed.filterNot { it.id == item.id }
                    _uiState.value = _uiState.value.copy(videoFeed = updatedFeed)
                    onDeleted()
                },
                onFailure = { /* Bỏ qua */ }
            )
        }
    }
}

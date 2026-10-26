package com.podoc.app.ui.screens.reader

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.repository.ArticleRepository
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
    val fileError: String? = null
)

class ReaderViewModel(
    private val articleRepository: ArticleRepository,
    private val dataStoreManager: DataStoreManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var progressSyncJob: Job? = null

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
                    // Nếu là tệp tin, tự động nạp file về cache để mở trực tiếp trên app
                    if (detail.sourceType == "FILE" || !detail.fileName.isNullOrBlank()) {
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

        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                isFileLoading = false,
                localFile = file,
                textContent = text,
                htmlContent = html
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
}

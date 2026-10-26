package com.podoc.app.ui.screens.reader

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.repository.ArticleRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class ReaderUiState(
    val article: ArticleDetail? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val fontSizeSp: Int = 16,
    val isSerif: Boolean = false,
    val readProgress: Float = 0.0f,
    val isDownloadingFile: Boolean = false
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

    fun loadArticle(id: Int) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            articleRepository.getArticleDetail(id).fold(
                onSuccess = { detail ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        article = detail,
                        readProgress = detail.readProgress
                    )
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

    private var lastSavedProgress = 0.0f

    fun updateReadProgress(progress: Float) {
        val clamped = progress.coerceIn(0.0f, 1.0f)
        lastSavedProgress = clamped

        // Debounce đồng bộ tiến độ đọc lên server (không trigger recompose giao diện đọc)
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
        if (article.sourceType != "FILE") return

        _uiState.value = _uiState.value.copy(isDownloadingFile = true)
        viewModelScope.launch {
            articleRepository.downloadArticleFile(article.id).fold(
                onSuccess = { responseBody ->
                    try {
                        val fileName = article.fileName ?: "document_${article.id}"
                        val cacheFile = File(context.cacheDir, fileName)
                        cacheFile.outputStream().use { output ->
                            responseBody.byteStream().use { input ->
                                input.copyTo(output)
                            }
                        }

                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cacheFile
                        )

                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, article.mimeType ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Mở tệp tin bằng"))
                    } catch (e: Exception) {
                        // Handle open error
                    } finally {
                        _uiState.value = _uiState.value.copy(isDownloadingFile = false)
                    }
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(isDownloadingFile = false)
                }
            )
        }
    }
}

package com.podoc.app.ui.screens.category

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.UploadFailureDialogData
import com.podoc.app.data.model.UploadFailureItem
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.CategoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CategoryDetailUiState(
    val categoryId: Int,
    val categoryName: String,
    val categoryColor: String,
    val articles: List<ArticleListItem> = emptyList(),
    val categories: List<Category> = emptyList(),
    val searchQuery: String = "",
    val selectedContentType: String? = null, // null (Tất cả), "article", "file", "image", "video"
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val userMessage: String? = null,
    val isUploading: Boolean = false,
    val uploadProgressMessage: String? = null,
    val uploadFailureDialogData: UploadFailureDialogData? = null
)

class CategoryDetailViewModel(
    private val categoryId: Int,
    categoryName: String,
    categoryColor: String,
    val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CategoryDetailUiState(
            categoryId = categoryId,
            categoryName = categoryName,
            categoryColor = categoryColor
        )
    )
    val uiState: StateFlow<CategoryDetailUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var loadArticlesJob: Job? = null

    init {
        viewModelScope.launch {
            categoryRepository.categoriesFlow.collect { updatedList ->
                if (updatedList.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(categories = updatedList)
                }
            }
        }
        loadCategories()
        loadArticles(refresh = true)
    }

    fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategories().onSuccess { list ->
                _uiState.value = _uiState.value.copy(categories = list)
            }
        }
    }

    fun loadArticles(refresh: Boolean = false) {
        if (!refresh) {
            val currentState = _uiState.value
            if (currentState.isLoading || currentState.isRefreshing) return
            _uiState.value = currentState.copy(isLoading = true, errorMessage = null)
        } else {
            loadArticlesJob?.cancel()
            val currentArticles = _uiState.value.articles
            if (currentArticles.isEmpty()) {
                _uiState.value = _uiState.value.copy(isLoading = true, isRefreshing = false, errorMessage = null)
            } else {
                _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
            }
        }

        val targetState = _uiState.value
        loadArticlesJob = viewModelScope.launch {
            val result = articleRepository.getArticles(
                query = targetState.searchQuery,
                categoryId = targetState.categoryId,
                contentType = targetState.selectedContentType,
                page = 1,
                pageSize = 100
            )

            result.fold(
                onSuccess = { list ->
                    _uiState.value = _uiState.value.copy(
                        articles = list,
                        isLoading = false,
                        isRefreshing = false
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.message ?: "Lỗi tải danh sách trong danh mục"
                    )
                }
            )
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            loadArticles(refresh = true)
        }
    }

    fun onContentTypeChange(type: String?) {
        if (_uiState.value.selectedContentType == type) return
        loadArticlesJob?.cancel()
        _uiState.value = _uiState.value.copy(
            selectedContentType = type,
            articles = emptyList(),
            isLoading = true,
            isRefreshing = false
        )
        loadArticles(refresh = true)
    }

    fun toggleFavorite(article: ArticleListItem) {
        viewModelScope.launch {
            val newFav = !article.isFavorite
            _uiState.value = _uiState.value.copy(
                articles = _uiState.value.articles.map {
                    if (it.id == article.id) it.copy(isFavorite = newFav) else it
                }
            )
            articleRepository.updateArticle(article.id, isFavorite = newFav)
        }
    }

    fun deleteArticle(article: ArticleListItem) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                articles = _uiState.value.articles.filter { it.id != article.id }
            )
            val result = articleRepository.deleteArticle(article.id)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(userMessage = "Đã xoá: ${article.title.take(25)}")
                    categoryRepository.getCategories()
                },
                onFailure = {
                    loadArticles(refresh = true)
                    _uiState.value = _uiState.value.copy(errorMessage = "Không thể xoá mục này")
                }
            )
        }
    }

    fun updateArticleTitle(articleId: Int, newTitle: String) {
        viewModelScope.launch {
            articleRepository.updateArticle(articleId, title = newTitle).onSuccess {
                loadArticles(refresh = true)
            }
        }
    }

    fun updateArticleCategory(articleId: Int, newCategoryId: Int) {
        viewModelScope.launch {
            articleRepository.updateArticle(articleId, categoryId = newCategoryId).onSuccess {
                loadArticles(refresh = true)
                categoryRepository.getCategories()
            }
        }
    }

    fun uploadFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isUploading = true,
                uploadProgressMessage = if (uris.size == 1) "Đang lưu tệp tin vào danh mục..." else "Đang lưu ${uris.size} tệp tin vào danh mục...",
                uploadFailureDialogData = null
            )
            var successCount = 0
            val failures = mutableListOf<UploadFailureItem>()

            for ((index, uri) in uris.withIndex()) {
                val (qName, qSize) = articleRepository.queryFileInfo(uri)
                val fileName = qName ?: "tệp_${index + 1}"
                val sizeMb = if (qSize > 0) qSize.toDouble() / (1024 * 1024) else 0.0

                if (uris.size > 1) {
                    val sizeText = if (sizeMb > 0) String.format(" (%.1f MB)", sizeMb) else ""
                    _uiState.value = _uiState.value.copy(
                        uploadProgressMessage = "Đang lưu tệp ${index + 1}/${uris.size}: $fileName$sizeText..."
                    )
                }

                val progressCallback: (Long, Long) -> Unit = { written, total ->
                    val writtenMb = written.toDouble() / (1024 * 1024)
                    val totalMb = if (total > 0) total.toDouble() / (1024 * 1024) else sizeMb
                    val percent = if (totalMb > 0) ((writtenMb / totalMb) * 100).toInt().coerceIn(0, 100) else 0
                    val filePrefix = if (uris.size > 1) "[${index + 1}/${uris.size}] " else ""
                    val progressText = if (totalMb >= 1.0) {
                        String.format("%sĐang tải lên %s: %.1f MB / %.1f MB (%d%%)", filePrefix, fileName, writtenMb, totalMb, percent)
                    } else {
                        String.format("%sĐang tải lên %s: %d%%", filePrefix, fileName, percent)
                    }
                    _uiState.value = _uiState.value.copy(uploadProgressMessage = progressText)
                }

                var uploaded = false
                var lastErr = "Lỗi không xác định"
                for (attempt in 1..2) {
                    try {
                        val res = articleRepository.uploadFile(
                            uri = uri,
                            categoryId = categoryId,
                            onProgress = progressCallback
                        )
                        if (res.isSuccess) {
                            successCount++
                            uploaded = true
                            break
                        } else {
                            lastErr = res.exceptionOrNull()?.message ?: "Lỗi tải lên"
                            if (attempt < 2) {
                                kotlinx.coroutines.delay(1000)
                            }
                        }
                    } catch (e: Throwable) {
                        lastErr = e.message ?: "Lỗi kết nối máy chủ"
                        if (attempt < 2) {
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                }

                if (!uploaded) {
                    failures.add(
                        UploadFailureItem(
                            fileName = fileName,
                            fileSizeMb = sizeMb,
                            errorReason = lastErr,
                            uri = uri
                        )
                    )
                }

                if ((index + 1) % 5 == 0) {
                    System.gc()
                }
            }

            val failedCount = failures.size
            _uiState.value = _uiState.value.copy(
                isUploading = false,
                uploadProgressMessage = null,
                userMessage = when {
                    failedCount == 0 -> "Đã lưu thành công tất cả $successCount mục vào danh mục!"
                    successCount > 0 -> "Đã lưu $successCount/${uris.size} mục ($failedCount mục không thành công)"
                    else -> "Không thể lưu tệp: ${failures.firstOrNull()?.errorReason ?: "Lỗi kết nối"}"
                },
                uploadFailureDialogData = if (failures.isNotEmpty()) {
                    UploadFailureDialogData(
                        successCount = successCount,
                        totalCount = uris.size,
                        failures = failures
                    )
                } else null
            )
            loadArticles(refresh = true)
            categoryRepository.getCategories()
        }
    }

    fun dismissUploadFailureDialog() {
        _uiState.value = _uiState.value.copy(uploadFailureDialogData = null)
    }

    fun retryFailedUploads() {
        val failedUris = _uiState.value.uploadFailureDialogData?.failures?.map { it.uri } ?: emptyList()
        dismissUploadFailureDialog()
        if (failedUris.isNotEmpty()) {
            uploadFiles(failedUris)
        }
    }

    fun clearUserMessage() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

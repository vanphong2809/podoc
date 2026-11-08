package com.podoc.app.ui.screens.category

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.data.model.Category
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
    val isUploading: Boolean = false
)

class CategoryDetailViewModel(
    private val categoryId: Int,
    categoryName: String,
    categoryColor: String,
    private val articleRepository: ArticleRepository,
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
            _uiState.value = _uiState.value.copy(isUploading = true)
            var successCount = 0
            var firstError: String? = null

            for ((index, uri) in uris.withIndex()) {
                try {
                    val res = articleRepository.uploadFile(uri, categoryId = categoryId)
                    res.fold(
                        onSuccess = { successCount++ },
                        onFailure = { err ->
                            if (firstError == null) firstError = err.message
                        }
                    )
                } catch (e: Throwable) {
                    if (firstError == null) firstError = e.message
                }
                if ((index + 1) % 5 == 0) {
                    System.gc()
                }
            }

            _uiState.value = _uiState.value.copy(
                isUploading = false,
                userMessage = when {
                    firstError != null && successCount > 0 -> "Đã lưu $successCount tệp. Lỗi: $firstError"
                    firstError != null -> "Tải lên thất bại: $firstError"
                    else -> "Đã lưu thành công $successCount mục vào danh mục!"
                }
            )
            loadArticles(refresh = true)
            categoryRepository.getCategories()
        }
    }

    fun clearUserMessage() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

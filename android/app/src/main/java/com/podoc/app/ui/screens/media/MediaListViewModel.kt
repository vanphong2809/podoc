package com.podoc.app.ui.screens.media

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

data class MediaListUiState(
    val contentType: String, // "image", "video", "audio", "file"
    val title: String,       // "Hình ảnh", "Video", "Âm thanh", "Tệp tin"
    val articles: List<ArticleListItem> = emptyList(),
    val categories: List<Category> = emptyList(),
    val searchQuery: String = "",
    val page: Int = 1,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<Int> = emptySet(),
    val isGridView: Boolean = true,
    val isMovingMultiple: Boolean = false,
    val isDeletingMultiple: Boolean = false,
    val errorMessage: String? = null,
    val userMessage: String? = null
)

class MediaListViewModel(
    val contentType: String,
    val title: String,
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MediaListUiState(
            contentType = contentType,
            title = title,
            isGridView = contentType in listOf("image", "video")
        )
    )
    val uiState: StateFlow<MediaListUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var loadArticlesJob: Job? = null

    init {
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
        val targetPage = if (refresh) 1 else _uiState.value.page + 1
        if (!refresh) {
            val currentState = _uiState.value
            if (currentState.isLoading || currentState.isRefreshing || !currentState.hasMore) return
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
                contentType = targetState.contentType,
                page = targetPage,
                pageSize = 100
            )

            result.fold(
                onSuccess = { list ->
                    val nextArticles = if (refresh) list else targetState.articles + list
                    _uiState.value = _uiState.value.copy(
                        articles = nextArticles,
                        page = targetPage,
                        hasMore = list.size >= 100,
                        isLoading = false,
                        isRefreshing = false
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.message ?: "Không thể tải danh sách ${targetState.title.lowercase()}"
                    )
                }
            )
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            loadArticles(refresh = true)
        }
    }

    fun toggleViewMode() {
        _uiState.value = _uiState.value.copy(isGridView = !_uiState.value.isGridView)
    }

    fun startSelectionMode() {
        _uiState.value = _uiState.value.copy(isSelectionMode = true)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(isSelectionMode = false, selectedIds = emptySet())
    }

    fun toggleSelect(id: Int) {
        val current = _uiState.value.selectedIds
        val next = if (current.contains(id)) current - id else current + id
        _uiState.value = _uiState.value.copy(isSelectionMode = true, selectedIds = next)
    }

    fun selectAll() {
        val allIds = _uiState.value.articles.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(isSelectionMode = true, selectedIds = allIds)
    }

    fun moveSelectedArticlesToCategory(targetCategoryId: Int, onDone: (() -> Unit)? = null) {
        val selectedIds = _uiState.value.selectedIds.toList()
        if (selectedIds.isEmpty()) return

        val targetCategory = _uiState.value.categories.find { it.id == targetCategoryId }
        val targetCatName = targetCategory?.name ?: "danh mục mới"

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isMovingMultiple = true)
            val result = articleRepository.batchUpdateCategory(selectedIds, targetCategoryId)
            result.fold(
                onSuccess = {
                    val count = selectedIds.size
                    val updatedArticles = _uiState.value.articles.map { art ->
                        if (art.id in selectedIds) {
                            art.copy(categoryId = targetCategoryId, categoryName = targetCatName)
                        } else {
                            art
                        }
                    }

                    _uiState.value = _uiState.value.copy(
                        articles = updatedArticles,
                        isSelectionMode = false,
                        selectedIds = emptySet(),
                        isMovingMultiple = false,
                        userMessage = "Đã chuyển $count mục sang '$targetCatName'"
                    )
                    categoryRepository.getCategories()
                    onDone?.invoke()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isMovingMultiple = false,
                        errorMessage = err.message ?: "Lỗi khi chuyển danh mục"
                    )
                }
            )
        }
    }

    fun deleteSelectedArticles(onDone: (() -> Unit)? = null) {
        val selectedIds = _uiState.value.selectedIds.toList()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeletingMultiple = true)
            val result = articleRepository.deleteArticles(selectedIds)
            result.fold(
                onSuccess = {
                    val count = selectedIds.size
                    val updated = _uiState.value.articles.filterNot { it.id in selectedIds }
                    _uiState.value = _uiState.value.copy(
                        articles = updated,
                        isSelectionMode = false,
                        selectedIds = emptySet(),
                        isDeletingMultiple = false,
                        userMessage = "Đã xoá $count mục thành công"
                    )
                    categoryRepository.getCategories()
                    onDone?.invoke()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isDeletingMultiple = false,
                        errorMessage = err.message ?: "Lỗi khi xoá các mục đã chọn"
                    )
                }
            )
        }
    }

    fun updateArticleCategory(articleId: Int, newCategoryId: Int) {
        val targetCategory = _uiState.value.categories.find { it.id == newCategoryId }
        val targetCatName = targetCategory?.name ?: ""

        viewModelScope.launch {
            val result = articleRepository.updateArticle(articleId, categoryId = newCategoryId)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        articles = _uiState.value.articles.map { art ->
                            if (art.id == articleId) art.copy(categoryId = newCategoryId, categoryName = targetCatName) else art
                        },
                        userMessage = "Đã chuyển sang '$targetCatName'"
                    )
                    categoryRepository.getCategories()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(errorMessage = err.message ?: "Không thể đổi danh mục")
                }
            )
        }
    }

    fun updateArticleTitle(articleId: Int, newTitle: String) {
        viewModelScope.launch {
            articleRepository.updateArticle(articleId, title = newTitle).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        articles = _uiState.value.articles.map {
                            if (it.id == articleId) it.copy(title = newTitle) else it
                        },
                        userMessage = "Đã đổi tiêu đề thành công"
                    )
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(errorMessage = "Không thể đổi tiêu đề")
                }
            )
        }
    }

    fun deleteArticle(article: ArticleListItem) {
        viewModelScope.launch {
            articleRepository.deleteArticle(article.id).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        articles = _uiState.value.articles.filter { it.id != article.id },
                        userMessage = "Đã xoá '${article.title}'"
                    )
                    categoryRepository.getCategories()
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(errorMessage = "Không thể xoá")
                }
            )
        }
    }

    fun toggleFavorite(articleId: Int) {
        val article = _uiState.value.articles.find { it.id == articleId } ?: return
        val newFav = !article.isFavorite
        viewModelScope.launch {
            articleRepository.updateArticle(articleId, isFavorite = newFav).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        articles = _uiState.value.articles.map {
                            if (it.id == articleId) it.copy(isFavorite = newFav) else it
                        }
                    )
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(errorMessage = "Không thể cập nhật yêu thích")
                }
            )
        }
    }

    fun clearUserMessage() { _uiState.value = _uiState.value.copy(userMessage = null) }
    fun clearErrorMessage() { _uiState.value = _uiState.value.copy(errorMessage = null) }
}


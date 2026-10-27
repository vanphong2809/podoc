package com.podoc.app.ui.screens.library

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

data class LibraryUiState(
    val articles: List<ArticleListItem> = emptyList(),
    val categories: List<Category> = emptyList(),
    val searchQuery: String = "",
    val selectedCategoryId: Int? = null,
    val selectedSourceType: String? = null, // null (Tất cả), "URL", "FILE"
    val selectedContentType: String? = null, // null (Tất cả), "article", "file", "image", "video"
    val onlyFavorites: Boolean = false,
    val selectedSort: String = "newest",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    val recentlyDeletedArticle: ArticleListItem? = null,
    val isUploading: Boolean = false,
    val uploadProgressMessage: String? = null,
    val userMessage: String? = null
)

class LibraryViewModel(
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

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
        val currentState = _uiState.value
        val targetPage = if (refresh) 1 else currentState.page

        if (refresh) {
            _uiState.value = currentState.copy(isRefreshing = true, errorMessage = null)
        } else {
            if (currentState.isLoading || !currentState.hasMore) return
            _uiState.value = currentState.copy(isLoading = true, errorMessage = null)
        }

        viewModelScope.launch {
            val result = articleRepository.getArticles(
                query = currentState.searchQuery,
                categoryId = currentState.selectedCategoryId,
                sourceType = currentState.selectedSourceType,
                contentType = currentState.selectedContentType,
                favorite = if (currentState.onlyFavorites) true else null,
                sort = currentState.selectedSort,
                page = targetPage,
                pageSize = 20
            )

            result.fold(
                onSuccess = { newItems ->
                    val updatedList = if (refresh) newItems else currentState.articles + newItems
                    _uiState.value = _uiState.value.copy(
                        articles = updatedList,
                        page = targetPage + 1,
                        hasMore = newItems.size >= 20,
                        isLoading = false,
                        isRefreshing = false
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.message ?: "Lỗi tải danh sách bài viết"
                    )
                }
            )
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400) // Debounce 400ms
            loadArticles(refresh = true)
        }
    }

    fun onCategoryFilterChange(categoryId: Int?) {
        if (_uiState.value.selectedCategoryId == categoryId) {
            _uiState.value = _uiState.value.copy(selectedCategoryId = null)
        } else {
            _uiState.value = _uiState.value.copy(selectedCategoryId = categoryId)
        }
        loadArticles(refresh = true)
    }

    fun onSourceTypeFilterChange(sourceType: String?) {
        if (_uiState.value.selectedSourceType == sourceType) {
            _uiState.value = _uiState.value.copy(selectedSourceType = null)
        } else {
            _uiState.value = _uiState.value.copy(selectedSourceType = sourceType)
        }
        loadArticles(refresh = true)
    }

    fun onContentTypeFilterChange(contentType: String?) {
        if (_uiState.value.selectedContentType == contentType) {
            _uiState.value = _uiState.value.copy(selectedContentType = null)
        } else {
            _uiState.value = _uiState.value.copy(selectedContentType = contentType)
        }
        loadArticles(refresh = true)
    }

    fun onToggleFavoritesFilter() {
        _uiState.value = _uiState.value.copy(onlyFavorites = !_uiState.value.onlyFavorites)
        loadArticles(refresh = true)
    }

    fun onSortChange(sort: String) {
        _uiState.value = _uiState.value.copy(selectedSort = sort)
        loadArticles(refresh = true)
    }

    fun toggleFavorite(article: ArticleListItem) {
        val newFav = !article.isFavorite
        // Cập nhật lạc quan trên UI
        _uiState.value = _uiState.value.copy(
            articles = _uiState.value.articles.map {
                if (it.id == article.id) it.copy(isFavorite = newFav) else it
            }
        )

        viewModelScope.launch {
            articleRepository.updateArticle(article.id, isFavorite = newFav).onFailure {
                // Hoàn lại nếu lỗi
                _uiState.value = _uiState.value.copy(
                    articles = _uiState.value.articles.map {
                        if (it.id == article.id) it.copy(isFavorite = article.isFavorite) else it
                    }
                )
            }
        }
    }

    fun updateArticleTitle(id: Int, newTitle: String) {
        viewModelScope.launch {
            articleRepository.updateArticle(id, title = newTitle).onSuccess { updated ->
                _uiState.value = _uiState.value.copy(
                    articles = _uiState.value.articles.map {
                        if (it.id == id) it.copy(title = updated.title) else it
                    }
                )
            }
        }
    }

    fun updateArticleCategory(id: Int, newCategoryId: Int) {
        viewModelScope.launch {
            articleRepository.updateArticle(id, categoryId = newCategoryId).onSuccess { updated ->
                _uiState.value = _uiState.value.copy(
                    articles = _uiState.value.articles.map {
                        if (it.id == id) it.copy(
                            categoryId = updated.categoryId,
                            categoryName = updated.categoryName
                        ) else it
                    }
                )
            }
        }
    }

    fun deleteArticle(article: ArticleListItem) {
        // Tạm xoá khỏi UI để hiển thị Snackbar Hoàn tác
        val remaining = _uiState.value.articles.filter { it.id != article.id }
        _uiState.value = _uiState.value.copy(
            articles = remaining,
            recentlyDeletedArticle = article
        )

        viewModelScope.launch {
            // Đợi 4 giây cho người dùng có cơ hội bấm Hoàn tác
            delay(4000)
            if (_uiState.value.recentlyDeletedArticle?.id == article.id) {
                // Người dùng không bấm Hoàn tác -> Gọi xoá thật trên server
                articleRepository.deleteArticle(article.id)
                _uiState.value = _uiState.value.copy(recentlyDeletedArticle = null)
            }
        }
    }

    fun undoDelete() {
        val deleted = _uiState.value.recentlyDeletedArticle ?: return
        _uiState.value = _uiState.value.copy(
            articles = listOf(deleted) + _uiState.value.articles,
            recentlyDeletedArticle = null
        )
    }

    fun uploadFiles(uris: List<Uri>, onDone: ((count: Int) -> Unit)? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isUploading = true,
                uploadProgressMessage = if (uris.size == 1) "Đang lưu tệp tin vào thư viện..." else "Đang lưu ${uris.size} tệp tin vào thư viện..."
            )
            var successCount = 0
            var lastErrorMessage: String? = null
            for ((index, uri) in uris.withIndex()) {
                if (uris.size > 1) {
                    _uiState.value = _uiState.value.copy(
                        uploadProgressMessage = "Đang lưu tệp ${index + 1}/${uris.size}..."
                    )
                }
                articleRepository.uploadFile(uri).fold(
                    onSuccess = { successCount++ },
                    onFailure = { error ->
                        lastErrorMessage = error.message
                    }
                )
            }
            _uiState.value = _uiState.value.copy(
                isUploading = false,
                uploadProgressMessage = null,
                userMessage = if (successCount > 0) {
                    "Đã lưu thành công $successCount tệp tin vào thư viện"
                } else {
                    lastErrorMessage ?: "Không thể lưu tệp tin"
                }
            )
            // Tải lại danh sách thư viện ngay lập tức để hiện các file vừa lưu
            loadArticles(refresh = true)
            onDone?.invoke(successCount)
        }
    }

    fun clearUserMessage() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }
}

package com.podoc.app.ui.screens.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.ArticleDetail
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
    val userMessage: String? = null,
    val isSelectionMode: Boolean = false,
    val selectedMediaIds: Set<Int> = emptySet(),
    val isDeletingMultiple: Boolean = false
)

class LibraryViewModel(
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var loadArticlesJob: Job? = null

    init {
        // Lắng nghe cập nhật danh mục từ CategoryRepository theo thời gian thực
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
            if (currentState.isLoading || currentState.isRefreshing || !currentState.hasMore) return
            _uiState.value = currentState.copy(isLoading = true, errorMessage = null)
        } else {
            loadArticlesJob?.cancel()
            val currentArticles = _uiState.value.articles
            if (currentArticles.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    isRefreshing = false,
                    page = 1,
                    hasMore = true,
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = true,
                    page = 1,
                    errorMessage = null
                )
            }
        }

        val targetState = _uiState.value
        val targetPage = if (refresh) 1 else targetState.page
        val isMediaGrid = targetState.selectedContentType in listOf("image", "video")
        val targetPageSize = if (isMediaGrid) 80 else 20

        loadArticlesJob = viewModelScope.launch {
            val result = articleRepository.getArticles(
                query = targetState.searchQuery,
                categoryId = targetState.selectedCategoryId,
                sourceType = targetState.selectedSourceType,
                contentType = targetState.selectedContentType,
                favorite = if (targetState.onlyFavorites) true else null,
                sort = targetState.selectedSort,
                page = targetPage,
                pageSize = targetPageSize
            )

            result.fold(
                onSuccess = { newItems ->
                    val currentList = _uiState.value.articles
                    val updatedList = if (refresh) newItems else currentList + newItems
                    _uiState.value = _uiState.value.copy(
                        articles = updatedList,
                        page = targetPage + 1,
                        hasMore = newItems.size >= targetPageSize,
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

    fun onSelectAll() {
        loadArticlesJob?.cancel()
        _uiState.value = _uiState.value.copy(
            selectedContentType = null,
            onlyFavorites = false,
            selectedCategoryId = null,
            articles = emptyList(),
            isLoading = true,
            isRefreshing = false,
            page = 1,
            hasMore = true,
            isSelectionMode = false,
            selectedMediaIds = emptySet()
        )
        loadArticles(refresh = true)
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
        loadArticlesJob?.cancel()
        val newCatId = if (_uiState.value.selectedCategoryId == categoryId) null else categoryId
        _uiState.value = _uiState.value.copy(
            selectedCategoryId = newCatId,
            articles = emptyList(),
            isLoading = true,
            isRefreshing = false,
            page = 1,
            hasMore = true
        )
        loadArticles(refresh = true)
    }

    fun onSourceTypeFilterChange(sourceType: String?) {
        loadArticlesJob?.cancel()
        val newSource = if (_uiState.value.selectedSourceType == sourceType) null else sourceType
        _uiState.value = _uiState.value.copy(
            selectedSourceType = newSource,
            articles = emptyList(),
            isLoading = true,
            isRefreshing = false,
            page = 1,
            hasMore = true
        )
        loadArticles(refresh = true)
    }

    fun onContentTypeFilterChange(contentType: String?) {
        loadArticlesJob?.cancel()
        val newType = if (_uiState.value.selectedContentType == contentType) null else contentType
        _uiState.value = _uiState.value.copy(
            selectedContentType = newType,
            articles = emptyList(),
            isLoading = true,
            isRefreshing = false,
            page = 1,
            hasMore = true,
            isSelectionMode = false,
            selectedMediaIds = emptySet()
        )
        loadArticles(refresh = true)
    }

    fun onToggleFavoritesFilter() {
        loadArticlesJob?.cancel()
        val newFav = !_uiState.value.onlyFavorites
        _uiState.value = _uiState.value.copy(
            onlyFavorites = newFav,
            articles = emptyList(),
            isLoading = true,
            isRefreshing = false,
            page = 1,
            hasMore = true
        )
        loadArticles(refresh = true)
    }

    fun onSortChange(sort: String) {
        loadArticlesJob?.cancel()
        _uiState.value = _uiState.value.copy(
            selectedSort = sort,
            articles = emptyList(),
            isLoading = true,
            isRefreshing = false,
            page = 1,
            hasMore = true
        )
        loadArticles(refresh = true)
    }

    fun toggleFavorite(article: ArticleListItem) {
        val newFav = !article.isFavorite
        _uiState.value = _uiState.value.copy(
            articles = _uiState.value.articles.map {
                if (it.id == article.id) it.copy(isFavorite = newFav) else it
            }
        )

        viewModelScope.launch {
            articleRepository.updateArticle(article.id, isFavorite = newFav).onFailure {
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
                // Đồng bộ lại số đếm bài viết trong từng danh mục
                categoryRepository.getCategories()
            }
        }
    }

    fun deleteArticle(article: ArticleListItem) {
        // Cập nhật giao diện ngay lập tức và gửi lệnh xoá lên server ngay lập tức
        val remaining = _uiState.value.articles.filter { it.id != article.id }
        _uiState.value = _uiState.value.copy(
            articles = remaining,
            recentlyDeletedArticle = null
        )

        viewModelScope.launch {
            val result = articleRepository.deleteArticle(article.id)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        userMessage = "Đã xoá: ${article.title.take(25)}"
                    )
                    // Cập nhật lại số đếm danh mục sau khi xoá
                    categoryRepository.getCategories()
                },
                onFailure = { error ->
                    // Khôi phục lại bài viết nếu xoá thất bại
                    _uiState.value = _uiState.value.copy(
                        articles = listOf(article) + _uiState.value.articles,
                        errorMessage = "Không thể xoá: ${error.message ?: "Lỗi kết nối máy chủ"}"
                    )
                }
            )
        }
    }

    fun undoDelete() {
        // Không sử dụng cơ chế delay xoá để tránh xung đột dữ liệu
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
            // Tải lại danh sách thư viện và số đếm danh mục ngay lập tức
            loadArticles(refresh = true)
            categoryRepository.getCategories()
            onDone?.invoke(successCount)
        }
    }

    fun uploadAndConvertToPdf(uri: Uri, onSuccess: (ArticleDetail) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isUploading = true,
                uploadProgressMessage = "Đang tải lên và chuyển đổi sang PDF..."
            )
            articleRepository.uploadAndConvertToPdf(uri).fold(
                onSuccess = { newArticle ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        uploadProgressMessage = null,
                        userMessage = "Chuyển đổi sang PDF thành công!"
                    )
                    loadArticles(refresh = true)
                    categoryRepository.getCategories()
                    onSuccess(newArticle)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        uploadProgressMessage = null,
                        errorMessage = "Chuyển đổi sang PDF thất bại: ${error.message ?: "Lỗi hệ thống"}"
                    )
                }
            )
        }
    }

    fun clearUserMessage() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }

    fun startSelectionMode(initialSelectedId: Int? = null) {
        val newSelection = if (initialSelectedId != null) setOf(initialSelectedId) else emptySet()
        _uiState.value = _uiState.value.copy(
            isSelectionMode = true,
            selectedMediaIds = newSelection
        )
    }

    fun toggleSelectMedia(id: Int) {
        val current = _uiState.value.selectedMediaIds
        val next = if (current.contains(id)) {
            current - id
        } else {
            current + id
        }
        _uiState.value = _uiState.value.copy(
            isSelectionMode = true,
            selectedMediaIds = next
        )
    }

    fun selectAllMedia() {
        val allIds = _uiState.value.articles.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(
            isSelectionMode = true,
            selectedMediaIds = allIds
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedMediaIds = emptySet()
        )
    }

    fun deleteSelectedMedia(onDone: ((count: Int) -> Unit)? = null) {
        val idsToDelete = _uiState.value.selectedMediaIds.toList()
        if (idsToDelete.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeletingMultiple = true)
            val result = articleRepository.deleteArticles(idsToDelete)
            result.fold(
                onSuccess = {
                    val count = idsToDelete.size
                    val typeStr = when (_uiState.value.selectedContentType) {
                        "video" -> "video"
                        "image" -> "hình ảnh"
                        else -> "mục"
                    }
                    _uiState.value = _uiState.value.copy(
                        articles = _uiState.value.articles.filterNot { it.id in idsToDelete },
                        isSelectionMode = false,
                        selectedMediaIds = emptySet(),
                        isDeletingMultiple = false,
                        userMessage = "Đã xoá $count $typeStr thành công"
                    )
                    categoryRepository.getCategories()
                    onDone?.invoke(count)
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
}

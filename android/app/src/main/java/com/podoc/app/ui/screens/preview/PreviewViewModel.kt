package com.podoc.app.ui.screens.preview

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.ArticleDraft
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.CreateArticleRequest
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PreviewUiState(
    val isLoading: Boolean = false,
    val draft: ArticleDraft? = null,
    val editedTitle: String = "",
    val selectedCategoryId: Int = 7, // Mặc định Chưa phân loại (ID 7)
    val categories: List<Category> = emptyList(),
    val isSaved: Boolean = false,
    val savedArticleId: Int? = null,
    val errorMessage: String? = null,
    val isBatchMode: Boolean = false,
    val batchCurrentIndex: Int = 0,
    val batchTotalCount: Int = 0,
    val isUploadingFile: Boolean = false,
    val uploadProgressMessage: String? = null,
    val isWebFormatMode: Boolean = false
)

class PreviewViewModel(
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private var lastUrl: String? = null
    private var lastUri: Uri? = null

    init {
        // Lắng nghe cập nhật danh mục từ CategoryRepository theo thời gian thực
        viewModelScope.launch {
            categoryRepository.categoriesFlow.collect { updatedList ->
                if (updatedList.isNotEmpty()) {
                    val currentSelected = _uiState.value.selectedCategoryId
                    val newSelected = if (updatedList.any { it.id == currentSelected }) {
                        currentSelected
                    } else if (currentSelected > 0) {
                        currentSelected
                    } else {
                        updatedList.find { it.isSystem }?.id ?: updatedList.firstOrNull()?.id ?: 1
                    }
                    _uiState.value = _uiState.value.copy(
                        categories = updatedList,
                        selectedCategoryId = newSelected
                    )
                }
            }
        }
        loadCategories()
    }

    fun loadCategories(targetCategoryId: Int? = null) {
        viewModelScope.launch {
            categoryRepository.getCategories().onSuccess { list ->
                val currentSelected = targetCategoryId ?: _uiState.value.selectedCategoryId
                val validSelected = if (list.any { it.id == currentSelected }) {
                    currentSelected
                } else if (currentSelected > 0) {
                    currentSelected
                } else {
                    list.find { it.isSystem }?.id ?: list.firstOrNull()?.id ?: 1
                }
                _uiState.value = _uiState.value.copy(
                    categories = list,
                    selectedCategoryId = validSelected
                )
            }
        }
    }

    fun reset() {
        val cats = _uiState.value.categories
        val defaultCat = cats.find { it.isSystem }?.id ?: 1
        _uiState.value = PreviewUiState(
            categories = cats,
            selectedCategoryId = defaultCat
        )
        lastUrl = null
        lastUri = null
    }

    fun extractUrl(url: String, initialCategoryId: Int? = null, asWebFormat: Boolean = false) {
        lastUrl = url
        lastUri = null
        val cats = _uiState.value.categories
        val defaultCat = initialCategoryId
            ?: if (_uiState.value.selectedCategoryId > 0 && cats.any { it.id == _uiState.value.selectedCategoryId }) {
                _uiState.value.selectedCategoryId
            } else {
                cats.find { it.isSystem }?.id ?: cats.firstOrNull()?.id ?: 1
            }
        _uiState.value = PreviewUiState(
            isLoading = true,
            categories = cats,
            selectedCategoryId = defaultCat,
            draft = null,
            editedTitle = "",
            isSaved = false,
            savedArticleId = null,
            errorMessage = null,
            isBatchMode = false,
            batchCurrentIndex = 0,
            batchTotalCount = 0,
            isWebFormatMode = asWebFormat
        )
        loadCategories(targetCategoryId = defaultCat)

        viewModelScope.launch {
            articleRepository.extractUrl(url).fold(
                onSuccess = { draft ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        draft = draft,
                        editedTitle = draft.title,
                        isSaved = false,
                        savedArticleId = null,
                        errorMessage = null
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        draft = null,
                        errorMessage = error.message ?: "Lỗi khi trích xuất liên kết"
                    )
                }
            )
        }
    }

    fun uploadFile(uri: Uri) {
        lastUri = uri
        lastUrl = null
        val cats = _uiState.value.categories
        val defaultCat = cats.find { it.isSystem }?.id ?: cats.firstOrNull()?.id ?: 1
        _uiState.value = PreviewUiState(
            isLoading = true,
            isUploadingFile = true,
            categories = cats,
            selectedCategoryId = defaultCat,
            uploadProgressMessage = "Đang lưu tệp tin vào thư viện...",
            draft = null,
            editedTitle = "",
            isSaved = false,
            savedArticleId = null,
            errorMessage = null,
            isBatchMode = false,
            batchCurrentIndex = 0,
            batchTotalCount = 0
        )

        viewModelScope.launch {
            articleRepository.uploadFile(uri, defaultCat).fold(
                onSuccess = { article ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isUploadingFile = false,
                        isSaved = true,
                        savedArticleId = article.id,
                        errorMessage = null
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isUploadingFile = false,
                        draft = null,
                        errorMessage = error.message ?: "Lỗi khi lưu tệp tin vào thư viện"
                    )
                }
            )
        }
    }

    fun extractFile(uri: Uri) {
        uploadFile(uri)
    }

    fun uploadMultipleFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            uploadFile(uris[0])
            return
        }

        val cats = _uiState.value.categories
        val defaultCat = cats.find { it.isSystem }?.id ?: cats.firstOrNull()?.id ?: 1
        _uiState.value = PreviewUiState(
            isLoading = true,
            isUploadingFile = true,
            isBatchMode = true,
            batchCurrentIndex = 0,
            batchTotalCount = uris.size,
            categories = cats,
            selectedCategoryId = defaultCat,
            uploadProgressMessage = "Đang lưu ${uris.size} tệp tin vào thư viện...",
            draft = null,
            isSaved = false,
            savedArticleId = null,
            errorMessage = null
        )

        viewModelScope.launch {
            var lastId: Int? = null
            for ((index, uri) in uris.withIndex()) {
                _uiState.value = _uiState.value.copy(
                    batchCurrentIndex = index + 1,
                    uploadProgressMessage = "Đang lưu tệp tin ${index + 1}/${uris.size}..."
                )
                articleRepository.uploadFile(uri, defaultCat).onSuccess { article ->
                    lastId = article.id
                }
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isUploadingFile = false,
                isSaved = true,
                savedArticleId = lastId
            )
        }
    }

    fun extractMultipleFiles(uris: List<Uri>) {
        uploadMultipleFiles(uris)
    }

    fun onTitleChange(newTitle: String) {
        _uiState.value = _uiState.value.copy(editedTitle = newTitle)
    }

    fun onCategorySelect(categoryId: Int) {
        _uiState.value = _uiState.value.copy(selectedCategoryId = categoryId)
    }

    fun createCategory(name: String) {
        viewModelScope.launch {
            categoryRepository.createCategory(name, "folder", "#4F46E5", 0).fold(
                onSuccess = { newCat ->
                    // Đảm bảo danh mục mới được thêm vào danh sách và chọn ngay danh mục vừa tạo
                    val currentCats = _uiState.value.categories
                    val updatedList = if (currentCats.none { it.id == newCat.id }) currentCats + newCat else currentCats
                    _uiState.value = _uiState.value.copy(
                        categories = updatedList,
                        selectedCategoryId = newCat.id
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = error.message ?: "Không thể tạo danh mục"
                    )
                }
            )
        }
    }

    /**
     * Lưu bài viết ở chế độ giữ nguyên định dạng web gốc (Full Web View)
     */
    fun saveAsWebFormat() {
        val draft = _uiState.value.draft ?: return
        val title = _uiState.value.editedTitle.ifBlank { draft.title }
        val targetCatId = _uiState.value.selectedCategoryId

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            val req = CreateArticleRequest(
                title = title,
                sourceType = "URL",
                sourceUrl = draft.sourceUrl,
                normalizedUrl = draft.normalizedUrl,
                fileName = null,
                filePath = null,
                mimeType = "text/html-web",
                contentMarkdown = "<!-- WEB_VIEW_MODE -->\n\n${draft.contentMarkdown}",
                excerpt = if (!draft.excerpt.isNullOrBlank()) draft.excerpt else "Trang web gốc: ${draft.sourceUrl}",
                author = draft.author,
                siteName = draft.siteName,
                imageUrl = draft.imageUrl,
                language = draft.language,
                readingMinutes = draft.readingMinutes,
                categoryId = targetCatId
            )

            articleRepository.createArticle(req).fold(
                onSuccess = { created ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSaved = true,
                        savedArticleId = created.id
                    )
                    categoryRepository.getCategories()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Lỗi khi lưu trang web"
                    )
                }
            )
        }
    }

    fun saveArticle() {
        val draft = _uiState.value.draft ?: return
        val title = _uiState.value.editedTitle.ifBlank { draft.title }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            val req = CreateArticleRequest(
                title = title,
                sourceType = if (draft.fileName != null) "FILE" else "URL",
                sourceUrl = draft.sourceUrl,
                normalizedUrl = draft.normalizedUrl,
                fileName = draft.fileName,
                filePath = draft.filePath,
                mimeType = draft.mimeType,
                contentMarkdown = draft.contentMarkdown,
                excerpt = draft.excerpt,
                author = draft.author,
                siteName = draft.siteName,
                imageUrl = draft.imageUrl,
                language = draft.language,
                readingMinutes = draft.readingMinutes,
                categoryId = _uiState.value.selectedCategoryId
            )

            articleRepository.createArticle(req).fold(
                onSuccess = { created ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSaved = true,
                        savedArticleId = created.id
                    )
                    categoryRepository.getCategories()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Lỗi khi lưu bài viết"
                    )
                }
            )
        }
    }

    fun quickSave() {
        val currentCat = _uiState.value.selectedCategoryId
        val targetCat = if (currentCat > 0) currentCat else (_uiState.value.categories.find { it.isSystem }?.id ?: 1)
        _uiState.value = _uiState.value.copy(selectedCategoryId = targetCat)
        saveArticle()
    }

    fun retry() {
        if (lastUrl != null) {
            extractUrl(lastUrl!!)
        } else if (lastUri != null) {
            extractFile(lastUri!!)
        }
    }
}

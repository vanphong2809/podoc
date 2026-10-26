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
    val batchTotalCount: Int = 0
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
        loadCategories()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategories().onSuccess { list ->
                val defaultCat = list.find { it.isSystem }?.id ?: list.firstOrNull()?.id ?: 1
                _uiState.value = _uiState.value.copy(
                    categories = list,
                    selectedCategoryId = if (_uiState.value.selectedCategoryId == 7) defaultCat else _uiState.value.selectedCategoryId
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

    fun extractUrl(url: String) {
        lastUrl = url
        lastUri = null
        val cats = _uiState.value.categories
        val defaultCat = cats.find { it.isSystem }?.id ?: 1
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
            batchTotalCount = 0
        )

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

    fun extractFile(uri: Uri) {
        lastUri = uri
        lastUrl = null
        val cats = _uiState.value.categories
        val defaultCat = cats.find { it.isSystem }?.id ?: 1
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
            batchTotalCount = 0
        )

        viewModelScope.launch {
            articleRepository.extractFile(uri).fold(
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
                        errorMessage = error.message ?: "Lỗi khi trích xuất tệp tin"
                    )
                }
            )
        }
    }

    fun extractMultipleFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            extractFile(uris[0])
            return
        }

        val cats = _uiState.value.categories
        val defaultCat = cats.find { it.isSystem }?.id ?: 1
        _uiState.value = PreviewUiState(
            isLoading = true,
            isBatchMode = true,
            batchCurrentIndex = 0,
            batchTotalCount = uris.size,
            categories = cats,
            selectedCategoryId = defaultCat,
            draft = null,
            isSaved = false,
            savedArticleId = null,
            errorMessage = null
        )

        viewModelScope.launch {
            var successCount = 0
            val defaultCat = _uiState.value.categories.find { it.isSystem }?.id ?: 1

            for ((index, uri) in uris.withIndex()) {
                _uiState.value = _uiState.value.copy(batchCurrentIndex = index + 1)
                articleRepository.extractFile(uri).onSuccess { draft ->
                    val createReq = CreateArticleRequest(
                        title = draft.title,
                        sourceType = "FILE",
                        fileName = draft.fileName,
                        mimeType = draft.mimeType,
                        contentMarkdown = draft.contentMarkdown,
                        excerpt = draft.excerpt,
                        author = draft.author,
                        siteName = draft.siteName,
                        categoryId = defaultCat,
                        readingMinutes = draft.readingMinutes
                    )
                    articleRepository.createArticle(createReq).onSuccess {
                        successCount++
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isSaved = true
            )
        }
    }

    fun onTitleChange(newTitle: String) {
        _uiState.value = _uiState.value.copy(editedTitle = newTitle)
    }

    fun onCategorySelect(categoryId: Int) {
        _uiState.value = _uiState.value.copy(selectedCategoryId = categoryId)
    }

    fun createCategory(name: String) {
        viewModelScope.launch {
            categoryRepository.createCategory(name, "folder", "#4F46E5", 0).onSuccess { newCat ->
                loadCategories()
                _uiState.value = _uiState.value.copy(selectedCategoryId = newCat.id)
            }
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
        // Lưu nhanh vào Chưa phân loại (is_system=True)
        val defaultCat = _uiState.value.categories.find { it.isSystem }?.id ?: 1
        _uiState.value = _uiState.value.copy(selectedCategoryId = defaultCat)
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

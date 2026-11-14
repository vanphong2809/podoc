package com.podoc.app.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.model.LatestBriefingResponse
import com.podoc.app.data.model.NewsBriefingResponse
import com.podoc.app.data.repository.ArticleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class NewsBriefingUiState(
    val isLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val latestBriefing: LatestBriefingResponse? = null,
    val generatedBriefing: NewsBriefingResponse? = null,
    val geminiApiKey: String = "",
    val errorMessage: String? = null,
    val userMessage: String? = null
)

class NewsBriefingViewModel(
    val articleRepository: ArticleRepository,
    private val dataStoreManager: DataStoreManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewsBriefingUiState())
    val uiState: StateFlow<NewsBriefingUiState> = _uiState.asStateFlow()

    init {
        loadLatestBriefing()
    }

    fun loadLatestBriefing() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val apiKey = dataStoreManager.geminiApiKeyFlow.first()
            _uiState.value = _uiState.value.copy(geminiApiKey = apiKey)

            val result = articleRepository.getLatestNewsBriefing()
            result.fold(
                onSuccess = { latest ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        latestBriefing = latest
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Không thể tải bản tin mới nhất"
                    )
                }
            )
        }
    }

    fun generateBriefingNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGenerating = true, errorMessage = null)
            val apiKey = dataStoreManager.geminiApiKeyFlow.first()

            val result = articleRepository.generateNewsBriefing(
                geminiApiKey = apiKey.ifBlank { null }
            )

            result.fold(
                onSuccess = { res ->
                    _uiState.value = _uiState.value.copy(
                        isGenerating = false,
                        generatedBriefing = res,
                        latestBriefing = LatestBriefingResponse(
                            articleId = res.articleId,
                            title = res.title,
                            categoryId = res.categoryId,
                            categoryName = res.categoryName,
                            filePath = res.filePath,
                            imageUrl = res.imageUrl,
                            contentMarkdown = res.briefing?.markdownContent,
                            excerpt = res.briefing?.greeting ?: res.title,
                            slides = res.slides,
                            createdAt = res.createdAt
                        ),
                        userMessage = "Đã tạo Bản tin sáng mới thành công!"
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isGenerating = false,
                        errorMessage = err.message ?: "Lỗi khi tạo bản tin sáng"
                    )
                }
            )
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, userMessage = null)
    }
}


package com.podoc.app.ui.screens.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.StorageStats
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CategoryUiState(
    val categories: List<Category> = emptyList(),
    val storageStats: StorageStats? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

class CategoryViewModel(
    private val categoryRepository: CategoryRepository,
    private val articleRepository: ArticleRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryUiState())
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

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
    }

    fun loadCategories(refresh: Boolean = false) {
        if (refresh) {
            _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
        } else {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        }
        viewModelScope.launch {
            categoryRepository.getCategories().fold(
                onSuccess = { list ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        categories = list
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.message ?: "Lỗi tải danh mục"
                    )
                }
            )
        }
        loadStorageStats()
    }

    fun loadStorageStats() {
        viewModelScope.launch {
            articleRepository.getStorageStats().fold(
                onSuccess = { stats ->
                    _uiState.value = _uiState.value.copy(storageStats = stats)
                },
                onFailure = {
                    // Không chặn UI nếu thống kê dung lượng chưa tải được
                }
            )
        }
    }

    fun createCategory(name: String, color: String, icon: String, sortOrder: Int) {
        viewModelScope.launch {
            categoryRepository.createCategory(name, icon, color, sortOrder).onSuccess {
                loadCategories()
            }
        }
    }

    fun updateCategory(id: Int, name: String?, color: String?, icon: String?, sortOrder: Int?) {
        viewModelScope.launch {
            categoryRepository.updateCategory(id, name, icon, color, sortOrder).onSuccess {
                loadCategories()
            }
        }
    }

    fun deleteCategory(id: Int) {
        viewModelScope.launch {
            categoryRepository.deleteCategory(id).onSuccess {
                loadCategories()
            }
        }
    }
}

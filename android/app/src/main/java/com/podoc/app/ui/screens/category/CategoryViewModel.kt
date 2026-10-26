package com.podoc.app.ui.screens.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.Category
import com.podoc.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CategoryUiState(
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class CategoryViewModel(private val categoryRepository: CategoryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryUiState())
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    init {
        loadCategories()
    }

    fun loadCategories() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            categoryRepository.getCategories().fold(
                onSuccess = { list ->
                    _uiState.value = _uiState.value.copy(isLoading = false, categories = list)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Lỗi tải danh mục"
                    )
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

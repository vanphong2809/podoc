package com.podoc.app.ui.screens.tools

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.repository.ArticleRepository
import kotlinx.coroutines.launch

class ToolsViewModel(
    private val articleRepository: ArticleRepository
) : ViewModel() {

    var isConverting by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun uploadAndConvertToPdf(uri: Uri, onSuccess: (ArticleDetail) -> Unit) {
        viewModelScope.launch {
            isConverting = true
            statusMessage = "Đang tải tệp lên và chuyển đổi sang PDF..."
            errorMessage = null

            val result = articleRepository.uploadAndConvertToPdf(uri)
            result.fold(
                onSuccess = { newArticle ->
                    isConverting = false
                    statusMessage = null
                    onSuccess(newArticle)
                },
                onFailure = { error ->
                    isConverting = false
                    statusMessage = null
                    errorMessage = error.message ?: "Chuyển đổi sang PDF thất bại. Vui lòng kiểm tra lại tệp."
                }
            )
        }
    }

    fun clearError() {
        errorMessage = null
    }
}


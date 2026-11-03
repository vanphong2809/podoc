package com.podoc.app.ui.screens.tools

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.CreateArticleRequest
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.CategoryRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OcrViewModel(
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    var imageUri by mutableStateOf<Uri?>(null)
        private set

    var imageBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var isRecognizing by mutableStateOf(false)
        private set

    var recognizedText by mutableStateOf("")
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var isSaving by mutableStateOf(false)
        private set

    var saveSuccessMessage by mutableStateOf<String?>(null)
        private set

    var categories by mutableStateOf<List<Category>>(emptyList())
        private set

    var selectedCategoryId by mutableStateOf<Int?>(null)
        private set

    var documentTitle by mutableStateOf("")
        private set

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    init {
        loadCategories()
    }

    fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategories().fold(
                onSuccess = { list ->
                    categories = list
                    if (selectedCategoryId == null && list.isNotEmpty()) {
                        selectedCategoryId = list.first().id
                    }
                },
                onFailure = { /* ignore */ }
            )
        }
    }

    fun setSelectedCategory(catId: Int) {
        selectedCategoryId = catId
    }

    fun setDocTitle(title: String) {
        documentTitle = title
    }

    fun updateRecognizedText(newText: String) {
        recognizedText = newText
    }

    fun processImageUri(context: Context, uri: Uri) {
        imageUri = uri
        imageBitmap = null
        isRecognizing = true
        errorMessage = null
        recognizedText = ""

        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    isRecognizing = false
                    val text = visionText.text.trim()
                    recognizedText = text
                    if (text.isBlank()) {
                        errorMessage = "Không tìm thấy văn bản trong ảnh này. Vui lòng thử lại với ảnh rõ nét hơn."
                    } else {
                        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(40)
                        val timeStr = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date())
                        documentTitle = firstLine ?: "Tài liệu OCR ($timeStr)"
                    }
                }
                .addOnFailureListener { e ->
                    isRecognizing = false
                    errorMessage = "Lỗi nhận dạng ảnh: ${e.localizedMessage ?: "Vui lòng thử lại"}"
                }
        } catch (e: Exception) {
            isRecognizing = false
            errorMessage = "Không thể đọc tệp ảnh: ${e.localizedMessage}"
        }
    }

    fun processBitmap(bitmap: Bitmap) {
        imageBitmap = bitmap
        imageUri = null
        isRecognizing = true
        errorMessage = null
        recognizedText = ""

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    isRecognizing = false
                    val text = visionText.text.trim()
                    recognizedText = text
                    if (text.isBlank()) {
                        errorMessage = "Không tìm thấy văn bản trong ảnh này. Vui lòng thử lại với ảnh rõ nét hơn."
                    } else {
                        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(40)
                        val timeStr = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date())
                        documentTitle = firstLine ?: "Tài liệu OCR ($timeStr)"
                    }
                }
                .addOnFailureListener { e ->
                    isRecognizing = false
                    errorMessage = "Lỗi nhận dạng ảnh: ${e.localizedMessage ?: "Vui lòng thử lại"}"
                }
        } catch (e: Exception) {
            isRecognizing = false
            errorMessage = "Không thể đọc dữ liệu ảnh: ${e.localizedMessage}"
        }
    }

    fun saveToLibrary(
        onSuccess: (ArticleDetail) -> Unit
    ) {
        val text = recognizedText.trim()
        if (text.isBlank()) {
            errorMessage = "Nội dung văn bản đang trống, không thể lưu."
            return
        }

        val catId = selectedCategoryId ?: categories.firstOrNull()?.id
        if (catId == null) {
            errorMessage = "Vui lòng chọn danh mục lưu trữ."
            return
        }

        val title = documentTitle.trim().ifBlank {
            val timeStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            "Tài liệu OCR ($timeStr)"
        }

        viewModelScope.launch {
            isSaving = true
            errorMessage = null

            val wordCount = text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            val readingMin = maxOf(1, wordCount / 200)

            val request = CreateArticleRequest(
                title = title,
                sourceType = "URL",
                contentMarkdown = text,
                excerpt = text.take(150),
                readingMinutes = readingMin,
                categoryId = catId
            )

            val result = articleRepository.createArticle(request)
            result.fold(
                onSuccess = { article ->
                    isSaving = false
                    saveSuccessMessage = "Đã lưu tài liệu vào Thư viện thành công!"
                    onSuccess(article)
                },
                onFailure = { error ->
                    isSaving = false
                    errorMessage = "Lỗi khi lưu vào Thư viện: ${error.message ?: "Không xác định"}"
                }
            )
        }
    }

    fun clear() {
        imageUri = null
        imageBitmap = null
        recognizedText = ""
        documentTitle = ""
        errorMessage = null
        saveSuccessMessage = null
    }

    fun clearError() {
        errorMessage = null
    }

    fun clearSuccess() {
        saveSuccessMessage = null
    }
}


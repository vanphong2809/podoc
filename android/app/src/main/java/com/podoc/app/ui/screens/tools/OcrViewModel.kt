package com.podoc.app.ui.screens.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
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
import com.podoc.app.ui.screens.tools.ocr.CorrectionEntry
import com.podoc.app.ui.screens.tools.ocr.ImagePreprocessor
import com.podoc.app.ui.screens.tools.ocr.OcrCorrector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OcrViewModel(
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    var imageUri by mutableStateOf<Uri?>(null)
        private set

    // Ảnh gốc chụp/chọn
    var originalBitmap by mutableStateOf<Bitmap?>(null)
        private set

    // Ảnh đang hiển thị / đang dùng để quét (sau khi Crop ROI hoặc ảnh gốc)
    var activeBitmap by mutableStateOf<Bitmap?>(null)
        private set

    // Tương thích ngược với imageBitmap cũ
    val imageBitmap: Bitmap?
        get() = activeBitmap

    var isRoiActive by mutableStateOf(false)
        private set

    var showCropDialog by mutableStateOf(false)

    var isEnhanceContrastEnabled by mutableStateOf(true)
        private set

    var isAutoCorrectionEnabled by mutableStateOf(true)
        private set

    var isShowingCleaned by mutableStateOf(true)
        private set

    var isRecognizing by mutableStateOf(false)
        private set

    var recognizedText by mutableStateOf("")
        private set

    var rawRecognizedText by mutableStateOf("")
        private set

    var corrections by mutableStateOf<List<CorrectionEntry>>(emptyList())
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

    fun toggleShowingCleaned() {
        isShowingCleaned = !isShowingCleaned
        recognizedText = if (isShowingCleaned) {
            val result = OcrCorrector.cleanAndCorrect(rawRecognizedText)
            result.cleanedText
        } else {
            rawRecognizedText
        }
    }

    fun toggleAutoCorrection() {
        isAutoCorrectionEnabled = !isAutoCorrectionEnabled
        recognizedText = if (isAutoCorrectionEnabled) {
            val result = OcrCorrector.cleanAndCorrect(rawRecognizedText)
            corrections = result.corrections
            result.cleanedText
        } else {
            corrections = emptyList()
            rawRecognizedText
        }
    }

    fun toggleEnhanceContrast() {
        isEnhanceContrastEnabled = !isEnhanceContrastEnabled
        val currentBmp = activeBitmap ?: originalBitmap
        if (currentBmp != null) {
            recognizeFromBitmap(currentBmp)
        }
    }

    fun openCropDialog() {
        if (originalBitmap != null) {
            showCropDialog = true
        }
    }

    fun closeCropDialog() {
        showCropDialog = false
    }

    fun applyRoiCrop(cropRect: RectF) {
        showCropDialog = false
        val orig = originalBitmap ?: return
        viewModelScope.launch {
            val cropped = withContext(Dispatchers.Default) {
                ImagePreprocessor.cropNormalized(orig, cropRect)
            }
            activeBitmap = cropped
            isRoiActive = true
            recognizeFromBitmap(cropped)
        }
    }

    fun resetRoi() {
        val orig = originalBitmap ?: return
        activeBitmap = orig
        isRoiActive = false
        recognizeFromBitmap(orig)
    }

    fun processImageUri(context: Context, uri: Uri) {
        imageUri = uri
        isRecognizing = true
        errorMessage = null
        recognizedText = ""
        rawRecognizedText = ""
        corrections = emptyList()
        isRoiActive = false

        viewModelScope.launch {
            val loadedBitmap = withContext(Dispatchers.IO) {
                ImagePreprocessor.loadAndOrientBitmap(context, uri)
            }

            if (loadedBitmap == null) {
                isRecognizing = false
                errorMessage = "Không thể đọc hoặc xử lý tệp ảnh này."
                return@launch
            }

            originalBitmap = loadedBitmap
            activeBitmap = loadedBitmap
            recognizeFromBitmap(loadedBitmap)
        }
    }

    fun processBitmap(bitmap: Bitmap) {
        imageUri = null
        isRecognizing = true
        errorMessage = null
        recognizedText = ""
        rawRecognizedText = ""
        corrections = emptyList()
        isRoiActive = false
        originalBitmap = bitmap
        activeBitmap = bitmap

        recognizeFromBitmap(bitmap)
    }

    private fun recognizeFromBitmap(bitmap: Bitmap) {
        isRecognizing = true
        errorMessage = null

        viewModelScope.launch {
            // Tiền xử lý ảnh (Tăng tương phản & Grayscale nếu bật)
            val processedBitmap = withContext(Dispatchers.Default) {
                if (isEnhanceContrastEnabled) {
                    ImagePreprocessor.enhanceForOcr(bitmap)
                } else {
                    bitmap
                }
            }

            try {
                val inputImage = InputImage.fromBitmap(processedBitmap, 0)
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        isRecognizing = false
                        val rawText = visionText.text.trim()
                        rawRecognizedText = rawText

                        if (rawText.isBlank()) {
                            recognizedText = ""
                            corrections = emptyList()
                            errorMessage = "Không tìm thấy văn bản trong vùng ảnh này. Bạn hãy thử mở rộng vùng quét hoặc chụp rõ hơn."
                        } else {
                            if (isAutoCorrectionEnabled) {
                                val correctionResult = OcrCorrector.cleanAndCorrect(rawText)
                                recognizedText = correctionResult.cleanedText
                                corrections = correctionResult.corrections
                            } else {
                                recognizedText = rawText
                                corrections = emptyList()
                            }

                            val firstLine = recognizedText.lineSequence().firstOrNull { it.isNotBlank() }?.take(40)
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
                errorMessage = "Không thể xử lý ảnh: ${e.localizedMessage}"
            }
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
        originalBitmap = null
        activeBitmap = null
        isRoiActive = false
        showCropDialog = false
        recognizedText = ""
        rawRecognizedText = ""
        corrections = emptyList()
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

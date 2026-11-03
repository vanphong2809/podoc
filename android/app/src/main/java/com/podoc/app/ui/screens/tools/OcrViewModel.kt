package com.podoc.app.ui.screens.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.podoc.app.data.api.PoDocApiService
import com.podoc.app.data.local.DataStoreManager
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.CreateArticleRequest
import com.podoc.app.data.model.OcrExtractRequest
import com.podoc.app.data.model.OcrExtractResponse
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.CategoryRepository
import com.podoc.app.ui.screens.tools.ocr.CorrectionEntry
import com.podoc.app.ui.screens.tools.ocr.ImagePreprocessor
import com.podoc.app.ui.screens.tools.ocr.OcrCorrector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OcrViewModel(
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository,
    private val dataStoreManager: DataStoreManager,
    private val apiServiceProvider: () -> PoDocApiService?
) : ViewModel() {

    var imageUri by mutableStateOf<Uri?>(null)
        private set

    // Ảnh gốc chụp/chọn
    var originalBitmap by mutableStateOf<Bitmap?>(null)
        private set

    // Ảnh đang hiển thị / đang dùng để quét (sau khi Crop ROI hoặc ảnh gốc)
    var activeBitmap by mutableStateOf<Bitmap?>(null)
        private set

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

    // AI Structured Extraction (KIE) states
    var selectedDocType by mutableStateOf("general")
        private set

    var activeTab by mutableIntStateOf(0) // 0: Văn bản sạch, 1: Bóc tách AI

    var isExtractingAi by mutableStateOf(false)
        private set

    var structuredResult by mutableStateOf<OcrExtractResponse?>(null)
        private set

    var aiErrorMessage by mutableStateOf<String?>(null)
        private set

    var showApiKeyDialog by mutableStateOf(false)

    var geminiApiKey by mutableStateOf("")
        private set

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    init {
        loadCategories()
        loadApiKey()
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

    private fun loadApiKey() {
        viewModelScope.launch {
            geminiApiKey = dataStoreManager.geminiApiKeyFlow.first()
        }
    }

    fun saveGeminiApiKey(key: String) {
        viewModelScope.launch {
            dataStoreManager.saveGeminiApiKey(key)
            geminiApiKey = key.trim()
            showApiKeyDialog = false
        }
    }

    fun setSelectedCategory(catId: Int) {
        selectedCategoryId = catId
    }

    fun setDocTitle(title: String) {
        documentTitle = title
    }

    fun setDocType(type: String) {
        selectedDocType = type
    }

    fun switchTab(tab: Int) {
        activeTab = tab
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
            recognizeWithMlKitOcr(currentBmp)
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
            recognizeWithMlKitOcr(cropped)
        }
    }

    fun resetRoi() {
        val orig = originalBitmap ?: return
        activeBitmap = orig
        isRoiActive = false
        recognizeWithMlKitOcr(orig)
    }

    fun processImageUri(context: Context, uri: Uri) {
        imageUri = uri
        isRecognizing = true
        errorMessage = null
        recognizedText = ""
        rawRecognizedText = ""
        corrections = emptyList()
        structuredResult = null
        isRoiActive = false
        activeTab = 0

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
            recognizeWithMlKitOcr(loadedBitmap)
        }
    }

    fun processBitmap(bitmap: Bitmap) {
        imageUri = null
        isRecognizing = true
        errorMessage = null
        recognizedText = ""
        rawRecognizedText = ""
        corrections = emptyList()
        structuredResult = null
        isRoiActive = false
        originalBitmap = bitmap
        activeBitmap = bitmap
        activeTab = 0

        recognizeWithMlKitOcr(bitmap)
    }

    /**
     * Quét OCR chuẩn xác bằng Google ML Kit nội bộ trên máy (chạy trực tiếp trên ảnh, không bịa chữ)
     */
    fun recognizeWithMlKitOcr(bitmap: Bitmap) {
        isRecognizing = true
        errorMessage = null

        viewModelScope.launch {
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

    /**
     * Bóc tách thông tin có cấu trúc bằng AI (KIE & LLM Context Correction)
     */
    fun extractStructuredWithAi() {
        val currentBitmap = activeBitmap ?: originalBitmap
        val textToProcess = recognizedText.ifBlank { rawRecognizedText }

        if (currentBitmap == null && textToProcess.isBlank()) {
            errorMessage = "Chưa có hình ảnh hoặc văn bản để AI xử lý."
            return
        }

        val apiService = apiServiceProvider()
        if (apiService == null) {
            errorMessage = "Chưa kết nối máy chủ PoDoc. Vui lòng kiểm tra lại địa chỉ máy chủ trong Cài đặt."
            return
        }

        viewModelScope.launch {
            isExtractingAi = true
            aiErrorMessage = null

            try {
                val request = OcrExtractRequest(
                    rawText = textToProcess,
                    documentType = selectedDocType,
                    apiKey = geminiApiKey.takeIf { it.isNotBlank() }
                )
                val response = apiService.extractStructuredOcr(request)
                structuredResult = response
                activeTab = 1

                if (response.fullText.isNotBlank()) {
                    recognizedText = response.fullText
                    rawRecognizedText = response.fullText
                }

                // Gợi ý tiêu đề bài viết thông minh theo thông tin bóc tách
                val timeStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                if (selectedDocType == "id_card") {
                    val name = response.structuredData["full_name"]?.toString()?.replace("\"", "") ?: ""
                    val idNum = response.structuredData["id_number"]?.toString()?.replace("\"", "") ?: ""
                    documentTitle = if (name.isNotBlank()) "CCCD - $name ($idNum)" else "CCCD ($timeStr)"
                } else if (selectedDocType == "invoice") {
                    val seller = response.structuredData["seller_name"]?.toString()?.replace("\"", "") ?: ""
                    val invNo = response.structuredData["invoice_number"]?.toString()?.replace("\"", "") ?: ""
                    documentTitle = if (seller.isNotBlank()) "Hóa đơn - $seller ($invNo)" else "Hóa đơn ($timeStr)"
                } else if (selectedDocType == "business_card") {
                    val name = response.structuredData["full_name"]?.toString()?.replace("\"", "") ?: ""
                    val company = response.structuredData["company"]?.toString()?.replace("\"", "") ?: ""
                    documentTitle = if (name.isNotBlank()) "Danh thiếp - $name ($company)" else "Danh thiếp ($timeStr)"
                }
            } catch (e: Exception) {
                aiErrorMessage = "Lỗi khi trích xuất AI: ${e.localizedMessage ?: "Vui lòng kiểm tra kết nối"}"
            } finally {
                isExtractingAi = false
            }
        }
    }

    /**
     * Lưu vào Thư viện PoDoc (hỗ trợ cả lưu text thuần và lưu bảng Markdown bóc tách)
     */
    fun saveToLibrary(
        useStructuredTable: Boolean = false,
        onSuccess: (ArticleDetail) -> Unit
    ) {
        val content = if (useStructuredTable && structuredResult != null) {
            structuredResult!!.markdownTable
        } else {
            recognizedText.trim()
        }

        if (content.isBlank()) {
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

            val wordCount = content.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            val readingMin = maxOf(1, wordCount / 200)

            val request = CreateArticleRequest(
                title = title,
                sourceType = "URL",
                contentMarkdown = content,
                excerpt = content.take(150),
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

    fun clearAll() {
        imageUri = null
        originalBitmap = null
        activeBitmap = null
        isRoiActive = false
        showCropDialog = false
        recognizedText = ""
        rawRecognizedText = ""
        corrections = emptyList()
        structuredResult = null
        activeTab = 0
        documentTitle = ""
        errorMessage = null
        aiErrorMessage = null
        saveSuccessMessage = null
    }

    fun clearError() {
        errorMessage = null
    }

    fun clearAiError() {
        aiErrorMessage = null
    }

    fun clearSuccess() {
        saveSuccessMessage = null
    }
}

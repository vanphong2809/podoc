package com.podoc.app.ui.screens.tools

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.PdfMergeInput
import com.podoc.app.data.model.PdfSplitSource
import com.podoc.app.data.model.SplitPdfResponse
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.CategoryRepository
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

object PdfSplitHelper {
    const val MAX_SPLIT_OUTPUTS = 50

    fun estimateEveryNSplitCount(totalPages: Int?, pagesPerSplitInput: String): Int? {
        if (totalPages == null || totalPages <= 0) return null
        val perSplit = pagesPerSplitInput.trim().toIntOrNull() ?: return null
        if (perSplit < 1) return null
        return (totalPages + perSplit - 1) / perSplit
    }

    /** Returns an error message in Vietnamese if client validation detects invalid input, else null. */
    fun validateRangesInput(ranges: String, totalPages: Int?, mergeRanges: Boolean): String? {
        val trimmed = ranges.trim()
        if (trimmed.isBlank()) return "Vui lòng nhập khoảng trang cần tách (VD: 1-3, 5)."
        val tokens = trimmed.split(",").map { it.trim() }
        if (tokens.any { it.isBlank() }) return "Khoảng trang có dấu phẩy thừa hoặc phần trống."

        for (tok in tokens) {
            if (tok.contains("-")) {
                val parts = tok.split("-").map { it.trim() }
                val start = parts.getOrNull(0)?.toIntOrNull()
                val end = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || start == null || end == null) {
                    return "Khoảng '$tok' không hợp lệ. Đúng dạng: 'từ-đến' (VD: 1-4)."
                }
                if (start < 1 || end < 1) return "Số trang phải bắt đầu từ 1."
                if (start > end) return "Trang bắt đầu lớn hơn trang kết thúc ở '$tok'."
                if (totalPages != null && totalPages > 0 && end > totalPages) {
                    return "Trang $end vượt quá tổng số trang ($totalPages trang)."
                }
            } else {
                val page = tok.toIntOrNull() ?: return "Số trang '$tok' không hợp lệ."
                if (page < 1) return "Số trang phải bắt đầu từ 1."
                if (totalPages != null && totalPages > 0 && page > totalPages) {
                    return "Trang $page vượt quá tổng số trang ($totalPages trang)."
                }
            }
        }
        if (!mergeRanges && tokens.size > MAX_SPLIT_OUTPUTS) {
            return "Tối đa $MAX_SPLIT_OUTPUTS tệp cho mỗi lần tách."
        }
        return null
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

class PdfToolsViewModel(
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    var activeTab by mutableIntStateOf(0) // 0 = Ghép PDF, 1 = Tách PDF
    var categories by mutableStateOf<List<Category>>(emptyList())
        private set
    var selectedCategoryId by mutableStateOf<Int?>(null)

    // Library PDF picker state
    var libraryPdfs by mutableStateOf<List<ArticleListItem>>(emptyList())
        private set
    var isLoadingLibraryPdfs by mutableStateOf(false)
        private set
    var librarySearchQuery by mutableStateOf("")

    // Merge PDF state
    var mergeItems by mutableStateOf<List<PdfMergeInput>>(emptyList())
        private set
    var mergeOutputTitle by mutableStateOf("")
    var isMerging by mutableStateOf(false)
        private set
    var mergedResult by mutableStateOf<ArticleDetail?>(null)
        private set

    // Split PDF state
    var splitSource by mutableStateOf<PdfSplitSource?>(null)
        private set
    var isInspectingSplitSource by mutableStateOf(false)
        private set
    var splitMode by mutableStateOf("ranges") // "ranges" or "every_n"
    var splitRangesInput by mutableStateOf("1-2")
    var splitMergeRanges by mutableStateOf(false)
    var splitPagesPerFileInput by mutableStateOf("1")
    var splitTitlePrefix by mutableStateOf("")
    var isSplitting by mutableStateOf(false)
        private set
    var splitResult by mutableStateOf<SplitPdfResponse?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        loadCategories()
    }

    fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategories().onSuccess { list ->
                categories = list
                if (selectedCategoryId == null) {
                    selectedCategoryId = list.firstOrNull()?.id
                }
            }
        }
    }

    fun loadLibraryPdfs() {
        viewModelScope.launch {
            isLoadingLibraryPdfs = true
            val result = articleRepository.getArticles(
                contentType = "file",
                page = 1,
                pageSize = 200
            )
            result.onSuccess { list ->
                libraryPdfs = list.filter { item ->
                    val name = (item.fileName ?: item.filePath ?: "").lowercase()
                    item.mimeType?.equals("application/pdf", ignoreCase = true) == true ||
                        name.endsWith(".pdf")
                }
            }.onFailure { err ->
                errorMessage = err.message ?: "Không thể tải danh sách PDF từ thư viện"
            }
            isLoadingLibraryPdfs = false
        }
    }

    fun addLocalPdfsToMerge(uris: List<Uri>) {
        if (uris.isEmpty()) return
        mergedResult = null
        viewModelScope.launch {
            val added = mutableListOf<PdfMergeInput.LocalUri>()
            var rejectedEncrypted = false
            for (uri in uris) {
                val meta = articleRepository.inspectLocalPdf(uri)
                if (meta.isEncrypted) {
                    rejectedEncrypted = true
                    continue
                }
                added.add(
                    PdfMergeInput.LocalUri(
                        key = "local_${UUID.randomUUID()}",
                        uri = uri,
                        displayName = meta.fileName,
                        pageCount = meta.pageCount,
                        fileSizeBytes = meta.fileSizeBytes,
                        fileSizeFormatted = PdfSplitHelper.formatBytes(meta.fileSizeBytes).ifBlank { null }
                    )
                )
            }
            if (added.isNotEmpty()) {
                mergeItems = mergeItems + added
                if (mergeOutputTitle.isBlank()) {
                    mergeOutputTitle = suggestMergeTitle(mergeItems)
                }
            }
            if (rejectedEncrypted) {
                errorMessage = "Một số tệp PDF có mật khẩu bảo vệ chưa được hỗ trợ ghép."
            }
        }
    }

    fun addLibraryPdfToMerge(article: ArticleListItem) {
        mergedResult = null
        val display = article.fileName ?: "${article.title}.pdf"
        val itemKey = "lib_${article.id}_${UUID.randomUUID()}"
        val entry = PdfMergeInput.LibraryArticle(
            key = itemKey,
            articleId = article.id,
            displayName = display,
            pageCount = null,
            fileSizeFormatted = null,
            categoryName = article.categoryName
        )
        mergeItems = mergeItems + entry
        if (mergeOutputTitle.isBlank()) {
            mergeOutputTitle = suggestMergeTitle(mergeItems)
        }
        viewModelScope.launch {
            articleRepository.getLibraryPdfInfo(article.id).onSuccess { info ->
                if (info.isEncrypted) {
                    mergeItems = mergeItems.filterNot { it.key == itemKey }
                    errorMessage = "Tệp '$display' đang có mật khẩu bảo vệ, chưa hỗ trợ ghép."
                    return@onSuccess
                }
                mergeItems = mergeItems.map { current ->
                    if (current.key == itemKey && current is PdfMergeInput.LibraryArticle) {
                        current.copy(
                            pageCount = info.pageCount,
                            fileSizeFormatted = info.fileSizeFormatted
                        )
                    } else current
                }
            }
        }
    }

    private fun suggestMergeTitle(items: List<PdfMergeInput>): String {
        val firstBase = items.firstOrNull()?.displayName
            ?.substringBeforeLast('.')
            ?.trim()
            ?.take(28)
            ?: "Tai_lieu"
        return "${firstBase}_ghep"
    }

    fun moveMergeItemUp(index: Int) {
        if (index <= 0 || index >= mergeItems.size) return
        val mutable = mergeItems.toMutableList()
        val prev = mutable[index - 1]
        mutable[index - 1] = mutable[index]
        mutable[index] = prev
        mergeItems = mutable
    }

    fun moveMergeItemDown(index: Int) {
        if (index < 0 || index >= mergeItems.size - 1) return
        val mutable = mergeItems.toMutableList()
        val next = mutable[index + 1]
        mutable[index + 1] = mutable[index]
        mutable[index] = next
        mergeItems = mutable
    }

    fun removeMergeItem(index: Int) {
        if (index !in mergeItems.indices) return
        val mutable = mergeItems.toMutableList()
        mutable.removeAt(index)
        mergeItems = mutable
    }

    fun clearMergeResult() {
        mergedResult = null
    }

    fun resetMerge() {
        mergeItems = emptyList()
        mergeOutputTitle = ""
        mergedResult = null
    }

    fun executeMerge() {
        if (mergeItems.size < 2 || isMerging) {
            if (mergeItems.size < 2) errorMessage = "Vui lòng chọn ít nhất 2 tệp PDF để ghép."
            return
        }
        viewModelScope.launch {
            isMerging = true
            errorMessage = null
            mergedResult = null
            val res = articleRepository.mergePdfFiles(
                items = mergeItems,
                title = mergeOutputTitle,
                categoryId = selectedCategoryId
            )
            res.fold(
                onSuccess = { article ->
                    isMerging = false
                    mergedResult = article
                },
                onFailure = { err ->
                    isMerging = false
                    errorMessage = err.message ?: "Ghép PDF thất bại. Vui lòng thử lại."
                }
            )
        }
    }

    fun selectLocalPdfForSplit(uri: Uri) {
        splitResult = null
        viewModelScope.launch {
            isInspectingSplitSource = true
            val meta = articleRepository.inspectLocalPdf(uri)
            isInspectingSplitSource = false
            if (meta.isEncrypted) {
                errorMessage = "Tệp '${meta.fileName}' được bảo vệ bằng mật khẩu, chưa hỗ trợ tách."
                return@launch
            }
            splitSource = PdfSplitSource.LocalUri(
                uri = uri,
                displayName = meta.fileName,
                pageCount = meta.pageCount,
                fileSizeFormatted = PdfSplitHelper.formatBytes(meta.fileSizeBytes).ifBlank { null }
            )
            applyDefaultSplitFields(meta.fileName, meta.pageCount)
        }
    }

    fun selectLibraryPdfForSplit(article: ArticleListItem) {
        splitResult = null
        val display = article.fileName ?: "${article.title}.pdf"
        splitSource = PdfSplitSource.LibraryArticle(
            articleId = article.id,
            displayName = display,
            pageCount = null,
            fileSizeFormatted = null,
            categoryId = article.categoryId
        )
        applyDefaultSplitFields(display, null)
        viewModelScope.launch {
            isInspectingSplitSource = true
            articleRepository.getLibraryPdfInfo(article.id).fold(
                onSuccess = { info ->
                    isInspectingSplitSource = false
                    if (info.isEncrypted) {
                        splitSource = null
                        errorMessage = "Tệp '$display' đang được bảo vệ bằng mật khẩu, chưa hỗ trợ tách."
                        return@fold
                    }
                    splitSource = PdfSplitSource.LibraryArticle(
                        articleId = article.id,
                        displayName = info.fileName ?: display,
                        pageCount = info.pageCount,
                        fileSizeFormatted = info.fileSizeFormatted,
                        categoryId = article.categoryId
                    )
                    applyDefaultSplitFields(info.fileName ?: display, info.pageCount)
                },
                onFailure = { err ->
                    isInspectingSplitSource = false
                    errorMessage = err.message ?: "Không thể kiểm tra số trang của PDF"
                }
            )
        }
    }

    private fun applyDefaultSplitFields(fileName: String, pageCount: Int?) {
        splitTitlePrefix = fileName.substringBeforeLast('.').ifBlank { "Document" }
        if (pageCount != null && pageCount > 1) {
            val mid = (pageCount / 2).coerceAtLeast(1)
            splitRangesInput = "1-$mid, ${mid + 1}-$pageCount"
        } else {
            splitRangesInput = "1"
        }
    }

    fun clearSplitSource() {
        splitSource = null
        splitResult = null
    }

    fun clearSplitResult() {
        splitResult = null
    }

    fun validateSplitInput(): String? {
        val src = splitSource ?: return "Vui lòng chọn tệp PDF cần tách."
        return if (splitMode == "ranges") {
            PdfSplitHelper.validateRangesInput(splitRangesInput, src.pageCount, splitMergeRanges)
        } else {
            val perSplit = splitPagesPerFileInput.trim().toIntOrNull()
            if (perSplit == null || perSplit < 1) {
                return "Số trang mỗi tệp phải là số nguyên lớn hơn hoặc bằng 1."
            }
            val count = PdfSplitHelper.estimateEveryNSplitCount(src.pageCount, splitPagesPerFileInput)
            if (count != null && count > PdfSplitHelper.MAX_SPLIT_OUTPUTS) {
                return "Sẽ tạo $count tệp, vượt quá tối đa ${PdfSplitHelper.MAX_SPLIT_OUTPUTS} tệp mỗi lần."
            }
            null
        }
    }

    fun executeSplit() {
        val src = splitSource ?: run {
            errorMessage = "Vui lòng chọn tệp PDF cần tách."
            return
        }
        val validationError = validateSplitInput()
        if (validationError != null) {
            errorMessage = validationError
            return
        }
        if (isSplitting) return

        viewModelScope.launch {
            isSplitting = true
            errorMessage = null
            splitResult = null
            val perSplit = splitPagesPerFileInput.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
            val res = articleRepository.splitPdfFile(
                source = src,
                mode = splitMode,
                ranges = if (splitMode == "ranges") splitRangesInput else null,
                mergeRanges = splitMergeRanges,
                pagesPerSplit = perSplit,
                titlePrefix = splitTitlePrefix,
                categoryId = selectedCategoryId
            )
            res.fold(
                onSuccess = { resp ->
                    isSplitting = false
                    splitResult = resp
                },
                onFailure = { err ->
                    isSplitting = false
                    errorMessage = err.message ?: "Tách PDF thất bại. Vui lòng thử lại."
                }
            )
        }
    }

    fun clearError() {
        errorMessage = null
    }
}


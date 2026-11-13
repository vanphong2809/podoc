package com.podoc.app.ui.screens.note

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.ArticleDetail
import com.podoc.app.data.model.CreateArticleRequest
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.CategoryRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoteEditorHelper {
    fun deriveNoteTitle(title: String, content: String, date: Date = Date()): String {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isNotBlank()) return trimmedTitle

        // Thử lấy dòng đầu tiên của nội dung ghi chú
        val firstLine = content.lineSequence()
            .map { it.trim().trimStart('#', '*', '-', ' ', '•') }
            .firstOrNull { it.isNotBlank() }

        if (!firstLine.isNullOrBlank()) {
            return if (firstLine.length > 50) firstLine.take(47) + "..." else firstLine
        }

        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(date)
        return "Ghi chú $dateStr"
    }

    fun calculateWordCount(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split("\\s+".toRegex()).size
    }

    fun calculateCharCount(text: String): Int {
        return text.length
    }

    fun applySnippet(
        text: String,
        selStart: Int,
        selEnd: Int,
        prefix: String,
        suffix: String = ""
    ): Pair<String, Int> {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)
        val selected = text.substring(start, end)
        val replacement = "$prefix$selected$suffix"
        val newText = text.substring(0, start) + replacement + text.substring(end)
        val newCursor = start + prefix.length + selected.length + suffix.length
        return Pair(newText, newCursor)
    }
}

class NoteEditorViewModel(
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    var title by mutableStateOf("")
        private set

    var content by mutableStateOf("")
        private set

    var isSaving by mutableStateOf(false)
        private set

    var isEditMode by mutableStateOf(false)
        private set

    var editingArticleId by mutableStateOf<Int?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var saveSuccess by mutableStateOf(false)
        private set

    var savedArticleId by mutableStateOf<Int?>(null)
        private set

    val wordCount: Int
        get() = NoteEditorHelper.calculateWordCount(content)

    val charCount: Int
        get() = NoteEditorHelper.calculateCharCount(content)

    fun onTitleChange(newTitle: String) {
        title = newTitle
    }

    fun onContentChange(newContent: String) {
        content = newContent
    }

    fun clearError() {
        errorMessage = null
    }

    fun initForNewNote() {
        title = ""
        content = ""
        isSaving = false
        isEditMode = false
        editingArticleId = null
        errorMessage = null
        saveSuccess = false
        savedArticleId = null
    }

    fun initForEdit(articleId: Int) {
        initForNewNote()
        isEditMode = true
        editingArticleId = articleId
        viewModelScope.launch {
            articleRepository.getArticleDetail(articleId).fold(
                onSuccess = { art ->
                    title = art.title
                    content = art.contentMarkdown
                },
                onFailure = { err ->
                    errorMessage = "Không thể tải ghi chú: ${err.message}"
                }
            )
        }
    }

    fun appendFormatting(prefix: String, suffix: String = "") {
        content = if (content.isEmpty()) {
            "$prefix$suffix"
        } else {
            val separator = if (content.endsWith("\n")) "" else "\n"
            "$content$separator$prefix$suffix"
        }
    }

    fun insertTimestamp() {
        val timeStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        appendFormatting("\n> ⏱ $timeStr\n")
    }

    fun insertTodo() {
        appendFormatting("- [ ] ")
    }

    fun insertBullet() {
        appendFormatting("• ")
    }

    fun insertHeading() {
        appendFormatting("### ")
    }

    fun saveNote(onSuccess: (ArticleDetail) -> Unit) {
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank()) {
            errorMessage = "Vui lòng nhập nội dung ghi chú"
            return
        }

        val finalTitle = NoteEditorHelper.deriveNoteTitle(title, trimmedContent)
        isSaving = true
        errorMessage = null

        viewModelScope.launch {
            try {
                // 1. Tìm hoặc tạo danh mục "Ghi chú"
                val catListResult = categoryRepository.getCategories()
                val categories = catListResult.getOrDefault(emptyList())
                var noteCat = categories.find { it.name.trim().equals("Ghi chú", ignoreCase = true) }

                if (noteCat == null) {
                    val createCatResult = categoryRepository.createCategory(
                        name = "Ghi chú",
                        icon = "note",
                        color = "#F59E0B",
                        sortOrder = 7
                    )
                    noteCat = createCatResult.getOrNull()
                }

                val targetCategoryId = noteCat?.id ?: 1

                if (isEditMode && editingArticleId != null) {
                    val updateResult = articleRepository.updateArticle(
                        id = editingArticleId!!,
                        title = finalTitle,
                        categoryId = targetCategoryId,
                        contentMarkdown = content
                    )
                    updateResult.fold(
                        onSuccess = { updated ->
                            isSaving = false
                            saveSuccess = true
                            savedArticleId = updated.id
                            onSuccess(updated)
                        },
                        onFailure = { err ->
                            isSaving = false
                            errorMessage = "Lỗi khi cập nhật ghi chú: ${err.message ?: "Không xác định"}"
                        }
                    )
                } else {
                    val readingMin = maxOf(1, NoteEditorHelper.calculateWordCount(trimmedContent) / 200)
                    val request = CreateArticleRequest(
                        title = finalTitle,
                        sourceType = "NOTE",
                        mimeType = "text/markdown",
                        contentMarkdown = content,
                        excerpt = trimmedContent.take(150),
                        readingMinutes = readingMin,
                        categoryId = targetCategoryId
                    )
                    val createResult = articleRepository.createArticle(request)
                    createResult.fold(
                        onSuccess = { created ->
                            isSaving = false
                            saveSuccess = true
                            savedArticleId = created.id
                            onSuccess(created)
                        },
                        onFailure = { err ->
                            isSaving = false
                            errorMessage = "Lỗi khi lưu ghi chú: ${err.message ?: "Không xác định"}"
                        }
                    )
                }
            } catch (e: Exception) {
                isSaving = false
                errorMessage = "Có lỗi xảy ra: ${e.message ?: "Không xác định"}"
            }
        }
    }
}

package com.podoc.app.ui.screens.tools

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podoc.app.data.model.ArticleListItem
import com.podoc.app.data.model.AudioExtractResultResponse
import com.podoc.app.data.model.AudioExtractSource
import com.podoc.app.data.model.AudioFormatOption
import com.podoc.app.data.model.AudioProbeResponse
import com.podoc.app.data.model.Category
import com.podoc.app.data.model.LocalVideoAudioMeta
import com.podoc.app.data.repository.ArticleRepository
import com.podoc.app.data.repository.CategoryRepository
import kotlinx.coroutines.launch
import java.util.Locale

object AudioExtractorHelper {
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatDuration(seconds: Float?): String {
        if (seconds == null || seconds <= 0f) return "--:--"
        val totalSec = Math.round(seconds)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun suggestExtension(codec: String?, formatOption: AudioFormatOption): String {
        return when (formatOption) {
            AudioFormatOption.MP3 -> "mp3"
            AudioFormatOption.M4A -> "m4a"
            AudioFormatOption.WAV -> "wav"
            AudioFormatOption.ORIGINAL -> {
                when (codec?.lowercase()?.trim()) {
                    "aac", "alac" -> "m4a"
                    "mp3" -> "mp3"
                    "opus" -> "opus"
                    "flac" -> "flac"
                    "vorbis" -> "ogg"
                    "wav", "pcm_s16le", "pcm_s24le" -> "wav"
                    else -> "m4a"
                }
            }
        }
    }
}

class AudioExtractorViewModel(
    private val articleRepository: ArticleRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    var videoSource by mutableStateOf<AudioExtractSource?>(null)
        private set

    var isInspectingSource by mutableStateOf(false)
        private set

    var probeInfo by mutableStateOf<AudioProbeResponse?>(null)
        private set

    var localMeta by mutableStateOf<LocalVideoAudioMeta?>(null)
        private set

    var selectedFormat by mutableStateOf(AudioFormatOption.ORIGINAL)

    var outputTitle by mutableStateOf("")

    var categories by mutableStateOf<List<Category>>(emptyList())
        private set

    var selectedCategoryId by mutableStateOf<Int?>(null)

    // Library video picker state
    var libraryVideos by mutableStateOf<List<ArticleListItem>>(emptyList())
        private set

    var isLoadingLibraryVideos by mutableStateOf(false)
        private set

    var librarySearchQuery by mutableStateOf("")

    var isExtracting by mutableStateOf(false)
        private set

    var extractionResult by mutableStateOf<AudioExtractResultResponse?>(null)
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

    fun loadLibraryVideos() {
        viewModelScope.launch {
            isLoadingLibraryVideos = true
            val res = articleRepository.getArticles(
                contentType = "video",
                page = 1,
                pageSize = 200
            )
            res.onSuccess { list ->
                libraryVideos = list
            }.onFailure { err ->
                errorMessage = err.message ?: "Không thể tải danh sách video từ Thư viện"
            }
            isLoadingLibraryVideos = false
        }
    }

    fun selectLocalVideo(uri: Uri) {
        extractionResult = null
        viewModelScope.launch {
            isInspectingSource = true
            val meta = articleRepository.inspectLocalVideoAudio(uri)
            localMeta = meta
            probeInfo = null
            isInspectingSource = false

            if (!meta.hasAudio) {
                errorMessage = "Tệp video này không có luồng âm thanh."
                videoSource = null
                return@launch
            }

            val source = AudioExtractSource.LocalUri(
                uri = uri,
                displayName = meta.fileName,
                durationSeconds = meta.durationSeconds,
                durationFormatted = meta.durationFormatted,
                fileSizeBytes = meta.fileSizeBytes,
                fileSizeFormatted = AudioExtractorHelper.formatBytes(meta.fileSizeBytes).ifBlank { null }
            )
            videoSource = source
            applyDefaultTitle(meta.fileName)
        }
    }

    fun selectLibraryVideo(article: ArticleListItem) {
        extractionResult = null
        localMeta = null
        val display = article.fileName ?: "${article.title}.mp4"
        val source = AudioExtractSource.LibraryArticle(
            articleId = article.id,
            displayName = display,
            durationSeconds = article.durationSeconds,
            durationFormatted = article.durationSeconds?.let { AudioExtractorHelper.formatDuration(it) },
            fileSizeFormatted = null,
            categoryId = article.categoryId,
            categoryName = article.categoryName
        )
        videoSource = source
        selectedCategoryId = article.categoryId
        applyDefaultTitle(article.title.ifBlank { display })

        viewModelScope.launch {
            isInspectingSource = true
            articleRepository.probeLibraryVideoAudio(article.id).fold(
                onSuccess = { info ->
                    isInspectingSource = false
                    probeInfo = info
                    if (!info.hasAudio) {
                        videoSource = null
                        errorMessage = "Video '${article.title}' không có luồng âm thanh để trích xuất."
                    }
                },
                onFailure = { err ->
                    isInspectingSource = false
                    // Probe lỗi trên máy chủ thì vẫn cho phép gửi lệnh trích xuất nếu người dùng muốn
                }
            )
        }
    }

    private fun applyDefaultTitle(name: String) {
        val base = name.substringBeforeLast('.').trim().ifBlank { "Am_thanh" }
        outputTitle = "$base (Âm thanh)"
    }

    fun clearSource() {
        videoSource = null
        probeInfo = null
        localMeta = null
        extractionResult = null
    }

    fun clearResult() {
        extractionResult = null
    }

    fun clearError() {
        errorMessage = null
    }

    fun executeExtraction() {
        val src = videoSource ?: run {
            errorMessage = "Vui lòng chọn video cần tách âm thanh."
            return
        }

        if (isExtracting) return

        viewModelScope.launch {
            isExtracting = true
            errorMessage = null
            extractionResult = null

            val res = articleRepository.extractAudioFromVideo(
                source = src,
                outputFormat = selectedFormat.code,
                title = outputTitle.trim().ifBlank { null },
                categoryId = selectedCategoryId
            )

            res.fold(
                onSuccess = { result ->
                    isExtracting = false
                    extractionResult = result
                },
                onFailure = { err ->
                    isExtracting = false
                    errorMessage = err.message ?: "Trích xuất âm thanh thất bại. Vui lòng thử lại."
                }
            )
        }
    }
}


package com.podoc.app.data.model

import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudioProbeResponse(
    @SerialName("has_audio") val hasAudio: Boolean = false,
    @SerialName("codec_name") val codecName: String? = null,
    @SerialName("codec_display") val codecDisplay: String? = null,
    @SerialName("channels") val channels: Int? = null,
    @SerialName("channels_display") val channelsDisplay: String? = null,
    @SerialName("sample_rate_hz") val sampleRateHz: Int? = null,
    @SerialName("bit_rate_kbps") val bitRateKbps: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Float? = null,
    @SerialName("duration_formatted") val durationFormatted: String? = null,
    @SerialName("suggested_ext") val suggestedExt: String = "m4a",
    @SerialName("suggested_mime") val suggestedMime: String = "audio/mp4",
    @SerialName("lossless_possible") val losslessPossible: Boolean = true
)

@Serializable
data class AudioExtractResultResponse(
    @SerialName("article_id") val articleId: Int,
    @SerialName("title") val title: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("duration_seconds") val durationSeconds: Float? = null,
    @SerialName("duration_formatted") val durationFormatted: String? = null,
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0L,
    @SerialName("file_size_formatted") val fileSizeFormatted: String = "",
    @SerialName("audio_codec") val audioCodec: String = "",
    @SerialName("is_lossless") val isLossless: Boolean = true,
    @SerialName("source_video_name") val sourceVideoName: String? = null
)

data class LocalVideoAudioMeta(
    val fileName: String,
    val fileSizeBytes: Long,
    val durationSeconds: Float?,
    val durationFormatted: String?,
    val hasAudio: Boolean,
    val mimeType: String?
)

sealed class AudioExtractSource {
    abstract val displayName: String
    abstract val durationSeconds: Float?
    abstract val durationFormatted: String?
    abstract val fileSizeFormatted: String?
    abstract val isFromLibrary: Boolean

    data class LocalUri(
        val uri: Uri,
        override val displayName: String,
        override val durationSeconds: Float?,
        override val durationFormatted: String?,
        val fileSizeBytes: Long,
        override val fileSizeFormatted: String?
    ) : AudioExtractSource() {
        override val isFromLibrary: Boolean = false
    }

    data class LibraryArticle(
        val articleId: Int,
        override val displayName: String,
        override val durationSeconds: Float?,
        override val durationFormatted: String?,
        override val fileSizeFormatted: String?,
        val categoryId: Int? = null,
        val categoryName: String? = null
    ) : AudioExtractSource() {
        override val isFromLibrary: Boolean = true
    }
}

enum class AudioFormatOption(
    val code: String,
    val title: String,
    val description: String,
    val isRecommended: Boolean = false
) {
    ORIGINAL(
        code = "original",
        title = "Chất lượng gốc",
        description = "Giữ nguyên 100% bit-for-bit từ video (Lossless Stream Copy)",
        isRecommended = true
    ),
    MP3(
        code = "mp3",
        title = "MP3 (320 kbps)",
        description = "Tương thích cao với mọi thiết bị nghe nhạc"
    ),
    M4A(
        code = "m4a",
        title = "M4A / AAC",
        description = "Chất lượng cao, chuẩn Apple & Android"
    ),
    WAV(
        code = "wav",
        title = "WAV Lossless",
        description = "Không nén PCM nguyên bản"
    )
}


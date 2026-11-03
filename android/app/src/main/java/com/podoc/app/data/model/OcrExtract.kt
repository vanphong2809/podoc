package com.podoc.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class OcrExtractRequest(
    @SerialName("raw_text") val rawText: String? = "",
    @SerialName("image_base64") val imageBase64: String? = null,
    @SerialName("mime_type") val mimeType: String = "image/jpeg",
    @SerialName("document_type") val documentType: String = "id_card",
    @SerialName("api_key") val apiKey: String? = null
)

@Serializable
data class ValidationStatus(
    @SerialName("is_valid") val isValid: Boolean = true,
    @SerialName("score") val score: Float = 1.0f,
    @SerialName("warnings") val warnings: List<String> = emptyList()
)

@Serializable
data class OcrExtractResponse(
    @SerialName("document_type") val documentType: String,
    @SerialName("full_text") val fullText: String = "",
    @SerialName("is_vision") val isVision: Boolean = false,
    @SerialName("structured_data") val structuredData: JsonObject = JsonObject(emptyMap()),
    @SerialName("confidence_notes") val confidenceNotes: List<String> = emptyList(),
    @SerialName("validation") val validation: ValidationStatus = ValidationStatus(),
    @SerialName("markdown_table") val markdownTable: String = ""
)

@Serializable
data class ReadImageRequest(
    @SerialName("image_base64") val imageBase64: String,
    @SerialName("mime_type") val mimeType: String = "image/jpeg",
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("prompt") val prompt: String? = null
)

@Serializable
data class ReadImageResponse(
    @SerialName("text") val text: String,
    @SerialName("model_used") val modelUsed: String = "gemini-3.8-flash",
    @SerialName("word_count") val wordCount: Int = 0,
    @SerialName("line_count") val lineCount: Int = 0
)

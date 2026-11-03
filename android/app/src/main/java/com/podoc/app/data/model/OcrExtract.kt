package com.podoc.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class OcrExtractRequest(
    @SerialName("raw_text") val rawText: String,
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
    @SerialName("structured_data") val structuredData: JsonObject = JsonObject(emptyMap()),
    @SerialName("confidence_notes") val confidenceNotes: List<String> = emptyList(),
    @SerialName("validation") val validation: ValidationStatus = ValidationStatus(),
    @SerialName("markdown_table") val markdownTable: String = ""
)

package com.podoc.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GenerateBriefingRequest(
    @SerialName("gemini_api_key") val geminiApiKey: String? = null,
    @SerialName("gemini_model") val geminiModel: String = "gemini-3.8-flash",
    @SerialName("max_items_per_topic") val maxItemsPerTopic: Int = 3
)

@Serializable
data class NewsSlideItem(
    @SerialName("slide_index") val slideIndex: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("summary") val summary: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("category_color") val categoryColor: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("link") val link: String? = null,
    @SerialName("image_file") val imageFile: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("original_image_url") val originalImageUrl: String? = null
)

@Serializable
data class NewsBriefingResponse(
    @SerialName("article_id") val articleId: Int,
    @SerialName("title") val title: String,
    @SerialName("category_id") val categoryId: Int,
    @SerialName("category_name") val categoryName: String,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("slides") val slides: List<NewsSlideItem> = emptyList(),
    @SerialName("briefing") val briefing: NewsBriefingContent? = null
)

@Serializable
data class NewsBriefingContent(
    @SerialName("title") val title: String? = null,
    @SerialName("date_str") val dateStr: String? = null,
    @SerialName("greeting") val greeting: String? = null,
    @SerialName("categories") val categories: List<NewsCategoryItem> = emptyList(),
    @SerialName("markdown_content") val markdownContent: String? = null,
    @SerialName("is_ai_generated") val isAiGenerated: Boolean = true
)

@Serializable
data class NewsCategoryItem(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String,
    @SerialName("icon") val icon: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("items") val items: List<NewsArticleItem> = emptyList()
)

@Serializable
data class NewsArticleItem(
    @SerialName("title") val title: String,
    @SerialName("summary") val summary: String,
    @SerialName("source") val source: String? = null,
    @SerialName("link") val link: String? = null,
    @SerialName("image_url") val imageUrl: String? = null
)

@Serializable
data class LatestBriefingResponse(
    @SerialName("article_id") val articleId: Int,
    @SerialName("title") val title: String,
    @SerialName("category_id") val categoryId: Int,
    @SerialName("category_name") val categoryName: String,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("content_markdown") val contentMarkdown: String? = null,
    @SerialName("excerpt") val excerpt: String? = null,
    @SerialName("slides") val slides: List<NewsSlideItem> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class NewsSettingsResponse(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("scheduled_time") val scheduledTime: String = "07:00",
    @SerialName("gemini_model") val geminiModel: String = "gemini-3.8-flash",
    @SerialName("has_custom_api_key") val hasCustomApiKey: Boolean = false
)

fun parseBriefingSlides(contentMarkdown: String?, resolveUrl: (String?) -> String?): List<NewsSlideItem> {
    if (contentMarkdown.isNullOrBlank()) return emptyList()
    val startTag = "<!-- PODOC_BRIEFING_SLIDES_START -->"
    val endTag = "<!-- PODOC_BRIEFING_SLIDES_END -->"
    if (contentMarkdown.contains(startTag) && contentMarkdown.contains(endTag)) {
        val startIndex = contentMarkdown.indexOf(startTag)
        val endIndex = contentMarkdown.indexOf(endTag)
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            val jsonStr = contentMarkdown.substring(startIndex + startTag.length, endIndex).trim()
            try {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val rawSlides = json.decodeFromString<List<NewsSlideItem>>(jsonStr)
                return rawSlides.map { slide ->
                    slide.copy(imageUrl = resolveUrl(slide.imageUrl))
                }
            } catch (_: Exception) {}
        }
    }

    // Fallback trích xuất ảnh slide từ cú pháp Markdown ![[title]](/api/news/slides/...)
    val imageRegex = Regex("!\\[(.*?)\\]\\((/api/news/slides/.*?)\\)")
    val matches = imageRegex.findAll(contentMarkdown).toList()
    if (matches.isNotEmpty()) {
        return matches.mapIndexed { idx, m ->
            val title = m.groupValues[1]
            val path = m.groupValues[2]
            NewsSlideItem(
                slideIndex = idx,
                title = title,
                imageUrl = resolveUrl(path)
            )
        }
    }

    return emptyList()
}


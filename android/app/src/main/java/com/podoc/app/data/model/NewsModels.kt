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
data class NewsBriefingResponse(
    @SerialName("article_id") val articleId: Int,
    @SerialName("title") val title: String,
    @SerialName("category_id") val categoryId: Int,
    @SerialName("category_name") val categoryName: String,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
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
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class NewsSettingsResponse(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("scheduled_time") val scheduledTime: String = "07:00",
    @SerialName("gemini_model") val geminiModel: String = "gemini-3.8-flash",
    @SerialName("has_custom_api_key") val hasCustomApiKey: Boolean = false
)


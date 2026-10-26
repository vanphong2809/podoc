package com.podoc.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Category(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String,
    @SerialName("icon") val icon: String = "folder",
    @SerialName("color") val color: String = "#3B82F6",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_system") val isSystem: Boolean = false,
    @SerialName("article_count") val articleCount: Int = 0
)

@Serializable
data class CreateCategoryRequest(
    @SerialName("name") val name: String,
    @SerialName("icon") val icon: String = "folder",
    @SerialName("color") val color: String = "#3B82F6",
    @SerialName("sort_order") val sortOrder: Int = 0
)

@Serializable
data class UpdateCategoryRequest(
    @SerialName("name") val name: String? = null,
    @SerialName("icon") val icon: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null
)

package com.podoc.app.data.api

import com.podoc.app.data.model.*
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface PoDocApiService {

    // Auth
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @GET("api/auth/me")
    suspend fun getMe(): User

    // Extraction
    @POST("api/extract")
    suspend fun extractUrl(@Body request: ExtractUrlRequest): ArticleDraft

    @Multipart
    @POST("api/extract-file")
    suspend fun extractFile(@Part file: MultipartBody.Part): ArticleDraft

    // Categories
    @GET("api/categories")
    suspend fun getCategories(): List<Category>

    @POST("api/categories")
    suspend fun createCategory(@Body request: CreateCategoryRequest): Category

    @PATCH("api/categories/{id}")
    suspend fun updateCategory(
        @Path("id") id: Int,
        @Body request: UpdateCategoryRequest
    ): Category

    @DELETE("api/categories/{id}")
    suspend fun deleteCategory(@Path("id") id: Int): Response<Unit>

    // Articles
    @GET("api/articles")
    suspend fun getArticles(
        @Query("q") query: String? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("source_type") sourceType: String? = null,
        @Query("favorite") favorite: Boolean? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): List<ArticleListItem>

    @GET("api/articles/{id}")
    suspend fun getArticleDetail(@Path("id") id: Int): ArticleDetail

    @POST("api/articles")
    suspend fun createArticle(@Body request: CreateArticleRequest): ArticleDetail

    @PATCH("api/articles/{id}")
    suspend fun updateArticle(
        @Path("id") id: Int,
        @Body request: UpdateArticleRequest
    ): ArticleDetail

    @DELETE("api/articles/{id}")
    suspend fun deleteArticle(@Path("id") id: Int): Response<Unit>

    // Export ZIP
    @Streaming
    @GET("api/export")
    suspend fun exportData(): ResponseBody

    // Download original file
    @Streaming
    @GET("api/articles/{id}/file")
    suspend fun downloadArticleFile(@Path("id") id: Int): ResponseBody
}

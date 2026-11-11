package com.podoc.app.data.api

import com.podoc.app.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
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
    @POST("api/articles/playback-sources")
    suspend fun getVideoPlaybackSources(@Body request: VideoPlaybackRequest): VideoPlaybackResponse

    @GET("api/articles")
    suspend fun getArticles(
        @Query("q") query: String? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("source_type") sourceType: String? = null,
        @Query("content_type") contentType: String? = null,
        @Query("favorite") favorite: Boolean? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): List<ArticleListItem>

    @GET("api/articles/{id}")
    suspend fun getArticleDetail(@Path("id") id: Int): ArticleDetail

    @GET("api/articles/{id}/file-info")
    suspend fun getArticleFileInfo(@Path("id") id: Int): ArticleFileInfo

    @POST("api/articles")
    suspend fun createArticle(@Body request: CreateArticleRequest): ArticleDetail

    @Multipart
    @POST("api/articles/upload")
    suspend fun uploadArticleFile(
        @Part file: MultipartBody.Part,
        @Part thumbnail: MultipartBody.Part? = null,
        @Part("category_id") categoryId: RequestBody? = null,
        @Part("title") title: RequestBody? = null,
        @Part("file_name") fileName: RequestBody? = null,
        @Part("duration_seconds") durationSeconds: RequestBody? = null
    ): ArticleDetail

    @PATCH("api/articles/{id}")
    suspend fun updateArticle(
        @Path("id") id: Int,
        @Body request: UpdateArticleRequest
    ): ArticleDetail

    @DELETE("api/articles/{id}")
    suspend fun deleteArticle(@Path("id") id: Int): Response<Unit>

    @POST("api/articles/batch-delete")
    suspend fun batchDeleteArticles(@Body request: BatchDeleteRequest): Response<BatchDeleteResponse>

    @POST("api/articles/batch-update-category")
    suspend fun batchUpdateCategory(@Body request: BatchUpdateCategoryRequest): Response<Unit>

    // Export ZIP
    @Streaming
    @GET("api/export")
    suspend fun exportData(): ResponseBody

    // Download original file or stream inline
    @Streaming
    @GET("api/articles/{id}/file")
    suspend fun downloadArticleFile(
        @Path("id") id: Int,
        @Query("inline") inline: Boolean = false,
        @Query("variant") variant: String? = null
    ): ResponseBody

    // Preview HTML (for DOCX, HTML, TXT, MD)
    @GET("api/articles/{id}/preview-html")
    suspend fun previewArticleHtml(@Path("id") id: Int): ResponseBody

    // Convert Office to PDF (doc, docx, xls, xlsx, ppt, pptx, rtf...)
    @POST("api/articles/{id}/convert-to-pdf")
    suspend fun convertArticleToPdf(@Path("id") id: Int): ArticleDetail

    @Multipart
    @POST("api/articles/convert-to-pdf")
    suspend fun uploadAndConvertToPdf(
        @Part file: MultipartBody.Part,
        @Part("category_id") categoryId: RequestBody? = null,
        @Part("title") title: RequestBody? = null
    ): ArticleDetail

    // Thống kê dung lượng lưu trữ các danh mục (Hình ảnh, Video, Âm thanh, Tệp tin)
    @GET("api/articles/storage-stats")
    suspend fun getStorageStats(
        @Query("category_id") categoryId: Int? = null
    ): StorageStats

    // OCR & IDP Structured Extraction
    @POST("api/ocr/extract-structured")
    suspend fun extractStructuredOcr(@Body request: OcrExtractRequest): OcrExtractResponse

    // Đọc toàn bộ văn bản từ ảnh bằng Gemini 3.8 Flash Vision (như trong chat)
    @POST("api/ocr/read-image")
    suspend fun readImageWithGemini(@Body request: ReadImageRequest): ReadImageResponse

    // Thống kê thông số máy chủ (RAM, Disk, CPU)
    @GET("api/system/stats")
    suspend fun getServerSystemStats(): ServerSystemStats

    // Ghép & Tách file PDF
    @GET("api/pdf/articles/{id}/info")
    suspend fun getLibraryPdfInfo(@Path("id") id: Int): PdfInfoResponse

    @Multipart
    @POST("api/pdf/merge")
    suspend fun mergePdfFiles(
        @Part files: List<MultipartBody.Part>,
        @Part("sources_json") sourcesJson: RequestBody? = null,
        @Part("title") title: RequestBody? = null,
        @Part("category_id") categoryId: RequestBody? = null
    ): ArticleDetail

    @Multipart
    @POST("api/pdf/split")
    suspend fun splitPdfFile(
        @Part file: MultipartBody.Part? = null,
        @Part("article_id") articleId: RequestBody? = null,
        @Part("mode") mode: RequestBody,
        @Part("ranges") ranges: RequestBody? = null,
        @Part("merge_ranges") mergeRanges: RequestBody? = null,
        @Part("pages_per_split") pagesPerSplit: RequestBody? = null,
        @Part("title_prefix") titlePrefix: RequestBody? = null,
        @Part("category_id") categoryId: RequestBody? = null
    ): SplitPdfResponse
}

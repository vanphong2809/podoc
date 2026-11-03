package com.podoc.app.ui.screens.tools.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeminiVisionResult(
    val text: String,
    val modelUsed: String
)

object GeminiDirectVisionService {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val DEFAULT_PROMPT = """
        Hãy đọc và trích xuất TOÀN BỘ văn bản chữ viết có trong hình ảnh này một cách chính xác và trung thực nhất.
        - Giữ nguyên toàn bộ từ ngữ tiếng Việt có dấu, chữ số, ký hiệu và dấu câu.
        - Giữ nguyên cấu trúc các dòng, đoạn văn, danh sách hoặc bảng biểu giống như bố cục trong tài liệu gốc.
        - KHÔNG giải thích, KHÔNG tóm tắt, KHÔNG thêm lời chào hay kết luận.
        - Chỉ trả về văn bản đọc được từ ảnh.
    """.trimIndent()

    /**
     * Gọi TRỰC TIẾP Google Gemini API từ thiết bị để trích xuất văn bản từ ảnh:
     * - Hoạt động y hệt như khi chat với model trên Google AI Studio.
     * - Ưu tiên mô hình gemini-3.8-flash, tự động dự phòng sang gemini-2.0-flash / gemini-1.5-flash nếu cần.
     * - Không bị phụ thuộc hay gián đoạn bởi server backend.
     */
    suspend fun extractTextFromBitmap(
        bitmap: Bitmap,
        apiKey: String,
        customPrompt: String? = null
    ): Result<GeminiVisionResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Chưa có API Key Google AI Studio. Vui lòng bấm biểu tượng chìa khóa ở góc trên để nhập key.")
            )
        }

        val base64Image = ImagePreprocessor.bitmapToBase64(bitmap, maxDimension = 2048, quality = 90)
        val prompt = customPrompt ?: DEFAULT_PROMPT

        val modelsToTry = listOf("gemini-3.8-flash", "gemini-2.0-flash", "gemini-1.5-flash")
        var lastError: Exception? = null

        for (model in modelsToTry) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"

                val payloadJson = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    }

                    val contentsArray = JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", partsArray)
                        })
                    }

                    put("contents", contentsArray)
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.0)
                    })
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payloadJson.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val errObj = JSONObject(responseString).optJSONObject("error")
                        errObj?.optString("message") ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}: $responseString"
                    }

                    // Nếu là 404 (model chưa hỗ trợ cho key này) hoặc 503 (quá tải), thử model tiếp theo
                    if (response.code == 404 || response.code == 503) {
                        lastError = Exception("Mô hình $model trả về mã ${response.code}: $errorMsg")
                        continue
                    } else {
                        return@withContext Result.failure(Exception("Lỗi Gemini ($model): $errorMsg"))
                    }
                }

                val jsonRes = JSONObject(responseString)
                val candidates = jsonRes.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    lastError = Exception("$model không trả về kết quả.")
                    continue
                }

                val firstCand = candidates.getJSONObject(0)
                val content = firstCand.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    lastError = Exception("$model trả về nội dung rỗng.")
                    continue
                }

                val extractedText = parts.getJSONObject(0).optString("text", "").trim()
                if (extractedText.isNotBlank()) {
                    return@withContext Result.success(
                        GeminiVisionResult(
                            text = extractedText,
                            modelUsed = model
                        )
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Không thể đọc ảnh bằng Gemini Vision."))
    }
}

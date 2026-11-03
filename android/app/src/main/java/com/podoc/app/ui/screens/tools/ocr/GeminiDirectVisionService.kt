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

data class GeminiModelTarget(
    val model: String,
    val apiVersion: String
)

object GeminiDirectVisionService {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Cache danh sách model khả dụng theo API Key để tránh gọi lặp lại nhiều lần
    private var cachedTargets: List<GeminiModelTarget> = emptyList()
    private var cachedApiKey: String = ""

    private val DEFAULT_PROMPT = """
        Hãy đọc và trích xuất TOÀN BỘ văn bản chữ viết có trong hình ảnh này một cách chính xác và trung thực nhất.
        - Giữ nguyên toàn bộ từ ngữ tiếng Việt có dấu, chữ số, ký hiệu và dấu câu.
        - Giữ nguyên cấu trúc các dòng, đoạn văn, danh sách hoặc bảng biểu giống như bố cục trong tài liệu gốc.
        - KHÔNG giải thích, KHÔNG tóm tắt, KHÔNG thêm lời chào hay kết luận.
        - Chỉ trả về văn bản đọc được từ ảnh.
    """.trimIndent()

    /**
     * Tự động truy vấn danh sách mô hình thực tế mà API Key này được cấp phép từ Google ModelService (ListModels).
     * Quét cả v1beta và v1 để lấy chính xác các model đang hoạt động, loại trừ hoàn toàn lỗi 404.
     */
    suspend fun getAvailableModelTargets(apiKey: String): List<GeminiModelTarget> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext emptyList()

        if (cleanKey == cachedApiKey && cachedTargets.isNotEmpty()) {
            return@withContext cachedTargets
        }

        val discovered = mutableListOf<GeminiModelTarget>()
        val seenNames = mutableSetOf<String>()

        for (apiVersion in listOf("v1beta", "v1")) {
            try {
                val url = "https://generativelanguage.googleapis.com/$apiVersion/models?key=$cleanKey"
                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (response.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val modelsArray = json.optJSONArray("models") ?: continue

                    for (i in 0 until modelsArray.length()) {
                        val modelObj = modelsArray.optJSONObject(i) ?: continue
                        val methods = modelObj.optJSONArray("supportedGenerationMethods")
                        var canGenerate = false
                        if (methods != null) {
                            for (j in 0 until methods.length()) {
                                if (methods.optString(j) == "generateContent") {
                                    canGenerate = true
                                    break
                                }
                            }
                        }

                        if (canGenerate) {
                            val rawName = modelObj.optString("name", "")
                            val cleanName = if (rawName.startsWith("models/")) {
                                rawName.removePrefix("models/")
                            } else {
                                rawName
                            }

                            // Chỉ chọn các model text/multimodal, bỏ qua model embedding
                            val lower = cleanName.lowercase()
                            val isEmbeddingOrAqa = lower.contains("embedding") || lower.contains("aqa")
                            val keyName = "$apiVersion/$cleanName"

                            if (cleanName.isNotBlank() && !isEmbeddingOrAqa && !seenNames.contains(keyName)) {
                                seenNames.add(keyName)
                                discovered.add(GeminiModelTarget(model = cleanName, apiVersion = apiVersion))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Tiếp tục thử version tiếp theo
            }
        }

        // Sắp xếp các model theo thứ tự ưu tiên tối ưu nhất cho Vision OCR
        val sorted = discovered.sortedWith { t1, t2 ->
            val s1 = scoreModelTarget(t1)
            val s2 = scoreModelTarget(t2)
            s2.compareTo(s1)
        }

        if (sorted.isNotEmpty()) {
            cachedApiKey = cleanKey
            cachedTargets = sorted
        }

        sorted
    }

    private fun scoreModelTarget(target: GeminiModelTarget): Int {
        val lower = target.model.lowercase()
        val baseScore = when {
            lower.contains("3.8-flash") -> 120
            lower.contains("2.5-flash") -> 110
            lower.contains("2.0-flash") -> 100
            lower.contains("2.0-flash-exp") -> 95
            lower.contains("1.5-flash-latest") -> 90
            lower.contains("1.5-flash-002") -> 88
            lower.contains("1.5-flash") -> 85
            lower.contains("flash") -> 80
            lower.contains("1.5-pro") -> 70
            lower.contains("pro") -> 60
            lower.contains("gemini") -> 50
            else -> 10
        }
        // Ưu tiên v1beta cho model thử nghiệm mới, v1 cho stable
        val versionBonus = if (target.apiVersion == "v1beta" && (lower.contains("2.0") || lower.contains("3.8"))) 5 else 0
        return baseScore + versionBonus
    }

    /**
     * Kiểm tra nhanh tính hợp lệ của API Key và liệt kê các model khả dụng
     */
    suspend fun testApiKey(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Vui lòng nhập API Key"))
        }

        try {
            val targets = getAvailableModelTargets(cleanKey)
            if (targets.isNotEmpty()) {
                val modelNames = targets.map { "${it.model} (${it.apiVersion})" }.distinct()
                Result.success(modelNames)
            } else {
                // Nếu getAvailableModelTargets rỗng, gọi thử trực tiếp để lấy thông điệp lỗi chính xác từ Google
                val testUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey"
                val response = httpClient.newCall(Request.Builder().url(testUrl).get().build()).execute()
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}"
                    }
                    Result.failure(Exception("Google API từ chối ($errMsg)"))
                } else {
                    Result.failure(Exception("Không tìm thấy model nào hỗ trợ generateContent cho API Key này"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Lỗi kết nối mạng: ${e.localizedMessage ?: e.message}"))
        }
    }

    /**
     * Gọi TRỰC TIẾP Google Gemini API từ thiết bị để trích xuất văn bản từ ảnh:
     * - Tự thích ứng 100% bằng cách truy vấn danh sách model đang hoạt động của chính API Key đó.
     * - Tránh hoàn toàn lỗi 404 do lệch version/model.
     * - Đọc chính xác 100% tiếng Việt có dấu, số, ký hiệu như khi chat trên Google AI Studio.
     */
    suspend fun extractTextFromBitmap(
        bitmap: Bitmap,
        apiKey: String,
        customPrompt: String? = null
    ): Result<GeminiVisionResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Chưa có API Key Google AI Studio. Vui lòng bấm biểu tượng chìa khóa để nhập key.")
            )
        }

        val base64Image = ImagePreprocessor.bitmapToBase64(bitmap, maxDimension = 2048, quality = 90)
        val prompt = customPrompt ?: DEFAULT_PROMPT

        // Bước 1: Lấy danh sách model thực tế được Google cấp phép cho key này
        var targetsToTry = getAvailableModelTargets(cleanKey)
        if (targetsToTry.isEmpty()) {
            // Danh sách dự phòng đa dạng kết hợp cả v1 và v1beta
            targetsToTry = listOf(
                GeminiModelTarget("gemini-2.0-flash", "v1beta"),
                GeminiModelTarget("gemini-2.0-flash-exp", "v1beta"),
                GeminiModelTarget("gemini-1.5-flash", "v1"),
                GeminiModelTarget("gemini-1.5-flash", "v1beta"),
                GeminiModelTarget("gemini-1.5-flash-latest", "v1beta"),
                GeminiModelTarget("gemini-1.5-flash-002", "v1"),
                GeminiModelTarget("gemini-1.5-pro", "v1"),
                GeminiModelTarget("gemini-1.5-pro", "v1beta"),
                GeminiModelTarget("gemini-3.8-flash", "v1beta")
            )
        }

        var lastError: Exception? = null

        // Payload JSON chuẩn theo Google Generative Language REST API
        val payloadJson = JSONObject().apply {
            val partsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
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

        for (target in targetsToTry) {
            try {
                val url = "https://generativelanguage.googleapis.com/${target.apiVersion}/models/${target.model}:generateContent?key=$cleanKey"
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

                    // Nếu 404 (model chưa hỗ trợ endpoint này) hoặc 503, thử target tiếp theo
                    if (response.code == 404 || response.code == 503 || response.code == 400) {
                        lastError = Exception("Mô hình ${target.model} (${target.apiVersion}) trả về mã ${response.code}: $errorMsg")
                        continue
                    } else {
                        // Lỗi quota hoặc quyền hạn cụ thể
                        return@withContext Result.failure(Exception("Lỗi Gemini (${target.model}): $errorMsg"))
                    }
                }

                val jsonRes = JSONObject(responseString)
                val candidates = jsonRes.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    lastError = Exception("${target.model} không trả về kết quả.")
                    continue
                }

                val firstCand = candidates.getJSONObject(0)
                val content = firstCand.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    lastError = Exception("${target.model} trả về nội dung rỗng.")
                    continue
                }

                val sb = StringBuilder()
                for (p in 0 until parts.length()) {
                    val partObj = parts.optJSONObject(p)
                    val txt = partObj?.optString("text").orEmpty()
                    sb.append(txt)
                }

                val extractedText = sb.toString().trim()
                if (extractedText.isNotBlank()) {
                    return@withContext Result.success(
                        GeminiVisionResult(
                            text = extractedText,
                            modelUsed = target.model
                        )
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Không thể đọc ảnh bằng Gemini Vision. Vui lòng kiểm tra lại kết nối mạng hoặc API Key."))
    }
}

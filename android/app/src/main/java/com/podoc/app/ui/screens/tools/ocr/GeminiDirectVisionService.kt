package com.podoc.app.ui.screens.tools.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            .connectTimeout(35, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Cache danh sách model khả dụng theo API Key để tăng tốc độ phản hồi
    private var cachedTargets: List<GeminiModelTarget> = emptyList()
    private var cachedApiKey: String = ""

    private val DEFAULT_PROMPT = """
        Hãy đọc và trích xuất TOÀN BỘ văn bản chữ viết có trong hình ảnh này một cách chính xác và trung thực nhất.
        - Giữ nguyên toàn bộ từ ngữ tiếng Việt có dấu, chữ số, ký hiệu và dấu câu.
        - Giữ nguyên cấu trúc các dòng, đoạn văn, danh sách hoặc bảng biểu giống như bố cục trong tài liệu gốc.
        - KHÔNG giải thích, KHÔNG tóm tắt, KHÔNG thêm lời chào hay kết luận.
        - Chỉ trả về văn bản đọc được từ ảnh.
    """.trimIndent()

    fun resetQuotaCache() {
        cachedApiKey = ""
        cachedTargets = emptyList()
    }

    fun formatModelDisplayName(model: String): String {
        val clean = model.removePrefix("models/")
        return when {
            clean.contains("3.8-flash", ignoreCase = true) -> "Gemini 3.8 Flash"
            clean.contains("2.0-flash", ignoreCase = true) -> "Gemini 2.0 Flash"
            clean.contains("1.5-flash", ignoreCase = true) -> "Gemini 1.5 Flash"
            clean.contains("1.5-pro", ignoreCase = true) -> "Gemini 1.5 Pro"
            clean.contains("pro", ignoreCase = true) -> "Gemini Pro"
            else -> clean
        }
    }

    /**
     * Tự động truy vấn danh sách mô hình thực tế từ Google ModelService (ListModels).
     * Luôn luôn ưu tiên Gemini 3.8 Flash ở vị trí đầu tiên.
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

        // Sắp xếp các model: Gemini 3.8 Flash luôn đứng đầu
        val sorted = discovered.sortedWith { t1, t2 ->
            val s1 = scoreModelTarget(t1)
            val s2 = scoreModelTarget(t2)
            s2.compareTo(s1)
        }.toMutableList()

        // Đảm bảo gemini-3.8-flash luôn ở vị trí số 1 kể cả khi Google ListModels chưa hiển thị
        if (!sorted.any { it.model.contains("3.8-flash", ignoreCase = true) }) {
            sorted.add(0, GeminiModelTarget("gemini-3.8-flash", "v1beta"))
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
            lower.contains("3.8-flash") -> 1000 // Điểm cao nhất tuyệt đối
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
                val modelNames = targets.map { formatModelDisplayName(it.model) }.distinct()
                Result.success(modelNames)
            } else {
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
     * Trích xuất văn bản từ hình ảnh:
     * - LUÔN LUÔN ưu tiên chạy Gemini 3.8 Flash ở lượt quét đầu tiên.
     * - Nếu 3.8 Flash gặp lỗi hoặc hết hạn mức trong lần quét đó, tự động chuyển tiếp mượt mà sang Gemini 2.0 Flash / 1.5 Flash.
     */
    suspend fun extractTextFromBitmap(
        bitmap: Bitmap,
        apiKey: String,
        customPrompt: String? = null
    ): Result<GeminiVisionResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Chưa cấu hình API Key. Vui lòng bấm biểu tượng chìa khóa để nhập key.")
            )
        }

        val base64Image = ImagePreprocessor.bitmapToBase64(bitmap, maxDimension = 1920, quality = 85)
        val prompt = customPrompt ?: DEFAULT_PROMPT

        val discoveredTargets = getAvailableModelTargets(cleanKey).toMutableList()

        // Đảm bảo gemini-3.8-flash luôn là mục tiêu đầu tiên được thử
        val targetsToTry = mutableListOf<GeminiModelTarget>()
        targetsToTry.add(GeminiModelTarget("gemini-3.8-flash", "v1beta"))

        for (target in discoveredTargets) {
            if (target.model != "gemini-3.8-flash" && !targetsToTry.any { it.model == target.model }) {
                targetsToTry.add(target)
            }
        }

        // Bổ sung các model dự phòng tiêu chuẩn nếu danh sách chưa có
        val fallbackModels = listOf(
            GeminiModelTarget("gemini-2.0-flash", "v1beta"),
            GeminiModelTarget("gemini-2.0-flash-exp", "v1beta"),
            GeminiModelTarget("gemini-1.5-flash", "v1"),
            GeminiModelTarget("gemini-1.5-flash", "v1beta"),
            GeminiModelTarget("gemini-1.5-flash-latest", "v1beta"),
            GeminiModelTarget("gemini-1.5-pro", "v1")
        )
        for (fb in fallbackModels) {
            if (!targetsToTry.any { it.model == fb.model }) {
                targetsToTry.add(fb)
            }
        }

        var lastError: Exception? = null

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

                var response = httpClient.newCall(request).execute()
                var responseString = response.body?.string().orEmpty()

                // Nếu 3.8 Flash gặp rate limit ngắn hạn (429 theo phút), chờ nhẹ 1.2s và thử lại 1 lần duy nhất
                if (response.code == 429 && target.model.contains("3.8-flash")) {
                    val isDailyLimit = responseString.contains("per day", ignoreCase = true) || responseString.contains("Please retry in", ignoreCase = true)
                    if (!isDailyLimit) {
                        delay(1200)
                        response = httpClient.newCall(request).execute()
                        responseString = response.body?.string().orEmpty()
                    }
                }

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val errObj = JSONObject(responseString).optJSONObject("error")
                        errObj?.optString("message") ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}: $responseString"
                    }

                    // Nếu 429 (hết hạn mức) hoặc 404, 503, 400: chuyển tiếp ngay sang model tiếp theo (2.0-flash, 1.5-flash)
                    val isKeyInvalid = errorMsg.contains("API_KEY_INVALID", ignoreCase = true) || errorMsg.contains("API key not valid", ignoreCase = true)
                    if (!isKeyInvalid && (response.code == 429 || response.code == 404 || response.code == 503 || response.code == 400 || response.code == 403)) {
                        lastError = Exception("${formatModelDisplayName(target.model)} (${response.code}): $errorMsg")
                        continue
                    } else {
                        return@withContext Result.failure(Exception("Lỗi Google API: $errorMsg"))
                    }
                }

                val jsonRes = JSONObject(responseString)
                val candidates = jsonRes.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    lastError = Exception("${formatModelDisplayName(target.model)} không trả về kết quả.")
                    continue
                }

                val firstCand = candidates.getJSONObject(0)
                val content = firstCand.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    lastError = Exception("${formatModelDisplayName(target.model)} trả về nội dung rỗng.")
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
                            modelUsed = formatModelDisplayName(target.model)
                        )
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Không thể đọc ảnh bằng AI. Vui lòng kiểm tra lại kết nối mạng hoặc API Key."))
    }
}

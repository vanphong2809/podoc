package com.podoc.app.ui.screens.tools.ocr

import java.text.Normalizer
import kotlin.math.min

data class CorrectionEntry(
    val original: String,
    val corrected: String,
    val category: String, // "Ngày tháng", "Số định danh", "Họ và tên", "Tỉnh/Thành", "Định dạng"
    val reason: String
)

data class CorrectionResult(
    val cleanedText: String,
    val corrections: List<CorrectionEntry>
)

object OcrCorrector {

    // Danh bạ 63 Tỉnh/Thành phố trực thuộc Trung ương của Việt Nam
    private val VIETNAMESE_PROVINCES = listOf(
        "Hà Nội", "Hồ Chí Minh", "Hải Phòng", "Đà Nẵng", "Cần Thơ",
        "An Giang", "Bà Rịa - Vũng Tàu", "Bắc Giang", "Bắc Kạn", "Bạc Liêu",
        "Bắc Ninh", "Bến Tre", "Bình Định", "Bình Dương", "Bình Phước",
        "Bình Thuận", "Cà Mau", "Cao Bằng", "Đắk Lắk", "Đắk Nông",
        "Điện Biên", "Đồng Nai", "Đồng Tháp", "Gia Lai", "Hà Giang",
        "Hà Nam", "Hà Tĩnh", "Hải Dương", "Hậu Giang", "Hòa Bình",
        "Hưng Yên", "Khánh Hòa", "Kiên Giang", "Kon Tum", "Lai Châu",
        "Lâm Đồng", "Lạng Sơn", "Lào Cai", "Long An", "Nam Định",
        "Nghệ An", "Ninh Bình", "Ninh Thuận", "Phú Thọ", "Phú Yên",
        "Quảng Bình", "Quảng Nam", "Quảng Ngãi", "Quảng Ninh", "Quảng Trị",
        "Sóc Trăng", "Sơn La", "Tây Ninh", "Thái Bình", "Thái Nguyên",
        "Thanh Hóa", "Thừa Thiên Huế", "Tiền Giang", "Trà Vinh", "Tuyên Quang",
        "Vĩnh Long", "Vĩnh Phúc", "Yên Bái"
    )

    /**
     * Tính khoảng cách Levenshtein giữa 2 chuỗi Unicode
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val a = Normalizer.normalize(s1.trim(), Normalizer.Form.NFC).lowercase()
        val b = Normalizer.normalize(s2.trim(), Normalizer.Form.NFC).lowercase()

        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1, // deletion
                    min(
                        dp[i][j - 1] + 1, // insertion
                        dp[i - 1][j - 1] + cost // substitution
                    )
                )
            }
        }
        return dp[a.length][b.length]
    }

    /**
     * Tìm tỉnh/thành phố khớp nhất theo Fuzzy Matching (Levenshtein Distance)
     */
    private fun findBestProvinceMatch(token: String): Pair<String, Int>? {
        val clean = token.trim()
        if (clean.length < 4) return null

        // Nếu đã là tên tỉnh/thành phố chuẩn xác thì không thay đổi
        if (VIETNAMESE_PROVINCES.any { it.equals(clean, ignoreCase = true) }) {
            return null
        }

        var bestProvince: String? = null
        var minDistance = Int.MAX_VALUE

        for (province in VIETNAMESE_PROVINCES) {
            val dist = levenshteinDistance(clean, province)
            if (dist < minDistance) {
                minDistance = dist
                bestProvince = province
            }
        }

        // Chỉ chấp nhận khoảng cách 1 (hoặc 2 với chuỗi dài >= 7 ký tự)
        val maxAllowed = if (clean.length >= 7) 2 else 1
        return if (bestProvince != null && minDistance in 1..maxAllowed) {
            Pair(bestProvince, minDistance)
        } else {
            null
        }
    }

    /**
     * Hàm chính: Làm sạch và sửa lỗi chính tả ngữ cảnh cho văn bản OCR
     */
    fun cleanAndCorrect(rawText: String): CorrectionResult {
        if (rawText.isBlank()) {
            return CorrectionResult(rawText, emptyList())
        }

        val corrections = mutableListOf<CorrectionEntry>()
        var currentText = rawText

        // 1. Sửa lỗi trong ngày tháng (Ví dụ: 28/O9/1998 -> 28/09/1998, 01/l2/2020 -> 01/12/2020)
        val datePattern = Regex("""(?i)\b([0-3]?[0-9])([/.-])([O0o][1-9]|1[0-2]|O[0-9]|[0-9]{2})([/.-])(\d{4}|\d{2})\b""")
        currentText = datePattern.replace(currentText) { match ->
            val d = match.groupValues[1]
            val sep1 = match.groupValues[2]
            var m = match.groupValues[3]
            val sep2 = match.groupValues[4]
            val y = match.groupValues[5]

            val originalStr = match.value
            // Chuẩn hóa tháng: O -> 0, o -> 0, l/I -> 1
            m = m.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')
            val fixedStr = "$d$sep1$m$sep2$y"

            if (originalStr != fixedStr) {
                corrections.add(
                    CorrectionEntry(
                        original = originalStr,
                        corrected = fixedStr,
                        category = "Ngày tháng",
                        reason = "Sửa chữ 'O'/'l' thành số '0'/'1' trong định dạng ngày tháng"
                    )
                )
            }
            fixedStr
        }

        // 2. Sửa lỗi trong dòng Tên người (Sau các nhãn: Họ và tên, Họ tên, Full name)
        // Ví dụ: NGUYỄN VĂN PH0NG -> NGUYỄN VĂN PHONG
        val nameLabelPattern = Regex("""(?i)(Họ và tên|Họ tên|Full name)\s*[:：]\s*([^\n\r]+)""")
        currentText = nameLabelPattern.replace(currentText) { match ->
            val label = match.groupValues[1]
            val rawName = match.groupValues[2]

            // Trong tên người Việt, số 0 thường là chữ O, số 1 là chữ I, số 8 là chữ B
            var fixedName = rawName
            if (rawName.any { it.isDigit() }) {
                fixedName = rawName
                    .replace('0', 'O')
                    .replace('1', 'I')
                    .replace('8', 'B')

                if (rawName != fixedName) {
                    corrections.add(
                        CorrectionEntry(
                            original = rawName.trim(),
                            corrected = fixedName.trim(),
                            category = "Họ và tên",
                            reason = "Chuyển nhầm lẫn số (0, 1, 8) thành chữ hoa (O, I, B) trong tên người"
                        )
                    )
                }
            }
            "$label: $fixedName"
        }

        // 3. Sửa lỗi số định danh / Số CCCD / CMND (Thường là dãy số 9 đến 12 ký tự)
        // Thường nhầm: O/o -> 0, I/l/! -> 1, B -> 8
        val idLabelPattern = Regex("""(?i)(Số|Số CCCD|Số CMND|No\.|No)\s*[:：]?\s*([0-9OolI!B\s]{9,16})""")
        currentText = idLabelPattern.replace(currentText) { match ->
            val label = match.groupValues[1]
            val rawId = match.groupValues[2]

            val cleanedId = rawId
                .replace(" ", "")
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .replace('!', '1')
                .replace('B', '8')

            if (cleanedId.length in 9..12 && cleanedId.all { it.isDigit() }) {
                if (rawId.trim() != cleanedId) {
                    corrections.add(
                        CorrectionEntry(
                            original = rawId.trim(),
                            corrected = cleanedId,
                            category = "Số định danh",
                            reason = "Chuẩn hóa ký tự nhầm lẫn thành chữ số trong số CCCD/CMND"
                        )
                    )
                }
                "$label: $cleanedId"
            } else {
                match.value
            }
        }

        // 4. Dictionary & Fuzzy Matching cho 63 Tỉnh/Thành phố Việt Nam
        // Duyệt các dòng chứa từ khóa liên quan đến địa chỉ
        val lines = currentText.lines().map { line ->
            if (line.contains("Địa chỉ", ignoreCase = true) ||
                line.contains("Quê quán", ignoreCase = true) ||
                line.contains("Nơi thường trú", ignoreCase = true) ||
                line.contains("Nơi ĐKHKTT", ignoreCase = true) ||
                line.contains("Tỉnh", ignoreCase = true) ||
                line.contains("Thành phố", ignoreCase = true) ||
                line.contains("TP", ignoreCase = true)
            ) {
                var modifiedLine = line
                val tokens = line.split(",", "-", ";", ":")
                for (token in tokens) {
                    val trimmedToken = token.trim()
                    val match = findBestProvinceMatch(trimmedToken)
                    if (match != null) {
                        val (bestProvince, dist) = match
                        modifiedLine = modifiedLine.replace(trimmedToken, bestProvince)
                        corrections.add(
                            CorrectionEntry(
                                original = trimmedToken,
                                corrected = bestProvince,
                                category = "Tỉnh/Thành",
                                reason = "Fuzzy Matching sửa sai chính tả địa danh (Khoảng cách Levenshtein = $dist)"
                            )
                        )
                    }
                }
                modifiedLine
            } else {
                line
            }
        }
        currentText = lines.joinToString("\n")

        // 5. Chuẩn hóa ngắt dòng & khoảng trắng (Text Beautifier)
        // Nối các dòng bị ngắt ngang câu (nếu dòng trước không kết thúc bằng . ! ? : hoặc bullet và dòng sau viết thường)
        val formattedLines = mutableListOf<String>()
        val inputLines = currentText.lines()
        var i = 0
        while (i < inputLines.size) {
            val line = inputLines[i].trim()
            if (line.isBlank()) {
                formattedLines.add("")
                i++
                continue
            }

            var mergedLine = line
            while (i + 1 < inputLines.size) {
                val nextLine = inputLines[i + 1].trim()
                if (nextLine.isNotBlank() &&
                    !mergedLine.endsWith(".") &&
                    !mergedLine.endsWith("!") &&
                    !mergedLine.endsWith("?") &&
                    !mergedLine.endsWith(":") &&
                    !mergedLine.endsWith(";") &&
                    !mergedLine.startsWith("-") &&
                    !mergedLine.startsWith("•") &&
                    !nextLine.startsWith("-") &&
                    !nextLine.startsWith("•") &&
                    nextLine.firstOrNull()?.isLowerCase() == true
                ) {
                    mergedLine = "$mergedLine $nextLine"
                    i++
                } else {
                    break
                }
            }
            formattedLines.add(mergedLine)
            i++
        }

        // Xóa khoảng trắng thừa giữa các từ
        val finalCleanedText = formattedLines.joinToString("\n")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()

        return CorrectionResult(finalCleanedText, corrections)
    }
}

package com.podoc.app.ui.screens.tools.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCorrectorTest {

    @Test
    fun testLevenshteinDistance() {
        assertEquals(0, OcrCorrector.levenshteinDistance("Hà Nội", "Hà Nội"))
        assertEquals(1, OcrCorrector.levenshteinDistance("Hà Nộj", "Hà Nội"))
        assertEquals(1, OcrCorrector.levenshteinDistance("Hải Phòg", "Hải Phòng"))
    }

    @Test
    fun testDateCorrection() {
        val input = "Ngày sinh: 28/O9/1998"
        val result = OcrCorrector.cleanAndCorrect(input)
        assertTrue(result.cleanedText.contains("28/09/1998"))
        assertTrue(result.corrections.any { it.category == "Ngày tháng" })
    }

    @Test
    fun testNameCorrection() {
        val input = "Họ và tên: NGUYỄN VĂN PH0NG"
        val result = OcrCorrector.cleanAndCorrect(input)
        assertTrue(result.cleanedText.contains("NGUYỄN VĂN PHONG"))
        assertTrue(result.corrections.any { it.category == "Họ và tên" })
    }

    @Test
    fun testIdCardCorrection() {
        val input = "Số: 0123456789O1"
        val result = OcrCorrector.cleanAndCorrect(input)
        assertTrue(result.cleanedText.contains("012345678901"))
        assertTrue(result.corrections.any { it.category == "Số định danh" })
    }

    @Test
    fun testProvinceFuzzyCorrection() {
        val input = "Địa chỉ: Hà Nộj"
        val result = OcrCorrector.cleanAndCorrect(input)
        assertTrue(result.cleanedText.contains("Hà Nội"))
        assertTrue(result.corrections.any { it.category == "Tỉnh/Thành" })
    }

    @Test
    fun testFullDocumentMatchingUserExample() {
        val rawInput = """
            Họ và tên: NGUYỄN VĂN PH0NG
            Địa chỉ: Hà Nộj
            Ngày sinh: 28/O9/1998
            Số CCCD: 0123456789O1
        """.trimIndent()

        val result = OcrCorrector.cleanAndCorrect(rawInput)

        assertTrue(result.cleanedText.contains("Họ và tên: NGUYỄN VĂN PHONG"))
        assertTrue(result.cleanedText.contains("Địa chỉ: Hà Nội"))
        assertTrue(result.cleanedText.contains("Ngày sinh: 28/09/1998"))
        assertTrue(result.cleanedText.contains("Số CCCD: 012345678901"))
        assertEquals(4, result.corrections.size)
    }
}

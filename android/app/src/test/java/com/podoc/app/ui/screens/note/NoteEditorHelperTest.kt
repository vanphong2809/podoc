package com.podoc.app.ui.screens.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class NoteEditorHelperTest {

    @Test
    fun testDeriveNoteTitle_explicitTitle() {
        val result = NoteEditorHelper.deriveNoteTitle("Kế hoạch tuần này", "Nội dung bất kỳ...")
        assertEquals("Kế hoạch tuần này", result)
    }

    @Test
    fun testDeriveNoteTitle_fromFirstLine() {
        val content = "### Danh sách mua sắm\n1. Sữa tươi\n2. Bánh mì"
        val result = NoteEditorHelper.deriveNoteTitle("", content)
        assertEquals("Danh sách mua sắm", result)
    }

    @Test
    fun testDeriveNoteTitle_fallbackDate() {
        val date = Date(1731469200000L) // Fixed timestamp for reproducible test
        val result = NoteEditorHelper.deriveNoteTitle("", "", date)
        assertTrue(result.startsWith("Ghi chú "))
    }

    @Test
    fun testCalculateWordCount() {
        assertEquals(0, NoteEditorHelper.calculateWordCount(""))
        assertEquals(0, NoteEditorHelper.calculateWordCount("   "))
        assertEquals(4, NoteEditorHelper.calculateWordCount("Học lập trình Kotlin"))
        assertEquals(3, NoteEditorHelper.calculateWordCount("Một   hai    ba"))
    }

    @Test
    fun testCalculateCharCount() {
        assertEquals(0, NoteEditorHelper.calculateCharCount(""))
        assertEquals(12, NoteEditorHelper.calculateCharCount("Xin chào bạn"))
    }

    @Test
    fun testApplySnippet() {
        val original = "Xin chào các bạn"
        // Wrap "chào" (index 4 to 8) in **
        val (newText, newCursor) = NoteEditorHelper.applySnippet(original, 4, 8, "**", "**")
        assertEquals("Xin **chào** các bạn", newText)
        assertEquals(12, newCursor)
    }
}

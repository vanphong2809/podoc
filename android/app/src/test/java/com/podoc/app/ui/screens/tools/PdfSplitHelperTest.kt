package com.podoc.app.ui.screens.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PdfSplitHelperTest {
    @Test
    fun estimateEveryNSplitCountCalculatesCeiling() {
        assertEquals(3, PdfSplitHelper.estimateEveryNSplitCount(10, "4"))
        assertEquals(10, PdfSplitHelper.estimateEveryNSplitCount(10, "1"))
        assertEquals(1, PdfSplitHelper.estimateEveryNSplitCount(5, "12"))
        assertNull(PdfSplitHelper.estimateEveryNSplitCount(null, "2"))
        assertNull(PdfSplitHelper.estimateEveryNSplitCount(10, "0"))
        assertNull(PdfSplitHelper.estimateEveryNSplitCount(10, "abc"))
    }

    @Test
    fun validateRangesInputAcceptsValidRangesAndSinglePages() {
        assertNull(PdfSplitHelper.validateRangesInput("1-3, 5, 7-9", totalPages = 10, mergeRanges = false))
        assertNull(PdfSplitHelper.validateRangesInput("1", totalPages = null, mergeRanges = false))
        assertNull(PdfSplitHelper.validateRangesInput("2-4, 4-6", totalPages = 6, mergeRanges = true))
    }

    @Test
    fun validateRangesInputRejectsInvalidSyntaxAndBounds() {
        assertNotNull(PdfSplitHelper.validateRangesInput("", totalPages = 5, mergeRanges = false))
        assertNotNull(PdfSplitHelper.validateRangesInput("1-3, ", totalPages = 5, mergeRanges = false))
        assertNotNull(PdfSplitHelper.validateRangesInput("5-2", totalPages = 10, mergeRanges = false))
        assertNotNull(PdfSplitHelper.validateRangesInput("0-2", totalPages = 10, mergeRanges = false))
        assertNotNull(PdfSplitHelper.validateRangesInput("1-11", totalPages = 10, mergeRanges = false))
        assertNotNull(PdfSplitHelper.validateRangesInput("abc", totalPages = 10, mergeRanges = false))
    }

    @Test
    fun validateRangesInputEnforcesMaxSplitOutputsOnlyWhenUnmerged() {
        val tooManyGroups = (1..51).joinToString(", ")
        assertNotNull(PdfSplitHelper.validateRangesInput(tooManyGroups, totalPages = 100, mergeRanges = false))
        assertNull(PdfSplitHelper.validateRangesInput(tooManyGroups, totalPages = 100, mergeRanges = true))
    }

    @Test
    fun formatBytesFormatsExpectedUnits() {
        assertEquals("", PdfSplitHelper.formatBytes(0L))
        assertEquals("512 B", PdfSplitHelper.formatBytes(512L))
        assertEquals("1.5 KB", PdfSplitHelper.formatBytes(1536L))
        assertEquals("2.0 MB", PdfSplitHelper.formatBytes(2L * 1024L * 1024L))
    }
}


package com.podoc.app.ui.screens.tools

import com.podoc.app.data.model.AudioFormatOption
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioExtractorHelperTest {

    @Test
    fun formatBytesFormatsExpectedUnits() {
        assertEquals("", AudioExtractorHelper.formatBytes(0L))
        assertEquals("512 B", AudioExtractorHelper.formatBytes(512L))
        assertEquals("1.5 KB", AudioExtractorHelper.formatBytes(1536L))
        assertEquals("2.0 MB", AudioExtractorHelper.formatBytes(2L * 1024L * 1024L))
        assertEquals("1.50 GB", AudioExtractorHelper.formatBytes((1.5 * 1024.0 * 1024.0 * 1024.0).toLong()))
    }

    @Test
    fun formatDurationFormatsMmSsAndHhMmSs() {
        assertEquals("--:--", AudioExtractorHelper.formatDuration(null))
        assertEquals("--:--", AudioExtractorHelper.formatDuration(0f))
        assertEquals("00:45", AudioExtractorHelper.formatDuration(45.2f))
        assertEquals("02:06", AudioExtractorHelper.formatDuration(125.7f))
        assertEquals("01:01:05", AudioExtractorHelper.formatDuration(3665f))
    }

    @Test
    fun suggestExtensionRespectsCodecAndFormatOption() {
        assertEquals("m4a", AudioExtractorHelper.suggestExtension("aac", AudioFormatOption.ORIGINAL))
        assertEquals("mp3", AudioExtractorHelper.suggestExtension("mp3", AudioFormatOption.ORIGINAL))
        assertEquals("opus", AudioExtractorHelper.suggestExtension("opus", AudioFormatOption.ORIGINAL))
        assertEquals("flac", AudioExtractorHelper.suggestExtension("flac", AudioFormatOption.ORIGINAL))
        assertEquals("wav", AudioExtractorHelper.suggestExtension("pcm_s16le", AudioFormatOption.ORIGINAL))

        assertEquals("mp3", AudioExtractorHelper.suggestExtension("aac", AudioFormatOption.MP3))
        assertEquals("m4a", AudioExtractorHelper.suggestExtension("mp3", AudioFormatOption.M4A))
        assertEquals("wav", AudioExtractorHelper.suggestExtension("aac", AudioFormatOption.WAV))
    }
}


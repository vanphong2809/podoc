package com.podoc.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoDurationFormatTest {
    @Test fun nullOrNonPositiveDurationsReturnNull() {
        assertNull(formatVideoDuration(null))
        assertNull(formatVideoDuration(0f))
        assertNull(formatVideoDuration(-5f))
    }

    @Test fun subSecondPositiveDurationRoundsToOneSecond() {
        assertEquals("00:01", formatVideoDuration(0.3f))
    }

    @Test fun secondsAndMinutesFormatAsMmSs() {
        assertEquals("00:09", formatVideoDuration(9f))
        assertEquals("02:05", formatVideoDuration(125.4f))
        assertEquals("59:59", formatVideoDuration(3599f))
    }

    @Test fun hoursFormatAsHmmSs() {
        assertEquals("1:01:05", formatVideoDuration(3665f))
    }
}


package com.podoc.app.service

import org.junit.Assert.*
import org.junit.Test

class VideoPreloadPolicyTest {
    @Test fun movingFromSixToSevenIntroducesTwelveBeforeItIsSelected() {
        val atSix = VideoPreloadPolicy.window(40, 5).toSet()
        val atSeven = VideoPreloadPolicy.window(40, 6).toSet()
        assertEquals((0..10).toSet(), atSix)
        assertEquals(setOf(11), atSeven - atSix)
        assertEquals(10, atSix.intersect(atSeven).size)
        for (center in 6..15) {
            val previous = VideoPreloadPolicy.window(40, center - 1).toSet()
            val current = VideoPreloadPolicy.window(40, center).toSet()
            assertTrue("Selected video was already in the previous window", center in previous)
            assertEquals(1, (current - previous).size)
        }
    }

    @Test fun edgesAndEmptyListsNeverRequestInvalidNeighbours() {
        assertEquals((0..5).toList(), VideoPreloadPolicy.window(20, 0).toList())
        assertEquals((14..19).toList(), VideoPreloadPolicy.window(20, 19).toList())
        assertEquals(listOf(0), VideoPreloadPolicy.window(1, 0).toList())
        assertTrue(VideoPreloadPolicy.window(0, 0).isEmpty())
    }

    @Test fun fastSwipingReducesWastedDataWithoutDroppingAnyNeighbour() {
        val steady = (-5..5).filter { it != 0 }.sumOf { VideoPreloadPolicy.targetMs(it, false, false, false) }
        val rapid = (-5..5).filter { it != 0 }.sumOf { VideoPreloadPolicy.targetMs(it, false, true, false) }
        assertTrue(rapid < steady)
        assertTrue((-5..5).all { VideoPreloadPolicy.targetMs(it, true, false, false) >= 1_000 })
        assertEquals(0L, VideoPreloadPolicy.targetMs(6, false, false, false))
    }
}

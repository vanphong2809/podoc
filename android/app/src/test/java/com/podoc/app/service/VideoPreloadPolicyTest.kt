package com.podoc.app.service

import org.junit.Assert.*
import org.junit.Test

class VideoPreloadPolicyTest {
    @Test fun twentyOnePositionsSlideOneAtATimeWithTenPositionLookahead() {
        val atEleven = VideoPreloadPolicy.window(80, 10).toSet()
        val atTwelve = VideoPreloadPolicy.window(80, 11).toSet()
        assertEquals((0..20).toSet(), atEleven)
        assertEquals(setOf(21), atTwelve - atEleven)
        assertEquals(20, atEleven.intersect(atTwelve).size)
        for (center in 11..50) {
            assertTrue(center + 9 in VideoPreloadPolicy.window(80, center - 1))
            assertEquals(21, VideoPreloadPolicy.window(80, center).count())
        }
    }

    @Test fun edgesAndEmptyListsNeverRequestInvalidNeighbours() {
        assertEquals((0..10).toList(), VideoPreloadPolicy.window(40, 0).toList())
        assertEquals((29..39).toList(), VideoPreloadPolicy.window(40, 39).toList())
        assertEquals(listOf(0), VideoPreloadPolicy.window(1, 0).toList())
        assertTrue(VideoPreloadPolicy.window(0, 0).isEmpty())
    }

    @Test fun largerWindowHasABoundedOpeningAndSteadyDataBudget() {
        assertEquals(23_000L, (-10..10).sumOf { VideoPreloadPolicy.targetMs(it, true, false, false) })
        assertEquals(42_000L, (-10..10).sumOf { VideoPreloadPolicy.targetMs(it, false, false, false) })
        assertEquals(0L, VideoPreloadPolicy.targetMs(11, false, false, false))
        assertEquals(1_000L, VideoPreloadPolicy.taskTargetMs(1, 0, false, false, false))
        assertEquals(6_000L, VideoPreloadPolicy.taskTargetMs(1, 1_000, false, false, false))
        assertEquals(1_000L, VideoPreloadPolicy.taskTargetMs(10, 0, false, false, false))
    }

    @Test fun byteLimitedBufferDoesNotWaitForAnUnreachableEightSeconds() {
        val paused = VideoPreloadPolicy.Budget.PAUSED
        assertEquals(VideoPreloadPolicy.Budget.FULL, VideoPreloadPolicy.playbackBudget(5_000, false, 120_000, false, paused))
        assertEquals(VideoPreloadPolicy.Budget.FULL, VideoPreloadPolicy.playbackBudget(3_000, false, 120_000, true, paused))
        assertEquals(VideoPreloadPolicy.Budget.OPENINGS, VideoPreloadPolicy.playbackBudget(1_000, false, 120_000, true, paused))
        assertEquals(paused, VideoPreloadPolicy.playbackBudget(500, false, 120_000, true, paused))
        assertEquals(paused, VideoPreloadPolicy.playbackBudget(5_000, true, 120_000, false, paused))
    }

    @Test fun fastSwipingReducesDeepeningButKeepsEveryOpening() {
        val steady = (-10..10).sumOf { VideoPreloadPolicy.targetMs(it, false, false, false) }
        val rapid = (-10..10).sumOf { VideoPreloadPolicy.targetMs(it, false, true, false) }
        assertTrue(rapid < steady)
        assertTrue((-10..10).filter { it != 0 }.all { VideoPreloadPolicy.targetMs(it, false, true, false) >= 1_000 })
    }
}

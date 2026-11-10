package com.podoc.app.service

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Durations refer to playable media, including its original audio. */
internal object VideoPreloadPolicy {
    const val RADIUS = 5
    const val STARTUP_CURRENT_MS = 3_000L
    const val STARTUP_NEIGHBOUR_MS = 1_000L

    fun window(size: Int, center: Int): IntRange =
        if (size <= 0 || center !in 0 until size) IntRange.EMPTY
        else max(0, center - RADIUS)..min(size - 1, center + RADIUS)

    fun targetMs(distance: Int, initial: Boolean, rapid: Boolean, slowNetwork: Boolean): Long {
        val gap = abs(distance)
        if (gap > RADIUS) return 0
        if (initial) return if (gap == 0) STARTUP_CURRENT_MS else STARTUP_NEIGHBOUR_MS
        if (gap == 0) return 0 // The player owns loading of the active source.
        if (rapid || slowNetwork) return if (gap <= 2) 2_000 else 1_000
        return when (gap) { 1 -> 6_000; 2 -> 4_000; else -> 2_000 }
    }
}

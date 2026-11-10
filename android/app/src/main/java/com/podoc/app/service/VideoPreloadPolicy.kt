package com.podoc.app.service

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Durations include the source's unchanged audio; positions refer to feed order. */
internal object VideoPreloadPolicy {
    const val RADIUS = 10
    const val MAX_WINDOW = RADIUS * 2 + 1
    const val STARTUP_CURRENT_MS = 3_000L
    const val STARTUP_NEIGHBOUR_MS = 1_000L
    enum class Budget { PAUSED, OPENINGS, FULL }

    fun window(size: Int, center: Int): IntRange =
        if (size <= 0 || center !in 0 until size) IntRange.EMPTY
        else max(0, center - RADIUS)..min(size - 1, center + RADIUS)

    fun targetMs(distance: Int, initial: Boolean, rapid: Boolean, slowNetwork: Boolean): Long {
        val gap = abs(distance)
        if (gap > RADIUS) return 0
        if (initial) return if (gap == 0) STARTUP_CURRENT_MS else STARTUP_NEIGHBOUR_MS
        if (gap == 0) return 0 // The active player owns its loading.
        if (rapid || slowNetwork) return if (gap <= 2) 2_000 else 1_000
        return when (gap) { 1 -> 6_000; 2 -> 4_000; in 3..5 -> 2_000; else -> 1_000 }
    }

    fun taskTargetMs(distance: Int, completedMs: Long, initial: Boolean, rapid: Boolean, slowNetwork: Boolean): Long {
        val wanted = targetMs(distance, initial, rapid, slowNetwork)
        if (wanted <= completedMs) return 0
        // Cover every opening before deepening any already playable neighbour.
        return if (!initial && completedMs < STARTUP_NEIGHBOUR_MS) STARTUP_NEIGHBOUR_MS else wanted
    }

    fun playbackBudget(bufferedMs: Long, buffering: Boolean, remainingMs: Long,
        loading: Boolean, previous: Budget): Budget {
        if (buffering) return Budget.PAUSED
        val complete = remainingMs >= 0 && bufferedMs >= remainingMs - 250
        if (complete) return Budget.FULL
        if (bufferedMs < 750) return Budget.PAUSED
        // A byte-limited player can stop loading with fewer than 8 seconds.
        // Waiting for an unreachable time threshold would stop prefetch forever.
        if (!loading || bufferedMs >= 2_500) return Budget.FULL
        if (bufferedMs >= 1_500 && previous == Budget.FULL) return Budget.FULL
        return Budget.OPENINGS
    }
}

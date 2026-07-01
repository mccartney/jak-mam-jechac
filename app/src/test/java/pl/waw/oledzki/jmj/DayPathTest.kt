// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.maplibre.android.geometry.LatLng

/**
 * Replay tests for the trip estimator. A brigade's day is concatenated into one [DayPath];
 * a [TripCursor] is fed a synthetic GPS track and must report the right trip, advancing
 * across seams from position + direction of travel alone — never from the clock.
 *
 * The fixture is deliberately nasty: A→B→C→B→C→B, so the B↔C corridor is driven four times
 * (east, west, east, west). Any global nearest-point match would be hopeless there; the
 * windowed cursor plus heading must keep the passes apart.
 */
class DayPathTest {

    // Three corners ~1 km apart. A below B (north step); C east of B (east step).
    private val a = doubleArrayOf(52.20, 21.00)
    private val b = doubleArrayOf(52.21, 21.00)
    private val c = doubleArrayOf(52.21, 21.02)

    // The day: up to B, then shuttle B↔C twice more. Five trips, indices 0..4.
    private val day = DayPath.build(
        listOf(
            shape(a, b),   // 0: A→B
            shape(b, c),   // 1: B→C
            shape(c, b),   // 2: C→B
            shape(b, c),   // 3: B→C
            shape(c, b),   // 4: C→B
        )
    )

    @Test
    fun `advances across a complex A-B-C-B-C-B day, never backwards`() {
        val cursor = TripCursor(day, startTrip = 0)
        val seen = wholeDayTrack().map { cursor.update(it[0], it[1]).activeTrip }

        // distinct() keeps first-occurrence order, so this asserts it visits every trip *in order*.
        assertEquals(listOf(0, 1, 2, 3, 4), seen.distinct(), "should advance through all five trips in order")
        assertEquals(4, seen.last(), "should finish on the last trip")
        seen.zipWithNext { x, y -> assertTrue(y >= x, "active trip went backwards: $x -> $y") }
    }

    @Test
    fun `lateness or dwelling cannot skip or block advancement`() {
        // Same track, but every fix repeated three times — a slow/late bus loitering at each
        // point. The cursor consults no clock, so the outcome is identical to the brisk run.
        val cursor = TripCursor(day, startTrip = 0)
        val seen = wholeDayTrack().flatMap { listOf(it, it, it) }.map { cursor.update(it[0], it[1]).activeTrip }
        assertEquals(listOf(0, 1, 2, 3, 4), seen.distinct())
        assertEquals(4, seen.last())
    }

    @Test
    fun `at a terminus it holds the trip and previews the next, then advances when movement resumes`() {
        val cursor = TripCursor(day, startTrip = 0)
        // Drive A→B; we end at the seam between trip 0 and trip 1.
        leg(a, b, 8).forEach { cursor.update(it[0], it[1]) }

        // Layover: jitter around B (each move < 5 m), as a parked bus does on GPS.
        repeat(5) { i ->
            val wobble = if (i % 2 == 0) 0.00002 else -0.00002   // ~2 m
            val s = cursor.update(b[0] + wobble, b[1] + wobble)
            assertEquals(0, s.activeTrip, "must still be on trip 0 while parked at the terminus")
            assertEquals(1, s.previewTrip, "the next trip should be previewed at the seam")
        }

        // Pull out toward C: within a couple of fixes the cursor commits to trip 1.
        val after = leg(b, c, 8).map { cursor.update(it[0], it[1]).activeTrip }
        assertTrue(after.any { it == 1 }, "should advance to trip 1 once moving toward C")
    }

    @Test
    fun `mid-trip there is no preview`() {
        val cursor = TripCursor(day, startTrip = 0)
        var state = TripCursor.State(0, null, true)
        leg(a, b, 12).take(6).forEach { state = cursor.update(it[0], it[1]) }   // half way up A→B
        assertEquals(0, state.activeTrip)
        assertNull(state.previewTrip, "no seam in sight mid-trip")
    }

    @Test
    fun `cold start locates the bus mid-leg, not off-route`() {
        // First fix is halfway up A→B — well beyond the forward window from the leg's start,
        // so a naive seed-at-start cursor would wrongly flag off-route. It must locate instead.
        val cursor = TripCursor(day, startTrip = 0)
        val mid = leg(a, b, 2)[1]
        val s = cursor.update(mid[0], mid[1])
        assertTrue(s.onRoute, "should locate within the leg on the first fix")
        assertEquals(0, s.activeTrip)
    }

    @Test
    fun `cold start works when the picked leg is a later one`() {
        // Picked leg is trip 2 (C→B), and the first fix is halfway along it. The leg-wide
        // lock searches only that leg, so the shared B↔C corridor doesn't confuse it.
        val cursor = TripCursor(day, startTrip = 2)
        val mid = leg(c, b, 2)[1]
        val s = cursor.update(mid[0], mid[1])
        assertTrue(s.onRoute)
        assertEquals(2, s.activeTrip)
    }

    @Test
    fun `re-locks after a sustained detour and picks the bus back up`() {
        val cursor = TripCursor(day, startTrip = 0)
        leg(a, b, 8).forEach { cursor.update(it[0], it[1]) }
        repeat(5) { assertTrue(!cursor.update(52.30, 21.10).onRoute, "off-route during the detour") }

        // Reappear partway along the leg again: the re-lock should pick us up.
        val mid = leg(a, b, 2)[1]
        assertTrue(cursor.update(mid[0], mid[1]).onRoute, "should re-lock and recover after a long detour")
    }

    @Test
    fun `a wild fix holds the last trip and flags off-route, then recovers`() {
        val cursor = TripCursor(day, startTrip = 0)
        leg(a, b, 6).forEach { cursor.update(it[0], it[1]) }
        cursor.update(b[0], b[1])

        val off = cursor.update(52.30, 21.10)   // far off any segment
        assertTrue(!off.onRoute, "a fix far off the path must flag off-route")
        assertEquals(0, off.activeTrip, "and hold the last confident trip")

        val back = cursor.update(b[0], b[1])     // back on the path
        assertTrue(back.onRoute, "should recover once back on the path")
    }

    @Test
    fun `tripAt maps arc ranges to trips and clamps at the ends`() {
        assertEquals(0, day.tripAt(-100.0), "before the start clamps to trip 0")
        assertEquals(4, day.tripAt(day.tripEnd(4) + 100.0), "past the end clamps to the last trip")
        for (t in 0 until day.tripCount) {
            val mid = (day.tripStart(t) + day.tripEnd(t)) / 2
            assertEquals(t, day.tripAt(mid), "arc mid trip $t should map to it")
        }
    }

    // --- fixtures ----------------------------------------------------------------------------

    /** A trip shape: a straight run of 6 points from [from] to [to]. */
    private fun shape(from: DoubleArray, to: DoubleArray): List<LatLng> =
        leg(from, to, 5).map { LatLng(it[0], it[1]) }

    /** [n]+1 evenly spaced points from [from] to [to], inclusive of both ends. */
    private fun leg(from: DoubleArray, to: DoubleArray, n: Int): List<DoubleArray> =
        (0..n).map { i ->
            val t = i.toDouble() / n
            doubleArrayOf(from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t)
        }

    /** A dense GPS track over the whole A→B→C→B→C→B day. */
    private fun wholeDayTrack(): List<DoubleArray> =
        leg(a, b, 8) + leg(b, c, 8) + leg(c, b, 8) + leg(b, c, 8) + leg(c, b, 8)
}

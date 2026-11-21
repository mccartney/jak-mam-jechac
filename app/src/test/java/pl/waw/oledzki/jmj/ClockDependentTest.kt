// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ClockDependentTest {

    private val today = LocalDate.of(2026, 11, 20)
    private fun at(hhmm: String) = LocalDateTime.of(today, LocalTime.parse(hhmm))

    private fun trip(dep: String, arr: String, varCode: String = "TP-X") =
        Trip(null, "", varCode, listOf(StopTime("A", dep, dep), StopTime("B", arr, arr)))

    // pull-out, two revenue runs, a run past midnight, pull-in
    private val day = listOf(
        trip("05:40", "05:50", "TD-X"),
        trip("06:00", "06:31"),
        trip("06:40", "07:10"),
        trip("24:10", "24:40"),
        trip("24:50", "25:05", "TZ-X"),
    )

    @Test
    fun `before the shift today suggests the pull-out`() {
        assertEquals(0, nowIndex(day, today, at("04:00")))
    }

    @Test
    fun `mid-trip today suggests the trip in progress`() {
        assertEquals(1, nowIndex(day, today, at("06:15")))
    }

    @Test
    fun `between trips today suggests the next one`() {
        assertEquals(2, nowIndex(day, today, at("06:35")))
    }

    @Test
    fun `a trip arriving this very minute still counts as current`() {
        assertEquals(2, nowIndex(day, today, at("07:10")))
    }

    @Test
    fun `late evening today suggests the run past midnight`() {
        assertEquals(3, nowIndex(day, today, at("23:00")))
    }

    @Test
    fun `just after midnight yesterday's duty suggests its run past midnight`() {
        assertEquals(3, nowIndex(day, today.minusDays(1), at("00:15")))
    }

    @Test
    fun `just after midnight yesterday's duty suggests its pull-in once the last run is done`() {
        assertEquals(4, nowIndex(day, today.minusDays(1), at("00:45")))
    }

    @Test
    fun `yesterday's duty once it's over has no leg running now`() {
        assertNull(nowIndex(day, today.minusDays(1), at("09:00")))
    }

    @Test
    fun `another date has no leg running now`() {
        assertNull(nowIndex(day, today.plusDays(1), at("06:15")))
    }

    // --- remembered selection ---

    private val hour = 60L * 60 * 1000
    private val saved = 1_000_000_000_000L

    @Test
    fun `a selection is remembered for twelve hours`() {
        assertTrue(isRemembered(saved, saved + 1))
        assertTrue(isRemembered(saved, saved + 12 * hour))
        assertFalse(isRemembered(saved, saved + 12 * hour + 1))
    }

    @Test
    fun `nothing saved is never remembered`() {
        assertFalse(isRemembered(0L, saved))
    }
}

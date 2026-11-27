// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class NextStopTest {

    // A straight road due north, ~1.1 km, with stops at its start, middle and end.
    private val framing = RouteFraming.fromGeoJson(
        """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[21.0,52.20],[21.0,52.21]]}}""",
        """{"type":"FeatureCollection","features":[""" +
            listOf("52.20", "52.205", "52.21").joinToString(",") {
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[21.0,$it]}}"""
            } + "]}",
    )
    private val half = 0.005 * 111_320.0   // metres between consecutive stops

    @Test
    fun `before the first stop the first stop is ahead`() {
        assertEquals(0, framing.stopAhead(-50.0))
    }

    @Test
    fun `between stops the next one is ahead`() {
        assertEquals(2, framing.stopAhead(half * 1.25))
    }

    @Test
    fun `past the last stop the last stop stays ahead`() {
        assertEquals(2, framing.stopAhead(half * 3))
    }

    private val due = LocalDateTime.of(2026, 11, 26, 10, 4)

    @Test
    fun `before the next stop's time is ahead`() {
        assertEquals(90L, scheduleDeviation(due, due.minusSeconds(90)))
    }

    @Test
    fun `after the next stop's time is late`() {
        assertEquals(-90L, scheduleDeviation(due, due.plusSeconds(90)))
    }

    @Test
    fun `a gap of hours is another day, not a deviation`() {
        assertNull(scheduleDeviation(due, due.plusHours(5)))
    }

    @Test
    fun `post number is the stop id's last two digits, depots have none`() {
        assertEquals("09", postNumber("200809"))
        assertNull(postNumber("R-0700"))
    }

    @Test
    fun `formats with sign and seconds`() {
        assertEquals("+1:05", formatDeviation(65))
        assertEquals("−2:30", formatDeviation(-150))
        assertEquals("0:00", formatDeviation(0))
    }
}

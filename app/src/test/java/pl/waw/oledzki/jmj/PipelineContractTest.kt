// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Parses preprocess/build.py's golden output (preprocess/tests/expected, on the test
 * classpath) — the exact files the pipeline produces from its synthetic feed. If either
 * side changes the JSON format without the other, this or the Python golden test fails.
 */
class PipelineContractTest {

    private fun read(name: String): String =
        javaClass.getResource("/$name")?.readText() ?: error("can't find /$name on the test classpath")

    private val line = parseLine(read("lines/504.json"))
    private val pts = line.services.getValue("2026-11-20:PtS")

    @Test
    fun `feed version and calendar come through`() {
        assertEquals("2026-11-20", line.feedVersion)
        assertEquals(listOf("2026-11-13", "2026-11-20", "2026-11-27"), line.calendar["2026-11-20:PtS"])
        assertEquals("2026-11-22:NdS", line.resolveServiceId(LocalDate.of(2026, 11, 11)))
    }

    @Test
    fun `shapes are read as lon-lat pairs into LatLng`() {
        val first = line.shapes.getValue("S_N").first()
        assertEquals(52.12923, first.latitude)
        assertEquals(21.06825, first.longitude)
        assertEquals(3, line.shapes.getValue("S_N").size)
    }

    @Test
    fun `stops carry name and position`() {
        assertEquals(Stop("Dw. Centralny", 52.22822, 21.00338), line.stops["C1"])
        assertEquals(setOf("C1", "C2", "D", "K1", "MID"), line.stops.keys)
    }

    @Test
    fun `brigade chain keeps pipeline order, depot moves included`() {
        assertEquals(listOf("1", "2"), pts.keys.sorted())
        val chain = pts.getValue("1")
        assertEquals(listOf("TX-OUT", "TP-DWC", "TP-KAB", "TP-NIGHT", "TX-IN"), chain.map { it.varCode })
        assertEquals(listOf(1, 0, 0, 0, 1), chain.map { it.exc })
        assertEquals("Dw. Centralny", chain[1].head)
    }

    @Test
    fun `a shapeless depot move parses to a null shape`() {
        assertNull(pts.getValue("1").first().shape)
        assertEquals("S_N", pts.getValue("1")[1].shape)
    }

    @Test
    fun `stop times expand the compact t, a-d and r forms`() {
        val north = pts.getValue("1")[1]
        assertEquals(
            listOf(
                StopTime("K1", "06:00", "06:00"),
                StopTime("MID", "06:15", "06:15", onRequest = true),
                StopTime("C1", "06:28", "06:29"),
                StopTime("C2", "06:31", "06:31"),
            ),
            north.stops,
        )
        assertEquals("06:00", north.departure)
        assertEquals("06:31", north.arrival)
    }

    @Test
    fun `after-midnight times survive as hours past 24`() {
        val pullIn = pts.getValue("1").last()
        assertEquals("24:50", pullIn.departure)
        assertEquals(25 * 60 + 5, hhmmToMinutes(pullIn.arrival!!))
    }

    @Test
    fun `brigade index lists bus lines with brigades per day-type`() {
        val index = parseBrigades(read("brigades.json"))
        assertEquals("2026-11-20", index.feedVersion)
        val info = index.lines.single()   // the tram line is not listed
        assertEquals("504", info.line)
        assertEquals("Os. Kabaty — Dw. Centralny", info.name)
        assertEquals(3, info.type)
        assertEquals(listOf("1", "2"), info.brigades("PtS"))
        assertEquals(listOf("1"), info.brigades("NdS"))
        assertEquals(emptyList<String>(), info.brigades("SbS"))
    }

    @Test
    fun `brigade index gives the feed's day-type per date, weekday rule outside it`() {
        val index = parseBrigades(read("brigades.json"))
        assertEquals("PtS", index.dayType(LocalDate.of(2026, 11, 20)))
        assertEquals("NdS", index.dayType(LocalDate.of(2026, 11, 11)))   // Wednesday holiday
        assertEquals("PcS", index.dayType(LocalDate.of(2026, 11, 23)))   // tram-only date: not in the index
        assertEquals("SbS", index.dayType(LocalDate.of(2026, 12, 5)))    // outside the feed
    }
}

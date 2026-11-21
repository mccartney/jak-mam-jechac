// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class LineDataTest {

    private val stops = mapOf(
        "K1" to Stop("Os. Kabaty", 52.129, 21.068),
        "MID" to Stop("Metro Wilanowska", 52.180, 21.024),
        "C1" to Stop("Dw. Centralny", 52.228, 21.003),
        "C2" to Stop("Dw. Centralny", 52.229, 21.004),
    )

    private fun data(calendar: Map<String, List<String>> = emptyMap()) =
        LineData("v", calendar, emptyMap(), stops, emptyMap())

    private fun trip(vararg ids: String, requestAt: Set<Int> = emptySet()) = Trip(
        shape = null, head = "", varCode = "",
        stops = ids.mapIndexed { i, id -> StopTime(id, "06:0$i", "06:0$i", onRequest = i in requestAt) },
    )

    // --- resolveServiceId ---

    private val calendar = mapOf(
        "2026-11-20:PtS" to listOf("2026-11-13", "2026-11-20"),
        "2026-11-22:NdS" to listOf("2026-11-11", "2026-11-22"),   // 11 Nov: a Wednesday holiday
    )

    @Test
    fun `resolveServiceId finds the service running that date`() {
        assertEquals("2026-11-20:PtS", data(calendar).resolveServiceId(LocalDate.of(2026, 11, 13)))
    }

    @Test
    fun `resolveServiceId follows the feed calendar on a weekday holiday`() {
        assertEquals("2026-11-22:NdS", data(calendar).resolveServiceId(LocalDate.of(2026, 11, 11)))
    }

    @Test
    fun `resolveServiceId is null outside the feed`() {
        assertNull(data(calendar).resolveServiceId(LocalDate.of(2026, 12, 25)))
    }

    // --- finalClusterStart ---

    @Test
    fun `a single-stop terminus starts at the last index`() {
        assertEquals(2, data().finalClusterStart(trip("C1", "MID", "K1")))
    }

    @Test
    fun `a two-post terminus starts at its first post`() {
        assertEquals(2, data().finalClusterStart(trip("K1", "MID", "C1", "C2")))
    }

    @Test
    fun `the terminus name earlier in the route is not part of the cluster`() {
        assertEquals(3, data().finalClusterStart(trip("C1", "K1", "MID", "C2")))
    }

    @Test
    fun `stops missing from the stop table are skipped, keeping indices aligned with the map`() {
        // the GeoJSON drops "GHOST" too, so the cluster starts at feature 2, not trip-stop 3
        assertEquals(2, data().finalClusterStart(trip("K1", "GHOST", "MID", "C1", "C2")))
    }

    @Test
    fun `a trip with no known stops starts at 0`() {
        assertEquals(0, data().finalClusterStart(trip("GHOST")))
    }

    // --- tripStopsGeoJson ---

    private fun roles(geoJson: String): List<String?> {
        val features = JSONObject(geoJson).getJSONArray("features")
        return List(features.length()) { i ->
            features.getJSONObject(i).optJSONObject("properties")?.getString("role")
        }
    }

    @Test
    fun `stops are points in travel order, unknown ones dropped`() {
        val features = JSONObject(data().tripStopsGeoJson(trip("K1", "GHOST", "C1"))).getJSONArray("features")
        assertEquals(2, features.length())
        val first = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(21.068, first.getDouble(0))   // [lon, lat]
        assertEquals(52.129, first.getDouble(1))
    }

    @Test
    fun `request stops are marked, and the last stop is final even when on request`() {
        assertEquals(
            listOf(null, "request", "final"),
            roles(data().tripStopsGeoJson(trip("K1", "MID", "C1", requestAt = setOf(1, 2)))),
        )
    }

    @Test
    fun `finalClusterStart indexes the GeoJSON features, not the trip's stop list`() {
        val t = trip("K1", "GHOST", "MID", "C1", "C2")
        val features = JSONObject(data().tripStopsGeoJson(t)).getJSONArray("features")
        val c = features.getJSONObject(data().finalClusterStart(t)).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(stops.getValue("C1").lon, c.getDouble(0))
        assertEquals(stops.getValue("C1").lat, c.getDouble(1))
    }

    // --- Trip times ---

    @Test
    fun `departure and arrival fall back to whichever time a stop has`() {
        val t = Trip(null, "", "", listOf(StopTime("A", "06:00", null), StopTime("B", null, "06:30")))
        assertEquals("06:00", t.departure)
        assertEquals("06:30", t.arrival)
    }

    @Test
    fun `a trip without stops has no times`() {
        val t = Trip(null, "", "", emptyList())
        assertNull(t.departure)
        assertNull(t.arrival)
    }

    @Test
    fun `hhmmToMinutes handles after-midnight hours`() {
        assertEquals(6 * 60 + 5, hhmmToMinutes("06:05"))
        assertEquals(25 * 60 + 5, hhmmToMinutes("25:05"))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs

/**
 * The map frames the camera from the [TripCursor]'s arc instead of re-projecting each fix onto
 * the leg. That only works if the two agree: a leg's [RouteFraming] anchored at the [DayPath]'s
 * origin must place every on-route fix at the same arc — and so the same segment — as its own
 * full-path projection.
 */
class CursorFramingTest {

    private fun read(name: String): String =
        javaClass.getResource("/$name")?.readText() ?: error("can't find /$name on the test classpath")

    private val route = read("shape_504_kabaty_centralny.geojson")
    private val stops = read("stops_504_kabaty_centralny.geojson")
    private val shape504: List<LatLng> = JSONObject(route).getJSONObject("geometry").getJSONArray("coordinates")
        .let { c -> (0 until c.length()).map { LatLng(c.getJSONArray(it).getDouble(1), c.getJSONArray(it).getDouble(0)) } }

    // A first trip starting well north-west of the 504, so the day's projection origin differs
    // from the leg's own — the case where unanchored arcs drift apart.
    private val day = DayPath.build(listOf(listOf(LatLng(52.40, 20.90), shape504.first()), shape504))

    @Test
    fun `cursor arc picks the same segment as projecting onto the leg`() {
        val framing = RouteFraming.fromGeoJson(route, stops, origin = day.ref)
        val cursor = TripCursor(day, startTrip = 1)
        for (p in shape504) {
            val st = cursor.update(p.latitude, p.longitude)
            assertTrue(st.onRoute, "every shape vertex is on route")
            val legArc = framing.arcOf(p.latitude, p.longitude)
            assertEquals(legArc, st.tripArc!!, 1e-6, "cursor and leg disagree at $p")
            assertEquals(framing.select(p.latitude, p.longitude)!!.key, framing.segmentAt(st.tripArc))
        }
    }

    @Test
    fun `without the shared origin the arcs drift`() {
        // Guards the origin parameter: the leg's own anchor scales east-west metres differently.
        val unanchored = RouteFraming.fromGeoJson(route, stops)
        val anchored = RouteFraming.fromGeoJson(route, stops, origin = day.ref)
        val end = shape504.last()
        val drift = abs(unanchored.arcOf(end.latitude, end.longitude) - anchored.arcOf(end.latitude, end.longitude))
        assertTrue(drift > 5.0, "expected the projections to differ, got $drift m")
    }
}

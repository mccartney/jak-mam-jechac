// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs

/**
 * A trip that drives a road out and back (like 150 into Dziekanów Leśny, or 715 through Łady)
 * is the same place on both passes. Framing must place the stops, and the bus, on the right
 * pass: stops in trip order, the bus by the [TripCursor]'s place along the trip.
 *
 * Fixture: A → B (~1.1 km due north) and straight back to A on the very same line. A stop
 * ~3 m east of the road is served going north, one ~3 m west going south, at the same spot.
 */
class ReturnLegFramingTest {

    private val a = doubleArrayOf(52.200, 21.000)
    private val m = doubleArrayOf(52.205, 21.000)
    private val b = doubleArrayOf(52.210, 21.000)
    private val shape = listOf(a, m, b, m, a)

    private val outStop = doubleArrayOf(52.203, 21.00004)    // served northbound
    private val backStop = doubleArrayOf(52.203, 20.99996)   // served southbound, same spot
    private val stops = listOf(a, outStop, b, backStop, a)

    private val legMeters = (b[0] - a[0]) * 111_320.0          // one pass, A→B
    private val framing = RouteFraming.fromGeoJson(line(shape), points(stops))

    @Test
    fun `a stop served on the way back frames the return leg, travel up`() {
        // Bus heading back south, between B and the southbound stop.
        val sel = framing.select(legMeters + 300.0)!!
        assertEquals(3, sel.key, "should frame B → the southbound stop")
        assertTrue(angleDiff(sel.bearing, 180.0) < 5, "travel (south) should point up, bearing ${sel.bearing}")
    }

    @Test
    fun `the first stop of a trip that ends where it starts stays at the start`() {
        val sel = framing.select(100.0)!!
        assertEquals(1, sel.key, "should frame A → the northbound stop")
        assertTrue(angleDiff(sel.bearing, 0.0) < 5, "travel (north) should point up, bearing ${sel.bearing}")
    }

    @Test
    fun `framing by arc walks every stop pair in order`() {
        val keys = (0..44).map { framing.select(it * 2 * legMeters / 44)!!.key }
        keys.zipWithNext { x, y -> assertTrue(y >= x, "framing went backwards: $x -> $y") }
        assertEquals(listOf(1, 2, 3, 4), keys.distinct())
    }

    // --- the cursor's place along the trip ---------------------------------------------------

    private val day = DayPath.build(listOf(shape.map { LatLng(it[0], it[1]) }))

    @Test
    fun `cold start on a shared road takes the pass the GPS bearing agrees with`() {
        val northbound = TripCursor(day).update(52.205, 21.00003, bearing = 0.0)
        val southbound = TripCursor(day).update(52.205, 20.99997, bearing = 180.0)
        assertTrue(northbound.onRoute && southbound.onRoute)
        assertEquals(legMeters / 2, northbound.tripArc!!, 5.0)
        assertEquals(legMeters * 1.5, southbound.tripArc!!, 5.0)
    }

    @Test
    fun `the cursor follows the bus onto the return leg and framing follows the cursor`() {
        val cursor = TripCursor(day)
        val track = leg(a, b, 20) + leg(b, a, 20).drop(1)
        val states = track.map { cursor.update(it[0], it[1]) }
        assertTrue(states.all { it.onRoute })
        states.zipWithNext { x, y -> assertTrue(y.tripArc!! >= x.tripArc!! - 1, "cursor went backwards") }
        // Back at the southbound stop's latitude, heading south: the return pass, not the outbound.
        val i = track.indices.last { abs(track[it][0] - backStop[0]) < 0.0003 }
        assertEquals(legMeters + (b[0] - backStop[0]) * 111_320.0, states[i].tripArc!!, 30.0)
    }

    @Test
    fun `framing built on the day's origin measures arcs exactly like the cursor`() {
        // A later trip in a longer day, far from the day's origin, so a different projection
        // origin would skew the arcs.
        val east = doubleArrayOf(52.30, 21.20)
        val east2 = doubleArrayOf(52.32, 21.25)
        val trips = listOf(listOf(a, b), listOf(east, east2))
        val dayPath = DayPath.build(trips.map { t -> leg(t[0], t[1], 5).map { LatLng(it[0], it[1]) } })
        val trip1 = RouteFraming.fromGeoJson(
            line(leg(east, east2, 5)), points(listOf(east, east2)), origin = dayPath.ref,
        )
        val cursor = TripCursor(dayPath, startTrip = 1)
        val p = leg(east, east2, 10)[3]
        val st = cursor.update(p[0], p[1])
        assertEquals(trip1.arcOf(p[0], p[1]), st.tripArc!!, 0.01)
    }

    // --- fixtures ------------------------------------------------------------------------------

    private fun leg(from: DoubleArray, to: DoubleArray, n: Int): List<DoubleArray> =
        (0..n).map { i ->
            val t = i.toDouble() / n
            doubleArrayOf(from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t)
        }

    private fun line(pts: List<DoubleArray>): String {
        val coords = pts.joinToString(",") { "[${it[1]},${it[0]}]" }
        return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}"""
    }

    private fun points(pts: List<DoubleArray>): String {
        val feats = pts.joinToString(",") {
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it[1]},${it[0]}]}}"""
        }
        return """{"type":"FeatureCollection","features":[$feats]}"""
    }

    private fun angleDiff(x: Double, y: Double): Double {
        val d = abs(x - y) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}

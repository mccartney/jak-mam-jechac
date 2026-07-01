// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.maplibre.android.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

private const val M_PER_DEG_LAT = 111_320.0

/**
 * A brigade's whole day laid end to end as one arc-indexed polyline: every trip's shape,
 * concatenated. It is **never drawn** — it's only the coordinate system the [TripCursor]
 * tracks position in. The red line on the map always shows a single trip; this just lets
 * the cursor say *which* trip a position belongs to, no matter how convoluted the day
 * (A→B→C→B→C→B and the like), because matching is local to a moving window rather than a
 * doomed global nearest-point search over a self-retracing path.
 *
 * Geometry uses the same local equirectangular projection (metres, anchored at the first
 * point) as [RouteFraming].
 */
class DayPath private constructor(
    private val path: List<DoubleArray>,   // [x, y] metres
    private val cum: DoubleArray,           // cumulative metres along the path at each vertex
    private val startArc: DoubleArray,      // arc at each trip's first vertex
    private val endArc: DoubleArray,        // arc at each trip's last vertex
    private val ref: LatLng,
) {
    val tripCount: Int get() = startArc.size

    fun tripStart(trip: Int): Double = startArc[trip]
    fun tripEnd(trip: Int): Double = endArc[trip]

    /** Which trip an arc position falls in: the earliest trip whose end is at or after it. */
    fun tripAt(arc: Double): Int {
        for (i in 0 until tripCount) if (arc <= endArc[i]) return i
        return tripCount - 1
    }

    /** A position's projection onto the day-path within a search window. */
    data class Hit(
        val arc: Double,        // metres along the day-path to the closest point
        val lateral: Double,    // metres off the path
        val bearing: Double,    // compass bearing of the path there (its travel direction)
    )

    /**
     * Best projection of (lat, lon) onto the path segments whose arc overlaps [lo]..[hi] —
     * the moving window. Returns null when nothing in the window lies within [maxLateral].
     * When [heading] is given, segments whose direction disagrees by more than [headingTol]
     * are skipped: that's what tells an out-and-back's two passes apart when they share a
     * corridor (same position, opposite bearing).
     */
    fun project(
        lat: Double, lon: Double, lo: Double, hi: Double,
        maxLateral: Double, heading: Double?, headingTol: Double,
    ): Hit? {
        val px = (lon - ref.longitude) * M_PER_DEG_LAT * cos(Math.toRadians(ref.latitude))
        val py = (lat - ref.latitude) * M_PER_DEG_LAT
        var best = Double.MAX_VALUE
        var hit: Hit? = null
        for (i in 0 until path.size - 1) {
            if (cum[i + 1] < lo || cum[i] > hi) continue        // segment outside the window
            val a = path[i]; val b = path[i + 1]
            val abx = b[0] - a[0]; val aby = b[1] - a[1]
            val len2 = abx * abx + aby * aby
            val t = if (len2 == 0.0) 0.0
                else (((px - a[0]) * abx + (py - a[1]) * aby) / len2).coerceIn(0.0, 1.0)
            val cx = a[0] + t * abx; val cy = a[1] + t * aby
            val d = hypot(px - cx, py - cy)
            if (d > maxLateral || d >= best) continue
            val bearing = bearingOf(abx, aby)
            if (heading != null && angleDiff(bearing, heading) > headingTol) continue
            best = d
            hit = Hit(cum[i] + t * hypot(abx, aby), d, bearing)
        }
        return hit
    }

    companion object {
        /** Concatenates each trip's shape (in travel order) into one arc-indexed polyline. */
        fun build(trips: List<List<LatLng>>): DayPath {
            require(trips.any { it.isNotEmpty() }) { "no trip geometry" }
            val ref = trips.first { it.isNotEmpty() }.first()
            val path = ArrayList<DoubleArray>()
            val cum = ArrayList<Double>()
            val startArc = DoubleArray(trips.size)
            val endArc = DoubleArray(trips.size)
            var running = 0.0
            for ((t, trip) in trips.withIndex()) {
                val first = path.size
                for (ll in trip) {
                    val p = doubleArrayOf(
                        (ll.longitude - ref.longitude) * M_PER_DEG_LAT * cos(Math.toRadians(ref.latitude)),
                        (ll.latitude - ref.latitude) * M_PER_DEG_LAT,
                    )
                    if (path.isNotEmpty()) running += hypot(p[0] - path.last()[0], p[1] - path.last()[1])
                    path += p; cum += running
                }
                // Empty trips (no shape) collapse onto the running arc so indices still line up.
                startArc[t] = if (trip.isEmpty()) running else cum[first]
                endArc[t] = running
            }
            return DayPath(path, cum.toDoubleArray(), startArc, endArc, ref)
        }

        /** Compass bearing of a projected east/north step (north = 0°, clockwise). */
        private fun bearingOf(east: Double, north: Double): Double {
            var b = Math.toDegrees(atan2(east, north))
            if (b < 0) b += 360.0
            return b
        }

        /** Smallest absolute difference between two compass bearings, in [0, 180]. */
        internal fun angleDiff(a: Double, b: Double): Double {
            val d = Math.abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }
    }
}

/**
 * Tracks where a bus is along its brigade's [DayPath] and reports which trip it's running —
 * advancing across trip seams **naturally**, from position and direction of travel alone.
 * Wall-clock is never consulted: a late bus shifts *when* a seam is crossed, never *whether*.
 *
 * Seeded once with the trip the driver picks on the selection screen ([startTrip]); from
 * there it auto-advances. Per fix it searches only a window around its current arc, so the
 * day's self-retracing (the same corridor run four times) can't confuse it, and uses the
 * travel bearing to pick the right pass at shared corridors and termini. A weak or absent
 * match **holds** the last confident trip (the quiet "off route / arriving" state) rather
 * than guessing.
 */
class TripCursor(
    private val day: DayPath,
    startTrip: Int = 0,
) {
    /** What the map should show: the active trip, the next one while near the seam, on-route. */
    data class State(val activeTrip: Int, val previewTrip: Int?, val onRoute: Boolean)

    private var arc = day.tripStart(startTrip)
    private var committed = startTrip
    private var pending = startTrip
    private var pendingCount = 0
    private var anchorLat = Double.NaN     // last position far enough back to give a heading
    private var anchorLon = Double.NaN
    private var initialized = false        // have we located the bus *within* its leg yet?
    private var offStreak = 0              // consecutive unmatched fixes (triggers a re-lock)

    fun update(lat: Double, lon: Double, bearing: Double? = null): State {
        // Picking the leg says *which* leg, not *where along it*: locate the bus across the
        // whole leg once (no window, no clock), then track it with the moving window. Also
        // how we recover if we lose the route for a while (see the off-route branch).
        if (!initialized) return localize(lat, lon)

        // Travel bearing from real movement beats the GPS-reported one (which is noisy or
        // absent when crawling/parked); fall back to the reported bearing, else none. The
        // anchor is the last position far enough back to give a stable heading.
        val moved = if (anchorLat.isNaN()) Double.NaN else metersBetween(anchorLat, anchorLon, lat, lon)
        val heading = if (!moved.isNaN() && moved >= MIN_MOVE) bearingBetween(anchorLat, anchorLon, lat, lon) else bearing

        val hit = day.project(
            lat, lon, arc - WINDOW_BACK, arc + WINDOW_FWD,
            MAX_LATERAL, heading, HEADING_TOL,
        )
        if (hit == null) {
            // Off route (detour, GPS gone wild): hold the last confident trip, don't advance,
            // and don't let the stray fix poison the heading anchor. If it persists, drop back
            // to a leg-wide re-lock so we can pick the bus up wherever it rejoined.
            pending = committed; pendingCount = 0
            if (++offStreak >= RELOCATE_AFTER) initialized = false
            return state(onRoute = false)
        }
        offStreak = 0
        // Advance the heading anchor only on accepted fixes, once we've genuinely moved.
        if (anchorLat.isNaN() || (!moved.isNaN() && moved >= MIN_MOVE)) {
            anchorLat = lat; anchorLon = lon
        }
        arc = hit.arc

        // Debounce seam crossings so a single stray fix can't flip the active trip.
        val here = day.tripAt(arc)
        when {
            here == committed -> { pending = committed; pendingCount = 0 }
            here == pending -> if (++pendingCount >= CONFIRM) { committed = pending; pendingCount = 0 }
            else -> { pending = here; pendingCount = 1 }
        }
        return state(onRoute = true)
    }

    /**
     * One-time (or post-detour) lock: search the whole committed leg, no window and no
     * heading, to find where along it the bus is. Holds (off-route) until a match lands
     * within [MAX_LATERAL]; once it does, the moving window takes over.
     */
    private fun localize(lat: Double, lon: Double): State {
        val hit = day.project(
            lat, lon, day.tripStart(committed), day.tripEnd(committed),
            MAX_LATERAL, null, HEADING_TOL,
        ) ?: return state(onRoute = false)
        arc = hit.arc
        anchorLat = lat; anchorLon = lon
        offStreak = 0
        initialized = true
        return state(onRoute = true)
    }

    private fun state(onRoute: Boolean): State {
        val preview = if (committed < day.tripCount - 1 &&
            day.tripEnd(committed) - arc <= PREVIEW_DIST
        ) committed + 1 else null
        return State(committed, preview, onRoute)
    }

    private companion object {
        const val WINDOW_FWD = 400.0     // a fix can advance the cursor at most this far
        const val WINDOW_BACK = 60.0     // small backward slack for GPS jitter
        const val MAX_LATERAL = 45.0     // beyond this off the path we call it off-route
        const val HEADING_TOL = 60.0     // travel vs. path-direction agreement to accept a match
        const val MIN_MOVE = 5.0         // metres of movement before we trust a derived heading
        const val CONFIRM = 2            // consecutive fixes in a new trip before committing
        const val PREVIEW_DIST = 300.0   // show the next trip within this far of the seam
        const val RELOCATE_AFTER = 4     // consecutive off-route fixes before a leg-wide re-lock

        fun bearingBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val cosLat = cos(Math.toRadians((lat1 + lat2) / 2))
            val east = (lon2 - lon1) * M_PER_DEG_LAT * cosLat
            val north = (lat2 - lat1) * M_PER_DEG_LAT
            var b = Math.toDegrees(atan2(east, north))
            if (b < 0) b += 360.0
            return b
        }

        fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val cosLat = cos(Math.toRadians((lat1 + lat2) / 2))
            val east = (lon2 - lon1) * M_PER_DEG_LAT * cosLat
            val north = (lat2 - lat1) * M_PER_DEG_LAT
            return hypot(east, north)
        }
    }
}

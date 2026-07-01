// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import android.location.Location
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

private const val M_PER_DEG_LAT = 111_320.0

/**
 * A trip's path and its stops. Given a position, finds the stop just behind and
 * just ahead along the path and describes how to frame them.
 *
 * Geometry uses a local equirectangular projection (metres) anchored at the
 * path's first point — accurate enough across a single bus line's extent.
 */
class RouteFraming private constructor(
    private val path: List<DoubleArray>,   // [x, y] metres, projected from the shape
    private val cum: DoubleArray,           // cumulative metres along the path at each vertex
    private val stops: List<LatLng>,
    private val stopArc: DoubleArray,       // metres along the path for each stop
    private val ref: LatLng,
    private val finalStageStart: Int,       // stop index at which the terminus cluster begins
) {
    /** How to frame the trip between the previous and next stop. */
    data class Selection(
        val target: LatLng,      // centre of the road between prev/next — goes to screen centre
        val bearing: Double,     // compass bearing prev→next, so travel points up
        val spanMeters: Double,  // extent of the road along travel (vertical fit)
        val crossMeters: Double, // extent of the road across travel (horizontal fit)
        val key: Int,            // index of the next stop; identifies the current segment
    )

    /** Where a position sits relative to this leg, used to detect arrival/handoff. */
    data class Fix(
        val distToPath: Double, // metres off the nearest point of the path
        val arc: Double,        // metres travelled along the path to that point
        val distToEnd: Double,  // straight-line metres to the final stop ("arrived" when small)
    )

    fun locate(location: Location): Fix = locate(location.latitude, location.longitude)

    fun locate(lat: Double, lon: Double): Fix {
        val (arc, dist) = projectArc(path, cum, ref, lat, lon)
        val end = stops.lastOrNull()?.let {
            val p = xy(lat, lon, ref); val q = xy(it.latitude, it.longitude, ref)
            hypot(p[0] - q[0], p[1] - q[1])
        } ?: 0.0
        return Fix(dist, arc, end)
    }

    fun select(location: Location): Selection? = select(location.latitude, location.longitude)

    fun select(lat: Double, lon: Double): Selection? {
        if (stops.size < 2) return null
        val s = projectArc(path, cum, ref, lat, lon).first
        var k = stopArc.indexOfFirst { it > s }
        if (k < 0) k = stops.lastIndex     // past the last stop
        if (k == 0) k = 1                  // before the first stop
        // Final stage: once the next stop is the first of the terminus cluster (the trailing
        // run of stops sharing the last stop's name), frame the whole remaining tail — the
        // rest of the stops plus any shape beyond the last stop — as one steady window
        // instead of the usual prev→next pair. While still approaching that first cluster stop
        // we keep the preceding stop in view (e.g. Centrum + every Dw. Centralny); once it's
        // behind us we drop it and frame only the cluster (first such stop → last). Two sentinel
        // keys, both past every real stop index, so the camera re-frames once on entry and once
        // when the preceding stop drops off, then holds still across the rest of the cluster.
        if (k >= finalStageStart) {
            val approaching = k == finalStageStart
            val startIdx = if (approaching) (finalStageStart - 1).coerceAtLeast(0) else finalStageStart
            return frame(
                stops[startIdx], stops.last(), stopArc[startIdx], cum.last(),
                if (approaching) stops.size else stops.size + 1,
            )
        }
        return frame(
            stops[k - 1], stops[k],
            minOf(stopArc[k - 1], stopArc[k]), maxOf(stopArc[k - 1], stopArc[k]),
            k,
        )
    }

    /**
     * Frames the road from [from] to [to], boxing every path vertex whose arc lies in
     * [lo]..[hi] — not just the straight chord: a hairpin or loop between close stops
     * bulges far past the chord and the bus rides that bulge, so chord-only framing would
     * push the puck off-screen. The box is travel-up (along = up, cross = sideways);
     * [key] identifies the framed segment so the camera only re-frames when it changes.
     */
    private fun frame(from: LatLng, to: LatLng, lo: Double, hi: Double, key: Int): Selection {
        val dx = (to.longitude - from.longitude) * M_PER_DEG_LAT * cos(Math.toRadians(from.latitude))
        val dy = (to.latitude - from.latitude) * M_PER_DEG_LAT
        var bearing = Math.toDegrees(atan2(dx, dy))
        if (bearing < 0) bearing += 360.0

        val theta = Math.toRadians(bearing)
        val sinT = sin(theta); val cosT = cos(theta)
        var alongMin = Double.MAX_VALUE; var alongMax = -Double.MAX_VALUE
        var crossMin = Double.MAX_VALUE; var crossMax = -Double.MAX_VALUE
        fun include(p: DoubleArray) {
            val along = p[0] * sinT + p[1] * cosT
            val cross = p[0] * cosT - p[1] * sinT
            alongMin = minOf(alongMin, along); alongMax = maxOf(alongMax, along)
            crossMin = minOf(crossMin, cross); crossMax = maxOf(crossMax, cross)
        }
        include(xy(from.latitude, from.longitude, ref))
        include(xy(to.latitude, to.longitude, ref))
        for (i in path.indices) if (cum[i] in lo..hi) include(path[i])

        val alongC = (alongMin + alongMax) / 2
        val crossC = (crossMin + crossMax) / 2
        // Rotate the box centre back to east/north (this rotation is its own inverse)…
        val east = alongC * sinT + crossC * cosT
        val north = alongC * cosT - crossC * sinT
        // …then to lat/lon, matching xy()'s equirectangular scaling about ref.
        val target = LatLng(
            ref.latitude + north / M_PER_DEG_LAT,
            ref.longitude + east / (M_PER_DEG_LAT * cos(Math.toRadians(ref.latitude))),
        )
        return Selection(target, bearing, alongMax - alongMin, crossMax - crossMin, key)
    }

    /**
     * The stretch of shape that runs past the final stop — the manoeuvre to where the next
     * trip starts. Begins at the final stop and follows the path to its end; empty when the
     * shape ends at the terminus (nothing to draw).
     */
    fun tailBeyondLastStop(): List<LatLng> {
        val endArc = stopArc.lastOrNull() ?: return emptyList()
        val tail = ArrayList<LatLng>()
        tail += stops.last()
        for (i in path.indices) if (cum[i] > endArc + 1.0) tail += toLatLng(path[i])
        return if (tail.size >= 2) tail else emptyList()
    }

    private fun toLatLng(p: DoubleArray) = LatLng(
        ref.latitude + p[1] / M_PER_DEG_LAT,
        ref.longitude + p[0] / (M_PER_DEG_LAT * cos(Math.toRadians(ref.latitude))),
    )

    companion object {
        /**
         * Zoom that makes a [spanMeters] gap fill [fraction] of a [heightDp]-tall
         * viewport, given the map's current [zoom] and its [metersPerPixel] at that
         * zoom. Pure (no map/Android state) so the framing maths can be unit-tested;
         * clamped to MapLibre's sane street-level range.
         */
        fun zoomForSpan(
            zoom: Double,
            metersPerPixel: Double,
            spanMeters: Double,
            heightDp: Double,
            fraction: Double,
        ): Double {
            val mppWanted = spanMeters / (fraction * heightDp)
            return (zoom + ln(metersPerPixel / mppWanted) / ln(2.0)).coerceIn(11.0, 18.0)
        }

        /**
         * Builds the framing straight from a trip's GeoJSON (line + stops). [finalStageStart]
         * is the index of the first stop in the terminus cluster; the default disables the
         * final-stage framing (used by tests that exercise the plain prev→next behaviour).
         */
        fun fromGeoJson(
            routeGeoJson: String,
            stopsGeoJson: String,
            finalStageStart: Int = Int.MAX_VALUE,
        ): RouteFraming {
            val pathLL = readLineString(routeGeoJson)
            val stops = readPoints(stopsGeoJson)
            val ref = pathLL.first()
            val path = ArrayList<DoubleArray>(pathLL.size)
            val cum = DoubleArray(pathLL.size)
            for ((i, ll) in pathLL.withIndex()) {
                path += xy(ll.latitude, ll.longitude, ref)
                if (i > 0) cum[i] = cum[i - 1] +
                    hypot(path[i][0] - path[i - 1][0], path[i][1] - path[i - 1][1])
            }
            val stopArc = DoubleArray(stops.size) {
                projectArc(path, cum, ref, stops[it].latitude, stops[it].longitude).first
            }
            return RouteFraming(path, cum, stops, stopArc, ref, finalStageStart)
        }

        private fun xy(lat: Double, lon: Double, ref: LatLng) = doubleArrayOf(
            (lon - ref.longitude) * M_PER_DEG_LAT * cos(Math.toRadians(ref.latitude)),
            (lat - ref.latitude) * M_PER_DEG_LAT,
        )

        /**
         * Projects (lat,lon) onto the path: returns (arc, dist) where arc is the metres
         * from the path start to the closest point, and dist is how far off it lies.
         */
        private fun projectArc(
            path: List<DoubleArray>, cum: DoubleArray, ref: LatLng, lat: Double, lon: Double,
        ): Pair<Double, Double> {
            val p = xy(lat, lon, ref)
            var best = Double.MAX_VALUE
            var bestArc = 0.0
            for (i in 0 until path.size - 1) {
                val a = path[i]; val b = path[i + 1]
                val abx = b[0] - a[0]; val aby = b[1] - a[1]
                val len2 = abx * abx + aby * aby
                val t = if (len2 == 0.0) 0.0
                    else (((p[0] - a[0]) * abx + (p[1] - a[1]) * aby) / len2).coerceIn(0.0, 1.0)
                val cx = a[0] + t * abx; val cy = a[1] + t * aby
                val d = hypot(p[0] - cx, p[1] - cy)
                if (d < best) {
                    best = d
                    bestArc = cum[i] + t * hypot(abx, aby)
                }
            }
            return bestArc to best
        }

        private fun readLineString(geoJson: String): List<LatLng> {
            val coords = JSONObject(geoJson).getJSONObject("geometry").getJSONArray("coordinates")
            return (0 until coords.length()).map {
                val c = coords.getJSONArray(it); LatLng(c.getDouble(1), c.getDouble(0))
            }
        }

        private fun readPoints(geoJson: String): List<LatLng> {
            val feats = JSONObject(geoJson).getJSONArray("features")
            return (0 until feats.length()).map {
                val c = feats.getJSONObject(it).getJSONObject("geometry").getJSONArray("coordinates")
                LatLng(c.getDouble(1), c.getDouble(0))
            }
        }
    }
}

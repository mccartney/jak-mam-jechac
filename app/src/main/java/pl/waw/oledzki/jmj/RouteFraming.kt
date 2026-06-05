package pl.waw.oledzki.jmj

import android.content.Context
import android.location.Location
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

private const val M_PER_DEG_LAT = 111_320.0

/**
 * The hard-coded 504 path and its stops. Given a position, finds the stop just
 * behind and just ahead along the path and describes how to frame them.
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
) {
    /** How to frame the trip between the previous and next stop. */
    data class Selection(
        val target: LatLng,     // midpoint of prev/next — goes to screen centre
        val bearing: Double,    // compass bearing prev→next, so travel points up
        val spanMeters: Double, // distance prev↔next
        val key: Int,           // index of the next stop; identifies the current segment
    )

    fun select(location: Location): Selection? {
        if (stops.size < 2) return null
        val s = arcLengthOf(path, cum, ref, location.latitude, location.longitude)
        var k = stopArc.indexOfFirst { it > s }
        if (k < 0) k = stops.lastIndex     // past the last stop
        if (k == 0) k = 1                  // before the first stop
        val prev = stops[k - 1]
        val next = stops[k]
        val dx = (next.longitude - prev.longitude) * M_PER_DEG_LAT * cos(Math.toRadians(prev.latitude))
        val dy = (next.latitude - prev.latitude) * M_PER_DEG_LAT
        var bearing = Math.toDegrees(atan2(dx, dy))
        if (bearing < 0) bearing += 360.0
        val target = LatLng((prev.latitude + next.latitude) / 2, (prev.longitude + next.longitude) / 2)
        return Selection(target, bearing, hypot(dx, dy), k)
    }

    companion object {
        fun load(context: Context): RouteFraming {
            val pathLL = readLineString(context, R.raw.shape_504_kabaty_centralny)
            val stops = readPoints(context, R.raw.stops_504_kabaty_centralny)
            val ref = pathLL.first()
            val path = ArrayList<DoubleArray>(pathLL.size)
            val cum = DoubleArray(pathLL.size)
            for ((i, ll) in pathLL.withIndex()) {
                path += xy(ll.latitude, ll.longitude, ref)
                if (i > 0) cum[i] = cum[i - 1] +
                    hypot(path[i][0] - path[i - 1][0], path[i][1] - path[i - 1][1])
            }
            val stopArc = DoubleArray(stops.size) {
                arcLengthOf(path, cum, ref, stops[it].latitude, stops[it].longitude)
            }
            return RouteFraming(path, cum, stops, stopArc, ref)
        }

        private fun xy(lat: Double, lon: Double, ref: LatLng) = doubleArrayOf(
            (lon - ref.longitude) * M_PER_DEG_LAT * cos(Math.toRadians(ref.latitude)),
            (lat - ref.latitude) * M_PER_DEG_LAT,
        )

        /** Distance (metres) from the path start to the point on the path closest to (lat,lon). */
        private fun arcLengthOf(
            path: List<DoubleArray>, cum: DoubleArray, ref: LatLng, lat: Double, lon: Double,
        ): Double {
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
            return bestArc
        }

        private fun readLineString(context: Context, resId: Int): List<LatLng> {
            val coords = readJson(context, resId).getJSONObject("geometry").getJSONArray("coordinates")
            return (0 until coords.length()).map {
                val c = coords.getJSONArray(it); LatLng(c.getDouble(1), c.getDouble(0))
            }
        }

        private fun readPoints(context: Context, resId: Int): List<LatLng> {
            val feats = readJson(context, resId).getJSONArray("features")
            return (0 until feats.length()).map {
                val c = feats.getJSONObject(it).getJSONObject("geometry").getJSONArray("coordinates")
                LatLng(c.getDouble(1), c.getDouble(0))
            }
        }

        private fun readJson(context: Context, resId: Int) = JSONObject(
            context.resources.openRawResource(resId).bufferedReader().use { it.readText() },
        )
    }
}

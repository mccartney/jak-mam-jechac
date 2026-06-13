package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reproduces the real 229/1 complaint (location off-screen near Ursynów Płd, ~52.154/21.037):
 * the road between the first two stops makes a hairpin — the stops sit ~110 m apart but the
 * tarmac swings ~720 m out and back. The old framing boxed only the straight stop-to-stop
 * chord, so the bus riding the bulge fell far outside the viewport. The fix frames the actual
 * road between the stops; this test asserts the whole sub-path (and the puck on it) fits.
 */
class RouteFramingHairpinTest {

    private fun line(vararg lonlat: Double): String {
        val coords = lonlat.toList().chunked(2).joinToString(",") { "[${it[0]},${it[1]}]" }
        return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}"""
    }

    private fun stops(vararg lonlat: Double): String {
        val feats = lonlat.toList().chunked(2).joinToString(",") {
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it[0]},${it[1]}]}}"""
        }
        return """{"type":"FeatureCollection","features":[$feats]}"""
    }

    // Stop A → a ~720 m hairpin apex due north → Stop B, ~110 m from A (the two stops are close,
    // the road between them is not). The bus drives A → apex → B.
    private val stopA = doubleArrayOf(52.1500, 21.0500)
    private val apex = doubleArrayOf(52.1565, 21.0500)
    private val stopB = doubleArrayOf(52.1510, 21.0501)

    private val framing = RouteFraming.fromGeoJson(
        line(stopA[1], stopA[0], apex[1], apex[0], stopB[1], stopB[0]),
        stops(stopA[1], stopA[0], stopB[1], stopB[0]),
    )

    @Test
    fun `frames the whole hairpin between two close stops, not just the chord`() {
        // Puck out on the bulge, just shy of the apex.
        val sel = framing.select(52.1560, 21.0500)!!
        assertEquals(1, sel.key, "should frame the A→B segment")

        // The framed window covers far more than the ~110 m chord — it spans the ~720 m bulge.
        assertTrue(sel.spanMeters > 600.0, "span ${sel.spanMeters} should cover the bulge, not the chord")

        // Everything the driver must see — both stops and the apex (and the puck) — lands inside
        // the framed window: |along| ≤ span/2 and |cross| ≤ cross/2 about the target.
        for (p in listOf(stopA, apex, stopB, doubleArrayOf(52.1560, 21.0500))) {
            val (along, cross) = offsetFromTarget(sel, p[0], p[1])
            assertTrue(abs(along) <= sel.spanMeters / 2 + 1.5, "along $along outside ±${sel.spanMeters / 2}")
            assertTrue(abs(cross) <= sel.crossMeters / 2 + 1.5, "cross $cross outside ±${sel.crossMeters / 2}")
        }

        // Repro guard: under the OLD chord-only framing (target = chord midpoint, half-window =
        // chord/2) the apex was hundreds of metres off-screen — that's the bug this fixes.
        val chordMid = doubleArrayOf((stopA[0] + stopB[0]) / 2, (stopA[1] + stopB[1]) / 2)
        val apexAlongOld = offsetFromTarget(sel.copy(target = latLng(chordMid)), apex[0], apex[1]).first
        val chordHalf = dist(stopA, stopB) / 2
        assertTrue(
            abs(apexAlongOld) > 5 * chordHalf,
            "sanity: apex ($apexAlongOld m) should dwarf the old half-window ($chordHalf m)",
        )
    }

    /** Along-/cross-travel offset (metres) of a point from the selection's target. */
    private fun offsetFromTarget(sel: RouteFraming.Selection, lat: Double, lon: Double): Pair<Double, Double> {
        val cosRef = cos(Math.toRadians(sel.target.latitude))
        val east = (lon - sel.target.longitude) * 111_320.0 * cosRef
        val north = (lat - sel.target.latitude) * 111_320.0
        val t = Math.toRadians(sel.bearing)
        return (east * sin(t) + north * cos(t)) to (east * cos(t) - north * sin(t))
    }

    private fun latLng(p: DoubleArray) = org.maplibre.android.geometry.LatLng(p[0], p[1])

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        val cosRef = cos(Math.toRadians(a[0]))
        val east = (b[1] - a[1]) * 111_320.0 * cosRef
        val north = (b[0] - a[0]) * 111_320.0
        return Math.hypot(east, north)
    }
}

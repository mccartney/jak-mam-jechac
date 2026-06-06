package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tests the Two Steps View stop-selection logic against the real, hard-coded 504
 * shape/stops (Os. Kabaty → Dw. Centralny). The expected geometry is checked with
 * an *independent* oracle — great-circle distance/bearing — rather than re-deriving
 * the production equirectangular maths, so the test can actually catch a wrong
 * formula instead of mirroring it.
 */
class RouteFramingTest {

    // A handful of the 25 stops, taken verbatim from stops_504_kabaty_centralny.geojson.
    private val kabaty = doubleArrayOf(52.129226, 21.06825)      // seq 0
    private val metroKabaty = doubleArrayOf(52.131281, 21.06491) // seq 1
    private val mielczarskiego = doubleArrayOf(52.131232, 21.05936) // seq 2
    private val starynkiewicza = doubleArrayOf(52.226023, 20.994585) // seq 23
    private val centralny = doubleArrayOf(52.229286, 21.003772) // seq 24

    private val framing: RouteFraming = run {
        fun read(name: String): String {
            for (candidate in listOf("src/main/res/raw/$name", "app/src/main/res/raw/$name")) {
                val f = File(candidate)
                if (f.exists()) return f.readText()
            }
            error("can't find $name (cwd=${File("").absolutePath})")
        }
        RouteFraming.fromGeoJson(
            read("shape_504_kabaty_centralny.geojson"),
            read("stops_504_kabaty_centralny.geojson"),
        )
    }

    @Test
    fun `between two stops frames that exact pair`() {
        // GPS at the midpoint between Metro Kabaty (1) and Mielczarskiego (2).
        val mid = midpoint(metroKabaty, mielczarskiego)
        val sel = framing.select(mid[0], mid[1])!!

        assertEquals(2, sel.key, "next stop should be Mielczarskiego (seq 2)")
        assertStop(sel, metroKabaty, mielczarskiego)
    }

    @Test
    fun `before the first stop clamps to the first segment`() {
        val sel = framing.select(kabaty[0], kabaty[1])!!
        assertEquals(1, sel.key, "should frame the very first segment")
        assertStop(sel, kabaty, metroKabaty)
    }

    @Test
    fun `at or past the last stop clamps to the final segment`() {
        val sel = framing.select(centralny[0], centralny[1])!!
        assertEquals(24, sel.key, "should frame the final segment")
        assertStop(sel, starynkiewicza, centralny)
    }

    @Test
    fun `the selected segment never goes backwards as the bus advances`() {
        // Anti-thrash sanity: walking the stops in order yields a non-decreasing key.
        val keys = listOf(kabaty, metroKabaty, mielczarskiego, starynkiewicza, centralny)
            .map { framing.select(it[0], it[1])!!.key }
        assertEquals(listOf(1, 2, 3, 24, 24), keys)
        keys.zipWithNext { a, b -> assertTrue(b >= a, "key went backwards: $a -> $b") }
    }

    /** Asserts the selection frames [prev]→[next]: midpoint target, span, and bearing. */
    private fun assertStop(sel: RouteFraming.Selection, prev: DoubleArray, next: DoubleArray) {
        val expectedMid = midpoint(prev, next)
        assertEquals(expectedMid[0], sel.target.latitude, 1e-6, "target latitude")
        assertEquals(expectedMid[1], sel.target.longitude, 1e-6, "target longitude")

        // Independent oracle: great-circle distance/bearing. Over a few hundred metres
        // these agree with the production equirectangular maths to well under a percent.
        val gcSpan = haversineMeters(prev, next)
        assertEquals(gcSpan, sel.spanMeters, gcSpan * 0.01, "span (metres)")

        val gcBearing = greatCircleBearing(prev, next)
        assertEquals(0.0, angleDiff(gcBearing, sel.bearing), 1.0, "bearing (degrees)")
    }

    companion object {
        private const val EARTH_R = 6_371_000.0

        private fun midpoint(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)

        private fun haversineMeters(a: DoubleArray, b: DoubleArray): Double {
            val dLat = Math.toRadians(b[0] - a[0])
            val dLon = Math.toRadians(b[1] - a[1])
            val la1 = Math.toRadians(a[0]); val la2 = Math.toRadians(b[0])
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_R * atan2(Math.sqrt(h), Math.sqrt(1 - h))
        }

        private fun greatCircleBearing(a: DoubleArray, b: DoubleArray): Double {
            val la1 = Math.toRadians(a[0]); val la2 = Math.toRadians(b[0])
            val dLon = Math.toRadians(b[1] - a[1])
            val y = sin(dLon) * cos(la2)
            val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        /** Smallest absolute difference between two compass bearings, in [0, 180]. */
        private fun angleDiff(a: Double, b: Double): Double {
            val d = Math.abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }
    }
}

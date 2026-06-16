package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    // Fixtures live in src/test/resources (a real 504 half-trip), loaded off the classpath.
    private fun read(name: String): String =
        javaClass.getResource("/$name")?.readText() ?: error("can't find /$name on the test classpath")

    private fun framing(finalStageStart: Int = Int.MAX_VALUE): RouteFraming =
        RouteFraming.fromGeoJson(
            read("shape_504_kabaty_centralny.geojson"),
            read("stops_504_kabaty_centralny.geojson"),
            finalStageStart,
        )

    private val framing: RouteFraming = framing()

    // 131 (Sadyba → Dw. Centralny, shape 3:831) — the terminus example with two "Dw. Centralny"
    // posts. Stop coords taken verbatim from stops_131_sadyba_centralny.geojson.
    private val centrum131 = doubleArrayOf(52.229590, 21.009960)    // seq 17 (the stop before the cluster)
    private val dwCentralny02 = doubleArrayOf(52.228220, 21.003380) // seq 18 (first stop of the cluster)
    private val dwCentralny21 = doubleArrayOf(52.229440, 21.003720) // seq 19 (final stop)

    // The four debug fake-GPS points (MapScreen.FAKE_POINTS), all sampled from the 131 shape.
    private val fakeA = doubleArrayOf(52.229545, 21.009889) // at Centrum
    private val fakeB = doubleArrayOf(52.228323, 21.004010) // reaching the first Dw. Centralny
    private val fakeC = doubleArrayOf(52.228328, 21.001824) // just past the first Dw. Centralny
    private val fakeD = doubleArrayOf(52.229336, 21.003154) // between the two Dw. Centralny posts

    // The terminus cluster on 131 is the trailing run of "Dw. Centralny" stops (seq 18, 19).
    private fun framing131(): RouteFraming =
        RouteFraming.fromGeoJson(
            read("shape_131_sadyba_centralny.geojson"),
            read("stops_131_sadyba_centralny.geojson"),
            finalStageStart = 18,
        )

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

    @Test
    fun `final stage keeps the preceding stop while approaching the first terminus stop`() {
        // Single-stop terminus (cluster = just Centralny, seq 24): approaching it from
        // Starynkiewicza keeps that preceding stop in the window, under the approach sentinel.
        val fr = framing(finalStageStart = 24)
        val sel = fr.select(starynkiewicza[0], starynkiewicza[1])!!

        assertEquals(25, sel.key, "approach sentinel, steady before the terminus")
        // Both the preceding stop and the terminus sit inside the framed window.
        assertInWindow(sel, starynkiewicza, centralny)
    }

    @Test
    fun `final stage drops the preceding stop once the first terminus stop is behind us`() {
        // Cluster begins at Starynkiewicza (seq 23). Standing at the last stop we're past that
        // first cluster stop, so the window spans only the cluster (first such stop → last),
        // under its own sentinel so the camera re-frames once when the preceding stop drops off.
        val fr = framing(finalStageStart = 23)
        val sel = fr.select(centralny[0], centralny[1])!!

        assertEquals(26, sel.key, "group-only sentinel, distinct from the approach key")
        // The first cluster stop and the final stop sit inside the framed window.
        assertInWindow(sel, starynkiewicza, centralny)
    }

    // One test per debug fake-GPS point, on the real 131 Sadyba → Dw. Centralny trip:
    // the two points before the first "Dw. Centralny" still see Centrum + both posts;
    // the two beyond it frame only the cluster.

    @Test
    fun `131 fake point A (Centrum) frames Centrum and both Dw Centralny posts`() {
        val sel = framing131().select(fakeA[0], fakeA[1])!!
        assertEquals(20, sel.key, "approach sentinel = stops.size")
        assertInWindow(sel, centrum131, dwCentralny02, dwCentralny21)
    }

    @Test
    fun `131 fake point B (reaching first Dw Centralny) still frames Centrum and both posts`() {
        val sel = framing131().select(fakeB[0], fakeB[1])!!
        assertEquals(20, sel.key, "still approaching the first cluster stop")
        assertInWindow(sel, centrum131, dwCentralny02, dwCentralny21)
    }

    @Test
    fun `131 fake point C (past first Dw Centralny) frames only the cluster`() {
        val sel = framing131().select(fakeC[0], fakeC[1])!!
        assertEquals(21, sel.key, "group-only sentinel = stops.size + 1")
        assertInWindow(sel, dwCentralny02, dwCentralny21)
    }

    @Test
    fun `131 fake point D (between the two Dw Centralny posts) frames only the cluster`() {
        val sel = framing131().select(fakeD[0], fakeD[1])!!
        assertEquals(21, sel.key, "group-only sentinel")
        assertInWindow(sel, dwCentralny02, dwCentralny21)
    }

    @Test
    fun `tail beyond the last stop is empty when the shape ends at the terminus`() {
        // The 504 fixture's shape ends at Dw. Centralny, so there's no next-trip manoeuvre to draw.
        assertTrue(framing.tailBeyondLastStop().isEmpty())
    }

    /**
     * Asserts the selection frames [prev]→[next]: travel bearing, and that both stops fall
     * inside the framed window. The window boxes the *road* between the stops, so its span is
     * at least the straight chord (more where the road curves) and both stops sit within it.
     */
    private fun assertStop(sel: RouteFraming.Selection, prev: DoubleArray, next: DoubleArray) {
        // Independent oracle: great-circle distance/bearing. Over a few hundred metres
        // these agree with the production equirectangular maths to well under a percent.
        val gcBearing = greatCircleBearing(prev, next)
        assertEquals(0.0, angleDiff(gcBearing, sel.bearing), 1.0, "bearing (degrees)")

        // The road between the stops is never shorter (along travel) than the straight chord.
        val gcSpan = haversineMeters(prev, next)
        assertTrue(sel.spanMeters >= gcSpan - 1.0, "span ${sel.spanMeters} < chord $gcSpan")

        // Both stops land inside the framed window centred on the target.
        for (p in listOf(prev, next)) {
            val (along, cross) = offsetFromTarget(sel, p[0], p[1])
            assertTrue(Math.abs(along) <= sel.spanMeters / 2 + 1.0, "stop along $along outside window")
            assertTrue(Math.abs(cross) <= sel.crossMeters / 2 + 1.0, "stop cross $cross outside window")
        }
    }

    /** Asserts every given (lat, lon) stop falls inside the selection's framed window. */
    private fun assertInWindow(sel: RouteFraming.Selection, vararg points: DoubleArray) {
        for (p in points) {
            val (along, cross) = offsetFromTarget(sel, p[0], p[1])
            assertTrue(Math.abs(along) <= sel.spanMeters / 2 + 1.0, "stop along $along outside window")
            assertTrue(Math.abs(cross) <= sel.crossMeters / 2 + 1.0, "stop cross $cross outside window")
        }
    }

    /** Along-/cross-travel offset (metres) of a point from the selection's target. */
    private fun offsetFromTarget(sel: RouteFraming.Selection, lat: Double, lon: Double): Pair<Double, Double> {
        val cosRef = cos(Math.toRadians(sel.target.latitude))
        val east = (lon - sel.target.longitude) * 111_320.0 * cosRef
        val north = (lat - sel.target.latitude) * 111_320.0
        val t = Math.toRadians(sel.bearing)
        return (east * sin(t) + north * cos(t)) to (east * cos(t) - north * sin(t))
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

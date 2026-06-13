package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the leg hand-off heuristic with two synthetic legs meeting at a terminus:
 *  - leg A runs west→east along latitude 52.00, ending at (52.00, 21.010);
 *  - leg B departs that same terminus and runs north to (52.005, 21.010).
 * Distances: 0.001° lat ≈ 111 m; 0.001° lon ≈ 68 m at this latitude.
 */
class LegAdvancerTest {

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

    private val legA = RouteFraming.fromGeoJson(
        line(21.000, 52.000, 21.010, 52.000),
        stops(21.000, 52.000, 21.010, 52.000),
    )
    private val legB = RouteFraming.fromGeoJson(
        line(21.010, 52.000, 21.010, 52.005),
        stops(21.010, 52.000, 21.010, 52.005),
    )

    private fun advancer() = LegAdvancer(legA, legB)

    @Test
    fun `does not switch mid-route or before arming`() {
        val a = advancer()
        // Mid-leg-A, far from the terminus: never fires, even sitting still for ages.
        repeat(10) { i ->
            assertFalse(a.onFix(52.000, 21.005, tMs = i * 60_000L))
        }
        // Even teleported onto leg B, without ever arming it must not fire.
        assertFalse(a.onFix(52.0015, 21.010, tMs = 999_000L))
    }

    @Test
    fun `switches after dwelling still at the terminus for over two minutes`() {
        val a = advancer()
        // Arrive at the terminus (distToEnd ~0) and sit there.
        assertFalse(a.onFix(52.000, 21.010, tMs = 0L))         // arm + start timer
        assertFalse(a.onFix(52.000, 21.010, tMs = 60_000L))    // 1 min
        assertFalse(a.onFix(52.000, 21.010, tMs = 119_000L))   // just under 2 min
        assertTrue(a.onFix(52.000, 21.010, tMs = 121_000L))    // past 2 min → switch
    }

    @Test
    fun `dwell timer resets if the bus keeps moving around`() {
        val a = advancer()
        a.onFix(52.000, 21.010, tMs = 0L)                       // park, start timer
        // Drift ~67 m (within the 75 m terminus radius but past the 60 m still-threshold)
        // → counts as moving, so the timer restarts.
        a.onFix(52.0006, 21.010, tMs = 60_000L)
        assertFalse(a.onFix(52.0006, 21.010, tMs = 150_000L))   // only 90 s since restart
    }

    @Test
    fun `switches once clearly driving onto the next leg`() {
        val a = advancer()
        a.onFix(52.000, 21.010, tMs = 0L)                       // arrive → arm
        // ~167 m north onto leg B: in its corridor, closer to it than to A, well past DEPART_PROGRESS.
        assertFalse(a.onFix(52.0015, 21.010, tMs = 5_000L))     // 1st qualifying fix (debounce)
        assertTrue(a.onFix(52.0015, 21.010, tMs = 6_000L))      // 2nd → switch
    }

    @Test
    fun `only ever the configured legs — sanity on the framing fixtures`() {
        // The fixtures themselves must locate sensibly (guards against a broken test setup).
        assertEquals(0.0, legA.locate(52.000, 21.010).distToEnd, 5.0, "at A's end → ~0 m left")
        assertTrue(legB.locate(52.0015, 21.010).arc > 120.0, "150 m up B → past DEPART_PROGRESS")
    }
}

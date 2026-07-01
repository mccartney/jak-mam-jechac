// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.ln

/**
 * Pure unit tests for the Two Steps View zoom maths ([RouteFraming.zoomForSpan]).
 *
 * No Android, no map, no resources — just the formula that turns a prev↔next gap
 * (in metres) into a zoom level. This is the bit that carried the dp-vs-physical
 * pixel bug, so it gets the most scrutiny.
 */
class ZoomForSpanTest {

    private val fraction = 0.75
    private val heightDp = 800.0

    private fun log2(x: Double) = ln(x) / ln(2.0)

    @Test
    fun `span that already fills the frame leaves zoom unchanged`() {
        // mppWanted = 600 / (0.75 * 800) = 1.0, equal to the current metres/pixel.
        val z = RouteFraming.zoomForSpan(15.0, 1.0, 600.0, heightDp, fraction)
        assertEquals(15.0, z, 1e-9)
    }

    @Test
    fun `doubling the span zooms out one level, halving zooms in one`() {
        val base = RouteFraming.zoomForSpan(15.0, 1.0, 600.0, heightDp, fraction)
        val wider = RouteFraming.zoomForSpan(15.0, 1.0, 1200.0, heightDp, fraction)
        val tighter = RouteFraming.zoomForSpan(15.0, 1.0, 300.0, heightDp, fraction)
        assertEquals(base - 1.0, wider, 1e-9)
        assertEquals(base + 1.0, tighter, 1e-9)
    }

    @Test
    fun `at the chosen zoom the span really occupies the requested fraction of height`() {
        // Solve the formula forwards, then check the implied geometry holds: the
        // metres-per-pixel at the resulting zoom must make the span = fraction * height.
        val zoom0 = 15.0
        val mppNow = 1.7
        val span = 540.0
        val z = RouteFraming.zoomForSpan(zoom0, mppNow, span, heightDp, fraction)
        val mppAtResult = mppNow * Math.pow(2.0, zoom0 - z) // MapLibre: mpp halves per zoom level
        assertEquals(span, mppAtResult * fraction * heightDp, 1e-6)
    }

    @Test
    fun `tiny spans clamp to the max street-level zoom`() {
        val z = RouteFraming.zoomForSpan(15.0, 1.0, 1.0, heightDp, fraction)
        assertEquals(18.0, z, 1e-9)
    }

    @Test
    fun `huge spans clamp to the min zoom`() {
        val z = RouteFraming.zoomForSpan(15.0, 1.0, 1_000_000.0, heightDp, fraction)
        assertEquals(11.0, z, 1e-9)
    }

    @Test
    fun `regression - feeding physical pixels instead of dp inflates zoom by log2(density)`() {
        // The fixed bug: View height is physical px, MapLibre's mpp is per dp pixel.
        // Passing the physical height (dp * density) makes the frame look that many
        // times shorter, pushing zoom up by exactly log2(density).
        val density = 2.75
        val correct = RouteFraming.zoomForSpan(15.0, 1.0, 600.0, heightDp, fraction)
        val buggy = RouteFraming.zoomForSpan(15.0, 1.0, 600.0, heightDp * density, fraction)
        assertEquals(log2(density), buggy - correct, 1e-9)
    }
}

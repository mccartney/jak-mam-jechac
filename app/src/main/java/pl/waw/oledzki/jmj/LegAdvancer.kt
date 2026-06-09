package pl.waw.oledzki.jmj

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Decides, from a stream of GPS fixes, when to hand off from the current leg to the
 * next one of a brigade's day.
 *
 * Termini are tricky: the bus lingers, and the return leg often retraces the outbound
 * path, so a single position is ambiguous. So we first *arm* once the bus reaches the
 * current leg's final stop, then switch when either of two things happens:
 *
 *  - **dwell** — it has sat still near the terminus for more than [DWELL_MS] (a layover), or
 *  - **departed** — it is clearly tracking the next leg's path and has driven a stretch
 *    onto it (i.e. already heading back the other way).
 *
 * A schedule guard rejects an implausibly-early *departed* switch (a later leg sweeping
 * back past this terminus); dwell needs no guard — you can't dwell while sweeping past.
 * Stateful but otherwise pure, so the logic is unit-tested directly.
 */
class LegAdvancer(
    private val current: RouteFraming,
    private val next: RouteFraming,
    private val nextDepartureMin: Int?,   // next leg's scheduled departure, or null to skip the guard
) {
    private var armed = false
    private var streak = 0
    private var dwellSince: Long? = null   // when we parked near the terminus (fix-clock millis)
    private var anchorLat = 0.0
    private var anchorLon = 0.0

    /**
     * Feed one fix. [nowMin] is minutes since midnight (schedule guard); [tMs] is the fix's
     * timestamp in millis (dwell timing). Returns true exactly when to advance to the next leg.
     */
    fun onFix(lat: Double, lon: Double, nowMin: Int, tMs: Long): Boolean {
        val cur = current.locate(lat, lon)

        // Dwell: still (within DWELL_RADIUS of where we parked) near the terminus long enough.
        if (cur.distToEnd <= ARRIVE_RADIUS) {
            armed = true
            if (dwellSince == null || distMeters(lat, lon, anchorLat, anchorLon) > DWELL_RADIUS) {
                dwellSince = tMs; anchorLat = lat; anchorLon = lon   // (re)start the still-timer
            }
        } else {
            dwellSince = null
        }
        val dwelled = dwellSince?.let { tMs - it >= DWELL_MS } ?: false

        // Departed: in the next leg's corridor, closer to it than to this leg, and well onto it.
        val nxt = next.locate(lat, lon)
        val onNext = armed &&
            nxt.distToPath < CORRIDOR &&
            nxt.distToPath < cur.distToPath &&
            nxt.arc >= DEPART_PROGRESS
        streak = if (onNext) streak + 1 else 0
        val departed = streak >= DEBOUNCE && scheduleOk(nowMin)

        return dwelled || departed
    }

    /** Allow a departed-switch only from a little before the next leg's scheduled departure. */
    private fun scheduleOk(nowMin: Int): Boolean {
        val dep = nextDepartureMin ?: return true
        // Normalise the gap into ±12h so after-midnight departures (hours >= 24) compare sanely.
        var gap = dep - nowMin
        if (gap > 720) gap -= 1440
        if (gap < -720) gap += 1440
        return gap <= SCHEDULE_SLACK_MIN
    }

    private fun distMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = (aLat - bLat) * M_PER_DEG_LAT
        val dLon = (aLon - bLon) * M_PER_DEG_LAT * cos(Math.toRadians(aLat))
        return hypot(dLat, dLon)
    }

    companion object {
        const val ARRIVE_RADIUS = 75.0       // m to the final stop that counts as "arrived"
        const val DWELL_RADIUS = 60.0        // m of drift still counted as "parked" (resets the timer)
        const val DWELL_MS = 120_000L        // sit this long near the terminus → switch (the >2 min rule)
        const val CORRIDOR = 40.0            // m off the next path that counts as "on it"
        const val DEPART_PROGRESS = 120.0    // m driven onto the next leg before we commit
        const val DEBOUNCE = 2               // consecutive qualifying fixes (kills GPS spikes)
        const val SCHEDULE_SLACK_MIN = 20    // may switch from this many minutes before departure
        private const val M_PER_DEG_LAT = 111_320.0
    }
}

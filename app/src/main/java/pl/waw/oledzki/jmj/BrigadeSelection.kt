package pl.waw.oledzki.jmj

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * ZTM surface-network day-type, as it appears in the mkuran feed's service_id
 * suffix. Derived from the driving date — the feed's own calendar resolves the
 * actual service (and handles holidays), this is just the human-readable code.
 */
enum class ServiceDay(val code: String) {
    PcS("PcS"),   // poniedziałek–czwartek (Mon–Thu)
    PtS("PtS"),   // piątek (Friday)
    SbS("SbS"),   // sobota (Saturday)
    NdS("NdS");   // niedziela / święta (Sunday & holidays)

    companion object {
        fun of(date: LocalDate): ServiceDay = when (date.dayOfWeek) {
            DayOfWeek.FRIDAY -> PtS
            DayOfWeek.SATURDAY -> SbS
            DayOfWeek.SUNDAY -> NdS
            else -> PcS
        }
    }
}

/** What the driver picks before navigating: a line's brigade on a given date. */
data class BrigadeSelection(
    val line: String,
    val brigade: String,
    val date: LocalDate,
)

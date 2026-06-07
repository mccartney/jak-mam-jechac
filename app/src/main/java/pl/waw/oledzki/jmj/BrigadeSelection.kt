package pl.waw.oledzki.jmj

/**
 * ZTM schedule day-type. The 504 rosters we verified confirmed exactly two
 * (work-day vs holiday); add an entry here if a line ever needs more.
 */
enum class ServiceDay(val code: String) {
    WEEKDAY("DP"),   // dzień powszedni
    HOLIDAY("DŚ"),   // dni świąteczne — Saturdays, Sundays & holidays
}

/** What the driver picks before navigating: a line's brigade on a given service-day. */
data class BrigadeSelection(
    val line: String,
    val brigade: String,
    val day: ServiceDay,
)

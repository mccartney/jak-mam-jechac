// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A leg's stop as the next-stop strip needs it; [minute] is from the service day's midnight. */
class LegStop(val name: String, val post: String?, val minute: Int?, val onRequest: Boolean)

/** The post number ("02" of Saska 02): the last two digits of a ZTM stop id; depots have none. */
internal fun postNumber(stopId: String): String? =
    stopId.takeLast(2).takeIf { stopId.length == 6 && stopId.all(Char::isDigit) }

/** What the strip above the map shows: the stop ahead and when it's due (null if untimed). */
data class NextStop(val name: String, val post: String?, val onRequest: Boolean, val due: LocalDateTime?)

// Beyond this the "deviation" means the driver is looking at another day's duty, not running late.
private const val MAX_DEVIATION_SEC = 60 * 60L

/**
 * Seconds ahead of (+) or behind (−) the timetable: the next stop's scheduled time [due]
 * minus [now]. Null when the stop has no time, or the gap is too large to be a real deviation.
 */
internal fun scheduleDeviation(due: LocalDateTime?, now: LocalDateTime): Long? =
    due?.let { ChronoUnit.SECONDS.between(now, it) }?.takeIf { abs(it) <= MAX_DEVIATION_SEC }

/** "+1:05" ahead, "−2:30" late, "0:00" on time. */
internal fun formatDeviation(sec: Long): String {
    val sign = when { sec > 0 -> "+"; sec < 0 -> "−"; else -> "" }
    val a = abs(sec)
    return "$sign${a / 60}:%02d".format(a % 60)
}

/** A slim strip above the map: next stop name, NŻ for a request stop, and the ± deviation. */
@Composable
fun NextStopBar(next: NextStop) {
    val dark = isSystemInDarkTheme()
    // Its own clock: the deviation ticks every second, not just when a GPS fix arrives.
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L - now.nano / 1_000_000)   // tick on the second
            now = LocalDateTime.now()
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A long name gets the "…", never the post number after it.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    next.name,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                next.post?.let { Text(it, style = MaterialTheme.typography.titleLarge, maxLines = 1) }
            }
            if (next.onRequest) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        stringResource(R.string.map_on_request),
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            scheduleDeviation(next.due, now)?.let { dev ->
                Text(
                    formatDeviation(dev),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = when {
                        dev > 0 -> if (dark) Color(0xFF66D98A) else Color(0xFF1B8A3A)
                        dev < 0 -> if (dark) Color(0xFFFF5252) else Color(0xFFB60000)
                        else -> Color.Unspecified
                    },
                )
            }
        }
    }
}

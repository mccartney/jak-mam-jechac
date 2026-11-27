// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Second step: the chosen brigade runs several legs across the day (depot pull-out,
 * revenue runs, pull-in). The driver taps the leg they're on; if the date is today,
 * the leg in progress (or next up) is highlighted as a hint from the current time.
 */
@Composable
fun SegmentSelectScreen(
    selection: BrigadeSelection,
    modifier: Modifier = Modifier,
    onConfirm: (index: Int, count: Int) -> Unit,
) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<LineData?>(null) }
    var chain by remember { mutableStateOf<List<Trip>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(selection) {
        loading = true; failed = false
        try {
            val d = fetchLine(context, selection.line)
            // null: the feed has no timetable on that date; empty: the brigade doesn't run then.
            chain = d.resolveServiceId(selection.date)?.let { d.services[it]?.get(selection.brigade).orEmpty() }
            data = d
        } catch (e: Exception) {
            failed = true
        }
        loading = false
    }

    val d = data
    val trips = chain
    when {
        loading -> Centered(modifier) { CircularProgressIndicator() }
        failed -> Centered(modifier) { Text(stringResource(R.string.segment_error)) }
        d != null && trips == null -> Centered(modifier) { Text(stringResource(R.string.segment_no_date)) }
        d == null || trips.isNullOrEmpty() -> Centered(modifier) { Text(stringResource(R.string.segment_empty)) }
        else -> SegmentList(selection, d, trips, modifier) { onConfirm(it, trips.size) }
    }
}

@Composable
private fun SegmentList(
    selection: BrigadeSelection,
    data: LineData,
    trips: List<Trip>,
    modifier: Modifier,
    onConfirm: (Int) -> Unit,
) {
    // Re-read the clock on every return to the app and each minute while it's shown: the screen
    // may sit backgrounded for hours, and a sleeping phone's delay() timer doesn't tick.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(nowIndex(trips, selection.date), trips, selection.date, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = nowIndex(trips, selection.date); delay(60_000) }
        }
    }
    fun stopName(id: String?): String = id?.let { data.stops[it]?.name } ?: "—"

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.select_segment), style = MaterialTheme.typography.titleLarge)
        Text(
            "${selection.line} / ${selection.brigade} · ${selection.date}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(trips) { i, trip ->
                SegmentRow(
                    trip = trip,
                    origin = stopName(trip.stops.firstOrNull()?.stopId),
                    destination = stopName(trip.stops.lastOrNull()?.stopId),
                    isNow = i == now,
                    onClick = { onConfirm(i) },
                )
            }
        }
    }
}

@Composable
private fun SegmentRow(
    trip: Trip,
    origin: String,
    destination: String,
    isNow: Boolean,
    onClick: () -> Unit,
) {
    // Depot moves only, by variant prefix. GTFS `exceptional` is no guide to "no passengers":
    // it also flags ordinary passenger runs on a non-standard variant (TX-…).
    val tag = when {
        trip.varCode.startsWith("TD") -> stringResource(R.string.segment_pullout)
        trip.varCode.startsWith("TZ") -> stringResource(R.string.segment_pullin)
        else -> null
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isNow) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Tag and "teraz" ride on the time row's right side rather than a line of their own.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${trip.departure ?: "--:--"} – ${trip.arrival ?: "--:--"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (tag != null) Text(
                    tag,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isNow) Text(
                    stringResource(R.string.segment_now),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Destination always on its own line, led by a drawn arrow: the "→" glyph sits at a
            // different height in every OEM font. The icon fills the first line's box, so it stays
            // level with that line even when a long name wraps.
            val style = MaterialTheme.typography.bodyLarge
            Text(origin, style = style)
            Row {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(with(LocalDensity.current) { style.lineHeight.toDp() }).padding(3.dp),
                )
                Text(destination, style = style)
            }
        }
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * The leg running "now" (highlighted and tagged "teraz"), or null when the current time says
 * nothing about [date]: the first leg not yet finished (arrival >= now), i.e. the one in
 * progress or the next up, of today's duty or of yesterday's while it's still running past
 * midnight (GTFS times go beyond 24:00, so 00:15 is its 24:15). Null once the duty is over.
 */
internal fun nowIndex(trips: List<Trip>, date: LocalDate, now: LocalDateTime = LocalDateTime.now()): Int? {
    val today = now.toLocalDate()
    if (date != today && date != today.minusDays(1)) return null
    val nowMin = ChronoUnit.MINUTES.between(date.atStartOfDay(), now)
    return trips.indexOfFirst { (it.arrival?.let(::hhmmToMinutes) ?: Int.MAX_VALUE) >= nowMin }.takeIf { it >= 0 }
}

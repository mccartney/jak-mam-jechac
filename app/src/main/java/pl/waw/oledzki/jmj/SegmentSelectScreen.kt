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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime

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
            chain = d.resolveServiceId(selection.date)?.let { d.services[it]?.get(selection.brigade) }.orEmpty()
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
    val suggested = remember(trips, selection.date) { suggestedIndex(trips, selection.date) }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(trips) { i, trip ->
                SegmentRow(
                    trip = trip,
                    origin = stopName(trip.stops.firstOrNull()?.stopId),
                    destination = stopName(trip.stops.lastOrNull()?.stopId),
                    isNow = i == suggested,
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
    val tag = when {
        trip.depot == "in" -> stringResource(R.string.segment_pullin)
        trip.depot != null -> stringResource(R.string.segment_pullout)
        !trip.rev -> stringResource(R.string.segment_nonrevenue)
        else -> null
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isNow) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${trip.departure ?: "--:--"} – ${trip.arrival ?: "--:--"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (isNow) Text(
                    stringResource(R.string.segment_now),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text("$origin → $destination", style = MaterialTheme.typography.bodyLarge)
            if (tag != null) Text(
                tag,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * The leg to pre-highlight, or -1 for none. Only today's date says anything: the first
 * leg not yet finished (arrival >= now) is the one in progress or the next up; else the
 * last. On any other date the clock is meaningless, and a highlight there reads as a
 * pre-selection the driver never made — so highlight nothing.
 */
private fun suggestedIndex(trips: List<Trip>, date: LocalDate): Int {
    if (date != LocalDate.now()) return -1
    val now = LocalTime.now().run { hour * 60 + minute }
    val i = trips.indexOfFirst { (it.arrival?.let(::hhmmToMinutes) ?: Int.MAX_VALUE) >= now }
    return if (i >= 0) i else trips.lastIndex
}

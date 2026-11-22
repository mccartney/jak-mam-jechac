// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Lets the driver pick date → line → brigade before the map opens. The date is the source
 * of truth (the feed's calendar gives its day-type, holidays included). Line and brigade
 * are chosen from the brigades.json index — a searchable line list, then tappable brigade chips — so there
 * is no error-prone free-text entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrigadeSelectScreen(
    modifier: Modifier = Modifier,
    onConfirm: (BrigadeSelection) -> Unit,
) {
    val context = LocalContext.current
    // Prefill from the last selection, but only while it's fresh (a shift's worth, 12h);
    // anything older has expired and the form starts blank.
    val saved = remember { loadSelection(context) }
    var date by rememberSaveable { mutableStateOf(saved?.date ?: LocalDate.now()) }
    var line by rememberSaveable { mutableStateOf(saved?.line.orEmpty()) }
    var brigade by rememberSaveable { mutableStateOf(saved?.brigade.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    // "zmień" stashes the line/brigade it cleared, so Back can undo it until another line is picked.
    var previous by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    BackHandler(enabled = previous != null) {
        previous?.let { (l, b) -> line = l; brigade = b }
        previous = null; query = ""
    }
    var showPicker by remember { mutableStateOf(false) }
    var showAttributions by remember { mutableStateOf(false) }

    // The line/brigade index, cached on disk (see fetchBrigades).
    var index by remember { mutableStateOf<BrigadeIndex?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            index = fetchBrigades(context)
        } catch (e: Exception) {
            failed = true
        }
    }

    // Pull down past the top of the list to re-download the index; the form is left as it is.
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    fun refresh() = scope.launch {
        refreshing = true
        failed = false
        try {
            index = fetchBrigades(context, force = true)
        } catch (e: Exception) {
            failed = true
        }
        refreshing = false
    }

    val day = index?.dayType(date) ?: ServiceDay.of(date).code
    val loaded = index?.lines
    val selected = loaded?.firstOrNull { it.line == line }
    val filtered = remember(loaded, query) {
        val all = loaded ?: return@remember emptyList<LineInfo>()
        val q = query.trim()
        (if (q.isEmpty()) all else all.filter { it.matches(q) }).sortedWith(lineOrder)
    }

    // The whole screen is one scrollable list inside the pull-to-refresh box, so pulling
    // down from the very top (over the date field) re-downloads the index.
    Column(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refresh() },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item("date") { DateField(date, day) { showPicker = true } }

                when {
                    loaded == null && !failed -> item("loading") {
                        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    loaded == null -> item("error") {
                        Text(stringResource(R.string.lines_error))
                    }
                    selected == null -> {
                        item("search") {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text(stringResource(R.string.select_line)) },
                                placeholder = { Text("linia") },
                                singleLine = true,
                                trailingIcon = {
                                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Clear, stringResource(R.string.clear_search))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        items(filtered, key = { it.line }) { info ->
                            LineRow(info, query.trim()) { line = info.line; brigade = ""; previous = null }
                        }
                    }
                    else -> {
                        fun confirm() {
                            val selection = BrigadeSelection(line, brigade, date)
                            saveSelection(context, selection)   // remembered for the next 12h
                            onConfirm(selection)
                        }
                        brigadeItems(
                            info = selected,
                            day = day,
                            brigade = brigade,
                            // Tapping a brigade completes the duty, so it moves straight on to the run picker;
                            // "Dalej" stays for confirming a prefilled one (or a changed date).
                            onBrigade = { brigade = it; confirm() },
                            onChangeLine = { previous = line to brigade; line = ""; brigade = "" },
                            onConfirm = ::confirm,
                        )
                    }
                }
            }
        }

        // Required data/map credits, reachable from the very first screen: pinned under the
        // list and muted, so they're always there but don't compete with the form.
        TextButton(
            onClick = { showAttributions = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.attributions_link), style = MaterialTheme.typography.labelMedium)
        }
    }

    if (showAttributions) {
        AttributionsDialog(feedVersion = index?.feedVersion) { showAttributions = false }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = remember(index) { feedDates(index) },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // The picker reports UTC midnight; read it back in UTC to avoid an off-by-one day.
                    pickerState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showPicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) { DatePicker(state = pickerState) }
    }
}

/** Only the dates the feed has a timetable for; any date until the index has loaded. */
@OptIn(ExperimentalMaterial3Api::class)
private fun feedDates(index: BrigadeIndex?): SelectableDates {
    val days = index?.days?.takeIf { it.isNotEmpty() } ?: return object : SelectableDates {}
    val years = days.keys.mapNotNullTo(HashSet()) { it.take(4).toIntOrNull() }
    return object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) =
            Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().toString() in days
        override fun isSelectableYear(year: Int) = year in years
    }
}

/**
 * Read-only date field, with the date's day-type (the feed's, else the weekday rule) as a static suffix.
 * The overlay box opens the picker (a click is otherwise swallowed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: LocalDate, day: String, onClick: () -> Unit) {
    Box {
        OutlinedTextField(
            value = date.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.select_date)) },
            suffix = { Text(day) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

@Composable
private fun LineRow(info: LineInfo, query: String, onClick: () -> Unit) {
    val hit = if (query.isEmpty()) null else info.match(query)
    // A faint tint behind the matched letters.
    val style = SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(highlight(info.line, hit?.line, style), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(highlight(info.name, hit?.name, style), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** Chosen line (tap to change), the day's brigades as chips, then the confirm button. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.brigadeItems(
    info: LineInfo,
    day: String,
    brigade: String,
    onBrigade: (String) -> Unit,
    onChangeLine: () -> Unit,
    onConfirm: () -> Unit,
) {
    val brigades = info.brigades(day).sortedWith(brigadeOrder)
    item("chosen") {
        Card(Modifier.fillMaxWidth().clickable(onClick = onChangeLine)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(info.line, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(info.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.change_line),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    item("brigades") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.select_brigade), style = MaterialTheme.typography.titleMedium)
            if (brigades.isEmpty()) {
                Text(stringResource(R.string.no_brigades), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // 48dp chips fill the touch target FilterChip reserves anyway, so rows don't
                // carry invisible padding on top of the gap.
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    brigades.forEach { b ->
                        FilterChip(
                            selected = b == brigade,
                            onClick = { onBrigade(b) },
                            label = { Text(b, style = MaterialTheme.typography.titleMedium) },
                            modifier = Modifier.height(48.dp),
                        )
                    }
                }
            }
        }
    }
    item("confirm") {
        Button(
            onClick = onConfirm,
            enabled = brigade in brigades,
            modifier = Modifier.fillMaxWidth().height(80.dp),
        ) { Text(stringResource(R.string.select_confirm), style = MaterialTheme.typography.headlineMedium) }
    }
}

// Lines and brigades: real numbers first in numeric order (1, 2, … 10, …), then any
// alphanumeric ids (e.g. "M1", zero-padded depot brigades) after, alphabetically.
internal val lineOrder =
    compareBy<LineInfo>({ it.line.toIntOrNull() == null }, { it.line.toIntOrNull() ?: 0 }, { it.line })
// Search folds case and Polish letters ("bialo" finds "Biało"), char for char, so indices
// in the folded text are indices in the original (used to highlight the hit).
private const val PL = "ąćęłńóśźż"
private const val ASCII = "acelnoszz"
private fun Char.fold() = lowercaseChar().let { c -> PL.indexOf(c).let { if (it < 0) c else ASCII[it] } }
private fun String.fold() = String(CharArray(length) { this[it].fold() })
private fun Char.isGap() = this == '-' || isWhitespace()
private fun String.bare() = filterNot { it.isGap() }

/** Where a query hit: the matched part of the line number and/or of the name. */
internal data class LineMatch(val line: IntRange?, val name: IntRange?)

/**
 * Line numbers match by prefix ignoring dashes and spaces, so "l3" or "l 3" finds "L-3";
 * names match by substring. Null when neither matches.
 */
internal fun LineInfo.match(query: String): LineMatch? {
    val q = query.fold()
    val bare = q.bare()
    val lineHit = if (bare.isNotEmpty() && line.fold().bare().startsWith(bare)) {
        var n = 0   // the hit runs up to the original char that completes the bare prefix
        0..line.indexOfFirst { !it.isGap() && ++n == bare.length }
    } else null
    val at = name.fold().indexOf(q)
    val nameHit = if (at >= 0) at until at + q.length else null
    return if (lineHit == null && nameHit == null) null else LineMatch(lineHit, nameHit)
}
internal fun LineInfo.matches(query: String) = match(query) != null

/** [text] with [hit] (if any) given [style]. */
private fun highlight(text: String, hit: IntRange?, style: SpanStyle) = buildAnnotatedString {
    append(text)
    if (hit != null && !hit.isEmpty()) addStyle(style, hit.first, hit.last + 1)
}
internal val brigadeOrder =
    compareBy<String>({ it.toIntOrNull() == null }, { it.toIntOrNull() ?: 0 }, { it })

/** The last confirmed selection, persisted so the form survives restarts within a shift. */
private const val SELECTION_PREFS = "sluzba"
private const val KEY_DATE = "date"
private const val KEY_LINE = "line"
private const val KEY_BRIGADE = "brigade"
private const val KEY_SAVED_AT = "savedAt"
private const val REMEMBER_MS = 12L * 60 * 60 * 1000   // 12h: roughly one shift

private fun saveSelection(context: Context, selection: BrigadeSelection) {
    context.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_DATE, selection.date.toString())
        .putString(KEY_LINE, selection.line)
        .putString(KEY_BRIGADE, selection.brigade)
        .putLong(KEY_SAVED_AT, System.currentTimeMillis())
        .apply()
}

/** The persisted selection, or null if there is none or it's older than [REMEMBER_MS]. */
private fun loadSelection(context: Context): BrigadeSelection? {
    val prefs = context.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE)
    val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
    if (!isRemembered(savedAt, System.currentTimeMillis())) return null
    val date = prefs.getString(KEY_DATE, null) ?: return null
    return BrigadeSelection(
        line = prefs.getString(KEY_LINE, "").orEmpty(),
        brigade = prefs.getString(KEY_BRIGADE, "").orEmpty(),
        date = LocalDate.parse(date),
    )
}

/** Whether a selection saved at [savedAt] (epoch ms, 0 = never) is still within [REMEMBER_MS] of [now]. */
internal fun isRemembered(savedAt: Long, now: Long): Boolean = savedAt != 0L && now - savedAt <= REMEMBER_MS

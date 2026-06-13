package pl.waw.oledzki.jmj

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Lets the driver pick date → line → brigade before the map opens. The date is the source
 * of truth (the day-type [ServiceDay] is derived from it). Line and brigade are chosen from
 * the brigades.json index — a searchable line list, then tappable brigade chips — so there
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
    var showPicker by remember { mutableStateOf(false) }

    // The line/brigade index, cached on disk (see fetchBrigades).
    var index by remember { mutableStateOf<List<LineInfo>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            index = fetchBrigades(context)
        } catch (e: Exception) {
            failed = true
        }
    }

    val day = ServiceDay.of(date)
    val loaded = index
    val selected = loaded?.firstOrNull { it.line == line }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DateField(date) { showPicker = true }
        ServiceDayRow(day)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loaded == null && !failed -> Centered { CircularProgressIndicator() }
                loaded == null -> Centered { Text(stringResource(R.string.lines_error)) }
                selected == null -> LinePicker(loaded, query, onQuery = { query = it }) { picked ->
                    line = picked.line; brigade = ""
                }
                else -> BrigadePicker(
                    info = selected,
                    day = day,
                    brigade = brigade,
                    onBrigade = { brigade = it },
                    onChangeLine = { line = ""; brigade = "" },
                ) {
                    val selection = BrigadeSelection(line, brigade, date)
                    saveSelection(context, selection)   // remembered for the next 12h
                    onConfirm(selection)
                }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
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

/** Read-only date field; the overlay box opens the picker (a click is otherwise swallowed). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: LocalDate, onClick: () -> Unit) {
    Box {
        OutlinedTextField(
            value = date.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.select_date)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

/** The day-type, derived from the date above (informational). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceDayRow(day: ServiceDay) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ServiceDay.entries.forEachIndexed { index, sd ->
            SegmentedButton(
                selected = day == sd,
                onClick = {},
                shape = SegmentedButtonDefaults.itemShape(index, ServiceDay.entries.size),
            ) { Text(sd.code) }
        }
    }
}

/** Search box + scrollable list of lines (number, name, tram/bus badge). */
@Composable
private fun LinePicker(
    lines: List<LineInfo>,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (LineInfo) -> Unit,
) {
    val filtered = remember(lines, query) {
        val q = query.trim()
        (if (q.isEmpty()) lines
        else lines.filter { it.line.startsWith(q, true) || it.name.contains(q, true) })
            .sortedWith(lineOrder)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(stringResource(R.string.select_line)) },
            placeholder = { Text("linia") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.line }) { info -> LineRow(info) { onPick(info) } }
        }
    }
}

@Composable
private fun LineRow(info: LineInfo, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(info.line, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(info.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** Chosen line (tap to change) + the day's brigades as chips, then the confirm button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrigadePicker(
    info: LineInfo,
    day: ServiceDay,
    brigade: String,
    onBrigade: (String) -> Unit,
    onChangeLine: () -> Unit,
    onConfirm: () -> Unit,
) {
    val brigades = remember(info, day) { info.brigades(day).sortedWith(brigadeOrder) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth().clickable(onClick = onChangeLine)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(info.line, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(info.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.change_line),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(stringResource(R.string.select_brigade), style = MaterialTheme.typography.titleMedium)
        if (brigades.isEmpty()) {
            Text(stringResource(R.string.no_brigades), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                brigades.forEach { b ->
                    FilterChip(selected = b == brigade, onClick = { onBrigade(b) }, label = { Text(b) })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onConfirm,
            enabled = brigade in brigades,
            modifier = Modifier.fillMaxWidth().height(80.dp),
        ) { Text(stringResource(R.string.select_confirm)) }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// Lines and brigades: real numbers first in numeric order (1, 2, … 10, …), then any
// alphanumeric ids (e.g. "M1", zero-padded depot brigades) after, alphabetically.
private val lineOrder =
    compareBy<LineInfo>({ it.line.toIntOrNull() == null }, { it.line.toIntOrNull() ?: 0 }, { it.line })
private val brigadeOrder =
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
    if (savedAt == 0L || System.currentTimeMillis() - savedAt > REMEMBER_MS) return null
    val date = prefs.getString(KEY_DATE, null) ?: return null
    return BrigadeSelection(
        line = prefs.getString(KEY_LINE, "").orEmpty(),
        brigade = prefs.getString(KEY_BRIGADE, "").orEmpty(),
        date = LocalDate.parse(date),
    )
}

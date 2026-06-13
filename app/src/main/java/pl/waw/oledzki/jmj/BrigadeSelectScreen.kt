package pl.waw.oledzki.jmj

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Lets the driver pick date → line → brigade before the map opens. The date is
 * the source of truth: the day-type ([ServiceDay]) is derived from it.
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
    var showPicker by remember { mutableStateOf(false) }
    val day = ServiceDay.of(date)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Read-only field; the overlay box opens the picker (a click would otherwise
        // be swallowed by the text field itself).
        Box {
            OutlinedTextField(
                value = date.toString(),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.select_date)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clickable { showPicker = true })
        }

        OutlinedTextField(
            value = line,
            onValueChange = { line = it.trim() },
            label = { Text(stringResource(R.string.select_line)) },
            placeholder = { Text("linia") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = brigade,
            // Brigades are mostly digits but some carry letters (e.g. "M6"); keep
            // alphanumerics only and upper-case them.
            onValueChange = { brigade = it.filter(Char::isLetterOrDigit).uppercase() },
            label = { Text(stringResource(R.string.select_brigade)) },
            placeholder = { Text("brygada") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        // Day-type, derived from the date above (informational).
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ServiceDay.entries.forEachIndexed { index, sd ->
                SegmentedButton(
                    selected = day == sd,
                    onClick = {},
                    shape = SegmentedButtonDefaults.itemShape(index, ServiceDay.entries.size),
                ) { Text(sd.code) }
            }
        }

        Button(
            onClick = {
                val selection = BrigadeSelection(line, brigade, date)
                saveSelection(context, selection)   // remembered for the next 12h
                onConfirm(selection)
            },
            enabled = line.isNotBlank() && brigade.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
        ) { Text(stringResource(R.string.select_confirm)) }
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

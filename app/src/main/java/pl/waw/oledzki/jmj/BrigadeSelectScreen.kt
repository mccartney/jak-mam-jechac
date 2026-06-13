package pl.waw.oledzki.jmj

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    var date by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var line by rememberSaveable { mutableStateOf("") }
    var brigade by rememberSaveable { mutableStateOf("") }
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

        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onConfirm(BrigadeSelection(line, brigade, date)) },
            enabled = line.isNotBlank() && brigade.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
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

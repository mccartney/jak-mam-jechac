package pl.waw.oledzki.jmj

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * Lets the driver pick line → brigade → day before the map opens.
 * Exactly three inputs, in that order; the day is one of [ServiceDay].
 */
@Composable
fun BrigadeSelectScreen(
    modifier: Modifier = Modifier,
    onConfirm: (BrigadeSelection) -> Unit,
) {
    // Prefilled with the one trip the map currently hard-codes, so the screen is
    // usable out of the box; the driver can overwrite either field.
    var line by rememberSaveable { mutableStateOf("504") }
    var brigade by rememberSaveable { mutableStateOf("1") }
    var dayOrdinal by rememberSaveable { mutableStateOf(ServiceDay.WEEKDAY.ordinal) }
    val day = ServiceDay.entries[dayOrdinal]

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = line,
            onValueChange = { line = it.trim() },
            label = { Text(stringResource(R.string.select_line)) },
            placeholder = { Text("504") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = brigade,
            // Brigades are mostly digits but some carry letters (e.g. "M6"); keep
            // alphanumerics only and upper-case them.
            onValueChange = { brigade = it.filter(Char::isLetterOrDigit).uppercase() },
            label = { Text(stringResource(R.string.select_brigade)) },
            placeholder = { Text("01") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.select_day), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ServiceDay.entries.forEachIndexed { index, sd ->
                SegmentedButton(
                    selected = day == sd,
                    onClick = { dayOrdinal = index },
                    shape = SegmentedButtonDefaults.itemShape(index, ServiceDay.entries.size),
                ) { Text(sd.code) }
            }
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onConfirm(BrigadeSelection(line, brigade, day)) },
            enabled = line.isNotBlank() && brigade.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.select_confirm)) }
    }
}

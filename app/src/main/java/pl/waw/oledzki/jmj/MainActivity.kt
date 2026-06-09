package pl.waw.oledzki.jmj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** The three always-visible destinations: duty → run → map. */
private enum class Dest(val labelRes: Int) {
    SLUZBA(R.string.tab_sluzba),
    KURS(R.string.tab_kurs),
    MAPA(R.string.tab_mapa),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    var dest by remember { mutableStateOf(Dest.SLUZBA) }
                    var selection by remember { mutableStateOf<BrigadeSelection?>(null) }
                    // The active run and the chain size, shown on the Kurs tab as "N/M".
                    // activeLeg auto-advances from the map at each terminus.
                    var activeLeg by remember { mutableStateOf<Int?>(null) }
                    var legCount by remember { mutableStateOf<Int?>(null) }

                    Column(Modifier.padding(padding)) {
                        val leg = activeLeg
                        val count = legCount
                        TabRow(selectedTabIndex = dest.ordinal) {
                            Dest.entries.forEach { d ->
                                val label = if (d == Dest.KURS && leg != null && count != null)
                                    "${stringResource(d.labelRes)} ${leg + 1}/$count"
                                else stringResource(d.labelRes)
                                Tab(
                                    selected = dest == d,
                                    onClick = { dest = d },
                                    text = { Text(label) },
                                )
                            }
                        }
                        Box(Modifier.fillMaxSize()) {
                            when (dest) {
                                // Picking a duty resets the run and jumps to the run picker.
                                Dest.SLUZBA -> BrigadeSelectScreen {
                                    selection = it; activeLeg = null; legCount = null; dest = Dest.KURS
                                }
                                Dest.KURS -> selection?.let { sel ->
                                    SegmentSelectScreen(sel) { index, total ->
                                        activeLeg = index; legCount = total; dest = Dest.MAPA
                                    }
                                } ?: Hint(R.string.hint_pick_sluzba)
                                Dest.MAPA -> {
                                    val sel = selection
                                    val cur = activeLeg
                                    if (sel != null && cur != null)
                                        MapScreen(sel, cur, onLegChange = { activeLeg = it })
                                    else Hint(R.string.hint_pick_kurs)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(textRes: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyLarge)
    }
}

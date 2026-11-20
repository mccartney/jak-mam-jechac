// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The three always-visible destinations: duty → run → map. */
private enum class Dest(val labelRes: Int) {
    SLUZBA(R.string.tab_sluzba),
    KURS(R.string.tab_kurs),
    MAPA(R.string.tab_mapa),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is mandatory from targetSdk 35; Scaffold pads for the bars and, in landscape, the
        // camera cutout (safeDrawing; its default systemBars leaves the cutout uncovered). The UI is always
        // light (no dark scheme), so pin dark bar icons rather than following the system theme.
        val light = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = light, navigationBarStyle = light)
        setContent {
            MaterialTheme {
                Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                    // Saveable, so a rotation or the system killing the app mid-route doesn't
                    // drop the driver back on the first tab with the duty and run forgotten.
                    var dest by rememberSaveable { mutableStateOf(Dest.SLUZBA) }
                    var selection by rememberSaveable { mutableStateOf<BrigadeSelection?>(null) }
                    // The active run and the chain size, shown on the Kurs tab as "N/M".
                    // activeLeg auto-advances from the map at each terminus.
                    var activeLeg by rememberSaveable { mutableStateOf<Int?>(null) }
                    var legCount by rememberSaveable { mutableStateOf<Int?>(null) }
                    // Back walks the flow leftwards (map → run → duty); only exits from the first tab.
                    BackHandler(enabled = dest != Dest.SLUZBA) { dest = Dest.entries[dest.ordinal - 1] }

                    Column(Modifier.padding(padding)) {
                        UpdateBanner()
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
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, Modifier.width(140.dp))
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyLarge)
    }
}

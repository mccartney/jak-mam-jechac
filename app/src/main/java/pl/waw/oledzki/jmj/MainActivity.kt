package pl.waw.oledzki.jmj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    // Flow: pick brigade -> pick which leg of its day -> map.
                    var selection by remember { mutableStateOf<BrigadeSelection?>(null) }
                    var segment by remember { mutableStateOf<Int?>(null) }
                    val sel = selection
                    val seg = segment
                    when {
                        sel == null ->
                            BrigadeSelectScreen(Modifier.padding(padding)) { selection = it }
                        seg == null -> {
                            // Back returns to brigade selection instead of leaving the app.
                            BackHandler { selection = null }
                            SegmentSelectScreen(sel, Modifier.padding(padding)) { segment = it }
                        }
                        else -> {
                            // Back returns to the leg picker.
                            BackHandler { segment = null }
                            MapScreen(sel, seg, Modifier.padding(padding))
                        }
                    }
                }
            }
        }
    }
}

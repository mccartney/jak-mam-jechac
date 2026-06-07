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
                    // Pick a brigade first; once chosen, show the map. (The map still
                    // draws the hard-coded 504 until the data layer is wired in.)
                    var selection by remember { mutableStateOf<BrigadeSelection?>(null) }
                    if (selection == null) {
                        BrigadeSelectScreen(Modifier.padding(padding)) { selection = it }
                    } else {
                        // Back returns to brigade selection instead of leaving the app.
                        BackHandler { selection = null }
                        MapScreen(Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

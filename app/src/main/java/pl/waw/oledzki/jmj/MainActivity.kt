package pl.waw.oledzki.jmj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // MaterialTheme with no arguments gives us Material 3's default
            // color scheme and typography for free.
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    HomeScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Jak mam jechać?", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Nawigacja dla kierowcy autobusu miejskiego w Warszawie",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

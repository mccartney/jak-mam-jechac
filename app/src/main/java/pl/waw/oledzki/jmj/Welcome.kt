// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

private const val WELCOME_PREFS = "welcome"
private const val KEY_SEEN_VERSION = "seenVersion"

/**
 * Bump to show the screen once more to everyone, existing installs included (e.g. with
 * "what's new in 1.0" text swapped in). Each install remembers the highest version it dismissed.
 */
private const val WELCOME_VERSION = 1
private const val CONTACT_EMAIL = "jakmamjechac@a.436.pl"

/**
 * Shows [WelcomeScreen] instead of [content] until this install has dismissed [WELCOME_VERSION].
 * Leaving the app without tapping through shows it again next time.
 */
@Composable
fun WelcomeGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(WELCOME_PREFS, Context.MODE_PRIVATE) }
    var seen by remember { mutableStateOf(prefs.getInt(KEY_SEEN_VERSION, 0) >= WELCOME_VERSION) }
    if (seen) content() else WelcomeScreen { prefs.edit { putInt(KEY_SEEN_VERSION, WELCOME_VERSION) }; seen = true }
}

/** What the app is for and the Służba → Kurs → Mapa flow, with the start button pinned below. */
@Composable
private fun WelcomeScreen(onDone: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painterResource(R.drawable.ic_logo), contentDescription = null,
                Modifier.width(140.dp).align(Alignment.CenterHorizontally),
            )
            Text(stringResource(R.string.welcome_intro), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.welcome_how), style = MaterialTheme.typography.titleMedium)
            Step(R.drawable.ic_tab_badge, R.string.tab_sluzba, R.string.welcome_step_sluzba)
            Step(R.drawable.ic_tab_route, R.string.tab_kurs, R.string.welcome_step_kurs)
            Step(R.drawable.ic_tab_navigation, R.string.tab_mapa, R.string.welcome_step_mapa)
            Text(stringResource(R.string.welcome_once), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.welcome_about),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                CONTACT_EMAIL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                // No mail app installed makes openUri throw; the address stays readable anyway.
                modifier = Modifier.clickable { runCatching { uri.openUri("mailto:$CONTACT_EMAIL") } },
            )
        }
        Button(onClick = onDone, Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(stringResource(R.string.welcome_start))
        }
    }
}

/** One tab of the flow: its icon and name (as on the tab row), then what to do there. */
@Composable
private fun Step(iconRes: Int, titleRes: Int, textRes: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.installStatus
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.requestUpdateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Google Play's flexible in-app update, as a strip above the tabs: offer → download in the background
 * while the app keeps working → offer a restart. Nothing is forced; the restart only happens on tap,
 * so a driver mid-route is never interrupted. Shows nothing for installs not from Play (debug, sideload).
 */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val manager = remember { AppUpdateManagerFactory.create(context) }
    val update by produceState<AppUpdateResult>(AppUpdateResult.NotAvailable) {
        manager.requestUpdateFlow().catch {}.collect { value = it }
    }
    // "No thanks" in Play's dialog hides the offer until the next app start.
    var declined by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(StartIntentSenderForResult()) {
        if (it.resultCode != Activity.RESULT_OK) declined = true
    }
    val scope = rememberCoroutineScope()

    when (val u = update) {
        is AppUpdateResult.Available -> when {
            // Reopened mid-download: the flow starts as Available until the next progress event.
            u.updateInfo.installStatus.isDownloading() -> Strip(R.string.update_downloading)
            !declined && u.updateInfo.isFlexibleUpdateAllowed ->
                Strip(R.string.update_available, R.string.update_start) { u.startFlexibleUpdate(launcher) }
        }
        // Also reports canceled/failed installs, which should just make the strip go away.
        is AppUpdateResult.InProgress ->
            if (u.installState.installStatus().isDownloading()) Strip(R.string.update_downloading)
        is AppUpdateResult.Downloaded ->
            Strip(R.string.update_ready, R.string.update_restart) { scope.launch { runCatching { u.completeUpdate() } } }
        else -> {}
    }
}

private fun Int.isDownloading() = this == InstallStatus.PENDING || this == InstallStatus.DOWNLOADING

@Composable
private fun Strip(textRes: Int, actionRes: Int? = null, onAction: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(textRes), Modifier.weight(1f))
            if (actionRes != null) TextButton(onClick = onAction) { Text(stringResource(actionRes)) }
        }
    }
}

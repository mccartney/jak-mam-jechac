// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The newest published release, as written to S3 by the release workflow. */
data class Release(val versionCode: Int, val versionName: String)

/** The latest release, or null when offline or the file is missing — never worth an error. */
suspend fun fetchLatestRelease(): Release? = withContext(Dispatchers.IO) {
    runCatching {
        val o = JSONObject(download("$DATA_BASE_URL/app/version.json").decodeToString())
        Release(o.getInt("versionCode"), o.getString("versionName"))
    }.getOrNull()
}

/**
 * A strip noting that a newer release than this build exists; nothing otherwise. Informational only:
 * a Play-distributed app may not offer an update from anywhere but Play.
 */
@Composable
fun UpdateBanner() {
    val latest by produceState<Release?>(null) { value = fetchLatestRelease() }
    val release = latest?.takeIf { it.versionCode > BuildConfig.VERSION_CODE } ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.update_available, release.versionName),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

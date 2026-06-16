package pl.waw.oledzki.jmj

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * One credit we must display. The transit credits mirror the feed's own attributions.txt —
 * ZTM Warszawa (the authority), Mikołaj Kuranowski (who builds the GTFS) and OpenStreetMap
 * (the bus shapes, under ODbL). The basemap credits are the ones OpenFreeMap requires
 * ("© OpenMapTiles · Data from OpenStreetMap"; OpenFreeMap itself is optional but included).
 * The City Hall / api.um.warszawa.pl credit is intentionally absent: it applies to the
 * realtime vehicle positions, which this app does not use. The schedule's feed version is
 * filled in live from the loaded data.
 */
private data class Credit(val label: String, val url: String)

private val TRANSIT_CREDITS = listOf(
    Credit("Zarząd Transportu Miejskiego w Warszawie", "https://ztm.waw.pl"),
    Credit("GTFS: Mikołaj Kuranowski", "https://mkuran.pl/gtfs/"),
    Credit("Kształty tras: © OpenStreetMap (ODbL)", "https://www.openstreetmap.org/copyright"),
)
private val BASEMAP_CREDITS = listOf(
    Credit("© OpenMapTiles", "https://www.openmaptiles.org/"),
    Credit("Dane © OpenStreetMap (ODbL)", "https://www.openstreetmap.org/copyright"),
    Credit("OpenFreeMap", "https://openfreemap.org"),
)

/** Small tappable "©" badge to overlay on the map; opens [AttributionsDialog]. */
@Composable
fun AttributionBadge(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Text(
            "©",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Lists every required data/map credit, with the live schedule [feedVersion] when known. */
@Composable
fun AttributionsDialog(feedVersion: String?, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        title = { Text(stringResource(R.string.attributions_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Section(R.string.attributions_transit, TRANSIT_CREDITS, uri)
                feedVersion?.let {
                    Text(
                        stringResource(R.string.attributions_feed_version, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Section(R.string.attributions_basemap, BASEMAP_CREDITS, uri)
            }
        },
    )
}

/** A titled group of tappable credit lines (each opens its URL in the browser). */
@Composable
private fun Section(titleRes: Int, credits: List<Credit>, uri: UriHandler) {
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    credits.forEach { c ->
        Text(
            c.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { uri.openUri(c.url) }.padding(vertical = 2.dp),
        )
    }
}

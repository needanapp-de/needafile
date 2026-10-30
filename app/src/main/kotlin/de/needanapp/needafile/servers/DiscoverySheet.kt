package de.needanapp.needafile.servers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.CardSpacing
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.TileCard
import de.needanapp.needafile.R
import de.needanapp.needafile.discovery.DiscoveredServer
import de.needanapp.needafile.discovery.ServerDiscovery
import de.needanapp.needafile.remote.SERVER_TYPE_WEBDAV
import kotlinx.coroutines.delay

/** Looks for servers on the local network while open; picking one hands it to [onPick]. */
@Composable
fun DiscoverySheet(onPick: (DiscoveredServer) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<DiscoveredServer>() }
    val discovery = remember { ServerDiscovery(context) { found.add(it) } }
    var round by remember { mutableIntStateOf(0) }
    var isSearching by remember { mutableStateOf(true) }

    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    // mDNS keeps listening; the progress shows while most answers usually come in
    LaunchedEffect(round) {
        isSearching = true
        delay(SEARCH_DURATION_MS)
        isSearching = false
    }

    OptionsSheet(onDismiss, title = stringResource(R.string.discovery_title)) {
        if (isSearching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).clip(CircleShape))
        }
        if (found.isEmpty() && !isSearching) {
            Text(
                stringResource(R.string.discovery_nothing_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        TileCard(null, CardSpacing) {
            found.sortedWith(compareBy({ it.name.lowercase() }, { it.type })).forEach { server ->
                val address = "${server.host}:${server.port}" + server.path.takeIf { server.type == SERVER_TYPE_WEBDAV }.orEmpty()
                val summary = stringResource(R.string.discovery_server_summary, stringResource(protocolLabel(server.type)), address)
                line({ m, shape -> ActionTile(server.name, { onPick(server) }, shape, m, protocolIcon(server.type), summary = summary, inline = true) })
            }
            line({ m, shape ->
                ActionTile(
                    stringResource(R.string.discovery_search_again),
                    {
                        discovery.searchWindowsComputers()
                        round++
                    },
                    shape,
                    m,
                    Icons.Outlined.Refresh,
                    inline = true,
                    enabled = !isSearching,
                )
            })
        }
    }
}

private const val SEARCH_DURATION_MS = 6000L

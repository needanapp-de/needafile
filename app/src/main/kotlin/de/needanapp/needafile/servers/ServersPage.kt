package de.needanapp.needafile.servers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.BottomPillSpacer
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.TileCard
import de.needanapp.design.components.scrollbar
import de.needanapp.needafile.R
import de.needanapp.needafile.exchange.rememberNeedaloadImport
import de.needanapp.needafile.remote.RemoteServer
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.remote.SERVER_TYPE_FTP
import de.needanapp.needafile.remote.SERVER_TYPE_SMB
import de.needanapp.needafile.remote.SERVER_TYPE_WEBDAV

/**
 * The saved server connections as status cards: name, protocol and address, below them open and edit; a tap on the
 * card browses the server in the files page, the info button shows the server as a card floating in place of the pill. Below them, connections can be taken over from needaload when it is
 * installed; [onServersAdded] then shows the new ones.
 */
@Composable
fun ServersPage(
    version: Int,
    contentPadding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    onOpen: (RemoteServer) -> Unit,
    onEdit: (RemoteServer) -> Unit,
    onInfo: (RemoteServer) -> Unit,
    onAdd: () -> Unit,
    onServersAdded: () -> Unit,
) {
    val context = LocalContext.current
    val servers = remember(version) { RemoteServersStore.getServers(context) }
    val takeOverFromNeedaload = rememberNeedaloadImport(onImported = onServersAdded)
    val cardPadding = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize().scrollbar(listState, contentPadding).nestedScroll(nestedScrollConnection),
    ) {
        if (servers.isEmpty()) {
            item {
                EmptyState(
                    Icons.Outlined.Dns,
                    stringResource(R.string.servers_empty_title),
                    message = stringResource(R.string.servers_empty_placeholder),
                    action = { Button(onClick = onAdd) { Text(stringResource(R.string.add_server)) } },
                )
            }
        }
        servers.forEach { server ->
            item(key = server.id) {
                TileCard(null, cardPadding) {
                    line({ m, shape ->
                        ActionTile(
                            server.getDisplayName(),
                            { onOpen(server) },
                            shape,
                            m,
                            protocolIcon(server.type),
                            summary = stringResource(R.string.server_protocol_and_address, stringResource(protocolLabel(server.type)), server.getSummary()),
                            inline = true,
                            trailing = {
                                IconButton(onClick = { onInfo(server) }) {
                                    Icon(Icons.Outlined.Info, stringResource(R.string.server_options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                    })
                    line(
                        { m, shape ->
                            ActionTile(stringResource(R.string.open_server), { onOpen(server) }, shape, m, Icons.Outlined.FolderOpen, centered = true, inline = true, iconInCircle = false)
                        },
                        { m, shape ->
                            ActionTile(stringResource(R.string.edit), { onEdit(server) }, shape, m, Icons.Outlined.Edit, centered = true, inline = true, iconInCircle = false)
                        },
                    )
                }
            }
        }
        takeOverFromNeedaload?.let { start ->
            item {
                TileCard(null, cardPadding) {
                    line({ m, shape ->
                        ActionTile(
                            stringResource(R.string.needaload_import),
                            start,
                            shape,
                            m,
                            Icons.Outlined.Download,
                            summary = stringResource(R.string.needaload_import_summary),
                            inline = true,
                        )
                    })
                }
            }
        }
        item { BottomPillSpacer() }
    }
}

/** Each protocol's own icon: a terminal for SFTP, a network for SMB, a cloud for WebDAV, up and down for FTP. */
internal fun protocolIcon(type: String) = when (type) {
    SERVER_TYPE_SMB -> Icons.Outlined.Lan
    SERVER_TYPE_WEBDAV -> Icons.Outlined.Cloud
    SERVER_TYPE_FTP -> Icons.Outlined.SwapVert
    else -> Icons.Outlined.Terminal
}

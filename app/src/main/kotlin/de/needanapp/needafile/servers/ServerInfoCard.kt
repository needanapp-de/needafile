package de.needanapp.needafile.servers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.needanapp.design.components.CardAction
import de.needanapp.design.components.CardMenu
import de.needanapp.design.components.CardTileGroups
import de.needanapp.design.components.ConfirmDialog
import de.needanapp.design.components.Detail
import de.needanapp.design.components.FloatingItemCard
import de.needanapp.design.components.detailLines
import de.needanapp.needafile.R
import de.needanapp.needafile.remote.AUTH_KEY
import de.needanapp.needafile.remote.RemoteServer
import de.needanapp.needafile.remote.SERVER_TYPE_FTP
import de.needanapp.needafile.remote.SERVER_TYPE_SFTP
import de.needanapp.needafile.remote.SERVER_TYPE_SMB
import de.needanapp.needafile.remote.SERVER_TYPE_WEBDAV

/**
 * A saved connection as a card floating in place of the pill, the way the viewers show a file: its name, protocol and
 * address, the actions open, edit and delete (after asking), and below them what is set for it. Info again or back
 * runs [onClose]; it slides away while [visible] is false.
 */
@Composable
fun ServerInfoCard(
    server: RemoteServer,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val actions = listOf(
        CardAction(Icons.Outlined.FolderOpen, stringResource(R.string.open_server)) {
            onClose()
            onOpen()
        },
        CardAction(Icons.Outlined.Edit, stringResource(R.string.edit)) {
            onClose()
            onEdit()
        },
        CardAction(Icons.Outlined.Delete, stringResource(R.string.delete), isDestructive = true) { confirmDelete = true },
        // shown already, so it closes the card
        CardAction(Icons.Outlined.Info, stringResource(R.string.info_short), isActive = true, onClick = onClose),
    )
    val details = serverDetails(server).map { (label, value) -> Detail(stringResource(label), value) }
    FloatingItemCard(
        title = server.getDisplayName(),
        subtitle = stringResource(R.string.server_protocol_and_address, stringResource(protocolLabel(server.type)), server.getSummary()),
        menu = CardMenu(actions, emptyList()),
        expanded = false,
        onFold = onClose,
        visible = visible,
        modifier = modifier,
        details = {
            CardTileGroups {
                label(stringResource(R.string.server_card_connection))
                detailLines(details)
            }
        },
    )

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_server),
            message = stringResource(R.string.delete_server_question, server.getDisplayName()),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                onClose()
                onDelete()
            },
            onDismiss = { confirmDelete = false },
            isDestructive = true,
        )
    }
}

/** Label and value of what is set for [server]; empty values are left out, an empty user is a guest or anonymous. */
@Composable
private fun serverDetails(server: RemoteServer): List<Pair<Int, String>> = buildList {
    add(R.string.server_detail_protocol to stringResource(protocolLabel(server.type)))
    add(R.string.server_detail_address to server.getSummary())
    if (server.type != SERVER_TYPE_WEBDAV) {
        add(R.string.server_host to server.host)
        add(R.string.server_port to server.port.toString())
    }
    if (server.type == SERVER_TYPE_SMB) {
        if (server.share.isNotEmpty()) add(R.string.server_detail_share to server.share)
        if (server.domain.isNotEmpty()) add(R.string.server_detail_domain to server.domain)
    }
    if ((server.type == SERVER_TYPE_SFTP || server.type == SERVER_TYPE_FTP) && server.initialPath != "/") {
        add(R.string.server_detail_start to server.initialPath)
    }
    val user = when {
        server.username.isNotEmpty() -> server.username
        server.type == SERVER_TYPE_SMB -> stringResource(R.string.server_detail_guest)
        server.type == SERVER_TYPE_FTP -> stringResource(R.string.server_detail_anonymous)
        else -> null
    }
    user?.let { add(R.string.server_username to it) }
    if (server.type == SERVER_TYPE_SFTP) {
        add(R.string.server_authentication to stringResource(if (server.authType == AUTH_KEY) R.string.server_auth_key else R.string.server_auth_password))
    }
}


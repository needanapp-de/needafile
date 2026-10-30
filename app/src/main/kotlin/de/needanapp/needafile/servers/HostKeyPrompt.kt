package de.needanapp.needafile.servers

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.CardStatus
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.Detail
import de.needanapp.design.components.DetailTile
import de.needanapp.design.components.StatusTile
import de.needanapp.design.components.StatusTone
import de.needanapp.design.components.TileCard
import de.needanapp.needafile.R
import de.needanapp.needafile.remote.HostKeyException
import de.needanapp.needafile.security.KnownHosts

/**
 * Shows the SSH host key of [error] with its fingerprint and asks whether to trust it. A changed key is a warning with
 * the previous key below. Nothing is stored unless the user confirms; then [onTrusted] runs.
 */
@Composable
fun HostKeyPrompt(error: HostKeyException, onTrusted: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rejection = error.rejection
    val presented = rejection.presented
    val previous = rejection.previous
    val address = if (rejection.port == DEFAULT_SSH_PORT) rejection.host else "${rejection.host}:${rejection.port}"

    ContentDialog(
        title = stringResource(if (previous == null) R.string.host_key_unknown_title else R.string.host_key_changed_title),
        confirmLabel = stringResource(if (previous == null) R.string.host_key_trust else R.string.host_key_accept_new),
        onConfirm = {
            KnownHosts(context).trust(rejection.host, rejection.port, presented)
            onDismiss()
            onTrusted()
        },
        onDismiss = onDismiss,
        isDestructive = previous != null,
        icon = if (previous == null) Icons.Outlined.Key else Icons.Outlined.GppMaybe,
    ) {
        Text(
            stringResource(if (previous == null) R.string.host_key_unknown_message else R.string.host_key_changed_message, address),
            color = if (previous != null) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        Spacer(Modifier.height(12.dp))
        val status = if (previous == null) {
            CardStatus(stringResource(R.string.host_key_status_unknown), Icons.Outlined.Key, StatusTone.ACCENT)
        } else {
            CardStatus(stringResource(R.string.host_key_status_changed), Icons.Outlined.GppMaybe, StatusTone.ERROR)
        }
        // each key its type above its fingerprint
        TileCard(stringResource(R.string.host_key_current)) {
            line({ m, shape -> StatusTile(status, shape, m) })
            val current = Detail(presented.type, presented.fingerprint, isCode = true)
            line({ m, shape -> DetailTile(current, shape, m) })
        }
        if (previous != null) {
            Spacer(Modifier.height(12.dp))
            TileCard(stringResource(R.string.host_key_previous)) {
                val before = Detail(previous.type, previous.fingerprint, isCode = true)
                line({ m, shape -> DetailTile(before, shape, m) })
            }
        }
    }
}

private const val DEFAULT_SSH_PORT = 22

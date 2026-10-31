package de.needanapp.needafile.exchange

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.InfoDialog
import de.needanapp.design.components.signalCheckboxColors
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.remote.RemoteServer
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.remote.SERVER_TYPE_SFTP
import de.needanapp.needafile.security.KnownHosts
import de.needanapp.needafile.theme.setThemedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where needaload asks for this app's SFTP connections (see [ConnectionExchange]). Only needaload, signed with this
 * app's key, gets an answer; it shows the connections to choose from as a dialog over needaload and returns the chosen
 * ones with their passwords or keys and confirmed host keys. The app lock comes first, as on every screen of the app.
 */
class ShareConnectionsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // anything but the confirmed choice answers "cancelled"
        setResult(RESULT_CANCELED)
        if (!isCalledByNeedaload()) {
            finish()
            return
        }
        setThemedContent { ShareConnectionsDialog(onShare = ::share, onCancel = ::finish) }
    }

    /** The activity's permission already keeps out apps with another key; this checks who will get the answer. */
    private fun isCalledByNeedaload(): Boolean {
        val caller = callingActivity?.packageName ?: return false
        return ConnectionExchange.isPartner(caller) && packageManager.checkSignatures(caller, packageName) == PackageManager.SIGNATURE_MATCH
    }

    private fun share(connections: List<SharedConnection>) {
        setResult(RESULT_OK, Intent().putExtra(ConnectionExchange.EXTRA_CONNECTIONS, ConnectionExchange.encode(connections)))
        finish()
    }
}

/** One SFTP server to choose; [connection] carries its secrets and is only complete with a saved password or key. */
private class ConnectionChoice(val id: String, val title: String, val address: String, val connection: SharedConnection)

@Composable
private fun ShareConnectionsDialog(onShare: (List<SharedConnection>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    // read off the main thread, as decrypting the secrets uses the Keystore; kept in memory only, never in saved state
    val choices by produceState<List<ConnectionChoice>?>(null) { value = withContext(Dispatchers.IO) { loadChoices(context) } }
    // all start chosen; only ids are saved with the screen
    var unchosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val shown = choices ?: return

    if (shown.isEmpty()) {
        InfoDialog(
            title = stringResource(R.string.share_connections_title),
            message = stringResource(R.string.share_connections_none),
            onDismiss = onCancel,
            confirmLabel = stringResource(R.string.share_connections_close),
            icon = Icons.Outlined.Dns,
        )
        return
    }

    val chosen = shown.filter { it.connection.isComplete && it.id !in unchosen }
    ContentDialog(
        title = stringResource(R.string.share_connections_title),
        confirmLabel = stringResource(R.string.share_connections_confirm, chosen.size),
        onConfirm = { onShare(chosen.map { it.connection }) },
        onDismiss = onCancel,
        confirmEnabled = chosen.isNotEmpty(),
        icon = Icons.Outlined.Dns,
    ) {
        shown.forEach { choice ->
            ChoiceRow(choice, isChosen = choice in chosen) { checked ->
                unchosen = if (checked) unchosen - choice.id else unchosen + choice.id
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.share_connections_hint), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChoiceRow(choice: ConnectionChoice, isChosen: Boolean, onChange: (Boolean) -> Unit) {
    val isComplete = choice.connection.isComplete
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(isChosen, enabled = isComplete, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
    ) {
        Checkbox(isChosen, onCheckedChange = null, enabled = isComplete, modifier = Modifier.padding(12.dp), colors = signalCheckboxColors())
        Column {
            Text(choice.title, style = MaterialTheme.typography.bodyLarge)
            Text(choice.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!isComplete) {
                Text(stringResource(R.string.share_connections_incomplete), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** The saved SFTP servers in their order, each with the host key confirmed for it. */
private fun loadChoices(context: Context): List<ConnectionChoice> {
    val knownHosts = KnownHosts(context)
    return RemoteServersStore.getServers(context).filter { it.type == SERVER_TYPE_SFTP }.map { server ->
        val hostKey = knownHosts.get(server.host, server.port)?.let { SharedHostKey(it.type, it.blob) }
        ConnectionChoice(server.id, server.getDisplayName(), address(context, server), server.toShared(hostKey))
    }
}

/** user@host:port, an IPv6 address in brackets. */
private fun address(context: Context, server: RemoteServer): String {
    val host = server.host.trim().let { if (':' in it) "[$it]" else it }
    return context.getString(R.string.connection_address, server.username.trim(), host, server.port)
}

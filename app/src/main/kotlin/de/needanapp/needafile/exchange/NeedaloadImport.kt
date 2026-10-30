package de.needanapp.needafile.exchange

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.needanapp.needafile.R
import de.needanapp.needafile.core.showError
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.security.KnownHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Take over connections from needaload" (see [ConnectionExchange]): opens needaload's choice of its connections and
 * adds the chosen ones as SFTP servers, then tells what happened. Null while the needaload build matching this one isn't
 * installed (or too old to offer connections); checked again whenever the screen comes back, e.g. after installing it.
 * [onImported] runs after servers may have been added.
 */
@Composable
fun rememberNeedaloadImport(onImported: () -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnImported by rememberUpdatedState(onImported)
    val partner = remember(context) { ConnectionExchange.partnerPackage(context.packageName) }
    var isAvailable by remember { mutableStateOf(context.offersConnections(partner)) }
    LifecycleResumeEffect(partner) {
        isAvailable = context.offersConnections(partner)
        onPauseOrDispose {}
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // cancelled in needaload, or this app wasn't accepted there: nothing to take over
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        // holds passwords and keys, so it lives only in this call, never in the screen's saved state
        val answer = result.data?.getStringExtra(ConnectionExchange.EXTRA_CONNECTIONS)
        scope.launch {
            try {
                // finished even if the page is left meanwhile, so servers and host keys are never saved halfway
                val message = withContext(NonCancellable + Dispatchers.IO) { takeOver(context, answer) }
                context.toast(message, Toast.LENGTH_LONG)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.showError(context.describeError(e))
            }
            currentOnImported()
        }
    }

    if (!isAvailable) return null
    return {
        // needaload only answers apps signed with its own key; say so instead of a silent refusal
        if (context.packageManager.checkSignatures(partner, context.packageName) != PackageManager.SIGNATURE_MATCH) {
            context.toast(R.string.needaload_other_signature, Toast.LENGTH_LONG)
        } else {
            try {
                launcher.launch(pickIntent(partner))
            } catch (e: ActivityNotFoundException) {
                context.toast(R.string.needaload_unavailable)
            } catch (e: SecurityException) {
                context.toast(R.string.needaload_other_signature, Toast.LENGTH_LONG)
            }
        }
    }
}

private fun pickIntent(partner: String) = Intent(ConnectionExchange.ACTION_PICK_CONNECTIONS).setPackage(partner)

/** Whether [partner] is installed with the activity that hands out connections (visible through the manifest's queries). */
private fun Context.offersConnections(partner: String): Boolean {
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.resolveActivity(pickIntent(partner), PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.resolveActivity(pickIntent(partner), 0)
    }
    return resolved != null
}

/** Saves what needaload answered as new servers and host keys; returns the message for the user. */
private fun takeOver(context: Context, answer: String?): String = when (val decoded = ConnectionExchange.decode(answer)) {
    ConnectionExchange.Decoded.Unreadable -> context.getString(R.string.needaload_import_unreadable)
    is ConnectionExchange.Decoded.UnsupportedFormat -> context.getString(R.string.needaload_import_unsupported)
    is ConnectionExchange.Decoded.Connections -> {
        val knownHosts = KnownHosts(context)
        val plan = ConnectionImport.plan(RemoteServersStore.getServers(context), decoded.connections) { host, port ->
            knownHosts.get(host, port)?.let { SharedHostKey(it.type, it.blob) }
        }
        RemoteServersStore.add(context, plan.added)
        plan.hostKeys.forEach { knownHosts.trust(it.host, it.port, KnownHosts.HostKey(it.key.type, it.key.blob)) }
        summary(context, plan, decoded.skipped)
    }
}

/** "2 connections taken over, 1 was already there", plus host key conflicts and skipped entries if there were any. */
private fun summary(context: Context, plan: ConnectionImport.Plan, skipped: Int): String {
    val resources = context.resources
    fun part(plural: Int, count: Int) = resources.getQuantityString(plural, count, count)
    val incomplete = skipped + plan.incomplete
    return listOfNotNull(
        part(R.plurals.needaload_import_added, plan.added.size),
        plan.existing.takeIf { it > 0 }?.let { part(R.plurals.needaload_import_existing, it) },
        plan.hostKeyConflicts.takeIf { it > 0 }?.let { part(R.plurals.needaload_import_conflicts, it) },
        incomplete.takeIf { it > 0 }?.let { part(R.plurals.needaload_import_skipped, it) },
    ).reduce { text, next -> context.getString(R.string.list_join, text, next) }
}

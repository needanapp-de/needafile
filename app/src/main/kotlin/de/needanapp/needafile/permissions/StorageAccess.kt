package de.needanapp.needafile.permissions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.needanapp.design.components.AppScaffold
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.SignalButton
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.needafile.R
import de.needanapp.needafile.core.showError
import java.util.WeakHashMap

/** Access to all files ("all files access"), which a file manager needs to show and change them. */
object StorageAccess {
    fun isGranted() = Environment.isExternalStorageManager()

    /** The system page where the user switches the access on for needafile. */
    fun openSettings(activity: Activity) {
        val forThisApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
        try {
            activity.startActivity(forThisApp)
        } catch (e: ActivityNotFoundException) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { activity.showError(it.toString()) }
        }
    }
}

private val pendingActions = WeakHashMap<Activity, MutableState<(() -> Unit)?>>()

private fun Activity.pendingAccess() = pendingActions.getOrPut(this) { mutableStateOf(null) }

/** Runs [action] right away when needafile may access all files, otherwise once the user allowed it. */
fun Activity.withStorageAccess(action: () -> Unit) {
    if (StorageAccess.isGranted()) action() else pendingAccess().value = action
}

/** Asks for what [withStorageAccess] needs and goes on after the system settings; part of every themed screen. */
@Composable
fun StorageAccessHost() {
    val activity = LocalActivity.current ?: return
    val state = remember(activity) { activity.pendingAccess() }
    val action = state.value ?: return

    // back from the system settings
    LifecycleResumeEffect(action) {
        if (StorageAccess.isGranted()) {
            state.value = null
            action()
        }
        onPauseOrDispose {}
    }
    ContentDialog(
        title = stringResource(R.string.storage_access_title),
        confirmLabel = stringResource(R.string.storage_access_allow),
        onConfirm = { StorageAccess.openSettings(activity) },
        onDismiss = { state.value = null },
        icon = Icons.Outlined.FolderOpen,
    ) {
        Text(stringResource(R.string.storage_access_message, stringResource(R.string.brand_name)))
    }
}

/** Instead of the files, as long as needafile may not access them: what it needs, and the way to allow it. */
@Composable
fun StorageAccessScreen(onAllow: () -> Unit) {
    AppScaffold(title = stringResource(R.string.brand_name)) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).verticalScrollWithBar(), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Outlined.FolderOpen,
                stringResource(R.string.storage_access_title),
                message = stringResource(R.string.storage_access_message, stringResource(R.string.brand_name)),
                action = { SignalButton(onClick = onAllow) { Text(stringResource(R.string.storage_access_allow)) } },
            )
        }
    }
}

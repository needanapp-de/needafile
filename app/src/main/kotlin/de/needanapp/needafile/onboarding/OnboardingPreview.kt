package de.needanapp.needafile.onboarding

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.AppScaffold
import de.needanapp.design.components.NavigationPill
import de.needanapp.design.components.PillAction
import de.needanapp.design.components.PillItem
import de.needanapp.design.components.PillMenu
import de.needanapp.design.components.SignalLinearProgressIndicator
import de.needanapp.design.components.designCard
import de.needanapp.design.theme.LocalDesignShapes
import de.needanapp.needafile.R
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.internalStorage
import de.needanapp.needafile.files.FileRow
import de.needanapp.needafile.files.rememberStorageSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/**
 * The files as the introduction shows them above its steps: the phone's storage with its first entries, or empty rows
 * while needafile may not read it yet ([showNoAccess] says so); with [showNotifications] the two kinds of
 * notifications on top. Built from the app's own parts, so it takes on every choice of the appearance step.
 */
@Composable
internal fun OnboardingPreview(filesAllowed: Boolean, showNoAccess: Boolean, showNotifications: Boolean) {
    val context = LocalContext.current
    val root = context.internalStorage
    val space = rememberStorageSpace(root)
    val entries by produceState(emptyList<FileEntry>(), filesAllowed) {
        value = if (filesAllowed) withContext(Dispatchers.IO) { firstEntries(root) } else emptyList()
    }
    val pages = listOf(
        PillItem(Icons.Outlined.Folder, stringResource(R.string.files_tab)),
        PillItem(Icons.Outlined.Dns, stringResource(R.string.servers)),
        PillItem(Icons.Outlined.Lock, stringResource(R.string.vaults)),
    )
    AppScaffold(
        title = stringResource(R.string.internal),
        subtitle = space?.let { stringResource(R.string.storage_free_of, it.free.formatSize(), it.total.formatSize()) },
        actions = {
            IconButton(onClick = {}) { Icon(Icons.Outlined.Search, contentDescription = null) }
        },
        bottomBar = { hazeState ->
            NavigationPill(
                items = pages,
                selectedIndex = 0,
                onSelect = {},
                modifier = Modifier.align(Alignment.BottomCenter),
                hazeState = hazeState,
                leadingAction = PillAction(Icons.Outlined.Add, stringResource(R.string.create_new_title)) {},
                trailingMenu = PillMenu(Icons.Outlined.Menu, stringResource(R.string.menu), emptyList()),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showNotifications) SampleNotifications()
            if (entries.isNotEmpty()) {
                entries.forEach { entry ->
                    FileRow(entry, selected = false, onClick = {}, onLongClick = {}, onIconClick = {}, asCard = true, isDetails = true)
                }
            } else {
                repeat(PLACEHOLDER_ROWS) { PlaceholderRow() }
            }
        }
        if (showNoAccess) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NoAccessBadge() }
        }
    }
}

/** The first entries of the phone's storage as the list sorts them: folders first, then by name, hidden ones left out. */
private fun firstEntries(root: String): List<FileEntry> =
    File(root).listFiles().orEmpty()
        .filterNot { it.isHidden }
        .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        .take(PREVIEW_ENTRIES)
        .map { file ->
            val children = if (file.isDirectory) file.list()?.count { !it.startsWith('.') } ?: 0 else 0
            FileEntry(file.absolutePath, file.name, file.isDirectory, children, if (file.isDirectory) 0L else file.length(), file.lastModified())
        }

/** A row of the list without content, as long as there are no files to show. */
@Composable
private fun PlaceholderRow() {
    val shapes = LocalDesignShapes.current
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = shapes.rowGap)
            .designCard(RoundedCornerShape(shapes.group))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(fill))
        Box(Modifier.padding(start = 16.dp).weight(1f)) {
            Box(Modifier.fillMaxWidth(0.55f).height(10.dp).clip(CircleShape).background(fill))
        }
        Box(Modifier.width(56.dp).height(8.dp).clip(CircleShape).background(fill))
    }
}

/** "No access yet" over the empty rows. */
@Composable
private fun NoAccessBadge() {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerHighest)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FolderOff, contentDescription = null, tint = scheme.primary)
        Text(stringResource(R.string.onboarding_no_access), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
    }
}

/** What needafile's notifications look like: a copy in progress and an open vault counting down to its lock. */
@Composable
private fun SampleNotifications() {
    val context = LocalContext.current
    val lockTime = remember { DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + SAMPLE_LOCK_DELAY_MS)) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SampleNotification(Icons.Outlined.ContentCopy, stringResource(R.string.transfer_progress, 3, 12), progress = SAMPLE_PROGRESS)
        SampleNotification(
            Icons.Outlined.LockOpen,
            stringResource(R.string.vault_notification_countdown_one, stringResource(R.string.onboarding_sample_vault), SAMPLE_COUNTDOWN),
            detail = stringResource(R.string.vault_notification_lock_time, lockTime),
            action = stringResource(R.string.vault_lock_short),
        )
    }
}

@Composable
private fun SampleNotification(icon: ImageVector, title: String, progress: Float? = null, detail: String? = null, action: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHighest)
            .padding(14.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            progress?.let { SignalLinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
            action?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

private const val PREVIEW_ENTRIES = 8
private const val PLACEHOLDER_ROWS = 7
private const val SAMPLE_PROGRESS = 0.25f
private const val SAMPLE_COUNTDOWN = "4:59"
private const val SAMPLE_LOCK_DELAY_MS = 5 * 60_000L

package de.needanapp.needafile.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import de.needanapp.design.components.ListRow
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.SignalButton
import de.needanapp.design.components.SignalCircularProgressIndicator
import de.needanapp.design.components.SubScreen
import de.needanapp.design.components.scrollbar
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.BusyWork
import de.needanapp.needafile.core.displayPath
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.internalStorage
import de.needanapp.needafile.core.openFileForWriting
import de.needanapp.needafile.core.rescanPaths
import de.needanapp.needafile.core.showError
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.files.FolderPick
import de.needanapp.needafile.files.FolderPickerSheet
import de.needanapp.needafile.files.typeIcon
import de.needanapp.needafile.permissions.withStorageAccess
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.theme.setThemedContent
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What another app shares: files, or without files a piece of text. */
private sealed interface SharedContent {
    data class Files(val uris: List<Uri>) : SharedContent
    data class Text(val text: String, val subject: String?) : SharedContent

    companion object {
        fun from(intent: Intent): SharedContent? {
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> return null
            }
            if (uris.isNotEmpty()) return Files(uris)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return null
            return Text(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        }
    }
}

/** One file to save: [uri] is null for shared text, which is written from the intent itself. */
private data class SharedItem(val uri: Uri?, val name: String, val size: Long?)

/** "Save" in the share menu of other apps: the shared files (or text) are copied into a folder of choice. */
class SaveSharedActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = SharedContent.from(intent)
        if (content == null) {
            toast(R.string.unknown_error_occurred)
            finish()
            return
        }
        setThemedContent { SaveSharedScreen(this, content, onBack = ::finish) }
    }
}

@Composable
private fun SaveSharedScreen(activity: BaseActivity, content: SharedContent, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<SharedItem>?>(null) }
    var folder by rememberSaveable { mutableStateOf(defaultFolder(activity)) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(content) {
        items = withContext(Dispatchers.IO) { sharedItems(activity, content) }
    }

    fun save(toSave: List<SharedItem>) = activity.withStorageAccess {
        saving = true
        // the display stays on until it's done
        val work = BusyWork.begin(listOf(folder))
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { runCatching { saveAll(activity, content, toSave, folder) } }
            } finally {
                // also when the screen closes meanwhile
                BusyWork.end(work)
            }
            result.onSuccess {
                activity.config.lastCopyPath = folder
                activity.toast(activity.resources.getQuantityString(R.plurals.files_saved, toSave.size))
                activity.finish()
            }.onFailure { error ->
                saving = false
                activity.showError(activity.describeError(error))
            }
        }
    }

    SubScreen(stringResource(R.string.save_as), onBack) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize().scrollbar(listState, padding)) {
            val shown = items
            if (shown == null) {
                item { SignalCircularProgressIndicator(Modifier.padding(32.dp)) }
            } else {
                item {
                    SettingsGroup(pluralStringResource(R.plurals.shared_items, shown.size, shown.size)) {
                        shown.forEach { item ->
                            row {
                                val icon = if (item.uri == null) Icons.AutoMirrored.Outlined.Notes else typeIcon(item.name)
                                ListRow(it, item.name, summary = item.size?.formatSize(), icon = icon)
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.save_to)) {
                    row { ListRow(it, activity.displayPath(folder), icon = Icons.Outlined.Folder, onClick = { picking = true }) }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (saving) SignalCircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 3.dp)
                    SignalButton(enabled = !saving && !shown.isNullOrEmpty(), onClick = { shown?.let(::save) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    if (picking) {
        FolderPickerSheet(FolderPick(R.string.select_folder, R.string.use_this_folder, folder) { folder = it }, onDismiss = { picking = false })
    }
}

/** The last copy destination if it still exists, otherwise the download folder. */
private fun defaultFolder(context: Context): String =
    context.config.lastCopyPath.takeIf { it.isNotEmpty() && File(it).isDirectory }
        ?: "${context.internalStorage}/${Environment.DIRECTORY_DOWNLOADS}"

private fun sharedItems(context: Context, content: SharedContent): List<SharedItem> = when (content) {
    is SharedContent.Text -> {
        val title = content.subject?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_NAME_LENGTH) ?: context.getString(R.string.shared_text_name)
        listOf(SharedItem(null, "${sanitize(title)}.txt", content.text.toByteArray().size.toLong()))
    }

    is SharedContent.Files -> content.uris.map { uri ->
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    size = if (cursor.isNull(1)) null else cursor.getLong(1)
                }
            }
        }
        // plain file links carry no columns, the file itself tells its size
        if (uri.scheme == ContentResolver.SCHEME_FILE) size = uri.path?.let { File(it).length() }
        SharedItem(uri, sanitize(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.shared_file_name)), size)
    }
}

/** Copies every item into [folder]; taken names get " (2)", " (3)" …, nothing is overwritten. */
private fun saveAll(context: Context, content: SharedContent, items: List<SharedItem>, folder: String): List<String> {
    File(folder).mkdirs()
    val paths = items.map { item ->
        val path = availablePath(folder, item.name)
        val output = openFileForWriting(path)
        output.use { out ->
            if (item.uri != null) {
                val input = context.contentResolver.openInputStream(item.uri) ?: throw IOException(item.name)
                input.use { it.copyTo(out) }
            } else if (content is SharedContent.Text) {
                out.write(content.text.toByteArray())
            }
        }
        path
    }
    context.rescanPaths(paths)
    return paths
}

private fun availablePath(folder: String, name: String): String {
    val base = name.substringBeforeLast('.').takeIf { name.contains('.') && !name.startsWith('.') } ?: name
    val extension = name.removePrefix(base)
    var candidate = File(folder, name)
    var number = 2
    while (candidate.exists()) {
        candidate = File(folder, "$base ($number)$extension")
        number++
    }
    return candidate.path
}

private fun sanitize(name: String) = name.replace(INVALID_CHARACTERS, "_").trim().ifEmpty { "_" }

private val INVALID_CHARACTERS = Regex("[/\\\\<>:\"|?*\u0000-\u001F]")
private const val MAX_TEXT_NAME_LENGTH = 60

package de.needanapp.needafile.viewer.image.edit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.AppScaffold
import de.needanapp.design.components.CardSpacing
import de.needanapp.design.components.ConfirmDialog
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.NavigationPill
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.PillAction
import de.needanapp.design.components.PillItem
import de.needanapp.design.components.SignalCircularProgressIndicator
import de.needanapp.design.components.TileCard
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.EXTRA_FILE_PATH
import de.needanapp.needafile.core.internalStorage
import de.needanapp.needafile.core.showError
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.editor.SaveAsSheet
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.permissions.withStorageAccess
import de.needanapp.needafile.theme.setThemedContent
import de.needanapp.needafile.vault.closeWhenVaultLocks
import de.needanapp.needafile.viewer.image.ViewerImage
import de.needanapp.needafile.viewer.image.ViewerImages
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The picture editor: crop, rotate, flip and resize, drawing. The steps add up; saving makes a copy next to
 * the original, or – after asking – replaces it.
 */
class ImageEditorActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        val uri = path?.let { Uri.fromFile(File(it)) } ?: intent.data
        if (uri == null) {
            toast(R.string.unknown_error_occurred)
            finish()
            return
        }
        closeWhenVaultLocks(path, intent.data)
        if (isFinishing) return
        setThemedContent { ImageEditorScreen(this, uri) }
    }

    companion object {
        /** The file the edited picture was saved to, for the viewer to show it. */
        const val RESULT_PATH = "de.needanapp.needafile.viewer.EDITED_PATH"

        internal fun intent(context: Context, image: ViewerImage): Intent {
            val intent = Intent(context, ImageEditorActivity::class.java)
            return if (image.path != null) {
                intent.putExtra(EXTRA_FILE_PATH, image.path)
            } else {
                intent.setDataAndType(image.uri, context.contentResolver.getType(image.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}

@Composable
private fun ImageEditorScreen(activity: BaseActivity, uri: Uri) {
    var image by remember { mutableStateOf<ViewerImage?>(null) }
    var session by remember { mutableStateOf<EditorSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        try {
            val resolved = withContext(Dispatchers.IO) { ViewerImages.resolve(activity, uri) }
            image = resolved
            session = EditorSession(withContext(Dispatchers.IO) { EditedImages.load(activity, resolved) })
        } catch (e: Exception) {
            error = activity.describeError(e)
        } catch (e: OutOfMemoryError) {
            error = activity.getString(R.string.out_of_memory_error)
        }
    }

    val current = session
    val source = image
    if (current == null || source == null) {
        AppScaffold(
            title = stringResource(R.string.image_editor_title),
            navigationIcon = { IconButton(onClick = { activity.finish() }) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) } },
        ) { padding ->
            if (error != null) {
                EmptyState(Icons.Outlined.ErrorOutline, stringResource(R.string.image_editor_load_failed), Modifier.padding(padding), message = error)
            } else {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { SignalCircularProgressIndicator() }
            }
        }
        return
    }
    EditorContent(activity, source, current)
}

@Composable
private fun EditorContent(activity: BaseActivity, image: ViewerImage, session: EditorSession) {
    val scope = rememberCoroutineScope()
    var askBeforeClosing by remember { mutableStateOf(false) }
    var showSaveOptions by remember { mutableStateOf(false) }
    var showSaveAs by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    fun close() {
        if (session.isChanged) askBeforeClosing = true else activity.finish()
    }

    fun switchTool(tool: EditorTool) {
        if (tool == session.tool || session.isBusy) return
        scope.launch {
            session.commit()
            session.tool = tool
        }
    }

    fun startSaving() {
        if (session.isBusy) return
        scope.launch {
            session.commit()
            showSaveOptions = true
        }
    }

    fun save(target: String?) {
        val bitmap = session.working
        val write = if (target == null && image.path == null) { action: () -> Unit -> action() } else activity::withStorageAccess
        write {
            scope.launch {
                session.isBusy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when {
                            target != null -> EditedImages.saveToPath(activity, bitmap, target, session.loaded.exif)
                            image.path != null -> EditedImages.saveToPath(activity, bitmap, image.path, session.loaded.exif)
                            else -> EditedImages.saveToUri(activity, bitmap, image.uri, image.name, session.loaded.exif)
                        }
                    }
                }
                session.isBusy = false
                result.onSuccess {
                    activity.toast(R.string.file_saved)
                    val saved = target ?: image.path
                    activity.setResult(android.app.Activity.RESULT_OK, Intent().putExtra(ImageEditorActivity.RESULT_PATH, saved))
                    activity.finish()
                }.onFailure { e ->
                    activity.showError(activity.describeError(e))
                }
            }
        }
    }

    BackHandler { close() }

    val tools = listOf(
        PillItem(Icons.Outlined.Crop, stringResource(R.string.image_editor_crop)),
        PillItem(Icons.Outlined.Brush, stringResource(R.string.image_editor_draw)),
    )
    AppScaffold(
        title = stringResource(R.string.image_editor_title),
        subtitle = image.name,
        navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) } },
        actions = {
            IconButton(onClick = { confirmReset = true }, enabled = session.isChanged && !session.isBusy) {
                Icon(Icons.Outlined.RestartAlt, stringResource(R.string.image_editor_reset))
            }
        },
        bottomBar = { hazeState ->
            NavigationPill(
                items = tools,
                selectedIndex = session.tool.ordinal,
                onSelect = { switchTool(EditorTool.entries[it]) },
                hazeState = hazeState,
                trailingAction = PillAction(Icons.Outlined.Check, stringResource(R.string.save), enabled = !session.isBusy) { startSaving() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (session.tool) {
                    EditorTool.CROP -> CropArea(session)
                    EditorTool.DRAW -> DrawArea(session)
                }
                if (session.isBusy) SignalCircularProgressIndicator()
            }
            ToolPanel(session)
            // room for the floating pill
            Spacer(Modifier.navigationBarsPadding().height(PILL_SPACE.dp))
        }
    }

    if (showSaveOptions) {
        SaveOptionsSheet(
            session = session,
            canOverwrite = EditedImages.canOverwrite(image),
            onDismiss = { showSaveOptions = false },
            onCopy = {
                showSaveOptions = false
                showSaveAs = true
            },
            onOverwrite = {
                showSaveOptions = false
                confirmOverwrite = true
            },
        )
    }
    if (showSaveAs) {
        val folder = image.path?.let { File(it).parent }?.takeIf { File(it).canWrite() } ?: defaultFolder(activity)
        SaveAsSheet(
            name = EditedImages.copyName(folder, image.name, stringResource(R.string.image_editor_copy_suffix)),
            folder = folder,
            onDismiss = { showSaveAs = false },
            onSave = { path ->
                showSaveAs = false
                save(path)
            },
        )
    }
    if (confirmOverwrite) {
        ConfirmDialog(
            title = stringResource(R.string.image_editor_overwrite_title),
            message = stringResource(R.string.image_editor_overwrite_message),
            confirmLabel = stringResource(R.string.image_editor_overwrite),
            onConfirm = {
                confirmOverwrite = false
                save(null)
            },
            onDismiss = { confirmOverwrite = false },
            isDestructive = true,
            icon = Icons.Outlined.Autorenew,
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.image_editor_reset),
            message = stringResource(R.string.image_editor_reset_message),
            confirmLabel = stringResource(R.string.image_editor_reset),
            onConfirm = {
                confirmReset = false
                session.reset()
            },
            onDismiss = { confirmReset = false },
            isDestructive = true,
            icon = Icons.Outlined.RestartAlt,
        )
    }
    if (askBeforeClosing) {
        ContentDialog(
            title = stringResource(R.string.editor_unsaved_title),
            confirmLabel = stringResource(R.string.save),
            onConfirm = {
                askBeforeClosing = false
                startSaving()
            },
            onDismiss = { askBeforeClosing = false },
            extraLabel = stringResource(R.string.discard),
            onExtra = {
                askBeforeClosing = false
                activity.finish()
            },
            icon = Icons.Outlined.EditNote,
            confirmIcon = Icons.Outlined.Save,
            extraIcon = Icons.Outlined.Delete,
        ) {
            Text(stringResource(R.string.editor_unsaved_message))
        }
    }
}

/** How to save: a copy (the usual way) or over the original; tells when the picture got smaller for editing. */
@Composable
private fun SaveOptionsSheet(session: EditorSession, canOverwrite: Boolean, onDismiss: () -> Unit, onCopy: () -> Unit, onOverwrite: () -> Unit) {
    val loaded = session.loaded
    OptionsSheet(onDismiss = onDismiss, title = stringResource(R.string.save)) {
        val result = session.working
        if (loaded.originalWidth > loaded.bitmap.width) {
            Text(
                stringResource(R.string.image_editor_scaled_down, result.width, result.height, loaded.originalWidth, loaded.originalHeight),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        TileCard(null, CardSpacing) {
            // a single way lies flat instead of as a large tile across the sheet
            line(
                listOfNotNull(
                    { m, shape ->
                        ActionTile(stringResource(R.string.image_editor_save_copy), onCopy, shape, m, Icons.Outlined.SaveAs, centered = canOverwrite, inline = !canOverwrite)
                    },
                    if (canOverwrite) {
                        { m, shape -> ActionTile(stringResource(R.string.image_editor_overwrite), onOverwrite, shape, m, Icons.Outlined.Save, centered = true) }
                    } else {
                        null
                    },
                ),
            )
        }
    }
}

private fun defaultFolder(context: Context) = "${context.internalStorage}/${Environment.DIRECTORY_PICTURES}"

// height of the floating pill with its margin
private const val PILL_SPACE = 84

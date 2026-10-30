package de.needanapp.needafile.viewer.image

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.ShuffleOn
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.AppScaffold
import de.needanapp.design.components.CardAction
import de.needanapp.design.components.CardActionGroup
import de.needanapp.design.components.CardMenu
import de.needanapp.design.components.ConfirmDialog
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.FloatingItemCard
import de.needanapp.design.components.SignalButton
import de.needanapp.design.components.SignalCircularProgressIndicator
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.EXTRA_FILE_PATH
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.deleteFiles
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.core.runOnMainThread
import de.needanapp.needafile.core.setAs
import de.needanapp.needafile.core.showError
import de.needanapp.needafile.core.showOnMap
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.files.getVirtualInnerPath
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.theme.setThemedContent
import de.needanapp.needafile.transfer.TransferManager
import de.needanapp.needafile.vault.VaultSessions
import de.needanapp.needafile.vault.closeWhenVaultLocks
import de.needanapp.needafile.vault.hideFromRecents
import de.needanapp.needafile.vault.readableVaultPath
import de.needanapp.needafile.viewer.ViewerActions
import de.needanapp.needafile.viewer.Viewers
import de.needanapp.needafile.viewer.image.edit.ImageEditorActivity
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import coil3.request.ImageRequest
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Pictures from needafile or another app: swipe through the folder, zoom, share, delete, details. Tapping hides
 * everything but the picture. Files downloaded from a server are shown alone ([Viewers.EXTRA_SINGLE]); the pictures of
 * a vault folder ([Viewers.EXTRA_VAULT_GALLERY]) are decrypted one by one, and the viewer closes when the vault locks.
 */
class ImageViewerActivity : BaseActivity() {
    // the picture on screen, so it stays there when the activity is recreated (e.g. rotating the device)
    private var currentPath: String? = null
    private var galleryToken: String? = null
    private var vaultListener: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(Viewers.EXTRA_VAULT_GALLERY)?.let { token ->
            showVaultGallery(token, savedInstanceState?.getString(STATE_CURRENT))
            return
        }
        val path = savedInstanceState?.getString(STATE_CURRENT) ?: intent.getStringExtra(EXTRA_FILE_PATH)
        val uri = path?.let { Uri.fromFile(File(it)) } ?: intent.data
        if (uri == null) {
            toast(R.string.unknown_error_occurred)
            finish()
            return
        }
        closeWhenVaultLocks(path, intent.data)
        if (isFinishing) return
        val single = intent.getBooleanExtra(Viewers.EXTRA_SINGLE, false)
        setThemedContent { ImageViewerScreen(this, uri, single) { currentPath = it } }
    }

    private fun showVaultGallery(token: String, current: String?) {
        galleryToken = token
        // gone when the app was ended meanwhile, and with it the vault's keys
        val gallery = VaultGallery.get(token)
        if (gallery == null || !VaultSessions.isUnlocked(gallery.location)) {
            finish()
            return
        }
        hideFromRecents(true)
        vaultListener = { _: String -> if (!VaultSessions.isUnlocked(gallery.location)) finish() }.also(VaultSessions::addListener)
        val start = gallery.entries.indexOfFirst { it.vaultPath == current }.takeIf { it >= 0 } ?: gallery.start
        val pictures = gallery.entries.map { ViewerImage(null, Uri.EMPTY, it.file.name, vault = it) }
        setThemedContent {
            var images by remember { mutableStateOf(pictures) }
            ImagePager(
                this, images, start, isCopy = true, onCurrentChanged = { currentPath = it },
                onDeleted = { deleted ->
                    images = images - deleted
                    deleted.vault?.let { VaultGallery.removeEntry(token, it.vaultPath) }
                    if (images.isEmpty()) finish()
                },
                onEdited = {},
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPath?.let { outState.putString(STATE_CURRENT, it) }
    }

    override fun onDestroy() {
        vaultListener?.let(VaultSessions::removeListener)
        if (isFinishing) galleryToken?.let(VaultGallery::remove)
        super.onDestroy()
    }

    private companion object {
        const val STATE_CURRENT = "current"
    }
}

@Composable
private fun ImageViewerScreen(activity: BaseActivity, uri: Uri, single: Boolean, onCurrentChanged: (String?) -> Unit) {
    // the opened picture alone first, then (in the same place) together with the others of its folder
    var images by remember { mutableStateOf<List<ViewerImage>>(emptyList()) }
    var startIndex by remember { mutableStateOf(0) }
    var generation by remember { mutableStateOf(0) }
    // after editing: the saved picture, with the folder read again
    var focus by remember { mutableStateOf(uri) }
    var reloads by remember { mutableStateOf(0) }

    LaunchedEffect(focus, reloads) {
        val start = withContext(Dispatchers.IO) { ViewerImages.resolve(activity, focus) }
        if (images.isEmpty()) images = listOf(start)
        val path = start.path
        if (path == null || single) {
            images = listOf(start)
            generation++
            return@LaunchedEffect
        }
        val found = withContext(Dispatchers.IO) { ViewerImages.siblings(activity, path) }
        val sorted = ViewerImages.sortLikeFolder(activity, File(path).parent.orEmpty(), found)
        val index = sorted.indexOfFirst { it.path == path }
        if (sorted.size > 1 && index >= 0) {
            startIndex = index
            images = sorted.map { ViewerImages.fromPath(it.path) }
        } else {
            startIndex = 0
            images = listOf(start)
        }
        generation++
    }

    if (images.isEmpty()) {
        Box(Modifier.fillMaxSize())
        return
    }
    // a new pager for the full list, starting at the opened picture
    key(generation) {
        ImagePager(
            activity, images, startIndex, isCopy = single, onCurrentChanged,
            onDeleted = { deleted ->
                images = images - deleted
                if (images.isEmpty()) activity.finish()
            },
            onEdited = { saved ->
                focus = saved
                reloads++
            },
        )
    }
}

/**
 * Swipes through [images], in their order or, turned on under "more", in a random one. [isCopy]: they are copies in
 * the cache (from a server or a vault), so editing, deleting and showing their folder are left out; vault pictures can
 * still be deleted, in the vault itself. Vault pictures are decrypted as they come on screen, one at a time.
 */
@Composable
private fun ImagePager(
    activity: BaseActivity,
    images: List<ViewerImage>,
    startIndex: Int,
    isCopy: Boolean,
    onCurrentChanged: (String?) -> Unit,
    onDeleted: (ViewerImage) -> Unit,
    onEdited: (Uri) -> Unit,
) {
    // random order: the picture on screen when it was turned on ([shuffleAnchor]) first, the others mixed by the seed;
    // each picture keeps its place when others are deleted, and every turning on mixes anew
    var shuffleSeed by rememberSaveable { mutableStateOf<Long?>(null) }
    var shuffleAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    val inOrder = remember(images, shuffleSeed, shuffleAnchor) {
        val seed = shuffleSeed ?: return@remember images
        images.sortedWith(compareBy<ViewerImage> { it.key != shuffleAnchor }.thenBy { shuffleRank(seed, it.key) })
    }
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { inOrder.size }
    var isFullScreen by rememberSaveable { mutableStateOf(false) }
    // "more" unfolds the card's further actions, "info" what is known about the picture
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ViewerImage?>(null) }
    // vault pictures by their vault path, once decrypted
    val decrypted = remember { mutableStateMapOf<String, ViewerImage>() }
    val decrypting = remember { Mutex() }
    val page = inOrder.getOrNull(pagerState.currentPage.coerceAtMost(inOrder.lastIndex)) ?: return
    // null while a vault picture is still being decrypted
    val current = if (page.vault != null) decrypted[page.key] else page
    val isLocalFile = current?.path != null && !isCopy
    // a vault picture is deleted in the vault, a copy from a server or another app's picture not at all
    val canDelete = current != null && (isLocalFile || page.vault != null)
    val editor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val saved = result.data?.getStringExtra(ImageEditorActivity.RESULT_PATH)
        if (result.resultCode == Activity.RESULT_OK) onEdited(saved?.let { Uri.fromFile(File(it)) } ?: page.uri)
    }

    LaunchedEffect(page) {
        onCurrentChanged(page.vault?.vaultPath ?: page.path)
        expanded = false
        showDetails = false
    }
    // copies from a server or a vault carry the original's date only where it is known, not the copy's own
    val facts by produceState<String?>(null, current?.key) {
        value = current?.let { image ->
            withContext(Dispatchers.IO) {
                val modified = page.vault?.file?.lastModified ?: image.path?.takeIf { !isCopy }?.let { File(it).lastModified() }
                imageFacts(activity, image, modified?.takeIf { it > 0 })
            }
        }
    }
    val details by produceState<ImageDetailLists?>(null, current?.key) {
        value = current?.let { image ->
            withContext(Dispatchers.IO) { imageDetails(activity, image, page.vault?.vaultPath?.readableVaultPath() ?: image.path?.takeIf { !isCopy }) }
        }
    }
    LaunchedEffect(isFullScreen) { ViewerActions.setFullScreen(activity, isFullScreen) }

    // the picture on screen stays there: first in the random order, at its own place in the other
    fun setShuffled(on: Boolean) {
        val key = page.key
        shuffleSeed = if (on) Random.nextLong() else null
        shuffleAnchor = if (on) key else null
        pagerState.requestScrollToPage(if (on) 0 else images.indexOfFirst { it.key == key }.coerceAtLeast(0))
    }

    fun delete(image: ViewerImage) {
        val entry = image.vault
        if (entry != null) {
            // the decrypted copy in the cache goes with it
            val copy = decrypted[image.key]?.path
            inBackground {
                val result = runCatching {
                    TransferManager.getRemoteFileSystem(activity, entry.vaultPath).delete(listOf(entry.vaultPath.getVirtualInnerPath()))
                    copy?.let { File(it).delete() }
                }
                runOnMainThread {
                    result.onSuccess {
                        decrypted.remove(image.key)
                        onDeleted(image)
                    }.onFailure { activity.showError(activity.describeError(it)) }
                }
            }
            return
        }
        val path = image.path ?: return
        activity.deleteFiles(listOf(FileEntry(path, image.name))) { success ->
            if (success) onDeleted(image) else activity.toast(R.string.unknown_error_occurred)
        }
    }

    AppScaffold(
        title = page.name,
        subtitle = when {
            inOrder.size <= 1 -> null
            shuffleSeed != null -> stringResource(R.string.viewer_position_shuffled, pagerState.currentPage + 1, inOrder.size)
            else -> stringResource(R.string.viewer_position, pagerState.currentPage + 1, inOrder.size)
        },
        navigationIcon = {
            IconButton(onClick = { activity.finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_back)) }
        },
        headerVisible = !isFullScreen,
        backgroundColor = if (isFullScreen) Color.Black else null,
        bottomBar = {
            val actions = buildList {
                if (!isCopy && current != null) {
                    add(CardAction(Icons.Outlined.Edit, stringResource(R.string.edit)) { editor.launch(ImageEditorActivity.intent(activity, current)) })
                }
                add(CardAction(Icons.Outlined.Share, stringResource(R.string.share), enabled = current != null) { current?.let { share(activity, it) } })
                if (canDelete) {
                    add(CardAction(Icons.Outlined.Delete, stringResource(R.string.delete), isDestructive = true) {
                        if (activity.config.skipDeleteConfirmation) delete(page) else confirmDelete = page
                    })
                }
                add(
                    CardAction(Icons.Outlined.Info, stringResource(R.string.info_short), enabled = details != null, isActive = showDetails) {
                        expanded = false
                        showDetails = !showDetails
                    },
                )
                add(
                    CardAction(
                        icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.MoreHoriz,
                        label = stringResource(if (expanded) R.string.less_actions else R.string.more_actions),
                        enabled = current != null,
                        isActive = expanded,
                    ) {
                        showDetails = false
                        expanded = !expanded
                    },
                )
            }
            fun folding(action: () -> Unit): () -> Unit = {
                expanded = false
                action()
            }
            val groups = if (current == null) {
                emptyList()
            } else {
                listOfNotNull(
                    CardActionGroup(
                        stringResource(R.string.card_group_open),
                        listOf(
                            CardAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_with), onClick = folding { openWith(activity, current) }),
                            CardAction(Icons.Outlined.Wallpaper, stringResource(R.string.set_as), onClick = folding { setAs(activity, current) }),
                        ),
                    ),
                    // mixing two pictures changes nothing
                    if (images.size > 2) {
                        val shuffled = shuffleSeed != null
                        CardActionGroup(
                            stringResource(R.string.card_group_order),
                            listOf(
                                CardAction(
                                    if (shuffled) Icons.Outlined.ShuffleOn else Icons.Outlined.Shuffle,
                                    stringResource(R.string.viewer_shuffle),
                                    isActive = shuffled,
                                    onClick = folding { setShuffled(!shuffled) },
                                ),
                            ),
                        )
                    } else {
                        null
                    },
                    if (isLocalFile) {
                        CardActionGroup(
                            stringResource(R.string.card_group_place),
                            listOf(
                                CardAction(Icons.Outlined.FolderOpen, stringResource(R.string.show_in_folder), onClick = folding {
                                    ViewerActions.showInFolder(activity, current.path!!)
                                }),
                            ),
                        )
                    } else {
                        null
                    },
                )
            }
            FloatingItemCard(
                title = page.name,
                subtitle = facts,
                menu = CardMenu(actions, groups),
                expanded = expanded,
                onFold = {
                    expanded = false
                    showDetails = false
                },
                visible = !isFullScreen,
                modifier = Modifier.align(Alignment.BottomCenter),
                details = details?.takeIf { showDetails }?.let { lists -> { ImageDetails(lists) { activity.showOnMap(it.latitude, it.longitude) } } },
            )
        },
    ) {
        HorizontalPager(
            state = pagerState,
            key = { inOrder[it].key },
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val image = inOrder[index]
            val shown = if (image.vault != null) decrypted[image.key] else image
            if (shown != null) {
                ImagePage(shown, onTap = { isFullScreen = !isFullScreen }, onOpenWith = { openWith(activity, shown) })
            } else {
                VaultImagePage(image, decrypting, onDecrypted = { decrypted[image.key] = it })
            }
        }
    }

    confirmDelete?.let { image ->
        ConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.deletion_confirmation, stringResource(R.string.quoted_name, image.name)),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                confirmDelete = null
                delete(image)
            },
            onDismiss = { confirmDelete = null },
            isDestructive = true,
        )
    }
}

/**
 * A vault picture while it is decrypted (one at a time, [decrypting]), with how far it got; a hint when that fails.
 * Swiping on stops it.
 */
@Composable
private fun VaultImagePage(image: ViewerImage, decrypting: Mutex, onDecrypted: (ViewerImage) -> Unit) {
    val context = LocalContext.current
    val entry = image.vault ?: return
    var failed by remember(image.key) { mutableStateOf(false) }
    var progress by remember(image.key) { mutableStateOf<Float?>(null) }
    LaunchedEffect(image.key) {
        val file = runCatching {
            decrypting.withLock {
                withContext(Dispatchers.IO) {
                    VaultSessions.decryptForViewing(context, entry.vaultPath, entry.file, isCancelled = { !isActive }) {
                        if (it.bytesTotal > 0) progress = (it.bytesDone.toFloat() / it.bytesTotal).coerceIn(0f, 1f)
                    }
                }
            }
        }.getOrNull()
        if (file != null) onDecrypted(ViewerImages.fromPath(file.path)) else failed = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            failed -> EmptyState(Icons.Outlined.BrokenImage, stringResource(R.string.viewer_image_failed))
            else -> progress?.let { SignalCircularProgressIndicator(progress = { it }) } ?: SignalCircularProgressIndicator()
        }
    }
}

/** One picture, zoomable (pinch, double tap); a hint with "Open with" when it can't be decoded. */
@Composable
private fun ImagePage(image: ViewerImage, onTap: () -> Unit, onOpenWith: () -> Unit) {
    val context = LocalContext.current
    var failed by remember(image.key) { mutableStateOf(false) }
    val request = remember(image.key) {
        ImageRequest.Builder(context)
            .data(image.model)
            .listener(onError = { _, _ -> failed = true })
            .build()
    }
    if (failed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Outlined.BrokenImage,
                stringResource(R.string.viewer_image_failed),
                action = { SignalButton(onClick = onOpenWith) { Text(stringResource(R.string.open_with)) } },
            )
        }
        return
    }
    val state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZOOM_SPEC))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZoomableAsyncImage(
            model = request,
            contentDescription = image.name,
            imageLoader = ViewerImages.loader(context),
            state = state,
            onClick = { onTap() },
            onDoubleClick = DOUBLE_TAP_ZOOM,
            modifier = Modifier.fillMaxSize(),
        )
        if (!state.isImageDisplayed) SignalCircularProgressIndicator()
    }
}

private fun share(activity: BaseActivity, image: ViewerImage) = ViewerActions.share(activity, image.path, image.uri, IMAGE_TYPE)

private fun openWith(activity: BaseActivity, image: ViewerImage) = ViewerActions.openWith(activity, image.path, image.uri, IMAGE_TYPE)

private fun setAs(activity: BaseActivity, image: ViewerImage) {
    if (image.path != null) {
        activity.setAs(image.path)
    } else {
        ViewerActions.send(activity, Intent.ACTION_ATTACH_DATA, image.uri, IMAGE_TYPE, R.string.set_as)
    }
}

private const val IMAGE_TYPE = "image/*"

/**
 * The place of the picture [key] in the random order of [seed] (SplitMix64 over both): the same for the same seed,
 * unrelated between pictures, so the order doesn't change when one of them is deleted.
 */
private fun shuffleRank(seed: Long, key: String): Long {
    var z = seed + key.hashCode() * GOLDEN_GAMMA
    z = (z xor (z ushr 30)) * MIX_1
    z = (z xor (z ushr 27)) * MIX_2
    return z xor (z ushr 31)
}

private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
private const val MIX_1 = -0x40a7b892e31b1a47L
private const val MIX_2 = -0x6b2fb644ecceee15L

/**
 * Pinching zooms on as long as the fingers go, until one pixel of the picture fills more than the screen; the library
 * would stop at twice the picture's own size.
 */
private val ZOOM_SPEC = ZoomSpec(maximum = ZoomLimit(factor = 4000f))

/** A double tap keeps zooming to twice the picture's own size and back, not to the far end of [ZOOM_SPEC]. */
private val DOUBLE_TAP_ZOOM = DoubleClickToZoomListener.cycle(maxZoomFactor = 2f)

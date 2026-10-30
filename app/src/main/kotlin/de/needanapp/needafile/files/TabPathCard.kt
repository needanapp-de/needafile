package de.needanapp.needafile.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.CardAction
import de.needanapp.design.components.CardActionRow
import de.needanapp.design.components.TabChip
import de.needanapp.design.components.TileDefaults
import de.needanapp.design.components.designCard
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.design.theme.LocalDesignShapes
import de.needanapp.needafile.R
import de.needanapp.needafile.core.copyToClipboard
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.vault.VaultSessions
import de.needanapp.needafile.vault.getVaultLocation
import de.needanapp.needafile.vault.isVaultPath
import kotlin.math.roundToInt

/** A tab whose path to show (tapped while shown, or long-pressed): which one, and where its chip lies on the screen. */
data class TabPathRequest(val index: Int, val chip: Rect)

/**
 * The whole path of a tab in a card below its chip, over the dimmed screen: each folder from the place down, the
 * current one marked; tapping one opens it in that tab. Below, copying the path, locking the vault the tab is in, and
 * closing the tab (not the last one). Back or a tap beside the card closes it. Lies over the whole screen, so it goes
 * last into the app's layout.
 */
@Composable
fun TabPathOverlay(model: FileBrowserModel, request: TabPathRequest?, onDismiss: () -> Unit) {
    // the last request stays while the overlay fades out
    var shown by remember { mutableStateOf(request) }
    if (request != null) shown = request
    BackHandler(enabled = request != null, onBack = onDismiss)
    AnimatedVisibility(visible = request != null, enter = fadeIn(), exit = fadeOut()) {
        val current = shown ?: return@AnimatedVisibility
        val path = model.tabs.getOrNull(current.index) ?: return@AnimatedVisibility
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
            // the chip again, above the dimming
            TabChip(
                label = model.breadcrumbs(path).last().first,
                selected = !model.showsPlaces && current.index == model.activeTab,
                onClick = onDismiss,
                modifier = Modifier.offset { IntOffset(current.chip.left.roundToInt(), current.chip.top.roundToInt()) },
                icon = locationIcon(model.locationKind(path)),
            )
            val top = with(density) { current.chip.bottom.toDp() } + CARD_GAP
            val bottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + CARD_GAP
            TabPathCard(
                model,
                current.index,
                path,
                onDismiss,
                Modifier
                    .padding(horizontal = 16.dp)
                    .offset(y = top)
                    .fillMaxWidth()
                    .heightIn(max = (maxHeight - top - bottom).coerceAtLeast(MIN_CARD_HEIGHT)),
            )
        }
    }
}

@Composable
private fun TabPathCard(model: FileBrowserModel, index: Int, path: String, onDismiss: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val crumbs = model.breadcrumbs(path)
    val vault = path.takeIf { it.isVaultPath() }?.getVaultLocation()?.takeIf(VaultSessions::isUnlocked)
    Column(modifier.designCard(RoundedCornerShape(CARD_RADIUS)).padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        Text(
            stringResource(R.string.tab_path_title),
            style = MaterialTheme.typography.titleSmall,
            color = colors.primary,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp).semantics { heading() },
        )
        // a deep path scrolls, the buttons below stay in view
        Column(
            Modifier.weight(1f, fill = false).verticalScrollWithBar(),
            verticalArrangement = Arrangement.spacedBy(LocalDesignShapes.current.rowGap),
        ) {
            crumbs.forEachIndexed { level, (label, crumbPath) ->
                val isCurrent = level == crumbs.lastIndex
                val isPlace = crumbPath.trimEnd('/').length <= model.locationRoot(crumbPath).trimEnd('/').length
                CrumbRow(
                    label = label,
                    icon = if (isPlace) locationIcon(model.locationKind(crumbPath)) else Icons.Outlined.Folder,
                    isCurrent = isCurrent,
                    indent = INDENT * minOf(level, MAX_INDENT_LEVELS),
                    onClick = {
                        if (!isCurrent || index != model.activeTab || model.showsPlaces) model.openInTab(index, crumbPath)
                        onDismiss()
                    },
                )
            }
        }
        Row(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TouchApp, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.tab_path_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        CardActionRow(
            listOfNotNull(
                CardAction(Icons.Outlined.Link, stringResource(R.string.copy_path)) {
                    context.copyToClipboard(if (path.isVirtualPath()) model.displayPath(path) else path)
                    onDismiss()
                },
                // from any of its folders; the vault's tabs close with it
                vault?.let { location ->
                    CardAction(Icons.Outlined.Lock, stringResource(R.string.vault_lock)) {
                        model.lockVault(location)
                        context.toast(R.string.vault_locked_toast)
                        onDismiss()
                    }
                },
                if (model.tabs.size > 1) {
                    CardAction(Icons.Outlined.Close, stringResource(R.string.close_tab), isDestructive = true) {
                        model.closeTab(index)
                        onDismiss()
                    }
                } else {
                    null
                },
            ),
            contentColor = colors.onSurface,
        )
    }
}

/** One folder of the path: indented by its depth, the current one in the accent; long names wrap instead of being cut. */
@Composable
private fun CrumbRow(label: String, icon: ImageVector, isCurrent: Boolean, indent: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = if (isCurrent) colors.onPrimary else colors.onSurface
    Row(
        Modifier
            .padding(start = indent)
            .fillMaxWidth()
            .clip(RoundedCornerShape(LocalDesignShapes.current.inner))
            .background(if (isCurrent) colors.primary else TileDefaults.containerColor())
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (isCurrent) content else colors.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = content, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (isCurrent) {
            Text(stringResource(R.string.tab_path_current), style = MaterialTheme.typography.labelMedium, color = content)
        } else {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

private const val SCRIM_ALPHA = 0.55f
private val CARD_GAP = 8.dp
private val CARD_RADIUS = 24.dp
private val MIN_CARD_HEIGHT = 160.dp
private val INDENT = 14.dp
private const val MAX_INDENT_LEVELS = 5

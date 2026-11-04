package de.needanapp.needafile.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.BottomPillSpacer
import de.needanapp.design.components.ContentRow
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.SwitchRow
import de.needanapp.design.components.scrollbar
import de.needanapp.design.theme.ThemeSettings
import de.needanapp.needafile.R
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.system.AppIcon
import de.needanapp.needafile.system.AppIconColor
import de.needanapp.needafile.system.AppIconMotif
import de.needanapp.needafile.system.LauncherIcon

/** The launcher icon: one of the motifs, or the classic icon in one of its colors or following the accent color. */
@Composable
fun AppIconSettings(themeSettings: ThemeSettings, contentPadding: PaddingValues, nestedScrollConnection: NestedScrollConnection) {
    val context = LocalContext.current
    val config = context.config
    var motif by remember { mutableStateOf(config.appIconMotif) }
    var choice by remember { mutableStateOf(config.appIconChoice) }
    val isClassic = AppIconMotif.fromKey(motif) == null
    val followsAccent = choice == LauncherIcon.FOLLOW_ACCENT
    // recomputed with the theme, so "like the accent color" shows the color it currently picks
    val shownColor = remember(choice, themeSettings) { LauncherIcon.resolveColor(context, themeSettings) }

    fun chooseMotif(value: String) {
        motif = value
        config.appIconMotif = value
    }

    fun choose(value: String) {
        choice = value
        config.appIconChoice = value
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize().scrollbar(listState, contentPadding).nestedScroll(nestedScrollConnection),
    ) {
        item {
            SettingsGroup(null) {
                row {
                    ContentRow(it, stringResource(R.string.app_icon_motif)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppIconMotif.entries.forEach { entry ->
                                MotifChoice(entry, stringResource(entry.label), isSelected = entry.name == motif) { chooseMotif(entry.name) }
                            }
                            MotifChoice(shownColor, stringResource(R.string.app_icon_classic), isSelected = isClassic) { chooseMotif(LauncherIcon.CLASSIC) }
                        }
                    }
                }
            }
        }
        if (isClassic) {
            item {
                SettingsGroup(null) {
                    row {
                        SwitchRow(
                            it,
                            stringResource(R.string.app_icon_follow_accent),
                            checked = followsAccent,
                            onCheckedChange = { follow -> choose(if (follow) LauncherIcon.FOLLOW_ACCENT else shownColor.name) },
                            summary = stringResource(R.string.app_icon_follow_accent_summary),
                        )
                    }
                    row {
                        ContentRow(it, stringResource(R.string.app_icon_color)) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AppIconColor.entries.forEach { color ->
                                    IconChoice(color, isSelected = color == shownColor) { choose(color.name) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.app_icon_hint, stringResource(R.string.brand_name)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            )
        }
        item { BottomPillSpacer() }
    }
}

/** A motif (or the classic icon) with its name below; the chosen one framed in the accent color. */
@Composable
private fun MotifChoice(icon: AppIcon, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(MOTIF_CELL.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .border(if (isSelected) BorderStroke(3.dp, colors.primary) else BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(MOTIF_FRAME_RADIUS.dp))
                .padding(5.dp),
        ) {
            AppIconImage(icon, ICON_SIZE.dp)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) colors.primary else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun IconChoice(color: AppIconColor, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(color.label)
    Box(
        modifier = Modifier
            .size(ICON_SIZE.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHighest)
            .border(if (isSelected) BorderStroke(3.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant), CircleShape)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AppIconImage(color, ICON_SIZE.dp)
    }
}

/**
 * The launcher icon as it looks: a motif on its rounded plate filling [size], or the classic glyph, which fills about
 * half of its 108dp canvas and is drawn [glyphZoom] times larger than [size].
 */
@Composable
fun AppIconImage(icon: AppIcon, size: Dp, modifier: Modifier = Modifier, glyphZoom: Float = 1.6f) {
    when (icon) {
        is AppIconMotif -> Box(modifier.size(size).clip(RoundedCornerShape(percent = MOTIF_CORNER_PERCENT)), contentAlignment = Alignment.Center) {
            // the visible 72dp of the 108dp canvas fill the plate
            Image(painterResource(icon.background), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
            Image(painterResource(icon.foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
        }
        is AppIconColor -> Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Image(painterResource(icon.foreground), contentDescription = null, modifier = Modifier.requiredSize(size * glyphZoom))
        }
    }
}

private const val ICON_SIZE = 56
private const val MOTIF_CELL = 76
private const val MOTIF_FRAME_RADIUS = 20
private const val MOTIF_CORNER_PERCENT = 24

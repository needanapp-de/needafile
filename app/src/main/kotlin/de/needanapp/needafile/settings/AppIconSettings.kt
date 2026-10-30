package de.needanapp.needafile.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.BottomPillSpacer
import de.needanapp.design.components.ContentRow
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.SwitchRow
import de.needanapp.design.components.scrollbar
import de.needanapp.design.theme.ThemeSettings
import de.needanapp.needafile.R
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.system.AppIconColor
import de.needanapp.needafile.system.LauncherIcon

/** The launcher icon in one of its colors, or following the accent color. */
@Composable
fun AppIconSettings(themeSettings: ThemeSettings, contentPadding: PaddingValues, nestedScrollConnection: NestedScrollConnection) {
    val context = LocalContext.current
    val config = context.config
    var choice by remember { mutableStateOf(config.appIconChoice) }
    val followsAccent = choice == LauncherIcon.FOLLOW_ACCENT
    // recomputed with the theme, so "like the accent color" shows the color it currently picks
    val shown = remember(choice, themeSettings) { LauncherIcon.resolve(context, themeSettings) }

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
                    SwitchRow(
                        it,
                        stringResource(R.string.app_icon_follow_accent),
                        checked = followsAccent,
                        onCheckedChange = { follow -> choose(if (follow) LauncherIcon.FOLLOW_ACCENT else shown.name) },
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
                                IconChoice(color, isSelected = color == shown) { choose(color.name) }
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
        // the icon's glyph fills about half of its 108dp canvas, so it is drawn larger than the tile
        Image(painterResource(color.foreground), contentDescription = null, modifier = Modifier.requiredSize((ICON_SIZE * ICON_ZOOM).dp))
    }
}

private const val ICON_SIZE = 56
private const val ICON_ZOOM = 1.6f

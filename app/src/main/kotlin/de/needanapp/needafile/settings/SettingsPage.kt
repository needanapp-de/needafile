package de.needanapp.needafile.settings

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.BottomPillSpacer
import de.needanapp.design.components.ListRow
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.TileGrid
import de.needanapp.design.components.ToggleTile
import de.needanapp.design.components.scrollbar
import de.needanapp.design.settings.AppearanceSettings
import de.needanapp.design.theme.DesignStyle
import de.needanapp.design.theme.ThemeSettings
import de.needanapp.needafile.R
import de.needanapp.needafile.about.AboutActivity
import de.needanapp.needafile.backup.ExportBackupFlow
import de.needanapp.needafile.backup.ImportBackupFlow
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.formatDate
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.root.RootShell
import de.needanapp.needafile.security.LockType
import de.needanapp.needafile.security.ProtectionKind
import de.needanapp.needafile.security.Protections
import de.needanapp.needafile.security.setUpProtection
import de.needanapp.needafile.security.withProtection
import de.needanapp.needafile.system.LauncherIcon

/** Pages below the settings, shown in place of them. */
enum class SettingsSubPage(@StringRes val title: Int) {
    APPEARANCE(de.needanapp.design.R.string.nd_appearance),
    APP_ICON(R.string.app_icon),
}

/** The styles needafile lets choose from: NeedAnApp stays the default, Myzel can be chosen. */
internal val APP_STYLES = listOf(DesignStyle.NEEDANAPP, DesignStyle.MYZEL)

/** needafile's settings: the switches as compact tiles, the rest as grouped lists; the appearance comes from the design package. */
@Composable
fun SettingsPage(
    themeSettings: ThemeSettings,
    subPage: SettingsSubPage?,
    onSubPage: (SettingsSubPage) -> Unit,
    contentPadding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    onSettingsChanged: () -> Unit,
    onBackupRestored: () -> Unit,
    onShowIntroduction: () -> Unit,
) {
    when (subPage) {
        SettingsSubPage.APPEARANCE -> AppearanceSettings(
            themeSettings,
            contentPadding = contentPadding,
            nestedScrollConnection = nestedScrollConnection,
            styles = APP_STYLES,
        )

        SettingsSubPage.APP_ICON -> AppIconSettings(themeSettings, contentPadding, nestedScrollConnection)

        null -> SettingsList(themeSettings, onSubPage, contentPadding, nestedScrollConnection, onSettingsChanged, onBackupRestored, onShowIntroduction)
    }
}

@Composable
private fun SettingsList(
    themeSettings: ThemeSettings,
    onSubPage: (SettingsSubPage) -> Unit,
    contentPadding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    onSettingsChanged: () -> Unit,
    onBackupRestored: () -> Unit,
    onShowIntroduction: () -> Unit,
) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val config = activity.config

    var pressBackTwice by remember { mutableStateOf(config.pressBackTwice) }
    var swipePages by remember { mutableStateOf(config.swipePages) }
    var autoGallery by remember { mutableStateOf(config.autoGallery) }
    var skipDeleteConfirmation by remember { mutableStateOf(config.skipDeleteConfirmation) }
    var isProtected by remember { mutableStateOf(Protections.get(activity, ProtectionKind.APP) != null) }
    var rootAccess by remember { mutableStateOf(config.enableRootAccess) }
    var showDateFormat by remember { mutableStateOf(false) }
    // changes with the format picked in its sheet
    var dateExample by remember { mutableStateOf(System.currentTimeMillis().formatDate(activity)) }
    var showExport by remember { mutableStateOf(false) }
    // saved with the screen, so the file picker's answer still arrives when the screen is rebuilt meanwhile
    var showImport by rememberSaveable { mutableStateOf(false) }

    fun toggleProtection() {
        if (isProtected) {
            activity.withProtection(ProtectionKind.APP) {
                Protections.set(activity, ProtectionKind.APP, null)
                isProtected = false
            }
        } else {
            activity.setUpProtection { protection ->
                Protections.set(activity, ProtectionKind.APP, protection)
                isProtected = true
                val done = if (protection.type == LockType.BIOMETRIC) R.string.fingerprint_setup_successfully else R.string.protection_setup_successfully
                activity.toast(done)
            }
        }
    }

    fun toggleRoot(value: Boolean) {
        if (value) {
            // the root manager may ask first, so this waits in the background
            inBackground {
                val granted = RootShell.requestAccess()
                activity.runOnUiThread {
                    rootAccess = granted
                    config.enableRootAccess = granted
                }
            }
        } else {
            rootAccess = false
            config.enableRootAccess = false
        }
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize().scrollbar(listState, contentPadding).nestedScroll(nestedScrollConnection),
    ) {
        item {
            TileGrid(Modifier.padding(horizontal = 16.dp)) {
                label(stringResource(R.string.general_settings))
                line(
                    { m, shape ->
                        ToggleTile(stringResource(R.string.tile_press_back_twice), pressBackTwice, { value ->
                            pressBackTwice = value
                            config.pressBackTwice = value
                        }, shape, m, Icons.Outlined.KeyboardDoubleArrowLeft, iconInCircle = true, description = stringResource(R.string.press_back_twice))
                    },
                    { m, shape ->
                        ToggleTile(stringResource(R.string.tile_swipe_pages), swipePages, { value ->
                            swipePages = value
                            config.swipePages = value
                        }, shape, m, Icons.Outlined.Swipe, iconInCircle = true, description = stringResource(R.string.swipe_pages))
                    },
                )
                label(stringResource(R.string.settings_files))
                line(
                    { m, shape ->
                        ToggleTile(stringResource(R.string.tile_auto_gallery), autoGallery, { value ->
                            autoGallery = value
                            config.autoGallery = value
                            onSettingsChanged()
                        }, shape, m, Icons.Outlined.PhotoLibrary, iconInCircle = true, description = stringResource(R.string.auto_gallery))
                    },
                    { m, shape ->
                        ToggleTile(stringResource(R.string.delete_without_asking), skipDeleteConfirmation, { value ->
                            skipDeleteConfirmation = value
                            config.skipDeleteConfirmation = value
                        }, shape, m, Icons.Outlined.DeleteForever, iconInCircle = true)
                    },
                )
                label(stringResource(R.string.security))
                line(
                    listOfNotNull(
                        { m, shape ->
                            ToggleTile(
                                stringResource(R.string.tile_protect_app),
                                isProtected,
                                { toggleProtection() },
                                shape,
                                m,
                                Icons.Outlined.PhonelinkLock,
                                iconInCircle = true,
                                description = stringResource(R.string.protect_app),
                            )
                        },
                        if (config.isRootAvailable) {
                            { m, shape ->
                                ToggleTile(stringResource(R.string.enable_root_access), rootAccess, ::toggleRoot, shape, m, Icons.Outlined.Tag, iconInCircle = true)
                            }
                        } else {
                            null
                        },
                    ),
                )
            }
        }

        item {
            SettingsGroup(stringResource(R.string.settings_more)) {
                row {
                    ListRow(it, stringResource(R.string.change_date_and_time_format), summary = dateExample, icon = Icons.Outlined.CalendarMonth, onClick = {
                        showDateFormat = true
                    })
                }
                row {
                    ListRow(
                        it,
                        stringResource(R.string.backup_export),
                        summary = stringResource(R.string.backup_export_summary),
                        icon = Icons.Outlined.Upload,
                        onClick = { showExport = true },
                    )
                }
                row {
                    ListRow(
                        it,
                        stringResource(R.string.backup_import),
                        summary = stringResource(R.string.backup_import_summary),
                        icon = Icons.Outlined.Download,
                        onClick = { showImport = true },
                    )
                }
            }
        }

        item {
            SettingsGroup(null) {
                row {
                    ListRow(
                        it,
                        stringResource(de.needanapp.design.R.string.nd_appearance),
                        summary = stringResource(R.string.appearance_summary),
                        icon = Icons.Outlined.Palette,
                        onClick = { onSubPage(SettingsSubPage.APPEARANCE) },
                    )
                }
                row {
                    val icon = remember(themeSettings) { LauncherIcon.resolve(activity, themeSettings) }
                    val followsAccent = config.appIconChoice == LauncherIcon.FOLLOW_ACCENT
                    ListRow(
                        it,
                        stringResource(R.string.app_icon),
                        summary = if (followsAccent) {
                            stringResource(R.string.label_value, stringResource(R.string.app_icon_follow_accent), stringResource(icon.label))
                        } else {
                            stringResource(icon.label)
                        },
                        icon = Icons.Outlined.Apps,
                        onClick = { onSubPage(SettingsSubPage.APP_ICON) },
                        trailing = {
                            Image(painterResource(icon.foreground), contentDescription = null, modifier = Modifier.size(40.dp).wrapContentSize(unbounded = true).requiredSize(64.dp))
                        },
                    )
                }
            }
        }

        item {
            SettingsGroup(null) {
                row {
                    ListRow(
                        it,
                        stringResource(R.string.onboarding_show),
                        summary = stringResource(R.string.onboarding_show_summary),
                        icon = Icons.Outlined.TipsAndUpdates,
                        onClick = onShowIntroduction,
                    )
                }
                row {
                    ListRow(it, stringResource(R.string.about), icon = Icons.Outlined.Info, onClick = {
                        activity.startActivity(Intent(activity, AboutActivity::class.java))
                    })
                }
            }
        }

        item { BottomPillSpacer() }
    }

    if (showDateFormat) {
        DateTimeFormatSheet(onChanged = {
            dateExample = System.currentTimeMillis().formatDate(activity)
            onSettingsChanged()
        }, onDismiss = { showDateFormat = false })
    }
    if (showExport) ExportBackupFlow(onDismiss = { showExport = false })
    if (showImport) ImportBackupFlow(onDismiss = { showImport = false }, onRestored = onBackupRestored)
}

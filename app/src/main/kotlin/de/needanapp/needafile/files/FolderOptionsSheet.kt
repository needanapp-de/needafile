package de.needanapp.needafile.files

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DensityLarge
import androidx.compose.material.icons.outlined.DensityMedium
import androidx.compose.material.icons.outlined.DensitySmall
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.ChoiceTile
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.StepperTile
import de.needanapp.design.components.SwitchTile
import de.needanapp.design.components.TileCard
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.Sorting
import de.needanapp.needafile.core.ViewType
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.vault.VaultBiometrics
import de.needanapp.needafile.vault.VaultStore
import de.needanapp.needafile.vault.vaultKeyLines
import de.needanapp.needafile.vault.getVaultLocation
import de.needanapp.needafile.vault.isVaultPath

/**
 * The folder options as tiles: sorting, view with entry size and grouping, hidden files and the home folder; inside a
 * vault also its locking and passwords.
 */
@Composable
internal fun FolderOptionsSheet(model: FileBrowserModel, onDismiss: () -> Unit, show: (FileDialog) -> Unit) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val config = activity.config
    val path = model.currentPath
    var sorting by remember { mutableIntStateOf(config.getFolderSorting(path)) }
    var sortingForFolder by remember { mutableStateOf(config.hasCustomSorting(path)) }
    var showHidden by remember { mutableStateOf(config.showHidden) }

    fun saveSorting(value: Int, forFolder: Boolean) {
        sorting = value
        sortingForFolder = forFolder
        if (forFolder) {
            config.saveCustomSorting(path, value)
        } else {
            config.removeCustomSorting(path)
            config.sorting = value
        }
        model.refresh()
    }

    OptionsSheet(onDismiss = onDismiss, title = stringResource(R.string.folder_options), skipPartiallyExpanded = true) {
        Column(Modifier.verticalScrollWithBar()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SortingCard(sorting, sortingForFolder, ::saveSorting)
                ViewCard(model)
                TileCard(null) {
                    line({ m, shape ->
                        SwitchTile(stringResource(R.string.show_hidden_files), showHidden, { value ->
                            showHidden = value
                            config.showHidden = value
                            model.refresh()
                        }, shape, m, Icons.Outlined.VisibilityOff)
                    })
                }
                if (!path.isVirtualPath() && config.homeFolder != path) {
                    TileCard(stringResource(R.string.this_folder)) {
                        line({ m, shape ->
                            ActionTile(stringResource(R.string.set_as_home_folder), {
                                config.homeFolder = path
                                activity.toast(R.string.home_folder_updated)
                                onDismiss()
                            }, shape, m, Icons.Outlined.Home, inline = true)
                        })
                    }
                }
                val vaultLocation = if (path.isVaultPath()) path.getVaultLocation() else model.lockedVault
                if (vaultLocation != null && !model.isPicking) {
                    VaultOptions(vaultLocation, isUnlocked = path.isVaultPath(), model, onDismiss, show)
                }
            }
        }
    }
}

/** Sort by name, date, size or type, ascending or descending, numbers by value; for all folders or just this one. */
@Composable
private fun SortingCard(sorting: Int, forFolder: Boolean, save: (Int, Boolean) -> Unit) {
    val kind = SORT_KINDS.firstOrNull { sorting and it.flag != 0 } ?: SORT_KINDS.first()
    val isDescending = sorting and Sorting.DESCENDING != 0
    val isNumeric = sorting and Sorting.NUMBERS_BY_VALUE != 0
    TileCard(stringResource(R.string.sort_by)) {
        line(
            SORT_KINDS.map { option ->
                { m, shape ->
                    ChoiceTile(stringResource(option.label), option == kind, {
                        save(option.flag or (sorting and (Sorting.DESCENDING or Sorting.NUMBERS_BY_VALUE)), forFolder)
                    }, shape, m, option.icon, stacked = true, iconInCircle = true)
                }
            },
        )
        line(
            { m, shape ->
                ChoiceTile(stringResource(R.string.ascending), !isDescending, { save(sorting and Sorting.DESCENDING.inv(), forFolder) }, shape, m, Icons.Outlined.ArrowUpward)
            },
            { m, shape ->
                ChoiceTile(stringResource(R.string.descending), isDescending, { save(sorting or Sorting.DESCENDING, forFolder) }, shape, m, Icons.Outlined.ArrowDownward)
            },
        )
        if (kind.flag == Sorting.BY_NAME) {
            line({ m, shape ->
                SwitchTile(stringResource(R.string.sort_numeric), isNumeric, { numeric ->
                    save(if (numeric) sorting or Sorting.NUMBERS_BY_VALUE else sorting and Sorting.NUMBERS_BY_VALUE.inv(), forFolder)
                }, shape, m, Icons.Outlined.Pin, summary = stringResource(R.string.sort_numeric_summary))
            })
        }
        line({ m, shape ->
            SwitchTile(stringResource(R.string.use_for_this_folder), forFolder, { save(sorting, it) }, shape, m, Icons.Outlined.Folder)
        })
    }
}

/** List, details, grid or gallery, the size of the entries, names in the grid, cards, grouping; for all folders or this one. */
@Composable
private fun ViewCard(model: FileBrowserModel) {
    val config = LocalContext.current.config
    val view = model.view
    val screenWidth = LocalConfiguration.current.screenWidthDp.toFloat()
    var showNames by remember { mutableStateOf(config.displayFilenames) }
    var asCards by remember { mutableStateOf(config.listAsCards) }
    TileCard(stringResource(R.string.view)) {
        line(
            VIEW_TYPES.map { type ->
                { m, shape ->
                    ChoiceTile(stringResource(viewTypeLabel(type)), view.type == type, {
                        if (type != view.type) model.changeView(view.copy(type = type, zoom = config.generalZoom(type)))
                    }, shape, m, viewTypeIcon(type), stacked = true, iconInCircle = true)
                }
            },
        )
        if (model.isAutoGallery) label(stringResource(R.string.auto_gallery_active))
        label(stringResource(R.string.view_zoom))
        if (view.type == ViewType.GRID || view.type == ViewType.GALLERY) {
            // the same as pinching the list, for everyone who can't or doesn't want to; a higher level has fewer, larger cells
            val levels = zoomLevels(view.type)
            val columns = gridColumns(view.type, view.zoom, screenWidth)
            line({ m, shape ->
                StepperTile(
                    pluralStringResource(R.plurals.grid_columns, columns, columns),
                    onDecrease = if (view.zoom > 0) ({ model.changeView(view.copy(zoom = view.zoom - 1)) }) else null,
                    onIncrease = if (view.zoom < levels - 1) ({ model.changeView(view.copy(zoom = view.zoom + 1)) }) else null,
                    decreaseLabel = stringResource(R.string.view_zoom_smaller),
                    increaseLabel = stringResource(R.string.view_zoom_larger),
                    shape = shape,
                    modifier = m,
                )
            })
        } else {
            line(
                ROW_ZOOMS.map { (zoom, label, icon) ->
                    { m, shape ->
                        ChoiceTile(stringResource(label), view.zoom == zoom, { model.changeView(view.copy(zoom = zoom)) }, shape, m, icon, stacked = true, iconInCircle = true)
                    }
                },
            )
        }
        if (view.type == ViewType.GRID) {
            line({ m, shape ->
                SwitchTile(stringResource(R.string.show_file_names), showNames, { value ->
                    showNames = value
                    config.displayFilenames = value
                    model.refresh()
                }, shape, m, Icons.Outlined.Title)
            })
        }
        line({ m, shape ->
            SwitchTile(stringResource(R.string.list_as_cards), asCards, { value ->
                asCards = value
                config.listAsCards = value
                model.refresh()
            }, shape, m, Icons.Outlined.ViewAgenda)
        })
        label(stringResource(R.string.group_by))
        line(
            GroupBy.entries.map { groupBy ->
                { m, shape ->
                    ChoiceTile(stringResource(groupBy.label), view.groupBy == groupBy, { model.changeView(view.copy(groupBy = groupBy)) }, shape, m, groupBy.icon(), stacked = true, iconInCircle = true)
                }
            },
        )
        line({ m, shape ->
            SwitchTile(
                stringResource(R.string.use_for_this_folder),
                view.isCustom,
                { forFolder -> model.changeView(view.copy(isCustom = forFolder)) },
                shape,
                m,
                Icons.Outlined.Folder,
                summary = stringResource(R.string.use_for_this_folder_view_summary),
            )
        })
    }
}

/** Locking, passwords and recovery key of the vault the open folder belongs to (or, locked, is). */
@Composable
private fun VaultOptions(location: String, isUnlocked: Boolean, model: FileBrowserModel, onDismiss: () -> Unit, show: (FileDialog) -> Unit) {
    // inside the sheet the context is the sheet's window, not the activity
    val context = LocalContext.current
    val hasFingerprint = remember(location) { VaultStore.get(context, location)?.hasFingerprint == true }
    TileCard(stringResource(R.string.vault)) {
        if (isUnlocked) {
            line({ m, shape ->
                ActionTile(stringResource(R.string.vault_lock), {
                    onDismiss()
                    model.lockVault(location)
                    context.toast(R.string.vault_locked_toast)
                }, shape, m, Icons.Outlined.Lock, inline = true)
            })
        } else {
            line({ m, shape ->
                ActionTile(stringResource(R.string.vault_unlock), {
                    onDismiss()
                    model.requestVaultUnlock(location)
                }, shape, m, Icons.Outlined.LockOpen, inline = true)
            })
        }
        vaultKeyLines(location, hasFingerprint, onDialog = show) {
            VaultBiometrics.forget(context, location)
            context.toast(R.string.vault_fingerprint_forgotten)
            onDismiss()
        }
    }
}

private class SortKind(val flag: Int, val label: Int, val icon: ImageVector)

private val SORT_KINDS = listOf(
    SortKind(Sorting.BY_NAME, R.string.name, Icons.Outlined.SortByAlpha),
    SortKind(Sorting.BY_DATE, R.string.sort_date, Icons.Outlined.CalendarMonth),
    SortKind(Sorting.BY_SIZE, R.string.size, Icons.Outlined.Straighten),
    SortKind(Sorting.BY_EXTENSION, R.string.sort_type, Icons.Outlined.Category),
)

private val ROW_ZOOMS = listOf(
    Triple(ZOOM_COMPACT, R.string.zoom_compact, Icons.Outlined.DensitySmall),
    Triple(ZOOM_NORMAL, R.string.zoom_normal, Icons.Outlined.DensityMedium),
    Triple(ZOOM_LARGE, R.string.zoom_large, Icons.Outlined.DensityLarge),
)

private fun viewTypeIcon(type: Int) = when (type) {
    ViewType.DETAILS -> Icons.Outlined.TableRows
    ViewType.GRID -> Icons.Outlined.GridView
    ViewType.GALLERY -> Icons.Outlined.PhotoLibrary
    else -> Icons.AutoMirrored.Outlined.ViewList
}

private fun GroupBy.icon() = when (this) {
    GroupBy.NONE -> Icons.Outlined.Block
    GroupBy.DATE -> Icons.Outlined.CalendarMonth
    GroupBy.TYPE -> Icons.Outlined.Category
    GroupBy.SIZE -> Icons.Outlined.Straighten
    GroupBy.NAME -> Icons.Outlined.Abc
}

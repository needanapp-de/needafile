package de.needanapp.needafile.archive

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.CardSpacing
import de.needanapp.design.components.CardStatus
import de.needanapp.design.components.ChoiceTile
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.Detail
import de.needanapp.design.components.ListRow
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.SecretTextField
import de.needanapp.design.components.SegmentedRow
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.SignalButton
import de.needanapp.design.components.SliderRow
import de.needanapp.design.components.StatusTile
import de.needanapp.design.components.StatusTone
import de.needanapp.design.components.SwitchRow
import de.needanapp.design.components.SwitchTile
import de.needanapp.design.components.TextFieldRow
import de.needanapp.design.components.TextFieldTile
import de.needanapp.design.components.TileCard
import de.needanapp.design.components.TileCardScope
import de.needanapp.design.components.detailLines
import de.needanapp.design.components.rememberSectionState
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.needafile.R
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.displayPath
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.getFilenameFromPath
import de.needanapp.needafile.core.getParentPath
import de.needanapp.needafile.core.isAValidFilename
import de.needanapp.needafile.files.FolderPick
import de.needanapp.needafile.files.FolderPickerSheet
import de.needanapp.needafile.settings.config
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import net.lingala.zip4j.model.enums.AesKeyStrength

/**
 * Name, folder and format (zip or 7z) of a new archive of [items], with an optional password. A line below explains how
 * the archive will be encrypted, so the difference between zip and 7z is visible before creating it. "Advanced"
 * (collapsed at first) offers the compression strength, for zip also the AES key length and splitting into parts, for
 * 7z solid compression. All options but the password start as they were for the last archive. While the password is
 * hidden it has to be typed a second time; shown, it can be checked by eye, so the second field goes.
 */
@Composable
fun CompressSheet(items: List<FileEntry>, onDismiss: () -> Unit, onCompress: (CompressRequest) -> Unit) {
    val context = LocalContext.current
    val first = items.first()
    val baseName = remember(first) { if (!first.isDirectory && first.name.contains('.')) first.name.substringBeforeLast('.') else first.name }
    var name by rememberSaveable { mutableStateOf(baseName) }
    var folder by rememberSaveable { mutableStateOf(first.path.getParentPath()) }
    val last = remember { context.config.compressOptions }
    var format by rememberSaveable { mutableStateOf(last.format) }
    var isProtected by rememberSaveable { mutableStateOf(last.isProtected) }
    // passwords stay out of the saved instance state and the settings
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var passwordShown by rememberSaveable { mutableStateOf(false) }
    var encryptNames by rememberSaveable { mutableStateOf(last.encryptFileNames) }
    var strength by rememberSaveable { mutableStateOf(last.strength) }
    var aesKeyStrength by rememberSaveable { mutableStateOf(last.aesKeyStrength) }
    var split by rememberSaveable { mutableStateOf(last.isSplit) }
    var solid by rememberSaveable { mutableStateOf(last.isSolid) }
    var partSize by rememberSaveable { mutableLongStateOf(last.partSize) }
    var pickingFolder by remember { mutableStateOf(false) }
    val sections = rememberSectionState(listOf(ADVANCED_SECTION))

    val extension = format.extensions.first()
    val fileName = "${name.trim()}$extension"
    val isSplit = format == ArchiveKind.ZIP && split
    val nameError = when {
        name.isBlank() -> null
        !name.trim().isAValidFilename() -> stringResource(R.string.invalid_name)
        File("$folder/$fileName").exists() -> stringResource(R.string.name_taken)
        // a split zip's first part would overwrite an existing one
        isSplit && File("$folder/${name.trim()}.z01").exists() -> stringResource(R.string.name_taken)
        else -> null
    }
    val passwordConfirmed = passwordShown || repeated == password
    val canCompress = name.isNotBlank() && nameError == null && (!isProtected || (password.isNotEmpty() && passwordConfirmed))
    val encryptionInfo = when {
        !isProtected -> R.string.archive_info_not_encrypted
        format == ArchiveKind.ZIP && aesKeyStrength == AesKeyStrength.KEY_STRENGTH_128 -> R.string.archive_info_zip_aes_128
        format == ArchiveKind.ZIP -> R.string.archive_info_zip_aes
        encryptNames -> R.string.archive_info_7z_names_encrypted
        else -> R.string.archive_info_7z_names_visible
    }

    OptionsSheet(onDismiss = onDismiss, title = stringResource(R.string.compress_as), skipPartiallyExpanded = true) {
        Column(Modifier.verticalScrollWithBar()) {
            SettingsGroup(null) {
                row {
                    TextFieldRow(
                        it,
                        name,
                        { value -> name = value },
                        stringResource(R.string.filename),
                        error = nameError,
                        supportingText = fileName,
                    )
                }
                row {
                    ListRow(it, stringResource(R.string.folder), summary = context.displayPath(folder), icon = Icons.Outlined.Folder, onClick = {
                        pickingFolder = true
                    })
                }
            }
            TileCard(stringResource(R.string.archive_info_format), CardSpacing) {
                line(
                    listOf(ArchiveKind.ZIP, ArchiveKind.SEVEN_ZIP).map { kind ->
                        { m, shape ->
                            ChoiceTile(
                                formatName(context, kind, null),
                                format == kind,
                                { format = kind },
                                shape,
                                m,
                                if (kind == ArchiveKind.ZIP) Icons.Outlined.FolderZip else Icons.Outlined.Archive,
                                stacked = true,
                                iconInCircle = true,
                                summary = stringResource(if (kind == ArchiveKind.ZIP) R.string.archive_zip_summary else R.string.archive_7z_summary),
                            )
                        }
                    },
                )
            }
            TileCard(stringResource(R.string.archive_info_encryption), CardSpacing) {
                line({ m, shape -> SwitchTile(stringResource(R.string.add_password), isProtected, { value -> isProtected = value }, shape, m, Icons.Outlined.Lock) })
                if (isProtected) {
                    line({ m, shape ->
                        TextFieldTile(
                            password,
                            { value -> password = value },
                            stringResource(R.string.password),
                            shape,
                            m,
                            isSecret = true,
                            revealed = passwordShown,
                            onRevealedChange = { shown -> passwordShown = shown },
                        )
                    })
                    if (!passwordShown) {
                        line({ m, shape ->
                            TextFieldTile(
                                repeated,
                                { value -> repeated = value },
                                stringResource(R.string.password_repeat),
                                shape,
                                m,
                                // a mismatch only shows once something is typed
                                error = stringResource(R.string.password_mismatch).takeIf { repeated.isNotEmpty() && repeated != password },
                                isSecret = true,
                                revealed = false,
                                // showing the second field shows the password instead, which makes it unneeded
                                onRevealedChange = { shown -> passwordShown = shown },
                            )
                        })
                    }
                    if (format == ArchiveKind.SEVEN_ZIP) {
                        line({ m, shape ->
                            SwitchTile(stringResource(R.string.archive_encrypt_file_names), encryptNames, { value -> encryptNames = value }, shape, m, Icons.Outlined.TextFields)
                        })
                    }
                }
            }
            SheetHint(stringResource(encryptionInfo))
            SettingsGroup(stringResource(R.string.archive_advanced), id = ADVANCED_SECTION, sectionState = sections, icon = Icons.Outlined.Tune) {
                row {
                    SliderRow(
                        it,
                        stringResource(R.string.compression_strength),
                        strength.ordinal.toFloat(),
                        onValueChange = { value -> strength = CompressionStrength.entries[value.roundToInt()] },
                        valueRange = 0f..CompressionStrength.entries.lastIndex.toFloat(),
                        valueText = stringResource(strength.label),
                        steps = CompressionStrength.entries.size - 2,
                        summary = stringResource(strength.summary),
                    )
                }
                if (format == ArchiveKind.SEVEN_ZIP) {
                    row {
                        SwitchRow(
                            it,
                            stringResource(R.string.archive_solid),
                            solid,
                            onCheckedChange = { value -> solid = value },
                            summary = stringResource(R.string.archive_solid_summary),
                        )
                    }
                }
                if (format == ArchiveKind.ZIP && isProtected) {
                    row {
                        SegmentedRow(
                            it,
                            stringResource(R.string.archive_key_length),
                            listOf(AesKeyStrength.KEY_STRENGTH_256, AesKeyStrength.KEY_STRENGTH_128),
                            aesKeyStrength,
                            label = { keyStrength ->
                                stringResource(if (keyStrength == AesKeyStrength.KEY_STRENGTH_128) R.string.archive_aes_128 else R.string.archive_aes_256)
                            },
                            onSelect = { keyStrength -> aesKeyStrength = keyStrength },
                        )
                    }
                }
                if (format == ArchiveKind.ZIP) row {
                    SwitchRow(
                        it,
                        stringResource(R.string.archive_split),
                        split,
                        onCheckedChange = { value -> split = value },
                        summary = stringResource(R.string.archive_split_summary),
                    )
                }
                if (isSplit) {
                    row {
                        SegmentedRow(
                            it,
                            stringResource(R.string.archive_part_size),
                            ZIP_PART_SIZES,
                            partSize,
                            label = { size -> size.formatSize() },
                            onSelect = { size -> partSize = size },
                        )
                    }
                }
            }
            if (sections.isExpanded(ADVANCED_SECTION)) {
                if (isSplit) SheetHint(stringResource(R.string.archive_split_names, name.trim()))
            } else {
                // the options are remembered, so what's set stays visible while the section is closed
                SheetHint(advancedSummary(context, format, isProtected, strength, aesKeyStrength, isSplit, partSize, solid))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                SignalButton(enabled = canCompress, onClick = {
                    onDismiss()
                    context.config.compressOptions = CompressOptions(format, isProtected, encryptNames, strength, aesKeyStrength, split, partSize, solid)
                    val password = password.takeIf { isProtected }
                    val encrypt7zNames = format == ArchiveKind.SEVEN_ZIP && password != null && encryptNames
                    onCompress(
                        CompressRequest(
                            "$folder/$fileName",
                            format,
                            password,
                            encrypt7zNames,
                            strength,
                            aesKeyStrength = aesKeyStrength,
                            partSize = partSize.takeIf { isSplit },
                            solid = solid,
                        ),
                    )
                }) { Text(stringResource(R.string.compress)) }
            }
        }
    }

    if (pickingFolder) {
        FolderPickerSheet(FolderPick(R.string.select_folder, R.string.use_this_folder, folder) { folder = it }, onDismiss = { pickingFolder = false })
    }
}

private const val ADVANCED_SECTION = "compress_advanced"

/** The advanced options in one line, e.g. "Compression: Maximum · AES-128 · Parts of 100 MB". */
private fun advancedSummary(
    context: Context,
    format: ArchiveKind,
    isProtected: Boolean,
    strength: CompressionStrength,
    aesKeyStrength: AesKeyStrength,
    isSplit: Boolean,
    partSize: Long,
    solid: Boolean,
): String = listOfNotNull(
    context.getString(R.string.archive_advanced_strength, context.getString(strength.label)),
    context.getString(R.string.archive_aes_128).takeIf { format == ArchiveKind.ZIP && isProtected && aesKeyStrength == AesKeyStrength.KEY_STRENGTH_128 },
    context.getString(R.string.archive_advanced_parts, partSize.formatSize()).takeIf { format == ArchiveKind.ZIP && isSplit },
    context.getString(R.string.archive_solid).takeIf { format == ArchiveKind.SEVEN_ZIP && solid },
).joinToString(" · ")

/** A note below a group of the sheet, e.g. how the archive will be encrypted. */
@Composable
private fun SheetHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
    )
}

/** Asks for the password of [path]; [isWrong] says that the previous attempt failed. */
@Composable
fun ArchivePasswordPrompt(path: String, isWrong: Boolean, onPassword: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var passwordShown by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    fun confirm() {
        if (password.isNotEmpty()) onPassword(password)
    }

    ContentDialog(
        title = path.getFilenameFromPath(),
        confirmLabel = stringResource(R.string.ok),
        onConfirm = ::confirm,
        onDismiss = onCancel,
        confirmEnabled = password.isNotEmpty(),
        icon = Icons.Outlined.Lock,
    ) {
        Text(
            stringResource(if (isWrong) R.string.archive_wrong_password_retry else R.string.archive_password_required),
            color = if (isWrong) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        Spacer(Modifier.height(12.dp))
        SecretTextField(
            password,
            { password = it },
            stringResource(R.string.password),
            modifier = Modifier.focusRequester(focusRequester),
            onDone = ::confirm,
        )
    }
}

/** How well an archive protects its content. */
internal enum class Protection { NONE, WEAK, STRONG }

/** The details of an archive, split into the archive itself and its encryption. */
internal data class ArchiveDetails(val general: List<Detail>, val encryption: List<Detail>, val protection: Protection)

/**
 * The archive as cards of tiles: format and content, then the encryption, led by a tile that tells at a glance how
 * well the content is protected. Without encryption that tile leads the first card instead.
 */
@Composable
internal fun ArchiveDetailCards(info: ArchiveInfo) {
    val context = LocalContext.current
    val details = remember(info) { archiveDetails(context, info) }
    val status = archiveStatus(details.protection)
    val isOpen = details.protection == Protection.NONE
    TileCard(stringResource(R.string.card_archive), CardSpacing) {
        if (isOpen) line({ m, shape -> StatusTile(status, shape, m) })
        detailLines(details.general)
    }
    if (!isOpen) {
        TileCard(stringResource(R.string.archive_info_encryption), CardSpacing) {
            line({ m, shape -> StatusTile(status, shape, m) })
            detailLines(details.encryption)
        }
    }
}

/** The same as [ArchiveDetailCards], as labeled lines of tiles, e.g. in the boxes of a card. */
@Composable
// not restartable: what it reads must redo the whole card, which lays out the lines it adds
@NonRestartableComposable
internal fun TileCardScope.archiveDetailLines(details: ArchiveDetails) {
    val status = archiveStatus(details.protection)
    val isOpen = details.protection == Protection.NONE
    label(stringResource(R.string.card_archive))
    if (isOpen) line({ m, shape -> StatusTile(status, shape, m) })
    detailLines(details.general)
    if (!isOpen) {
        label(stringResource(R.string.archive_info_encryption))
        line({ m, shape -> StatusTile(status, shape, m) })
        detailLines(details.encryption)
    }
}

/** How well an archive protects its content, as a state at a glance. */
@Composable
private fun archiveStatus(protection: Protection): CardStatus = when (protection) {
    Protection.NONE -> CardStatus(stringResource(R.string.archive_status_none), Icons.Outlined.LockOpen, StatusTone.NEUTRAL)
    Protection.WEAK -> CardStatus(stringResource(R.string.archive_status_weak), Icons.Outlined.GppMaybe, StatusTone.ERROR)
    Protection.STRONG -> CardStatus(stringResource(R.string.archive_status_strong), Icons.Outlined.Lock, StatusTone.POSITIVE)
}

internal fun archiveDetails(context: Context, info: ArchiveInfo): ArchiveDetails {
    val general = ArrayList<Detail>()
    val encryptionDetails = ArrayList<Detail>()
    fun add(label: Int, value: String, isWarning: Boolean = false) = general.add(Detail(context.getString(label), value, isWarning))
    fun addEncryption(label: Int, value: String, isWarning: Boolean = false) = encryptionDetails.add(Detail(context.getString(label), value, isWarning))

    add(R.string.archive_info_format, formatName(context, info.kind, info.formatVersion))
    if (info.fileCount != null && info.folderCount != null) {
        val files = context.resources.getQuantityString(R.plurals.archive_file_count, info.fileCount, info.fileCount)
        val folders = context.resources.getQuantityString(R.plurals.archive_folder_count, info.folderCount, info.folderCount)
        add(R.string.archive_info_content, context.getString(R.string.archive_info_files_and_folders, files, folders))
    } else if (info.encryption.hidesFileNames) {
        add(R.string.archive_info_content, context.getString(R.string.archive_info_content_hidden))
    }
    info.unpackedSize?.let { add(R.string.archive_info_unpacked_size, it.formatSize()) }
    info.method?.takeIf { it.isNotBlank() }?.let { add(R.string.archive_info_method, it) }

    val protection = when (val encryption = info.encryption) {
        ArchiveEncryption.None -> Protection.NONE
        ArchiveEncryption.ZipCrypto -> {
            // the weak zip encryption is pointed out
            addEncryption(R.string.archive_info_encryption, context.getString(R.string.archive_info_encryption_zipcrypto), isWarning = true)
            addEncryption(R.string.archive_info_file_names, context.getString(R.string.archive_info_file_names_visible_zip))
            Protection.WEAK
        }

        is ArchiveEncryption.ZipAes -> {
            addEncryption(R.string.archive_info_encryption, context.getString(R.string.archive_info_encryption_zip_aes, encryption.keyBits, encryption.version))
            addEncryption(R.string.archive_info_file_names, context.getString(R.string.archive_info_file_names_visible_zip))
            Protection.STRONG
        }

        is ArchiveEncryption.SevenZipAes -> {
            addEncryption(R.string.archive_info_encryption, context.getString(R.string.archive_info_encryption_7z_aes))
            encryption.cyclesPower?.let { power ->
                val rounds = NumberFormat.getIntegerInstance().format(1L shl power)
                addEncryption(R.string.archive_info_key_derivation, context.getString(R.string.archive_info_key_derivation_value, power, rounds))
            }
            addEncryption(R.string.archive_info_file_names, context.getString(if (encryption.fileNamesEncrypted) R.string.archive_info_file_names_encrypted else R.string.archive_info_file_names_visible))
            Protection.STRONG
        }

        is ArchiveEncryption.RarAes -> {
            addEncryption(R.string.archive_info_encryption, context.getString(R.string.archive_info_encryption_rar_aes, encryption.keyBits))
            addEncryption(R.string.archive_info_file_names, context.getString(if (encryption.fileNamesEncrypted) R.string.archive_info_file_names_encrypted else R.string.archive_info_file_names_visible))
            Protection.STRONG
        }
    }
    return ArchiveDetails(general, encryptionDetails, protection)
}

private fun formatName(context: Context, kind: ArchiveKind, version: Int?) = when (kind) {
    ArchiveKind.ZIP -> context.getString(R.string.archive_format_zip)
    ArchiveKind.SEVEN_ZIP -> context.getString(R.string.archive_format_7z)
    ArchiveKind.RAR -> if (version != null) context.getString(R.string.archive_format_rar_version, version) else context.getString(R.string.archive_format_rar)
    else -> kind.extensions.first().removePrefix(".").uppercase(Locale.ROOT)
}

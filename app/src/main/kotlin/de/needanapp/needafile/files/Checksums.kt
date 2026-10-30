package de.needanapp.needafile.files

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.CardStatus
import de.needanapp.design.components.Detail
import de.needanapp.design.components.DetailTile
import de.needanapp.design.components.SignalLinearProgressIndicator
import de.needanapp.design.components.StatusTile
import de.needanapp.design.components.StatusTone
import de.needanapp.design.components.TileCardScope
import de.needanapp.design.components.TileDefaults
import de.needanapp.needafile.R
import de.needanapp.needafile.errors.describeError
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The checksums offered for files; all are calculated in one pass over the file. */
enum class ChecksumAlgorithm(@StringRes val label: Int, val javaName: String) {
    MD5(R.string.checksum_md5, "MD5"),
    SHA1(R.string.checksum_sha1, "SHA-1"),
    SHA256(R.string.checksum_sha256, "SHA-256"),
    SHA512(R.string.checksum_sha512, "SHA-512"),
}

/** What comparing with the clipboard found. */
private sealed interface Comparison {
    data class Match(val algorithm: ChecksumAlgorithm) : Comparison
    data object NoMatch : Comparison
    data object NoChecksum : Comparison
}

/**
 * The checksums of [file] in lower-case hex, read once for all algorithms; [onProgress] gets the share done (0–1).
 * Stops when the calling coroutine is cancelled, e.g. as the sheet closes.
 */
suspend fun calculateChecksums(file: File, onProgress: (Float) -> Unit): Map<ChecksumAlgorithm, String> = withContext(Dispatchers.IO) {
    val digests = ChecksumAlgorithm.entries.associateWith { MessageDigest.getInstance(it.javaName) }
    val total = file.length().coerceAtLeast(1)
    var done = 0L
    var lastReported = -1
    file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digests.values.forEach { it.update(buffer, 0, read) }
            done += read
            val percent = (done * PERCENT / total).toInt()
            if (percent != lastReported) {
                lastReported = percent
                onProgress(done.toFloat() / total)
            }
        }
    }
    digests.mapValues { (_, digest) -> digest.digest().joinToString("") { "%02x".format(it) } }
}

/** Hex values in [text] that are as long as one of the checksums, e.g. from "sha256sum" output or a download page. */
internal fun checksumCandidates(text: String): List<String> =
    HEX_VALUE.findAll(text).map { it.value.lowercase() }.filter { it.length in CHECKSUM_LENGTHS }.toList()

/**
 * The checksums of the single local file [path] as lines of tiles, calculated on request; comparing them with the
 * clipboard shows its result as a tile above them.
 */
@Composable
// not restartable: what it reads must redo the whole card, which lays out the lines it adds
@NonRestartableComposable
fun TileCardScope.checksumLines(path: String) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var requested by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var checksums by remember { mutableStateOf<Map<ChecksumAlgorithm, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var comparison by remember { mutableStateOf<Comparison?>(null) }

    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        try {
            checksums = calculateChecksums(File(path)) { progress = it }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = resources.getString(R.string.checksums_failed, context.describeError(e))
            requested = false
        }
    }

    val result = checksums
    val matched = (comparison as? Comparison.Match)?.algorithm
    val status = comparison?.let { found ->
        when (found) {
            is Comparison.Match -> CardStatus(describe(resources, found), Icons.Outlined.CheckCircle, StatusTone.POSITIVE)
            Comparison.NoMatch -> CardStatus(describe(resources, found), Icons.Outlined.ErrorOutline, StatusTone.ERROR)
            Comparison.NoChecksum -> CardStatus(describe(resources, found), Icons.Outlined.ContentPaste, StatusTone.NEUTRAL)
        }
    }
    status?.let { line({ m, shape -> StatusTile(it, shape, m) }) }
    when {
        result != null -> {
            ChecksumAlgorithm.entries.forEach { algorithm ->
                // the matching one in the main color
                line({ m, shape ->
                    DetailTile(Detail(stringResource(algorithm.label), result.getValue(algorithm), isCode = true, isHighlighted = algorithm == matched), shape, m)
                })
            }
            line({ m, shape ->
                ActionTile(
                    stringResource(R.string.checksums_compare),
                    { comparison = compareWithClipboard(context, result) },
                    shape,
                    m,
                    Icons.Outlined.ContentPaste,
                    summary = stringResource(R.string.checksums_compare_summary),
                    inline = true,
                )
            })
        }

        requested -> line({ m, shape ->
            Column(m.clip(shape).background(TileDefaults.containerColor()).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.checksums_progress, (progress * PERCENT).toInt()), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                SignalLinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
            }
        })

        else -> line({ m, shape ->
            ActionTile(
                stringResource(R.string.checksums_calculate),
                {
                    error = null
                    progress = 0f
                    requested = true
                },
                shape,
                m,
                Icons.Outlined.Fingerprint,
                summary = error ?: stringResource(R.string.checksums_calculate_summary),
                inline = true,
                trailing = if (error != null) {
                    { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
            )
        })
    }
}

private fun compareWithClipboard(context: Context, checksums: Map<ChecksumAlgorithm, String>): Comparison {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    val candidates = checksumCandidates(text)
    if (candidates.isEmpty()) return Comparison.NoChecksum
    val match = checksums.entries.firstOrNull { (_, value) -> value in candidates }?.key
    return if (match != null) Comparison.Match(match) else Comparison.NoMatch
}

private fun describe(resources: Resources, comparison: Comparison): String = when (comparison) {
    is Comparison.Match -> resources.getString(R.string.checksums_match, resources.getString(comparison.algorithm.label))
    Comparison.NoMatch -> resources.getString(R.string.checksums_no_match)
    Comparison.NoChecksum -> resources.getString(R.string.checksums_clipboard_empty)
}

private val HEX_VALUE = Regex("\\b[0-9a-fA-F]{32,128}\\b")
private val CHECKSUM_LENGTHS = setOf(32, 40, 64, 128)
private const val BUFFER_SIZE = 256 * 1024
private const val PERCENT = 100

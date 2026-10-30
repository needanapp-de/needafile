package de.needanapp.needafile.files

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.MarqueeText
import de.needanapp.design.components.SignalLinearProgressIndicator
import de.needanapp.needafile.R
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.transfer.TransferProgress

/**
 * A file that has to be there whole before it opens: decrypted from a vault, downloaded from a server or extracted from
 * an archive. [title] says which. Changes on the main thread only, except [isCancelled], which the work asks.
 */
@Stable
class FetchProgress(val name: String, @StringRes val title: Int) {
    var bytesDone by mutableLongStateOf(0L)
        private set
    var bytesTotal by mutableLongStateOf(0L)
        private set

    /** Done, failed or cancelled: the dialog closes. */
    var isFinished by mutableStateOf(false)
        private set

    @Volatile
    var isCancelled = false
        private set

    fun update(progress: TransferProgress) {
        bytesDone = progress.bytesDone
        bytesTotal = progress.bytesTotal
    }

    fun finish() {
        isFinished = true
    }

    fun cancel() {
        isCancelled = true
    }
}

/** How far [fetch] got, with a button to stop it; closes by itself once it is done. */
@Composable
fun FetchDialog(fetch: FetchProgress, onDismiss: () -> Unit) {
    LaunchedEffect(fetch.isFinished) {
        if (fetch.isFinished) onDismiss()
    }
    val cancel = {
        fetch.cancel()
        onDismiss()
    }
    ContentDialog(
        title = stringResource(fetch.title),
        confirmLabel = stringResource(R.string.cancel),
        onConfirm = cancel,
        onDismiss = cancel,
        dismissLabel = null,
    ) {
        MarqueeText(fetch.name, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        val total = fetch.bytesTotal
        if (total > 0) {
            val fraction = (fetch.bytesDone.toFloat() / total).coerceIn(0f, 1f)
            SignalLinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.fetch_progress, (fraction * PERCENT).toInt(), fetch.bytesDone.formatSize(), total.formatSize()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // connecting, or a size the server didn't tell
            SignalLinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        }
    }
}

private const val PERCENT = 100

package de.needanapp.needafile.transfer

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.needanapp.design.components.ContentDialog
import de.needanapp.needafile.R

/** Asks whether items that already exist in the target folder are overwritten or skipped; cancelling copies nothing. */
@Composable
fun TransferConflictPrompt(names: List<String>, onDecision: (overwrite: Boolean) -> Unit, onDismiss: () -> Unit) {
    val shownNames = names.take(MAX_SHOWN_NAMES).joinToString("\n") + if (names.size > MAX_SHOWN_NAMES) "\n…" else ""
    ContentDialog(
        title = stringResource(R.string.transfer_conflict_title),
        confirmLabel = stringResource(R.string.transfer_overwrite),
        onConfirm = {
            onDismiss()
            onDecision(true)
        },
        onDismiss = onDismiss,
        isDestructive = true,
        extraLabel = stringResource(R.string.transfer_skip),
        onExtra = {
            onDismiss()
            onDecision(false)
        },
    ) {
        Text(stringResource(R.string.transfer_conflict_message, shownNames))
    }
}

private const val MAX_SHOWN_NAMES = 8

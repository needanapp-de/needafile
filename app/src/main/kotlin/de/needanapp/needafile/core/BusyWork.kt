package de.needanapp.needafile.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import de.needanapp.needafile.vault.isVaultPath

/**
 * Long work the user waits for, e.g. a copy or an archive being unpacked. While any runs and isn't paused, needafile's
 * screens keep the display on ([KeepScreenOnWhileBusy]); vaults whose files it reads or writes lock only once it is
 * done (VaultAutoLock), so neither a dark screen nor the lock breaks it off. Used on the main thread only.
 */
object BusyWork {
    /** One piece of work on [paths]; while it [isPaused] the display may go off. */
    class Work internal constructor(val paths: List<String>) {
        var isPaused by mutableStateOf(false)
    }

    private val running = mutableStateListOf<Work>()
    private val endListeners = LinkedHashSet<() -> Unit>()

    /** Whether something runs that isn't paused; read while composing, it composes anew when that changes. */
    val keepsScreenOn get() = running.any { !it.isPaused }

    /** Whether running work reads from or writes into a vault. */
    fun involvesVault() = running.any { work -> work.paths.any { it.isVaultPath() } }

    /** Work on [paths] begins; [end] it once it's done, failed or cancelled. */
    fun begin(paths: List<String>) = Work(paths).also { running.add(it) }

    fun end(work: Work) {
        if (running.remove(work)) endListeners.toList().forEach { it() }
    }

    /** [listener] hears whenever a piece of work ends. */
    fun addEndListener(listener: () -> Unit) {
        endListeners.add(listener)
    }
}

/** Keeps the display on while [BusyWork] runs and this screen is visible. */
@Composable
fun KeepScreenOnWhileBusy() {
    val view = LocalView.current
    val keepOn = BusyWork.keepsScreenOn
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}

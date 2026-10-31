package de.needanapp.needafile.transfer

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import de.needanapp.needafile.R
import de.needanapp.needafile.archive.ArchiveSessions
import de.needanapp.needafile.archive.getArchiveLocation
import de.needanapp.needafile.archive.isArchivePath
import de.needanapp.needafile.core.BusyWork
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.files.getVirtualInnerPath
import de.needanapp.needafile.files.isSameVirtualRoot
import de.needanapp.needafile.files.isVirtualPath
import de.needanapp.needafile.files.isWritableVirtualPath
import de.needanapp.needafile.remote.BrowserFile
import de.needanapp.needafile.remote.RemoteConnections
import de.needanapp.needafile.remote.RemoteFileSystem
import de.needanapp.needafile.remote.ServerFileSystem
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.remote.joinRemotePath
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.vault.VaultSessions
import de.needanapp.needafile.vault.getVaultLocation
import de.needanapp.needafile.vault.isVaultPath
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The copy or move that is currently running, if any: on the phone, between phone and servers, or out of archives;
 * packing and unpacking archives run here as a [TransferJob] too. The work itself happens in [TransferService], a
 * foreground service, so it keeps going while the app is in the background or closed.
 * A copy involving a server can be paused, and when its connection breaks it waits for it to come back (see
 * [TransferState]). Listeners are called on the main thread.
 */
object TransferManager {
    sealed class Event {
        data class Progress(val progress: TransferProgress?) : Event()
        data object StateChanged : Event()
        data class Finished(val targetPath: String, val itemCount: Int, val kind: TransferKind, val error: Throwable?, val bytes: Long = 0) : Event()
    }

    enum class TransferState {
        RUNNING,

        /** The connection broke; it is tried again now and then, up to an hour, then the copy pauses. */
        WAITING,

        /** Paused by the user, or after waiting in vain for the connection (then [problem] is set). */
        PAUSED,
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(Event) -> Unit>()

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var isCancelled = false
        private set

    var currentProgress: TransferProgress? = null
        private set

    var targetPath = ""
        private set

    /** What the running work reads or, deleting, removes, in the order it goes through them. */
    var sourcePaths: List<String> = emptyList()
        private set

    /** What the running job does: copying, moving, packing, unpacking or deleting. */
    var kind = TransferKind.COPY
        private set

    val isMove get() = kind == TransferKind.MOVE

    @Volatile
    var state = TransferState.RUNNING
        private set

    /** The connection problem the copy waits out ([TransferState.WAITING]) or gave up on ([TransferState.PAUSED]). */
    @Volatile
    var problem: Throwable? = null
        private set

    /** Until when (wall clock time) a waiting copy keeps trying. */
    @Volatile
    var waitingUntil = 0L
        private set

    /** Whether the running copy involves a server; only those can pause. */
    var canPause = false
        private set

    /** Whether the main screen is in the foreground, results are then shown there instead of as notification. */
    var isUiVisible = false

    internal var pendingItems: List<FileEntry> = emptyList()
        private set

    /** The packing or unpacking to run instead of a copy of [pendingItems]. */
    private var pendingJob: TransferJob? = null
    private var onJobFinished: ((Throwable?) -> Unit)? = null

    /** How many items the result message counts, and how many bytes it names. */
    private var resultCount = 0
    private var resultBytes = 0L

    internal val hasWork get() = pendingJob != null || pendingItems.isNotEmpty()

    /** What the clipboard held when pasting it started; null for copies that didn't come from it. */
    private var clipboardAtStart: List<FileEntry>? = null

    /** Whether the running move happens on its server itself, see [movesOnServer]. */
    private var isMoveOnServer = false

    /** The running work as [BusyWork] knows it. */
    private var work: BusyWork.Work? = null

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    private var isPauseRequested = false

    /** Set by resuming, "try now" and the network coming back: a waiting copy tries at once. */
    private var isWakeUpRequested = false
    private var retryCount = 0
    private lateinit var appContext: Context

    /**
     * Starts copying [items] into [targetPath], or moving them with [isMove]; returns false while another copy is still
     * running. [fromClipboard] when the items are pasted: the clipboard empties once they arrived.
     */
    fun start(context: Context, items: List<FileEntry>, targetPath: String, isMove: Boolean = false, fromClipboard: Boolean = false): Boolean {
        if (isRunning) {
            return false
        }

        val kind = when {
            isMove -> TransferKind.MOVE
            // copying out of an archive extracts
            items.first().path.isArchivePath() -> TransferKind.EXTRACT
            else -> TransferKind.COPY
        }
        pendingItems = items
        pendingJob = null
        onJobFinished = null
        resultCount = items.size
        resultBytes = 0
        clipboardAtStart = if (fromClipboard) TransferClipboard.items else null
        isMoveOnServer = isMove && movesOnServer(context, items, targetPath)
        // renaming on the server takes a moment only, there is nothing to pause
        canPause = (items.first().path.isRemotePath() || targetPath.isRemotePath()) && !isMoveOnServer
        begin(context, kind, items.map { it.path }, targetPath)
        return true
    }

    /**
     * Whether moving [items] into [targetPath] works on their server (or in their vault) itself, by renaming instead
     * of copying.
     */
    private fun movesOnServer(context: Context, items: List<FileEntry>, targetPath: String): Boolean {
        val sourcePath = items.first().path
        if (!sourcePath.isWritableVirtualPath() || !isSameVirtualRoot(sourcePath, targetPath)) {
            return false
        }
        val fileSystem = runCatching { getRemoteFileSystem(context, targetPath) }.getOrNull() ?: return false
        val targetDir = targetPath.getVirtualInnerPath()
        return items.all { fileSystem.canMove(it.path.getVirtualInnerPath(), joinRemotePath(targetDir, it.name)) }
    }

    /**
     * Starts [job], which reads [sourcePaths] and works in or into [targetPath]; [onFinished] runs on the main thread
     * once it is done, with the error it ended with. Returns false while something else is still running.
     */
    fun startJob(
        context: Context,
        kind: TransferKind,
        sourcePaths: List<String>,
        targetPath: String,
        job: TransferJob,
        onFinished: ((Throwable?) -> Unit)? = null,
    ): Boolean {
        if (isRunning) {
            return false
        }

        pendingItems = emptyList()
        pendingJob = job
        onJobFinished = onFinished
        resultCount = 0
        resultBytes = 0
        clipboardAtStart = null
        isMoveOnServer = false
        canPause = false
        begin(context, kind, sourcePaths, targetPath)
        return true
    }

    private fun begin(context: Context, kind: TransferKind, sourcePaths: List<String>, targetPath: String) {
        appContext = context.applicationContext
        // keeps the display on, and vaults it touches unlocked, until the work is done
        work = BusyWork.begin(sourcePaths + targetPath)
        isRunning = true
        isCancelled = false
        isPauseRequested = false
        isWakeUpRequested = false
        retryCount = 0
        state = TransferState.RUNNING
        problem = null
        currentProgress = null
        this.targetPath = targetPath
        this.sourcePaths = sourcePaths
        this.kind = kind
        dispatch(Event.Progress(null))
        ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
    }

    fun cancel() = lock.withLock {
        if (isRunning) {
            isCancelled = true
            changed.signalAll()
        }
    }

    /** Pauses the copy at the next few kilobytes; also stops waiting for a broken connection. */
    fun pause() = lock.withLock {
        if (isRunning && canPause && state != TransferState.PAUSED) {
            isPauseRequested = true
            changed.signalAll()
        }
    }

    /** Continues a paused copy, or lets a waiting one try again right away. */
    fun resume() = lock.withLock {
        if (isRunning) {
            isPauseRequested = false
            isWakeUpRequested = true
            changed.signalAll()
        }
    }

    /** A network became available, a waiting copy tries again. */
    internal fun onNetworkAvailable() = lock.withLock {
        if (isRunning && state == TransferState.WAITING) {
            isWakeUpRequested = true
            changed.signalAll()
        }
    }

    fun addListener(listener: (Event) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Event) -> Unit) {
        listeners.remove(listener)
    }

    internal fun reportProgress(progress: TransferProgress) {
        mainHandler.post {
            if (isRunning) {
                currentProgress = progress
                dispatch(Event.Progress(progress))
            }
        }
    }

    internal fun reportFinished(error: Throwable?) {
        mainHandler.post {
            val wasCopy = pendingJob == null
            val onFinished = onJobFinished
            isRunning = false
            state = TransferState.RUNNING
            problem = null
            currentProgress = null
            pendingItems = emptyList()
            pendingJob = null
            onJobFinished = null
            work?.let(BusyWork::end)
            work = null
            // the clipboard stays filled after a failure, so pasting can simply be retried
            val pasted = clipboardAtStart
            if (wasCopy && error == null && pasted != null && TransferClipboard.items === pasted) {
                TransferClipboard.clear()
            }
            dispatch(Event.Finished(targetPath, resultCount, kind, error, resultBytes))
            onFinished?.invoke(error)
        }
    }

    /** The count and the bytes for the result message once the work is done. */
    internal val finishedCount get() = resultCount
    internal val finishedBytes get() = resultBytes

    private fun setState(newState: TransferState, newProblem: Throwable?, until: Long = 0L) {
        if (state == newState && problem === newProblem && waitingUntil == until) return
        state = newState
        problem = newProblem
        waitingUntil = until
        mainHandler.post {
            // paused, the display may go off; waiting for the connection, the copy still goes on by itself
            work?.isPaused = newState == TransferState.PAUSED
            dispatch(Event.StateChanged)
        }
    }

    private fun dispatch(event: Event) {
        listeners.toList().forEach { it(event) }
    }

    /** Pausing, waiting and cancelling as the user and the network decide. */
    private val control = object : TransferControl {
        override fun checkpoint() {
            if (isCancelled) throw TransferCancelledException()
            if (isPauseRequested) throw TransferPausedException()
        }

        override fun shouldWaitFor(error: Throwable) =
            error.isConnectionProblem() || (error is IOException && error !is LocalizedException && !hasNetwork())

        override fun awaitRetry(problem: Throwable?, outageStart: Long) = lock.withLock {
            var gaveUpOn: Throwable? = null
            if (problem != null && !isPauseRequested) {
                val deadline = outageStart + MAX_OUTAGE_MS
                val retryAt = SystemClock.elapsedRealtime() + RETRY_DELAYS_MS[retryCount.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
                retryCount++
                isWakeUpRequested = false
                setState(TransferState.WAITING, problem, System.currentTimeMillis() + (deadline - SystemClock.elapsedRealtime()))
                while (!isCancelled && !isPauseRequested && !isWakeUpRequested) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= deadline) {
                        // waited long enough; it stays paused until the user resumes it
                        isPauseRequested = true
                        gaveUpOn = problem
                        break
                    }
                    if (now >= retryAt) break
                    changed.await(minOf(retryAt, deadline) - now, TimeUnit.MILLISECONDS)
                }
                if (isCancelled) throw TransferCancelledException()
                if (!isPauseRequested) {
                    isWakeUpRequested = false
                    return@withLock
                }
            }

            setState(TransferState.PAUSED, gaveUpOn)
            while (!isCancelled && isPauseRequested) {
                changed.await()
            }
            if (isCancelled) throw TransferCancelledException()
            isWakeUpRequested = false
            retryCount = 0
        }

        override fun onRunning() {
            setState(TransferState.RUNNING, null)
        }
    }

    private fun hasNetwork(): Boolean {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Runs the pending copy or job; returns the local files that changed, for the media scan. */
    internal fun runPending(context: Context, onProgress: (TransferProgress) -> Unit): List<File> {
        val job = pendingJob ?: return copyItems(context, pendingItems, targetPath, onProgress)
        val result = job.run({ isCancelled }, throttled(onProgress))
        resultCount = result.itemCount
        resultBytes = result.bytes
        return result.changed
    }

    /**
     * Archive libraries and deleting report progress very often; a few updates a second are plenty. An entry of a
     * deletion that is gone completely is told at once, the list drops it then.
     */
    private fun throttled(onProgress: (TransferProgress) -> Unit): (TransferProgress) -> Unit {
        var lastReport = 0L
        var lastItemsDone = 0
        return { progress ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastReport >= JOB_PROGRESS_INTERVAL_MS || progress.itemsDone != lastItemsDone) {
                lastReport = now
                lastItemsDone = progress.itemsDone
                onProgress(progress)
            }
        }
    }

    /**
     * Copies (or moves) [items] into [targetPath]; archives only serve as source. Returns the local files that changed,
     * for the media scan. A move between phone and server, or between two servers, copies first and deletes the
     * originals once everything arrived, so a failed or cancelled move leaves them all in place.
     */
    private fun copyItems(
        context: Context,
        items: List<FileEntry>,
        targetPath: String,
        onProgress: (TransferProgress) -> Unit,
    ): List<File> {
        val sourcePath = items.first().path
        return when {
            !sourcePath.isVirtualPath() && !targetPath.isVirtualPath() -> {
                LocalCopier.run(items.map { File(it.path) }, File(targetPath), isMove, control, onProgress)
            }

            isMoveOnServer -> {
                val server = getRemoteFileSystem(context, targetPath)
                ServerMove.run(server, items.map { it.toBrowserFile() }, targetPath.getVirtualInnerPath(), { isCancelled }, onProgress)
                emptyList()
            }

            !targetPath.isVirtualPath() -> {
                val source = getRemoteFileSystem(context, sourcePath)
                val downloaded = download(source, items.map { it.toBrowserFile() }, File(targetPath), onProgress)
                if (isMove) deleteOriginals(source, items)
                downloaded
            }

            !sourcePath.isVirtualPath() -> {
                upload(getRemoteFileSystem(context, targetPath), items.map { File(it.path) }, targetPath.getVirtualInnerPath(), onProgress)
                if (isMove) deleteOriginals(items) else emptyList()
            }

            else -> {
                // server to server (or archive to server) goes through a staging folder in the cache: download everything, then upload it
                val staging = File(context.cacheDir, TRANSFER_STAGING_FOLDER)
                staging.deleteRecursively()
                staging.mkdirs()
                val source = getRemoteFileSystem(context, sourcePath)
                try {
                    download(source, items.map { it.toBrowserFile() }, staging, onProgress)
                    val stagedFiles = staging.listFiles()?.toList().orEmpty()
                    upload(getRemoteFileSystem(context, targetPath), stagedFiles, targetPath.getVirtualInnerPath(), onProgress)
                } finally {
                    staging.deleteRecursively()
                }
                if (isMove) deleteOriginals(source, items)
                emptyList()
            }
        }
    }

    /** Deletes the server originals of a move, once all of them arrived. */
    private fun deleteOriginals(source: RemoteFileSystem, items: List<FileEntry>) {
        items.forEach { item ->
            if (isCancelled) throw TransferCancelledException()
            try {
                source.delete(listOf(item.path.getVirtualInnerPath()))
            } catch (e: Exception) {
                throw LocalizedException(R.string.transfer_move_source_left, item.name, cause = e)
            }
        }
    }

    /** Deletes the phone originals of a move, once all of them arrived; returns them for the media scan. */
    private fun deleteOriginals(items: List<FileEntry>): List<File> = items.map { item ->
        if (isCancelled) throw TransferCancelledException()
        val file = File(item.path)
        if (!file.deleteRecursively()) throw LocalizedException(R.string.transfer_move_source_left, item.name)
        file
    }

    private fun download(source: RemoteFileSystem, items: List<BrowserFile>, targetDir: File, onProgress: (TransferProgress) -> Unit) =
        if (source is ServerFileSystem) {
            ResumableCopy.download(source, items, targetDir, control, onProgress)
        } else {
            source.download(items, targetDir, { isCancelled }, onProgress)
        }

    private fun upload(target: RemoteFileSystem, items: List<File>, targetDir: String, onProgress: (TransferProgress) -> Unit) {
        if (target is ServerFileSystem) {
            ResumableCopy.upload(target, items, targetDir, control, onProgress)
        } else {
            target.upload(items, targetDir, { isCancelled }, onProgress)
        }
    }

    /**
     * The server, archive or vault [path] lies on; opening an archive may take a while, so call it off the main thread.
     * A locked vault throws [de.needanapp.needafile.vault.VaultLockedException].
     */
    fun getRemoteFileSystem(context: Context, path: String): RemoteFileSystem {
        if (path.isArchivePath()) return ArchiveSessions.get(context, path.getArchiveLocation())
        if (path.isVaultPath()) return VaultSessions.get(path.getVaultLocation())
        return RemoteConnections.get(context, path.getRemoteServerId()) ?: throw LocalizedException(R.string.unknown_server)
    }

    fun FileEntry.toBrowserFile() = BrowserFile(name, path.getVirtualInnerPath(), isDirectory, size, modified)

    private const val TRANSFER_STAGING_FOLDER = "transfer_staging"
    private const val JOB_PROGRESS_INTERVAL_MS = 200L

    /** How long a broken connection is waited for before the copy pauses. */
    private val MAX_OUTAGE_MS = TimeUnit.HOURS.toMillis(1)

    /** Pauses between tries while waiting, growing with every failed one; a network coming back skips them. */
    private val RETRY_DELAYS_MS = longArrayOf(3_000, 6_000, 12_000, 24_000, 30_000)
}

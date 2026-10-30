package de.needanapp.needafile.transfer

import android.os.SystemClock
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.errors.MAX_CAUSE_DEPTH
import de.needanapp.needafile.remote.BrowserFile
import de.needanapp.needafile.remote.NeedsTrustDecision
import de.needanapp.needafile.remote.ServerErrorReply
import de.needanapp.needafile.remote.ServerFileSystem
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.remote.TransferConnection
import de.needanapp.needafile.remote.joinRemotePath
import org.apache.commons.net.ftp.FTPConnectionClosedException
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import com.hierynomus.protocol.transport.TransportException as SmbTransportException
import net.schmizz.sshj.connection.ConnectionException as SshConnectionException
import net.schmizz.sshj.transport.TransportException as SshTransportException

/** Stops a copy for a pause; once resumed it continues where it stopped. */
class TransferPausedException : IOException("Transfer paused")

/**
 * Steers a running copy: [checkpoint] comes between every few kilobytes and throws to stop it, after a pause or a
 * broken connection [awaitRetry] decides when it goes on.
 */
interface TransferControl {
    /** Throws [TransferCancelledException] or [TransferPausedException] when the copy should stop now. */
    fun checkpoint()

    /** Whether the copy waits and tries again after [error] instead of failing. */
    fun shouldWaitFor(error: Throwable): Boolean

    /**
     * Blocks until the copy should go on: after a pause ([problem] null) until it is resumed, after the connection
     * problem [problem], which began at [outageStart] (elapsed realtime), until it's worth another try.
     * Throws [TransferCancelledException] when the copy is cancelled meanwhile.
     */
    fun awaitRetry(problem: Throwable?, outageStart: Long)

    /** The copy is going (again), e.g. after it waited for the connection. */
    fun onRunning() = Unit

    companion object {
        /** Only cancelling, for copies nobody pauses or waits for (opening a file, caching an archive). */
        fun cancellable(isCancelled: () -> Boolean) = object : TransferControl {
            override fun checkpoint() {
                if (isCancelled()) throw TransferCancelledException()
            }

            override fun shouldWaitFor(error: Throwable) = false

            override fun awaitRetry(problem: Throwable?, outageStart: Long) = throw problem ?: TransferCancelledException()
        }
    }
}

/**
 * Copies files and folders between the phone and a server so that the copy can stop and carry on: after a pause or a
 * broken connection it continues with the file it was at, from where that stopped, on a new connection. Files done
 * before aren't copied again. On the phone a file always continues; on the server where the protocol can append
 * ([TransferConnection.canContinueWrite]), otherwise that one file starts anew.
 */
class ResumableCopy private constructor(
    private val server: ServerFileSystem,
    private val isUpload: Boolean,
    private val control: TransferControl,
    private val onProgress: (TransferProgress) -> Unit,
) {
    /** One file; [source] and [target] are a server path and a phone path, depending on the direction. */
    private class FileJob(val source: String, val target: String, val name: String, var size: Long, val modified: Long) {
        /** Set once the target was opened for writing: from then on a retry continues it, and cancelling removes it. */
        var isStarted = false
    }

    private val folders = ArrayList<String>()
    private val jobs = ArrayList<FileJob>()
    private var isPlanned = false
    private var areFoldersMade = false
    private var nextJob = 0
    private var bytesBefore = 0L
    private var totalBytes = 0L
    private var hasProgressed = false
    private var lastReport = 0L

    /** Plans with [plan] once, then copies; connects anew after every pause or broken connection. */
    private fun run(plan: (TransferConnection) -> Unit) {
        var hasConnected = false
        var outageStart = 0L
        while (true) {
            var connection: TransferConnection? = null
            hasProgressed = false
            try {
                control.checkpoint()
                connection = server.openTransfer()
                hasConnected = true
                if (!isPlanned) {
                    plan(connection)
                    totalBytes = jobs.sumOf { it.size.coerceAtLeast(0) }
                    isPlanned = true
                }
                if (!areFoldersMade) {
                    makeFolders(connection)
                    areFoldersMade = true
                }
                control.onRunning()
                while (nextJob < jobs.size) {
                    copy(connection, jobs[nextJob])
                    bytesBefore += jobs[nextJob].size.coerceAtLeast(0)
                    nextJob++
                    hasProgressed = true
                }
                closeQuietly(connection)
                return
            } catch (e: Throwable) {
                closeQuietly(connection)
                try {
                    when {
                        e is TransferPausedException -> {
                            outageStart = 0
                            control.awaitRetry(null, 0)
                        }

                        // a server that can't be reached at all at the start is told at once, it may be the wrong one
                        hasConnected && control.shouldWaitFor(e) -> {
                            if (outageStart == 0L || hasProgressed) outageStart = SystemClock.elapsedRealtime()
                            control.awaitRetry(e, outageStart)
                        }

                        else -> throw e
                    }
                } catch (stop: Throwable) {
                    removePartial(stop)
                    throw stop
                }
            }
        }
    }

    private fun makeFolders(connection: TransferConnection) {
        // parents are planned before their content, so creating them in order always works
        folders.forEach { folder ->
            control.checkpoint()
            if (isUpload) {
                connection.makeFolder(folder)
            } else {
                val local = File(folder)
                if (!local.isDirectory && !local.mkdirs()) throw LocalizedException(R.string.remote_error_create_local_folder, folder)
            }
        }
    }

    private fun copy(connection: TransferConnection, job: FileJob) {
        val offset = if (job.isStarted) continueOffset(connection, job) else 0L
        report(job, offset, force = true)
        if (isUpload) upload(connection, job, offset) else download(connection, job, offset)
    }

    /** Where the started [job] continues: what's there already, unless its source changed meanwhile. */
    private fun continueOffset(connection: TransferConnection, job: FileJob): Long {
        if (!isUpload) {
            val size = connection.sizeOf(job.source) ?: throw LocalizedException(R.string.remote_error_not_found)
            if (size != job.size) return restart(job, size)
            val local = File(job.target)
            return local.length().takeIf { local.isFile && it <= job.size } ?: 0L
        }

        val size = File(job.source).length()
        if (size != job.size) return restart(job, size)
        if (!connection.canContinueWrite) return 0L
        val present = connection.sizeOf(job.target) ?: return 0L
        if (present > job.size) return 0L
        return (present - connection.rewriteMargin).coerceAtLeast(0L)
    }

    private fun restart(job: FileJob, newSize: Long): Long {
        totalBytes += newSize - job.size.coerceAtLeast(0)
        job.size = newSize
        return 0L
    }

    private fun download(connection: TransferConnection, job: FileJob, offset: Long) {
        val local = File(job.target)
        connection.read(job.source, offset) { input ->
            if (offset > 0) RandomAccessFile(local, "rw").use { it.setLength(offset) }
            FileOutputStream(local, offset > 0).use { output ->
                job.isStarted = true
                pump(input, output, job, offset)
            }
        }
        if (job.modified > 0) local.setLastModified(job.modified)
    }

    private fun upload(connection: TransferConnection, job: FileJob, offset: Long) {
        FileInputStream(job.source).use { input ->
            skipFully(input, offset)
            connection.write(job.target, offset, job.size - offset, job.modified) { output ->
                job.isStarted = true
                pump(input, output, job, offset)
            }
        }
    }

    private fun pump(input: InputStream, output: OutputStream, job: FileJob, offset: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var done = offset
        while (true) {
            control.checkpoint()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            done += read
            hasProgressed = true
            report(job, done, force = false)
        }
    }

    private fun report(job: FileJob, doneInFile: Long, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastReport >= PROGRESS_INTERVAL_MS) {
            lastReport = now
            onProgress(TransferProgress(job.name, nextJob + 1, jobs.size, bytesBefore + doneInFile, totalBytes))
        }
    }

    /**
     * After cancelling or failing, the file being written is incomplete and goes, unless the server answered with an
     * error of its own: then it decided itself what became of it, and an older version may still be there.
     */
    private fun removePartial(error: Throwable) {
        if (error is TransferPausedException) return
        val job = jobs.getOrNull(nextJob)?.takeIf { it.isStarted } ?: return
        if (!isUpload) {
            File(job.target).delete()
            return
        }
        if (generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is ServerErrorReply }) return
        runCatching { server.openTransfer().use { it.deleteFile(job.target) } }
    }

    private fun closeQuietly(connection: TransferConnection?) {
        runCatching { connection?.close() }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 150L
        private const val MAX_FOLDER_DEPTH = 100

        /** Copies the server [items] (folders with their content) into the phone folder [targetDir]; returns the files written. */
        fun download(
            server: ServerFileSystem,
            items: List<BrowserFile>,
            targetDir: File,
            control: TransferControl,
            onProgress: (TransferProgress) -> Unit,
        ): List<File> {
            val copy = ResumableCopy(server, isUpload = false, control, onProgress)
            copy.run { connection ->
                val visited = HashSet<String>()
                fun collect(item: BrowserFile, target: File, depth: Int) {
                    control.checkpoint()
                    if (!item.isDirectory) {
                        copy.jobs.add(FileJob(item.path, target.absolutePath, item.name, item.size, item.lastModified))
                        return
                    }
                    // a folder reached again through a symlink is left out; servers resolving links themselves could
                    // still present an endless chain
                    val key = connection.folderKey(item.path)
                    if (key != null && !visited.add(key)) return
                    if (depth > MAX_FOLDER_DEPTH) throw LocalizedException(R.string.remote_error_folders_too_deep, item.path)
                    copy.folders.add(target.absolutePath)
                    connection.list(item.path).forEach { collect(it, File(target, it.name), depth + 1) }
                }
                items.forEach { collect(it, File(targetDir, it.name), 0) }
            }
            return copy.jobs.map { File(it.target) }
        }

        /** Copies the phone's [items] (folders with their content) into the server folder [targetDir]. */
        fun upload(server: ServerFileSystem, items: List<File>, targetDir: String, control: TransferControl, onProgress: (TransferProgress) -> Unit) {
            val copy = ResumableCopy(server, isUpload = true, control, onProgress)
            copy.run { connection ->
                connection.checkTarget(targetDir)
                fun collect(file: File, target: String) {
                    control.checkpoint()
                    if (file.isDirectory) {
                        copy.folders.add(target)
                        file.listFiles()?.forEach { collect(it, joinRemotePath(target, it.name)) }
                    } else {
                        copy.jobs.add(FileJob(file.absolutePath, target, file.name, file.length(), file.lastModified()))
                    }
                }
                items.forEach { collect(it, joinRemotePath(targetDir, it.name)) }
            }
        }

        private fun skipFully(input: InputStream, count: Long) {
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) throw EOFException()
                left -= skipped
            }
        }
    }
}

/**
 * Whether this error means the connection broke or the server can't be reached for now, so trying again later may
 * work. Answers of the server (refused access, wrong password, unknown file) and errors on the phone don't count.
 */
fun Throwable.isConnectionProblem(): Boolean {
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).forEach { error ->
        when (error) {
            is TransferCancelledException, is TransferPausedException, is NeedsTrustDecision, is FileNotFoundException -> return false
            is ServerErrorReply -> return error.isTemporary
            is SocketException, is InterruptedIOException, is UnknownHostException, is EOFException, is TimeoutException, is SSLException,
            is SshTransportException, is SshConnectionException, is SmbTransportException, is FTPConnectionClosedException -> return true
            is SMBApiException -> return error.status in SMB_SESSION_GONE
        }
    }
    return false
}

private val SMB_SESSION_GONE = setOf(
    NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
    NtStatus.STATUS_USER_SESSION_DELETED,
    NtStatus.STATUS_NETWORK_NAME_DELETED,
)

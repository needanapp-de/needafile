package de.needanapp.needafile.transfer

import android.os.SystemClock
import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Copies and moves on the phone itself: internal storage, SD cards and USB drives. Files already in the target are
 * replaced and folders merged, whatever should be kept was left out before. Moving within one volume only renames,
 * which is instant even for big files; otherwise the items are copied and the originals deleted once all arrived.
 */
internal object LocalCopier {
    /** A file to copy and where it goes. */
    private class Job(val source: File, val target: File)

    /** Returns the paths whose content changed, for the media scan: the items in [targetDir] and, when moving, the originals. */
    fun run(
        items: List<File>,
        targetDir: File,
        isMove: Boolean,
        control: TransferControl,
        onProgress: (TransferProgress) -> Unit,
    ): List<File> {
        val changed = ArrayList<File>()
        val toCopy = ArrayList<File>()
        for (item in items) {
            control.checkpoint()
            val target = File(targetDir, item.name)
            checkKinds(item, target)
            if (isMove && moveByRenaming(item, target)) {
                changed.add(target)
                changed.add(item)
            } else {
                toCopy.add(item)
            }
        }
        if (toCopy.isEmpty()) return changed

        val jobs = ArrayList<Job>()
        toCopy.forEach { collectJobs(it, File(targetDir, it.name), jobs, control) }
        copyFiles(jobs, sync = isMove, control, onProgress)
        toCopy.forEach { changed.add(File(targetDir, it.name)) }

        if (isMove) {
            // only now that everything arrived, so a failed or cancelled move leaves all originals in place
            toCopy.forEach { item ->
                control.checkpoint()
                if (!item.deleteRecursively()) throw LocalizedException(R.string.transfer_move_source_left, item.name)
                changed.add(item)
            }
        }
        return changed
    }

    /** A file can't replace a folder or the other way round; that would throw away a whole folder by surprise. */
    private fun checkKinds(source: File, target: File) {
        if (target.exists() && target.isDirectory != source.isDirectory) {
            throw LocalizedException(R.string.transfer_kind_conflict, source.name)
        }
    }

    /**
     * Moves [source] to [target] by renaming, merging into a folder that is already there. False if that isn't possible,
     * e.g. between two volumes; what was already moved stays moved then and the rest gets copied.
     */
    private fun moveByRenaming(source: File, target: File): Boolean {
        if (source.isDirectory && target.isDirectory) {
            val children = source.listFiles() ?: return false
            // stops at a file / folder clash, copying the rest then reports it
            val allMoved = children.all { child ->
                val childTarget = File(target, child.name)
                !(childTarget.exists() && childTarget.isDirectory != child.isDirectory) && moveByRenaming(child, childTarget)
            }
            return allMoved && source.delete()
        }

        if (target.isFile && !target.delete()) return false
        // a moved file keeps its time, like a copy
        return source.renameTo(target)
    }

    private fun collectJobs(source: File, target: File, jobs: MutableList<Job>, control: TransferControl) {
        control.checkpoint()
        if (source.isDirectory) {
            checkKinds(source, target)
            source.listFiles()?.forEach { collectJobs(it, File(target, it.name), jobs, control) }
            // empty folders are copied too
            if (source.list().isNullOrEmpty()) jobs.add(Job(source, target))
        } else {
            checkKinds(source, target)
            jobs.add(Job(source, target))
        }
    }

    /** [sync] writes each file through to the storage before going on, as the originals of a move are deleted afterwards. */
    private fun copyFiles(jobs: List<Job>, sync: Boolean, control: TransferControl, onProgress: (TransferProgress) -> Unit) {
        val totalBytes = jobs.sumOf { if (it.source.isFile) it.source.length() else 0L }
        var bytesBefore = 0L
        var lastReport = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        jobs.forEachIndexed { index, job ->
            fun report(doneInFile: Long, force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                if (force || now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now
                    onProgress(TransferProgress(job.source.name, index + 1, jobs.size, bytesBefore + doneInFile, totalBytes))
                }
            }

            report(0, force = true)
            if (job.source.isDirectory) {
                if (!job.target.isDirectory && !job.target.mkdirs()) throw LocalizedException(R.string.transfer_create_folder_failed, job.target.name)
                return@forEachIndexed
            }

            val parent = job.target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw LocalizedException(R.string.transfer_create_folder_failed, parent.name)
            var done = 0L
            try {
                FileInputStream(job.source).use { input ->
                    FileOutputStream(job.target).use { output ->
                        while (true) {
                            control.checkpoint()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            report(done)
                        }
                        if (sync) output.fd.sync()
                    }
                }
            } catch (e: IOException) {
                // a half written file is worth nothing and might be taken for the real one
                job.target.delete()
                throw e
            }
            // copies keep the change time of their originals
            job.target.setLastModified(job.source.lastModified())
            bytesBefore += done
            report(0, force = true)
        }
    }

    private const val BUFFER_SIZE = 256 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
}

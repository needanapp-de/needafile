package de.needanapp.needafile.archive

import de.needanapp.needafile.R
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.transfer.TransferProgress
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItem7z
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.InputStreamSequentialInStream
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.Date

/**
 * Creates 7z archives with 7-Zip (LZMA2). With a password, 7-Zip encrypts the content with AES-256, the key being derived
 * from the password with 2^19 SHA-256 rounds; encryptFileNames also encrypts the archive's header (file list).
 */
object SevenZipArchiver {
    private class Entry(val file: File, val pathInArchive: String)

    /**
     * @param level 7-Zip's compression level (-mx), 0 = copy … 9 = ultra
     * @param solid compress the files together in one solid block; smaller, but extracting single files takes longer
     * @param isCancelled stops with a [TransferCancelledException] once true; the unfinished archive is deleted
     */
    fun create(
        sources: List<String>,
        destination: String,
        password: String?,
        encryptFileNames: Boolean,
        level: Int,
        solid: Boolean,
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ) {
        val entries = collectEntries(sources.map(::File))
        val target = File(destination)
        val progress = Progress(entries, isCancelled, onProgress)
        try {
            RandomAccessFile(target, "rw").use { file ->
                SevenZip.openOutArchive7z().use { archive ->
                    // all settings reach 7-Zip together only with needafile's build of 7-Zip-JBinding (libs/7-zip-jbinding);
                    // the published library hands each one over separately, and 7-Zip keeps only the last. Threads stay
                    // at 7-Zip's default (all cores).
                    archive.setLevel(level)
                    archive.setSolid(solid)
                    archive.setHeaderEncryption(password != null && encryptFileNames)
                    val callback = if (password != null) EncryptingCallback(entries, progress, password) else CreateCallback(entries, progress)
                    archive.createArchive(RandomAccessFileOutStream(file), entries.size, callback)
                }
            }
        } catch (e: Exception) {
            target.delete()
            if (progress.wasCancelled) throw TransferCancelledException()
            throw ArchiveException(R.string.archive_create_failed, e.message ?: e.javaClass.simpleName, cause = e)
        }
    }

    /** Turns 7-Zip's progress (bytes read of all files) into [TransferProgress] and stops 7-Zip when cancelled. */
    private class Progress(private val entries: List<Entry>, private val isCancelled: () -> Boolean, private val onProgress: (TransferProgress) -> Unit) {
        private val fileCount = entries.count { !it.file.isDirectory }.coerceAtLeast(1)
        private var total = 0L
        private var name = ""
        private var number = 0

        @Volatile
        var wasCancelled = false
            private set

        fun onFile(index: Int) {
            checkCancelled()
            name = entries[index].file.name
            number++
        }

        fun setTotal(total: Long) {
            this.total = total
        }

        fun setCompleted(completed: Long) {
            checkCancelled()
            onProgress(TransferProgress(name, number.coerceIn(1, fileCount), fileCount, completed, total))
        }

        private fun checkCancelled() {
            if (isCancelled()) {
                wasCancelled = true
                throw SevenZipException("cancelled")
            }
        }
    }

    /** Every file and folder below [sources], with paths relative to the folder they are in. */
    private fun collectEntries(sources: List<File>): List<Entry> {
        val entries = ArrayList<Entry>()
        fun visit(file: File, path: String) {
            entries.add(Entry(file, path))
            if (file.isDirectory) {
                file.listFiles()?.sortedBy { it.name }?.forEach { visit(it, "$path/${it.name}") }
            }
        }
        sources.forEach { visit(it, it.name) }
        return entries
    }

    private open class CreateCallback(private val entries: List<Entry>, private val progress: Progress) : IOutCreateCallback<IOutItem7z> {
        private var openStream: ISequentialInStream? = null

        override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItem7z>): IOutItem7z {
            val entry = entries[index]
            return outItemFactory.createOutItem().apply {
                propertyPath = entry.pathInArchive
                propertyIsDir = entry.file.isDirectory
                propertyLastModificationTime = Date(entry.file.lastModified())
                dataSize = if (entry.file.isDirectory) 0 else entry.file.length()
            }
        }

        override fun getStream(index: Int): ISequentialInStream? {
            closeOpenStream()
            val file = entries[index].file
            if (file.isDirectory) return null
            progress.onFile(index)
            return InputStreamSequentialInStream(FileInputStream(file)).also { openStream = it }
        }

        override fun setOperationResult(operationResultOk: Boolean) = closeOpenStream()

        override fun setTotal(total: Long) = progress.setTotal(total)

        override fun setCompleted(complete: Long) = progress.setCompleted(complete)

        private fun closeOpenStream() {
            openStream?.close()
            openStream = null
        }
    }

    // 7-Zip only encrypts when the callback can hand out a password
    private class EncryptingCallback(entries: List<Entry>, progress: Progress, private val password: String) :
        CreateCallback(entries, progress), ICryptoGetTextPassword {
        override fun cryptoGetTextPassword() = password
    }
}

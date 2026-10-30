package de.needanapp.needafile.archive

import de.needanapp.needafile.R
import de.needanapp.needafile.transfer.TransferProgress
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZipException
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Date

/**
 * Extracts every [ArchiveKind]: 7-Zip for all formats but xz (split RAR and 7z through all their parts, split zips joined
 * first), XZ for Java for xz. Compressed tar archives are unpacked in two steps through a temporary file. Encrypted
 * archives need [password]; without it or with a wrong one an [ArchivePasswordException] is thrown and nothing is
 * left behind.
 */
class ArchiveExtractor(private val cacheDir: File) {

    /**
     * Extracts [archivePath] into a new folder (or a single file for gz, bz2, xz …) in [targetFolder], next to the
     * archive by default, and returns that path. Any part of a split archive extracts the whole archive. [isCancelled]
     * stops it with a [CancelledExtractionException], leaving nothing behind.
     */
    fun extract(
        archivePath: String,
        password: String?,
        targetFolder: File? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ): File {
        val selected = File(archivePath)
        val kind = ArchiveKind.of(selected.name) ?: throw ArchiveException(R.string.archive_unsupported, selected.name)
        val archive = ArchiveVolumes.firstVolume(selected)
        val parent = targetFolder ?: archive.parentFile ?: throw ArchiveException(R.string.archive_unsupported, archive.name)
        val baseName = ArchiveKind.baseName(archive.name)

        return when {
            kind.isTarCompressed -> intoNewFolder(parent, baseName) { folder ->
                withTemporaryFile { tar ->
                    // first the outer layer, shown as the archive itself
                    decompressSingle(archive, kind, tar, isCancelled) { onProgress(it.copy(fileName = archive.name)) }
                    extractAll(tar, ArchiveKind.TAR, folder, null, isCancelled, onProgress)
                }
            }

            kind.isSingleStream -> {
                val target = uniqueFile(parent, baseName)
                try {
                    decompressSingle(archive, kind, target, isCancelled, onProgress)
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                target
            }

            kind == ArchiveKind.ZIP && isSplitZip(archive) -> intoNewFolder(parent, baseName) { folder ->
                withTemporaryFile { joined ->
                    SplitZipJoiner.join(archive, joined)
                    extractAll(joined, ArchiveKind.ZIP, folder, password, isCancelled, onProgress)
                }
            }

            else -> intoNewFolder(parent, baseName) { folder -> extractAll(archive, kind, folder, password, isCancelled, onProgress) }
        }
    }

    /**
     * Unpacks a single-stream format (gz, bz2, xz, Z, lzma, or the outer layer of a compressed tar) into [target]; the
     * progress counts the bytes read from [archive].
     */
    fun decompressSingle(
        archive: File,
        kind: ArchiveKind,
        target: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ) {
        if (kind == ArchiveKind.XZ || kind == ArchiveKind.TAR_XZ) {
            val total = archive.length()
            FileInputStream(archive).use { file ->
                XZInputStream(BufferedInputStream(file)).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            if (isCancelled()) throw CancelledExtractionException()
                            onProgress(TransferProgress(target.name, 1, 1, file.channel.position(), total))
                        }
                    }
                }
            }
            return
        }

        ArchiveOpener.open(archive, kind, null).use { opened ->
            if (opened.archive.numberOfItems < 1) throw ArchiveException(R.string.archive_damaged)
            extractItems(opened.archive, intArrayOf(0), null, isCancelled, { done, total ->
                onProgress(TransferProgress(target.name, 1, 1, done, total))
            }) { target }
        }
    }

    private fun extractAll(
        archive: File,
        kind: ArchiveKind,
        folder: File,
        password: String?,
        isCancelled: () -> Boolean,
        onProgress: (TransferProgress) -> Unit,
    ) {
        ArchiveOpener.open(archive, kind, password).use { opened ->
            val items = opened.archive
            if (password == null && (0 until items.numberOfItems).any { items.getProperty(it, PropID.ENCRYPTED) == true }) {
                throw ArchivePasswordException(isWrong = false)
            }
            val canonicalFolder = folder.canonicalPath + File.separator
            val fileCount = (0 until items.numberOfItems).count { items.getProperty(it, PropID.IS_FOLDER) != true }.coerceAtLeast(1)
            var currentName = ""
            var currentNumber = 0
            extractItems(items, null, password, isCancelled, { done, total ->
                onProgress(TransferProgress(currentName, currentNumber.coerceIn(1, fileCount), fileCount, done, total))
            }) { index ->
                val path = items.getStringProperty(index, PropID.PATH)?.takeIf { it.isNotEmpty() } ?: return@extractItems null
                // entries like "../../x" must not escape the target folder ("zip slip")
                File(folder, path).takeIf { it.canonicalPath.startsWith(canonicalFolder) }?.also {
                    if (items.getProperty(index, PropID.IS_FOLDER) != true) {
                        currentName = it.name
                        currentNumber++
                    }
                }
            }
        }
    }

    /** [name] in [parent], or "name (2)", "name (3)" … if it's taken. */
    private fun uniqueFile(parent: File, name: String): File {
        var candidate = File(parent, name)
        var number = 2
        while (candidate.exists()) {
            candidate = File(parent, "$name ($number)")
            number++
        }
        return candidate
    }

    /** Runs [block] on a new folder named [name] in [parent]; the folder is removed again if [block] fails. */
    private fun intoNewFolder(parent: File, name: String, block: (File) -> Unit): File {
        val folder = uniqueFile(parent, name)
        if (!folder.mkdirs()) throw ArchiveException(R.string.remote_error_create_local_folder, folder.absolutePath)
        try {
            block(folder)
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
        return folder
    }

    private fun <T> withTemporaryFile(block: (File) -> T): T {
        val file = File.createTempFile("extract", ".tar", cacheDir)
        try {
            return block(file)
        } finally {
            file.delete()
        }
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024

        // without the right password, older formats (ZipCrypto, 7z, RAR 4) report broken data instead of a wrong password
        private val PASSWORD_SYMPTOMS = setOf(ExtractOperationResult.CRCERROR, ExtractOperationResult.DATAERROR, ExtractOperationResult.UNSUPPORTEDMETHOD)

        /**
         * Extracts the entries of an opened archive that [targetFor] gives a file for ([indices] null = all entries);
         * folders are created, files written. [onProgress] gets the bytes done of the total; [isCancelled] stops the
         * extraction with a [CancelledExtractionException].
         */
        fun extractItems(
            archive: IInArchive,
            indices: IntArray?,
            password: String?,
            isCancelled: () -> Boolean = { false },
            onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
            targetFor: (Int) -> File?,
        ) {
            val callback = ExtractCallback(archive, password, isCancelled, onProgress, targetFor)
            try {
                if (indices == null) archive.extract(null, false, callback) else archive.extract(indices, false, callback)
            } catch (e: SevenZipException) {
                if (callback.wasCancelled) throw CancelledExtractionException()
                if (callback.wasPasswordRequested) throw ArchivePasswordException(isWrong = password != null)
                throw ArchiveException(R.string.archive_damaged, cause = e)
            } finally {
                callback.closeOpenFile()
            }
            val failure = callback.failure ?: return
            if (failure == ExtractOperationResult.WRONG_PASSWORD || callback.wasPasswordRequested && failure in PASSWORD_SYMPTOMS) {
                throw ArchivePasswordException(isWrong = password != null)
            }
            throw ArchiveException(R.string.archive_damaged)
        }
    }

    private class ExtractCallback(
        private val archive: IInArchive,
        private val password: String?,
        private val isCancelled: () -> Boolean,
        private val onProgress: (done: Long, total: Long) -> Unit,
        private val targetFor: (Int) -> File?,
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {
        var failure: ExtractOperationResult? = null
            private set
        var wasPasswordRequested = false
            private set
        var wasCancelled = false
            private set
        private var openFile: OutputStream? = null
        private var openTarget: File? = null
        private var openIndex = -1
        private var total = 0L

        override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
            closeOpenFile()
            if (extractAskMode != ExtractAskMode.EXTRACT) return null
            val target = targetFor(index) ?: return null

            if (archive.getProperty(index, PropID.IS_FOLDER) == true) {
                target.mkdirs()
                setModified(target, index)
                return null
            }

            target.parentFile?.mkdirs()
            val output = FileOutputStream(target)
            openFile = output
            openTarget = target
            openIndex = index
            return ISequentialOutStream { data ->
                if (isCancelled()) {
                    wasCancelled = true
                    throw SevenZipException("cancelled")
                }
                output.write(data)
                data.size
            }
        }

        private fun setModified(target: File, index: Int) {
            (archive.getProperty(index, PropID.LAST_MODIFICATION_TIME) as? Date)?.let { target.setLastModified(it.time) }
        }

        override fun cryptoGetTextPassword(): String {
            wasPasswordRequested = true
            return password.orEmpty()
        }

        override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

        override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
            closeOpenFile()
            if (extractOperationResult != ExtractOperationResult.OK && failure == null) {
                failure = extractOperationResult
            }
        }

        override fun setTotal(total: Long) {
            this.total = total
        }

        override fun setCompleted(complete: Long) = onProgress(complete, total)

        fun closeOpenFile() {
            val file = openFile ?: return
            file.close()
            openFile = null
            // the file keeps the date it has in the archive
            openTarget?.let { setModified(it, openIndex) }
            openTarget = null
        }
    }
}

/** The user stopped an extraction; the files written so far stay. */
class CancelledExtractionException : IOException("Extraction cancelled")

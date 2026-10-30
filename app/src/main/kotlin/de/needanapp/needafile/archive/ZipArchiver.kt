package de.needanapp.needafile.archive

import de.needanapp.needafile.R
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.transfer.TransferProgress
import net.lingala.zip4j.io.outputstream.SplitOutputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Creates zip archives with zip4j: AES encrypted when a password is given, split into parts (name.z01, name.z02 …
 * name.zip) when a part size is given. Paths in the archive start below the folder of the sources; empty folders are
 * kept. A cancelled or failed archive is deleted again.
 */
object ZipArchiver {
    private class Entry(val file: File, val pathInArchive: String)

    fun create(sources: List<File>, request: CompressRequest, isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit) {
        val target = File(request.destination)
        val partSize = request.partSize
        // zip4j writes the parts itself, through Android's document access that isn't possible
        if (partSize != null && (target.parentFile?.canWrite() != true || sources.any { !it.canRead() })) {
            throw ArchiveException(R.string.archive_split_needs_file_access)
        }

        val entries = sources.flatMap { source ->
            val base = source.parentFile ?: source
            source.walkTopDown().map { Entry(it, it.relativeTo(base).invariantSeparatorsPath) }.toList()
        }
        val fileCount = entries.count { it.file.isFile }.coerceAtLeast(1)
        val totalBytes = entries.sumOf { if (it.file.isFile) it.file.length() else 0L }

        val output: OutputStream = if (partSize != null) SplitOutputStream(target, partSize) else FileOutputStream(target)
        var isComplete = false
        try {
            val zip = request.password?.let { ZipOutputStream(output, it.toCharArray()) } ?: ZipOutputStream(output)
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesDone = 0L
            var number = 0
            for (entry in entries) {
                if (isCancelled()) throw TransferCancelledException()
                val file = entry.file
                if (file.isDirectory) {
                    zip.putNextEntry(folderParameters("${entry.pathInArchive}/", file.lastModified()))
                    zip.closeEntry()
                    continue
                }

                number++
                onProgress(TransferProgress(file.name, number, fileCount, bytesDone, totalBytes))
                zip.putNextEntry(parameters(request, entry.pathInArchive, file.lastModified(), file.length()))
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        bytesDone += read
                        if (isCancelled()) throw TransferCancelledException()
                        onProgress(TransferProgress(file.name, number, fileCount, bytesDone, totalBytes))
                    }
                }
                zip.closeEntry()
            }
            zip.close()
            isComplete = true
        } finally {
            if (!isComplete) {
                runCatching { output.close() }
                splitZipParts(target).forEach(File::delete)
                target.delete()
            }
        }
    }

    /** Folders hold no data, there is nothing to compress or encrypt. */
    private fun folderParameters(name: String, lastModified: Long) = ZipParameters().apply {
        fileNameInZip = name
        lastModifiedFileTime = lastModified
        compressionMethod = CompressionMethod.STORE
        entrySize = 0
    }

    /** Stored entries need their size up front. */
    private fun parameters(request: CompressRequest, name: String, lastModified: Long, size: Long) = ZipParameters().apply {
        fileNameInZip = name
        lastModifiedFileTime = lastModified
        val level = request.strength.zipLevel
        if (level == null) {
            compressionMethod = CompressionMethod.STORE
            entrySize = size
        } else {
            compressionLevel = level
        }
        if (request.password != null) {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = request.aesKeyStrength
        }
    }

    private const val BUFFER_SIZE = 256 * 1024
}

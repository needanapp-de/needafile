package de.needanapp.needafile.archive

import de.needanapp.needafile.R
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.VolumedArchiveInStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** An archive opened by 7-Zip together with the files of its parts, which stay open until [close]. */
class OpenedArchive internal constructor(val archive: IInArchive, private val volumes: VolumeCallback) : Closeable {
    override fun close() {
        try {
            archive.close()
        } finally {
            volumes.closeAll()
        }
    }
}

/** Opens archives of every [ArchiveKind] 7-Zip reads, including split ones, from any of their parts. */
object ArchiveOpener {

    /** The 7-Zip formats to try for [kind], in order; empty for xz, which XZ for Java reads instead. */
    fun formatsOf(kind: ArchiveKind): List<ArchiveFormat> = when (kind) {
        ArchiveKind.ZIP -> listOf(ArchiveFormat.ZIP)
        ArchiveKind.SEVEN_ZIP -> listOf(ArchiveFormat.SEVEN_ZIP)
        ArchiveKind.RAR -> listOf(ArchiveFormat.RAR5, ArchiveFormat.RAR)
        ArchiveKind.TAR -> listOf(ArchiveFormat.TAR)
        ArchiveKind.GZIP, ArchiveKind.TAR_GZIP -> listOf(ArchiveFormat.GZIP)
        ArchiveKind.BZIP2, ArchiveKind.TAR_BZIP2 -> listOf(ArchiveFormat.BZIP2)
        ArchiveKind.XZ, ArchiveKind.TAR_XZ -> emptyList()
        ArchiveKind.Z -> listOf(ArchiveFormat.Z)
        ArchiveKind.LZMA -> listOf(ArchiveFormat.LZMA)
        // DVD images carry their full file names only in UDF, plain CD images only have ISO 9660
        ArchiveKind.ISO -> listOf(ArchiveFormat.UDF, ArchiveFormat.ISO)
        ArchiveKind.CAB -> listOf(ArchiveFormat.CAB)
        ArchiveKind.ARJ -> listOf(ArchiveFormat.ARJ)
        ArchiveKind.LZH -> listOf(ArchiveFormat.LZH)
        ArchiveKind.WIM -> listOf(ArchiveFormat.WIM)
        ArchiveKind.CPIO -> listOf(ArchiveFormat.CPIO)
        ArchiveKind.RPM -> listOf(ArchiveFormat.RPM)
        ArchiveKind.DEB -> listOf(ArchiveFormat.AR)
        ArchiveKind.CHM -> listOf(ArchiveFormat.CHM)
        ArchiveKind.XAR -> listOf(ArchiveFormat.XAR)
    }

    /**
     * Opens [file] as [kind]; for a later part of a split archive the whole archive is opened from its first part.
     * Archives with encrypted file names need [password] already here, otherwise an [ArchivePasswordException] says so.
     */
    fun open(file: File, kind: ArchiveKind, password: String?, formats: List<ArchiveFormat> = formatsOf(kind)): OpenedArchive {
        val first = ArchiveVolumes.firstVolume(file)
        if (!first.canRead()) throw ArchiveException(R.string.archive_damaged)
        var lastError: SevenZipException? = null
        for (format in formats) {
            val volumes = VolumeCallback(first, password)
            try {
                val stream = if (format == ArchiveFormat.SEVEN_ZIP && first.name.endsWith(SEVEN_ZIP_FIRST_PART, ignoreCase = true)) {
                    VolumedArchiveInStream(first.absolutePath, volumes)
                } else {
                    volumes.getStream(first.absolutePath) ?: throw ArchiveException(R.string.archive_damaged)
                }
                return OpenedArchive(SevenZip.openInArchive(format, stream, volumes), volumes)
            } catch (e: SevenZipException) {
                volumes.closeAll()
                if (volumes.wasPasswordRequested) throw ArchivePasswordException(isWrong = password != null)
                lastError = e
            }
        }
        throw ArchiveException(R.string.archive_damaged, cause = lastError)
    }

    private const val SEVEN_ZIP_FIRST_PART = ".7z.001"
}

/**
 * Hands 7-Zip the parts of a split archive by name and the password when it asks for one. Every part is opened once
 * and stays open until [closeAll].
 */
class VolumeCallback internal constructor(first: File, private val password: String?) :
    IArchiveOpenCallback, IArchiveOpenVolumeCallback, ICryptoGetTextPassword {
    private val openFiles = LinkedHashMap<String, RandomAccessFile>()
    private var currentName = first.absolutePath

    var wasPasswordRequested = false
        private set

    override fun getProperty(propID: PropID): Any? = if (propID == PropID.NAME) currentName else null

    override fun getStream(filename: String): IInStream? {
        val file = File(filename).takeIf { it.exists() } ?: caseInsensitiveSibling(File(filename)) ?: return null
        val randomAccessFile = openFiles.getOrPut(file.absolutePath) { RandomAccessFile(file, "r") }
        randomAccessFile.seek(0)
        currentName = filename
        return RandomAccessFileInStream(randomAccessFile)
    }

    // 7-Zip derives the next part's name from the first one, the real part may differ in case (name.R00)
    private fun caseInsensitiveSibling(file: File): File? =
        file.parentFile?.listFiles()?.firstOrNull { it.name.equals(file.name, ignoreCase = true) }

    override fun cryptoGetTextPassword(): String {
        wasPasswordRequested = true
        return password.orEmpty()
    }

    override fun setTotal(files: Long?, bytes: Long?) = Unit

    override fun setCompleted(files: Long?, bytes: Long?) = Unit

    fun closeAll() {
        openFiles.values.forEach { runCatching { it.close() } }
        openFiles.clear()
    }
}

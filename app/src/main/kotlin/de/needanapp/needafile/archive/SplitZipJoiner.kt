package de.needanapp.needafile.archive

import de.needanapp.needafile.R
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Joins a split zip (name.z01, name.z02 … name.zip) into one ordinary zip, which 7-Zip and zip4j then read like any
 * other. Neither reads every split zip by itself, e.g. not those whose central directory starts in an earlier part.
 * The parts are copied one after another and the central directory is written anew, with offsets counted from the
 * start of the joined file. Zip64 split archives (over 4 GB) aren't supported.
 */
object SplitZipJoiner {
    private const val SPLIT_MARKER = 0x08074b50
    private const val END_SIGNATURE = 0x06054b50
    private const val ENTRY_SIGNATURE = 0x02014b50
    private const val END_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val ENTRY_FIXED_SIZE = 46
    private const val MAX_16 = 0xFFFF
    private const val MAX_32 = 0xFFFFFFFFL

    /** Writes the split zip whose last part is [lastPart] as a single zip to [target]. */
    fun join(lastPart: File, target: File) {
        val parts = splitZipParts(lastPart)
        // the first part may start with a marker that isn't part of the archive; offsets on that part count it
        val hasMarker = RandomAccessFile(parts.first(), "r").use { it.length() >= 4 && Integer.reverseBytes(it.readInt()) == SPLIT_MARKER }
        val starts = LongArray(parts.size)
        var position = if (hasMarker) -4L else 0L
        parts.forEachIndexed { index, part ->
            starts[index] = position
            position += part.length()
        }

        val end = readEnd(lastPart)
        val directoryDisk = end.getShort(6).toInt() and MAX_16
        val entryCount = end.getShort(10).toInt() and MAX_16
        val directorySize = end.getInt(12).toLong() and MAX_32
        val directoryOffset = end.getInt(16).toLong() and MAX_32
        if (entryCount == MAX_16 || directorySize == MAX_32 || directoryOffset == MAX_32 || directoryDisk >= parts.size) {
            throw ArchiveException(R.string.archive_damaged)
        }

        val directoryStart = starts[directoryDisk] + directoryOffset
        val directory = ByteBuffer.wrap(readJoined(parts, starts, directoryStart, directorySize.toInt())).order(ByteOrder.LITTLE_ENDIAN)
        rewriteEntries(directory, entryCount, starts)

        BufferedOutputStream(FileOutputStream(target)).use { output ->
            copyJoined(parts, hasMarker, directoryStart, output)
            output.write(directory.array())
            val newEnd = ByteBuffer.allocate(END_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(END_SIGNATURE)
                .putShort(0)
                .putShort(0)
                .putShort(entryCount.toShort())
                .putShort(entryCount.toShort())
                .putInt(directorySize.toInt())
                .putInt(checkedOffset(directoryStart))
                .putShort(0)
            output.write(newEnd.array())
        }
    }

    /** The end of central directory record of [lastPart], found from the back (a comment may follow it). */
    private fun readEnd(lastPart: File): ByteBuffer = RandomAccessFile(lastPart, "r").use { file ->
        val tailSize = minOf(file.length(), (END_SIZE + MAX_COMMENT).toLong()).toInt()
        val tail = ByteArray(tailSize)
        file.seek(file.length() - tailSize)
        file.readFully(tail)
        val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val start = (tailSize - END_SIZE downTo 0).firstOrNull { buffer.getInt(it) == END_SIGNATURE } ?: throw ArchiveException(R.string.archive_damaged)
        ByteBuffer.wrap(tail, start, END_SIZE).slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    /** Points every entry of the central directory at its place in the joined file, on "disk" 0. */
    private fun rewriteEntries(directory: ByteBuffer, entryCount: Int, starts: LongArray) {
        var position = 0
        repeat(entryCount) {
            if (directory.getInt(position) != ENTRY_SIGNATURE) throw ArchiveException(R.string.archive_damaged)
            val nameLength = directory.getShort(position + 28).toInt() and MAX_16
            val extraLength = directory.getShort(position + 30).toInt() and MAX_16
            val commentLength = directory.getShort(position + 32).toInt() and MAX_16
            val disk = directory.getShort(position + 34).toInt() and MAX_16
            val offset = directory.getInt(position + 42).toLong() and MAX_32
            if (disk == MAX_16 || offset == MAX_32 || disk >= starts.size) throw ArchiveException(R.string.archive_damaged)
            directory.putShort(position + 34, 0)
            directory.putInt(position + 42, checkedOffset(starts[disk] + offset))
            position += ENTRY_FIXED_SIZE + nameLength + extraLength + commentLength
        }
    }

    private fun checkedOffset(offset: Long): Int {
        if (offset !in 0 until MAX_32) throw ArchiveException(R.string.archive_damaged)
        return offset.toInt()
    }

    /** [length] bytes from [start] of the parts seen as one file, [starts] being where each part begins in it. */
    private fun readJoined(parts: List<File>, starts: LongArray, start: Long, length: Int): ByteArray {
        val result = ByteArray(length)
        var done = 0
        parts.forEachIndexed { index, part ->
            if (done == length) return@forEachIndexed
            val partEnd = starts[index] + part.length()
            val from = start + done
            if (from >= partEnd || from < starts[index]) return@forEachIndexed
            RandomAccessFile(part, "r").use { file ->
                file.seek(from - starts[index])
                val count = minOf((partEnd - from).toInt(), length - done)
                file.readFully(result, done, count)
                done += count
            }
        }
        if (done < length) throw ArchiveException(R.string.archive_damaged)
        return result
    }

    /** Copies the content of all parts up to [limit] (the old central directory) into [output]. */
    private fun copyJoined(parts: List<File>, hasMarker: Boolean, limit: Long, output: BufferedOutputStream) {
        var written = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        parts.forEachIndexed { index, part ->
            FileInputStream(part).use { input ->
                if (index == 0 && hasMarker) repeat(4) { input.read() }
                while (written < limit) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - written).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
            }
        }
        if (written < limit) throw ArchiveException(R.string.archive_damaged)
    }

    private const val BUFFER_SIZE = 64 * 1024
}

package de.needanapp.needafile.archive

import java.io.File

/**
 * Archives split into parts: RAR (name.part1.rar, name.part2.rar … or the older name.rar, name.r00, name.r01 …),
 * 7z (name.7z.001, name.7z.002 …) and ZIP (name.z01, name.z02 … name.zip). Every part counts as an archive file;
 * opening any of them opens the whole archive from its first part (for ZIP the .zip, which holds the directory).
 */
object ArchiveVolumes {
    private val RAR_PART = Regex("""^(.*)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)
    private val RAR_OLD_PART = Regex("""^(.*)\.r(\d{2})$""", RegexOption.IGNORE_CASE)
    private val SEVEN_ZIP_PART = Regex("""^(.*)\.7z\.(\d{3})$""", RegexOption.IGNORE_CASE)
    private val ZIP_PART = Regex("""^(.*)\.z(\d{2})$""", RegexOption.IGNORE_CASE)

    /** The format of a part whose extension doesn't tell it, e.g. name.r00 or name.7z.002. */
    internal fun kindOfVolume(path: String): ArchiveKind? {
        val name = path.substringAfterLast('/')
        return when {
            RAR_OLD_PART.matches(name) -> ArchiveKind.RAR
            SEVEN_ZIP_PART.matches(name) -> ArchiveKind.SEVEN_ZIP
            ZIP_PART.matches(name) -> ArchiveKind.ZIP
            else -> null
        }
    }

    internal fun baseNameOfVolume(name: String): String? = RAR_PART.matchEntire(name)?.groupValues?.get(1)
        ?: RAR_OLD_PART.matchEntire(name)?.groupValues?.get(1)
        ?: SEVEN_ZIP_PART.matchEntire(name)?.groupValues?.get(1)
        ?: ZIP_PART.matchEntire(name)?.groupValues?.get(1)

    /** The part an archive has to be opened from (name.part1.rar, name.rar, name.7z.001 or name.zip), or [file] itself. */
    fun firstVolume(file: File): File {
        val folder = file.parentFile ?: return file
        return firstVolumeName(file.name)?.let { sibling(folder, it) } ?: file
    }

    /** The name of the first part for the part [name], null if [name] isn't a later part of a split archive. */
    fun firstVolumeName(name: String): String? {
        RAR_PART.matchEntire(name)?.let { match ->
            // the part numbers share one width: part1, part01 or part001
            val first = "${match.groupValues[1]}.part${"1".padStart(match.groupValues[2].length, '0')}.rar"
            return first.takeIf { !it.equals(name, ignoreCase = true) }
        }
        RAR_OLD_PART.matchEntire(name)?.let { return "${it.groupValues[1]}.rar" }
        SEVEN_ZIP_PART.matchEntire(name)?.let { match ->
            return "${match.groupValues[1]}.7z.001".takeIf { !it.equals(name, ignoreCase = true) }
        }
        ZIP_PART.matchEntire(name)?.let { return "${it.groupValues[1]}.zip" }
        return null
    }

    /** All parts of the archive whose first part is [first], in order; only [first] when it isn't split. */
    fun allVolumes(first: File): List<File> {
        val folder = first.parentFile ?: return listOf(first)
        return volumeNames(first.name) { sibling(folder, it) != null }.map { sibling(folder, it) ?: File(folder, it) }
    }

    /**
     * The names of all parts of the archive whose first part is [firstName], in order; [exists] tells which names are
     * there, so this also works for archives on servers.
     */
    fun volumeNames(firstName: String, exists: (String) -> Boolean): List<String> {
        RAR_PART.matchEntire(firstName)?.let { match ->
            val width = match.groupValues[2].length
            return generateSequence(1) { it + 1 }
                .map { "${match.groupValues[1]}.part${it.toString().padStart(width, '0')}.rar" }
                .takeWhile { it.equals(firstName, ignoreCase = true) || exists(it) }
                .toList()
        }
        SEVEN_ZIP_PART.matchEntire(firstName)?.let { match ->
            return generateSequence(1) { it + 1 }
                .map { "${match.groupValues[1]}.7z.${it.toString().padStart(3, '0')}" }
                .takeWhile { it.equals(firstName, ignoreCase = true) || exists(it) }
                .toList()
        }
        if (firstName.endsWith(".rar", ignoreCase = true)) {
            val base = firstName.dropLast(".rar".length)
            val parts = generateSequence(0) { it + 1 }
                .map { "$base.r${it.toString().padStart(2, '0')}" }
                .takeWhile(exists)
            return listOf(firstName) + parts
        }
        if (firstName.endsWith(".zip", ignoreCase = true)) {
            val base = firstName.dropLast(".zip".length)
            val parts = generateSequence(1) { it + 1 }
                .map { "$base.z${it.toString().padStart(2, '0')}" }
                .takeWhile(exists)
            return listOf(firstName) + parts
        }
        return listOf(firstName)
    }

    /** [name] in [folder], also when only the case differs (name.R00 next to name.rar). */
    private fun sibling(folder: File, name: String): File? {
        val exact = File(folder, name)
        if (exact.exists()) return exact
        return folder.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

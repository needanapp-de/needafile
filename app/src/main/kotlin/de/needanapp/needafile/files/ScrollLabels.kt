package de.needanapp.needafile.files

import android.text.format.DateFormat
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.Sorting
import de.needanapp.needafile.core.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the bubble beside the dragged scrollbar handle says for [entry] in a list sorted by [sorting]: the month of
 * its date, its size, its extension, or the first letter of its name, so it names what the order runs by. Folders
 * have no size of their own and show their letter.
 */
fun scrollLabel(entry: FileEntry, sorting: Int): String = when {
    sorting and Sorting.BY_DATE != 0 && entry.modified > 0 -> monthFormat().format(Date(entry.modified))
    sorting and Sorting.BY_SIZE != 0 && !entry.isDirectory -> entry.size.formatSize()
    sorting and Sorting.BY_EXTENSION != 0 && !entry.isDirectory -> entry.name.substringAfterLast('.', "").uppercase().ifEmpty { NO_EXTENSION }
    else -> entry.name.firstLetter()
}

/** The first character of a name, whole even when it takes two chars (e.g. an emoji), in capitals. */
private fun String.firstLetter(): String {
    if (isEmpty()) return ""
    return String(Character.toChars(codePointAt(0))).uppercase()
}

/** Month and year as the language writes them, e.g. "Okt. 2026" or "Oct 2026". */
private fun monthFormat(): SimpleDateFormat {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMyyyy"), locale)
}

private const val NO_EXTENSION = "–"

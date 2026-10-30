package de.needanapp.needafile.files

import android.content.Context
import androidx.annotation.StringRes
import de.needanapp.needafile.R
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.Sorting
import de.needanapp.needafile.core.getMimeType
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Entries under one heading; [key] identifies the heading within its folder, e.g. to remember it collapsed. */
data class EntryGroup(val key: String, val label: String, val items: List<FileEntry>)

/** The kinds of files grouping by type knows, in the order the groups appear. */
enum class FileCategory(@StringRes val label: Int) {
    IMAGES(R.string.group_images),
    VIDEOS(R.string.group_videos),
    AUDIO(R.string.group_audio),
    DOCUMENTS(R.string.group_documents),
    ARCHIVES(R.string.group_archives),
    APPS(R.string.group_apps),
    OTHER(R.string.group_other),
}

fun fileCategory(name: String): FileCategory {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        extension == "apk" || extension == "apks" || extension == "xapk" -> FileCategory.APPS
        extension in ARCHIVE_EXTENSIONS -> FileCategory.ARCHIVES
        extension in DOCUMENT_EXTENSIONS -> FileCategory.DOCUMENTS
        else -> when (name.getMimeType().substringBefore('/')) {
            "image" -> FileCategory.IMAGES
            "video" -> FileCategory.VIDEOS
            "audio" -> FileCategory.AUDIO
            "text" -> FileCategory.DOCUMENTS
            else -> FileCategory.OTHER
        }
    }
}

private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "lzma", "z", "cab", "iso", "jar")
private val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "odt", "rtf", "epub", "md", "txt", "xls", "xlsx", "ods", "csv", "tsv", "ppt", "pptx", "odp", "key",
    "html", "htm", "json", "xml",
)

/**
 * Splits [items] (already sorted) into headed groups: folders first, then the files by [groupBy]. Groups keep the
 * order of their entries; the groups themselves run newest, biggest or A first, the other way round when the list is
 * sorted the other way by the same property.
 */
fun groupEntries(context: Context, items: List<FileEntry>, groupBy: GroupBy, sorting: Int, now: Long = System.currentTimeMillis()): List<EntryGroup> {
    val folders = items.filter { it.isDirectory }
    val slots = LinkedHashMap<Slot, MutableList<FileEntry>>()
    val slotOf = slotFunction(context, groupBy, now)
    items.filterNot { it.isDirectory }.forEach { slots.getOrPut(slotOf(it)) { ArrayList() }.add(it) }

    val isDescending = sorting and Sorting.DESCENDING != 0
    val reverse = when (groupBy) {
        GroupBy.DATE -> sorting and Sorting.BY_DATE != 0 && !isDescending
        GroupBy.SIZE -> sorting and Sorting.BY_SIZE != 0 && !isDescending
        GroupBy.NAME -> sorting and Sorting.BY_NAME != 0 && isDescending
        else -> false
    }
    val ordered = slots.keys.sortedBy { it.rank }.let { sorted ->
        // entries without a date stay at the end either way
        if (reverse) sorted.filter { it.rank != Long.MAX_VALUE }.reversed() + sorted.filter { it.rank == Long.MAX_VALUE } else sorted
    }
    val groups = ordered.map { EntryGroup(it.key, it.label, slots.getValue(it)) }
    return if (folders.isEmpty()) groups else listOf(EntryGroup(FOLDERS_KEY, context.getString(R.string.group_folders), folders)) + groups
}

private data class Slot(val key: String, val label: String, val rank: Long)

private fun slotFunction(context: Context, groupBy: GroupBy, now: Long): (FileEntry) -> Slot = when (groupBy) {
    GroupBy.DATE -> dateSlots(context, now)
    GroupBy.TYPE -> { item ->
        val category = fileCategory(item.name)
        Slot(category.name, context.getString(category.label), category.ordinal.toLong())
    }

    GroupBy.SIZE -> { item ->
        val size = if (item.size < 0) SizeGroup.SMALL else SizeGroup.entries.first { item.size > it.above }
        Slot(size.name, context.getString(size.label), size.ordinal.toLong())
    }

    GroupBy.NAME -> { item -> letterSlot(context, item.name) }
    GroupBy.NONE -> { _ -> Slot("", "", 0) }
}

/** Sizes in decimal units, like the sizes shown in the app; unknown sizes (-1) count as small. */
private enum class SizeGroup(val above: Long, @StringRes val label: Int) {
    HUGE(1_000_000_000L, R.string.group_size_huge),
    LARGE(100_000_000L, R.string.group_size_large),
    MEDIUM(1_000_000L, R.string.group_size_medium),
    SMALL(0L, R.string.group_size_small),
    EMPTY(Long.MIN_VALUE, R.string.group_size_empty),
}

/** Today, yesterday, this week, this month, then each month of this year and each earlier year. */
private fun dateSlots(context: Context, now: Long): (FileEntry) -> Slot {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val today = calendar.timeInMillis
    val yesterday = (calendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
    val week = (calendar.clone() as Calendar).apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.timeInMillis.let {
        // the week's first day can't lie after today
        if (it > today) it - WEEK_MS else it
    }
    val month = (calendar.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    val thisYear = calendar.get(Calendar.YEAR)
    val thisMonthIndex = thisYear * MONTHS + calendar.get(Calendar.MONTH)
    val monthFormat = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
    val itemCalendar = Calendar.getInstance()

    return { item ->
        val modified = item.modified
        when {
            modified <= 0 -> Slot("none", context.getString(R.string.group_no_date), Long.MAX_VALUE)
            modified >= today -> Slot("today", context.getString(R.string.group_today), 0)
            modified >= yesterday -> Slot("yesterday", context.getString(R.string.group_yesterday), 1)
            modified >= week -> Slot("week", context.getString(R.string.group_this_week), 2)
            modified >= month -> Slot("month", context.getString(R.string.group_this_month), 3)
            else -> {
                itemCalendar.timeInMillis = modified
                val year = itemCalendar.get(Calendar.YEAR)
                if (year == thisYear) {
                    val index = year * MONTHS + itemCalendar.get(Calendar.MONTH)
                    Slot("m$index", monthFormat.format(Date(modified)).replaceFirstChar { it.titlecase(Locale.getDefault()) }, MONTH_RANK + thisMonthIndex - index)
                } else {
                    Slot("y$year", year.toString(), MONTH_RANK + (thisYear - year) * MONTHS + MONTHS)
                }
            }
        }
    }
}

/** The first letter without accents ("Ä" goes with "A"); digits share "0–9", anything else "#", both before the letters like in the sorted list. */
private fun letterSlot(context: Context, name: String): Slot {
    val first = name.firstOrNull() ?: return Slot("#", context.getString(R.string.group_symbols), SYMBOLS_RANK)
    return when {
        first.isDigit() -> Slot("0", context.getString(R.string.group_digits), DIGITS_RANK)
        first.isLetter() -> {
            val letter = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
            Slot(letter.toString(), letter.toString(), letter.code.toLong())
        }

        else -> Slot("#", context.getString(R.string.group_symbols), SYMBOLS_RANK)
    }
}

const val FOLDERS_KEY = "folders"
private const val MONTHS = 12
private const val MONTH_RANK = 1000L
private const val DIGITS_RANK = -2L
private const val SYMBOLS_RANK = -1L
private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

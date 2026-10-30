package de.needanapp.needafile.core

import android.content.Context
import android.text.format.DateFormat
import de.needanapp.needafile.settings.config
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bytes as people read them, in steps of 1000: "0 B", "512 kB", "1,4 GB". */
fun Long.formatSize(): String {
    if (this <= 0) return "0 B"
    var value = toDouble()
    var unit = 0
    while (value >= SIZE_STEP && unit < SIZE_UNITS.lastIndex) {
        value /= SIZE_STEP
        unit++
    }
    return DecimalFormat("#,##0.#").format(value) + " " + SIZE_UNITS[unit]
}

fun Int.formatSize() = toLong().formatSize()

/** A point in time as the settings want dates and times shown, e.g. "15.10.2026, 17:30". */
fun Long.formatDate(context: Context): String {
    val pattern = "${context.config.dateFormat}, ${context.timePattern()}"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(this))
}

/** The clock part of [formatDate]: 24 hours or 12 with am/pm, as picked in the settings. */
fun Context.timePattern() = if (config.use24HourFormat) TIME_PATTERN_24 else TIME_PATTERN_12

/** The date layouts the settings offer, stored by their pattern. */
val DATE_PATTERNS = listOf(
    "dd.MM.yyyy",
    "dd/MM/yyyy",
    "MM/dd/yyyy",
    "yyyy-MM-dd",
    LONG_DATE_PATTERN,
    "MMMM d yyyy",
    "MM-dd-yyyy",
    "dd-MM-yyyy",
)

/**
 * The offered layout closest to the phone's own date format: the same order of day, month and year and the same
 * separator, otherwise the day with the month written out.
 */
fun Context.defaultDatePattern(): String {
    val order = String(DateFormat.getDateFormatOrder(this))
    val sample = DateFormat.getDateFormat(this).format(Date(0))
    val separator = sample.firstOrNull { !it.isLetterOrDigit() && !it.isWhitespace() }
    val numeric = when (order) {
        "yMd" -> "yyyy${separator ?: '-'}MM${separator ?: '-'}dd"
        "dMy" -> "dd${separator ?: '.'}MM${separator ?: '.'}yyyy"
        "Mdy" -> "MM${separator ?: '/'}dd${separator ?: '/'}yyyy"
        else -> null
    }
    return numeric?.takeIf { it in DATE_PATTERNS } ?: LONG_DATE_PATTERN
}

const val TIME_PATTERN_12 = "hh:mm a"
const val TIME_PATTERN_24 = "HH:mm"

private const val LONG_DATE_PATTERN = "d MMMM yyyy"
private const val SIZE_STEP = 1000.0
private val SIZE_UNITS = arrayOf("B", "kB", "MB", "GB", "TB", "PB", "EB")

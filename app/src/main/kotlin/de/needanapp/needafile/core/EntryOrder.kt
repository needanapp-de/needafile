package de.needanapp.needafile.core

/** How a folder is sorted, as flags stored per folder in the settings. */
object Sorting {
    const val BY_NAME = 1
    const val BY_DATE = 2
    const val BY_SIZE = 4
    const val BY_EXTENSION = 16
    const val DESCENDING = 1024

    /** Names compare the numbers in them by value: "2" before "10". */
    const val NUMBERS_BY_VALUE = 32768
    const val DEFAULT = BY_NAME or NUMBERS_BY_VALUE

    fun isDescending(sorting: Int) = sorting and DESCENDING != 0
}

/** The order of a list sorted by [sorting]: folders always before files, then by the chosen key. */
fun entryOrder(sorting: Int): Comparator<FileEntry> {
    val byKey: Comparator<FileEntry> = when {
        sorting and Sorting.BY_NAME != 0 -> {
            val names: Comparator<String> = if (sorting and Sorting.NUMBERS_BY_VALUE != 0) NaturalOrder else naturalOrder()
            compareBy(names) { it.name.withoutAccents().lowercase() }
        }
        sorting and Sorting.BY_SIZE != 0 -> compareBy { it.size }
        sorting and Sorting.BY_DATE != 0 -> compareBy { it.modified }
        else -> compareBy { if (it.isDirectory) it.name.lowercase() else it.path.substringAfterLast('.', "").lowercase() }
    }
    val directed = if (Sorting.isDescending(sorting)) byKey.reversed() else byKey
    return compareBy<FileEntry> { !it.isDirectory }.then(directed)
}

/** Compares texts with the numbers in them by value, so "file 2" comes before "file 10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val runA = run(a, i)
            val runB = run(b, j)
            i += runA.length
            j += runB.length
            val result = if (runA[0].isDigit() && runB[0].isDigit()) compareNumbers(runA, runB) else runA.compareTo(runB)
            if (result != 0) return result
        }
        return (a.length - i).compareTo(b.length - j)
    }

    /** Digits of any length: without leading zeros, the longer number is the bigger one. */
    private fun compareNumbers(a: String, b: String): Int {
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
    }

    /** The digits or the other characters starting at [start]. */
    private fun run(text: String, start: Int): String {
        val digits = text[start].isDigit()
        var end = start + 1
        while (end < text.length && text[end].isDigit() == digits) end++
        return text.substring(start, end)
    }
}

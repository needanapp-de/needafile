package de.needanapp.needafile.transfer

import de.needanapp.needafile.core.FileEntry

/** App-wide clipboard for "Copy" or "Cut" → switch tab or folder → "Paste", for phone and server items alike. */
object TransferClipboard {
    var items: List<FileEntry> = emptyList()
        private set

    /** The items were cut: pasting moves them instead of copying. */
    var isCut = false
        private set

    private val listeners = LinkedHashSet<() -> Unit>()

    fun copy(newItems: List<FileEntry>) = put(newItems, cut = false)

    fun cut(newItems: List<FileEntry>) = put(newItems, cut = true)

    fun clear() {
        if (items.isNotEmpty()) {
            items = emptyList()
            isCut = false
            notifyListeners()
        }
    }

    private fun put(newItems: List<FileEntry>, cut: Boolean) {
        items = newItems.toList()
        isCut = cut
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}

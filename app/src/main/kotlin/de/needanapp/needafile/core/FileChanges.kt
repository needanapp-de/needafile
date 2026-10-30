package de.needanapp.needafile.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Deletes [items] in the background, folders with everything inside. [onDone] gets whether all of them are gone, on
 * the main thread.
 */
fun Context.deleteFiles(items: List<FileEntry>, onDone: (Boolean) -> Unit) {
    inBackground {
        val deleted = ArrayList<String>()
        var allGone = true
        items.forEach { item ->
            val file = File(item.path)
            val gone = !file.exists() || file.deleteRecursively()
            if (gone) deleted.add(item.path) else allGone = false
        }
        // MediaStore follows changes made through the file system, scanning only makes sure galleries notice at once
        rescanPaths(deleted)
        runOnMainThread { onDone(allGone) }
    }
}

/**
 * Renames [oldPath] to [newPath] in the background; [onDone] gets the result on the main thread. A change of upper
 * and lower case only goes through a temporary name, as many file systems see both as the same name.
 */
fun Context.renameFile(oldPath: String, newPath: String, onDone: (Boolean) -> Unit) {
    inBackground {
        val source = File(oldPath)
        val target = File(newPath)
        val success = when {
            !source.exists() -> false
            oldPath.equals(newPath, ignoreCase = true) && oldPath != newPath -> {
                val temp = File(source.parentFile, "${source.name}.${System.nanoTime()}.tmp")
                when {
                    !source.renameTo(temp) -> false
                    temp.renameTo(target) -> true
                    else -> {
                        // back to the old name, so nothing is left under the temporary one
                        temp.renameTo(source)
                        false
                    }
                }
            }

            target.exists() -> false
            else -> source.renameTo(target)
        }
        if (success) rescanPaths(listOf(oldPath, newPath))
        runOnMainThread { onDone(success) }
    }
}

/** Writes [path] from the start, creating missing folders on the way; throws when that isn't possible. */
fun openFileForWriting(path: String): OutputStream {
    val file = File(path)
    file.parentFile?.mkdirs()
    return FileOutputStream(file)
}

/** Hides an item by putting a dot in front of its name, or shows it again by taking the dot away. */
fun Context.toggleItemVisibility(path: String, hide: Boolean, onDone: (String) -> Unit = {}) {
    val name = path.getFilenameFromPath()
    if (hide == name.startsWith('.')) {
        onDone(path)
        return
    }
    val newName = if (hide) ".${name.trimStart('.')}" else name.substring(1)
    val newPath = "${path.getParentPath()}/$newName"
    renameFile(path, newPath) { success -> onDone(if (success) newPath else path) }
}

package de.needanapp.needafile.viewer.image

import de.needanapp.needafile.core.getParentPath
import de.needanapp.needafile.remote.BrowserFile
import java.util.UUID

/**
 * The pictures of a vault folder for the viewer, in the order of the file list. They are decrypted one by one as they
 * come on screen, into the cache that is emptied when the vault locks. Kept in memory only, like the vault's keys; the
 * viewer finds them by the token in its intent.
 */
internal object VaultGallery {
    /** A picture in the vault: its vault path and what the vault knows about it. */
    class Entry(val vaultPath: String, val file: BrowserFile)

    /** The pictures of the vault at [location], opened at [start]. */
    class Gallery(val location: String, val entries: List<Entry>, val start: Int)

    private val galleries = HashMap<String, Gallery>()

    // vault folders whose pictures were deleted in the viewer
    private val changedFolders = HashSet<String>()

    @Synchronized
    fun put(gallery: Gallery): String = UUID.randomUUID().toString().also { galleries[it] = gallery }

    @Synchronized
    fun get(token: String): Gallery? = galleries[token]

    @Synchronized
    fun remove(token: String) {
        galleries.remove(token)
    }

    /**
     * Leaves out the picture at [vaultPath] once it was deleted, also when the viewer is created anew, and notes its
     * folder as changed, see [takeChanged].
     */
    @Synchronized
    fun removeEntry(token: String, vaultPath: String) {
        galleries[token]?.let { gallery ->
            val entries = gallery.entries.filterNot { it.vaultPath == vaultPath }
            galleries[token] = Gallery(gallery.location, entries, gallery.start.coerceAtMost((entries.size - 1).coerceAtLeast(0)))
        }
        changedFolders += vaultPath.getParentPath().trimEnd('/')
    }

    /**
     * Whether pictures in the vault folder [path] were deleted in the viewer since the last call. The file list reloads
     * it then on its return; vault folders aren't reloaded every time, as those on a server would cost a request.
     */
    @Synchronized
    fun takeChanged(path: String): Boolean = changedFolders.remove(path.trimEnd('/'))
}

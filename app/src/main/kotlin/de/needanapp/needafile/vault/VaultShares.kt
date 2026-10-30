package de.needanapp.needafile.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import de.needanapp.needafile.remote.BrowserFile
import java.security.SecureRandom

/**
 * Vault files handed to other apps (open with, share, set as). They get a uri of [VaultFileProvider] instead of a
 * decrypted copy: it decrypts only what they read, while they read. Each handover gets a random, unguessable uri;
 * locking the vault takes the apps' permission back and makes every further read fail.
 */
object VaultShares {
    private const val AUTHORITY_SUFFIX = ".vaultfiles"
    private const val TOKEN_BYTES = 16

    /** A file of the vault at [location], [path] inside it, as handed over. */
    class Share internal constructor(val token: String, val location: String, val path: String, val name: String, val size: Long, val uri: Uri) {
        @Volatile
        var isOpen = true
            private set

        /** Readers other apps hold open, each with the thread it reads on. */
        private val readers = HashMap<VaultFileReader, Handler>()

        @Synchronized
        internal fun opened(reader: VaultFileReader, handler: Handler) {
            readers[reader] = handler
        }

        @Synchronized
        internal fun released(reader: VaultFileReader) {
            readers.remove(reader)
        }

        /** No more reads; what the open readers keep decrypted in memory is dropped on their threads. */
        @Synchronized
        internal fun close() {
            isOpen = false
            readers.forEach { (reader, handler) -> handler.post { reader.close() } }
            readers.clear()
        }
    }

    private val shares = HashMap<String, Share>()
    private val random = SecureRandom()

    fun authority(context: Context) = context.packageName + AUTHORITY_SUFFIX

    /** A uri other apps can read the vault file [file] through; [path] is its "vault://" path. */
    fun uriFor(context: Context, path: String, file: BrowserFile): Uri {
        val location = path.getVaultLocation()
        val token = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val uri = Uri.Builder().scheme("content").authority(authority(context)).appendPath(token).appendPath(file.name).build()
        synchronized(this) { shares[token] = Share(token, location, path.getVaultInnerPath(), file.name, file.size, uri) }
        return uri
    }

    /** The handover behind [uri], null when there is none (any more). */
    @Synchronized
    fun get(uri: Uri): Share? = uri.pathSegments.firstOrNull()?.let { shares[it] }

    /** Whether [uri] is one of [VaultFileProvider]'s. */
    fun isVaultUri(context: Context, uri: Uri) = uri.authority == authority(context)

    /** The vault at [location] locks: no app can read its files any more. */
    fun revoke(context: Context, location: String) {
        val closed = synchronized(this) {
            shares.values.filter { it.location == location }.onEach { shares.remove(it.token) }
        }
        closed.forEach { share ->
            share.close()
            context.revokeUriPermission(share.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

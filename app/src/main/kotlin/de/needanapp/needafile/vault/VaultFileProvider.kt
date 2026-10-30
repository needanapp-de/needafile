package de.needanapp.needafile.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import de.needanapp.needafile.core.getMimeType
import java.io.FileNotFoundException

/**
 * Lets other apps read vault files handed to them ([VaultShares]) without a decrypted copy anywhere: they get a file
 * descriptor whose reads are answered here, decrypting only the part asked for. Once the vault locks, every read fails.
 * Read only.
 */
class VaultFileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val share = VaultShares.get(uri) ?: return null
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        val values = columns.map { if (it == OpenableColumns.DISPLAY_NAME) share.name else share.size.takeIf { size -> size >= 0 } }
        return MatrixCursor(columns.toTypedArray(), 1).apply { addRow(values) }
    }

    override fun getType(uri: Uri): String? = VaultShares.get(uri)?.name?.getMimeType()?.ifEmpty { null }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) throw FileNotFoundException("Vault files can only be read")
        val share = VaultShares.get(uri)?.takeIf { it.isOpen && it.size >= 0 } ?: throw FileNotFoundException("Not available")
        val reader = try {
            VaultSessions.get(share.location).openReader(share.path, share.size)
        } catch (e: Exception) {
            throw FileNotFoundException("Not available").apply { initCause(e) }
        }
        val thread = HandlerThread("vault-read").apply { start() }
        val handler = Handler(thread.looper)
        share.opened(reader, handler)
        val storage = context!!.getSystemService(StorageManager::class.java)
        return try {
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Reading(share, reader, thread), handler)
        } catch (e: Exception) {
            share.released(reader)
            thread.quitSafely()
            throw FileNotFoundException("Not available").apply { initCause(e) }
        }
    }

    /** Answers the reads of one opened file descriptor, on its own thread. */
    private class Reading(private val share: VaultShares.Share, private val reader: VaultFileReader, private val thread: HandlerThread) :
        ProxyFileDescriptorCallback() {
        override fun onGetSize() = reader.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (!share.isOpen) throw ErrnoException("read", OsConstants.EIO)
            return try {
                reader.read(offset, size, data)
            } catch (e: Exception) {
                throw ErrnoException("read", OsConstants.EIO, e)
            }
        }

        override fun onRelease() {
            reader.close()
            share.released(reader)
            thread.quitSafely()
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}

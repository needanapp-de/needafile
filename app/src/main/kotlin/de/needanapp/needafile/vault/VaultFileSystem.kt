package de.needanapp.needafile.vault

import android.os.SystemClock
import com.google.common.io.BaseEncoding
import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.remote.BrowserFile
import de.needanapp.needafile.remote.RemoteFileSystem
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.remote.joinRemotePath
import de.needanapp.needafile.transfer.TransferProgress
import org.cryptomator.cryptolib.api.AuthenticationFailedException
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.common.DecryptingReadableByteChannel
import org.cryptomator.cryptolib.common.EncryptingWritableByteChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

/**
 * An unlocked Cryptomator vault seen as a folder tree, with the same interface as a server so it fits into tabs,
 * clipboard and background copies. Paths are cleartext paths inside the vault starting with "/".
 *
 * The layout (format 7 and 8): every folder has a random id; its content lies in "d/XX/YYYY…", named after the hash of
 * that id. An entry is named by its encrypted cleartext name plus ".c9r": a file there holds the encrypted content, a
 * folder there holds "dir.c9r" with the id of the folder it stands for. Encrypted names longer than the threshold are
 * shortened to a hash plus ".c9s", a folder holding the full name in "name.c9s" and the content in "contents.c9r" (or
 * "dir.c9r"). Each content folder also keeps its own id, encrypted, in "dirid.c9r" for repairs.
 */
class VaultFileSystem(private val storage: VaultStorage, opened: OpenedVault) : RemoteFileSystem {
    private val cryptor: Cryptor = opened.cryptor
    private val shorteningThreshold = opened.shorteningThreshold
    private val headerSize = cryptor.fileHeaderCryptor().headerSize()

    /** A folder of the vault: its id and where its encrypted content lies. */
    private class CipherDir(val dirId: String, val path: String)

    /** The encrypted entry standing for [name] in the folder [parent]. */
    private inner class Node(val parent: CipherDir, val name: String) {
        val encryptedName = encryptName(name, parent.dirId) + FILE_SUFFIX
        val isShortened = encryptedName.length > shorteningThreshold
        val storedName = if (isShortened) shortName(encryptedName) else encryptedName
        val path = "${parent.path}/$storedName"

        /** Where the encrypted content of a file lies. */
        val contentPath = if (isShortened) "$path/$CONTENTS_FILE" else path
        val dirFilePath = "$path/$DIR_FILE"
        val nameFilePath = "$path/$NAME_FILE"
    }

    private val root = CipherDir(VaultAccess.ROOT_DIR_ID, contentPathOf(cryptor, VaultAccess.ROOT_DIR_ID))

    /** The folders found so far by their cleartext path, so going deeper doesn't read every id above again. */
    private val folders = HashMap<String, CipherDir>()

    override fun testConnection() = Unit

    // region reading

    override fun listFiles(path: String): List<BrowserFile> {
        val folderPath = normalize(path)
        val dir = resolveDir(folderPath)
        val entries = storage.list(dir.path) ?: if (dir === root) throw VaultException(R.string.vault_damaged) else emptyList()
        return entries.mapNotNull { toBrowserFile(folderPath, dir, it) }.sortedWith(BrowserFile.DEFAULT_ORDER)
    }

    private fun toBrowserFile(folderPath: String, dir: CipherDir, entry: StoredEntry): BrowserFile? = when {
        entry.name == DIR_ID_FILE -> null

        entry.name.endsWith(FILE_SUFFIX) -> decryptName(entry.name.removeSuffix(FILE_SUFFIX), dir.dirId)?.let { name ->
            // a folder there stands for a folder of the vault (or a symbolic link, which isn't followed)
            BrowserFile(name, joinRemotePath(folderPath, name), entry.isDirectory, if (entry.isDirectory) 0 else cleartextSize(entry.size), entry.modified)
        }

        entry.name.endsWith(SHORT_SUFFIX) && entry.isDirectory -> shortenedEntry(folderPath, dir, entry)
        else -> null
    }

    /** An entry whose name was too long: its full name is read from inside, and what else lies there tells its kind. */
    private fun shortenedEntry(folderPath: String, dir: CipherDir, entry: StoredEntry): BrowserFile? = try {
        val inside = storage.list("${dir.path}/${entry.name}").orEmpty()
        val fullName = String(storage.readBytes("${dir.path}/${entry.name}/$NAME_FILE"), Charsets.UTF_8).trim()
        val name = decryptName(fullName.removeSuffix(FILE_SUFFIX), dir.dirId)
        val contents = inside.firstOrNull { it.name == CONTENTS_FILE && !it.isDirectory }
        when {
            name == null -> null
            contents != null -> BrowserFile(name, joinRemotePath(folderPath, name), false, cleartextSize(contents.size), contents.modified)
            inside.any { it.name == DIR_FILE } -> BrowserFile(name, joinRemotePath(folderPath, name), true, 0, entry.modified)
            else -> null
        }
    } catch (e: TransferCancelledException) {
        throw e
    } catch (e: IOException) {
        // one damaged entry doesn't hide the rest of the folder
        null
    }

    /**
     * Decrypts [items] (folders with their content) into [targetDir], replacing what is there; returns the files and
     * folders written at the top.
     */
    override fun download(items: List<BrowserFile>, targetDir: File, isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): List<File> {
        val files = ArrayList<Pair<BrowserFile, File>>()
        val createdFolders = ArrayList<File>()
        fun collect(item: BrowserFile, target: File) {
            if (isCancelled()) throw TransferCancelledException()
            if (item.isDirectory) {
                createdFolders.add(target)
                listFiles(item.path).forEach { collect(it, File(target, it.name)) }
            } else {
                files.add(item to target)
            }
        }

        val written = items.map { item -> File(targetDir, item.name).also { collect(item, it) } }
        createdFolders.forEach { if (!it.isDirectory && !it.mkdirs()) throw IOException("Can't create ${it.path}") }
        val progress = ProgressCounter(files.size, files.sumOf { it.first.size.coerceAtLeast(0) }, onProgress)
        files.forEach { (item, target) ->
            if (isCancelled()) throw TransferCancelledException()
            progress.startFile(item.name)
            decryptFile(item.path, target, isCancelled, progress)
            if (item.lastModified > 0) target.setLastModified(item.lastModified)
        }
        return written
    }

    /** Reads the file [path] piece by piece, decrypting only what is read; [size] is its cleartext size. Blocks. */
    fun openReader(path: String, size: Long) = VaultFileReader(storage, nodeOf(path).contentPath, cryptor, size)

    private fun decryptFile(path: String, target: File, isCancelled: () -> Boolean, progress: ProgressCounter) {
        val node = nodeOf(path)
        try {
            storage.read(node.contentPath) { input ->
                progress.restartFile()
                FileOutputStream(target).use { output -> decrypt(input, output, isCancelled, progress) }
            }
        } catch (e: Exception) {
            target.delete()
            if (e is IOException && e.cause is AuthenticationFailedException) throw VaultException(R.string.vault_file_damaged, node.name, cause = e)
            throw e
        }
    }

    private fun decrypt(input: InputStream, output: OutputStream, isCancelled: () -> Boolean, progress: ProgressCounter?) {
        val channel = DecryptingReadableByteChannel(Channels.newChannel(input), cryptor, true)
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        while (true) {
            if (isCancelled()) throw TransferCancelledException()
            buffer.clear()
            val count = channel.read(buffer)
            if (count < 0) break
            output.write(buffer.array(), 0, count)
            progress?.add(count.toLong())
        }
    }

    // endregion

    // region writing

    /** Encrypts the phone's [items] (folders with their content) into [targetDir]; files already there are replaced. */
    override fun upload(items: List<File>, targetDir: String, isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit) {
        val files = items.flatMap { it.walkTopDown().filter { file -> file.isFile }.toList() }
        val progress = ProgressCounter(files.size, files.sumOf { it.length() }, onProgress)
        uploadInto(items, resolveDir(normalize(targetDir)), isCancelled, progress)
    }

    private fun uploadInto(items: List<File>, dir: CipherDir, isCancelled: () -> Boolean, progress: ProgressCounter) {
        val present = storage.list(dir.path).orEmpty().associateBy { it.name }
        items.forEach { item ->
            if (isCancelled()) throw TransferCancelledException()
            val node = Node(dir, composed(item.name))
            val existing = present[node.storedName]?.let { kindOf(node, it) }
            if (item.isDirectory) {
                if (existing == Kind.FILE) throw LocalizedException(R.string.transfer_kind_conflict, item.name)
                val childDir = if (existing == Kind.FOLDER) readDir(node) else createFolderNode(node)
                uploadInto(item.listFiles()?.toList().orEmpty(), childDir, isCancelled, progress)
            } else {
                if (existing == Kind.FOLDER) throw LocalizedException(R.string.transfer_kind_conflict, item.name)
                progress.startFile(item.name)
                encryptFile(item, node, isNew = existing == null, isCancelled, progress)
            }
        }
    }

    private fun encryptFile(file: File, node: Node, isNew: Boolean, isCancelled: () -> Boolean, progress: ProgressCounter) {
        if (node.isShortened && isNew) {
            storage.makeFolder(node.path)
            storage.writeBytes(node.nameFilePath, node.encryptedName.toByteArray(Charsets.UTF_8))
        }
        try {
            storage.write(node.contentPath, encryptedSize(file.length()), file.lastModified()) { output ->
                progress.restartFile()
                FileInputStream(file).use { input -> encrypt(input, output, isCancelled, progress) }
            }
        } catch (e: Exception) {
            // a half written file can't be decrypted any more, it goes
            runCatching { if (node.isShortened && isNew) storage.deleteTree(node.path) else storage.deleteFile(node.contentPath) }
            throw e
        }
    }

    private fun encrypt(input: InputStream, output: OutputStream, isCancelled: () -> Boolean, progress: ProgressCounter?) {
        val channel = EncryptingWritableByteChannel(Channels.newChannel(NonClosingOutputStream(output)), cryptor)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            if (isCancelled()) throw TransferCancelledException()
            val count = input.read(buffer)
            if (count < 0) break
            channel.write(ByteBuffer.wrap(buffer, 0, count))
            progress?.add(count.toLong())
        }
        // writes the last piece; the stream itself stays open for its owner
        channel.close()
    }

    private fun encryptedSize(cleartextSize: Long) = headerSize + cryptor.fileContentCryptor().ciphertextSize(cleartextSize)

    override fun createFolder(parentPath: String, name: String): String {
        val folderPath = normalize(parentPath)
        val node = Node(resolveDir(folderPath), composed(name))
        if (storage.entry(node.path) != null) throw LocalizedException(R.string.remote_error_name_exists, name)
        createFolderNode(node)
        return joinRemotePath(folderPath, node.name)
    }

    /** Creates the folder [node] stands for: first its content folder with the id backup, last the entry naming its id. */
    private fun createFolderNode(node: Node): CipherDir {
        val dirId = UUID.randomUUID().toString()
        val dir = CipherDir(dirId, contentPathOf(cryptor, dirId))
        createContentFolder(dir)
        storage.makeFolder(node.path)
        if (node.isShortened) storage.writeBytes(node.nameFilePath, node.encryptedName.toByteArray(Charsets.UTF_8))
        storage.writeBytes(node.dirFilePath, dirId.toByteArray(Charsets.UTF_8))
        return dir
    }

    private fun createContentFolder(dir: CipherDir) {
        storage.makeFolder(dir.path)
        val encrypted = ByteArrayOutputStream()
        encrypt(ByteArrayInputStream(dir.dirId.toByteArray(Charsets.US_ASCII)), encrypted, { false }, null)
        storage.writeBytes("${dir.path}/$DIR_ID_FILE", encrypted.toByteArray())
    }

    /** The top folder of a new vault. */
    internal fun createRoot() = createContentFolder(root)

    override fun delete(paths: List<String>) {
        paths.map(::normalize).groupBy(::parentOf).forEach { (folderPath, group) ->
            val dir = resolveDir(folderPath)
            val present = storage.list(dir.path).orEmpty().associateBy { it.name }
            group.forEach { path ->
                val node = Node(dir, nameOf(path))
                val entry = present[node.storedName] ?: return@forEach
                if (kindOf(node, entry) == Kind.FOLDER) {
                    readDirIdOrNull(node)?.let { deleteContent(CipherDir(it, contentPathOf(cryptor, it))) }
                }
                if (entry.isDirectory) storage.deleteTree(node.path) else storage.deleteFile(node.path)
                forgetFolders(path)
            }
        }
    }

    /** Deletes the content folder of [dir] and, before, those of all folders below it. */
    private fun deleteContent(dir: CipherDir) {
        val entries = storage.list(dir.path) ?: return
        entries.filter { it.isDirectory }.forEach { entry ->
            val path = "${dir.path}/${entry.name}"
            val hasDirFile = entry.name.endsWith(FILE_SUFFIX) || storage.list(path).orEmpty().any { it.name == DIR_FILE }
            if (!hasDirFile) return@forEach
            val dirId = try {
                readDirIdAt("$path/$DIR_FILE")
            } catch (e: IOException) {
                // a symbolic link or a broken entry has no content folder of its own
                null
            }
            dirId?.let { deleteContent(CipherDir(it, contentPathOf(cryptor, it))) }
        }
        storage.deleteTree(dir.path)
    }

    /**
     * Moves [path] to [newPath] within the vault, also into another folder: only the entry is renamed, the content
     * (also of folders) stays where it is. Fails when something is at [newPath] already.
     */
    override fun move(path: String, newPath: String) {
        val source = nodeOf(path)
        val target = nodeOf(newPath, isNew = true)
        if (storage.entry(target.path) != null) throw LocalizedException(R.string.remote_error_name_exists, target.name)
        val sourceEntry = storage.entry(source.path) ?: throw VaultException(R.string.vault_item_missing, source.name)
        val isFolder = kindOf(source, sourceEntry) == Kind.FOLDER
        when {
            !source.isShortened && !target.isShortened -> storage.move(source.path, target.path)

            !source.isShortened -> {
                if (isFolder) {
                    storage.move(source.path, target.path)
                } else {
                    storage.makeFolder(target.path)
                    storage.move(source.path, target.contentPath)
                }
                storage.writeBytes(target.nameFilePath, target.encryptedName.toByteArray(Charsets.UTF_8))
            }

            !target.isShortened -> if (isFolder) {
                storage.move(source.path, target.path)
                storage.deleteFile(target.nameFilePath)
            } else {
                storage.move(source.contentPath, target.path)
                storage.deleteTree(source.path)
            }

            else -> {
                storage.move(source.path, target.path)
                storage.writeBytes(target.nameFilePath, target.encryptedName.toByteArray(Charsets.UTF_8))
            }
        }
        forgetFolders(normalize(path))
    }

    // endregion

    // region folders and names

    private enum class Kind { FILE, FOLDER }

    /** Whether [entry], the stored entry of [node], is a file or a folder of the vault. */
    private fun kindOf(node: Node, entry: StoredEntry): Kind = when {
        !entry.isDirectory -> Kind.FILE
        !node.isShortened -> Kind.FOLDER
        storage.list(node.path).orEmpty().any { it.name == CONTENTS_FILE } -> Kind.FILE
        else -> Kind.FOLDER
    }

    /**
     * The entry at [path]. Existing entries are found by their name exactly as listed; names given anew are written in
     * the composed Unicode form ([isNew]), as Cryptomator does, so the same name typed on another system matches.
     */
    private fun nodeOf(path: String, isNew: Boolean = false): Node {
        val normalized = normalize(path)
        return Node(resolveDir(parentOf(normalized)), nameOf(normalized).let { if (isNew) composed(it) else it })
    }

    private fun composed(name: String) = Normalizer.normalize(name, Normalizer.Form.NFC)

    /** The folder at the cleartext [path], reading the ids of the folders on the way that aren't known yet. */
    private fun resolveDir(path: String): CipherDir {
        if (path == "/") return root
        synchronized(folders) { folders[path] }?.let { return it }
        val dir = readDir(Node(resolveDir(parentOf(path)), nameOf(path)))
        synchronized(folders) { folders[path] = dir }
        return dir
    }

    private fun readDir(node: Node): CipherDir {
        val dirId = readDirIdOrNull(node) ?: throw VaultException(R.string.vault_folder_missing, node.name)
        return CipherDir(dirId, contentPathOf(cryptor, dirId))
    }

    private fun readDirIdOrNull(node: Node): String? = try {
        readDirIdAt(node.dirFilePath)
    } catch (e: TransferCancelledException) {
        throw e
    } catch (e: IOException) {
        // gone, or not a folder; a broken connection fails again on asking
        if (storage.entry(node.dirFilePath) == null) null else throw e
    }

    private fun readDirIdAt(path: String): String {
        val dirId = String(storage.readBytes(path, MAX_DIR_ID_LENGTH), Charsets.UTF_8)
        if (dirId.isEmpty() || dirId.any { it.code > ASCII_MAX || it.isISOControl() }) throw VaultException(R.string.vault_damaged)
        return dirId
    }

    private fun forgetFolders(path: String) = synchronized(folders) {
        folders.keys.removeAll { it == path || it.startsWith("$path/") }
    }

    private fun encryptName(name: String, dirId: String): String =
        cryptor.fileNameCryptor().encryptFilename(BASE64URL, name, dirId.toByteArray(Charsets.UTF_8))

    /** The cleartext name, null for names that aren't the vault's (e.g. files a sync tool added). */
    private fun decryptName(encrypted: String, dirId: String): String? = try {
        cryptor.fileNameCryptor().decryptFilename(BASE64URL, encrypted, dirId.toByteArray(Charsets.UTF_8))
    } catch (e: AuthenticationFailedException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun shortName(encryptedName: String): String {
        val hash = MessageDigest.getInstance("SHA-1").digest(encryptedName.toByteArray(Charsets.UTF_8))
        return BASE64URL.encode(hash) + SHORT_SUFFIX
    }

    private fun cleartextSize(encryptedSize: Long): Long = when {
        encryptedSize <= headerSize -> 0
        else -> runCatching { cryptor.fileContentCryptor().cleartextSize(encryptedSize - headerSize) }.getOrDefault(-1)
    }

    // endregion

    /** Forgets the keys; the vault can't be used any more afterwards. */
    override fun close() {
        runCatching { storage.close() }
        cryptor.destroy()
    }

    /** Counts what was copied for [TransferProgress], a few updates a second at most. */
    private class ProgressCounter(private val fileCount: Int, private val bytesTotal: Long, private val onProgress: (TransferProgress) -> Unit) {
        private var fileName = ""
        private var fileNumber = 0
        private var bytesBefore = 0L
        private var bytesInFile = 0L
        private var lastReport = 0L

        fun startFile(name: String) {
            bytesBefore += bytesInFile
            bytesInFile = 0
            fileName = name
            fileNumber++
            report(force = true)
        }

        /** The file is written anew, e.g. after a retry on a new connection. */
        fun restartFile() {
            bytesInFile = 0
        }

        fun add(count: Long) {
            bytesInFile += count
            report(force = false)
        }

        private fun report(force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastReport < PROGRESS_INTERVAL_MS) return
            lastReport = now
            onProgress(TransferProgress(fileName, fileNumber.coerceIn(1, fileCount.coerceAtLeast(1)), fileCount.coerceAtLeast(1), bytesBefore + bytesInFile, bytesTotal))
        }
    }

    companion object {
        private const val FILE_SUFFIX = ".c9r"
        private const val SHORT_SUFFIX = ".c9s"
        private const val DIR_FILE = "dir.c9r"
        private const val CONTENTS_FILE = "contents.c9r"
        private const val NAME_FILE = "name.c9s"
        private const val DIR_ID_FILE = "dirid.c9r"

        /** Folder ids are UUIDs, 36 characters. */
        private const val MAX_DIR_ID_LENGTH = 36
        private const val ASCII_MAX = 127
        private const val PROGRESS_INTERVAL_MS = 150L
        private val BASE64URL = BaseEncoding.base64Url()

        /** Where the content of the folder with [dirId] lies: "d/" plus the hash of the id, split after two characters. */
        internal fun contentPathOf(cryptor: Cryptor, dirId: String): String {
            val hash = cryptor.fileNameCryptor().hashDirectoryId(dirId)
            return "${VaultAccess.DATA_FOLDER}/${hash.substring(0, 2)}/${hash.substring(2)}"
        }

        /** "/a/b" for "a/b/", "/" for the top. */
        private fun normalize(path: String) = "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/")

        private fun parentOf(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }

        private fun nameOf(path: String) = path.substringAfterLast('/')
    }
}

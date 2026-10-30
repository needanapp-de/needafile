package de.needanapp.needafile.root

import de.needanapp.needafile.core.FileEntry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * File access with root rights. Every call starts its own su process and hands it a small shell script on stdin, so
 * nothing stays open between calls. Paths only ever reach a script in single quotes. Everything except [isAvailable]
 * blocks and belongs on a background thread.
 */
object RootShell {
    /** Whether the device has a su binary at all. Cheap, any thread. */
    fun isAvailable(): Boolean = locateSu() != null

    /** Runs `id -u` as root; the root manager may ask the user the first time. True when it ran as uid 0. Blocking. */
    fun requestAccess(): Boolean = try {
        val result = runAsRoot(ID_SCRIPT, SHORT_TIMEOUT_SECONDS)
        result.completed && result.output.trim() == ROOT_UID
    } catch (e: IOException) {
        false
    }

    /** The entries of [folder] read as root. Blocking. Throws java.io.IOException when root is refused or the folder can't be read. */
    fun list(folder: String, withHidden: Boolean, withSizes: Boolean): List<FileEntry> {
        val cleanFolder = cleanPath(folder) ?: throw IOException("Not a usable folder path: $folder")
        val result = runAsRoot(listScript(cleanFolder, withHidden, withSizes), SHORT_TIMEOUT_SECONDS)
        if (!result.completed) throw listingFailure(result, cleanFolder)
        return parseListing(result.output, cleanFolder, withHidden)
    }

    /** Creates the folder [path] together with missing parent folders; true when it exists afterwards. */
    fun createFolder(path: String): Boolean {
        val target = cleanPath(path)?.takeIf { it != ROOT } ?: return false
        val quoted = shellQuote(target)
        val change = Change("mkdir -p -- $quoted && [ -d $quoted ]", listOf(target))
        return applyChanges(listOf(change), SHORT_TIMEOUT_SECONDS) == 1
    }

    /** Creates the empty file [path]; false when anything already exists there, which stays untouched. */
    fun createFile(path: String): Boolean {
        val target = cleanPath(path)?.takeIf { it != ROOT } ?: return false
        val change = Change("( set -C; : > ${shellQuote(target)} ) 2> /dev/null", listOf(target))
        return applyChanges(listOf(change), SHORT_TIMEOUT_SECONDS) == 1
    }

    /** Deletes files and folders (folders with everything in them); returns how many paths are gone afterwards. */
    fun delete(paths: List<String>): Int {
        val changes = paths.mapNotNull { removablePath(it) }.map { path ->
            val quoted = shellQuote(path)
            Change("rm -rf -- $quoted; [ ! -e $quoted ] && [ ! -L $quoted ]", listOf(path))
        }
        return applyChanges(changes, DELETE_TIMEOUT_SECONDS)
    }

    /** Copies (folders with their content) or moves each source into the folder [destination]; returns how many succeeded. */
    fun copyOrMove(sources: List<String>, destination: String, move: Boolean): Int {
        val target = cleanPath(destination) ?: return 0
        val quotedTarget = shellQuote(target)
        val transfer = if (move) "mv --" else "cp -a --"
        val changes = sources.mapNotNull { removablePath(it) }
            .filter { source -> source.parentPath() != target && target != source && !target.startsWith("$source/") }
            .map { source ->
                val touchedPaths = if (move) listOf(source, target) else listOf(target)
                Change("[ -d $quotedTarget ] && $transfer ${shellQuote(source)} $quotedTarget", touchedPaths)
            }
        return applyChanges(changes, timeoutSeconds = null)
    }

    /** [text] in single quotes, so a shell takes it literally whatever it contains. */
    internal fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** The script that prints one line per entry of [folder]; [parseListing] reads its output. */
    internal fun listScript(folder: String, withHidden: Boolean, withSizes: Boolean): String =
        "p=${shellQuote(folder.trimEnd('/'))}\nh=${withHidden.toFlag()}\nz=${withSizes.toFlag()}\n$LIST_SCRIPT"

    /** Turns the output of [listScript] into the entries of [folder]; lines that can't be taken apart are skipped. */
    internal fun parseListing(output: String, folder: String, withHidden: Boolean): List<FileEntry> {
        val prefix = folder.trimEnd('/')
        return output.split('\n').mapNotNull { parseEntry(it, prefix, withHidden) }
    }

    /**
     * The mount point holding [path] when the /proc/mounts text [mounts] lists it read-only. Null when it is writable
     * or [path] lies on shared storage, which is never remounted.
     */
    internal fun readOnlyMountPoint(path: String, mounts: String): String? {
        if (NEVER_REMOUNTED.any { path == it || path.startsWith("$it/") }) return null
        var bestPoint: String? = null
        var bestIsReadOnly = false
        for (line in mounts.split('\n')) {
            val fields = line.split(' ').filter { it.isNotEmpty() }
            if (fields.size < MOUNT_FIELDS) continue
            val point = unescapeMountField(fields[1])
            val holdsPath = point == ROOT || path == point || path.startsWith("$point/")
            // a later line at the same place is mounted on top of the earlier one
            if (holdsPath && point.length >= (bestPoint?.length ?: 0)) {
                bestPoint = point
                bestIsReadOnly = READ_ONLY in fields[3].split(',')
            }
        }
        return bestPoint?.takeIf { bestIsReadOnly }
    }

    /**
     * Starts [command], feeds it [script] on stdin and collects what it prints. Without [timeoutSeconds] it waits as
     * long as the script takes, otherwise the process is ended when the time is up. Throws IOException when the
     * command can't be started or the waiting thread is interrupted.
     */
    internal fun runShell(command: List<String>, script: String, timeoutSeconds: Long?): ShellResult {
        val process = ProcessBuilder(command).start()
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val readers = listOf(
            drain(process.inputStream, output, Int.MAX_VALUE),
            drain(process.errorStream, errors, MAX_ERROR_BYTES),
        )
        // the braces make the shell read the whole script before it runs any part of it
        val input = "{\n$script\n}\nexit\n".toByteArray()
        thread(isDaemon = true) { runCatching { process.outputStream.use { it.write(input) } } }
        try {
            val finished = if (timeoutSeconds == null) {
                process.waitFor()
                true
            } else {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            }
            if (!finished) process.destroyForcibly()
            readers.forEach { it.join(READER_GRACE_MILLIS) }
            return ShellResult(if (finished) process.exitValue() else null, output.toUtf8(), errors.toUtf8())
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for the shell").apply { initCause(e) }
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    /** What a script printed and how it ended; [exitCode] is null when it ran out of time. */
    internal class ShellResult(val exitCode: Int?, val output: String, val errors: String) {
        /** Whether the script ran to its end without an error. */
        val completed get() = exitCode == 0
    }

    /** One change of the file system: [condition] is shell code that ends successfully when the change worked. */
    private class Change(val condition: String, val touchedPaths: List<String>)

    /** The indices of the changes that worked in one su call, and whether that call ran to its end. */
    private class ChangeRun(val succeeded: Set<Int>, val completed: Boolean)

    private fun runAsRoot(script: String, timeoutSeconds: Long?): ShellResult =
        runShell(listOf(locateSu() ?: SU), script, timeoutSeconds)

    /** The su binary from PATH or one of the usual places. */
    private fun locateSu(): String? {
        val pathDirectories = System.getenv("PATH").orEmpty().split(':').filter { it.isNotEmpty() }
        return (SU_DIRECTORIES + pathDirectories).distinct()
            .map { File(it, SU) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.path
    }

    /**
     * Runs [changes] as root and returns how many worked. Failed ones on a read-only mount are tried once more with
     * that mount made writable for the moment.
     */
    private fun applyChanges(changes: List<Change>, timeoutSeconds: Long?): Int {
        if (changes.isEmpty()) return 0
        val firstRun = runChanges(changes, changes.indices.toList(), emptySet(), timeoutSeconds) ?: return 0
        val succeeded = firstRun.succeeded.toMutableSet()
        if (!firstRun.completed || succeeded.size == changes.size) return succeeded.size
        val mounts = readMounts() ?: return succeeded.size
        val retries = changes.indices.filter { it !in succeeded }
            .associateWith { index -> changes[index].touchedPaths.mapNotNullTo(mutableSetOf<String>()) { readOnlyMountPoint(it, mounts) } }
            .filterValues { it.isNotEmpty() }
        if (retries.isNotEmpty()) {
            runChanges(changes, retries.keys.toList(), retries.values.flatten().toSet(), timeoutSeconds)
                ?.let { succeeded += it.succeeded }
        }
        return succeeded.size
    }

    /** Applies the [indices] of [changes] in one su call, with [writableMounts] remounted read-write around them. Null when su can't run. */
    private fun runChanges(changes: List<Change>, indices: List<Int>, writableMounts: Set<String>, timeoutSeconds: Long?): ChangeRun? {
        val script = buildString {
            writableMounts.forEach { appendLine("mount -o rw,remount ${shellQuote(it)} 2> /dev/null") }
            indices.forEach { appendLine("if ${changes[it].condition}; then echo $DONE_MARKER$it; fi") }
            writableMounts.forEach { appendLine("mount -o ro,remount ${shellQuote(it)} 2> /dev/null") }
            append("exit 0")
        }
        val result = try {
            runAsRoot(script, timeoutSeconds)
        } catch (e: IOException) {
            return null
        }
        val expected = indices.toSet()
        val succeeded = result.output.split('\n')
            .filter { it.startsWith(DONE_MARKER) }
            .mapNotNull { it.removePrefix(DONE_MARKER).trim().toIntOrNull() }
            .filterTo(mutableSetOf()) { it in expected }
        return ChangeRun(succeeded, result.completed)
    }

    private fun readMounts(): String? = try {
        File(PROC_MOUNTS).readText()
    } catch (e: IOException) {
        null
    }

    private fun parseEntry(line: String, prefix: String, withHidden: Boolean): FileEntry? {
        val fields = line.split(FIELD_SEPARATOR, limit = LISTING_FIELDS)
        if (fields.size != LISTING_FIELDS) return null
        val isDirectory = when (fields[0]) {
            TYPE_FOLDER -> true
            TYPE_FILE -> false
            else -> return null
        }
        val size = fields[1].toLongOrNull() ?: return null
        val modified = fields[2].toLongOrNull() ?: return null
        val children = fields[3].toIntOrNull() ?: return null
        val name = fields[4]
        if (name.isEmpty() || name == "." || name == ".." || FIELD_SEPARATOR in name) return null
        if (!withHidden && name.startsWith('.')) return null
        return FileEntry(
            path = "$prefix/$name",
            name = name,
            isDirectory = isDirectory,
            children = children,
            size = size,
            modified = TimeUnit.SECONDS.toMillis(modified),
            isSectionTitle = false,
        )
    }

    private fun listingFailure(result: ShellResult, folder: String): IOException {
        val reason = result.errors.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val message = when {
            result.exitCode == null -> "Reading $folder as root took too long"
            reason != null -> "Can't read $folder as root: $reason"
            else -> "Can't read $folder as root (exit code ${result.exitCode})"
        }
        return IOException(message)
    }

    /** [path] without doubled or trailing slashes; null when it isn't absolute or walks through "." or "..". */
    private fun cleanPath(path: String): String? {
        if (!path.startsWith('/') || NUL in path) return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        return ROOT + segments.joinToString("/")
    }

    /** [path] cleaned up when it may be deleted or moved, which the root folder never may. */
    private fun removablePath(path: String): String? = cleanPath(path)?.takeIf { it != ROOT }

    private fun String.parentPath(): String = substringBeforeLast('/').ifEmpty { ROOT }

    private fun unescapeMountField(field: String): String =
        OCTAL_ESCAPE.replace(field) { it.groupValues[1].toInt(8).toChar().toString() }

    private fun Boolean.toFlag() = if (this) "1" else "0"

    private fun ByteArrayOutputStream.toUtf8() = String(toByteArray(), Charsets.UTF_8)

    /** Copies [input] into [target] on a background thread, keeping at most [limit] bytes, so the process never blocks on a full pipe. */
    private fun drain(input: InputStream, target: ByteArrayOutputStream, limit: Int): Thread = thread(isDaemon = true) {
        runCatching {
            val buffer = ByteArray(BUFFER_SIZE)
            var count = input.read(buffer)
            while (count >= 0) {
                val room = limit - target.size()
                if (room > 0) target.write(buffer, 0, minOf(count, room))
                count = input.read(buffer)
            }
        }
    }
}

private const val SU = "su"
private const val ROOT = "/"
private const val ROOT_UID = "0"
private const val ID_SCRIPT = "id -u"
private const val PROC_MOUNTS = "/proc/mounts"
private const val READ_ONLY = "ro"
private const val MOUNT_FIELDS = 4
private const val DONE_MARKER = "ok "
private const val FIELD_SEPARATOR = '/'
private const val LISTING_FIELDS = 5
private const val TYPE_FOLDER = "d"
private const val TYPE_FILE = "f"
private const val NUL = '\u0000'
private const val SHORT_TIMEOUT_SECONDS = 60L
private const val DELETE_TIMEOUT_SECONDS = 300L
private const val READER_GRACE_MILLIS = 5_000L
private const val MAX_ERROR_BYTES = 4_096
private const val BUFFER_SIZE = 8_192

private val SU_DIRECTORIES = listOf(
    "/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin", "/debug_ramdisk",
    "/data/adb/ksu/bin", "/data/adb/ap/bin",
)

/** Shared storage goes through its own file system service and is never remounted. */
private val NEVER_REMOUNTED = listOf("/storage", "/sdcard", "/data/media")

private val OCTAL_ESCAPE = Regex("""\\([0-7]{3})""")

/**
 * Lists the folder p (without trailing slash, empty for the root folder). h=1 includes hidden entries, z=1 reports
 * file sizes. Prints one line per entry: d or f, size in bytes, modification time in seconds, number of entries in a
 * folder and the name, separated by "/", which no name can contain. Folder links count as folders; names with a line
 * break are left out. Exits with 2 when p is no folder and with 3 when it can't be read. All sizes and times come from
 * a single stat call, which keeps large folders fast; an entry it has no line for gets a stat call of its own.
 */
private val LIST_SCRIPT = $$"""
    nl='
    '
    if [ -n "${KSH_VERSION:-}" ]; then
        emit() { print -r -- "$1"; }
    else
        emit() { printf '%s\n' "$1"; }
    fi
    count_children() {
        d=$1
        set -- "$d"/*
        if [ -e "$1" ] || [ -L "$1" ]; then c=$#; else c=0; fi
        [ "$h" = 1 ] || return 0
        for e in "$d"/.*; do
            case ${e##*/} in .|..) continue ;; esac
            if [ -e "$e" ] || [ -L "$e" ]; then c=$((c + 1)); fi
        done
    }
    [ -d "$p/" ] || exit 2
    ls -a -- "$p/" > /dev/null || exit 3
    if [ "$h" = 1 ]; then set -- "$p"/* "$p"/.*; else set -- "$p"/*; fi
    stat -L -c '%s/%Y/%n' -- "$@" 2> /dev/null | {
        IFS= read -r l || l=
        for f; do
            s=
            m=
            if [ "${l#*/*/}" = "${f%%$nl*}" ]; then
                s=${l%%/*}
                m=${l#*/}
                m=${m%%/*}
                rest=$f
                while [ "${rest#*$nl}" != "$rest" ]; do
                    rest=${rest#*$nl}
                    IFS= read -r l || l=
                done
                IFS= read -r l || l=
            fi
            n=${f##*/}
            case $n in .|..|*"$nl"*) continue ;; esac
            if [ -z "$m" ]; then
                case $f in "$p/*"|"$p/.*") [ -e "$f" ] || [ -L "$f" ] || continue ;; esac
                if r=$(stat -c '%s/%Y' -- "$f" 2> /dev/null); then
                    s=${r%%/*}
                    m=${r#*/}
                else
                    s=0
                    m=0
                fi
            fi
            [ "$z" = 1 ] || s=0
            if [ -d "$f" ]; then
                count_children "$f"
                emit "d/0/$m/$c/$n"
            else
                emit "f/$s/$m/0/$n"
            fi
        done
    }
    exit 0
""".trimIndent()

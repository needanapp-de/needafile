package de.needanapp.needafile.archive

import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.transfer.TransferJob
import de.needanapp.needafile.transfer.TransferProgress
import java.io.File

/** Packs [sources] into the archive [request] describes. */
class CompressJob(private val sources: List<String>, private val request: CompressRequest) : TransferJob {
    override fun run(isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): TransferJob.Result {
        if (request.format == ArchiveKind.SEVEN_ZIP) {
            SevenZipArchiver.create(
                sources,
                request.destination,
                request.password,
                request.encryptFileNames,
                request.strength.sevenZipLevel,
                request.solid,
                isCancelled,
                onProgress,
            )
        } else {
            ZipArchiver.create(sources.map(::File), request, isCancelled, onProgress)
        }
        return TransferJob.Result(1, listOf(File(request.destination)))
    }
}

/**
 * Extracts each archive of [archives] (path and password, if known) next to itself. Archives that need a password
 * they weren't given, or whose password was wrong, are skipped and end up in [locked], so they can be asked for
 * afterwards. Any other failure doesn't stop the remaining archives; the first one is reported at the end.
 */
class ExtractJob(private val cacheDir: File, archives: List<Pair<String, String?>>) : TransferJob {
    /** An archive that waits for its password; [isWrong] when the one given didn't fit. */
    class Locked(val path: String, val isWrong: Boolean)

    // every part of a split archive stands for the whole archive, which is extracted once
    private val archives = archives.distinctBy { (path, _) -> ArchiveVolumes.firstVolume(File(path)).path }

    /** Filled while running, read once the job finished. */
    val locked = ArrayList<Locked>()

    override fun run(isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): TransferJob.Result {
        val extractor = ArchiveExtractor(cacheDir)
        val extracted = ArrayList<File>()
        var failure: Exception? = null
        for ((path, password) in archives) {
            if (isCancelled()) throw TransferCancelledException()
            try {
                extracted.add(extractor.extract(path, password, isCancelled = isCancelled, onProgress = onProgress))
            } catch (e: ArchivePasswordException) {
                locked.add(Locked(path, e.isWrong))
            } catch (e: CancelledExtractionException) {
                throw TransferCancelledException()
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        failure?.let { throw it }
        return TransferJob.Result(extracted.size, extracted)
    }
}

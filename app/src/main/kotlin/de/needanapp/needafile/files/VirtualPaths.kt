package de.needanapp.needafile.files

import de.needanapp.needafile.archive.buildArchivePath
import de.needanapp.needafile.archive.getArchiveInnerPath
import de.needanapp.needafile.archive.getArchiveLocation
import de.needanapp.needafile.archive.isArchivePath
import de.needanapp.needafile.remote.buildRemotePath
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.remote.getRemoteServerPath
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.vault.buildVaultPath
import de.needanapp.needafile.vault.getVaultInnerPath
import de.needanapp.needafile.vault.getVaultLocation
import de.needanapp.needafile.vault.isVaultPath

/**
 * Paths outside the phone's file system: on a server, inside an archive or inside an unlocked vault. All are reached
 * via a RemoteFileSystem.
 */
fun String.isVirtualPath() = isRemotePath() || isArchivePath() || isVaultPath()

/** Virtual paths whose content can be changed: servers and vaults, not archives. */
fun String.isWritableVirtualPath() = isRemotePath() || isVaultPath()

/** The path within the server, archive or vault, always starting with "/". */
fun String.getVirtualInnerPath() = when {
    isArchivePath() -> getArchiveInnerPath()
    isVaultPath() -> getVaultInnerPath()
    else -> getRemoteServerPath()
}

/** The path to [innerPath] on the same server, in the same archive or in the same vault as [path]. */
fun buildVirtualPathLike(path: String, innerPath: String) = when {
    path.isArchivePath() -> buildArchivePath(path.getArchiveLocation(), innerPath)
    path.isVaultPath() -> buildVaultPath(path.getVaultLocation(), innerPath)
    else -> buildRemotePath(path.getRemoteServerId(), innerPath)
}

/** The server [path] lies on, also inside an archive or vault there; null on the phone. */
fun serverIdOf(path: String): String? = when {
    path.isRemotePath() -> path.getRemoteServerId()
    path.isArchivePath() -> serverIdOf(path.getArchiveLocation())
    path.isVaultPath() -> serverIdOf(path.getVaultLocation())
    else -> null
}

/** Whether [a] and [b] lie on the same server, in the same archive or in the same vault. */
fun isSameVirtualRoot(a: String, b: String) = when {
    a.isArchivePath() && b.isArchivePath() -> a.getArchiveLocation() == b.getArchiveLocation()
    a.isVaultPath() && b.isVaultPath() -> a.getVaultLocation() == b.getVaultLocation()
    a.isRemotePath() && b.isRemotePath() -> a.getRemoteServerId() == b.getRemoteServerId()
    else -> false
}

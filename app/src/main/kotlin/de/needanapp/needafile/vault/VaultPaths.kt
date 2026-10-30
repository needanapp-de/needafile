package de.needanapp.needafile.vault

import android.net.Uri

/**
 * Files inside an unlocked Cryptomator vault are addressed as "vault://<location of the vault>/<path inside>", the
 * location being the vault's folder on the phone or on a server ("remote://…"), encoded so it fits into one path
 * segment. Like server and archive paths they flow through the regular path based code of the files tab.
 */
const val VAULT_PATH_PREFIX = "vault://"

fun String.isVaultPath() = startsWith(VAULT_PATH_PREFIX)

fun buildVaultPath(location: String, innerPath: String): String {
    val inner = if (innerPath.startsWith("/")) innerPath else "/$innerPath"
    return VAULT_PATH_PREFIX + Uri.encode(location.trimEnd('/')) + inner
}

/** The top of the vault at [location] seen as a folder. */
fun vaultRootPath(location: String) = buildVaultPath(location, "/")

/** Where the vault's folder lies. */
fun String.getVaultLocation(): String = Uri.decode(removePrefix(VAULT_PATH_PREFIX).substringBefore('/'))

/** The path inside the vault, always starting with "/". */
fun String.getVaultInnerPath(): String {
    val withoutPrefix = removePrefix(VAULT_PATH_PREFIX)
    val slashIndex = withoutPrefix.indexOf('/')
    return if (slashIndex == -1) "/" else withoutPrefix.substring(slashIndex)
}

/** A vault path as people read it: the vault's folder and the path inside, e.g. "/storage/…/Tresor/Bild.jpg". */
fun String.readableVaultPath(): String = if (isVaultPath()) getVaultLocation().trimEnd('/') + getVaultInnerPath() else this

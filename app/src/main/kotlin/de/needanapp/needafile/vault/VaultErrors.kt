package de.needanapp.needafile.vault

import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException

/** The vault at [location] is locked, so nothing inside can be read until it is unlocked again. */
class VaultLockedException(val location: String) : LocalizedException(R.string.vault_locked)

/** The password doesn't open the vault. */
class VaultPasswordException : LocalizedException(R.string.vault_wrong_password)

/** Opening, reading or changing a vault failed; [messageRes] tells why. */
class VaultException(messageRes: Int, vararg formatArgs: Any, cause: Throwable? = null) :
    LocalizedException(messageRes, *formatArgs, cause = cause)

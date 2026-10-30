package de.needanapp.needafile.archive

import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException

/** The archive needs a password ([isWrong] = false) or the given one is wrong. */
class ArchivePasswordException(val isWrong: Boolean) :
    LocalizedException(if (isWrong) R.string.archive_wrong_password else R.string.archive_password_required)

/** Extraction or creation failed; [messageRes] tells why. */
class ArchiveException(messageRes: Int, vararg formatArgs: Any, cause: Throwable? = null) :
    LocalizedException(messageRes, *formatArgs, cause = cause)

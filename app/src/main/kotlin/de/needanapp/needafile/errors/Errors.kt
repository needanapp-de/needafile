package de.needanapp.needafile.errors

import android.content.Context
import androidx.annotation.StringRes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import de.needanapp.needafile.R
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** An error whose text comes from a string resource, so it can be translated. [describeError] turns it into text. */
open class LocalizedException(@StringRes val messageRes: Int, vararg val formatArgs: Any, cause: Throwable? = null) : IOException(cause)

/** A user-readable, translated description of [error], also for the usual network errors thrown by the libraries. */
fun Context.describeError(error: Throwable): String {
    generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).forEach { cause ->
        describeKnownError(cause)?.let { return it }
    }
    return error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}

private fun Context.describeKnownError(error: Throwable): String? = when (error) {
    is LocalizedException -> getString(error.messageRes, *error.formatArgs)
    is UnknownHostException -> getString(R.string.remote_error_unknown_host)
    is ConnectException, is NoRouteToHostException -> getString(R.string.remote_error_unreachable)
    is SocketTimeoutException -> getString(R.string.remote_error_timeout)
    is UserAuthException -> getString(R.string.remote_error_wrong_credentials)
    is SSLException -> getString(R.string.remote_error_tls, error.message.orEmpty())
    is SMBApiException -> when (error.status) {
        NtStatus.STATUS_ACCESS_DENIED -> getString(R.string.remote_error_access_denied)
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_BAD_NETWORK_NAME ->
            getString(R.string.remote_error_not_found)
        NtStatus.STATUS_OBJECT_NAME_COLLISION -> getString(R.string.remote_error_already_exists)
        NtStatus.STATUS_DISK_FULL -> getString(R.string.remote_error_disk_full)
        else -> null
    }
    else -> null
}

internal const val MAX_CAUSE_DEPTH = 8

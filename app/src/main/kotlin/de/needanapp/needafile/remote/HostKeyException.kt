package de.needanapp.needafile.remote

import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.errors.MAX_CAUSE_DEPTH
import de.needanapp.needafile.security.KnownCertificates
import de.needanapp.needafile.security.KnownHosts

/** A connection refused until the user decides whether to trust the server's SSH key or TLS certificate. */
sealed interface NeedsTrustDecision

/** The SSH server presented a host key the user hasn't confirmed; HostKeyPrompt asks and stores it. */
class HostKeyException(val rejection: KnownHosts.Rejection, cause: Throwable?) : LocalizedException(
    if (rejection.previous == null) R.string.host_key_unconfirmed else R.string.host_key_changed_error,
    rejection.host,
    cause = cause,
), NeedsTrustDecision

/** The server presented a TLS certificate Android doesn't trust and the user hasn't confirmed; CertificatePrompt asks. */
class UntrustedCertificateException(val rejection: KnownCertificates.Rejection, cause: Throwable?) : LocalizedException(
    if (rejection.previous == null) R.string.certificate_unconfirmed else R.string.certificate_changed_error,
    rejection.host,
    cause = cause,
), NeedsTrustDecision

/**
 * The FTP server has no TLS; it is only used unencrypted once the user agreed, which is remembered for the server
 * [serverId] (and can be changed in its settings).
 */
class PlainFtpRequiredException(val serverId: String, val host: String, cause: Throwable?) :
    LocalizedException(R.string.ftp_no_tls_error, host, cause = cause), NeedsTrustDecision

fun Throwable.findTrustDecision(): NeedsTrustDecision? =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<NeedsTrustDecision>().firstOrNull()

package de.needanapp.needafile.servers

import android.net.http.SslCertificate
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.CardStatus
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.Detail
import de.needanapp.design.components.DetailTile
import de.needanapp.design.components.StatusTile
import de.needanapp.design.components.StatusTone
import de.needanapp.design.components.TileCard
import de.needanapp.needafile.R
import de.needanapp.needafile.remote.HostKeyException
import de.needanapp.needafile.remote.NeedsTrustDecision
import de.needanapp.needafile.remote.PlainFtpRequiredException
import de.needanapp.needafile.remote.RemoteConnections
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.remote.UntrustedCertificateException
import de.needanapp.needafile.security.KnownCertificates
import java.security.cert.X509Certificate

/**
 * Asks about the SSH key or TLS certificate a connection was refused for, or whether to use FTP unencrypted;
 * [onTrusted] runs once the user agreed.
 */
@Composable
fun TrustPrompt(decision: NeedsTrustDecision, onTrusted: () -> Unit, onDismiss: () -> Unit) = when (decision) {
    is HostKeyException -> HostKeyPrompt(decision, onTrusted, onDismiss)
    is UntrustedCertificateException -> CertificatePrompt(decision, onTrusted, onDismiss)
    is PlainFtpRequiredException -> PlainFtpPrompt(decision, onTrusted, onDismiss)
}

/** The FTP server has no TLS: connect unencrypted? A yes is stored with the saved server, then [onTrusted] runs. */
@Composable
private fun PlainFtpPrompt(error: PlainFtpRequiredException, onTrusted: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ContentDialog(
        title = stringResource(R.string.ftp_plain_title),
        confirmLabel = stringResource(R.string.ftp_plain_confirm),
        onConfirm = {
            RemoteServersStore.getServer(context, error.serverId)?.let { server ->
                RemoteServersStore.upsert(context, server.copy(allowPlain = true))
                RemoteConnections.invalidate(server.id)
            }
            onDismiss()
            onTrusted()
        },
        onDismiss = onDismiss,
        isDestructive = true,
        icon = Icons.Outlined.NoEncryption,
    ) {
        Text(stringResource(R.string.ftp_plain_message, error.host))
    }
}

/**
 * Shows the certificate of [error] with its fingerprint, holder, issuer and validity and asks whether to trust it for
 * this server. A changed certificate is a warning with the previous fingerprint below. Nothing is stored unless the
 * user confirms; then [onTrusted] runs.
 */
@Composable
fun CertificatePrompt(error: UntrustedCertificateException, onTrusted: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rejection = error.rejection
    val presented = rejection.presented
    val previous = rejection.previous
    val address = if (rejection.port == DEFAULT_HTTPS_PORT) rejection.host else "${rejection.host}:${rejection.port}"
    val details = SslCertificate(presented)
    val dateFormat = DateFormat.getDateFormat(context)
    val message = when {
        previous != null -> R.string.certificate_changed_message
        rejection.isNameMismatch -> R.string.certificate_name_mismatch_message
        else -> R.string.certificate_unknown_message
    }

    ContentDialog(
        title = stringResource(if (previous == null) R.string.certificate_unknown_title else R.string.certificate_changed_title),
        confirmLabel = stringResource(if (previous == null) R.string.host_key_trust else R.string.certificate_accept_new),
        onConfirm = {
            KnownCertificates(context).trust(rejection.host, rejection.port, presented)
            onDismiss()
            onTrusted()
        },
        onDismiss = onDismiss,
        isDestructive = previous != null,
        icon = if (previous == null) Icons.Outlined.VerifiedUser else Icons.Outlined.GppMaybe,
    ) {
        Text(stringResource(message, address), color = if (previous != null) MaterialTheme.colorScheme.error else Color.Unspecified)
        Spacer(Modifier.height(12.dp))
        val status = when {
            previous != null -> CardStatus(stringResource(R.string.certificate_status_changed), Icons.Outlined.GppMaybe, StatusTone.ERROR)
            rejection.isNameMismatch -> CardStatus(stringResource(R.string.certificate_status_mismatch), Icons.Outlined.GppMaybe, StatusTone.ERROR)
            else -> CardStatus(stringResource(R.string.host_key_status_unknown), Icons.Outlined.VerifiedUser, StatusTone.ACCENT)
        }
        val shown = listOf(
            Detail(stringResource(R.string.certificate_fingerprint), KnownCertificates.fingerprint(presented), isCode = true),
            Detail(stringResource(R.string.certificate_issued_to), issuedTo(details, presented)),
            Detail(stringResource(R.string.certificate_issued_by), details.issuedBy.cName.ifEmpty { details.issuedBy.dName }),
            Detail(
                stringResource(R.string.certificate_validity),
                stringResource(R.string.certificate_validity_value, dateFormat.format(presented.notBefore), dateFormat.format(presented.notAfter)),
            ),
        )
        // the dialog is narrow, so each detail has a line of its own
        TileCard(stringResource(R.string.certificate_card)) {
            line({ m, shape -> StatusTile(status, shape, m) })
            shown.forEach { detail -> line({ m, shape -> DetailTile(detail, shape, m) }) }
        }
        if (previous != null) {
            Spacer(Modifier.height(12.dp))
            TileCard(stringResource(R.string.certificate_card_previous)) {
                val before = Detail(stringResource(R.string.certificate_fingerprint), KnownCertificates.fingerprint(previous), isCode = true)
                line({ m, shape -> DetailTile(before, shape, m) })
            }
        }
    }
}

/** The holder's name and the other names (DNS names, IP addresses) the certificate is valid for. */
private fun issuedTo(details: SslCertificate, certificate: X509Certificate): String {
    val names = runCatching {
        certificate.subjectAlternativeNames.orEmpty()
            .filter { it.size >= 2 && (it[0] == SAN_DNS || it[0] == SAN_IP) }
            .mapNotNull { it[1] as? String }
    }.getOrDefault(emptyList())
    return (listOf(details.issuedTo.cName.ifEmpty { details.issuedTo.dName }) + names).filter { it.isNotEmpty() }.distinct().joinToString("\n")
}

private const val DEFAULT_HTTPS_PORT = 443
private const val SAN_DNS = 2
private const val SAN_IP = 7

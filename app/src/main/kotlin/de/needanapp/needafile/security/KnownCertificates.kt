package de.needanapp.needafile.security

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS certificates the user confirmed for servers Android doesn't trust by itself (self-signed ones, or ones issued
 * for another name), one per "host:port". Like SSH host keys: shown with their fingerprint on first contact, trusted
 * only after confirmation, a different one later is refused with a warning. Never are all certificates accepted.
 */
class KnownCertificates(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(host: String, port: Int): X509Certificate? {
        val encoded = prefs.getString(entryKey(host, port), null) ?: return null
        return runCatching { decode(Base64.decode(encoded, Base64.NO_WRAP)) }.getOrNull()
    }

    fun trust(host: String, port: Int, certificate: X509Certificate) =
        prefs.edit().putString(entryKey(host, port), Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)).apply()

    fun forget(host: String, port: Int) = prefs.edit().remove(entryKey(host, port)).apply()

    private fun isConfirmed(host: String, port: Int, certificate: X509Certificate) = get(host, port)?.encoded?.contentEquals(certificate.encoded) == true

    private fun entryKey(host: String, port: Int) = "${host.lowercase()}:$port"

    /** A certificate that isn't confirmed; [previous] is set when another one was confirmed before. */
    data class Rejection(val host: String, val port: Int, val presented: X509Certificate, val previous: X509Certificate?, val isNameMismatch: Boolean)

    /** Thrown during the TLS handshake for a certificate the user still has to decide about. */
    class RejectedCertificateException(val rejection: Rejection, cause: Throwable?) : CertificateException(cause)

    /**
     * TLS for one server at [host]:[port]: what Android trusts is accepted as usual, otherwise only the certificate the
     * user confirmed for this server. A refused certificate is kept in [rejection] for the prompt.
     */
    inner class Trust(private val host: String, private val port: Int) {
        @Volatile
        var rejection: Rejection? = null
            private set

        val trustManager: X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = systemTrustManager.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException()
                // a new handshake: what an earlier one refused no longer counts
                rejection = null
                try {
                    systemTrustManager.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    if (isConfirmed(host, port, leaf)) return
                    throw RejectedCertificateException(reject(leaf, isNameMismatch = false), e)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
        }

        /** Checks the name like Android does; a confirmed certificate is accepted for this server whatever its name. */
        val hostnameVerifier = HostnameVerifier { hostname: String, session: SSLSession ->
            if (HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)) return@HostnameVerifier true
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return@HostnameVerifier false
            if (isConfirmed(host, port, leaf)) return@HostnameVerifier true
            reject(leaf, isNameMismatch = true)
            false
        }

        val socketFactory: SSLSocketFactory by lazy {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
        }

        private fun reject(certificate: X509Certificate, isNameMismatch: Boolean) =
            Rejection(host, port, certificate, get(host, port), isNameMismatch).also { rejection = it }

        /**
         * The rejection behind a failed connection, if it failed because of the certificate: found among the causes, or,
         * as TLS errors don't always keep them (and a wrong name is only told by the hostname check), the one noted last.
         */
        fun rejectionOf(error: Throwable): Rejection? {
            val causes = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            causes.filterIsInstance<RejectedCertificateException>().firstOrNull()?.let { return it.rejection }
            return rejection.takeIf { causes.any { it is SSLException } }
        }
    }

    companion object {
        internal const val PREFS_NAME = "known_certificates"
        private const val MAX_CAUSE_DEPTH = 8

        private val systemTrustManager: X509TrustManager by lazy {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        private fun decode(encoded: ByteArray) =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate

        /** "AB:CD:…", the SHA-256 fingerprint as browsers show it. */
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
    }
}

package de.needanapp.needafile.security

import android.content.Context
import android.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

/**
 * SSH host keys the user confirmed, one per "host:port" (trust on first use, like OpenSSH's known_hosts).
 * Stored as key type plus the Base64 key blob in a preferences file of its own.
 */
class KnownHosts(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(host: String, port: Int): HostKey? {
        val entry = prefs.getString(entryKey(host, port), null) ?: return null
        val (type, blob) = entry.split(' ', limit = 2).takeIf { it.size == 2 } ?: return null
        return HostKey(type, blob)
    }

    fun trust(host: String, port: Int, key: HostKey) = prefs.edit().putString(entryKey(host, port), "${key.type} ${key.blob}").apply()

    fun forget(host: String, port: Int) = prefs.edit().remove(entryKey(host, port)).apply()

    private fun entryKey(host: String, port: Int) = "${host.lowercase()}:$port"

    /** A host key as the server presents it; [fingerprint] is shown like OpenSSH does ("SHA256:…"). */
    data class HostKey(val type: String, val blob: String) {
        val fingerprint: String
            get() {
                val digest = MessageDigest.getInstance("SHA-256").digest(Base64.decode(blob, Base64.NO_WRAP))
                return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
            }

        companion object {
            fun of(key: PublicKey) = HostKey(
                KeyType.fromKey(key).toString(),
                Base64.encodeToString(Buffer.PlainBuffer().putPublicKey(key).compactData, Base64.NO_WRAP),
            )
        }
    }

    /**
     * Accepts only the confirmed key of a host. Anything else is refused and remembered in [rejection], so the
     * caller can ask the user after sshj aborted the connection.
     */
    inner class Verifier : HostKeyVerifier {
        var rejection: Rejection? = null
            private set

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val presented = HostKey.of(key)
            val known = get(hostname, port)
            if (known == presented) return true
            rejection = Rejection(hostname, port, presented, known)
            return false
        }

        // lets sshj negotiate the key type we already know, so a server with several keys isn't taken for a changed one
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = listOfNotNull(get(hostname, port)?.type)
    }

    /** A key that isn't confirmed yet; [previous] is set when the host had a different key before. */
    data class Rejection(val host: String, val port: Int, val presented: HostKey, val previous: HostKey?)

    companion object {
        internal const val PREFS_NAME = "known_hosts"
    }
}

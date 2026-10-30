package de.needanapp.needafile.vault

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.needanapp.needafile.R
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The vault configuration of format 8 ("vault.cryptomator"): a JSON Web Token naming the format, the ciphers and how
 * long encrypted names get before they are shortened, signed with the vault's key. The key itself is found through
 * the token's key id, for password vaults "masterkeyfile:masterkey.cryptomator".
 */
internal class VaultConfigToken private constructor(
    private val header: JsonObject,
    private val payload: JsonObject,
    private val signingInput: ByteArray,
    private val signature: ByteArray,
) {
    val keyId: String? get() = header.get(KEY_ID)?.takeIf { it.isJsonPrimitive }?.asString

    val format: Int get() = payload.intOrNull(FORMAT) ?: 0

    val cipherCombo: String? get() = payload.get(CIPHER_COMBO)?.takeIf { it.isJsonPrimitive }?.asString

    val shorteningThreshold: Int get() = payload.intOrNull(SHORTENING_THRESHOLD) ?: DEFAULT_SHORTENING_THRESHOLD

    /** Whether the token was signed with [rawKey], the 64 bytes of the vault's key. */
    fun isSignedWith(rawKey: ByteArray): Boolean {
        val algorithm = MAC_ALGORITHMS[header.get(ALGORITHM)?.takeIf { it.isJsonPrimitive }?.asString] ?: return false
        val expected = mac(algorithm, rawKey, signingInput)
        return MessageDigest.isEqual(expected, signature)
    }

    companion object {
        private const val KEY_ID = "kid"
        private const val ALGORITHM = "alg"
        private const val FORMAT = "format"
        private const val CIPHER_COMBO = "cipherCombo"
        private const val SHORTENING_THRESHOLD = "shorteningThreshold"
        const val DEFAULT_SHORTENING_THRESHOLD = 220
        private val MAC_ALGORITHMS = mapOf("HS256" to "HmacSHA256", "HS384" to "HmacSHA384", "HS512" to "HmacSHA512")

        /** Reads [token] without checking its signature yet, that needs the key. */
        fun parse(token: String): VaultConfigToken {
            val parts = token.trim().split('.')
            if (parts.size != 3) throw VaultException(R.string.vault_damaged)
            try {
                val decoder = Base64.getUrlDecoder()
                val header = JsonParser.parseString(String(decoder.decode(parts[0]), Charsets.UTF_8)).asJsonObject
                val payload = JsonParser.parseString(String(decoder.decode(parts[1]), Charsets.UTF_8)).asJsonObject
                return VaultConfigToken(header, payload, "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII), decoder.decode(parts[2]))
            } catch (e: RuntimeException) {
                throw VaultException(R.string.vault_damaged, cause = e)
            }
        }

        /** A new configuration for format 8, signed with [rawKey]. */
        fun create(rawKey: ByteArray, keyId: String, cipherCombo: String, shorteningThreshold: Int): String {
            val header = JsonObject().apply {
                addProperty(KEY_ID, keyId)
                addProperty("typ", "JWT")
                addProperty(ALGORITHM, "HS256")
            }
            val payload = JsonObject().apply {
                addProperty("jti", UUID.randomUUID().toString())
                addProperty(FORMAT, 8)
                addProperty(CIPHER_COMBO, cipherCombo)
                addProperty(SHORTENING_THRESHOLD, shorteningThreshold)
            }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val signingInput = encoder.encodeToString(header.toString().toByteArray(Charsets.UTF_8)) + "." +
                encoder.encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
            val signature = mac("HmacSHA256", rawKey, signingInput.toByteArray(Charsets.US_ASCII))
            return signingInput + "." + encoder.encodeToString(signature)
        }

        private fun mac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance(algorithm).apply { init(SecretKeySpec(key, algorithm)) }.doFinal(data)

        private fun JsonObject.intOrNull(name: String) = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    }
}

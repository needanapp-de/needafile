package de.needanapp.needafile.vault

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import de.needanapp.needafile.R
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Remembers a vault's password behind the fingerprint: it is encrypted with a key of the Android Keystore that only
 * works right after a strong biometric check, and that becomes invalid as soon as fingerprints are added or removed.
 * Only the encrypted password is stored ([VaultStore]).
 */
object VaultBiometrics {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /**
     * Why the stored password couldn't be used: backed out of the fingerprint, "Enter password" chosen instead, the
     * fingerprints changed since, or anything else such as too many attempts.
     */
    enum class Failure { CANCELLED, USE_PASSWORD, INVALIDATED, ERROR }

    fun isAvailable(context: Context) =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Asks for the fingerprint and stores [password] for [location] behind it; [onDone] tells whether it was stored. */
    fun save(activity: Activity, location: String, password: String, onDone: (Boolean) -> Unit) {
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, createKey(location)) }
        } catch (e: Exception) {
            onDone(false)
            return
        }
        val name = location.trimEnd('/').substringAfterLast('/')
        authenticate(activity, activity.getString(R.string.vault_fingerprint_save_title, name), cipher, onSuccess = { unlocked ->
            val secret = unlocked.doFinal(password.toByteArray(Charsets.UTF_8))
            VaultStore.setFingerprint(activity, location, encode(secret), encode(unlocked.iv))
            onDone(true)
        }, onFailure = { onDone(false) })
    }

    /** Asks for the fingerprint and hands over the stored password of [location]. */
    fun load(activity: Activity, location: String, onPassword: (String) -> Unit, onFailure: (Failure) -> Unit) {
        val saved = VaultStore.get(activity, location)?.takeIf { it.hasFingerprint }
        val key = keyFor(location)
        if (saved == null || key == null) {
            forget(activity, location)
            onFailure(Failure.INVALIDATED)
            return
        }
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decode(saved.fingerprintIv!!))) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // fingerprints were added or removed since: the password has to be typed once more
            forget(activity, location)
            onFailure(Failure.INVALIDATED)
            return
        } catch (e: Exception) {
            onFailure(Failure.ERROR)
            return
        }
        val name = location.trimEnd('/').substringAfterLast('/')
        authenticate(activity, activity.getString(R.string.vault_fingerprint_title, name), cipher, onSuccess = { unlocked ->
            onPassword(String(unlocked.doFinal(decode(saved.fingerprintSecret!!)), Charsets.UTF_8))
        }, onFailure = onFailure)
    }

    /** Forgets the stored password of [location] and its key. */
    fun forget(context: Context, location: String) {
        VaultStore.setFingerprint(context, location, null, null)
        runCatching { keyStore().deleteEntry(alias(location)) }
    }

    private fun authenticate(activity: Activity, title: String, cipher: Cipher, onSuccess: (Cipher) -> Unit, onFailure: (Failure) -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setNegativeButton(activity.getString(R.string.vault_enter_password), activity.mainExecutor) { _, _ -> onFailure(Failure.USE_PASSWORD) }
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val unlocked = result.cryptoObject?.cipher
                try {
                    if (unlocked == null) onFailure(Failure.ERROR) else onSuccess(unlocked)
                } catch (e: Exception) {
                    onFailure(Failure.ERROR)
                }
            }

            // the negative button only calls its own listener, so this is backing out or e.g. a lockout
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(
                when (errorCode) {
                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> Failure.CANCELLED
                    else -> Failure.ERROR
                },
            )
        })
    }

    private fun createKey(location: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias(location), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyFor(location: String) = (keyStore().getEntry(alias(location), null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun alias(location: String) =
        "vault_" + MessageDigest.getInstance("SHA-256").digest(location.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
}

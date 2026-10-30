package de.needanapp.needafile.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Bundle
import android.util.Base64
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.needanapp.needafile.R
import de.needanapp.needafile.settings.config
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** What a protection guards; so far only opening the app. */
enum class ProtectionKind(val key: String) { APP("app") }

/** How the user proves who they are. */
enum class LockType(@StringRes val label: Int) {
    PATTERN(R.string.pattern),
    PIN(R.string.pin),
    BIOMETRIC(R.string.biometrics),
}

/** Whether fingerprint (or face) can protect: ready, waiting for one to be set up in Android, or not on this device. */
enum class BiometricState { READY, NOT_SET_UP, UNAVAILABLE }

/** A protection that is switched on; [sealed] is the stored form of the PIN or pattern (see [Protections.seal]), empty for [LockType.BIOMETRIC]. */
data class Protection(val type: LockType, val sealed: String)

/**
 * The stored protections. A PIN or pattern is never kept itself, only a salted PBKDF2 hash of it; a pattern counts
 * as the numbers of its dots in the order drawn, row by row from 0 to 8.
 */
object Protections {
    fun get(context: Context, kind: ProtectionKind): Protection? {
        val prefs = context.config.prefs
        val type = prefs.getString(typeKey(kind), null)?.let { name -> LockType.entries.firstOrNull { it.name == name } } ?: return null
        return Protection(type, prefs.getString(sealedKey(kind), "").orEmpty())
    }

    /** Switches [kind] on with [protection], or off with null. */
    fun set(context: Context, kind: ProtectionKind, protection: Protection?) {
        val editor = context.config.prefs.edit()
        if (protection == null) {
            editor.remove(typeKey(kind)).remove(sealedKey(kind))
        } else {
            editor.putString(typeKey(kind), protection.type.name).putString(sealedKey(kind), protection.sealed)
        }
        editor.apply()
    }

    /** The stored form of [secret]: random salt and hash, so the same PIN never looks the same twice. */
    fun seal(secret: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return "${encode(salt)}$SEPARATOR${encode(derive(secret, salt))}"
    }

    /** Whether [secret] is the one [sealed] was made from; compares in constant time. */
    fun matches(secret: String, sealed: String): Boolean {
        val salt = decode(sealed.substringBefore(SEPARATOR, "")) ?: return false
        val hash = decode(sealed.substringAfter(SEPARATOR, "")) ?: return false
        return MessageDigest.isEqual(derive(secret, salt), hash)
    }

    private fun derive(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, HASH_ROUNDS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun typeKey(kind: ProtectionKind) = "protection.${kind.key}.type"

    private fun sealedKey(kind: ProtectionKind) = "protection.${kind.key}.sealed"

    // wrong attempts: after MAX_WRONG_ATTEMPTS of them, the next try has to wait LOCKOUT_SECONDS

    /** Counts a wrong attempt; returns the seconds to wait before the next one, 0 if it may follow right away. */
    fun registerFailure(context: Context): Int {
        val prefs = context.config.prefs
        val wrong = prefs.getInt(WRONG_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(WRONG_ATTEMPTS, wrong)
        if (wrong >= MAX_WRONG_ATTEMPTS) editor.putLong(LOCKOUT_SINCE, System.currentTimeMillis())
        editor.apply()
        return if (wrong >= MAX_WRONG_ATTEMPTS) LOCKOUT_SECONDS else 0
    }

    fun registerSuccess(context: Context) {
        context.config.prefs.edit().remove(WRONG_ATTEMPTS).remove(LOCKOUT_SINCE).apply()
    }

    /** Seconds left of a wait after too many wrong attempts, 0 when trying is allowed. */
    fun remainingLockout(context: Context): Int {
        val since = context.config.prefs.getLong(LOCKOUT_SINCE, 0L).takeIf { it > 0 } ?: return 0
        val leftMs = LOCKOUT_SECONDS * MILLIS_PER_SECOND - (System.currentTimeMillis() - since)
        if (leftMs <= 0) {
            registerSuccess(context)
            return 0
        }
        return ((leftMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
    }

    fun biometricState(context: Context) =
        when (context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricState.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricState.NOT_SET_UP
            else -> BiometricState.UNAVAILABLE
        }

    private const val WRONG_ATTEMPTS = "protection.wrong_attempts"
    private const val LOCKOUT_SINCE = "protection.lockout_since"
    private const val MAX_WRONG_ATTEMPTS = 3
    private const val LOCKOUT_SECONDS = 5
    private const val MILLIS_PER_SECOND = 1000L
    private const val SEPARATOR = '$'
    private const val SALT_BYTES = 16
    private const val HASH_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val HASH_ROUNDS = 20_000
    private const val HASH_BITS = 256
}

/**
 * Whether the lock screen has to be passed before the app shows anything. Locked at the start and again when the app
 * was in the background longer than [RELOCK_AFTER_MS].
 */
object AppLock {
    var isLocked by mutableStateOf(false)
        private set

    private var startedActivities = 0
    private var backgroundSince = 0L

    fun start(application: Application) {
        isLocked = Protections.get(application, ProtectionKind.APP) != null
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val wasInBackground = startedActivities == 0 && backgroundSince > 0
                startedActivities++
                if (wasInBackground && System.currentTimeMillis() - backgroundSince > RELOCK_AFTER_MS) {
                    lock(application)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities == 0) backgroundSince = System.currentTimeMillis()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Locks right away, e.g. when the main screen is left with back. */
    fun lock(context: Context) {
        if (Protections.get(context, ProtectionKind.APP) != null) isLocked = true
    }

    fun unlock() {
        isLocked = false
    }

    private const val RELOCK_AFTER_MS = 30_000L
}

package de.needanapp.needafile.vault

import de.needanapp.needafile.R
import org.cryptomator.cryptolib.api.CryptoException
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.api.InvalidPassphraseException
import org.cryptomator.cryptolib.api.Masterkey
import org.cryptomator.cryptolib.common.MasterkeyFileAccess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** An unlocked vault's keys and layout, as [VaultAccess.open] found them. */
class OpenedVault(val cryptor: Cryptor, val format: Int, val shorteningThreshold: Int)

/**
 * Opens, creates and re-keys Cryptomator vaults of format 8 (Cryptomator 1.6 and newer) and 7 (1.5): the password
 * unlocks the key in "masterkey.cryptomator" (scrypt and AES key wrap), format 8 also has "vault.cryptomator" (see
 * [VaultConfigToken]). Vaults of Cryptomator Hub keep their key on the Hub server and aren't supported, nor are the
 * old formats up to 6. All of it blocks, scrypt alone takes a moment.
 */
object VaultAccess {
    const val CONFIG_FILE = "vault.cryptomator"
    const val MASTERKEY_FILE = "masterkey.cryptomator"
    const val DATA_FOLDER = "d"

    /** Cryptomator asks for at least as many characters. */
    const val MIN_PASSWORD_LENGTH = 8

    private const val FORMAT = 8
    private const val LEGACY_FORMAT = 7

    /** What the key file names as its version since format 8, which moved the version into the configuration. */
    private const val KEY_FILE_VERSION = 999
    private const val KEY_SCHEME = "masterkeyfile:"
    private const val BACKUP_SUFFIX = ".bkup"

    private val random = SecureRandom()
    private val keyFiles get() = MasterkeyFileAccess(ByteArray(0), random)

    /** Whether a folder holding [entries] is a vault: its configuration (or key file, format 7) besides the folder "d". */
    fun isVault(entries: Collection<StoredEntry>): Boolean =
        entries.any { it.name == DATA_FOLDER && it.isDirectory } &&
            entries.any { !it.isDirectory && (it.name == CONFIG_FILE || it.name == MASTERKEY_FILE) }

    /** Unlocks the vault in [storage] with [password]; throws [VaultPasswordException] when it doesn't fit. */
    fun open(storage: VaultStorage, password: CharSequence): OpenedVault {
        val layout = readLayout(storage)
        val masterkey = loadKey(storage, layout.keyFile, password)
        try {
            if (layout.config != null && !layout.config.isSignedWith(masterkey.encoded)) throw VaultException(R.string.vault_damaged)
            return OpenedVault(provider(layout.cipherCombo).provide(masterkey.copy(), random), layout.format, layout.shorteningThreshold)
        } finally {
            masterkey.destroy()
        }
    }

    /**
     * Makes the empty folder of [storage] a new vault of format 8 with [password]; returns it unlocked together with
     * its recovery key.
     */
    fun create(storage: VaultStorage, password: CharSequence): Pair<OpenedVault, String> {
        val masterkey = Masterkey.generate(random)
        try {
            val keyFile = ByteArrayOutputStream().also { keyFiles.persist(masterkey, it, password, KEY_FILE_VERSION) }.toByteArray()
            storage.writeBytes(MASTERKEY_FILE, keyFile)
            // the key hands out its own array, the copy is wiped afterwards
            val rawKey = masterkey.encoded.copyOf()
            try {
                val config = VaultConfigToken.create(rawKey, KEY_SCHEME + MASTERKEY_FILE, CryptorProvider.Scheme.SIV_GCM.name, VaultConfigToken.DEFAULT_SHORTENING_THRESHOLD)
                storage.writeBytes(CONFIG_FILE, config.toByteArray(Charsets.US_ASCII))
                val opened = OpenedVault(provider(CryptorProvider.Scheme.SIV_GCM.name).provide(masterkey.copy(), random), FORMAT, VaultConfigToken.DEFAULT_SHORTENING_THRESHOLD)
                VaultFileSystem(storage, opened).createRoot()
                return opened to RecoveryKey.encode(rawKey)
            } finally {
                rawKey.fill(0)
            }
        } finally {
            masterkey.destroy()
        }
    }

    /** Replaces the password; the old key file is kept as backup next to it, as Cryptomator does. */
    fun changePassword(storage: VaultStorage, oldPassword: CharSequence, newPassword: CharSequence) {
        val keyFile = readLayout(storage).keyFile
        val oldBytes = storage.readBytes(keyFile)
        val newBytes = try {
            keyFiles.changePassphrase(oldBytes, oldPassword, newPassword)
        } catch (e: InvalidPassphraseException) {
            throw VaultPasswordException()
        } catch (e: IOException) {
            throw VaultException(R.string.vault_damaged, cause = e)
        }
        replaceKeyFile(storage, keyFile, oldBytes, newBytes)
    }

    /** Sets [newPassword] with the [recoveryKey] instead of the old password. */
    fun resetPassword(storage: VaultStorage, recoveryKey: String, newPassword: CharSequence) {
        val layout = readLayout(storage)
        val rawKey = RecoveryKey.decode(recoveryKey) ?: throw VaultException(R.string.vault_recovery_key_invalid)
        try {
            if (!fitsVault(storage, layout, rawKey)) throw VaultException(R.string.vault_recovery_key_wrong)
            val newBytes = Masterkey(rawKey).use { masterkey ->
                val version = if (layout.config != null) KEY_FILE_VERSION else LEGACY_FORMAT
                ByteArrayOutputStream().also { keyFiles.persist(masterkey, it, newPassword, version) }.toByteArray()
            }
            val oldBytes = storage.entry(layout.keyFile)?.let { storage.readBytes(layout.keyFile) }
            replaceKeyFile(storage, layout.keyFile, oldBytes, newBytes)
        } finally {
            rawKey.fill(0)
        }
    }

    /** The recovery key of the vault in [storage], for writing it down; needs the [password]. */
    fun recoveryKey(storage: VaultStorage, password: CharSequence): String {
        val masterkey = loadKey(storage, readLayout(storage).keyFile, password)
        val rawKey = masterkey.encoded.copyOf()
        try {
            return RecoveryKey.encode(rawKey)
        } finally {
            rawKey.fill(0)
            masterkey.destroy()
        }
    }

    /** Where the key lies and how the vault is laid out, from its configuration or, for format 7, its key file. */
    private class Layout(val config: VaultConfigToken?, val keyFile: String, val format: Int, val cipherCombo: String, val shorteningThreshold: Int)

    private fun readLayout(storage: VaultStorage): Layout {
        val configEntry = storage.entry(CONFIG_FILE)
        if (configEntry != null && !configEntry.isDirectory) {
            val config = VaultConfigToken.parse(String(storage.readBytes(CONFIG_FILE), Charsets.US_ASCII))
            val keyId = config.keyId ?: throw VaultException(R.string.vault_damaged)
            if (!keyId.startsWith(KEY_SCHEME)) throw VaultException(R.string.vault_hub_unsupported)
            // the key file lies next to the configuration, nowhere else
            val keyFile = keyId.removePrefix(KEY_SCHEME)
            if (keyFile.isEmpty() || keyFile.any { it == '/' || it == '\\' } || keyFile == "." || keyFile == "..") throw VaultException(R.string.vault_damaged)
            if (config.format != FORMAT) throw VaultException(R.string.vault_unsupported_format, config.format)
            val cipherCombo = config.cipherCombo ?: throw VaultException(R.string.vault_damaged)
            return Layout(config, keyFile, FORMAT, cipherCombo, config.shorteningThreshold)
        }

        if (storage.entry(MASTERKEY_FILE) == null) throw VaultException(R.string.vault_not_found)
        @Suppress("DEPRECATION")
        val version = try {
            MasterkeyFileAccess.readAllegedVaultVersion(storage.readBytes(MASTERKEY_FILE))
        } catch (e: IOException) {
            throw VaultException(R.string.vault_damaged, cause = e)
        }
        if (version != LEGACY_FORMAT) throw VaultException(R.string.vault_unsupported_format, version)
        return Layout(null, MASTERKEY_FILE, LEGACY_FORMAT, CryptorProvider.Scheme.SIV_CTRMAC.name, VaultConfigToken.DEFAULT_SHORTENING_THRESHOLD)
    }

    private fun loadKey(storage: VaultStorage, keyFile: String, password: CharSequence): Masterkey {
        val bytes = storage.readBytes(keyFile)
        return try {
            keyFiles.load(ByteArrayInputStream(bytes), password)
        } catch (e: InvalidPassphraseException) {
            throw VaultPasswordException()
        } catch (e: IOException) {
            throw VaultException(R.string.vault_damaged, cause = e)
        } catch (e: CryptoException) {
            throw VaultException(R.string.vault_damaged, cause = e)
        } catch (e: IllegalArgumentException) {
            throw VaultException(R.string.vault_damaged, cause = e)
        }
    }

    /** Whether [rawKey] belongs to the vault: it signed the configuration, or for format 7 it finds the top folder. */
    private fun fitsVault(storage: VaultStorage, layout: Layout, rawKey: ByteArray): Boolean {
        layout.config?.let { return it.isSignedWith(rawKey) }
        provider(layout.cipherCombo).provide(Masterkey(rawKey), random).use { cryptor ->
            return storage.entry(VaultFileSystem.contentPathOf(cryptor, ROOT_DIR_ID)) != null
        }
    }

    /** Moves the old key file to a backup named after its content, then writes the new one; puts the old one back on failure. */
    private fun replaceKeyFile(storage: VaultStorage, keyFile: String, oldBytes: ByteArray?, newBytes: ByteArray) {
        if (oldBytes == null) {
            storage.writeBytes(keyFile, newBytes)
            return
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(oldBytes)
        val backup = keyFile + "." + digest.take(4).joinToString("") { "%02X".format(it) } + BACKUP_SUFFIX
        if (storage.entry(backup) != null) storage.deleteFile(backup)
        storage.move(keyFile, backup)
        try {
            storage.writeBytes(keyFile, newBytes)
        } catch (e: Exception) {
            runCatching {
                if (storage.entry(keyFile) != null) storage.deleteFile(keyFile)
                storage.move(backup, keyFile)
            }
            throw e
        }
    }

    private fun provider(cipherCombo: String): CryptorProvider = when (cipherCombo) {
        CryptorProvider.Scheme.SIV_GCM.name -> org.cryptomator.cryptolib.v2.CryptorProviderImpl()
        CryptorProvider.Scheme.SIV_CTRMAC.name -> org.cryptomator.cryptolib.v1.CryptorProviderImpl()
        else -> throw VaultException(R.string.vault_unsupported_cipher, cipherCombo)
    }

    internal const val ROOT_DIR_ID = ""
}

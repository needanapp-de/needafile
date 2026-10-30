package de.needanapp.needafile.vault

import de.needanapp.needafile.remote.BrowserFile
import org.cryptomator.cryptofs.CryptoFileSystemProperties
import org.cryptomator.cryptofs.CryptoFileSystemProvider
import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.api.Masterkey
import org.cryptomator.cryptolib.api.MasterkeyLoader
import org.cryptomator.cryptolib.common.MasterkeyFileAccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Checks needafile's vault code against cryptofs, the library Cryptomator's desktop app uses: each side has to read
 * what the other one wrote, including long (shortened) names, nested folders, empty and multi-chunk files.
 */
class VaultCompatibilityTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val random = SecureRandom()
    private val longName = "Ein sehr langer Dateiname " + "x".repeat(170) + ".txt"
    // long enough to be shortened in the vault, short enough for the phone's own file system (255 bytes)
    private val longFolder = "Ordner mit Überlänge " + "ü".repeat(100)

    private fun content(size: Int) = Random(size).nextBytes(size)

    // region vaults written here, read by cryptofs

    @Test
    fun vaultCreatedHereOpensInCryptofs() {
        val folder = temp.newFolder("eigener")
        val (opened, _) = VaultAccess.create(LocalVaultStorage(folder), PASSWORD)
        val vault = VaultFileSystem(LocalVaultStorage(folder), opened)
        val local = temp.newFolder("lokal")
        val small = File(local, "klein.txt").apply { writeText("Hallo Tresor") }
        val big = File(local, "groß.bin").apply { writeBytes(content(200_000)) }
        val empty = File(local, "leer.dat").apply { writeBytes(ByteArray(0)) }
        val long = File(local, longName).apply { writeBytes(content(40_000)) }
        val tree = File(local, "Baum").apply { mkdirs() }
        File(tree, "innen.txt").writeText("innen")
        File(tree, "Unterordner").mkdirs()
        File(tree, "Unterordner/tief.txt").writeText("tief")

        vault.createFolder("/", "Ordner")
        vault.createFolder("/Ordner", longFolder)
        vault.upload(listOf(small, big, empty, long, tree), "/", { false }, {})
        vault.upload(listOf(small), "/Ordner/$longFolder", { false }, {})
        // renaming and moving, also between short and long names
        vault.move("/klein.txt", "/Ordner/umbenannt.txt")
        vault.move("/$longName", "/Ordner/kurz.txt")
        vault.move("/leer.dat", "/Ordner/$longName")
        vault.move("/Baum", "/Ordner/$longFolder/Baum")
        vault.createFolder("/", "weg")
        vault.upload(listOf(small), "/weg", { false }, {})
        vault.delete(listOf("/weg"))

        withCryptofs(folder.toPath()) { root ->
            assertEquals(setOf("groß.bin", "Ordner"), names(root))
            assertArrayEquals(big.readBytes(), Files.readAllBytes(root.resolve("groß.bin")))
            val ordner = root.resolve("Ordner")
            assertEquals(setOf("umbenannt.txt", "kurz.txt", longName, longFolder), names(ordner))
            assertEquals("Hallo Tresor", Files.readString(ordner.resolve("umbenannt.txt")))
            assertArrayEquals(long.readBytes(), Files.readAllBytes(ordner.resolve("kurz.txt")))
            assertEquals(0L, Files.size(ordner.resolve(longName)))
            val deep = ordner.resolve(longFolder)
            assertEquals(setOf("klein.txt", "Baum"), names(deep))
            assertEquals("tief", Files.readString(deep.resolve("Baum/Unterordner/tief.txt")))
        }
        // the deleted folder took its content folder along: one per remaining folder (Ordner, the long one, Baum,
        // Unterordner) plus the top
        assertEquals(5, contentFolders(folder))
        vault.close()
    }

    // endregion

    // region vaults written by cryptofs, read and changed here

    @Test
    fun cryptofsVaultOpensHere() = cryptofsVaultRoundTrip(CryptorProvider.Scheme.SIV_GCM)

    @Test
    fun cryptofsVaultWithOlderCiphersOpensHere() = cryptofsVaultRoundTrip(CryptorProvider.Scheme.SIV_CTRMAC)

    private fun cryptofsVaultRoundTrip(scheme: CryptorProvider.Scheme) {
        val folder = temp.newFolder("desktop")
        createWithCryptofs(folder.toPath(), scheme)
        val bigContent = content(300_000)
        withCryptofs(folder.toPath(), scheme) { root ->
            Files.writeString(root.resolve("notiz.txt"), "vom Desktop")
            Files.write(root.resolve("bild.jpg"), bigContent)
            Files.createDirectories(root.resolve("A/B/$longFolder"))
            Files.writeString(root.resolve("A/B/$longFolder/$longName"), "lang")
            Files.write(root.resolve("A/leer"), ByteArray(0))
        }

        val vault = VaultFileSystem(LocalVaultStorage(folder), VaultAccess.open(LocalVaultStorage(folder), PASSWORD))
        assertEquals(listOf("A", "bild.jpg", "notiz.txt"), vault.listFiles("/").map { it.name })
        assertEquals(300_000L, vault.listFiles("/").first { it.name == "bild.jpg" }.size)
        assertEquals(listOf(longName), vault.listFiles("/A/B/$longFolder").map { it.name })
        val out = temp.newFolder("raus")
        val files = vault.download(vault.listFiles("/"), out, { false }, {})
        assertEquals(3, files.size)
        assertArrayEquals(bigContent, File(out, "bild.jpg").readBytes())
        assertEquals("lang", File(out, "A/B/$longFolder/$longName").readText())
        assertEquals(0L, File(out, "A/leer").length())

        // changed here, then read by cryptofs again
        vault.move("/notiz.txt", "/A/B/notiz-verschoben.txt")
        vault.move("/A/B/$longFolder", "/Kurz")
        vault.delete(listOf("/bild.jpg"))
        vault.upload(listOf(File(out, "bild.jpg")), "/Kurz", { false }, {})
        vault.close()
        withCryptofs(folder.toPath(), scheme) { root ->
            assertEquals(setOf("A", "Kurz"), names(root))
            assertEquals("vom Desktop", Files.readString(root.resolve("A/B/notiz-verschoben.txt")))
            assertEquals("lang", Files.readString(root.resolve("Kurz/$longName")))
            assertArrayEquals(bigContent, Files.readAllBytes(root.resolve("Kurz/bild.jpg")))
        }
    }

    // endregion

    // region passwords and keys

    @Test
    fun wrongPasswordIsRefused() {
        val folder = temp.newFolder("tresor")
        VaultAccess.create(LocalVaultStorage(folder), PASSWORD).first.cryptor.destroy()
        try {
            VaultAccess.open(LocalVaultStorage(folder), "falsch-falsch")
            fail("opened with a wrong password")
        } catch (e: VaultPasswordException) {
            // expected
        }
    }

    @Test
    fun changedPasswordOpensAndKeepsBackup() {
        val folder = temp.newFolder("tresor")
        VaultAccess.create(LocalVaultStorage(folder), PASSWORD).first.cryptor.destroy()
        VaultAccess.changePassword(LocalVaultStorage(folder), PASSWORD, NEW_PASSWORD)
        VaultAccess.open(LocalVaultStorage(folder), NEW_PASSWORD).cryptor.destroy()
        assertTrue(folder.list()!!.any { it.startsWith("masterkey.cryptomator.") && it.endsWith(".bkup") })
        try {
            VaultAccess.open(LocalVaultStorage(folder), PASSWORD)
            fail("old password still works")
        } catch (e: VaultPasswordException) {
            // expected
        }
        // cryptofs reads the key file written here
        withCryptofs(folder.toPath(), password = NEW_PASSWORD) { root -> assertTrue(names(root).isEmpty()) }
    }

    @Test
    fun recoveryKeyResetsPassword() {
        val folder = temp.newFolder("tresor")
        val (opened, recoveryKey) = VaultAccess.create(LocalVaultStorage(folder), PASSWORD)
        opened.cryptor.destroy()
        assertEquals(44, recoveryKey.split(' ').size)
        assertEquals(recoveryKey, VaultAccess.recoveryKey(LocalVaultStorage(folder), PASSWORD))
        VaultAccess.resetPassword(LocalVaultStorage(folder), recoveryKey.uppercase(), NEW_PASSWORD)
        VaultAccess.open(LocalVaultStorage(folder), NEW_PASSWORD).cryptor.destroy()

        // the key of another vault doesn't fit
        val other = temp.newFolder("anderer")
        VaultAccess.create(LocalVaultStorage(other), PASSWORD).first.cryptor.destroy()
        try {
            VaultAccess.resetPassword(LocalVaultStorage(other), recoveryKey, NEW_PASSWORD)
            fail("foreign recovery key accepted")
        } catch (e: VaultException) {
            // expected
        }
    }

    @Test
    fun recoveryKeyMatchesCryptomatorsWords() {
        // the examples of Cryptomator's own tests: the same words with a wrong last word fail the check
        val valid = "pathway lift abuse plenty export texture gentleman landscape beyond ceiling around leaf cafe charity " +
            "border breakdown victory surely computer cat linger restrict infer crowd live computer true written amazed " +
            "investor boot depth left theory snow whereby terminal weekly reject happiness circuit partial cup ad"
        val rawKey = RecoveryKey.decode(valid)
        assertNotNull(rawKey)
        assertEquals(valid, RecoveryKey.encode(rawKey!!))
        assertNull(RecoveryKey.decode(valid.removeSuffix(" ad") + " wrong"))
        assertNull(RecoveryKey.decode("pathway lift"))
        assertNull(RecoveryKey.decode("Backpfeifengesicht Schweinehund"))
        assertFalse(RecoveryKey.isWord("Schweinehund"))
    }

    @Test
    fun legacyFormatSevenOpens() {
        // format 7 has no configuration file: the key file names the version, the content uses the older ciphers
        val folder = temp.newFolder("alt")
        val masterkey = Masterkey.generate(random)
        val keyFile = java.io.ByteArrayOutputStream().also { MasterkeyFileAccess(ByteArray(0), random).persist(masterkey, it, PASSWORD, 7) }.toByteArray()
        File(folder, VaultAccess.MASTERKEY_FILE).writeBytes(keyFile)
        val cryptor = org.cryptomator.cryptolib.v1.CryptorProviderImpl().provide(masterkey.copy(), random)
        VaultFileSystem(LocalVaultStorage(folder), OpenedVault(cryptor, 7, 220)).apply {
            createRoot()
            upload(listOf(File(temp.newFolder("lokal"), "a.txt").apply { writeText("sieben") }), "/", { false }, {})
        }
        val opened = VaultAccess.open(LocalVaultStorage(folder), PASSWORD)
        assertEquals(7, opened.format)
        val vault = VaultFileSystem(LocalVaultStorage(folder), opened)
        val out = temp.newFolder("raus")
        vault.download(vault.listFiles("/"), out, { false }, {})
        assertEquals("sieben", File(out, "a.txt").readText())
        assertTrue(VaultAccess.isVault(LocalVaultStorage(folder).list("")!!))
    }

    // endregion

    private fun names(folder: Path): Set<String> = Files.list(folder).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }

    private fun contentFolders(vault: File) = File(vault, "d").listFiles()!!.sumOf { it.listFiles()!!.size }

    /** Creates a vault the way Cryptomator's desktop app does: key file with password, configuration via cryptofs. */
    private fun createWithCryptofs(path: Path, scheme: CryptorProvider.Scheme) {
        val masterkey = Masterkey.generate(random)
        MasterkeyFileAccess(ByteArray(0), random).persist(masterkey, path.resolve(VaultAccess.MASTERKEY_FILE), PASSWORD)
        val properties = CryptoFileSystemProperties.cryptoFileSystemProperties()
            .withCipherCombo(scheme)
            .withKeyLoader(MasterkeyLoader { masterkey.copy() })
            .build()
        CryptoFileSystemProvider.initialize(path, properties, URI.create("masterkeyfile:masterkey.cryptomator"))
    }

    private fun withCryptofs(path: Path, scheme: CryptorProvider.Scheme = CryptorProvider.Scheme.SIV_GCM, password: String = PASSWORD, block: (Path) -> Unit) {
        val loader = MasterkeyLoader { keyId ->
            assertEquals("masterkeyfile:masterkey.cryptomator", keyId.toString())
            MasterkeyFileAccess(ByteArray(0), random).load(path.resolve(VaultAccess.MASTERKEY_FILE), password)
        }
        val properties = CryptoFileSystemProperties.cryptoFileSystemProperties().withCipherCombo(scheme).withKeyLoader(loader).build()
        CryptoFileSystemProvider.newFileSystem(path, properties).use { fileSystem -> block(fileSystem.getPath("/")) }
    }

    private companion object {
        const val PASSWORD = "geheim-genug"
        const val NEW_PASSWORD = "noch-geheimer"
    }
}

@Suppress("unused")
private fun BrowserFile.describe() = "$name ($size)"

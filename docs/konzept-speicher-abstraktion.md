# Konzept: Einheitliche Speicher-Abstraktion

Stand: 30.09.2026, Version 0.5.0-alpha. Status: Konzept, noch nicht umgesetzt.

Hinweis 15.10.2026 (0.76.0): Die App läuft inzwischen vollständig mit eigenem Code; der Ist-Stand unten beschreibt
die Lage von damals.

## Ziel

Lokaler Speicher, SD-Karte/USB, Root sowie SMB, SFTP, WebDAV und später FTPS sollen hinter **einer**
Schnittstelle liegen. Alles, was mit Dateien arbeitet (Dateiliste, Übertragungen, Archive,
`DocumentsProvider`, HTTPS-Server, Medien-Streaming, Vorschaubilder), nutzt nur diese Schnittstelle.
Ein neues Protokoll bedeutet dann nur noch eine neue Implementierung.

## Ist-Stand

| Bereich | Heute | Grenzen |
|---|---|---|
| Lokal, SD, OTG, Root | Übernommene Hilfsfunktionen auf Pfad-Strings (`getFileInputStreamSync`, SAF für `Android/data` und SD-Karte, `DocumentFile` für OTG, `RootHelpers`) | über viele Erweiterungsfunktionen verstreut, kein gemeinsames Interface |
| Server | `remote/RemoteFileSystem` mit `listFiles`, `download`, `upload`, `delete`, `rename`, `createFolder`; Pfade `remote://<serverId>/<pfad>`; `RemoteConnections` hält eine Browsing-Verbindung pro Server | Übertragungen nur als „Server ↔ `java.io.File`“, keine Streams (`openRead`/`openWrite`), kein `stat` |
| Übertragungen | `transfer/TransferManager` + `TransferService` (Foreground-Service); drei Fälle: Download, Upload, Server→Server über einen Zwischenordner im Cache | Server→Server kopiert alles doppelt über den Cache; kein Fortsetzen nach Abbruch; SAF- und Root-Ziele gehen nicht |
| Archive | `archive/ArchiveExtractor` und `SevenZipArchiver` auf `java.io.File` | keine Archive auf Servern oder in SAF-Ordnern |

Der Pfad-String `remote://…` hat sich bewährt: Tabs, Sortierung, Zwischenablage und Pfadleiste
funktionieren unverändert. Diese Idee bleibt erhalten.

## Zielbild

### Pfade

`StoragePath` als Wert-Klasse um einen String, rückwärtskompatibel zu heute:

| Schema | Beispiel | Backend |
|---|---|---|
| (kein Schema) | `/storage/emulated/0/Download` | `LocalBackend` (`java.io.File`) |
| `saf://` | `saf://<treeUri>/<relativer pfad>` | `SafBackend` (Android/data, SD-Karte schreiben, OTG) |
| `root://` | `root:///data/local` | `RootBackend` |
| `remote://` | `remote://<serverId>/<pfad>` | `SftpBackend`, `SmbBackend`, `WebDavBackend`, später `FtpsBackend` |
| `archive://` (später) | `archive://<pfad zum archiv>!/<pfad im archiv>` | `ArchiveBackend`, nur lesen |

Gespeicherte Tabs und Einstellungen bleiben gültig; `migrateLegacyRemotePath` zeigt, wie alte
Formate beim Lesen umgeschrieben werden.

### Schnittstelle

```kotlin
interface StorageBackend : Closeable {
    val capabilities: Set<Capability>

    suspend fun list(path: StoragePath): List<StorageEntry>
    suspend fun stat(path: StoragePath): StorageEntry?          // null = existiert nicht

    suspend fun openRead(path: StoragePath, offset: Long = 0): InputStream
    suspend fun openWrite(path: StoragePath, mode: WriteMode): OutputStream   // CREATE_NEW, OVERWRITE, APPEND

    suspend fun createFolder(path: StoragePath)
    suspend fun delete(path: StoragePath)                       // Ordner rekursiv
    suspend fun rename(path: StoragePath, newName: String): StoragePath
    suspend fun move(source: StoragePath, target: StoragePath)  // nur mit Capability SERVER_SIDE_MOVE
    suspend fun copy(source: StoragePath, target: StoragePath)  // nur mit Capability SERVER_SIDE_COPY
    suspend fun setModified(path: StoragePath, time: Long)
}

data class StorageEntry(val path: StoragePath, val name: String, val isFolder: Boolean,
                        val size: Long, val modified: Long, val isHidden: Boolean)

enum class Capability { RANDOM_ACCESS, APPEND, SERVER_SIDE_MOVE, SERVER_SIDE_COPY, SET_MODIFIED, THUMBNAILS }
```

- **Streams statt Dateien:** `openRead(offset)` ermöglicht Fortsetzen, Range-Requests (HTTPS-Server,
  Video-Streaming) und Server→Server ohne Zwischenordner.
- **Fähigkeiten statt Sonderfälle:** Die Übertragung fragt `SERVER_SIDE_MOVE` ab (SMB/SFTP/WebDAV
  können Verschieben auf demselben Server ohne Datenfluss übers Handy) und fällt sonst auf
  Stream-Kopie zurück.
- **Coroutines:** `suspend` + `Dispatchers.IO`; Abbruch über den Job statt über `isCancelled`-Lambdas.
  Die blockierenden Bibliotheken (sshj, SMBJ, OkHttp) werden intern gekapselt.
- **Fehler:** alle Backends werfen `errors/LocalizedException` bzw. typisierte Unterklassen
  (`NotFound`, `AccessDenied`, `AlreadyExists`, `AuthFailed`, `HostKeyException`, `CertificateUntrusted`).
  Die Oberfläche übersetzt sie wie heute über `describeError`.

### Registry und Verbindungen

`StorageRegistry.backendFor(path)` ersetzt `RemoteConnections` und `TransferManager.getRemoteFileSystem`.
Pro Server bleiben zwei Verbindungsarten: eine dauerhafte fürs Blättern und eigene für
Übertragungen (so bleibt die Liste bedienbar, während kopiert wird). Nach Leerlauf werden Verbindungen
geschlossen.

### Darauf aufbauende Dienste

- **TransferEngine** (ersetzt `download`/`upload`): kopiert zwischen zwei beliebigen Backends über
  Streams, mit Fortschritt, Konfliktregel (überschreiben, umbenennen, überspringen), Abbruch und
  **Fortsetzen**: Größe am Ziel per `stat`, dann `openRead(offset)` + `openWrite(APPEND)`.
- **Archive:** `ArchiveExtractor` liest über `openRead`; 7-Zip braucht wahlfreien Zugriff: mit
  `RANDOM_ACCESS` direkt (Adapter auf `IInStream`), sonst über eine temporäre Kopie. Später
  `ArchiveBackend` zum Durchblättern von Archiven ohne Entpacken.
- **DocumentsProvider:** `openDocument` → `openRead`/`openWrite`; für wahlfreien Zugriff
  `StorageManager.openProxyFileDescriptor` (ab API 26, bei uns immer verfügbar).
- **HTTPS-Server im LAN:** Downloads mit Range-Requests direkt aus `openRead(offset)`, also auch
  Dateien von SMB/SFTP/WebDAV.

## Umsetzung in Schritten

Jeder Schritt ist für sich lauffähig und wird einzeln getestet und committet.

1. **Grundgerüst** im Paket `de.needanapp.needafile.fs`: `StoragePath`, `StorageEntry`,
   `StorageBackend`, `Capability`, `StorageRegistry`. Noch keine Nutzer.
2. **Server-Backends:** die drei Manager um `stat`, `openRead(offset)` und `openWrite` ergänzen und als
   Backends anbieten; `RemoteFileSystem` bleibt so lange als dünne Hülle bestehen.
3. **Gemeinsame Vertragstests** (dieselbe Testsuite für jedes Backend) gegen Docker-Server:
   `atmoz/sftp`, Samba, ein WebDAV-Server (z. B. `rclone serve webdav`), später FTPS. Auf dem Gerät per
   `adb reverse`.
4. **TransferEngine** ersetzt `download`/`upload` und den Zwischenordner; Fortsetzen nach
   Verbindungsabbruch; Konfliktdialog für alle Richtungen gleich.
5. **LocalBackend und SafBackend** als Hüllen um die vorhandenen Hilfsfunktionen, ohne diese zu
   ändern. Damit gehen Übertragungen auch auf die SD-Karte und nach `Android/data`.
6. **Dateiliste:** `ItemsFragment.getRemoteItems` nutzt die Registry; das lokale Auflisten bleibt
   zunächst, wie es ist, und wandert mit der Compose-Umstellung in die Abstraktion.
7. **RootBackend** (Prüfen: `libsu`, Apache 2.0, statt des alten RootTools).
8. **Archive auf allen Backends**, danach `DocumentsProvider` und HTTPS-Server.

## Offene Punkte

- **Hintergrund-Übertragungen:** Ab Android 15 sind Foreground-Services vom Typ `dataSync` auf
  6 Stunden pro Tag begrenzt. Für vom Nutzer gestartete Übertragungen sind **User-Initiated Data
  Transfer Jobs** (`JobInfo.Builder.setUserInitiated`, ab Android 14) vorgesehen. Die Warteschlange
  sollte darauf aufbauen, der heutige `TransferService` dient als Übergang.
- **Zertifikate:** Für WebDAV und FTPS mit selbstsignierten Zertifikaten ein TLS-Gegenstück zu
  `security/KnownHosts` bauen: Fingerabdruck anzeigen, nach Bestätigung pinnen, bei Änderung warnen.
  Niemals pauschal alle Zertifikate akzeptieren.
- **Pfad-Eigenheiten:** SMB unterscheidet nicht zwischen Groß- und Kleinschreibung und nutzt
  intern `\`; WebDAV braucht URL-Kodierung; SFTP kennt symbolische Links (Schleifen erkennen, wie heute
  über `canonicalize`).
- **Vorschaubilder für Netzwerkdateien:** Glide-`ModelLoader` auf `openRead`, mit Größenlimit und Cache.
- **Offline-Variante ohne Internet-Berechtigung:** Die Server-Backends in ein eigenes Gradle-Modul
  legen, dann kann eine Build-Variante ohne Netzwerk sie einfach weglassen.

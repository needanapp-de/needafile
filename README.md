<p align="center">
  <img src="fastlane/metadata/android/de-DE/images/featureGraphic.png" alt="needafile – Deine Dateien auf dem Handy und im Netzwerk" width="100%">
</p>

# needafile

**Dateimanager für Handy und Netzwerk: SFTP, SMB, WebDAV, FTPS und Archive**

needafile ist ein Dateimanager für Android 12 und neuer – für die Dateien auf dem Handy und auf den Servern im eigenen Netzwerk, im modernen Material-3-Expressive-Stil.

[F-Droid-Repo](https://fdroid.needanapp.de) · [Datenschutzerklärung](https://needanapp.de/needafile/datenschutz.html) ·
Android 12 und neuer · Lizenz [GPL-3.0](LICENSE) · Herausgeber needanapp.de

## Installieren

Über das eigene F-Droid-Repo: in F-Droid (oder einer anderen F-Droid-App) unter „Paketquellen“ diese Adresse
hinzufügen oder auf [fdroid.needanapp.de](https://fdroid.needanapp.de) den QR-Code scannen.

```text
https://fdroid.needanapp.de/repo?fingerprint=231DF03DE2428B3A82E67B6CBF0D0CB0BB6E9261EA45392128707D2F39661844
```

Fingerabdruck (SHA-256) des Repo-Schlüssels: `23 1D F0 3D E2 42 8B 3A 82 E6 7B 6C BF 0D 0C B0 BB 6E 92 61 EA 45 39 21 28 70 7D 2F 39 66 18 44`

## Bilder

<table>
  <tr>
    <td align="center" width="25%"><img src="fastlane/metadata/android/de-DE/images/phoneScreenshots/1-photos.png" alt="Fotos als Galerie, Tabs für mehrere Orte" width="200"><br><sub>Fotos als Galerie, Tabs für mehrere Orte</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/de-DE/images/phoneScreenshots/2-servers.png" alt="Gespeicherte Server: SMB, SFTP, WebDAV, FTPS" width="200"><br><sub>Gespeicherte Server: SMB, SFTP, WebDAV, FTPS</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/de-DE/images/phoneScreenshots/3-nas.png" alt="Ordner auf dem NAS im eigenen Tab" width="200"><br><sub>Ordner auf dem NAS im eigenen Tab</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/de-DE/images/phoneScreenshots/4-archive.png" alt="ZIP-Archiv wie einen Ordner öffnen" width="200"><br><sub>ZIP-Archiv wie einen Ordner öffnen</sub></td>
  </tr>
  <tr>
    <td align="center" width="25%"><img src="fastlane/metadata/android/de-DE/images/phoneScreenshots/5-viewer.png" alt="Bildanzeige mit Editor" width="200"><br><sub>Bildanzeige mit Editor</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/de-DE/images/phoneScreenshots/6-actions.png" alt="Karte mit allen Aktionen, nach Zweck gruppiert" width="200"><br><sub>Karte mit allen Aktionen, nach Zweck gruppiert</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/de-DE/images/phoneScreenshots/7-selection.png" alt="Mehrere Dateien: eine Karte mit Anzahl und Größe" width="200"><br><sub>Mehrere Dateien: eine Karte mit Anzahl und Größe</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/de-DE/images/phoneScreenshots/8-appearance.png" alt="Farbwelten für hell und dunkel" width="200"><br><sub>Farbwelten für hell und dunkel</sub></td>
  </tr>
</table>

## Funktionen

### Handy

- Interner Speicher, SD-Karten und USB-Sticks, optional Root
- Tabs für mehrere Orte, Kopieren und Verschieben im Hintergrund mit Fortschritt und Abbrechen
- Ansicht als Liste, Details, Raster oder Galerie; Zoomen mit zwei Fingern; Gruppieren nach Datum, Typ, Größe oder Name
- Lang drücken öffnet eine Karte mit allen Aktionen; mehrere markierte Einträge teilen sich eine Karte
- Suche, Verknüpfungen auf dem Startbildschirm, Texteditor
- Prüfsummen (MD5, SHA-1, SHA-256, SHA-512) mit Vergleich gegen die Zwischenablage

### Netzwerk

- SFTP (mit Prüfung des Server-Schlüssels), SMB, WebDAV und FTPS; unverschlüsseltes FTP nur nach Rückfrage pro Server
- Selbst signierte Zertifikate erst nach Bestätigung des Fingerabdrucks, Warnung bei Änderung
- Passwörter verschlüsselt im Android Keystore
- Findet Server im Netzwerk (Bonjour/mDNS, WS-Discovery)
- Übertragungen sind pausierbar; nach einem Verbindungsabbruch wartet die App bis zu einer Stunde und macht an der Abbruchstelle weiter

### Archive

- ZIP (mit AES-Verschlüsselung, aufgeteilt) und 7z (auch mit verschlüsselten Dateinamen) erstellen
- RAR 4/5, 7z, tar, gz, bz2, xz, ISO, CAB und viele weitere entpacken
- Archive wie Ordner öffnen, auch auf Servern

### Tresore

- Cryptomator-Tresore anlegen und öffnen, auf dem Handy und auf Servern; Dateinamen und Inhalte bleiben verschlüsselt
- Entsperren per Passwort oder Fingerabdruck; Wiederherstellungsschlüssel, Passwort ändern
- Sperren sich im Hintergrund oder beim Ausschalten des Bildschirms von selbst; eine Benachrichtigung zählt herunter und sperrt sie auf Tipp

### Anzeigen

- Bilder (JPEG, PNG, WebP, GIF, SVG, HEIC, AVIF, JPEG XL …) mit einfachem Editor
- Dokumente: PDF, Word, Excel, OpenDocument, Markdown, CSV und HTML

### Außerdem

- Schutz per PIN, Muster oder Fingerabdruck
- Einstellungen und Server in einer passwortgeschützten Datei sichern
- Hell, dunkel und reines Schwarz, Farbwelten wie Wolke, Nordnacht, Signal oder Ultraviolett, Material You oder eigene Akzentfarbe
- Deutsch und Englisch

needafile ist freie Software (GPL-3.0). Netzwerkzugriff braucht die App nur für die Server-Verbindungen.

<details>
<summary><b>Alle Funktionen im Detail</b></summary>

- Interner Speicher, SD-Karte, USB und (optional) Root
- Server-Verbindungen: SFTP (mit Prüfung des Server-Schlüssels), SMB, WebDAV und FTPS (explizit oder implizit;
  selbst signierte Zertifikate nach Bestätigung des Fingerabdrucks, Warnung bei Änderung), unverschlüsseltes FTP
  nur nach Rückfrage pro Server; Zugangsdaten verschlüsselt im Android Keystore
- Server im Netzwerk finden: Bonjour/mDNS (SMB, SFTP/SSH, WebDAV, FTP) und Windows-Rechner bzw. NAS per WS-Discovery
- Einstellungen sichern und wiederherstellen: Darstellung, Einstellungen und Server (mit Passwörtern,
  bestätigten Schlüsseln und Zertifikaten) in einer per Passwort verschlüsselten Datei (Argon2id, AES-256-GCM); beim
  Import wählbar, welche Bereiche ersetzt werden
- Tabs für mehrere Orte; Kopieren und Verschieben im Hintergrund mit Fortschritt und Abbrechen, auf dem Gerät wie
  zwischen Gerät und Server; Server-Übertragungen sind pausierbar, nach einem Verbindungsabbruch wartet die App bis
  zu einer Stunde und macht an der Abbruchstelle weiter
- Packen und Entpacken: ZIP (Kompressionsstufe, AES-128/256, Aufteilen in Teile), 7z (Kompressionsstufe,
  Solid-Archiv, auch mit verschlüsselten Dateinamen); entpacken außerdem RAR 4/5 (auch mehrteilig und
  verschlüsselt), mehrteiliges 7z, tar(.gz/.bz2/.xz), gz, bz2, xz, Z, lzma, ISO, CAB, ARJ, LZH, WIM, CPIO, RPM,
  DEB, CHM und XAR
- Archive wie Ordner öffnen (schreibgeschützt, auch auf Servern und verschachtelt): Dateien direkt ansehen,
  einzelne Dateien oder Ordner entpacken, kopieren oder teilen
- Ansichten: Liste (kompakt, normal, groß), Details mit Spalten (Tippen auf eine Spalte sortiert), Raster und
  Galerie; Zoomen mit zwei Fingern; Gruppieren nach Datum, Typ, Größe oder Anfangsbuchstabe mit einklappbaren
  Überschriften; Ordner mit überwiegend Bildern und Videos öffnen sich als Galerie; allgemein oder pro Ordner
- Suche, Verknüpfungen auf dem Startbildschirm, Texteditor
- Prüfsummen (MD5, SHA-1, SHA-256, SHA-512) in den Eigenschaften, mit Vergleich gegen die Zwischenablage
- Bildanzeige (JPEG, PNG, WebP, GIF, SVG, HEIC, AVIF, JPEG XL u. a.) mit Zoom, Wischen durch den Ordner und
  Bilddetails (EXIF); Bildeditor zum Zuschneiden, Drehen, Spiegeln, Skalieren und Zeichnen –
  speichert standardmäßig als Kopie
- Dokumentanzeige: PDF (auch mit Passwort), Word (DOCX), Excel (XLSX), OpenDocument (ODS, ODT), Markdown,
  CSV und HTML, mit Suche und Drucken
- Bild- und Dokumentanzeige auch für andere Apps („Öffnen mit“)
- Cryptomator-Tresore (Format 8 und 7) anlegen und öffnen, lokal und auf allen Servern; Vorschaubilder nur im
  internen Cache, Bilder im Tresor durchwischen; Entsperren per Passwort oder Fingerabdruck,
  Wiederherstellungsschlüssel, Passwort ändern; automatische Sperre nach wählbarer Zeit im Hintergrund oder beim
  Ausschalten des Bildschirms (laufende Übertragungen werden vorher fertig), Benachrichtigung mit Countdown und
  Sperren-Taste
- Schutz per PIN, Muster oder Fingerabdruck
- Datei auswählen und „Speichern unter“ für andere Apps
- Darstellung: hell/dunkel/reines Schwarz, Farbwelten (Wolke, Papier, Nordschnee, Latte, Graphit, Nordnacht,
  Mitternacht, Gedimmt sowie Signal, Ultraviolett, Warnweste, Kupfer, Neon-Pink und Oliv mit eigener Akzentfarbe),
  Material You oder eigene Akzentfarbe, Paletten-Stile,
  progressiver Blur hinter Kopfzeile und Navigation, UI-Skalierung, eigene Schrift; App-Symbol in zwölf Farben
- Deutsch und Englisch

</details>

## Bauen

Die Oberfläche kommt aus dem Design-Paket [needanapp-design](https://git.needanapp.de/needanapp.de/needanapp-design),
das als Composite Build eingebunden ist (`includeBuild("../needanapp-design")` in `settings.gradle.kts`). Beide
Repositories müssen daher nebeneinander liegen:

```bash
git clone https://git.needanapp.de/needanapp.de/needafile.git
git clone https://git.needanapp.de/needanapp.de/needanapp-design.git
cd needafile
./gradlew :app:assembleFossDebug
```

```
needafile/           (dieses Repository)
needanapp-design/
```

Gebaut wird mit JDK 21 und dem Android SDK (compileSdk 37). Die Variante `foss` ist die F-Droid-Fassung, `gplay`
die für Google Play (ohne Verweis aufs F-Droid-Repo, sonst gleich).

7-Zip-JBinding wird mit einer kleinen Korrektur aus dem Quelltext unter `libs/7-zip-jbinding/` gebaut.

## Dank

- [7-Zip-JBinding](https://github.com/omicronapps/7-Zip-JBinding-4Android) und p7zip (LGPL-2.1) – 7z, RAR und weitere Archive
- Alle weiteren Bibliotheken mit Lizenzen stehen in der App unter Einstellungen → Über

## Lizenz

GPL-3.0, siehe [LICENSE](LICENSE). Das Design-Paket needanapp-design steht ebenfalls unter GPL-3.0.

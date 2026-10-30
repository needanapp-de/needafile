# needafile im Play Store

Checkliste und Textbausteine für die Play Console. Texte, die in die Console kommen, stehen auf Englisch (Google prüft
die Erklärungen), die Erläuterungen auf Deutsch. In der Kopie im Release-Ordner (`Play-Console-Anleitung.md`) steht
statt `{RELEASE_DIR}` der Ordner der Version und statt `{VERSION_CODE}` ihr Versionscode.

## Schlüssel

| Datei (in `/home/fuckup/needanapp-signing/`) | Rolle | SHA-256 des Zertifikats |
|---|---|---|
| `needanapp-release.p12` | **App-Signaturschlüssel**: signiert die Release-APKs (GitHub, F-Droid); Play signiert damit die ausgelieferten APKs | `C6:0E:A3:B5:6D:A7:0E:60:EF:F5:CB:96:DD:8D:C1:9A:41:86:0A:3E:D4:B2:9A:EB:2A:29:9F:83:3A:63:51:D9` |
| `needanapp-upload.p12` | **Upload-Schlüssel**: signiert nur das AAB, das du in die Console lädst | `4A:1E:DF:5E:BF:CB:5B:2B:7D:A1:DE:DC:5A:0C:77:92:88:EC:52:FF:71:53:C5:9A:4E:00:56:C3:73:B0:96:C3` |

Weil Play mit demselben Schlüssel signiert wie die Release-APKs auf GitHub, können Nutzer zwischen beiden Quellen
wechseln, ohne neu zu installieren. Den Schlüssel gibt es seit 30.10.2026 (0.97.3); bis dahin waren die APKs im F-Droid-Repo
mit einem anderen signiert (`4C:A4:F7:…:D5:47:02`), solche Installationen müssen einmal neu installiert werden.

- Der App-Signaturschlüssel lässt sich **nie** ersetzen, auch nicht über Google. Geht er verloren, gibt es keine
  Updates mehr. → `needanapp-release.p12` und Passwort an einem zweiten Ort sichern.
- Den Upload-Schlüssel kann Google bei Verlust zurücksetzen.
- Die Passwörter stehen in `keystore.properties` und `keystore-upload.properties` im selben Ordner (nicht in git, nicht
  in Syncthing).

## Bauen

```bash
cd needafile
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/fuckup/Android/Sdk ./gradlew :app:bundleGplayRelease   # AAB für Play, Upload-Schlüssel
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/fuckup/Android/Sdk ./gradlew :app:assembleFossRelease  # APK für F-Droid, Release-Schlüssel
```

Das AAB liegt unter `app/build/outputs/bundle/gplayRelease/`. Die Play-Variante (`gplay`) zeigt keinen Verweis aufs
F-Droid-Repo, sonst ist sie gleich.

## Einmalig: App anlegen und Play App-Signatur einrichten

1. Play Console → **App erstellen**: Name `needafile`, Standardsprache **Englisch (USA) – en-US**, App, kostenlos.
   Der Paketname `de.needanapp.needafile` steht mit dem ersten Upload für immer fest; der angezeigte Name lässt sich
   später noch ändern.
2. **Noch kein AAB hochladen.** Sonst legt Google beim ersten Upload automatisch einen eigenen Schlüssel an
   (quantensichere Hybrid-Signatur), und die Play-Version passt nicht mehr zur F-Droid-Version. Ändern lässt sich das
   nur, solange noch kein Release im offenen Test oder in der Produktion ist.
3. Linkes Menü **Mit Google Play geschützt** → Abschnitt **Google Play Store-Schutz** aufklappen (∨) → **Play
   App-Signatur** → Fenster „Einstellungen für App-Signatur“ → **Einen Schlüssel aus dem Java KeyStore exportieren und
   hochladen** (PEPK). Dafür gibt es `tools/play-export-key.sh`. Die Console bietet `pepk.jar` und Googles öffentlichen Verschlüsselungsschlüssel
   zum Download an und zeigt den genauen Befehl; er sieht etwa so aus:

```bash
java -jar pepk.jar --keystore=/home/fuckup/needanapp-signing/needanapp-release.p12 --alias=needanapp-release --output=needanapp-release-for-play.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
```

`pepk.jar` fragt nach den Passwörtern. Die erzeugte ZIP hochladen und danach löschen.

4. **Zertifikat des Uploadschlüssels (nötig!):** Im selben Dialog (Schritt „Upload-Zertifikat“ / „Zertifikat des
   Uploadschlüssels“) `{RELEASE_DIR}/Play/upload-zertifikat.pem` hochladen (Kopie von
   `/home/fuckup/needanapp-signing/needanapp-upload.pem`). Ohne dieses Zertifikat gilt der App-Signaturschlüssel selbst
   als Uploadschlüssel, und Play lehnt das AAB ab, weil es mit dem Uploadschlüssel signiert ist. Wurde der Schritt
   übersprungen: unter Play App-Signatur „Zurücksetzen des Uploadschlüssels anfordern“ und dort dieselbe `.pem`
   hochladen (Google bearbeitet das, es kann etwas dauern).
5. Prüfen: Unter Play App-Signatur müssen die beiden SHA-256-Werte aus der Tabelle oben stehen
   (App-Signaturschlüssel `C6:0E:A3:…:63:51:D9`, Uploadschlüssel `4A:1E:DF:…:B0:96:C3`). Erst dann das AAB hochladen.

## Erster Release

1. Zuerst **Interner Test** (bis 100 Tester, keine Prüfung abwarten nötig).
2. AAB `{RELEASE_DIR}/Play/needafile-{VERSION_CODE}-gplay-release.aab` hochladen, Release-Hinweise aus
   `{RELEASE_DIR}/Play/Store-Eintrag/en-US/release_notes.txt` und `{RELEASE_DIR}/Play/Store-Eintrag/de-DE/release_notes.txt`.
3. **Geschlossener Test, falls nötig:** Persönliche Entwicklerkonten, die nach dem 13. November 2023 angelegt wurden,
   brauchen vor der Produktion einen geschlossenen Test mit mindestens **12 Testern**, die **14 Tage ununterbrochen**
   dabei sind (echte Geräte, eigene Google-Konten). Erst danach lässt sich der Produktionszugang beantragen.
   Organisationskonten und ältere Konten sind ausgenommen.
4. Danach Produktion (die erste Prüfung durch Google dauert oft einige Tage).

## Android-Entwicklerverifizierung (auch für F-Droid wichtig)

Seit 30. September 2026 installieren zertifizierte Android-Geräte in Brasilien, Indonesien, Singapur und Thailand nur
noch Apps von verifizierten Entwicklern, auch außerhalb von Play; weltweit ist das für 2027 angekündigt. Mit dem
verifizierten Play-Konto den Paketnamen `de.needanapp.needafile` mit dem Release-Schlüssel (SHA-256 oben) registrieren,
dann gilt das auch für die APKs auf GitHub und im eigenen F-Droid-Repo, weil alle mit demselben Schlüssel signiert sind. Die
Debug-Variante `de.needanapp.needafile.debug` (Test-Schlüssel) wäre davon getrennt zu registrieren.

## Store-Eintrag

- Texte Englisch (Standard): `{RELEASE_DIR}/Play/Store-Eintrag/en-US/title.txt`,
  `{RELEASE_DIR}/Play/Store-Eintrag/en-US/short_description.txt`, `{RELEASE_DIR}/Play/Store-Eintrag/en-US/full_description.txt`.
- Texte Deutsch: `{RELEASE_DIR}/Play/Store-Eintrag/de-DE/title.txt`,
  `{RELEASE_DIR}/Play/Store-Eintrag/de-DE/short_description.txt`, `{RELEASE_DIR}/Play/Store-Eintrag/de-DE/full_description.txt`.
- Symbol 512 × 512: `{RELEASE_DIR}/Play/Grafiken/icon-512.png`.
- Feature-Grafik 1024 × 500: `{RELEASE_DIR}/Play/Grafiken/en-US/featureGraphic.png` und
  `{RELEASE_DIR}/Play/Grafiken/de-DE/featureGraphic.png`.
- 8 Smartphone-Screenshots 1080 × 1920 (9:16, Beispieldaten aus dem Emulator):
  `{RELEASE_DIR}/Play/Grafiken/en-US/phoneScreenshots/` und `{RELEASE_DIR}/Play/Grafiken/de-DE/phoneScreenshots/`.
  Reihenfolge: Fotos, Server, NAS, Archiv, Bildanzeige, Aktionen, Auswahl, Darstellung.
- Kategorie: Tools. Website: `https://needanapp.de`.
- Datenschutzerklärung (URL für die Console): `https://needanapp.de/needafile/privacy-policy.html`, deutsche Fassung
  `https://needanapp.de/needafile/datenschutz.html`. Beide Links stehen auch in der App unter Einstellungen → Über.
  Die Seiten erzeugt `python3 tools/make-privacy-pages.py` (fragt einmal Name, Anschrift und E-Mail,
  merkt sie sich in `/home/fuckup/needafile-release/verantwortlicher.json`); hochzuladen sind
  `{RELEASE_DIR}/Datenschutz/privacy-policy.html` und `{RELEASE_DIR}/Datenschutz/datenschutz.html` nach
  `https://needanapp.de/needafile/`.

## App-Inhalte (Richtlinien)

### Zugriff auf alle Dateien (MANAGE_EXTERNAL_STORAGE)

Zulässiger Zweck / Kernfunktion: **Dateimanager**. Die Console fragt zwei Texte ab, je höchstens 500 Zeichen; jeder
Block ist eine einzige Zeile zum Kopieren.

### Zugriff auf alle Dateien: Beschreibung der Nutzung

```text
needafile is a file manager. Its core function is to let users browse, open, copy, move, rename, compress, extract and delete any file on shared storage, SD cards and USB drives, and to transfer files between the device and their own servers (SFTP, SMB, WebDAV, FTPS). The app asks for the permission on its start screen, explains why it is needed and only changes files the user chooses.
```

(388 von 500 Zeichen)

### Zugriff auf alle Dateien: technischer Grund

Warum die datenschutzfreundlicheren Wege (MediaStore, Storage Access Framework) nicht reichen:

```text
A file manager has to list and change files of every type in every folder, including folders created by other apps, the root of the storage and the Download folder. MediaStore only covers media files and files the app created itself. The Storage Access Framework needs a separate grant for each folder and cannot grant the storage root or the Download folder on Android 11 and newer, so users could not manage their files with it. All files access is the only way to provide this core function.
```

(494 von 500 Zeichen)

Google verlangt oft ein kurzes Video (z. B. als nicht gelistetes YouTube-Video): App starten, Erklärung + Freigabe
zeigen, danach durch Ordner navigieren und eine Datei kopieren.

### Apps installieren (REQUEST_INSTALL_PACKAGES)

Zulässiger Zweck: **Dateiverwaltung**. Text:

```text
needafile is a file manager. When the user opens an APK file in it, needafile hands the file to the Android package installer, just like opening any other file type. The app never downloads or installs packages on its own.
```

(222 von 500 Zeichen)

### Vordergrunddienst (FOREGROUND_SERVICE_DATA_SYNC)

Typ: **Datensynchronisierung / Datenübertragung**. Text:

```text
needafile uses a data sync foreground service while the user copies or moves files on the device or between the device and the user's own servers, and while it compresses or extracts archives. Each operation is started by the user, shows its progress in a notification and can be cancelled there (server transfers can also be paused). The service stops as soon as the operation ends.
```

(383 von 500 Zeichen)

Auch hier wird meist ein Video verlangt: Kopie starten, App in den Hintergrund, Benachrichtigung mit Fortschritt
zeigen.

### Datensicherheit

Meine Einschätzung (bitte selbst prüfen, du gibst die Erklärung ab):

- **Erhebt die App Daten?** Nein. Daten gehen nur an Server, die der Nutzer selbst einträgt; needanapp.de bekommt
  nichts. Keine Analyse, keine Werbung, keine Absturzberichte.
- **Teilt die App Daten?** Nein (das Öffnen einer Datei mit einer anderen App startet der Nutzer selbst).
- Damit entfallen die Fragen zu Verschlüsselung bei der Übertragung und Löschanfragen.

### Weitere Fragen

- Werbung: Nein. Zielgruppe: ab 18 (keine Kinder-App). Nachrichten-App: Nein. Regierungs-App: Nein.
- App-Zugriff: Keine Anmeldung nötig, alle Funktionen frei zugänglich.
- Inhaltsfreigabe (IARC-Fragebogen): Dienstprogramm, keine nutzergenerierten Inhalte im Sinne von Kommunikation,
  keine Gewalt usw.

## Vor jedem Release

- `VERSION_NAME`/`VERSION_CODE` in `gradle.properties` erhöhen, Changelogs
  `fastlane/metadata/android/en-US/changelogs/` und `fastlane/metadata/android/de-DE/changelogs/` anlegen.
- R8-Release vorher auf einem Gerät testen (siehe Commit-Historie: alle Protokolle, Archive, Kopieren).
- Dieselbe Version als APK ins F-Droid-Release-Repo und als AAB in den Play Store.
- `python3 tools/make-release-folder.py` sammelt APK, AAB, Store-Texte, Grafiken, diese Anleitung und die
  Datenschutzseiten im Release-Ordner unter `/home/fuckup/needafile-release/` (prüft vorher die Signaturen).

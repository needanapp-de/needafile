# Datenschutzerklärung – needafile

Stand: 2. Oktober 2026

needafile ist ein Dateimanager für Android, herausgegeben von needanapp.de. Diese Erklärung beschreibt, mit welchen
Daten die App umgeht und wohin sie gelangen.

**Kurz gesagt: needafile erhebt, verkauft und teilt keine personenbezogenen Daten. Es gibt kein Konto, keine Werbung,
keine Analyse und keine Absturzberichte. Deine Dateien und Einstellungen bleiben auf deinem Gerät oder gehen nur an
Server, die du selbst einträgst.**

## Verantwortlicher

needanapp.de
[NAME]
[STRASSE, PLZ ORT, LAND]
E-Mail: [KONTAKTADRESSE]

## Daten auf deinem Gerät

needafile speichert ausschließlich auf deinem Gerät, im privaten Speicher der App:

- Einstellungen, Favoriten, offene Tabs und die gewählte Darstellung
- Server-Verbindungen, die du anlegst (Adresse, Port, Benutzername, Passwort oder SSH-Schlüssel). Passwörter und
  Schlüssel sind mit einem Schlüssel aus dem Android Keystore verschlüsselt, der das Gerät nie verlässt.
- Bestätigte Server-Schlüssel und Zertifikat-Fingerabdrücke, um dich bei Änderungen zu warnen

needanapp.de hat auf keine dieser Daten Zugriff.

## Netzwerkverbindungen

- needafile verbindet sich nur mit Servern, die du selbst einträgst (SFTP, SMB, WebDAV, FTP/FTPS). Dateien,
  Ordnerlisten und Anmeldedaten gehen direkt zwischen deinem Gerät und diesem Server. Ein Server von needanapp.de ist
  nicht beteiligt.
- Startest du die Suche im Netzwerk, sendet needafile Suchanfragen (Bonjour/mDNS, WS-Discovery) in dein lokales
  Netzwerk, um dort Server zu finden. Ins Internet geht dabei nichts.
- In der App angezeigte Dokumente (z. B. HTML-Dateien) können nichts aus dem Internet nachladen.

## Sicherungen

- **Export:** Nur auf deinen Wunsch schreibt needafile Einstellungen und Server in eine Datei an dem Ort, den du
  wählst. Die Datei ist mit dem Passwort verschlüsselt, das du eingibst (Argon2id und AES-GCM). needanapp.de erhält
  sie nie.
- **Android-Sicherung:** Hast du die Gerätesicherung von Android eingeschaltet, kann Android die Einstellungen von
  needafile in diese Sicherung in deinem eigenen Google-Konto aufnehmen. Gespeicherte Passwörter bleiben mit dem
  Geräteschlüssel verschlüsselt und lassen sich aus einer solchen Sicherung nicht lesen.

## Berechtigungen

| Berechtigung | Wofür |
|---|---|
| Zugriff auf alle Dateien | Dateien anzeigen, kopieren, verschieben, umbenennen, packen und löschen – der Kern eines Dateimanagers |
| Apps installieren | Eine APK-Datei installieren, nur wenn du sie in needafile öffnest |
| Benachrichtigungen | Fortschritt beim Kopieren, Übertragen, Komprimieren und Entpacken anzeigen |
| Vordergrunddienst | Kopieren, Übertragen, Komprimieren und Entpacken weiterlaufen lassen, während die App im Hintergrund ist |
| Internet, Netzwerk- und WLAN-Status | Verbindung zu deinen Servern und Suche nach Servern im lokalen Netzwerk |
| Biometrie | Entsperren der App per Fingerabdruck, wenn du den App-Schutz einschaltest |

## Andere Apps

Öffnest oder teilst du eine Datei mit einer anderen App, übergibt Android diese Datei an die App, die du auswählst.
Was diese App damit macht, regelt deren eigene Datenschutzerklärung.

## Kinder

needafile richtet sich nicht an Kinder und verarbeitet nicht wissentlich Daten von Kindern.

## Deine Rechte

Da needanapp.de über die App keine personenbezogenen Daten verarbeitet, gibt es bei uns in der Regel nichts
auszukunften, zu berichtigen oder zu löschen. Alle Daten liegen auf deinem Gerät und verschwinden, wenn du die
App-Daten löschst oder die App deinstallierst. Für Fragen schreib an die Adresse oben. Du hast außerdem das Recht,
dich bei einer Datenschutz-Aufsichtsbehörde zu beschweren.

## Änderungen

Geht die App künftig anders mit Daten um, wird diese Erklärung vor der Veröffentlichung dieser Version angepasst. Das
Datum oben zeigt die letzte Änderung.

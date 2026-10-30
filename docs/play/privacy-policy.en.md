# Privacy policy – needafile

Last updated: 2 October 2026

needafile is a file manager for Android, published by needanapp.de. This policy explains which data the app
handles and where it goes.

**In short: needafile does not collect, sell or share any personal data. It has no account, no advertising, no
analytics and no crash reporting. Your files and settings stay on your device or go only to the servers you add
yourself.**

## Controller

needanapp.de
[NAME]
[STREET, POSTCODE CITY, COUNTRY]
E-mail: [CONTACT ADDRESS]

## Data on your device

needafile stores the following on your device only, inside the app's private storage:

- Settings, favorites, open tabs and the appearance you chose
- Server connections you add (address, port, user name, password or SSH key). Passwords and keys are encrypted with
  a key from the Android Keystore that never leaves the device.
- Server keys and certificate fingerprints you confirmed, to warn you if a server changes

needanapp.de has no access to any of this data.

## Network connections

- needafile connects only to the servers you add yourself (SFTP, SMB, WebDAV, FTP/FTPS). Files, folder listings and
  your login data go directly between your device and that server. No server run by needanapp.de is involved.
- When you start a network search, needafile sends discovery requests (Bonjour/mDNS, WS-Discovery) into your local
  network to find servers there. Nothing is sent to the internet.
- Documents shown in the app (for example HTML files) cannot load anything from the internet.

## Backups

- **Export:** Only when you ask for it, needafile writes your settings and servers into a file at the place you
  choose. The file is encrypted with the password you enter (Argon2id and AES-GCM). needanapp.de never receives it.
- **Android backup:** If you turned on the device backup of Android, Android may include needafile's settings in
  that backup to your own Google account. Stored passwords stay encrypted with the device key and cannot be read
  from such a backup.

## Permissions

| Permission | Why |
|---|---|
| All files access | To show, copy, move, rename, pack and delete your files – the core of a file manager |
| Install apps | To install an APK file only when you open it in needafile |
| Notifications | To show the progress of copies, transfers, compressing and extracting |
| Foreground service | To keep copies, transfers, compressing and extracting running when the app is in the background |
| Internet, network and Wi-Fi state | To connect to your servers and to find servers in your local network |
| Biometrics | To unlock the app with your fingerprint, if you turn on the app lock |

## Other apps

When you open or share a file with another app, Android passes that file to the app you pick. What that app does
with it is covered by its own privacy policy.

## Children

needafile is not directed at children and does not knowingly process data of children.

## Your rights

As needanapp.de does not process personal data from the app, there is usually nothing to access, correct or
delete on our side. All data lies on your device and is removed when you clear the app's data or uninstall it. For
questions, write to the address above. You may also lodge a complaint with a data protection supervisory authority.

## Changes

If the app starts to handle data differently, this policy will be updated before that version is released. The date
at the top shows the latest change.

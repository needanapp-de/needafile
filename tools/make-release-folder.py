#!/usr/bin/env python3
"""Collects everything needed to publish the current version in ~/needafile-release/<version>/.

Run after `./gradlew :app:assembleFossRelease :app:bundleGplayRelease`. Checks that the APK carries the release key
and the AAB the upload key, then copies APK, AAB, store texts, graphics, the Play guide and the privacy pages (from
tools/make-privacy-pages.py) and writes LIESMICH.md with the steps. No passwords are read.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASE_ROOT = os.path.expanduser(os.environ.get("NEEDAFILE_RELEASE_DIR", "~/needafile-release"))
SIGNING = os.path.expanduser("~/needanapp-signing")
SDK = os.path.expanduser(os.environ.get("ANDROID_HOME", "~/Android/Sdk"))
JAVA = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
APP_ID = "de.needanapp.needafile"
RELEASE_CERT = "4ca4f74f692f10e767cd84253851dc854f14d399b23c7c480bfabbd6fed54702"
UPLOAD_CERT = "4a1edf5ebfcb5b2b7da1dedc5a0c779288ec52ff7153c59a4e0056c373b096c3"
FASTLANE = os.path.join(REPO, "fastlane", "metadata", "android")
LOCALES = ["en-US", "de-DE"]
DECLARATION_LIMIT = 500


def gradle_property(name):
    with open(os.path.join(REPO, "gradle.properties"), encoding="utf-8") as f:
        match = re.search(rf"^{name}=(.+)$", f.read(), re.MULTILINE)
    return match.group(1).strip()


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def apk_cert(apk):
    build_tools = sorted(os.listdir(os.path.join(SDK, "build-tools")))[-1]
    out = subprocess.run([os.path.join(SDK, "build-tools", build_tools, "apksigner"), "verify", "--print-certs", apk],
                         capture_output=True, text=True, check=True).stdout
    return re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", out).group(1)


def aab_cert(aab):
    out = subprocess.run([os.path.join(JAVA, "bin", "keytool"), "-J-Duser.language=en", "-printcert", "-jarfile", aab],
                         capture_output=True, text=True, check=True).stdout
    return re.search(r"SHA256: ([0-9A-F:]+)", out).group(1).replace(":", "").lower()


def colons(hex_digest):
    return ":".join(hex_digest[i:i + 2] for i in range(0, len(hex_digest), 2)).upper()


def copy(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy2(src, dst)


def main():
    version, code = gradle_property("VERSION_NAME"), gradle_property("VERSION_CODE")
    apk = os.path.join(REPO, "app", "build", "outputs", "apk", "foss", "release", f"needafile-{code}-foss-release.apk")
    aab = os.path.join(REPO, "app", "build", "outputs", "bundle", "gplayRelease", f"needafile-{code}-gplay-release.aab")
    for path in (apk, aab):
        if not os.path.exists(path):
            sys.exit(f"Fehlt: {path} – erst :app:assembleFossRelease :app:bundleGplayRelease bauen")
    if apk_cert(apk) != RELEASE_CERT:
        sys.exit("APK ist nicht mit dem Release-Schlüssel signiert")
    if aab_cert(aab) != UPLOAD_CERT:
        sys.exit("AAB ist nicht mit dem Upload-Schlüssel signiert")

    out = os.path.join(RELEASE_ROOT, version.removesuffix("-alpha"))
    if os.path.exists(out):
        shutil.rmtree(out)
    fdroid_apk = os.path.join(out, "F-Droid", f"{APP_ID}_{code}.apk")
    play_aab = os.path.join(out, "Play", f"needafile-{code}-gplay-release.aab")
    copy(apk, fdroid_apk)
    copy(aab, play_aab)
    copy(os.path.join(SIGNING, "needanapp-upload.pem"), os.path.join(out, "Play", "upload-zertifikat.pem"))
    with open(os.path.join(REPO, "docs", "play", "play-console.md"), encoding="utf-8") as f:
        guide = f.read().replace("{RELEASE_DIR}", out).replace("{VERSION_CODE}", code)
    os.makedirs(os.path.join(out, "Play"), exist_ok=True)
    with open(os.path.join(out, "Play", "Play-Console-Anleitung.md"), "w", encoding="utf-8") as f:
        f.write(guide)
    # each declaration text of the guide (a ```text block under a ### heading) as its own file, ready to copy
    declarations = []
    for heading, text in re.findall(r"^### ([^\n(]+?)(?: \([^\n]*\))?\n(?:(?!^##).)*?^```text\n(.+?)\n```", guide, re.MULTILINE | re.DOTALL):
        if len(text) > DECLARATION_LIMIT:
            sys.exit(f"Erklärung „{heading}“ hat {len(text)} Zeichen, erlaubt sind {DECLARATION_LIMIT}")
        name = re.sub(r"[^a-z0-9]+", "-", heading.lower().translate(str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"}))).strip("-") + ".txt"
        path = os.path.join(out, "Play", "Erklaerungen", name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        declarations.append((path, heading, len(text)))

    texts = os.path.join(out, "Play", "Store-Eintrag")
    graphics = os.path.join(out, "Play", "Grafiken")
    copy(os.path.join(FASTLANE, "en-US", "images", "icon.png"), os.path.join(graphics, "icon-512.png"))
    for locale in LOCALES:
        source = os.path.join(FASTLANE, locale)
        for name in ("title.txt", "short_description.txt", "full_description.txt"):
            copy(os.path.join(source, name), os.path.join(texts, locale, name))
        copy(os.path.join(source, "changelogs", f"{code}.txt"), os.path.join(texts, locale, "release_notes.txt"))
        copy(os.path.join(source, "images", "featureGraphic.png"), os.path.join(graphics, locale, "featureGraphic.png"))
        shots = os.path.join(source, "images", "phoneScreenshots")
        for shot in sorted(os.listdir(shots)):
            copy(os.path.join(shots, shot), os.path.join(graphics, locale, "phoneScreenshots", shot))

    privacy = os.path.join(RELEASE_ROOT, "datenschutz")
    has_privacy = all(os.path.exists(os.path.join(privacy, f)) for f in ("privacy-policy.html", "datenschutz.html"))
    if has_privacy:
        for name in ("privacy-policy.html", "datenschutz.html"):
            copy(os.path.join(privacy, name), os.path.join(out, "Datenschutz", name))

    with open(os.path.join(out, "SHA256SUMS.txt"), "w", encoding="utf-8") as f:
        for path in (fdroid_apk, play_aab):
            f.write(f"{sha256(path)}  {os.path.relpath(path, out)}\n")

    def full(*parts):
        return "`" + os.path.join(out, *parts) + "`"

    privacy_step = (
        f"1. **Datenschutzerklärung veröffentlichen:** {full('Datenschutz', 'privacy-policy.html')} und "
        f"{full('Datenschutz', 'datenschutz.html')} nach `https://needanapp.de/needafile/` hochladen. "
        "In die Console kommt `https://needanapp.de/needafile/privacy-policy.html`."
        if has_privacy else
        f"1. **Datenschutzerklärung:** erst `python3 {os.path.join(REPO, 'tools', 'make-privacy-pages.py')}` ausführen (fragt "
        f"Name, Anschrift, E-Mail), dann dieses Skript erneut; die HTML-Seiten landen in {full('Datenschutz')}.")
    rows = [
        (full("F-Droid", f"{APP_ID}_{code}.apk"), "F-Droid-Release-Repo, signiert mit dem Release-Schlüssel"),
        (full("Play", f"needafile-{code}-gplay-release.aab"), "Play Console, signiert mit dem Upload-Schlüssel"),
        (full("Play", "upload-zertifikat.pem"), "Upload-Zertifikat, nur beim ersten Einrichten von Play App Signing"),
    ]
    for locale in LOCALES:
        for name in ("title.txt", "short_description.txt", "full_description.txt", "release_notes.txt"):
            rows.append((full("Play", "Store-Eintrag", locale, name), f"Store-Eintrag {locale}" + (" (Standard)" if locale == "en-US" else "")))
    rows.append((full("Play", "Grafiken", "icon-512.png"), "App-Symbol 512 × 512"))
    for locale in LOCALES:
        rows.append((full("Play", "Grafiken", locale, "featureGraphic.png"), f"Feature-Grafik 1024 × 500, {locale}"))
        rows.append((full("Play", "Grafiken", locale, "phoneScreenshots", ""), f"Screenshots 1080 × 1920, {locale}"))
    rows.append((full("Play", "Play-Console-Anleitung.md"), "Alle Schritte und die fertigen Texte für Berechtigungen, Datensicherheit usw."))
    for path, heading, length in declarations:
        rows.append(("`" + path + "`", f"Erklärung „{heading}“ für die Console, eine Zeile, {length} von {DECLARATION_LIMIT} Zeichen"))
    if has_privacy:
        rows.append((full("Datenschutz", "privacy-policy.html"), "Datenschutzerklärung Englisch"))
        rows.append((full("Datenschutz", "datenschutz.html"), "Datenschutzerklärung Deutsch"))
    rows.append((full("SHA256SUMS.txt"), "Prüfsummen von APK und AAB"))
    table = "\n".join(f"| {path} | {use} |" for path, use in rows)
    readme = f"""# needafile {version} (Code {code}) – Veröffentlichung

Ordner: `{out}`

## Inhalt

| Datei | Wofür |
|---|---|
{table}

Zertifikate: Release-Schlüssel `{colons(RELEASE_CERT)}`, Upload-Schlüssel `{colons(UPLOAD_CERT)}`.

## Play Store, Reihenfolge

{privacy_step}
2. **Nur beim ersten Mal, vor dem ersten AAB-Upload:** App anlegen, dann im Menü „Mit Google Play geschützt → Google
   Play Store-Vertrieb → Play App-Signatur aufrufen → App-Signaturschlüssel ändern“ den eigenen Release-Schlüssel
   per PEPK bereitstellen und im selben Dialog {full('Play', 'upload-zertifikat.pem')} hochladen – siehe
   {full('Play', 'Play-Console-Anleitung.md')}, Abschnitt „Einmalig“.
3. **Store-Eintrag** für en-US und de-DE aus {full('Play', 'Store-Eintrag')} und {full('Play', 'Grafiken')} füllen.
4. **App-Inhalte:** Erklärungen für „Zugriff auf alle Dateien“, „Apps installieren“ und den Vordergrunddienst aus
   {full('Play', 'Erklaerungen')} kopieren; Datensicherheit, Zielgruppe, Inhaltseinstufung stehen in der Anleitung.
5. **Release:** {full('Play', f'needafile-{code}-gplay-release.aab')} im internen Test hochladen, Release-Hinweise aus
   {full('Play', 'Store-Eintrag', 'de-DE', 'release_notes.txt')} und {full('Play', 'Store-Eintrag', 'en-US', 'release_notes.txt')}.
   Neue persönliche Konten (seit 13.11.2023): vor der Produktion 14 Tage geschlossener Test mit 12 Testern.

## F-Droid

APK und Metadaten (Texte, Changelog, Symbol, Feature-Grafik, Screenshots) liegen im Eingang des Release-Repos; der
Server übernimmt sie von dort.
"""
    with open(os.path.join(out, "LIESMICH.md"), "w", encoding="utf-8") as f:
        f.write(readme)
    print(out)


if __name__ == "__main__":
    main()

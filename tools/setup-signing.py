#!/usr/bin/env python3
"""Fills ~/needanapp-signing/keystore.properties and keystore-upload.properties for the release build.

Asks only for the password of each .p12 file (hidden input), finds the key's alias itself, checks that the key can
sign and writes alias and passwords into the properties file. Nothing is printed except the alias.
"""
import getpass
import os
import subprocess
import sys
import tempfile
import zipfile

SIGNING_DIR = os.path.expanduser(os.environ.get("NEEDANAPP_SIGNING_DIR", "~/needanapp-signing"))
JAVA_BIN = os.environ.get("JAVA_BIN", "/usr/lib/jvm/java-21-openjdk-amd64/bin")
KEYS = [
    ("keystore.properties", "needanapp-release.p12", "Release-Schlüssel"),
    ("keystore-upload.properties", "needanapp-upload.p12", "Play-Upload-Schlüssel"),
]


def run(args, env):
    return subprocess.run(args, env={**os.environ, **env}, capture_output=True, text=True)


def find_alias(store, password):
    """The alias of the private key, or None if the password does not open the file."""
    result = run([f"{JAVA_BIN}/keytool", "-J-Duser.language=en", "-list", "-storetype", "PKCS12", "-keystore", store,
                  "-storepass:env", "NA_STORE_PW"], {"NA_STORE_PW": password})
    if result.returncode != 0:
        return None
    aliases = [line.split(",")[0].strip() for line in result.stdout.splitlines() if "PrivateKeyEntry" in line]
    return aliases[0] if aliases else None


def can_sign(store, alias, store_password, key_password):
    """Signs a tiny test jar, which proves that alias and key password fit."""
    with tempfile.TemporaryDirectory() as tmp:
        jar = os.path.join(tmp, "test.jar")
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("test.txt", "needafile")
        result = run([f"{JAVA_BIN}/jarsigner", "-storetype", "PKCS12", "-keystore", store, "-storepass:env", "NA_STORE_PW",
                      "-keypass:env", "NA_KEY_PW", jar, alias], {"NA_STORE_PW": store_password, "NA_KEY_PW": key_password})
        return result.returncode == 0


def write_properties(path, store_file, alias, store_password, key_password):
    # backslashes would be read as escapes by Java's Properties
    def escape(value):
        return value.replace("\\", "\\\\")

    comments = [line for line in open(path, encoding="utf-8") if line.startswith("#")] if os.path.exists(path) else []
    with open(path, "w", encoding="utf-8") as out:
        out.writelines(comments)
        out.write(f"storeFile={store_file}\nkeyAlias={escape(alias)}\n")
        out.write(f"storePassword={escape(store_password)}\nkeyPassword={escape(key_password)}\n")
    os.chmod(path, 0o600)


def setup(properties, store_file, label):
    store = os.path.join(SIGNING_DIR, store_file)
    if not os.path.exists(store):
        print(f"✗ {store} fehlt, übersprungen")
        return False
    for _ in range(3):
        password = getpass.getpass(f"Passwort für {store_file} ({label}): ")
        alias = find_alias(store, password)
        if alias is None:
            print("  Passwort passt nicht, bitte noch einmal.")
            continue
        key_password = password
        if not can_sign(store, alias, password, key_password):
            key_password = getpass.getpass(f"  Eigenes Passwort für den Schlüssel „{alias}“: ")
            if not can_sign(store, alias, password, key_password):
                print("  Schlüssel-Passwort passt nicht, bitte von vorn.")
                continue
        write_properties(os.path.join(SIGNING_DIR, properties), store_file, alias, password, key_password)
        print(f"✓ {properties}: Alias „{alias}“, Signieren geprüft")
        return True
    print(f"✗ {store_file}: dreimal falsch, übersprungen")
    return False


if __name__ == "__main__":
    results = [setup(*key) for key in KEYS]
    print("\nFertig, beide Schlüssel eingetragen." if all(results) else "\nNicht alles eingetragen, Skript einfach erneut starten.")
    sys.exit(0 if all(results) else 1)

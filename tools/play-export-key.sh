#!/bin/bash
# Exports the release key encrypted for Google Play ("Kopie Ihres App-Signaturschlüssels bereitstellen").
# Waits for pepk.jar and encryption_public_key.pem from the Play Console in the download folder (or the folder given as
# first argument). pepk asks for the keystore password itself; nothing is stored or printed.
set -euo pipefail

DOWNLOADS=${1:-/home/fuckup/Downloads}
KEYSTORE=/home/fuckup/needanapp-signing/needanapp-release.p12
OUT=/home/fuckup/needafile-release/needanapp-release-for-play.zip
JAVA=/usr/lib/jvm/java-21-openjdk-amd64/bin/java

find_downloads() {
    PEPK=$(ls -t "$DOWNLOADS"/pepk*.jar 2>/dev/null | head -1 || true)
    KEY=$(ls -t "$DOWNLOADS"/encryption_public_key*.pem 2>/dev/null | head -1 || true)
    # browsers write into .part/.crdownload files first; wait until both downloads are complete
    [ -n "$PEPK" ] && [ -s "$PEPK" ] && [ -n "$KEY" ] && [ -s "$KEY" ] &&
        ! ls "$DOWNLOADS"/*.part "$DOWNLOADS"/*.crdownload >/dev/null 2>&1
}

if ! find_downloads; then
    echo "Warte auf pepk.jar und encryption_public_key.pem in $DOWNLOADS …"
    echo "(In der Play Console: „Öffentlichen Verschlüsselungsschlüssel herunterladen“ und „PEPK-Tool herunterladen“; Abbruch mit Strg+C)"
    until find_downloads; do sleep 2; done
    sleep 1
fi
echo "pepk:      $PEPK"
echo "Google-Schlüssel: $KEY"
echo "Schlüssel: $KEYSTORE (Alias needanapp-release)"
echo

rm -f "$OUT"
"$JAVA" -jar "$PEPK" --keystore="$KEYSTORE" --alias=needanapp-release --output="$OUT" \
    --include-cert --rsa-aes-encryption --encryption-key-path="$KEY"

echo
echo "Fertig: $OUT"
echo "Diese Datei in der Play Console hochladen und danach löschen:"
echo "rm $OUT"

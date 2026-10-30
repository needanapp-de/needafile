#!/usr/bin/env python3
"""Fills the controller (name, address, e-mail) into the privacy policy and writes web pages for needanapp.de.

Reads docs/play/privacy-policy.en.md and docs/play/datenschutz.de.md (they keep their placeholders in git), asks for
the missing details once and remembers them in ~/needafile-release/verantwortlicher.json (readable only by you).
Writes privacy-policy.html, datenschutz.html and the filled Markdown files into ~/needafile-release/datenschutz/.
"""
import html
import json
import os
import sys

import markdown

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_ROOT = os.path.expanduser(os.environ.get("NEEDAFILE_RELEASE_DIR", "~/needafile-release"))
VALUES = os.path.join(OUT_ROOT, "verantwortlicher.json")
OUT = os.path.join(OUT_ROOT, "datenschutz")
COUNTRIES_EN = {"deutschland": "Germany", "österreich": "Austria", "schweiz": "Switzerland"}

PAGES = [
    # template, output name, language, placeholders → keys
    ("privacy-policy.en.md", "privacy-policy", "en", {
        "[NAME]": "name",
        "[STREET, POSTCODE CITY, COUNTRY]": "address_en",
        "[CONTACT ADDRESS]": "email",
    }),
    ("datenschutz.de.md", "datenschutz", "de", {
        "[NAME]": "name",
        "[STRASSE, PLZ ORT, LAND]": "address_de",
        "[KONTAKTADRESSE]": "email",
    }),
]

STYLE = """
:root { color-scheme: light dark; --bg: #fbf8ff; --fg: #1b1b21; --muted: #46464f; --line: #c6c5d0; --accent: #3d63d8; }
@media (prefers-color-scheme: dark) { :root { --bg: #131318; --fg: #e4e1e9; --muted: #c6c5d0; --line: #46464f; --accent: #b4c5ff; } }
body { margin: 0; background: var(--bg); color: var(--fg); font: 16px/1.6 system-ui, sans-serif; }
main { max-width: 760px; margin: 0 auto; padding: 32px 16px 64px; }
h1 { font-size: 1.8rem; line-height: 1.25; } h2 { margin-top: 2em; font-size: 1.25rem; }
a { color: var(--accent); }
table { width: 100%; border-collapse: collapse; margin: 1em 0; font-size: .95rem; }
th, td { text-align: left; vertical-align: top; padding: 8px 10px; border-bottom: 1px solid var(--line); }
th { color: var(--muted); font-weight: 600; }
"""


def ask(prompt, default=""):
    suffix = f" [{default}]" if default else ""
    while True:
        value = input(f"{prompt}{suffix}: ").strip() or default
        if value:
            return value
        print("  Bitte ausfüllen.")


def load_values():
    if os.path.exists(VALUES):
        with open(VALUES, encoding="utf-8") as f:
            values = json.load(f)
        print(f"Gespeicherte Angaben: {values['name']}, {values['street']}, {values['city']}, {values['country']}, {values['email']}")
        if input("Weiter verwenden? [J/n]: ").strip().lower() in ("", "j", "ja", "y", "yes"):
            return values
    print("Angaben für den Abschnitt „Verantwortlicher“ der Datenschutzerklärung:")
    values = {
        "name": ask("Name (Person oder Firma)"),
        "street": ask("Straße und Hausnummer"),
        "city": ask("PLZ und Ort"),
        "country": ask("Land", "Deutschland"),
        "email": ask("E-Mail-Adresse für Datenschutzfragen"),
    }
    os.makedirs(OUT_ROOT, exist_ok=True)
    with open(VALUES, "w", encoding="utf-8") as f:
        json.dump(values, f, ensure_ascii=False, indent=2)
    os.chmod(VALUES, 0o600)
    return values


def main():
    values = load_values()
    country_en = COUNTRIES_EN.get(values["country"].lower(), values["country"])
    fills = {
        "name": values["name"],
        "address_de": f"{values['street']}, {values['city']}, {values['country']}",
        "address_en": f"{values['street']}, {values['city']}, {country_en}",
        "email": values["email"],
    }
    os.makedirs(OUT, exist_ok=True)
    for template, name, lang, placeholders in PAGES:
        with open(os.path.join(REPO, "docs", "play", template), encoding="utf-8") as f:
            text = f.read()
        for placeholder, key in placeholders.items():
            if placeholder not in text:
                sys.exit(f"Platzhalter {placeholder} fehlt in {template}")
            # markdown would read the address as plain text; the line break keeps the lines apart
            text = text.replace(placeholder, fills[key])
        text = text.replace(fills["email"], f"<{fills['email']}>", 1)
        with open(os.path.join(OUT, f"{name}.md"), "w", encoding="utf-8") as f:
            f.write(text)
        body = markdown.markdown(text, extensions=["tables", "nl2br"])
        title = text.splitlines()[0].lstrip("# ").strip()
        page = (f'<!doctype html>\n<html lang="{lang}">\n<head>\n<meta charset="utf-8">\n'
                f'<meta name="viewport" content="width=device-width, initial-scale=1">\n'
                f"<title>{html.escape(title)}</title>\n<style>{STYLE}</style>\n</head>\n"
                f"<body>\n<main>\n{body}\n</main>\n</body>\n</html>\n")
        with open(os.path.join(OUT, f"{name}.html"), "w", encoding="utf-8") as f:
            f.write(page)
        print(f"✓ {os.path.join(OUT, name + '.html')}")
    print("\nFertig. Beide HTML-Dateien auf needanapp.de hochladen, z. B. als")
    print("  https://needanapp.de/needafile/privacy-policy.html  (Englisch, für die Play Console)")
    print("  https://needanapp.de/needafile/datenschutz.html")


if __name__ == "__main__":
    main()

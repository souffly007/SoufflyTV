#!/usr/bin/env python3
"""
Remplit les « sha256: A_REMPLIR » du manifeste Flatpak.

Pour chaque archive (url: …) dont l'empreinte est à remplir, le script la télécharge,
calcule son SHA-256 et l'écrit dans le manifeste. À lancer une fois, puis à chaque
changement de version d'une bibliothèque (remettre A_REMPLIR sur la ligne concernée).

Usage : python3 flatpak/fill-checksums.py [chemin/du/manifeste.yml]
"""
import hashlib
import pathlib
import re
import sys
import urllib.request

PLACEHOLDER = "A_REMPLIR"


def sha256_of(url: str) -> str:
    print(f"  téléchargement de {url}")
    digest = hashlib.sha256()
    request = urllib.request.Request(url, headers={"User-Agent": "SoufflyTV-flatpak"})
    with urllib.request.urlopen(request, timeout=120) as response:
        total = 0
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
    print(f"  {total / 1_048_576:.1f} Mo → {digest.hexdigest()}")
    return digest.hexdigest()


def main() -> int:
    default = pathlib.Path(__file__).with_name("app.souffly.SoufflyTV.yml")
    manifest = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else default
    lines = manifest.read_text(encoding="utf-8").splitlines(keepends=True)

    last_url = None
    changed = 0
    for i, line in enumerate(lines):
        url = re.match(r"\s*url:\s*(\S+)", line)
        if url:
            last_url = url.group(1)
            continue
        sha = re.match(r"(\s*sha256:\s*)" + PLACEHOLDER + r"\s*$", line)
        if sha:
            if not last_url:
                print(f"ligne {i + 1} : empreinte sans url, ignorée")
                continue
            lines[i] = f"{sha.group(1)}{sha256_of(last_url)}\n"
            changed += 1
            last_url = None

    if changed:
        manifest.write_text("".join(lines), encoding="utf-8")
        print(f"\n{changed} empreinte(s) écrite(s) dans {manifest}")
    else:
        print("Aucune empreinte à remplir.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

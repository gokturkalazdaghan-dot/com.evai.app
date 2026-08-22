#!/usr/bin/env python3
"""docs/legal/*.md dosyalarini deploy/site/legal/ altina HTML olarak uretir.

NEDEN VAR
---------
Yasal belgeler tek bir kaynakta (docs/legal/*.md) tutuluyor. Ayni metni
bir de elle HTML olarak yazmak, ikisinin zamanla ayrisip gizlilik
politikasinin iki farkli surumunun dolasmasi demek olurdu.

NEDEN GITHUB PAGES DEGIL
------------------------
Play, calisan bir gizlilik politikasi adresi sart kosuyor. Bunu kendi
alan adinizda sunmak hem daha kurumsal duruyor hem de ucuncu bir
servise bagimlilik birakmiyor -- Pages kapali kalirsa magaza sayfasi
da askida kalir.

CALISTIRMA
    python deploy/site-olustur.py
"""
import io
import os
import re
import sys

import markdown

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KAYNAK = os.path.join(ROOT, "docs", "legal")
HEDEF = os.path.join(ROOT, "deploy", "site", "legal")

# Jekyll on maddesi (--- ... ---) HTML'e gitmemeli.
FRONT_MATTER = re.compile(r"\A---\s*\n.*?\n---\s*\n", re.DOTALL)

SAYFA = """<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} — armanalabs</title>
<style>
  :root {{ color-scheme: dark; }}
  body {{
    margin: 0; background: #05080b; color: #dbe7ee;
    font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    line-height: 1.7;
  }}
  main {{ max-width: 46rem; margin: 0 auto; padding: 2.5rem 1.25rem 5rem; }}
  h1 {{ font-size: 1.5rem; }}
  h2 {{ font-size: 1.15rem; margin-top: 2.2rem; }}
  h3 {{ font-size: 1rem; }}
  a {{ color: #38bdf8; }}
  code {{ background: #0e1620; padding: .1rem .3rem; border-radius: 3px; }}
  hr {{ border: 0; border-top: 1px solid #1b2833; margin: 2rem 0; }}
  table {{ border-collapse: collapse; width: 100%; }}
  th, td {{ border: 1px solid #1b2833; padding: .5rem; text-align: left; }}
  .geri {{ display: inline-block; margin-bottom: 1.5rem; font-size: .9rem; }}
</style>
</head>
<body>
<main>
<a class="geri" href="/">&larr; armanalabs</a>
{icerik}
</main>
</body>
</html>
"""


def baslik_bul(md_metin, yedek):
    for satir in md_metin.splitlines():
        if satir.startswith("# "):
            return satir[2:].strip()
    return yedek


def main():
    if not os.path.isdir(KAYNAK):
        print("kaynak dizin yok:", KAYNAK)
        return 1

    os.makedirs(HEDEF, exist_ok=True)
    uretilen = 0

    for ad in sorted(os.listdir(KAYNAK)):
        if not ad.endswith(".md") or ad.lower() == "readme.md":
            continue

        yol = os.path.join(KAYNAK, ad)
        metin = io.open(yol, encoding="utf-8").read()
        metin = FRONT_MATTER.sub("", metin)

        govde = markdown.markdown(metin, extensions=["tables", "sane_lists"])
        slug = ad[:-3]

        sayfa = SAYFA.format(
            lang="en" if slug.endswith("-en") else "tr",
            title=baslik_bul(metin, slug),
            icerik=govde,
        )

        # Uzantisiz adres: /legal/privacy-policy-tr
        dizin = os.path.join(HEDEF, slug)
        os.makedirs(dizin, exist_ok=True)
        io.open(os.path.join(dizin, "index.html"), "w", encoding="utf-8").write(sayfa)

        print("uretildi: /legal/%s/" % slug)
        uretilen += 1

    print("toplam:", uretilen)
    return 0


if __name__ == "__main__":
    sys.exit(main())

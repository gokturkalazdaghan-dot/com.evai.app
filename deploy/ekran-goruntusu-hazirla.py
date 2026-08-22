#!/usr/bin/env python3
"""Ham cihaz ekran goruntulerini Play Console'un kabul ettigi bicime getirir.

NEDEN GEREKLI
-------------
Test cihazi 1080x2340 uretiyor; en-boy orani 2.17. Play, telefon ekran
goruntulerinde orani EN FAZLA 2:1 kabul ediyor -- ham dosyalar oldugu
gibi yuklenirse REDDEDILIR.

Kirpmak yerine kucultup yanlara dolgu konuyor: kirpmak ekranin altini
ya da ustunu keser (alt gezinme cubugu ya da baslik gider). Dolgu rengi
uygulamanin kendi zemini oldugu icin ek bir cerceve gibi degil, dogal
duruyor.

Hedef 1080x1920 (16:9) -- Play'in acikca onerdigi oran.

CALISTIRMA
    python deploy/ekran-goruntusu-hazirla.py <ham-dizin> <hedef-dizin>
"""
import os
import sys

from PIL import Image

# Uygulamanin zemin rengi (ui/theme Theme.kt ile ayni aile).
DOLGU = (0x05, 0x08, 0x0B)

HEDEF_EN = 1080
HEDEF_BOY = 1920


def hazirla(kaynak, hedef):
    im = Image.open(kaynak).convert("RGB")

    # Orani koruyarak hedefe SIGDIR.
    olcek = min(HEDEF_EN / im.width, HEDEF_BOY / im.height)
    yeni = (max(1, int(im.width * olcek)), max(1, int(im.height * olcek)))
    im = im.resize(yeni, Image.LANCZOS)

    tuval = Image.new("RGB", (HEDEF_EN, HEDEF_BOY), DOLGU)
    tuval.paste(im, ((HEDEF_EN - yeni[0]) // 2, (HEDEF_BOY - yeni[1]) // 2))
    tuval.save(hedef, "PNG")
    return tuval


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1

    ham, cikti = sys.argv[1], sys.argv[2]
    os.makedirs(cikti, exist_ok=True)

    sayi = 0
    for ad in sorted(os.listdir(ham)):
        if not ad.endswith(".png"):
            continue
        kaynak = os.path.join(ham, ad)
        hedef = os.path.join(cikti, ad)
        im = hazirla(kaynak, hedef)
        kb = os.path.getsize(hedef) / 1024
        print("%-24s %sx%s  %.0f KB  oran %.2f:1"
              % (ad, im.width, im.height, kb, im.height / im.width))
        sayi += 1

    print("toplam:", sayi)
    return 0


if __name__ == "__main__":
    sys.exit(main())

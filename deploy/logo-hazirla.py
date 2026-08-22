#!/usr/bin/env python3
"""Verilen logo maketinden site icin kullanilabilir varliklar uretir.

GIRDI BIR MAKET, LOGO DOSYASI DEGIL
-----------------------------------
Elimizdeki gorsel bir urun maketi: amblem bir tas altligin uzerinde,
kumas zeminde, perspektifli ve gollgeli olarak render edilmis. Bu
haliyle:
  - Basliga 26 piksel olarak konsa bulanik bir leke olur
  - Favicon olarak okunmaz
  - Kose dokularini (kumas) da beraberinde tasir

Burada yapilan, o maketten kullanilabilir iki varlik cikarmak:

  logo-amblem.png  Kalkan bolgesi kirpilip koseleri site zeminine
                   karistirilmis kare amblem (baslik + favicon).
  logo-hero.png    Maketin kendisi, hafifce optimize edilmis. Buyuk
                   boyutta gosterildiginde zaten iyi duruyor -- zaten
                   bunun icin uretilmis.

KALICI COZUM
------------
Logonun VEKTOR (SVG) surumu, maketi ureten taraftan istenmelidir.
Vektor her boyutta keskin kalir; buradaki 232 pikselik kirpma
buyutuldugunde yumusar.

CALISTIRMA
    python deploy/logo-hazirla.py
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VARLIK = os.path.join(ROOT, "deploy", "site", "assets")

HAM = os.path.join(VARLIK, "logo-ham.png")

# Kalkanin ham gorseldeki yeri (elle olculdu).
AMBLEM_KUTU = (398, 118, 630, 350)

# Site zemini; kose karisimi buna dogru yapilir.
ZEMIN = (5, 8, 11)


def amblem_uret(hedef_yan=512):
    im = Image.open(HAM).convert("RGB").crop(AMBLEM_KUTU)

    # Buyutme: LANCZOS, kucuk kaynakta en az bozan yontem. Yine de
    # vektor kadar keskin OLMAZ -- kaynak 232 piksel.
    im = im.resize((hedef_yan, hedef_yan), Image.LANCZOS)

    # Koseleri zemine karistir: kirpmada kalan kumas dokusu, koyu bir
    # sitede kirli bir cerceve gibi gorunuyordu. Yuvarlak bir maske ile
    # disari dogru soneriyor.
    maske = Image.new("L", (hedef_yan, hedef_yan), 0)
    ImageDraw.Draw(maske).ellipse(
        [-hedef_yan * 0.04, -hedef_yan * 0.04,
         hedef_yan * 1.04, hedef_yan * 1.04],
        fill=255,
    )
    maske = maske.filter(ImageFilter.GaussianBlur(hedef_yan * 0.05))

    zemin = Image.new("RGB", (hedef_yan, hedef_yan), ZEMIN)
    zemin.paste(im, (0, 0), maske)

    # 512'lik surum DISKE YAZILMIYOR: sitede 30 piksel gosteriliyor ve
    # o dosya 343 KB. Gorunen boyutun 17 kati veriyi her ziyaretciye
    # indirtmenin karsiligi yok. Buyuk surum yalnizca ara adim.
    #
    # Basliktaki amblem icin 96 piksel, yuksek yogunluklu ekranlarda
    # bile fazlasiyla yeterli.
    kucuk = zemin.resize((96, 96), Image.LANCZOS)
    kyol = os.path.join(VARLIK, "logo-amblem-96.png")
    kucuk.save(kyol, "PNG", optimize=True)
    print("uretildi: logo-amblem-96.png  96x96  %d KB"
          % (os.path.getsize(kyol) / 1024))
    return zemin


def hero_uret():
    """Kahraman gorseli.

    PNG DEGIL JPEG: kaynak bir render, yani fotograf benzeri surekli
    tonlu bir gorsel. PNG kayipsiz sikistirir ve boyle bir gorselde
    870 KB'a cikiyordu -- her ziyaretcinin indirecegi bir yuk. JPEG
    ayni gorseli gozle ayirt edilemeyecek kalitede onda birine indiriyor.
    """
    im = Image.open(HAM).convert("RGB")
    yol = os.path.join(VARLIK, "logo-hero.jpg")
    im.save(yol, "JPEG", quality=82, optimize=True, progressive=True)
    print("uretildi: logo-hero.jpg   %dx%d  %d KB"
          % (im.size[0], im.size[1], os.path.getsize(yol) / 1024))


def main():
    if not os.path.exists(HAM):
        print("kaynak yok:", HAM)
        return 1
    amblem_uret()
    hero_uret()

    # Deneme kirpmalari depoya girmemeli.
    for ad in os.listdir(VARLIK):
        if ad.startswith("deneme-"):
            os.remove(os.path.join(VARLIK, ad))
            print("silindi:", ad)
    return 0


if __name__ == "__main__":
    sys.exit(main())

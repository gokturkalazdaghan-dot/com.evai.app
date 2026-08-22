#!/usr/bin/env python3
"""Play Console magaza gorsellerini uretir.

  play-assets/ikon-512.png        Uygulama ikonu (512x512, 32-bit PNG)
  play-assets/one-cikan-1024.png  One cikan grafik (1024x500)

NEDEN KOD ILE URETILIYOR
------------------------
Logo uygulama icinde bir Canvas cizimi (bkz. ui/theme/EvaLogo.kt) --
hazir bir PNG yok. Ayni geometriyi burada tekrar ederek magaza ikonunun
uygulamanin icindeki logoyla BIREBIR ayni durmasi saglaniyor. Elle
cizilmis ayri bir ikon, ilk guncellemede uygulamadakinden ayrisirdi.

CALISTIRMA
    python deploy/magaza-gorselleri.py
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HEDEF = os.path.join(ROOT, "play-assets")

# EvaLogo.kt ve Theme.kt ile ayni degerler.
SARI = (0xFF, 0xD6, 0x0A)
NEON = (0x00, 0xE5, 0xFF)
GOK = (0x38, 0xBD, 0xF8)
ZEMIN = (0x07, 0x0D, 0x13)
METIN = (0xE6, 0xF1, 0xF6)
SOLUK = (0x8A, 0xA3, 0xB2)

RING_STROKE_FRACTION = 0.075
RING_RADIUS_FRACTION = 0.42


def _dikey_gradyan(cizim, kutu, ust, alt):
    x0, y0, x1, y1 = kutu
    yukseklik = max(1, y1 - y0)
    for y in range(y0, y1):
        t = (y - y0) / yukseklik
        renk = tuple(int(ust[i] + (alt[i] - ust[i]) * t) for i in range(3))
        cizim.line([(x0, y), (x1, y)], fill=renk)


def logo_ciz(img, merkez, yan, dalgalar=True):
    """EvaLogo.kt'deki cizimin birebir karsiligi."""
    d = ImageDraw.Draw(img)
    cx, cy = merkez
    yaricap = yan * RING_RADIUS_FRACTION
    kalinlik = max(2, int(yan * RING_STROKE_FRACTION))

    # Sari halka
    d.ellipse(
        [cx - yaricap, cy - yaricap, cx + yaricap, cy + yaricap],
        outline=SARI,
        width=kalinlik,
    )

    # Simsek -- gradyan icin ayri katmanda cizilip maske olarak uygulanir.
    boy = yaricap * 1.15
    en = yaricap * 0.62
    nokta = [
        (cx + en * 0.18, cy - boy / 2),
        (cx - en * 0.42, cy + boy * 0.10),
        (cx - en * 0.02, cy + boy * 0.10),
        (cx - en * 0.18, cy + boy / 2),
        (cx + en * 0.44, cy - boy * 0.12),
        (cx + en * 0.02, cy - boy * 0.12),
    ]

    maske = Image.new("L", img.size, 0)
    ImageDraw.Draw(maske).polygon(nokta, fill=255)

    gradyan = Image.new("RGB", img.size, NEON)
    _dikey_gradyan(
        ImageDraw.Draw(gradyan),
        (0, int(cy - boy / 2), img.size[0], int(cy + boy / 2)),
        NEON,
        GOK,
    )
    img.paste(gradyan, (0, 0), maske)

    if not dalgalar:
        return

    # Enerji isaretleri
    bosluk = yaricap * 0.38
    for i, oran in enumerate([0.30, 0.52, 0.30]):
        kayma = yaricap + bosluk * (i + 1)
        yari = yaricap * oran
        alfa = 0.85 - i * 0.22
        renk = tuple(int(c * alfa + ZEMIN[j] * (1 - alfa)) for j, c in enumerate(NEON))
        for x in (cx - kayma, cx + kayma):
            d.line([(x, cy - yari), (x, cy + yari)], fill=renk,
                   width=max(2, int(kalinlik * 0.7)))


def ikon_uret():
    """512x512 uygulama ikonu.

    Yesil arka plan KULLANILMIYOR: uygulamanin paletinden yesil bilincli
    olarak cikarilmisti, ama launcher ikonunun arka plani hala Google
    yesiliydi (#0F9D58). Ikon kullanicinin gordugu ILK sey; kimlikle
    catismamali.
    """
    yan = 512
    img = Image.new("RGB", (yan, yan), ZEMIN)
    _dikey_gradyan(ImageDraw.Draw(img), (0, 0, yan, yan), (0x0B, 0x14, 0x1C), ZEMIN)

    # Dalgalar ikonda YOK: 512'lik kareye sigdiginda cok ince kaliyor ve
    # kucuk boyutlarda kirli bir leke gibi gorunuyor.
    logo_ciz(img, (yan / 2, yan / 2), yan * 0.82, dalgalar=False)

    yol = os.path.join(HEDEF, "ikon-512.png")
    img.save(yol, "PNG")
    print("uretildi:", yol, "512x512")


def _yazitipi(boyut, kalin=False):
    adaylar = [
        r"C:\Windows\Fonts\segoeuib.ttf" if kalin else r"C:\Windows\Fonts\segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for a in adaylar:
        if os.path.exists(a):
            return ImageFont.truetype(a, boyut)
    return ImageFont.load_default()


def one_cikan_uret():
    """1024x500 one cikan grafik.

    Uzerindeki metin, uygulamanin GERCEKTEN yaptigi isi soyler. Play,
    uygulamada olmayan bir ozelligi gorselde vaat etmeyi politika ihlali
    sayiyor -- "yapay zeka", "anlik doluluk" gibi ifadeler yok.
    """
    en, boy = 1024, 500
    img = Image.new("RGB", (en, boy), ZEMIN)
    _dikey_gradyan(ImageDraw.Draw(img), (0, 0, en, boy), (0x0C, 0x17, 0x20), (0x05, 0x09, 0x0D))
    d = ImageDraw.Draw(img)

    # Zemin dokusu: hafif izgara, teknik his.
    for x in range(0, en, 48):
        d.line([(x, 0), (x, boy)], fill=(0x0E, 0x1B, 0x24))
    for y in range(0, boy, 48):
        d.line([(0, y), (en, y)], fill=(0x0E, 0x1B, 0x24))

    # Logo merkezi ve boyu, enerji cizgilerinin metne BINMEYECEGI sekilde
    # secildi: cizgiler halka yaricapinin 3.14 kati kadar disari tasiyor
    # (yaricap + 3 x bosluk). 270'lik bir yanda bu ~453 piksel eder, metin
    # de bu yuzden 510'dan basliyor.
    logo_ciz(img, (210, boy / 2), 270)

    d = ImageDraw.Draw(img)
    d.text((510, 148), "EVA AI", font=_yazitipi(72, kalin=True), fill=METIN)
    d.text((512, 240), "Şarj istasyonu bul, fiyatı gör", font=_yazitipi(32), fill=NEON)
    d.text((512, 296), "Yakınındaki istasyonlar, güncel kWh",
           font=_yazitipi(25), fill=SOLUK)
    d.text((512, 330), "fiyatları, aracına uyan soketler.",
           font=_yazitipi(25), fill=SOLUK)

    yol = os.path.join(HEDEF, "one-cikan-1024.png")
    img.save(yol, "PNG")
    print("uretildi:", yol, "1024x500")


if __name__ == "__main__":
    os.makedirs(HEDEF, exist_ok=True)
    ikon_uret()
    one_cikan_uret()
    sys.exit(0)

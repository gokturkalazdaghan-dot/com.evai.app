# Play Console varlıkları

`deploy/magaza-gorselleri.py` ve `deploy/ekran-goruntusu-hazirla.py`
tarafından üretilir. Elle düzenlemeyin — kaynak kod değişince yeniden
üretin, yoksa mağazadaki görsel uygulamadan ayrışır.

| Dosya | Nereye |
|---|---|
| `ikon-512.png` | Uygulama simgesi (512×512, PNG, <1 MB) |
| `one-cikan-1024.png` | Öne çıkan grafik (1024×500) |
| `ekran-goruntuleri/*.png` | Telefon ekran görüntüleri (1080×1920, 16:9) |

## Ekran görüntüleri neden ölçeklendi

Test cihazı 1080×2340 üretiyor; en-boy oranı 2,17. Play telefon ekran
görüntülerinde oranı **en fazla 2:1** kabul ediyor, ham dosyalar
reddedilir. Kırpmak yerine küçültülüp yanlara uygulamanın kendi zemin
rengiyle dolgu konuyor — kırpmak başlığı ya da alt gezinme çubuğunu
keserdi.

## Metinler

Mağaza açıklamaları `docs/store-listing.md` içinde, beş dilde.

## Yeniden üretme

```bash
python deploy/magaza-gorselleri.py
python deploy/ekran-goruntusu-hazirla.py <ham-dizin> play-assets/ekran-goruntuleri
```

Ham ekran görüntüleri cihazdan alınır. Cihaz `adb shell input` komutunu
engellediği için ekranlar arası gezinme yapılamıyor; her ekran için
`MainActivity.kt` içindeki `startDestination` geçici olarak
değiştirilip derleniyor, sonra geri alınıyor.

# Yasal belgeler

Bu klasör Google Play başvurusu için gereken metinleri tutar.

| Dosya | Ne için |
|---|---|
| `privacy-policy-tr.md` / `-en.md` | Gizlilik politikası — **Play'de zorunlu** |
| `terms-of-service-tr.md` / `-en.md` | Kullanım şartları — abonelik sattığımız için gerekli |
| `build-pdf.py` | Markdown → PDF üreteci |
| `*.pdf` | Üretilen PDF'ler (kaynak `.md`, PDF türetilmiş) |

PDF'leri yeniden üretmek için:

```bash
python docs/legal/build-pdf.py
```

---

## Önemli: Play PDF kabul etmez, URL ister

Google Play Console'daki **gizlilik politikası alanı bir bağlantı (URL)
bekler.** PDF dosyası tek başına başvuruyu geçirmez — herkese açık,
giriş gerektirmeyen bir web adresi gerekir.

PDF'ler destekleyici belgedir: kullanıcıya e-postayla göndermek, arşiv
tutmak ve hukuk incelemesine sunmak için.

### Nasıl yayınlanır

En hızlı ve ücretsiz yol GitHub Pages:

1. Bir depo oluşturun (örn. `eva-legal`), `.md` dosyalarını koyun.
2. Settings → Pages → kaynak olarak `main` dalını seçin.
3. Adres şu biçimde olur:
   `https://kullanici.github.io/eva-legal/privacy-policy-tr`

Kendi alan adınız varsa (`alan-adiniz.com`) politikayı orada yayınlamak
daha iyidir: uygulama, sunucu ve politika aynı alan adı altında olur.

### Play Console'a girilecek alanlar

| Alan | Değer |
|---|---|
| Gizlilik politikası URL'si | Yayınladığınız adres |
| Uygulama erişimi | Tüm işlevler giriş olmadan kullanılabilir |
| Reklamlar | Uygulama reklam içermiyor |
| İçerik derecelendirmesi | Anketi doldurun (araç/navigasyon) |
| Hedef kitle | 13 yaş ve üzeri |
| Veri güvenliği | Aşağıdaki tabloyu kullanın |

---

## Veri güvenliği formu — doldurulacak cevaplar

Bu tablo kodun **gerçek** davranışından çıkarıldı. Yanlış beyan,
uygulamanın mağazadan kaldırılma sebebidir.

### Toplanan veriler

| Veri türü | Toplanıyor mu | Paylaşılıyor mu | Zorunlu mu | Amaç |
|---|---|---|---|---|
| Yaklaşık konum | Evet | Hayır | Hayır (isteğe bağlı) | Uygulama işlevi |
| Kesin konum | Evet | Hayır | Hayır (isteğe bağlı) | Uygulama işlevi |
| Cihaz kimliği | Evet | Hayır | Evet | Uygulama işlevi, sahtecilik önleme |
| Satın alma geçmişi | Evet | Hayır | Evet | Uygulama işlevi |
| Diğer (araç telemetrisi) | Evet | Hayır | Hayır (isteğe bağlı) | Uygulama işlevi |

### Toplanmayan veriler — "Hayır" işaretlenecek

Ad, e-posta, telefon, adres, fotoğraf, video, **ses**, kişiler, takvim,
SMS, dosyalar, sağlık, finans bilgisi, arama geçmişi, uygulama listesi,
reklam kimliği.

> **Ses** özellikle vurgulanıyor: sesli asistan üründen çıkarıldı, mikrofon
> izni manifestten kaldırıldı. Eski bir sürüme bakıp "ses kaydı" işaretlemek
> yanlış beyan olur.

### Güvenlik uygulamaları

- Aktarım sırasında şifreleme: **Evet** (HTTPS)
- Kullanıcı veri silme talep edebilir: **Evet** (uygulama içi + e-posta)
- Bağımsız güvenlik incelemesinden geçti: **Hayır**

---

## Uygulama içinde bağlantı verilmesi gerekenler

Play, abonelik satan uygulamalarda şunların uygulama içinden erişilebilir
olmasını ister:

- Gizlilik politikası
- Kullanım şartları
- Abonelik koşulları (yenileme, iptal, iade)

Abonelik koşulları paywall ekranında yazıyor. Politika ve şartlar için
uygulama içine bağlantı eklenmesi gerekiyor — **bu henüz yapılmadı**,
yayın öncesi tamamlanmalı.

---

## Hukuki inceleme uyarısı

Bu metinler uygulamanın gerçek davranışına dayanarak hazırlandı ve
teknik olarak doğrudur. Ancak **hukuki danışmanlık değildir.**

Avrupa'da yayın yapacaksanız (GDPR/UK GDPR) ve abonelik satacaksanız,
bir avukatın gözden geçirmesi gerekir. Özellikle şu noktalar ülkeye göre
değişir:

- Veri sorumlusunun kimliği ve adresi (şahıs mı, şirket mi)
- AB'de temsilci atama yükümlülüğü (GDPR md. 27)
- Tüketici cayma hakkı süreleri
- Uygulanacak hukuk ve yetkili mahkeme maddeleri

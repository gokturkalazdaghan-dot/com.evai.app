---
title: Gizlilik Politikası
---

# EVA AI — Gizlilik Politikası

**Son güncelleme:** 21 Ağustos 2026
**Uygulama:** EVA AI (`com.evai.app`)

Bu politika, EVA AI mobil uygulamasının hangi verileri topladığını, neden
topladığını ve bu veriler üzerinde hangi haklara sahip olduğunuzu açıklar.

Kısaca: **adınızı, e-postanızı, telefon numaranızı istemiyoruz.** Hesap
açmanız gerekmiyor. Topladığımız her veri, size yakın ve uygun fiyatlı bir
şarj istasyonu bulmak içindir.

---

## 1. Topladığımız veriler

### 1.1 Konum

**Ne:** Cihazınızın yaklaşık veya kesin konumu (enlem/boylam).

**Neden:** "Yakınımdaki istasyonlar" sorusunun cevabı konuma bağlıdır.
Konumunuz olmadan hangi istasyonun size yakın veya ucuz olduğunu
söyleyemeyiz.

**Nasıl kullanılır:** Konum, yakındaki istasyonları sorgulamak ve yol
tarifi başlatmak için sunucumuza gönderilir. Sorgu sonrası **kalıcı
olarak saklanmaz**; sunucuda konum geçmişi tutulmaz.

**İzin:** Uygulama içinde reddedebilirsiniz. Reddederseniz uygulama
çalışmaya devam eder ancak istasyon listesi gösterilemez — çünkü
gösterilecek bir "yakın" nokta yoktur.

### 1.2 Cihaz kimliği

**Ne:** Uygulamanın ilk açılışta ürettiği rastgele bir kimlik (UUID) ve
buna ait bir imzalama anahtarı.

**Neden:** İsteklerin gerçekten uygulamadan geldiğini doğrulamak ve
abonelik durumunuzu cihazınıza bağlamak için.

**Önemli:** Bu **reklam kimliği değildir**, cihazın donanım kimliği
(IMEI, MAC, Android ID) değildir ve sizi tanımlamaz. Uygulamayı
kaldırdığınızda kaybolur.

### 1.3 Araç bilgileri

**Ne:** Marka, model, batarya kapasitesi, soket tipi ve şarj yüzdesi.

**Nerede saklanır:** **Yalnızca cihazınızda**, şifreli olarak. Marka ve
model sunucuya gönderilmez.

**Sunucuya giden tek şey:** İstasyon filtrelemesi için soket tipi ve
şarj yüzdesi. Plaka, şasi numarası (VIN) gibi gerçek dünya kimlikleri
**hiç toplanmaz**.

### 1.4 Araç telemetrisi (isteğe bağlı)

**Ne:** Bir OBD-II Bluetooth adaptörü bağlarsanız: batarya yüzdesi,
tahmini menzil, hız, kilometre ve şarj durumu.

**Neden:** Şarj seviyesini elle girmeniz gerekmesin ve düştüğünde sizi
uyarabilelim.

**Nasıl:** Adaptör eşleştirmesi telefonunuzun kendi Bluetooth
ayarlarından yapılır. Uygulama yalnızca eşleştirilmiş adaptörden veri
okur; çevredeki cihazları taramaz, konum çıkarımı için kullanmaz.

**Tamamen isteğe bağlıdır.** Adaptör bağlamazsanız bu veri hiç
oluşmaz.

### 1.5 Bildirimler

Batarya seviyeniz %50 ve %30'un altına düştüğünde bildirim göndeririz.
Bildirim iznini reddedebilir veya sonradan kapatabilirsiniz.

---

## 2. Toplamadığımız veriler

Aşağıdakiler **hiçbir koşulda** toplanmaz:

- Ad, soyad, e-posta, telefon numarası
- Reklam kimliği veya izleme tanımlayıcıları
- Rehber, fotoğraflar, dosyalar
- **Ses kaydı** (uygulamada sesli asistan bulunmamaktadır)
- Kamera görüntüsü
- Plaka veya şasi numarası
- Konum geçmişi

---

## 3. Verinin paylaşıldığı yerler

Verinizi **satmıyoruz** ve reklam amacıyla kimseyle paylaşmıyoruz.
Uygulamanın çalışması için aşağıdaki servisler kullanılır:

| Servis | Ne için | Ne görür |
|---|---|---|
| Google Play Hizmetleri (Haritalar, Konum) | Harita gösterimi ve konum | Konum |
| Google Play Billing / RevenueCat | Abonelik yönetimi | Anonim cihaz kimliği, satın alma durumu |
| Google Play Integrity | Sahte uygulama tespiti | Cihaz bütünlük durumu |
| Open Charge Map | İstasyon envanteri | Yalnızca sorgulanan bölge — kişisel veri gitmez |

Yol tarifi başlattığınızda telefonunuzun harita uygulaması açılır ve
hedef bilgisi ona aktarılır; o noktadan sonra o uygulamanın kendi
gizlilik politikası geçerlidir.

---

## 4. Verinin saklanma süresi

| Veri | Süre |
|---|---|
| Konum sorgusu | Saklanmaz (yalnızca istek anında işlenir) |
| Cihaz kimliği ve abonelik kaydı | Abonelik aktifken + sonrasında 12 ay (yasal/muhasebe) |
| Araç telemetrisi (son okuma) | 10 dakika, ardından silinir |
| Araç profili | Cihazınızda, siz silene kadar |
| Kullanım sayaçları (ücretsiz kota) | 30 gün |

---

## 5. Haklarınız

Bulunduğunuz ülkeye göre (AB/AEA ve Birleşik Krallık'ta GDPR, Kaliforniya'da
CCPA kapsamında) şu haklara sahipsiniz:

- **Erişim:** Hakkınızda tuttuğumuz veriyi isteme
- **Silme:** Verinizin silinmesini isteme
- **Düzeltme:** Yanlış veriyi düzelttirme
- **İtiraz ve kısıtlama:** İşlemeye itiraz etme
- **Taşınabilirlik:** Verinizi makine tarafından okunabilir biçimde alma
- **Şikâyet:** Ülkenizdeki veri koruma otoritesine başvurma

### Verinizi nasıl silersiniz

**Uygulama içinden:** Ayarlar → Verilerimi sil.

**E-posta ile:** [gokturkalazdaghan@gmail.com](mailto:gokturkalazdaghan@gmail.com)
adresine "Veri silme talebi" konusuyla yazın. Talebiniz **30 gün içinde**
karşılanır.

Uygulamayı kaldırmanız, cihazınızdaki verileri siler; sunucudaki abonelik
kaydı için yukarıdaki yollardan birini kullanmanız gerekir.

---

## 6. Hukuki dayanak (GDPR)

| İşleme | Dayanak |
|---|---|
| Konumla istasyon sorgulama | Açık rıza (izin ekranı) |
| Cihaz kimliği ile abonelik | Sözleşmenin ifası |
| Sahtecilik tespiti | Meşru menfaat |
| Araç telemetrisi | Açık rıza (adaptör bağlama) |

---

## 7. Çocuklar

EVA AI 13 yaşın altındaki çocuklara yönelik değildir ve bilerek onlardan
veri toplamaz.

---

## 8. Veri güvenliği

- Sunucuyla tüm iletişim **HTTPS** üzerinden şifrelidir.
- İstekler cihaz anahtarıyla **imzalanır**; imzasız istek reddedilir.
- Araç profili cihazda **şifreli** saklanır.
- Ödeme bilgileriniz bize **hiç ulaşmaz** — Google Play işler.

---

## 9. Değişiklikler

Bu politika değişirse güncel sürümü bu adreste yayınlarız ve uygulama
içinde bildiririz. "Son güncelleme" tarihi en üstte yer alır.

---

## 10. İletişim

**Veri sorumlusu:** Göktürk Alazdağhan
**E-posta:** [gokturkalazdaghan@gmail.com](mailto:gokturkalazdaghan@gmail.com)

AB/AEA'da bulunuyorsanız ve endişeniz giderilmezse, bulunduğunuz ülkenin
veri koruma otoritesine şikâyette bulunma hakkınız saklıdır.

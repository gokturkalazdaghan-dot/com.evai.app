# Play Store yayın kontrol listesi

Kodun tarafı hazır: `bundleRelease` çalışıyor, R8 minify geçiyor, imzalama
yapılandırması bağlı. Aşağıdakiler **senin hesaplarından** gelmek zorunda —
başkası adına anahtar üretilemez.

Tüm gizli değerler `android/local.properties` dosyasına yazılır. Bu dosya
`.gitignore`'dadır ve repoya **asla** girmemelidir. CI kullanıyorsan aynı
anahtarları secret olarak tanımlayıp `-P` ile geçir.

---

## 1. Upload anahtarı (imzalama)

```bash
keytool -genkey -v -keystore eva-upload.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias eva-upload
```

`local.properties`:

```properties
EVA_KEYSTORE_FILE=C:/güvenli/yol/eva-upload.jks
EVA_KEYSTORE_PASSWORD=...
EVA_KEY_ALIAS=eva-upload
EVA_KEY_PASSWORD=...
```

> Bu dört değer yoksa `signingConfig` **oluşturulmaz** ve release çıktısı
> imzasız üretilir. Sessizce yanlış bir imza atılmaz — eksiklik açıktır.
>
> `.jks` dosyasını kaybedersen uygulamayı bir daha güncelleyemezsin. Yedekle.

## 2. Play Integrity (cihaz doğrulama)

Google Cloud'da Play Integrity API'yi etkinleştir, proje numarasını al:

```properties
GOOGLE_CLOUD_PROJECT_NUMBER=123456789012
```

> Şu an `0L` olduğu için istemci `-16: cloud project number is invalid`
> alıyor. Debug build'de bu tolere ediliyor (attestation header'sız devam
> eder), **release'de fail-closed** — yani bu değer olmadan uygulama sunucuya
> istek atamaz.

## 3. RevenueCat (abonelik)

Dashboard → Project Settings → API Keys → Google Play:

```properties
REVENUECAT_PUBLIC_API_KEY=goog_...
```

Ayrıca Play Console'da abonelik ürünlerini oluşturup RevenueCat'te
`premium` entitlement'ına bağlaman gerekiyor (`AppConfig.REVENUECAT_ENTITLEMENT_ID`).

## 4. Production backend + sertifika sabitleme

```properties
EVA_GATEWAY_BASE_URL_RELEASE=https://api.senindomainin.com
GATEWAY_CERT_PIN_1=sha256/...
GATEWAY_CERT_PIN_2=sha256/...
```

> Pin'ler boş bırakılırsa certificate pinning **devre dışı** kalır
> (`AppConfig.gatewayCertificatePins`). En az iki pin verilmeli: mevcut
> sertifika + yedek. Tek pin ile sertifika yenileme günü tüm kullanıcılar
> bağlanamaz hale gelir.

Pin'i almak için:

```bash
openssl s_client -servername api.senindomainin.com -connect api.senindomainin.com:443 \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | openssl enc -base64
```

## 5. Harita

```properties
MAPS_API_KEY=AIza...
```

> Anahtar yoksa harita **hiç çizilmez**, uygulama liste görünümüyle çalışır
> (`AppConfig.isMapEnabled`). Bu bilinçli: anahtarsız Google Maps gri bir
> alan ve "for development purposes only" filigranı gösterir — temiz bir
> listeden daha kötü bir deneyim.

### Anahtarı yazmak yetmez — API'yi etkinleştirmen gerekiyor

Anahtar geçerli olsa bile ilgili API projede **etkin değilse** harita
karoları yüklenmez. Belirti: harita çerçevesi, Google logosu ve konum
düğmesi görünür ama zemin gri kalır. Logcat'te:

```
E/Google Maps Android API: Error requesting API token. StatusCode=INVALID_ARGUMENT
```

Yapılacaklar (Google Cloud Console):

1. **Maps SDK for Android**'i etkinleştir:
   <https://console.cloud.google.com/apis/library/maps-android-backend.googleapis.com>
2. Projeye bir **faturalandırma hesabı** bağla — Maps 2018'den beri bunu
   zorunlu tutuyor (ücretsiz kotayla birlikte).
3. Anahtarı **kısıtla** (şu an kısıtlamasız; bu haliyle anahtarı ele
   geçiren herkes senin kotandan harcama yapabilir):
   - Application restrictions → **Android apps**
   - Aşağıdaki iki paketi de ekle:

   | Paket adı | SHA-1 |
   |---|---|
   | `com.eva.app.debug` | `38:BD:76:1E:A2:10:FB:4E:31:8D:DD:B2:9D:09:DF:AA:8E:06:89:8C` |
   | `com.eva.app` | *(upload anahtarının SHA-1'i — bkz. bölüm 1)* |

   Debug SHA-1'i yeniden almak için:

   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
   ```

4. API restrictions → yalnızca **Maps SDK for Android** seç.

## 6. Play Console

- [ ] Mağaza listesi (başlık, kısa/uzun açıklama)
- [ ] Ekran görüntüleri (telefon, en az 2) + feature graphic (1024×500)
- [ ] Gizlilik politikası URL'i — **zorunlu** (uygulama konum ve mikrofon kullanıyor)
- [ ] Data safety formu — konum, mikrofon, cihaz kimliği beyanı
- [ ] İçerik derecelendirme anketi
- [ ] Abonelik ürünleri + fiyatlandırma

---

## Yayın öncesi son kontrol

```bash
cd android
./gradlew bundleRelease
```

Çıktı: `app/build/outputs/bundle/release/app-release.aab`

Yüklemeden önce imzayı doğrula:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

`versionCode`'u her yüklemede artırmayı unutma (`app/build.gradle.kts`,
şu an `1`).

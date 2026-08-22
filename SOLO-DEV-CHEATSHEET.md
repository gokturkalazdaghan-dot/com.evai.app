# Eva — Solo Geliştirici Kopya-Yapıştır Haritası

Bu doküman, kod tekrar yazılmadı — sadece **nerede olduğunu** ve **hangi
sırayla dokunacağını** gösteriyor. Şantiyeden gelip 2-3 saatte kafa
karışmadan devam edebilmen için var.

---

## 1. İstediğin 3 Parça — Tam Olarak Nerede

### 1) `docker-compose.yml` (Faz 6)
**Konum:** `docker-compose.yml` (kök dizin)
Postgres+PostGIS, Redis, Gateway, Python AI ajanı, Mock CPO Aggregator —
hepsi tanımlı. Mock CPO ayrı bir **profil** (`mock`) altında, yanlışlıkla
gerçek CPO sözleşmesi sonrası da mock'a bağlı kalmayasın diye.

**Tek komut (yeni eklenenler):**
```bash
./dev-up.sh      # ortamı ayağa kaldırır (mock CPO dahil)
./dev-logs.sh    # canlı logları izler
./dev-down.sh    # durdurur (veri kalır)
./dev-reset.sh   # her şeyi sıfırlar (dikkatli kullan)
```
Artık `docker compose --profile mock up -d --build` gibi uzun komutları
ezberlemene gerek yok — yorgun kafayla yanlış yazma riski sıfırlandı.

### 2) Android + RevenueCat + Hilt (Faz 8)
**Konum:**
```
android/build.gradle.kts                          # proje seviyesi bağımlılıklar
android/app/build.gradle.kts                       # modül seviyesi (RevenueCat, Play Integrity, vb.)
android/app/src/main/java/com/eva/app/
├── EvaApplication.kt                               # Hilt + RevenueCat başlatma
├── MainActivity.kt                                 # Compose Navigation giriş noktası
├── core/AppConfig.kt                                # Gateway URL / RevenueCat key okuma
├── di/
│   ├── NetworkModule.kt                              # APIClient, StationsRepository
│   ├── SecurityModule.kt                             # SecureTokenStore, PlayIntegrityManager
│   └── CommerceModule.kt                             # RevenueCatManager, SubscriptionRepository
├── security/
│   ├── SecureTokenStore.kt                           # EncryptedSharedPreferences
│   └── PlayIntegrityManager.kt                       # Play Integrity API
├── network/APIClient.kt                              # Tüm Gateway istekleri buradan geçer
├── commerce/
│   ├── RevenueCatManager.kt                          # RevenueCat SDK sarmalayıcı
│   ├── SubscriptionRepository.kt                     # ViewModel'in konuştuğu tek yer
│   ├── SubscriptionState.kt                          # UI'ın anladığı sade model
│   └── RevenueCatError.kt
└── ui/
    ├── stations/ (StationsScreen, StationsViewModel, ...)
    └── subscription/SubscriptionViewModel.kt
```
Paket şeması aşağıda — bu, "hangisi hangisini çağırıyor" sorusuna cevap.

### 3) Mock CPO Aggregator (Faz 5b)
**Konum:** `backend/ai-services/mock_cpo_aggregator/main.py`
Zaten `docker-compose.yml`'e bağlı, `./dev-up.sh` ile otomatik ayağa kalkıyor.
Gerçek CPO'nun döneceği JSON şemasını taklit ediyor, ayrıca %10 gecikme ve
%5 hata simüle ediyor ki `tariff_fetch_tool.py`'deki retry mantığı da
gerçekçi koşullarda test edilsin.

---

## 2. Paket Haritası — Kim Kiminle Konuşuyor (Android)

```
                        ┌─────────────────────┐
                        │   MainActivity.kt    │  ← Compose Navigation
                        │   (UI giriş noktası) │
                        └──────────┬───────────┘
                                   │ hiltViewModel()
              ┌────────────────────┼────────────────────┐
              ▼                                          ▼
   ┌─────────────────────┐                   ┌──────────────────────────┐
   │  StationsViewModel   │                   │  SubscriptionViewModel    │
   └──────────┬───────────┘                   └───────────┬──────────────┘
              │                                            │
              ▼                                            ▼
   ┌─────────────────────┐                   ┌──────────────────────────┐
   │ StationsRepository    │                   │ SubscriptionRepository    │
   └──────────┬───────────┘                   └───────────┬──────────────┘
              │                                            │
              ▼                                            ▼
   ┌─────────────────────┐                   ┌──────────────────────────┐
   │     APIClient         │                   │    RevenueCatManager       │
   │ (network/)             │                   │    (commerce/)              │
   └──────────┬───────────┘                   └───────────┬──────────────┘
              │  her istekte                               │  doğrudan
              ▼  ekler                                     ▼  RevenueCat SDK'sı
   ┌─────────────────────┐                   ┌──────────────────────────┐
   │ PlayIntegrityManager  │                   │  RevenueCat Sunucuları      │
   │ SecureTokenStore       │                   │  (Google Play ile kendi    │
   │ (security/)            │                   │   doğrulamasını yapar)     │
   └──────────┬───────────┘                                │
              │                                              │ webhook
              ▼                                              ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │                      NestJS Gateway (backend)                     │
   │  DeviceAttestationGuard  ←── stations isteklerini doğrular         │
   │  RevenueCatWebhookController ←── abonelik olaylarını dinler        │
   └─────────────────────────────────────────────────────────────────┘
```

**Kritik ayrım (kafa karıştıran nokta genelde burası):**
- `PlayIntegrityManager` → "Bu isteği gönderen gerçek, kurcalanmamış Eva
  uygulaması mı?" sorusuna cevap verir. Gateway'e giden **her** istekte
  kullanılır (istasyon arama dahil).
- `RevenueCatManager` → "Bu kullanıcı ödeme yaptı mı?" sorusuna cevap
  verir. Gateway'e hiç gitmez — doğrudan RevenueCat'in kendi sunucusuyla
  konuşur. Gateway, abonelik durumunu yalnızca RevenueCat'in gönderdiği
  **webhook** üzerinden öğrenir (arka planda, kullanıcı beklemez).

Bu iki mekanizma birbirinden tamamen bağımsız — biri çökerse diğeri
çalışmaya devam eder.

---

## 3. Günde 2-3 Saatlik Gerçekçi İş Akışı

Şantiyeden geldiğinde ilk 10 dakikanı "nereden kaldım" diye hatırlamaya
harcama. Bunun yerine:

**Her oturumun ilk 2 dakikası (sabit rutin):**
```bash
./dev-up.sh
./dev-logs.sh gateway   # (ctrl+c ile çık, sadece hata var mı bak)
```

**Haftalık öneri sırası** (her gün bir tık ilerlet, bitirmek zorunda değilsin):
1. **Hafta 1:** `docker-compose.yml` + mock CPO'yu bir kez ayağa kaldır,
   `curl` ile 3 servisin de `/health` döndüğünü doğrula. Bitti say, kapat.
2. **Hafta 2:** Android Studio'yu aç, `android/` klasörünü içeri al,
   `local.properties.example`'ı kopyala, **yalnızca derlensin** hedefle
   (çalışması değil, derlenmesi).
3. **Hafta 3:** Emülatörde StationsScreen'i mock verilerle gör (Postgres'e
   elle 2-3 test istasyonu ekle — `EVA-ROADMAP.md` Faz 3'teki INSERT
   örneğini kullan).
4. **Hafta 4:** RevenueCat sandbox satın alma testini tamamla.

Her oturum sonunda tek bir şey çalışır durumda bırak — yarım bıraktığın
yerde "neden çalışmıyor" diye debug etmeye 2-3 saatin yetmez.

**Kafan çok yorgunsa ve kod yazacak gücün yoksa:** O gün sadece
`EVA-ROADMAP.md`'deki bir sonraki fazı oku, hangi hesabı/API key'i
alman gerektiğini not al. Bu da ilerlemedir, kendine baskı yapma.

---

## 4. Güvenlik Katmanı Eklendikten Sonra

Eklenen büyük parça: **Had Safha Güvenlik** (`RequestSigner`,
`RequestSignatureGuard`, `DeviceIntegrityChecker`, Certificate Pinning).

> Sesli asistan üründen ÇIKARILDI. Kodu `_archive/voice-assistant/`
> altında duruyor; neden çıkarıldığı ve geri getirmek için neyin
> gerektiği oradaki README'de yazıyor.

**Bilmen gereken tek şey:** İki yeni ortam değişkeni eklendi —
`INTERNAL_SERVICE_MASTER_SECRET` (Gateway ve Python'da BİREBİR AYNI
olmalı, `docker-compose.yml` bunu otomatik senkronize ediyor) ve
`GATEWAY_CERT_PIN_1/2` (Android `local.properties`'te — yalnızca
production build'de gerekli, `./dev-up.sh` ile yerel test ederken boş
kalabilir).

**Güvenlik katmanının test sırası** (yorgun kafayla karıştırmamak için):
1. Önce `DeviceAttestationGuard` çalışsın (Play Integrity) — bu olmadan
   hiçbir şey ilerlemez.
2. Sonra cihaz kaydı (`/v1/devices/register`) — uygulama ilk açıldığında
   otomatik olur, `EvaApplication.onCreate()` loglarında
   "Cihaz kayıt durumu: Registered" görmelisin.
3. En son `RequestSignatureGuard` — kayıt olmadan bu her zaman başarısız
   olur, bu normal, panik yapma.

Certificate pinning'i **production'a çıkmadan bir gün önce** aktif et,
geliştirme sürecinde değil — yanlış pin değeri girersen kendi Gateway'ine
bile bağlanamazsın ve bunu debug etmek yorgun bir akşam için can sıkıcı
olur.

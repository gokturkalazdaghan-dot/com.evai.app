# Eva — Proje Dosya Yapısı

Bu paket, Eva projesinin mimari aşamalarında üretilen production-ready
kod tabanını içerir. Aşağıdaki dizin yapısı doğrudan ilgili proje
iskeletlerinize kopyalanabilir.

```
eva-project/
├── ios/EvaApp/                    # iOS Native (Swift / StoreKit 2 / SwiftUI-ready)
│   ├── Core/                      # AppAttestManager, SecureTokenStore, APIClient
│   ├── EdgeAI/                    # BatteryModel, OnDeviceRouteScorer (offline-first)
│   ├── Commerce/                  # StoreKit 2 abonelik yönetimi
│   └── Resources/*.lproj/         # 5 dilde Localizable.strings (en/tr/de/fr/es)
│
├── android/app/src/main/          # Android Native (Kotlin / Jetpack Compose)
│   ├── java/.../security/         # PlayIntegrityManager, SecureTokenStore
│   ├── java/.../commerce/         # Google Play Billing entegrasyonu
│   ├── java/.../ui/stations/      # Şarj istasyonu Compose bileşenleri
│   └── res/values*/strings.xml    # 5 dilde string kaynakları
│
├── backend/
│   ├── nestjs-gateway/src/
│   │   ├── common/guards/         # DeviceAttestationGuard
│   │   ├── common/services/       # App Attest / Play Integrity doğrulayıcılar
│   │   ├── cache/                 # Redis geohash cache servisi
│   │   └── stations/              # StationFilterService, StationsService, Controller
│   └── ai-services/price_saving_agent/
│       ├── agents/                # pricing_agent.py (CrewAI), tools/
│       ├── services/               # anonymizer.py, db_service.py, redis_publisher.py
│       ├── orchestrator.py         # Ajan koşu orkestrasyonu
│       └── main.py                 # FastAPI giriş noktası
│
└── database/
    └── schema.sql                 # PostGIS şeması (istasyonlar, soketler, tarifeler)
```

## Kurulum Notları

**iOS:** `ios/EvaApp/` içeriğini Xcode projenize sürükleyin. `Core/APIClient.swift`
içindeki `baseURL` değerini kendi Gateway adresinizle güncelleyin.

**Android:** `android/app/src/main/` içeriğini mevcut Gradle projenizin
üzerine kopyalayın. `PlayIntegrityManager` için `cloudProjectNumber`
parametresini Google Cloud Console'dan alacağınız proje numarasıyla
doldurun.

**Backend (NestJS):** `backend/nestjs-gateway/src/` içeriğini mevcut NestJS
projenizin `src/` dizinine kopyalayın. Gerekli paketler: `@nestjs/typeorm`,
`@nestjs-modules/ioredis`, `googleapis`, `cbor`. `.env` dosyanıza
`APPLE_APP_ATTEST_ROOT_CA_BASE64`, `GOOGLE_PLAY_INTEGRITY_SERVICE_ACCOUNT_KEY_PATH`
gibi değişkenleri eklemeyi unutmayın.

**Backend (Python):** `backend/ai-services/price_saving_agent/` dizininde
`pip install -r requirements.txt --break-system-packages` çalıştırın, bir
`.env` dosyası oluşturup `config.py`'deki tüm zorunlu alanları doldurun,
ardından `uvicorn main:app --reload` ile başlatın.

**Veritabanı:** `psql -U <user> -d <db> -f database/schema.sql` ile şemayı
kurun (PostGIS eklentisinin kurulu olduğu bir PostgreSQL 16+ instance
gerektirir).

## Tek Komutla Yerel Ortam (docker-compose)

```bash
cp .env.example .env          # değerleri doldurun (en az ANTHROPIC_API_KEY)
cp android/local.properties.example android/local.properties  # RevenueCat key
docker compose up -d --build
```

Bu komut şunları otomatik ayağa kaldırır: PostGIS'li Postgres (şema
otomatik yüklenir), Redis, NestJS Gateway (`localhost:3000`), Python Fiyat
Tasarruf Ajanı (`localhost:8000`). Durdurmak için `docker compose down`,
verileri de silmek için `docker compose down -v`.

## Mock CPO Aggregator (gerçek sözleşme tamamlanana kadar)

Fiyat Tasarruf Ajanı'nı gerçek bir CPO Aggregator olmadan test etmek için:

```bash
docker compose --profile mock up -d --build
```

Bu, normal `docker compose up`'ın başlatmadığı `mock-cpo-aggregator`
servisini de ayağa kaldırır ve `price-saving-agent`'ın varsayılan
`OCPI_AGGREGATOR_BASE_URL`'i otomatik olarak bu servise işaret eder. Gerçek
CPO Aggregator sözleşmeniz olduğunda `--profile mock` bayrağını
kullanmayın ve `.env`'de gerçek `OCPI_AGGREGATOR_BASE_URL` /
`OCPI_AGGREGATOR_API_KEY` değerlerini tanımlayın.

## CI/CD (GitHub Actions)

`.github/workflows/` altında beş iş akışı var:
- `gateway-ci.yml`, `price-agent-ci.yml`, `mobile-android-ci.yml` — her PR'da
  lint/test/build (path-filtering ile yalnızca ilgili bileşen değiştiğinde
  tetiklenir).
- `security-scan.yml` — haftalık + her PR'da bağımlılık/secret taraması.
- `release-android.yml` — yalnızca `android-v*.*.*` tag push'unda tetiklenir,
  `production-release` GitHub Environment'ının reviewer onayını bekler,
  Play Console'a **internal testing** track'ine draft olarak yükler
  (otomatik production yayını yapmaz — bkz. `fastlane/android/Fastfile`).

**Gerekli GitHub Secrets** (Settings → Secrets and variables → Actions):
```
ANDROID_KEYSTORE_BASE64            # release keystore'unuzun base64'ü
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
PLAY_CONSOLE_SERVICE_ACCOUNT_JSON  # Play Developer API için (RevenueCat'in
                                     # kendi Play entegrasyonundan AYRI —
                                     # bu, fastlane'in AAB yükleyebilmesi için)
REVENUECAT_PUBLIC_API_KEY          # release build'e gömülen public key
GOOGLE_CLOUD_PROJECT_NUMBER        # Play Integrity API için
```

`Settings → Environments` altında `production-release` adında bir
environment oluşturup **Required reviewers** ekleyin — bu olmadan
`release-android.yml` reviewer onayı beklemeden geçer.

## RevenueCat Kurulumu (Android — önce yayınlanacak platform)


1. RevenueCat Dashboard'da bir proje açın, Google Play uygulamanızı bağlayın
   (Play Console service account JSON'ı ile).
2. **Products** bölümünde Play Console'daki `eva_premium_subscription`
   ürününüzü (monthly-plan / yearly-plan base plan'larıyla) içe aktarın.
3. **Entitlements** bölümünde `premium` adında bir entitlement oluşturun,
   her iki paketi de buna bağlayın (`AppConfig.REVENUECAT_ENTITLEMENT_ID`
   ile birebir eşleşmeli).
4. **Offerings** bölümünde bu paketleri içeren bir offering oluşturup
   "current" olarak işaretleyin.
5. **Project Settings → API Keys → Google Play** üzerinden public API
   key'i alıp `android/local.properties`'e `REVENUECAT_PUBLIC_API_KEY`
   olarak yazın.
6. **Integrations → Webhooks** bölümünde Gateway'inizin
   `https://api.evaapp.com/v1/billing/webhooks/revenuecat/events`
   adresini ekleyin, bir "Authorization header value" (Bearer secret)
   belirleyin — bu değeri Gateway `.env`'inde
   `REVENUECAT_WEBHOOK_AUTH_SECRET` olarak tanımlayın (ikisi birebir aynı
   olmalı).

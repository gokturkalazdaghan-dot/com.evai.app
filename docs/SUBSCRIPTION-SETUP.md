# Abonelik Kurulumu — EVA Premium

Uygulamadaki paywall kodu hazır; aşağıdakiler **hesap tarafı** adımlardır.
Bunlar tamamlanana kadar paywall "Şu anda satın alınabilir bir paket
bulunmuyor" gösterir — bilerek: yapılandırılmamış bir üründe sahte fiyat
göstermek Play politikasına aykırıdır.

## Fiyatlandırma

| Plan | Fiyat (temel, USD) | Deneme |
|------|--------------------|--------|
| Aylık | 4,99 | 3 gün ücretsiz |
| Yıllık | 39,99 | 3 gün ücretsiz |

Yıllık plan, aylık plana göre **%33** tasarruf sağlar (39,99 / 59,88 →
ayda 3,33 USD). Bu oran ekranda **sabit yazılmaz**, Google'ın döndürdüğü
gerçek fiyatlardan hesaplanır — kur/ülke farkında yanlış rozet
basılmasın diye.

## 1. Google Play Console

**Monetize → Products → Subscriptions** altında iki abonelik:

| Alan | Aylık | Yıllık |
|------|-------|--------|
| Product ID | `eva_premium_monthly` | `eva_premium_annual` |
| Base plan ID | `monthly-autorenew` | `annual-autorenew` |
| Faturalandırma dönemi | 1 ay | 1 yıl |
| Yenileme | Otomatik | Otomatik |
| Temel fiyat | 4,99 USD | 39,99 USD |

Her base plan'a bir **offer** ekleyin:

- Offer ID: `trial-3d`
- Faz: **Free trial**, süre **3 gün**
- Uygunluk: *Yeni müşteri edinimi* (kullanıcı daha önce bu aboneliği
  denememişse)

> Deneme süresi uygulamada sabit yazılmaz; SDK ürünün `freePhase`
> alanından okur. Buradaki süreyi değiştirirseniz ekran kendiliğinden
> uyar. Offer tanımlı değilse buton "Aboneliği başlat" der ve ücretsiz
> deneme **vaat etmez**.

Diğer ülkelerin fiyatları Play'in kur dönüşümüyle otomatik oluşur;
istenirse ülke bazında elle ayarlanabilir. Uygulama hangi tutarı
gösterirse kullanıcıdan o çekilir.

## 2. RevenueCat

1. **Project → Apps → Google Play**: paket adı `com.eva.app`, Play
   Service Account JSON'unu yükleyin.
2. **Products**: yukarıdaki iki ürünü içe aktarın.
3. **Entitlements**: `premium` kimlikli bir entitlement açın, iki ürünü de
   ona bağlayın.
   → Kod bu kimliği `AppConfig.REVENUECAT_ENTITLEMENT_ID` ile okur;
   değiştirirseniz orayı da güncelleyin.
4. **Offerings**: `default` adında bir offering oluşturun ve
   **current** olarak işaretleyin. İçine iki paket:
   - `$rc_monthly` → `eva_premium_monthly`
   - `$rc_annual` → `eva_premium_annual`

   > Paket tipleri önemli: kod `PackageType.MONTHLY` ve
   > `PackageType.ANNUAL` arar. Özel kimlikli paketler ekranda
   > **gösterilmez** — beklenmedik bir paketi rastgele başlıkla basmak
   > kullanıcıyı yanıltır.

5. **API keys → Public app key** (`goog_` ile başlar) değerini alın.

## 3. Anahtarı uygulamaya verin

`android/local.properties` (sürüm kontrolüne **girmez**):

```properties
REVENUECAT_PUBLIC_API_KEY=goog_XXXXXXXXXXXXXXXXXXXX
```

Bu bir **public** anahtardır, APK'da bulunması beklenir. Buna karşılık
RevenueCat **secret** anahtarı ve Play Service Account JSON'u asla
uygulamaya konmaz — onlar sunucu tarafındadır.

## 4. Test

1. Play Console → **Setup → License testing** listesine test hesabınızı
   ekleyin (test satın alımları ücretsizdir ve deneme süresi dakikalara
   iner).
2. Uygulamayı **internal testing** kanalına yükleyin. Satın alma akışı
   yalnızca Play'den kurulan imzalı bir sürümde çalışır — `adb install`
   ile kurulan debug APK'da ürünler görünmez.
3. Doğrulanacaklar:
   - İki plan da yerel para biriminde görünüyor mu?
   - Yıllık planda tasarruf rozeti ve "ayda …" satırı doğru mu?
   - Buton "3 gün ücretsiz dene" diyor mu?
   - Satın alma sonrası `premium` entitlement aktif oluyor mu?
   - **Satın alımları geri yükle** çalışıyor mu? (Play bunu zorunlu tutar.)

## Deneme sonrası ve yeniden abonelik

3 günlük deneme bittiğinde Play otomatik olarak ücretlendirmeye geçer.
Kullanıcı iptal ederse erişim **dönem sonunda** kapanır — kalan süreyi
kullanabilir.

Süresi dolmuş bir kullanıcı **tekrar abone olduğunda erişim geri gelir**:
hak kaydı `EXPIRED` → `ACTIVE` olarak güncellenir, yeni bir kayıt
oluşturulmaz (`subscription_entitlements` üzerinde
`(subject_id, entitlement_key)` tekil kısıtı var). Doğrulanmış döngü:

```
deneme (ACTIVE)      → 200
deneme bitti (EXPIRED) → 402
tekrar abone (ACTIVE)  → 200
dönem sonu → yenileme  → 402 → 200
```

> Ücretsiz deneme **kullanıcı başına bir kez** verilir; Play Console'daki
> teklifin uygunluk ayarı "yeni müşteri edinimi" olduğu için yeniden
> abone olan biri denemeyi tekrar almaz, doğrudan ücretlendirilir.

## Sunucu tarafı

Gateway'de RevenueCat webhook'u ve abonelik tablosu hazır
(`revenuecat_subscriptions`, migration 001).

RevenueCat panelinde **Integrations → Webhooks**:

- URL: `https://<gateway-adresiniz>/v1/billing/webhooks/revenuecat/events`
- Authorization header: `Bearer <secret>`

Aynı secret gateway'e `REVENUECAT_WEBHOOK_AUTH_SECRET` olarak verilir ve
**en az 24 karakter** olmalıdır — kısa ya da tanımsızsa gateway bilerek
başlamaz (`revenuecat-webhook-auth.guard.ts`), çünkü korumasız bir ödeme
webhook'u herkesin abonelik uydurabileceği bir uç demektir.

Webhook tanımlanmazsa abonelik durumu yalnızca istemci tarafında bilinir;
sunucu premium kullanıcıyı tanıyamaz.

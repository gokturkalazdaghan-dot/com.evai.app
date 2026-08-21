# Ödeme Duvarı Mimarisi

## Neden sunucuda

Abonelik durumu daha önce yalnızca istemcide biliniyordu. Bu, kilitli
özelliklerin **gerçekten kilitli olmadığı** anlamına gelir: imzalı istek
gönderebilen biri premium uçları doğrudan çağırabilirdi. Kontrol artık
gateway'de.

## Akış

```
İstek
  │
  ├─ RequestSignatureGuard      → verifiedDeviceId (özne kimliği)
  │
  └─ SubscriptionGuard
        ├─ 1. Hak var mı?       → subscription_entitlements
        │      ACTIVE / IN_GRACE ve süresi geçmemiş → sınırsız geç
        │
        ├─ 2. Ücretsiz kota     → feature_usage_counters (günlük, UTC)
        │      atomik UPSERT, sınırın altındaysa artar → geç
        │
        └─ 3. Aksi halde        → HTTP 402 + PAYWALL gövdesi
```

**402, 403 değil.** 403 "bu kaynak sana kapalı" demektir ve istemciler
bunu genelde oturum hatası sayar. 402 (Payment Required) istemcinin
doğrudan paywall ekranını açmasını sağlar.

## Bir ucu kilitlemek

```ts
@Get('nearby/trends')
@UseGuards(SubscriptionGuard)
@RequiresEntitlement({ feature: 'price_trend', freeDailyQuota: 5 })
async getNearbyWithTrends(@Query() query, @Req() request) { … }
```

`freeDailyQuota: 0` özelliği tamamen kapatır. Sıfırdan büyük bir değer
kullanıcının neyin parasını ödeyeceğini görmesini sağlar — hiçbir şey
denemeden ödeme yapmasını beklemek dönüşümü de düşürür.

`feature` anahtarı **uç bazında ayrı** tutulur: "fiyat trendi" ile "AI
tahmini" tek bir sayacı paylaşsaydı, birini kullanan kullanıcı diğerini
kaybederdi.

### 402 gövdesi

```json
{
  "error": "PAYWALL",
  "message": "Bugünkü ücretsiz hakkın doldu. Premium ile sınırsız kullanabilirsin.",
  "feature": "price_trend",
  "requiredEntitlement": "premium",
  "quota": { "used": 5, "limit": 5, "remaining": 0 },
  "quotaResetsAt": "2026-08-21T00:00:00.000Z"
}
```

Başarılı yanıtlar da `quotaRemaining` taşır; kullanıcı duvara toslamadan
önce uyarılabilsin diye.

## Kimlik

Uygulamada hesap sistemi yok. Özne, imza doğrulamasından geçen **cihaz
kimliğidir**. İstemci bu kimliği RevenueCat'e `appUserID` olarak verir
(`EvaApplication.kt`), böylece webhook olayı doğru cihaza bağlanır.

> Bu bağlantı olmadan sistem **çalışmaz**: RevenueCat anonim bir kimlik
> üretir, webhook hangi cihaza ait olduğu bilinmeden gelir ve satın alma
> yapılsa bile sunucuda hak kaydı oluşmaz.

## Ödeme sağlayıcıları

`subscription_entitlements` tek bir sağlayıcıya bağlı değildir. İki kaynak
aynı satırı yazar; guard hangisinden geldiğini umursamaz.

| Yüzey | Sağlayıcı | Uç |
|-------|-----------|-----|
| Android / iOS | Play Billing / StoreKit (RevenueCat) | `/v1/billing/webhooks/revenuecat/events` |
| Web (ileride) | Stripe | `/v1/billing/webhooks/stripe/events` |

> **Mobilde Stripe kullanılmaz.** Google Play, uygulama içindeki dijital
> içeriğin Play Billing ile satılmasını zorunlu tutar; mobil premium'u
> Stripe ile açmak uygulamanın mağazadan kaldırılmasına yol açabilir.
> Stripe ucu, ileride eklenecek bir web yüzeyi (operatör paneli, kurumsal
> abonelik) içindir — orada serbesttir.

### Stripe kurulumu

Checkout oturumu oluşturulurken **`metadata.eva_subject_id`** doldurulmalıdır;
yoksa olay hangi cihaza yazılacağı bilinmediği için atlanır (rastgele bir
özneye yazmaktansa atlamak doğru).

`STRIPE_WEBHOOK_SECRET` tanımsızsa uç **kapalı kalır** — yapılandırılmamış
bir ödeme webhook'unu açık bırakmak, herkesin kendine abonelik yazabilmesi
demektir.

İmza doğrulaması ham gövde üzerinden yapılır (`main.ts`'te `rawBody: true`
ve `json({ verify })`); JSON yeniden serileştirilirse imza tutmaz.
Zaman toleransı 300 sn — eski ama geçerli imzalı bir gövde tekrar
gönderilip abonelik uzatılamasın diye.

## Durum eşlemesi

| Sağlayıcı durumu | Hak durumu | Erişim |
|------------------|------------|--------|
| active, trialing | `ACTIVE` | ✅ Var (deneme de erişim demektir) |
| past_due, billing issue, grace | `IN_GRACE` | ✅ `grace_until`'a kadar sürer |
| canceled, expired | `EXPIRED` | ❌ |
| refund / revoke | `REVOKED` | ❌ **Derhal** — süresi dolmamış olsa bile |

Süre kontrolü **kodda** yapılır, yalnızca `status` alanına bakılmaz:
abonelik webhook beklemeden de süresi dolarak biter.

## Doğrulanmış senaryolar

Ödeme duvarı (`test-paywall.js`):

1. Ücretsiz kullanıcı 5 istek geçer, 6.'da 402 alır ✅
2. Aktif hak → sınırsız ✅
3. Süresi geçmiş hak → 402 ✅
4. İade edilmiş hak (süresi dolmamış) → 402 ✅
5. Ödeme gecikmesi (`IN_GRACE`) → erişim sürer ✅

Stripe imzası (`test-stripe.js`):

| Senaryo | Sonuç |
|---------|-------|
| Geçerli imza | 200, hak `ACTIVE` |
| Yanlış secret | 401 |
| Eski imza (replay) | 401 |
| İmza yok | 401 |
| Gövde kurcalanmış | 401 |
| İade olayı | 200, hak `REVOKED` |

## Bakım

`feature_usage_counters` her kullanıcı, her özellik ve **her gün** için
bir satır üretir. Temizlenmezse tablo süresiz büyür (10 bin kullanıcı ×
3 özellik × 365 gün ≈ yılda 11 milyon satır).

`UsageCleanupService` her gece **03:00 UTC**'de 30 günden eski satırları
siler. Saat bilinçli: kota UTC gün başında (00:00) sıfırlanır, temizliği
o ana koymak sıfırlamayla yarışırdı.

> `@Cron` dekoratörleri `ScheduleModule.forRoot()` olmadan **sessizce**
> çalışmaz — kayıt `app.module.ts`'te.

30 gün saklanıyor çünkü dünün sayacı işlevsiz olsa da "bu kullanıcı
duvara ne sıklıkta tosluyor" sorusu ürün kararları için değerli.

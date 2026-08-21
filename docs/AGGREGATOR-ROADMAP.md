# Şarj Ağı Agregatörü ve Akıllı Harita Paneli — Yol Haritası

## Özet: neyin zaten var olduğu

İstediğin özelliğin önemli bir kısmı mevcut şemada karşılanıyor. Sıfırdan
kurulacak şey sandığından az; asıl risk kodda değil, **veri kaynağında**.

| İhtiyaç | Durum |
|---|---|
| Çok operatörlü model | ✅ `charging_network_operators` (+ `ocpi_party_id`, `api_base_url`, `cpo_source`) |
| Coğrafi sorgu | ✅ PostGIS `geom` + geohash trigger + `ST_DWithin` |
| Soket tipi / AC-DC / güç | ✅ `station_connectors` (`connector_type`, `power_kw`, `evse_id`) |
| Fiyat zaman serisi | ✅ `tariff_snapshots` (partition'lı, append-only) |
| **Fiyat trendi** | ✅ **Faz 1'de eklendi, çalışıyor** (`station_price_trend`) |
| **Doluluk durumu** | ✅ **Faz 1'de şema eklendi**, veri kaynağı bekliyor |
| **Tahmini boşalma** | ⚠️ Şema hazır, model yazılacak (Faz 4) |
| Gerçek CPO verisi | ❌ **Blokaj — aşağıya bakın** |

---

## Faz 0 — Veri kaynağı kararı (BLOKAJ, önce bu çözülmeli)

Bu, projenin tamamını belirleyen karardır ve teknik değil ticari/hukuki bir
karardır.

**ZES, Eşarj ve Trugo'nun herkese açık, dokümante edilmiş API'si yoktur.**
Üç seçenek var:

### A) OCPI sözleşmesi (önerilen, tek sürdürülebilir yol)
Operatörle ticari anlaşma → OCPI 2.2 `credentials` token'ı. Şema buna göre
zaten hazır: `cpo_source='OCPI'`, `ocpi_party_id`, `ocpi_country_code`.
Anlık doluluk **yalnızca** bu yolla güvenilir şekilde gelir.

- Artı: gerçek zamanlı, sözleşmeli, kırılmaz.
- Eksi: her operatörle ayrı görüşme, zaman alır.

### B) Açık veri (hızlı başlangıç)
[Open Charge Map](https://openchargemap.org/site/develop/api) Türkiye
istasyonlarını içerir, ücretsiz API anahtarı verir, lisansı izin verir.

- Artı: bugün başlanabilir, hukuki risk yok.
- Eksi: **anlık doluluk YOK**, sadece statik istasyon envanteri. Konum,
  soket tipi ve güç bilgisi için yeterli; "boş mu dolu mu" için değil.

### C) Scraping (önerilmez)
- Operatörlerin kullanım şartlarını ihlal edebilir.
- Sayfa değişince sessizce bozulur; bu projede "sessizce bozulma"nın ne
  demek olduğunu zaten gördük (ajan fiyat uydurmuştu).
- Anlık doluluk için bile güvenilir değil.

> **Tavsiyem:** B ile başla (envanteri doldur, harita ve fiyat trendi hemen
> çalışsın), paralelde A'yı yürüt. Doluluk özelliğini A gelene kadar
> **kapalı tut** — veri yokken yeşil/kırmızı pin göstermek kullanıcıyı
> yanıltır ve uygulamanın güvenilirliğini bitirir.

---

## Faz 1 — Şema ✅ TAMAMLANDI

`database/migrations/002_availability_and_price_trend.sql`

### Doluluk, sağlıktan ayrıldı

Mevcut `station_connectors.status` alanı **sağlık** durumudur
(OPERATIONAL/DEGRADED/OFFLINE). "Boş mu dolu mu" ayrı bir eksendir:
çalışan bir soket aynı anda dolu olabilir. İkisini tek alanda birleştirmek
geri dönüşü olmayan bilgi kaybıdır — bir soket "OFFLINE" ise bunun arıza mı
yoksa doluluk mu olduğunu bir daha ayırt edemezsiniz.

Bu yüzden OCPI 2.2 EVSE status modeli **ayrı** enum olarak eklendi:

```
connector_availability = AVAILABLE | CHARGING | RESERVED | BLOCKED
                       | INOPERATIVE | OUTOFORDER | UNKNOWN
```

Eklenen yapılar:

- `station_connectors.availability` + `availability_observed_at` + `availability_source`
- `connector_availability_events` — partition'lı geçmiş (tahmin modeli için)
- `station_availability_summary` — harita pin rengi için tek satır okuma
- `station_price_trend` — materialized view, **çalışıyor**

### Fiyat trendi yeni tablo gerektirmedi

`tariff_snapshots` zaten append-only zaman serisi. Trend = son iki gözlem
farkı. `%1`in altındaki oynamalar `STABLE` sayılır — gürültüye ok göstermek
yanlış sinyaldir.

---

## Faz 2 — Sağlayıcı adaptör katmanı

Python ajanında, her operatör için tek arayüz:

```python
class CpoAdapter(Protocol):
    cpo_code: str
    source: CpoSource

    async def fetch_stations(self, bbox) -> list[RawStation]: ...
    async def fetch_availability(self, station_refs) -> list[RawAvailability]: ...
    async def fetch_tariffs(self, station_refs) -> list[RawTariffQuote]: ...
```

Uygulamalar: `OpenChargeMapAdapter` (Faz 0-B), `OcpiAdapter` (Faz 0-A),
`MockAdapter` (mevcut `mock_cpo_aggregator`).

**Önemli:** `fetch_availability` desteklenmiyorsa adapter `NotSupported`
döndürmeli — boş liste DEĞİL. Boş liste "hepsi dolu" gibi yorumlanabilir;
`NotSupported` ise UI'ın doluluk rozetini hiç göstermemesini sağlar.

Bu katman `services/tariff_pipeline.py` ile aynı ilkeyi izler:
**deterministik, LLM yok.** Veri çekme ve normalizasyonda LLM'e yer yoktur.

---

## Faz 3 — Gateway endpoint'leri

Mevcut `/v1/stations/nearby` yanıtı genişletilir (geriye dönük uyumlu,
istemci `ignoreUnknownKeys` kullanıyor):

```jsonc
{
  "stationId": "...",
  "pricePerKwh": 8.41,
  "priceTrend": {           // YENİ
    "direction": "DOWN",    // UP | DOWN | STABLE | UNKNOWN
    "changePercent": -4.93,
    "observedAt": "..."
  },
  "availability": {         // YENİ — kaynak yoksa null
    "total": 4,
    "available": 2,
    "charging": 2,
    "observedAt": "...",
    "source": "OCPI",
    "estimatedFreeAt": null // Faz 4
  }
}
```

Yeni uç noktalar:

| Endpoint | Amaç |
|---|---|
| `GET /v1/stations/:id/price-history?days=7` | Detay kartındaki fiyat grafiği |
| `GET /v1/stations/:id/availability-history` | Doluluk örüntüsü (yoğun saatler) |
| `POST /v1/stations/:id/report-status` | Topluluk bildirimi (`COMMUNITY_VERIFIED`) |

> `availability` alanı veri yoksa **null** dönmeli, `{available: 0}` değil.
> Aradaki fark: "bilmiyoruz" ile "hepsi dolu" aynı şey değildir.

---

## Faz 4 — Tahmini boşalma saati

**Bu adım Faz 0-A olmadan yapılamaz.** Anlık durumdan boşalma saati tahmin
etmek uydurma olur.

Gerçekçi model, `connector_availability_events` biriktikten sonra:

1. Soket bazında tipik oturum süresi (medyan `CHARGING` süresi)
2. Haftanın günü + saat dilimine göre doluluk olasılığı
3. Tahmin = mevcut oturumun başlangıcı + medyan süre, **güven aralığıyla**

UI'da "18:40'ta boşalır" değil, **"~20 dk içinde boşalması bekleniyor"**
gösterilmeli. Kesin saat, sahip olmadığımız bir kesinlik iddiasıdır.

Yeterli veri yoksa (< 30 gözlem) tahmin **hiç gösterilmez**.

---

## Faz 5 — Harita ve UI

Altyapı hazır (`ui/map/StationMap.kt` — işaretçiler, otomatik çerçeveleme,
koyu tema). Eklenecekler:

- **Pin rengi:** 🟢 `available > 0` · 🔴 `available = 0` · ⚪ `availability = null`
  (gri = "bilinmiyor" — kırmızı GÖSTERİLMEZ)
- **Trend oku:** 📈 kırmızı / 📉 yeşil, `STABLE` ve `UNKNOWN`'da ok yok
- **Detay kartı:** doluluk oranı, tazelik etiketi ("3 dk önce"), AC/DC
  güçleri, fiyat grafiği

> Tazelik etiketi kritik: 10 dakika önceki "boş" bilgisiyle 3 saniye
> öncekini aynı güvende göstermek kullanıcıyı yanlış istasyona yollar.

---

## Nereden başlamalı

1. **Faz 0 kararı** — Open Charge Map anahtarı al (5 dk), OCPI görüşmelerini
   paralelde başlat.
2. **Faz 2**: `OpenChargeMapAdapter` yaz → gerçek Türkiye istasyonları
   veritabanına insin. Harita o an anlamlı hale gelir.
3. **Faz 3**: `priceTrend` alanını yanıta ekle → trend okları çalışır
   (veri zaten hazır).
4. Doluluk ve tahmin, OCPI sözleşmesi geldiğinde açılır.

Bu sıra, her adımda **çalışan ve doğrulanabilir** bir şey bırakır; doluluk
gibi veri kaynağına bağımlı özellikler en sona kalır.

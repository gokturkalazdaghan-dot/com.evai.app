# Araç Telemetrisi ve Canlı Panel

## Neden kullanıcı şarj yüzdesini elle girmiyor

Şarj seviyesi her yolculukta değişir; kullanıcıdan sürekli girmesini
beklemek hem zahmetli hem güvenilmezdi — ekranda gösterilen değer
saatler önce girilmiş olabiliyordu. Artık telemetri katmanı mevcut **en
iyi kaynağı kendisi seçiyor**.

## Kaynak sırası

| # | Kaynak | Gereksinim | Notlar |
|---|--------|-----------|--------|
| 1 | `ANDROID_AUTOMOTIVE` | Uygulama aracın kendi ekranında | En taze veri, ağ gerekmez. `CAR_ENERGY` ayrıcalıklı izin ister |
| 2 | `OBD_DONGLE` | ~20 $ BLE dongle | **Üretici hesabı gerekmez** — aracın CAN veri yolundan okur |
| 3 | `OEM_CLOUD` | Üretici hesabı (OAuth) | Marka desteği gerekir |
| 4 | `MANUAL` | — | Son çare; ekranda elle girildiği açıkça yazar |

Kaynaklar **birleştirilmez, seçilir**: iki kaynağın çelişen değerlerini
ortalamak hiçbirine ait olmayan bir sayı üretirdi.

## BLE topolojisi — neden Python ajanı BLE okumaz

BLE menzili ~10 metredir ve dongle araca takılıdır. Sunucudaki bir
servis ona **ulaşamaz**. Veriyi okuyabilecek tek şey aracın içindeki
cihazdır:

```
OBD dongle ──BLE──> Telefon ──HTTPS(imzalı)──> Gateway ──WebSocket──> Web paneli
```

Gateway yalnızca dağıtıcıdır. Yazma yolu HTTP'dir çünkü imza
doğrulamasından geçmelidir; okuma yolu (panel) tek yönlü WebSocket.

## Uyarı motoru

`BatteryAlertMonitor` — %50 ve %30 eşiklerinde Eva sesli uyarır.

- **Histerezis (5 puan):** batarya okuması %50 civarında 49–51 arası
  salınır (yokuş, klima, rejeneratif fren). Histerezis olmadan dakikada
  birkaç kez uyarı gelirdi.
- **Şarj olurken sessiz:** sorunu çözmekte olan birine sorunu haber
  vermek anlamsız. Şarj sonrası seviye yükselince eşikler yeniden kurulur.
- **Atlanan eşik yakılır:** uygulama arka plandayken %55'ten %28'e
  düşülmüşse yalnızca CRITICAL verilir, sonradan "%50'nin altındasın"
  denmez.
- **Bilinmeyen/bayat okuma uyarı üretmez.**

12 birim testi bu davranışları doğruluyor
(`BatteryAlertMonitorTest.kt`).

## Standart OBD-II'nin veremedikleri

Bu değerler **üreticiye özel PID** gerektirir ve okunmaz — panelde `—`
görünür:

| Değer | Durum |
|-------|-------|
| Paket voltajı (ör. 405 V) | ❌ Üreticiye özel |
| Menzil | ⚠️ Hesaplanır (SOC × kapasite × 6,2 km/kWh) |
| Lastik basıncı (TPMS) | ❌ Üreticiye özel |
| Batarya sıcaklığı | ❌ Üreticiye özel |
| SOC (%) | ✅ PID 0x5B, yedek 0x2F |
| 12V modül voltajı | ✅ PID 0x42 |
| Hız | ✅ PID 0x0D |

> Panelde 12V voltajı `12.4 V (12V)` diye etiketlenir. Onu düz "VOLTAGE"
> diye göstermek, kullanıcıyı paket voltajına baktığı sanısına düşürürdü.
>
> Görseldeki "TPMS OK" ve "Batt Temp 29°C" satırları bu yüzden yeşil
> onay olarak basılmıyor: veri gelmeden bir sistemi sağlıklı ilan etmek,
> kontrol edilmemiş bir şeyi kontrol edilmiş göstermek olurdu.

8 birim testi OBD ayrıştırmasını doğruluyor (`ObdProtocolTest.kt`) —
özellikle `A × 100 / 255` ölçeği: 100'e bölme hatası %39 yerine %100
gösterirdi.

## Panel kimlik doğrulaması

Panel bir tarayıcıdır ve cihazın imzalama anahtarına **sahip değildir**
(tarayıcıya konsa herkes cihaz taklidi yapabilirdi). Akış:

1. Mobil uygulama imzalı istekle `POST /v1/telemetry/panel-token` çağırır
2. 15 dakikalık belirteç alır
3. Panel `?subject=…&token=…` ile açılır ve WS aboneliğinde belirteci verir
4. Gateway belirtecin **o subjectId'ye ait olduğunu** doğrular

Yalnızca "belirteç geçerli mi" diye bakmak, geçerli belirteci olan
herkesin başkasının aracını izlemesine izin verirdi.

## Panelin çalıştırılması

```bash
npx http-server "web/telemetry-panel" -p 4173 -c-1
```

Ardından uygulamanın verdiği URL ile açılır:
`http://localhost:4173/?gateway=http://localhost:3000&subject=<id>&token=<token>`

## Doğrulanmış

Simülatörle (`telemetry-sim.js`) uçtan uca akış test edildi:

- Alım: `POST /v1/telemetry/ingest` → HTTP 202
- Panel canlı gösterdi: batarya %45, menzil 210 km, hız 74 km/s, km 14.325
- Paket voltajı gönderilmedi → panelde `—` + "üreticiye özel PID gerekli"
- 12V voltajı `12.4 V (12V)` olarak etiketlendi
- Veri yokken hiçbir alanda uydurma sayı yok

## Açık işler

- OEM bulut sağlayıcısı (Smartcar/Tesla) henüz bağlı değil; `vehicle_links`
  tablosu ve şifreli token alanları hazır (migration 006), OAuth akışı yok.
- Panelin `subject`/`token` değerlerini uygulamadan panele aktaran
  QR/derin bağlantı akışı yok — şu an elle URL veriliyor.
- `TELEMETRY_PANEL_ORIGIN` üretimde daraltılmalı; tanımsızsa CORS her
  kökene açık.

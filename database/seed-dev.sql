-- database/seed-dev.sql
--
-- YALNIZCA YEREL GELISTIRME icin ornek veri. Uretimde CALISTIRMAYIN.
--
-- Amac: /v1/stations/nearby ve /v1/voice/query yollarinin gercek PostGIS
-- verisiyle uctan uca test edilebilmesi. Voice Co-pilot ajani veriyi
-- UYDURMAZ -- prompt'a yalnizca buradaki dogrulanmis satirlar enjekte
-- edilir; tablo bossa ajan durustce "istasyon bulamadim" der.
--
-- Calistirma:
--   docker compose exec -T postgres psql -U postgres -d eva_dev < database/seed-dev.sql
--
-- Idempotent: tekrar tekrar calistirilabilir (ON CONFLICT / NOT EXISTS).

BEGIN;

-- ------------------------------------------------------------
-- CPO
-- ------------------------------------------------------------
INSERT INTO charging_network_operators
    (cpo_code, display_name, source_type, supports_realtime_pricing, is_active)
VALUES
    ('DEV_ACME', 'ACME Dev Sarj Agi', 'AGGREGATOR_API', TRUE, TRUE)
ON CONFLICT (cpo_code) DO NOTHING;

-- ------------------------------------------------------------
-- Istasyonlar (Istanbul merkez cevresi)
--
-- NOT: is_fast_charge GENERATED bir kolondur (max_power_kw >= 50), elle
-- yazilamaz. geom kanonik konum kaynagidir; lat/lon kolonlari TypeORM entity
-- uyumu icin ONUNLA TUTARLI tutulmalidir. NestJS tarafi ST_MakePoint(lon,lat)
-- ile lat/lon kolonlarini, Python tarafi ise dogrudan geom'u kullanir --
-- ikisi ayrisirsa iki servis FARKLI mesafeler hesaplar.
-- ------------------------------------------------------------
INSERT INTO charging_stations (
    cpo_id, external_ref, name, geom, lat, lon,
    geohash9, geohash7, geohash5, country_code, timezone_id,
    status, max_power_kw, connector_types, data_confidence_score
)
SELECT
    c.cpo_id, v.external_ref, v.name,
    ST_SetSRID(ST_MakePoint(v.lon, v.lat), 4326),
    v.lat, v.lon,
    v.geohash9, substring(v.geohash9 from 1 for 7), substring(v.geohash9 from 1 for 5),
    CASE WHEN v.external_ref LIKE 'DEV-SF-%' THEN 'US' ELSE 'TR' END,
    CASE WHEN v.external_ref LIKE 'DEV-SF-%' THEN 'America/Los_Angeles' ELSE 'Europe/Istanbul' END,
    'OPERATIONAL', v.max_power_kw, v.connector_types::connector_type[], 0.95
FROM (VALUES
    ('DEV-ST-001', 'Taksim Hizli Sarj',   41.0370, 28.9850, 'sxk9puc4h', 180.00, ARRAY['CCS2','TYPE2']),
    ('DEV-ST-002', 'Karakoy Sahil Sarj',  41.0255, 28.9744, 'sxk9pt8m2', 60.00,  ARRAY['CCS2','CHAdeMO']),
    ('DEV-ST-003', 'Besiktas Meydan Sarj',41.0422, 29.0075, 'sxk9pv1x7', 350.00, ARRAY['CCS2','TESLA_NACS']),
    -- Android uygulamasinin VARSAYILAN konumu San Francisco'dur
    -- (MainActivity.kt icinde bilincli bir placeholder). Uygulamayi
    -- kaynagi degistirmeden test edebilmek icin oraya da istasyon konuyor.
    -- CCS2 bilincli olarak eklendi: uygulamadaki test araci profili CCS2
    -- konnektoru kullaniyor ve Gateway, araca UYMAYAN istasyonlari eler.
    -- CCS2'siz veriyle dashboard hakli olarak bos gorunur.
    ('DEV-SF-001', 'Market St Supercharger', 37.7765, -122.4172, '9q8yyk8yu', 250.00, ARRAY['CCS1','CCS2','TESLA_NACS']),
    ('DEV-SF-002', 'Mission Bay Fast Charge',37.7706, -122.4103, '9q8yyj3kd', 150.00, ARRAY['CCS1','CCS2','CHAdeMO']),
    ('DEV-SF-003', 'Civic Center Garage',    37.7793, -122.4193, '9q8yykm7p', 50.00,  ARRAY['CCS1','CCS2','TYPE1']),
    -- Test cihazinin GERCEK konumu (Ipsala, Edirne). Uygulama artik
    -- FusedLocationProvider'dan gercek koordinati aldigi icin, uctan uca
    -- testin calisabilmesi adina buraya da istasyon konuluyor.
    ('DEV-TR-001', 'Ipsala Merkez Sarj',   40.9210, 26.3760, 'sx1x1x1x1', 120.00, ARRAY['CCS2','TYPE2']),
    ('DEV-TR-002', 'Ipsala Otogar Sarj',   40.9155, 26.3690, 'sx1x1x1x2', 60.00,  ARRAY['CCS2','CHAdeMO']),
    ('DEV-TR-003', 'Ipsala Sinir Hizli',   40.9250, 26.3800, 'sx1x1x1x3', 180.00, ARRAY['CCS2','TESLA_NACS'])
) AS v(external_ref, name, lat, lon, geohash9, max_power_kw, connector_types)
CROSS JOIN (SELECT cpo_id FROM charging_network_operators WHERE cpo_code = 'DEV_ACME') c
WHERE NOT EXISTS (
    SELECT 1 FROM charging_stations s WHERE s.external_ref = v.external_ref
);

-- ------------------------------------------------------------
-- Tarifeler — her istasyon icin tek bir guncel snapshot.
-- captured_at now() oldugu icin mevcut ay partition'ina duser.
-- ------------------------------------------------------------
INSERT INTO tariff_snapshots
    (station_id, price_per_kwh, currency, session_fee, is_dynamic_pricing, source_agent)
SELECT s.station_id, v.price,
       (CASE WHEN v.external_ref LIKE 'DEV-SF-%' THEN 'USD' ELSE 'TRY' END)::currency_code,
       0, FALSE, 'seed_dev'
FROM (VALUES
    ('DEV-ST-001', 8.4500),
    ('DEV-ST-002', 6.9000),
    ('DEV-ST-003', 11.2500),
    ('DEV-SF-001', 0.4800),
    ('DEV-SF-002', 0.3900),
    ('DEV-SF-003', 0.5600),
    ('DEV-TR-001', 9.4500),
    ('DEV-TR-002', 7.8000),
    ('DEV-TR-003', 11.9000)
) AS v(external_ref, price)
JOIN charging_stations s ON s.external_ref = v.external_ref
WHERE NOT EXISTS (
    SELECT 1 FROM tariff_snapshots t
    WHERE t.station_id = s.station_id AND t.source_agent = 'seed_dev'
);

-- ------------------------------------------------------------
-- Soketler
-- ------------------------------------------------------------
INSERT INTO station_connectors (station_id, connector_type, power_kw, status)
SELECT s.station_id, ct::connector_type, s.max_power_kw, 'OPERATIONAL'
FROM charging_stations s
CROSS JOIN LATERAL unnest(s.connector_types) AS ct
WHERE s.external_ref LIKE 'DEV-%'
  AND NOT EXISTS (
      SELECT 1 FROM station_connectors sc
      WHERE sc.station_id = s.station_id AND sc.connector_type = ct::connector_type
  );

COMMIT;

SELECT s.external_ref, s.name, s.max_power_kw, t.price_per_kwh, t.currency
FROM charging_stations s
LEFT JOIN LATERAL (
    SELECT price_per_kwh, currency FROM tariff_snapshots ts
    WHERE ts.station_id = s.station_id ORDER BY ts.captured_at DESC LIMIT 1
) t ON TRUE
WHERE s.external_ref LIKE 'DEV-%'
ORDER BY s.external_ref;

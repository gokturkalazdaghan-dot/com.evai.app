-- database/migrations/002_availability_and_price_trend.sql
--
-- "Tum Sarj Agi Operatorleri Agregatoru ve Akilli Harita Paneli" - Faz 1: sema.
--
-- TASARIM KARARI: DOLULUK, SAGLIKTAN AYRI BIR KAVRAMDIR
-- ------------------------------------------------------
-- Mevcut station_connectors.status alani `station_status` enum'unu kullanir
-- (OPERATIONAL / DEGRADED / OFFLINE / UNKNOWN / PLANNED). Bunlar istasyonun
-- SAGLIK durumudur: cihaz calisiyor mu, ariza var mi.
--
-- "Bos mu, dolu mu" ise AYRI bir eksendir. Calisan (OPERATIONAL) bir soket
-- ayni anda dolu (CHARGING) olabilir. Ikisini tek alanda birlestirmek
-- geri donusu olmayan bir bilgi kaybi yaratir: bir soket "OFFLINE" ise
-- bunun ariza mi yoksa dolu mu oldugunu bir daha ayirt edemezsiniz.
--
-- Bu yuzden OCPI 2.2'nin EVSE status modeli ayri bir enum olarak ekleniyor.
-- Boylece ZES/Esarj/Trugo gibi OCPI konusan operatorlerden gelen deger
-- CEVRILMEDEN saklanabilir.

BEGIN;

-- ------------------------------------------------------------
-- 1) Doluluk durumu (OCPI 2.2 EVSE status)
-- ------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'connector_availability') THEN
        CREATE TYPE connector_availability AS ENUM (
            'AVAILABLE',    -- bos, kullanilabilir
            'CHARGING',     -- su anda arac sarj oluyor
            'RESERVED',     -- rezerve edilmis
            'BLOCKED',      -- fiziksel olarak engellenmis (arac park etmis vb.)
            'INOPERATIVE',  -- gecici olarak devre disi
            'OUTOFORDER',   -- arizali
            'UNKNOWN'       -- veri kaynagi bilgi vermiyor
        );
    END IF;
END $$;

ALTER TABLE station_connectors
    ADD COLUMN IF NOT EXISTS availability connector_availability NOT NULL DEFAULT 'UNKNOWN',
    -- Gozlem zamani AYRI tutulur: 10 dakika onceki "AVAILABLE" ile 3 saniye
    -- oncekini ayni guvende gostermek kullaniciyi yaniltir. UI bu alana
    -- bakarak "5 dk once" gibi bir tazelik etiketi gosterebilir.
    ADD COLUMN IF NOT EXISTS availability_observed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS availability_source cpo_source;

CREATE INDEX IF NOT EXISTS idx_connectors_availability
    ON station_connectors (station_id, availability);

-- ------------------------------------------------------------
-- 2) Doluluk gecmisi
--
-- "Tahmini bosalma saati" ANCAK gecmis veriyle hesaplanabilir. Anlik
-- durumdan tahmin uretmek uydurma olur: bir soketin ne zaman bosalacagini
-- bilmek icin o soketin tipik oturum suresini ve gunun saatine gore
-- doluluk oruntusunu bilmek gerekir.
--
-- tariff_snapshots ile ayni desen: append-only, captured_at'e gore
-- partition'li.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS connector_availability_events (
    event_id       BIGINT GENERATED ALWAYS AS IDENTITY,
    connector_id   UUID NOT NULL REFERENCES station_connectors(connector_id) ON DELETE CASCADE,
    availability   connector_availability NOT NULL,
    source         cpo_source NOT NULL,
    observed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, observed_at)
) PARTITION BY RANGE (observed_at);

CREATE TABLE IF NOT EXISTS connector_availability_events_2026_08
    PARTITION OF connector_availability_events
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE TABLE IF NOT EXISTS connector_availability_events_2026_09
    PARTITION OF connector_availability_events
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE INDEX IF NOT EXISTS idx_availability_events_connector_time
    ON connector_availability_events (connector_id, observed_at DESC);

-- ------------------------------------------------------------
-- 3) Istasyon seviyesi doluluk ozeti
--
-- Harita pin rengi icin gereken tek sey: "kac soket bos". Bunu her harita
-- sorgusunda station_connectors uzerinde toplamak, yogun bolgelerde
-- (yuzlerce istasyon) gereksiz maliyet yaratir. Ozet tablo, ajan tarafindan
-- guncellenir ve tek satir okumayla cevap verir.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS station_availability_summary (
    station_id            UUID PRIMARY KEY REFERENCES charging_stations(station_id) ON DELETE CASCADE,
    total_connectors      SMALLINT NOT NULL DEFAULT 0,
    available_connectors  SMALLINT NOT NULL DEFAULT 0,
    charging_connectors   SMALLINT NOT NULL DEFAULT 0,
    unavailable_connectors SMALLINT NOT NULL DEFAULT 0,
    -- Veri ne kadar taze? UI "az once" / "10 dk once" ayrimini buradan yapar.
    observed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Kaynak guvenilirligi: OCPI dogrudan operatorden gelir (yuksek),
    -- COMMUNITY_VERIFIED kullanici bildirimidir (dusuk).
    source                cpo_source NOT NULL,
    CONSTRAINT chk_connector_counts CHECK (
        available_connectors + charging_connectors + unavailable_connectors <= total_connectors
    )
);

CREATE INDEX IF NOT EXISTS idx_availability_summary_free
    ON station_availability_summary (available_connectors)
    WHERE available_connectors > 0;

-- ------------------------------------------------------------
-- 4) Fiyat trendi
--
-- YENI TABLO GEREKMEZ: tariff_snapshots zaten append-only bir zaman
-- serisidir. Trend, iki gozlem arasindaki farktir. Materialized view,
-- her harita sorgusunda pencere fonksiyonu calistirmayi onler.
--
-- REFRESH: ajan kosusundan sonra `REFRESH MATERIALIZED VIEW CONCURRENTLY
-- station_price_trend;` cagrilir (CONCURRENTLY icin unique index sart).
-- ------------------------------------------------------------
DROP MATERIALIZED VIEW IF EXISTS station_price_trend;

CREATE MATERIALIZED VIEW station_price_trend AS
WITH ranked AS (
    SELECT
        station_id,
        price_per_kwh,
        currency,
        captured_at,
        ROW_NUMBER() OVER (PARTITION BY station_id ORDER BY captured_at DESC) AS rn
    FROM tariff_snapshots
    WHERE captured_at > now() - INTERVAL '7 days'
),
latest AS (
    SELECT station_id, price_per_kwh AS current_price, currency, captured_at AS current_at
    FROM ranked WHERE rn = 1
),
previous AS (
    SELECT station_id, price_per_kwh AS previous_price, captured_at AS previous_at
    FROM ranked WHERE rn = 2
)
SELECT
    l.station_id,
    l.current_price,
    l.currency,
    l.current_at,
    p.previous_price,
    p.previous_at,
    CASE
        WHEN p.previous_price IS NULL OR p.previous_price = 0 THEN NULL
        ELSE ROUND(((l.current_price - p.previous_price) / p.previous_price) * 100, 2)
    END AS change_percent,
    CASE
        WHEN p.previous_price IS NULL THEN 'UNKNOWN'
        -- %1'in altindaki oynamalar gurultudur; kullaniciya ok gostermek
        -- yanlis bir sinyal verir.
        WHEN ABS(l.current_price - p.previous_price) / NULLIF(p.previous_price, 0) < 0.01 THEN 'STABLE'
        WHEN l.current_price > p.previous_price THEN 'UP'
        ELSE 'DOWN'
    END AS trend
FROM latest l
LEFT JOIN previous p ON p.station_id = l.station_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_station_price_trend
    ON station_price_trend (station_id);

COMMIT;

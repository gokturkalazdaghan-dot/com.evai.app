-- database/schema.sql
-- ============================================================
-- Eva Platform — Core Database Schema
-- PostgreSQL 16+ / PostGIS 3.4+
-- Zero-PII: kullanıcı tablosu bilinçli olarak burada YOK.
-- Kimlik/oturum verisi ayrı bir "Identity Vault" mikroservisinde,
-- coğrafi/işlevsel veriden tamamen izole tutulur.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ------------------------------------------------------------
-- ENUM Tipleri
-- ------------------------------------------------------------

CREATE TYPE connector_type AS ENUM (
    'CCS1', 'CCS2', 'CHAdeMO', 'TYPE1', 'TYPE2',
    'TESLA_NACS', 'TESLA_DESTINATION', 'GBT_DC', 'GBT_AC'
);

CREATE TYPE station_status AS ENUM (
    'OPERATIONAL', 'DEGRADED', 'OFFLINE', 'UNKNOWN', 'PLANNED'
);

CREATE TYPE cpo_source AS ENUM (
    'OCPI', 'OCPP_PARTNER', 'AGGREGATOR_API', 'COMMUNITY_VERIFIED'
);

CREATE TYPE currency_code AS ENUM ('USD', 'EUR', 'GBP', 'TRY', 'CHF');

CREATE TYPE subscription_tier AS ENUM (
    'free', 'trialing', 'active', 'expired', 'gracePeriod', 'billingIssue', 'revoked'
);

CREATE TYPE agent_run_status AS ENUM ('pending', 'running', 'succeeded', 'failed');

-- ------------------------------------------------------------
-- 1) charging_network_operators (CPO'lar)
-- ------------------------------------------------------------

CREATE TABLE charging_network_operators (
    cpo_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cpo_code            VARCHAR(32) NOT NULL UNIQUE,      -- örn: 'IONITY', 'SHELL_RECHARGE'
    display_name        VARCHAR(128) NOT NULL,
    source_type         cpo_source NOT NULL,
    api_base_url        TEXT,
    ocpi_party_id       VARCHAR(8),
    ocpi_country_code   CHAR(2),
    supports_realtime_pricing BOOLEAN NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cpo_active ON charging_network_operators (is_active) WHERE is_active = TRUE;

-- ------------------------------------------------------------
-- 2) charging_stations — PostGIS mekansal çekirdek tablo
-- ------------------------------------------------------------

CREATE TABLE charging_stations (
    station_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cpo_id               UUID NOT NULL REFERENCES charging_network_operators(cpo_id) ON DELETE RESTRICT,
    external_ref         VARCHAR(128) NOT NULL,  -- CPO tarafındaki orijinal ID
    name                 VARCHAR(256) NOT NULL,

    -- Coğrafi konum: SRID 4326 (WGS84), global çapta doğru
    geom                 GEOMETRY(POINT, 4326) NOT NULL,

    -- Kolay okunabilirlik ve TypeORM entity uyumu için düz lat/lon kolonları
    -- da tutuluyor (geom ile birlikte, geom kanonik kaynak).
    lat                  DOUBLE PRECISION NOT NULL,
    lon                  DOUBLE PRECISION NOT NULL,

    -- Geohash: 9 karakter hassasiyet (~4.8m x 4.8m) — Redis cache anahtarlarıyla
    -- birebir örtüşecek şekilde üretiliyor. Immutable üretim, indekslenebilir.
    geohash9             VARCHAR(9) NOT NULL,
    geohash7             VARCHAR(7) NOT NULL,   -- ~150m x 150m — bölgesel agregasyon
    geohash5             VARCHAR(5) NOT NULL,   -- ~4.9km x 4.9km — şehir/bölge seviyesi

    country_code         CHAR(2) NOT NULL,
    admin_region         VARCHAR(128),           -- il/eyalet/kanton
    timezone_id          VARCHAR(64) NOT NULL,   -- örn: 'Europe/Istanbul'

    status               station_status NOT NULL DEFAULT 'UNKNOWN',
    max_power_kw         NUMERIC(6,2) NOT NULL CHECK (max_power_kw > 0),
    connector_types       connector_type[] NOT NULL,
    is_fast_charge        BOOLEAN NOT NULL GENERATED ALWAYS AS (max_power_kw >= 50) STORED,

    amenities            JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {"restroom":true,"cafe":true,...}
    operating_hours      JSONB,                                -- açık/kapalı zaman aralıkları

    last_status_check_at TIMESTAMPTZ,
    data_confidence_score NUMERIC(3,2) NOT NULL DEFAULT 0.50 CHECK (data_confidence_score BETWEEN 0 AND 1),

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_station_per_cpo UNIQUE (cpo_id, external_ref)
);

CREATE INDEX idx_stations_geom ON charging_stations USING GIST (geom);
CREATE INDEX idx_stations_geohash9 ON charging_stations (geohash9);
CREATE INDEX idx_stations_geohash7 ON charging_stations (geohash7);
CREATE INDEX idx_stations_geohash5 ON charging_stations (geohash5);
CREATE INDEX idx_stations_status ON charging_stations (status) WHERE status IN ('OPERATIONAL','DEGRADED');
CREATE INDEX idx_stations_connectors ON charging_stations USING GIN (connector_types);
CREATE INDEX idx_stations_country ON charging_stations (country_code);

-- ------------------------------------------------------------
-- 3) station_connectors — soket bazlı detay (bir istasyonda N soket)
-- ------------------------------------------------------------

CREATE TABLE station_connectors (
    connector_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id           UUID NOT NULL REFERENCES charging_stations(station_id) ON DELETE CASCADE,
    connector_type        connector_type NOT NULL,
    power_kw              NUMERIC(6,2) NOT NULL CHECK (power_kw > 0),
    status                station_status NOT NULL DEFAULT 'UNKNOWN',
    evse_id                VARCHAR(64),   -- OCPI EVSE tanımlayıcısı
    last_updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_connectors_station ON station_connectors (station_id);
CREATE INDEX idx_connectors_type_status ON station_connectors (connector_type, status);

-- ------------------------------------------------------------
-- 4) tariff_snapshots — Fiyat Tasarruf Ajanı'nın beslediği zaman serisi
--    (append-only; büyük veri analitiği ve anlık pencere hesapları için)
-- ------------------------------------------------------------

CREATE TABLE tariff_snapshots (
    snapshot_id           BIGINT GENERATED ALWAYS AS IDENTITY,
    station_id            UUID NOT NULL REFERENCES charging_stations(station_id) ON DELETE CASCADE,
    price_per_kwh          NUMERIC(8,4) NOT NULL CHECK (price_per_kwh >= 0),
    currency               currency_code NOT NULL,
    session_fee            NUMERIC(8,4) DEFAULT 0,
    is_dynamic_pricing      BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from              TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until             TIMESTAMPTZ,
    source_agent            VARCHAR(64) NOT NULL DEFAULT 'price_saving_agent',
    captured_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (snapshot_id, captured_at)
) PARTITION BY RANGE (captured_at);

-- İlk partition'lar (deployment script'i ileriki ayları otomatik ekleyecek
-- şekilde pg_partman ile genişletilebilir; burada ilk 2 ay manuel tanımlı).
CREATE TABLE tariff_snapshots_2026_08 PARTITION OF tariff_snapshots
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE TABLE tariff_snapshots_2026_09 PARTITION OF tariff_snapshots
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE INDEX idx_tariff_station_time ON tariff_snapshots (station_id, captured_at DESC);

-- ------------------------------------------------------------
-- 5) geohash_region_cache_meta — Redis ile senkron kalan "cache versiyon" tablosu
-- ------------------------------------------------------------

CREATE TABLE geohash_region_cache_meta (
    geohash5              VARCHAR(5) PRIMARY KEY,
    station_count          INTEGER NOT NULL DEFAULT 0,
    last_mutation_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    cache_version           BIGINT NOT NULL DEFAULT 1
);

-- ------------------------------------------------------------
-- 6) subscriptions — Sıfır-PII abonelik kaydı (StoreKit 2 / Play Billing)
-- ------------------------------------------------------------

CREATE TABLE subscriptions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Sıfır-PII: kullanıcı kimliği değil, tek yönlü cihaz attestation hash'i.
    device_attestation_hash VARCHAR(32) NOT NULL,
    original_transaction_id VARCHAR(128) NOT NULL,
    product_id              VARCHAR(128) NOT NULL,
    tier                     subscription_tier NOT NULL DEFAULT 'free',
    expiration_date          TIMESTAMPTZ,
    will_auto_renew          BOOLEAN NOT NULL DEFAULT FALSE,
    last_synced_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_subscription_device_transaction UNIQUE (device_attestation_hash, original_transaction_id)
);

CREATE INDEX idx_subscriptions_tier ON subscriptions (tier);

-- ------------------------------------------------------------
-- 7) device_public_keys - istek imzasi dogrulamasi icin cihaz anahtarlari
--    Sifir-PII: device_id istemcide uretilen rastgele bir UUID'dir
--    (donanim kimligine bagli DEGILDIR). public_key_base64 yalnizca
--    imza dogrulamasi icin gereken matematiksel bir degerdir.
-- ------------------------------------------------------------

CREATE TABLE device_public_keys (
    device_id          VARCHAR(64)  PRIMARY KEY,
    public_key_base64  TEXT         NOT NULL,
    attestation_hash   VARCHAR(32),
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    registered_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_public_keys_active
    ON device_public_keys (device_id)
    WHERE is_active;

-- ------------------------------------------------------------
-- 8) revenuecat_subscriptions - RevenueCat sunucu tarafi yansimasi
--    Yukaridaki `subscriptions` tablosu RevenueCat ONCESI tasarimdir
--    (device_attestation_hash + original_transaction_id) ve artik hicbir
--    entity tarafindan kullanilmaz. Yeni modelde anahtar alan
--    RevenueCat'in urettigi (zaten anonim) app_user_id'dir.
-- ------------------------------------------------------------

CREATE TABLE revenuecat_subscriptions (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    revenuecat_app_user_id  VARCHAR(128)  NOT NULL,
    original_app_user_id    VARCHAR(128),
    product_id              VARCHAR(128)  NOT NULL,
    entitlement_ids         TEXT[]        NOT NULL DEFAULT '{}',
    tier                    subscription_tier NOT NULL DEFAULT 'free',
    expiration_date         TIMESTAMPTZ,
    will_auto_renew         BOOLEAN       NOT NULL DEFAULT FALSE,
    environment             VARCHAR(16)   NOT NULL DEFAULT 'PRODUCTION',
    store                   VARCHAR(16)   NOT NULL DEFAULT 'PLAY_STORE',
    last_event_id           VARCHAR(64),
    last_synced_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_revenuecat_subscriptions_app_user_id
    ON revenuecat_subscriptions (revenuecat_app_user_id);

CREATE INDEX idx_revenuecat_subscriptions_tier
    ON revenuecat_subscriptions (tier);

-- ------------------------------------------------------------
-- 9) agent_run_logs — Fiyat Tasarruf Ajanı orchestration geçmişi
-- ------------------------------------------------------------

CREATE TABLE agent_run_logs (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    geohash5             VARCHAR(5) NOT NULL,
    status               agent_run_status NOT NULL DEFAULT 'pending',
    stations_processed   INTEGER,
    tariffs_updated      INTEGER,
    errors               TEXT[] NOT NULL DEFAULT '{}',
    duration_ms          INTEGER,
    started_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ
);

CREATE INDEX idx_agent_run_logs_geohash_status ON agent_run_logs (geohash5, status);
CREATE INDEX idx_agent_run_logs_started_at ON agent_run_logs (started_at DESC);

-- ------------------------------------------------------------
-- Trigger: istasyon INSERT/UPDATE'te geohash alanlarını otomatik türet
-- ve ilgili bölgenin cache_version'ını artır (Redis invalidation sinyali)
-- ------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_derive_geohash_and_bump_cache()
RETURNS TRIGGER AS $$
DECLARE
    v_geohash9 VARCHAR(9);
BEGIN
    v_geohash9 := ST_GeoHash(NEW.geom, 9);

    NEW.geohash9 := v_geohash9;
    NEW.geohash7 := LEFT(v_geohash9, 7);
    NEW.geohash5 := LEFT(v_geohash9, 5);
    NEW.lat := ST_Y(NEW.geom);
    NEW.lon := ST_X(NEW.geom);
    NEW.updated_at := now();

    INSERT INTO geohash_region_cache_meta (geohash5, station_count, last_mutation_at, cache_version)
    VALUES (NEW.geohash5, 1, now(), 1)
    ON CONFLICT (geohash5) DO UPDATE
        SET last_mutation_at = now(),
            cache_version = geohash_region_cache_meta.cache_version + 1;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stations_geohash
    BEFORE INSERT OR UPDATE OF geom ON charging_stations
    FOR EACH ROW
    EXECUTE FUNCTION fn_derive_geohash_and_bump_cache();

-- ------------------------------------------------------------
-- Trigger: subscriptions.last_synced_at otomatik güncelleme
-- ------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_touch_last_synced_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_synced_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_subscriptions_touch
    BEFORE UPDATE ON subscriptions
    FOR EACH ROW
    EXECUTE FUNCTION fn_touch_last_synced_at();

-- ------------------------------------------------------------
-- Örnek sorgu: kullanıcının 15km çevresindeki, uyumlu soketi olan
-- ve OPERATIONAL durumdaki istasyonlar (mesafe sıralı)
-- ------------------------------------------------------------

-- SELECT station_id, name,
--        ST_Distance(geom::geography, ST_MakePoint(:lon, :lat)::geography) AS distance_m
-- FROM charging_stations
-- WHERE status = 'OPERATIONAL'
--   AND connector_types && ARRAY['CCS2']::connector_type[]
--   AND ST_DWithin(geom::geography, ST_MakePoint(:lon, :lat)::geography, 15000)
-- ORDER BY geom <-> ST_MakePoint(:lon, :lat)::geography
-- LIMIT 20;

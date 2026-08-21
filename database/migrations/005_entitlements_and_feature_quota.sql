-- database/migrations/005_entitlements_and_feature_quota.sql
--
-- SUNUCU TARAFI ODEME DUVARI
-- ==========================
-- Bugune kadar abonelik durumu yalnizca ISTEMCIDE biliniyordu. Bu, kilitli
-- ozelliklerin gercekten kilitli olmadigi anlamina gelir: imzali bir istek
-- gonderebilen herkes premium uclara erisebilirdi. Bu migration, hak
-- sahipligini sunucuda tutar ve ucretsiz kullanim kotasini olcer.
--
-- SAGLAYICIDAN BAGIMSIZ TASARIM
-- -----------------------------
-- `subscription_entitlements` tek bir odeme saglayicisina bagli degildir.
-- Mobil satin alimlar Google Play / App Store uzerinden (RevenueCat ile)
-- gelir; ileride bir web yuzeyi eklenirse Stripe ayni tabloyu besler.
-- Guard hangi saglayicidan geldigini UMURSAMAZ, yalnizca hakka bakar.
--
-- KIMLIK
-- ------
-- Uygulamada hesap sistemi yok; ozne, imza dogrulamasindan gecen CIHAZ
-- kimligidir (bkz. RequestSignatureGuard). Istemci bu kimligi RevenueCat'e
-- app_user_id olarak verir, boylece webhook olayi dogru cihaza baglanir.

BEGIN;

-- ------------------------------------------------------------
-- Hak sahipligi
-- ------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'entitlement_status') THEN
        CREATE TYPE entitlement_status AS ENUM (
            'ACTIVE',      -- Hak gecerli
            'IN_GRACE',    -- Odeme basarisiz, saglayici yeniden deniyor
            'EXPIRED',     -- Sure doldu
            'REVOKED'      -- Iade/geri alim -- erisim DERHAL kesilir
        );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'entitlement_source') THEN
        CREATE TYPE entitlement_source AS ENUM (
            'PLAY_STORE',
            'APP_STORE',
            'STRIPE',
            'PROMOTIONAL'  -- Elle verilen erisim (destek, basin, test)
        );
    END IF;
END$$;

CREATE TABLE IF NOT EXISTS subscription_entitlements (
    entitlement_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Imza dogrulamasindan gecen cihaz kimligi.
    subject_id       varchar(128) NOT NULL,

    -- 'premium' gibi hak anahtari. Ileride 'pro' vb. eklenebilsin diye
    -- serbest metin; kod tarafinda sabitlerle sinirlanir.
    entitlement_key  varchar(64) NOT NULL,

    status           entitlement_status NOT NULL DEFAULT 'EXPIRED',
    source           entitlement_source NOT NULL,

    -- Saglayicidaki karsiligi (RevenueCat app_user_id, Stripe
    -- subscription id vb.) -- destek taleplerini izlemek icin.
    provider_ref     varchar(190),
    product_id       varchar(190),

    -- NULL = suresiz (promosyonel erisim). Aksi halde bu ana kadar gecerli.
    expires_at       timestamptz,

    -- Odeme basarisiz olduysa bu ana kadar erisim SURDURULUR. Kullaniciyi
    -- karti gecici reddedildi diye aninda kilitlemek kotu deneyimdir.
    grace_until      timestamptz,

    -- Ayni olayin tekrar islenmesini onler (webhook'lar en az bir kez
    -- teslim edilir, tekrarli gelebilir).
    last_event_id    varchar(190),

    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),

    -- Bir cihazin ayni hakki iki kez olamaz; webhook UPSERT bu kisita dayanir.
    CONSTRAINT uq_entitlement_subject_key UNIQUE (subject_id, entitlement_key)
);

-- Guard her istekte bu sorguyu calistirir: (subject_id, entitlement_key)
-- zaten unique index tarafindan karsilanir.
CREATE INDEX IF NOT EXISTS idx_entitlements_expiry
    ON subscription_entitlements (expires_at)
    WHERE status IN ('ACTIVE', 'IN_GRACE');

COMMENT ON TABLE subscription_entitlements IS
    'Sunucu tarafi hak sahipligi. Odeme saglayicisindan bagimsiz; webhook''lar besler.';

-- ------------------------------------------------------------
-- Ucretsiz kullanim kotasi
-- ------------------------------------------------------------
-- Kilitli ozellikler tamamen kapali DEGIL: kullanici gunde birkac kez
-- deneyebilmeli ki neyin parasini odedigini bilsin. Sayac gunluk tutulur.
CREATE TABLE IF NOT EXISTS feature_usage_counters (
    subject_id   varchar(128) NOT NULL,
    feature_key  varchar(64)  NOT NULL,

    -- UTC gun. Yerel gun kullanmak, saat dilimi degistiren bir kullaniciya
    -- kotayi iki kez verirdi.
    usage_date   date NOT NULL,

    used_count   integer NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (subject_id, feature_key, usage_date)
);

COMMENT ON TABLE feature_usage_counters IS
    'Ucretsiz kullanicilarin gunluk ozellik kullanimi. Gun bazinda sifirlanir.';

-- Eski sayaclar birikmesin: 30 gunden eskisi tarihsel olarak da gereksiz.
CREATE INDEX IF NOT EXISTS idx_feature_usage_date
    ON feature_usage_counters (usage_date);

COMMIT;

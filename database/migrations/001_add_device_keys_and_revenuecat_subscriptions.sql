-- database/migrations/001_add_device_keys_and_revenuecat_subscriptions.sql
--
-- AMAC
-- ----
-- Gateway'deki TypeORM entity modeli ile database/schema.sql arasindaki
-- uyumsuzluklari kapatir. Bu tablolar entity'lerde TANIMLI ama semada
-- HIC OLUSTURULMAMISTI; sonucu:
--   * DevicePublicKeyEntity  -> /v1/devices/register 500 verir
--   * RevenueCatSubscription -> RevenueCat webhook'u 500 verir
-- Cihaz kaydi olmadan RequestSignatureGuard hicbir istegi gecirmedigi icin
-- bu, /v1/stations/nearby ve /v1/voice/query dahil TUM korumali
-- endpoint'leri fiilen kullanilamaz yapiyordu.
--
-- KOLON ADLARI
-- ------------
-- app.module.ts icindeki SnakeNamingStrategy, camelCase entity
-- property'lerini snake_case kolonlara esler. Buradaki adlar TypeORM'un
-- kendi snakeCase() yardimcisiyla uretilmistir -- elle turetilmemistir
-- (ornegin publicKeyBase64 -> public_key_base64, "base_64" DEGIL).
--
-- CALISTIRMA (mevcut bir veritabani icin)
-- ---------------------------------------
--   docker compose exec -T postgres psql -U postgres -d eva_dev \
--     < database/migrations/001_add_device_keys_and_revenuecat_subscriptions.sql
--
-- Yeni kurulumlarda bu DDL zaten schema.sql icinde yer aldigi icin
-- migration'i ayrica calistirmaniza gerek yoktur; dosya idempotenttir.

BEGIN;

-- ------------------------------------------------------------
-- 1) device_public_keys
--    Sifir-PII: device_id, istemcide uretilen rastgele bir UUID'dir
--    (donanim kimligine bagli DEGILDIR). public_key_base64 yalnizca
--    imza dogrulamasi icin gereken matematiksel bir degerdir.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_public_keys (
    device_id          VARCHAR(64)  PRIMARY KEY,
    public_key_base64  TEXT         NOT NULL,
    attestation_hash   VARCHAR(32),
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    -- @CreateDateColumn / @UpdateDateColumn karsiliklari. TypeORM bu
    -- degerleri kendisi yazar; DEFAULT now() yalnizca dogrudan SQL
    -- INSERT'leri (ornegin testler) icin guvenlik agidir.
    registered_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Imza dogrulama yolu yalnizca AKTIF cihazlari sorgular.
CREATE INDEX IF NOT EXISTS idx_device_public_keys_active
    ON device_public_keys (device_id)
    WHERE is_active;

-- ------------------------------------------------------------
-- 2) subscription_tier enum'una 'billingIssue' eklenmesi
--    Entity'deki SubscriptionTier bu degeri iceriyor, DB enum'u
--    icermiyordu -> RevenueCat "BILLING_ISSUE" olayi INSERT'te patlardi.
-- ------------------------------------------------------------
ALTER TYPE subscription_tier ADD VALUE IF NOT EXISTS 'billingIssue' BEFORE 'revoked';

COMMIT;

-- ------------------------------------------------------------
-- 3) revenuecat_subscriptions
--    Eski `subscriptions` tablosu RevenueCat oncesi tasarimdir
--    (device_attestation_hash + original_transaction_id). Yeni modelde
--    anahtar alan RevenueCat'in urettigi app_user_id'dir. Eski tablo
--    BILINCLI olarak birakildi -- silmek ayri bir karardir.
--
--    NOT: Ayri bir ifade blogunda; yukaridaki ALTER TYPE ile eklenen
--    enum degeri ayni islem icinde KULLANILAMAZ (PostgreSQL kisiti).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS revenuecat_subscriptions (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    -- RevenueCat'in urettigi, zaten anonim/sifir-PII kullanici kimligi.
    revenuecat_app_user_id  VARCHAR(128)  NOT NULL,
    original_app_user_id    VARCHAR(128),
    product_id              VARCHAR(128)  NOT NULL,
    entitlement_ids         TEXT[]        NOT NULL DEFAULT '{}',
    tier                    subscription_tier NOT NULL DEFAULT 'free',
    expiration_date         TIMESTAMPTZ,
    will_auto_renew         BOOLEAN       NOT NULL DEFAULT FALSE,
    environment             VARCHAR(16)   NOT NULL DEFAULT 'PRODUCTION',
    store                   VARCHAR(16)   NOT NULL DEFAULT 'PLAY_STORE',
    -- Webhook idempotency: ayni olayin iki kez islenmesini engellemek icin.
    last_event_id           VARCHAR(64),
    last_synced_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Entity'deki @Index(['revenuecatAppUserId'], { unique: true }) karsiligi.
CREATE UNIQUE INDEX IF NOT EXISTS uq_revenuecat_subscriptions_app_user_id
    ON revenuecat_subscriptions (revenuecat_app_user_id);

CREATE INDEX IF NOT EXISTS idx_revenuecat_subscriptions_tier
    ON revenuecat_subscriptions (tier);

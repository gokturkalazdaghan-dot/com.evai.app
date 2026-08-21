-- database/migrations/006_vehicle_links.sql
--
-- ARAC HESABI BAGLANTISI
-- ======================
-- Kullanicinin sarj yuzdesini surekli elle girmesi gerekmemeli. Gercek
-- cozum, aracin ureticisinin bulut API'sinden okumaktir (Smartcar, Tesla
-- Fleet API vb.).
--
-- TOKEN'LAR NEDEN SUNUCUDA
-- ------------------------
-- Bu token'lar aracin kapisini acabilir, klimayi calistirabilir, konumunu
-- okuyabilir. APK'ya konsaydi, bir string dump ile cikarilan token
-- baskasinin aracinin kontrolu demek olurdu -- fiyat API anahtarindan
-- cok daha agir bir sonuc. Bu yuzden yalnizca sunucuda, sifreli durur.

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'vehicle_link_provider') THEN
        CREATE TYPE vehicle_link_provider AS ENUM (
            'SMARTCAR',   -- Cok markali birlesik API
            'TESLA'       -- Tesla Fleet API
        );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'vehicle_link_status') THEN
        CREATE TYPE vehicle_link_status AS ENUM (
            'ACTIVE',
            'NEEDS_REAUTH',  -- Refresh token gecersiz; kullanici yeniden izin vermeli
            'REVOKED'        -- Kullanici baglantiyi kaldirdi
        );
    END IF;
END$$;

CREATE TABLE IF NOT EXISTS vehicle_links (
    link_id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Imza dogrulamasindan gecen cihaz kimligi (hesap sistemi yok).
    subject_id         varchar(128) NOT NULL,

    provider           vehicle_link_provider NOT NULL,
    status             vehicle_link_status NOT NULL DEFAULT 'ACTIVE',

    -- Saglayicidaki arac kimligi.
    provider_vehicle_id varchar(190) NOT NULL,

    -- Kullaniciya gosterilecek etiket ("Tesla Model 3"). VIN ya da plaka
    -- SAKLANMAZ -- gercek dunya kimligi tutmuyoruz.
    vehicle_label      varchar(190),

    -- SIFRELI token'lar. Uygulama katmani AES-GCM ile sifreler; veritabani
    -- yedegi sizsa bile token'lar dogrudan kullanilamaz.
    access_token_enc   text NOT NULL,
    refresh_token_enc  text,
    access_expires_at  timestamptz,

    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),

    -- Bir cihaz basina bir arac. Coklu arac ileride gerekirse bu kisit
    -- kaldirilir; simdi tekil tutmak "hangi arac?" belirsizligini onler.
    CONSTRAINT uq_vehicle_link_subject UNIQUE (subject_id)
);

COMMENT ON TABLE vehicle_links IS
    'Kullanicinin uretici hesabi baglantisi. Token''lar sifreli saklanir.';

-- ------------------------------------------------------------
-- Son okuma onbellegi
-- ------------------------------------------------------------
-- Uretici API'leri hiz siniri uygular ve sik sorgu aracin 12V bataryasini
-- tuketebilir. Her istemci istegi icin araca gitmek yerine son okuma
-- burada tutulur.
CREATE TABLE IF NOT EXISTS vehicle_telemetry_snapshots (
    subject_id      varchar(128) PRIMARY KEY,

    -- NULL = BILINMIYOR. Sifir ya da son bilinen deger YAZILMAZ:
    -- bilinmeyen bir batarya seviyesini uydurmak, soforu bos bir yolda
    -- birakabilir.
    battery_percent smallint CHECK (battery_percent BETWEEN 0 AND 100),
    range_km        numeric(6, 1),
    is_charging     boolean,

    captured_at     timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE vehicle_telemetry_snapshots IS
    'Uretici bulutundan alinan son okuma. NULL degerler "bilinmiyor" demektir.';

COMMIT;

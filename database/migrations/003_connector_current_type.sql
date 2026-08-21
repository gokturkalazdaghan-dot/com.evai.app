-- database/migrations/003_connector_current_type.sql
--
-- Soketin AC mi DC mi oldugunu ACIKCA saklar.
--
-- NEDEN AYRI KOLON: connector_type'tan cikarmak GUVENILMEZ. "Type 2" hem
-- AC (22 kW ev/sokak sarji) hem DC (bazi hizli sarj cihazlarinda) olabilir;
-- GB/T icin de ayni sekilde iki varyant vardir. Surucunun aradigi bilgi
-- "hizli sarj olur mu" ise, bunu guce bakip tahmin etmek yerine kaynaktan
-- gelen degeri saklamak dogrudur.
--
-- Open Charge Map bu bilgiyi Connections[].CurrentType.Title alaninda
-- verir ("AC (Single-Phase)", "AC (Three-Phase)", "DC").

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'connector_current_type') THEN
        CREATE TYPE connector_current_type AS ENUM (
            'AC_SINGLE_PHASE',
            'AC_THREE_PHASE',
            'DC',
            'UNKNOWN'
        );
    END IF;
END $$;

ALTER TABLE station_connectors
    ADD COLUMN IF NOT EXISTS current_type connector_current_type NOT NULL DEFAULT 'UNKNOWN';

-- "Yakinimda DC hizli sarj" sorgusu icin.
CREATE INDEX IF NOT EXISTS idx_connectors_current_type
    ON station_connectors (station_id, current_type);

-- Ayni istasyon + soket tipi + EVSE kimligi tekrar eklenmesin. Agregator
-- ayni kaynagi tekrar tekrar cektiginde cift kayit olusmasini onler.
CREATE UNIQUE INDEX IF NOT EXISTS uq_connector_station_type_evse
    ON station_connectors (station_id, connector_type, COALESCE(evse_id, ''));

COMMIT;

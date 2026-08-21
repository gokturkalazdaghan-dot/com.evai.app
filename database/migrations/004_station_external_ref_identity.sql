-- database/migrations/004_station_external_ref_identity.sql
--
-- Istasyon kimligini external_ref'e baglar.
--
-- SORUN: mevcut kisit (cpo_id, external_ref) idi. Agregator kaynaginda bir
-- istasyonun ISLETMECISI degistiginde (OCM'de OperatorInfo guncellenmesi
-- siktir) ayni fiziksel istasyon IKINCI KEZ eklenirdi -- haritada ust uste
-- iki pin, iki farkli fiyat gecmisi.
--
-- COZUM: external_ref kaynak onekiyle uretilir ("OCM:<uuid>") ve global
-- olarak benzersizdir. Boylece isletmeci degisse bile istasyon ayni satir
-- olarak kalir, tariff_snapshots gecmisi korunur.

BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS uq_station_external_ref
    ON charging_stations (external_ref);

COMMIT;

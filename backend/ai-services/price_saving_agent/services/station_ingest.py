# backend/ai-services/price_saving_agent/services/station_ingest.py
"""
Kaynaklardan gelen istasyon envanterini veritabanina yazar.

TASARIM: UPSERT, SILME YOK
--------------------------
Kaynak bir istasyonu artik dondurmuyorsa o istasyon SILINMEZ. Sebebi:
kaynagin gecici bir kesintisi (rate limit, bolgesel bos yanit) tum
istasyonlarin silinmesine yol acardi -- ve tariff_snapshots ile
connector_availability_events bu istasyonlara FOREIGN KEY ile bagli oldugu
icin gecmis veri de kaskad silinirdi.

Bunun yerine kayitlar guncellenir; artik dogrulanmayan istasyonlar
`data_confidence_score` dusurulerek isaretlenebilir (ayri bir bakim isi).

LLM KULLANMAZ.
"""
import logging
from decimal import Decimal

from services.db_service import db_service
from services.openchargemap_adapter import RawStation

logger = logging.getLogger(__name__)

# Topluluk kaynakli veri operatorden dogrudan gelen veri kadar guvenilir
# degildir; bu skor UI'da "verileri kismen dogrulanmis" olarak gosterilir
# (bkz. StationDetailScreen.confidenceLabel).
COMMUNITY_CONFIDENCE = Decimal("0.65")


class IngestResult:
    def __init__(self) -> None:
        self.inserted_stations = 0
        self.updated_stations = 0
        self.inserted_connectors = 0
        self.skipped = 0
        self.errors: list[str] = []

    @property
    def total_stations(self) -> int:
        return self.inserted_stations + self.updated_stations

    def as_dict(self) -> dict:
        return {
            "inserted_stations": self.inserted_stations,
            "updated_stations": self.updated_stations,
            "inserted_connectors": self.inserted_connectors,
            "skipped": self.skipped,
            "errors": self.errors,
        }


async def ensure_operator(cpo_code: str, display_name: str, source: str) -> str:
    """Isletmeciyi bulur ya da olusturur; cpo_id doner."""
    query = """
        INSERT INTO charging_network_operators (cpo_code, display_name, source_type)
        VALUES ($1, $2, $3::cpo_source)
        ON CONFLICT (cpo_code) DO UPDATE SET display_name = EXCLUDED.display_name
        RETURNING cpo_id
    """
    async with db_service.acquire() as conn:
        return await conn.fetchval(query, cpo_code, display_name, source)


async def ingest_stations(
    stations: list[RawStation],
    cpo_code: str,
    source: str,
) -> IngestResult:
    result = IngestResult()

    if not stations:
        return result

    # Her istasyonun kendi isletmecisi var (OCM 'OperatorInfo'). Ayni
    # isletmeci tekrar tekrar sorgulanmasin diye kucuk bir onbellek.
    operator_cache: dict[str, str] = {}

    station_query = """
        INSERT INTO charging_stations (
            cpo_id, external_ref, name, geom, lat, lon,
            geohash9, geohash7, geohash5, country_code, timezone_id,
            status, max_power_kw, connector_types, data_confidence_score
        )
        VALUES (
            $1, $2, $3,
            ST_SetSRID(ST_MakePoint($5, $4), 4326),
            $4, $5,
            -- geohash alanlari trigger tarafindan geom'dan TURETILIR
            -- (bkz. fn_derive_geohash_and_bump_cache). Buradaki degerler
            -- yalnizca NOT NULL kisitini gecmek icin yer tutucudur ve
            -- kolon uzunluklarina (9/7/5) UYMAK ZORUNDADIR -- daha uzun
            -- bir yer tutucu StringDataRightTruncationError verir.
            '000000000', '0000000', '00000',
            $6, $7, 'OPERATIONAL', $8, $9::connector_type[], $10
        )
        ON CONFLICT (external_ref) DO UPDATE SET
            name = EXCLUDED.name,
            geom = EXCLUDED.geom,
            lat = EXCLUDED.lat,
            lon = EXCLUDED.lon,
            max_power_kw = EXCLUDED.max_power_kw,
            connector_types = EXCLUDED.connector_types,
            updated_at = now()
        RETURNING station_id, (xmax = 0) AS was_inserted
    """

    connector_query = """
        INSERT INTO station_connectors (
            station_id, connector_type, power_kw, status, current_type, evse_id
        )
        VALUES ($1, $2::connector_type, $3, 'UNKNOWN', $4::connector_current_type, $5)
        ON CONFLICT (station_id, connector_type, COALESCE(evse_id, '')) DO UPDATE SET
            power_kw = EXCLUDED.power_kw,
            current_type = EXCLUDED.current_type,
            last_updated_at = now()
        RETURNING (xmax = 0) AS was_inserted
    """

    for station in stations:
        try:
            if station.operator_name not in operator_cache:
                operator_cache[station.operator_name] = await ensure_operator(
                    cpo_code=f"{cpo_code}:{_slug(station.operator_name)}",
                    display_name=station.operator_name,
                    source=source,
                )
            cpo_id = operator_cache[station.operator_name]

            async with db_service.acquire() as conn:
                row = await conn.fetchrow(
                    station_query,
                    cpo_id,
                    station.external_ref,
                    station.name,
                    station.lat,
                    station.lon,
                    station.country_code,
                    _timezone_for(station.country_code),
                    station.max_power_kw,
                    station.connector_types,
                    COMMUNITY_CONFIDENCE,
                )

                station_id = row["station_id"]
                if row["was_inserted"]:
                    result.inserted_stations += 1
                else:
                    result.updated_stations += 1

                for connector in station.connectors:
                    inserted = await conn.fetchval(
                        connector_query,
                        station_id,
                        connector.connector_type,
                        connector.power_kw,
                        connector.current_type,
                        connector.evse_id,
                    )
                    if inserted:
                        result.inserted_connectors += 1

        except Exception as exc:
            result.skipped += 1
            message = f"{station.external_ref}: {type(exc).__name__}: {exc}"
            logger.warning("Istasyon yazilamadi -- %s", message)
            # Tek bir bozuk kayit tum ingest'i durdurmasin; hata listelenir.
            if len(result.errors) < 10:
                result.errors.append(message)

    logger.info("Ingest tamamlandi: %s", result.as_dict())
    return result


def _slug(value: str) -> str:
    """Isletmeci adindan cpo_code turetir (VARCHAR(32) sinirina uyar)."""
    cleaned = "".join(ch if ch.isalnum() else "_" for ch in value.upper())
    return cleaned.strip("_")[:24] or "UNKNOWN"


def _timezone_for(country_code: str) -> str:
    # Basit eslesme; cok ulkeli genisleme gerekirse koordinattan saat dilimi
    # cozen bir kutuphane (timezonefinder) eklenebilir.
    return {"TR": "Europe/Istanbul"}.get(country_code.upper(), "UTC")

# backend/ai-services/price_saving_agent/services/db_service.py
import logging
from contextlib import asynccontextmanager
from decimal import Decimal

import asyncpg

from config import settings
from models.tariff_schemas import ResolvedTariff

logger = logging.getLogger(__name__)


class DbService:
    def __init__(self) -> None:
        self._pool: asyncpg.Pool | None = None

    async def connect(self) -> None:
        if self._pool is not None:
            return
        try:
            self._pool = await asyncpg.create_pool(
                dsn=settings.postgres_dsn,
                min_size=2,
                max_size=10,
                command_timeout=10,
            )
            logger.info("PostgreSQL bağlantı havuzu kuruldu.")
        except Exception as exc:
            logger.error("PostgreSQL bağlantı havuzu kurulamadı.", exc_info=exc)
            raise

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    @asynccontextmanager
    async def acquire(self):
        if self._pool is None:
            raise RuntimeError("DbService.connect() çağrılmadan acquire() kullanılamaz.")
        async with self._pool.acquire() as conn:
            yield conn

    async def insert_tariff_snapshot(self, tariff: ResolvedTariff) -> None:
        query = """
            INSERT INTO tariff_snapshots (
                station_id, price_per_kwh, currency, session_fee,
                is_dynamic_pricing, source_agent, captured_at
            )
            VALUES ($1, $2, $3, $4, $5, $6, now())
        """
        async with self.acquire() as conn:
            await conn.execute(
                query,
                tariff.station_id,
                tariff.price_per_kwh,
                tariff.currency.value,
                tariff.session_fee,
                tariff.is_dynamic_pricing,
                tariff.source_agent,
            )

    async def refresh_price_trend(self) -> None:
        """
        station_price_trend materialized view'ini tazeler.

        NEDEN GEREKLI
        -------------
        MV kendiliginden guncellenmez. Yenilenmezse trend oklari, view'in
        OLUSTURULDUGU andaki fiyatlari gosterir -- yani kullaniciya bayat
        bir veriyi guncelmis gibi sunardik. Yeni tarife yazildikca
        cagrilmali.

        CONCURRENTLY: yenileme sirasinda okumalar bloke olmaz; kullanici
        istasyon listesi isterken beklemez. (Unique index sart, migration
        002'de tanimli.)

        Hata yutulur: trend gorsel bir zenginlik, tarife yazma isleminin
        basarisini gecersiz kilmamali.
        """
        try:
            async with self.acquire() as conn:
                await conn.execute(
                    "REFRESH MATERIALIZED VIEW CONCURRENTLY station_price_trend"
                )
        except Exception as exc:
            logger.warning("Fiyat trendi görünümü yenilenemedi: %s", exc)

    async def get_last_price_per_kwh(self, station_id: str) -> Decimal | None:
        """
        Bir istasyonun EN SON kaydedilmis kWh fiyati. Guven skoru (confidence
        score) bu degere gore hesaplanir: ani fiyat sicramalari supheli kabul
        edilir. Gecmis kayit yoksa None doner -- bu durumda karsilastirilacak
        bir taban olmadigi icin notr bir skor kullanilir.
        """
        query = """
            SELECT price_per_kwh
            FROM tariff_snapshots
            WHERE station_id = $1
            ORDER BY captured_at DESC
            LIMIT 1
        """
        async with self.acquire() as conn:
            row = await conn.fetchrow(query, station_id)
            return row["price_per_kwh"] if row else None

    async def get_stations_by_geohash5(self, geohash5: str, limit: int) -> list[dict]:
        query = """
            -- country_code para birimi dogrulamasi icin gerekli:
            -- ABD'deki bir istasyona TRY tarifesi yazilmasi olculdu.
            SELECT s.station_id, s.external_ref, s.country_code, cno.cpo_code
            FROM charging_stations s
            JOIN charging_network_operators cno ON cno.cpo_id = s.cpo_id
            WHERE s.geohash5 = $1
              AND s.status IN ('OPERATIONAL', 'DEGRADED')
              AND cno.supports_realtime_pricing = TRUE
            LIMIT $2
        """
        async with self.acquire() as conn:
            rows = await conn.fetch(query, geohash5, limit)
            return [dict(r) for r in rows]

    async def get_active_geohash5_regions(self, limit: int) -> list[str]:
        """Orchestrator'ın periyodik koşularda hangi bölgeleri işleyeceğine
        karar vermesi için istasyon içeren aktif bölgeleri döndürür."""
        query = """
            SELECT DISTINCT geohash5
            FROM charging_stations
            WHERE status IN ('OPERATIONAL', 'DEGRADED')
            LIMIT $1
        """
        async with self.acquire() as conn:
            rows = await conn.fetch(query, limit)
            return [r["geohash5"] for r in rows]

    async def get_nearby_stations_with_tariff(
        self,
        lat: float,
        lon: float,
        radius_meters: int,
        connector_types: list[str] | None,
        limit: int = 8,
    ) -> list[dict]:
        """
        Bir noktanın çevresindeki istasyonları, kesin mesafe ve son bilinen
        tarifeyle birlikte döndürür.

        ŞU AN ÇAĞRILMIYOR: tek kullanıcısı Voice Co-pilot'tu ve o üründen
        çıkarıldı (bkz. _archive/voice-assistant/). Sorgu doğru ve
        bağımsız olduğu için silinmedi; asistan geri geldiğinde ya da
        sunucu tarafı bir "yakındakiler" ucu gerektiğinde hazır.
        """
        connector_filter_sql = ""
        params: list = [lon, lat, radius_meters]

        if connector_types:
            connector_filter_sql = "AND s.connector_types && $4::connector_type[]"
            params.append(connector_types)

        params.append(limit)
        limit_param_index = len(params)

        query = f"""
            SELECT
                s.station_id,
                s.name,
                s.max_power_kw,
                s.connector_types,
                ST_Distance(s.geom::geography, ST_MakePoint($1, $2)::geography) AS distance_meters,
                t.price_per_kwh,
                t.currency
            FROM charging_stations s
            LEFT JOIN LATERAL (
                SELECT price_per_kwh, currency
                FROM tariff_snapshots ts
                WHERE ts.station_id = s.station_id
                ORDER BY ts.captured_at DESC
                LIMIT 1
            ) t ON TRUE
            WHERE s.status IN ('OPERATIONAL', 'DEGRADED')
              AND ST_DWithin(s.geom::geography, ST_MakePoint($1, $2)::geography, $3)
              {connector_filter_sql}
            ORDER BY s.geom <-> ST_MakePoint($1, $2)::geography
            LIMIT ${limit_param_index}
        """

        async with self.acquire() as conn:
            rows = await conn.fetch(query, *params)
            return [dict(r) for r in rows]


db_service = DbService()

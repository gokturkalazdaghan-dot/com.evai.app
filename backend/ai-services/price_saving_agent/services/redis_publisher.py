# backend/ai-services/price_saving_agent/services/redis_publisher.py
import logging

import redis as sync_redis
import redis.asyncio as aioredis

from config import settings
from models.tariff_schemas import ResolvedTariff

logger = logging.getLogger(__name__)


class RedisPublisher:
    def __init__(self) -> None:
        self._async_client: aioredis.Redis | None = None
        self._sync_client: sync_redis.Redis | None = None

    async def connect(self) -> None:
        try:
            self._async_client = aioredis.from_url(settings.redis_url, decode_responses=True)
            await self._async_client.ping()
            logger.info("Redis async bağlantısı kuruldu.")
        except Exception as exc:
            logger.error("Redis async bağlantısı kurulamadı.", exc_info=exc)
            raise

        try:
            self._sync_client = sync_redis.Redis.from_url(settings.redis_url, decode_responses=True)
            self._sync_client.ping()
        except Exception as exc:
            logger.error("Redis sync bağlantısı kurulamadı.", exc_info=exc)
            raise

    async def close(self) -> None:
        if self._async_client is not None:
            await self._async_client.close()

    def _live_tariff_key(self, station_id: str) -> str:
        return f"tariff:live:{station_id}"

    async def publish_live_tariff(self, tariff: ResolvedTariff) -> None:
        if self._async_client is None:
            raise RuntimeError("RedisPublisher.connect() çağrılmadı.")
        await self._async_client.set(
            self._live_tariff_key(tariff.station_id),
            tariff.model_dump_json(),
            ex=settings.tariff_cache_ttl_seconds,
        )

    def publish_live_tariff_sync(self, tariff: ResolvedTariff) -> None:
        if self._sync_client is None:
            raise RuntimeError("RedisPublisher.connect() çağrılmadı.")
        self._sync_client.set(
            self._live_tariff_key(tariff.station_id),
            tariff.model_dump_json(),
            ex=settings.tariff_cache_ttl_seconds,
        )


redis_publisher = RedisPublisher()

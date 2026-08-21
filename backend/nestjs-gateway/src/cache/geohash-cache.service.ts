// backend/nestjs-gateway/src/cache/geohash-cache.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRedis } from '@nestjs-modules/ioredis';
import Redis from 'ioredis';

export interface StationSummary {
  stationId: string;
  name: string;
  lat: number;
  lon: number;
  maxPowerKw: number;
  connectorTypes: string[];
  status: string;
}

@Injectable()
export class GeohashCacheService {
  private readonly logger = new Logger(GeohashCacheService.name);
  private readonly DEFAULT_TTL_SECONDS = 300;

  constructor(@InjectRedis() private readonly redis: Redis) {}

  private regionKey(geohash7: string): string {
    return `station:geo:${geohash7}`;
  }

  private detailKey(stationId: string): string {
    return `station:detail:${stationId}`;
  }

  private versionKey(geohash5: string): string {
    return `region:version:${geohash5}`;
  }

  private tariffKey(stationId: string): string {
    return `tariff:live:${stationId}`;
  }

  async getRegionStationIds(geohash7: string): Promise<string[] | null> {
    try {
      const ids = await this.redis.smembers(this.regionKey(geohash7));
      return ids.length > 0 ? ids : null;
    } catch (err) {
      this.logger.error(`Redis SMEMBERS başarısız: ${geohash7}`, err instanceof Error ? err.stack : String(err));
      return null;
    }
  }

  async setRegionStationIds(geohash7: string, stationIds: string[]): Promise<void> {
    if (stationIds.length === 0) return;
    const key = this.regionKey(geohash7);
    try {
      const pipeline = this.redis.pipeline();
      pipeline.del(key);
      pipeline.sadd(key, ...stationIds);
      pipeline.expire(key, this.DEFAULT_TTL_SECONDS);
      await pipeline.exec();
    } catch (err) {
      this.logger.error(`Redis bölge cache yazımı başarısız: ${geohash7}`, err instanceof Error ? err.stack : String(err));
    }
  }

  async getStationDetail(stationId: string): Promise<StationSummary | null> {
    try {
      const raw = await this.redis.get(this.detailKey(stationId));
      if (!raw) return null;
      return JSON.parse(raw) as StationSummary;
    } catch (err) {
      this.logger.error(`Redis GET başarısız: ${stationId}`, err instanceof Error ? err.stack : String(err));
      return null;
    }
  }

  async setStationDetail(station: StationSummary, ttlSeconds = 120): Promise<void> {
    try {
      await this.redis.set(
        this.detailKey(station.stationId),
        JSON.stringify(station),
        'EX',
        ttlSeconds,
      );
    } catch (err) {
      this.logger.error(`Redis SET başarısız: ${station.stationId}`, err instanceof Error ? err.stack : String(err));
    }
  }

  async getLiveTariff(stationId: string): Promise<Record<string, unknown> | null> {
    try {
      const raw = await this.redis.get(this.tariffKey(stationId));
      if (!raw) return null;
      return JSON.parse(raw) as Record<string, unknown>;
    } catch (err) {
      this.logger.error(`Redis tarife okuma başarısız: ${stationId}`, err instanceof Error ? err.stack : String(err));
      return null;
    }
  }

  /**
   * Postgres trigger'ının artırdığı cache_version ile Redis'teki versiyonu
   * karşılaştırır. Uyuşmazlık varsa bölge cache'i stale kabul edilir.
   */
  async isRegionStale(geohash5: string, dbCacheVersion: number): Promise<boolean> {
    try {
      const cached = await this.redis.get(this.versionKey(geohash5));
      if (cached === null) return true;
      return parseInt(cached, 10) !== dbCacheVersion;
    } catch (err) {
      this.logger.error(`Redis versiyon kontrolü başarısız: ${geohash5}`, err instanceof Error ? err.stack : String(err));
      return true;
    }
  }

  async updateRegionVersion(geohash5: string, dbCacheVersion: number): Promise<void> {
    try {
      await this.redis.set(this.versionKey(geohash5), dbCacheVersion.toString());
    } catch (err) {
      this.logger.error(`Redis versiyon güncelleme başarısız: ${geohash5}`, err instanceof Error ? err.stack : String(err));
    }
  }
}

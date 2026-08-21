// backend/nestjs-gateway/src/stations/stations.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { ChargingStationEntity } from './entities/station.entity';
import { GeohashCacheService, StationSummary } from '../cache/geohash-cache.service';
import { StationFilterService } from './station-filter.service';
import { PriceTrendService } from './price-trend.service';
import { NearbyStationsQueryDto } from './dto/nearby-stations-query.dto';
import { StationResponseDto } from './dto/station-response.dto';
import { encodeGeohash, truncateGeohash, precisionForRadiusMeters } from '../common/utils/geohash.util';

@Injectable()
export class StationsService {
  private readonly logger = new Logger(StationsService.name);

  /** Uretim ortaminda gelistirme verisi filtrelenir. */
  private readonly isProduction = process.env.NODE_ENV === 'production';

  constructor(
    @InjectRepository(ChargingStationEntity)
    private readonly stationRepo: Repository<ChargingStationEntity>,
    private readonly geohashCache: GeohashCacheService,
    private readonly stationFilter: StationFilterService,
    private readonly priceTrend: PriceTrendService,
  ) {}

  async findNearby(query: NearbyStationsQueryDto): Promise<StationResponseDto[]> {
    const geohash9 = encodeGeohash(query.lat, query.lon, 9);
    const precision = precisionForRadiusMeters(query.radiusMeters);
    const regionHash = truncateGeohash(geohash9, precision);

    const cachedIds = await this.geohashCache.getRegionStationIds(regionHash);

    let stationIds: string[];
    if (cachedIds && cachedIds.length > 0) {
      stationIds = cachedIds;
      this.logger.debug(`Cache hit: bölge=${regionHash}, ${cachedIds.length} istasyon`);
    } else {
      stationIds = await this.fetchAndCacheRegion(regionHash, query);
    }

    if (stationIds.length === 0) {
      return [];
    }

    const summaries = await this.hydrate(stationIds);
    // Tarife (Redis) ve trend (Postgres MV) birbirinden bagimsiz; paralel.
    const [tariffMap, trendMap] = await Promise.all([
      this.hydrateTariffs(summaries),
      this.priceTrend.getTrends(summaries.map((s) => s.stationId)),
    ]);
    return this.stationFilter.filterAndRank(summaries, query, tariffMap, trendMap);
  }

  /**
   * Her istasyon için Redis'teki tariff:live:{stationId} anahtarını okur
   * (Fiyat Tasarruf Ajanı tarafından yazılır). Bir istasyon için henüz
   * hiç tarife çekilmemişse Map'te o istasyon için kayıt OLMAZ — bu
   * bilinçli: StationFilterService bunu "fiyat bilinmiyor" olarak
   * yorumlayıp null döndürecek, asla 0 ya da ortalama bir değer UYDURMAYACAK.
   */
  private async hydrateTariffs(
    summaries: StationSummary[],
  ): Promise<Map<string, { pricePerKwh: number; currency: string }>> {
    const tariffMap = new Map<string, { pricePerKwh: number; currency: string }>();

    const results = await Promise.all(
      summaries.map(async (station) => {
        const tariff = await this.geohashCache.getLiveTariff(station.stationId);
        return { stationId: station.stationId, tariff };
      }),
    );

    for (const { stationId, tariff } of results) {
      if (!tariff) continue;

      const pricePerKwh = tariff['price_per_kwh'];
      const currency = tariff['currency'];

      if (typeof pricePerKwh === 'number' && typeof currency === 'string') {
        tariffMap.set(stationId, { pricePerKwh, currency });
      } else if (typeof pricePerKwh === 'string' && typeof currency === 'string') {
        // Decimal alanlar bazen string olarak serileşmiş olabilir
        // (pydantic Decimal -> JSON string davranışı) — güvenli şekilde
        // sayıya çeviriyoruz.
        const parsed = parseFloat(pricePerKwh);
        if (!isNaN(parsed)) {
          tariffMap.set(stationId, { pricePerKwh: parsed, currency });
        }
      }
    }

    // Redis'te canli fiyati OLMAYAN istasyonlar icin veritabanindaki son
    // snapshot'a duser.
    //
    // Neden gerekli: Fiyat Tasarruf Ajani tariff:live:* anahtarlarini
    // 60 SANIYE TTL ile yazar. Bu, "canli fiyat taze olmali" ilkesi acisindan
    // dogru; ama ajan her dakika kosmadiginda kullanici fiyati HIC goremiyor
    // ve "Fiyat bekleniyor" yaziyordu. Son bilinen fiyat, hic fiyat
    // gostermemekten iyidir -- yeter ki uydurulmus olmasin: buradaki deger
    // de gercekten olculmus, yalnizca daha eski bir gozlemdir.
    const missingIds = summaries
      .map((station) => station.stationId)
      .filter((stationId) => !tariffMap.has(stationId));

    if (missingIds.length > 0) {
      await this.hydrateLastKnownTariffs(missingIds, tariffMap);
    }

    return tariffMap;
  }

  /**
   * Verilen istasyonlar icin tariff_snapshots tablosundaki EN SON kaydi
   * okur. DISTINCT ON, istasyon basina tek satir dondurur.
   */
  private async hydrateLastKnownTariffs(
    stationIds: string[],
    tariffMap: Map<string, { pricePerKwh: number; currency: string }>,
  ): Promise<void> {
    try {
      const rows: Array<{
        station_id: string;
        price_per_kwh: string;
        currency: string;
      }> = await this.stationRepo.manager.query(
        `SELECT DISTINCT ON (station_id) station_id, price_per_kwh, currency
         FROM tariff_snapshots
         WHERE station_id = ANY($1::uuid[])
         ORDER BY station_id, captured_at DESC`,
        [stationIds],
      );

      for (const row of rows) {
        const parsed = parseFloat(row.price_per_kwh);
        if (!isNaN(parsed) && row.currency) {
          tariffMap.set(row.station_id, { pricePerKwh: parsed, currency: row.currency });
        }
      }

      this.logger.debug(
        `Canli fiyati olmayan ${stationIds.length} istasyondan ${rows.length} tanesi ` +
          `icin son bilinen fiyat kullanildi.`,
      );
    } catch (err) {
      // Fallback basarisiz olursa fiyat "bilinmiyor" kalir -- bu, hatali bir
      // deger gostermekten iyidir.
      this.logger.error(
        'Son bilinen tarife okunamadi; bu istasyonlar fiyatsiz donecek.',
        err instanceof Error ? err.stack : String(err),
      );
    }
  }

  private async fetchAndCacheRegion(
    regionHash: string,
    query: NearbyStationsQueryDto,
  ): Promise<string[]> {
    try {
      const rows = await this.stationRepo
        .createQueryBuilder('s')
        .select('s.station_id', 'stationId')
        .where('s.status IN (:...statuses)', { statuses: ['OPERATIONAL', 'DEGRADED'] })
        // GELISTIRME VERISI URETIMDE GORUNMEZ.
        //
        // seed-dev.sql, test icin UYDURMA istasyonlar ekliyor
        // (external_ref 'DEV-' onekli). Bunlar uretimde bir kullaniciya
        // gosterilirse, var olmayan bir istasyona yonlendirilmis olur --
        // gece yarisi bos bir otoparka. Veritabani yanlislikla
        // tohumlanmis olsa bile bu filtre onu kullaniciya ulastirmaz.
        .andWhere(
          this.isProduction ? "COALESCE(s.external_ref, '') NOT LIKE 'DEV-%'" : '1 = 1',
        )
        .andWhere(
          `ST_DWithin(
            ST_MakePoint(s.lon, s.lat)::geography,
            ST_MakePoint(:lon, :lat)::geography,
            :radius
          )`,
          { lon: query.lon, lat: query.lat, radius: query.radiusMeters },
        )
        .orderBy(`ST_MakePoint(s.lon, s.lat) <-> ST_MakePoint(:lonOrder, :latOrder)`)
        .setParameters({ lonOrder: query.lon, latOrder: query.lat })
        .limit(200)
        .getRawMany<{ stationId: string }>();

      const ids = rows.map((r) => r.stationId);
      await this.geohashCache.setRegionStationIds(regionHash, ids);
      return ids;
    } catch (err) {
      this.logger.error(
        `PostGIS bölge sorgusu başarısız: ${regionHash}`,
        err instanceof Error ? err.stack : String(err),
      );
      throw err;
    }
  }

  private async hydrate(stationIds: string[]): Promise<StationSummary[]> {
    const cachedResults = await Promise.all(
      stationIds.map((id) => this.geohashCache.getStationDetail(id)),
    );

    const missingIds = stationIds.filter((_, idx) => cachedResults[idx] === null);

    let dbFetched: ChargingStationEntity[] = [];
    if (missingIds.length > 0) {
      dbFetched = await this.stationRepo.find({
        where: missingIds.map((id) => ({ stationId: id })) as any,
      });

      await Promise.all(
        dbFetched.map((station) =>
          this.geohashCache.setStationDetail({
            stationId: station.stationId,
            name: station.name,
            lat: station.lat,
            lon: station.lon,
            maxPowerKw: Number(station.maxPowerKw),
            connectorTypes: station.connectorTypes,
            status: station.status,
          }),
        ),
      );
    }

    const fromCache = cachedResults.filter((r): r is StationSummary => r !== null);
    const fromDb: StationSummary[] = dbFetched.map((station) => ({
      stationId: station.stationId,
      name: station.name,
      lat: station.lat,
      lon: station.lon,
      maxPowerKw: Number(station.maxPowerKw),
      connectorTypes: station.connectorTypes,
      status: station.status,
    }));

    return [...fromCache, ...fromDb];
  }
}

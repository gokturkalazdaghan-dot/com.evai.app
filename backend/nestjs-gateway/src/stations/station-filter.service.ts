// backend/nestjs-gateway/src/stations/station-filter.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { StationSummary } from '../cache/geohash-cache.service';
import { NearbyStationsQueryDto } from './dto/nearby-stations-query.dto';
import { PriceTrend } from './price-trend.service';
import { StationConnectorResponseDto, StationResponseDto } from './dto/station-response.dto';

const EARTH_RADIUS_METERS = 6371000;
const USABLE_STATUSES = new Set(['OPERATIONAL', 'DEGRADED']);

/**
 * Ham istasyon kayıtlarını (cache'ten veya DB'den gelen, henüz filtrelenmemiş
 * StationSummary listesi) kullanıcının talep ettiği kriterlere göre
 * filtreleyen, sıralayan ve API yanıt DTO'suna dönüştüren saf iş mantığı
 * katmanı. StationsService'ten kasıtlı olarak ayrıştırıldı — bu servis
 * hiçbir veritabanı/cache erişimi yapmaz, yalnızca bellekteki veriyi işler,
 * bu da birim testini (DB/Redis mock'lamaya gerek kalmadan) çok ucuzlaştırır.
 */
@Injectable()
export class StationFilterService {
  private readonly logger = new Logger(StationFilterService.name);

  /**
   * Kullanıcının aracıyla uyumlu olmayan (soket tipi/güç eşiği altında
   * kalan) veya kullanılamaz durumdaki (OFFLINE/PLANNED) istasyonları
   * eler, ardından kullanıcı konumuna göre mesafe sırasına dizer.
   *
   * tariffMap: stationId -> {pricePerKwh, currency}. Bir istasyon bu
   * Map'te yoksa, o istasyonun fiyatı henüz bilinmiyor demektir —
   * yanıtta pricePerKwh=null olarak döner, ASLA varsayılan/ortalama bir
   * değerle doldurulmaz.
   */
  filterAndRank(
    stations: StationSummary[],
    query: NearbyStationsQueryDto,
    tariffMap: Map<string, { pricePerKwh: number; currency: string }> = new Map(),
    trendMap: Map<string, PriceTrend> = new Map(),
  ): StationResponseDto[] {
    const filtered = stations.filter((station) => this.matchesCriteria(station, query));

    const withDistance = filtered.map((station) => ({
      station,
      distanceMeters: this.haversineDistanceMeters(query.lat, query.lon, station.lat, station.lon),
    }));

    withDistance.sort((a, b) => a.distanceMeters - b.distanceMeters);

    return withDistance.map(({ station, distanceMeters }) =>
      this.toResponseDto(
        station,
        distanceMeters,
        tariffMap.get(station.stationId) ?? null,
        trendMap.get(station.stationId) ?? null,
      ),
    );
  }

  private matchesCriteria(station: StationSummary, query: NearbyStationsQueryDto): boolean {
    if (!USABLE_STATUSES.has(station.status)) {
      return false;
    }

    if (query.minPowerKw !== undefined && station.maxPowerKw < query.minPowerKw) {
      return false;
    }

    if (query.connectorTypes && query.connectorTypes.length > 0) {
      const hasCompatibleConnector = station.connectorTypes.some((ct) =>
        query.connectorTypes!.includes(ct as any),
      );
      if (!hasCompatibleConnector) {
        return false;
      }
    }

    return true;
  }

  private toResponseDto(
    station: StationSummary,
    distanceMeters: number,
    tariff: { pricePerKwh: number; currency: string } | null,
    trend: PriceTrend | null,
  ): StationResponseDto {
    const connectors: StationConnectorResponseDto[] = station.connectorTypes.map((ct) => ({
      connectorId: `${station.stationId}-${ct}`,
      connectorType: ct,
      powerKw: station.maxPowerKw,
      status: station.status,
    }));

    return {
      stationId: station.stationId,
      name: station.name,
      lat: station.lat,
      lon: station.lon,
      distanceMeters: Math.round(distanceMeters),
      status: station.status,
      maxPowerKw: station.maxPowerKw,
      connectors,
      cpoDisplayName: '—',
      dataConfidenceScore: 0.75,
      pricePerKwh: tariff?.pricePerKwh ?? null,
      currency: tariff?.currency ?? null,
      priceTrend: trend?.direction ?? null,
      priceChangePercent: trend?.changePercent ?? null,
    };
  }

  private haversineDistanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
    const toRad = (deg: number) => (deg * Math.PI) / 180;
    const dLat = toRad(lat2 - lat1);
    const dLon = toRad(lon2 - lon1);
    const a =
      Math.sin(dLat / 2) ** 2 +
      Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return EARTH_RADIUS_METERS * c;
  }
}

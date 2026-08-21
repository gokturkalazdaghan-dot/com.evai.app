// backend/nestjs-gateway/src/stations/price-trend.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';

/** View'in dondurdugu HAM yon degeri. */
export type RawPriceTrendDirection = 'UP' | 'DOWN' | 'STABLE' | 'UNKNOWN';

/**
 * Gozlemlenmis yon. 'UNKNOWN' bilincli olarak DISARIDA: bu servis
 * karsilastirilacak gecmis olcumu olmayan istasyonlari Map'e hic
 * koymaz, dolayisiyla bir PriceTrend nesnesi varsa yonu bellidir.
 * Invaryant tipte yazili -- yoksa istemciye 'UNKNOWN' sizabilirdi.
 */
export type PriceTrendDirection = Exclude<RawPriceTrendDirection, 'UNKNOWN'>;

export interface PriceTrend {
  direction: PriceTrendDirection;
  /** Yuzde degisim; view hesaplayamadiysa null. */
  changePercent: number | null;
}

/**
 * Tek seferde sorgulanacak en fazla istasyon. Yakin istasyon sorgusu
 * zaten sinirli sayida sonuc dondurur; bu, kotu bir cagriyla tum
 * tabloyu taramaya karsi ust sinir.
 */
const MAX_STATIONS_PER_QUERY = 200;

/**
 * Istasyonlarin fiyat trendini okur.
 *
 * KAYNAK: `station_price_trend` materialized view (migration 002).
 * View, son 7 gundeki iki olcumu karsilastirir ve %1'in altindaki
 * oynamalari GURULTU sayip STABLE der -- kullaniciya anlamsiz ok
 * gostermemek icin.
 *
 * View'i Fiyat Tasarruf Ajani her yeni tarife yazdiginda tazeler
 * (bkz. db_service.refresh_price_trend); burada yalnizca okunur.
 */
@Injectable()
export class PriceTrendService {
  private readonly logger = new Logger(PriceTrendService.name);

  constructor(@InjectDataSource() private readonly dataSource: DataSource) {}

  async getTrends(stationIds: string[]): Promise<Map<string, PriceTrend>> {
    const trends = new Map<string, PriceTrend>();
    if (stationIds.length === 0) return trends;

    const ids = stationIds.slice(0, MAX_STATIONS_PER_QUERY);

    try {
      const rows: Array<{
        station_id: string;
        trend: RawPriceTrendDirection;
        change_percent: string | null;
      }> = await this.dataSource.query(
        `SELECT station_id, trend, change_percent
           FROM station_price_trend
          WHERE station_id = ANY($1::uuid[])`,
        [ids],
      );

      for (const row of rows) {
        // Trend'i olmayan istasyon Map'e HIC girmez; cagiran taraf bunu
        // "bilinmiyor" olarak yorumlamali, "degismedi" olarak degil.
        if (row.trend === 'UNKNOWN') continue;

        trends.set(row.station_id, {
          direction: row.trend,
          // numeric -> string gelir; parseFloat NaN verirse null biraktir.
          changePercent: row.change_percent === null ? null : Number(row.change_percent),
        });
      }
    } catch (err) {
      // Trend gorsel bir zenginlik. Okunamazsa istasyon listesi yine de
      // donmeli -- fiyat ve mesafe asil bilgidir.
      this.logger.warn(
        `Fiyat trendi okunamadi: ${err instanceof Error ? err.message : String(err)}`,
      );
    }

    return trends;
  }
}

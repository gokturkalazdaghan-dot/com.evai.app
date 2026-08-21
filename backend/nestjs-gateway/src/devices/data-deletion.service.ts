// backend/nestjs-gateway/src/devices/data-deletion.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { InjectRedis } from '@nestjs-modules/ioredis';
import Redis from 'ioredis';
import { DataSource } from 'typeorm';

/** Silme isleminin dokumu; istemciye ve loga doner. */
export interface DeletionReport {
  deletedRows: Record<string, number>;
  deletedCacheKeys: number;
  /** Abonelik kaydi silinemedi mi? (bkz. asagidaki not) */
  subscriptionRetained: boolean;
}

/**
 * Bir cihaza ait TUM verinin silinmesi.
 *
 * NEDEN VAR
 * ---------
 * Gizlilik politikamiz kullaniciya "verilerini silebilirsin" diye soz
 * veriyor ve Google Play, veri toplayan uygulamalarda bunu ZORUNLU
 * tutuyor. Soz verilip yapilmayan bir ozellik, politikayi yalan haline
 * getirir.
 *
 * NE SILINIR
 * ----------
 * Cihaz kimligine bagli her sey: imzalama anahtari, hak kayitlari,
 * kullanim sayaclari, arac baglantisi ve telemetri.
 *
 * NE SILINMEZ -- VE NEDEN
 * -----------------------
 * Aktif bir abonelik kaydi HEMEN silinmez. Sebep hukuki: satis kaydi
 * muhasebe mevzuati geregi saklanmali (GDPR md. 17/3-b: yasal
 * yukumluluk, silme hakkinin istisnasidir). Ayrica kullanici parasini
 * odedigi hakki kaybetmemeli -- cihaz kimligini silip aboneligi
 * birakmak, kullaniciyi odedigi seye erisemez hale getirirdi.
 *
 * Bu durum kullaniciya ACIKCA bildirilir; sessizce veri saklamak
 * "sildim" demekten daha kotudur.
 */
@Injectable()
export class DataDeletionService {
  private readonly logger = new Logger(DataDeletionService.name);

  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    @InjectRedis() private readonly redis: Redis,
  ) {}

  async deleteEverythingFor(deviceId: string): Promise<DeletionReport> {
    if (!deviceId) {
      throw new Error('deviceId gerekli.');
    }

    const deletedRows: Record<string, number> = {};
    let subscriptionRetained = false;

    // Tek islem: yarim silme, kismen silinmis bir kullanici birakir --
    // hangi verinin gittigini kimse bilemez.
    await this.dataSource.transaction(async (manager) => {
      // Aktif abonelik var mi? Varsa kayit korunur (yukaridaki nota bak).
      const active: Array<{ count: string }> = await manager.query(
        `SELECT count(*)::text AS count
           FROM subscription_entitlements
          WHERE subject_id = $1
            AND status IN ('ACTIVE', 'IN_GRACE')
            AND (expires_at IS NULL OR expires_at > now())`,
        [deviceId],
      );
      subscriptionRetained = Number(active[0]?.count ?? 0) > 0;

      const tables: Array<[string, string]> = [
        ['feature_usage_counters', 'subject_id'],
        ['vehicle_telemetry_snapshots', 'subject_id'],
        ['vehicle_links', 'subject_id'],
        ['device_public_keys', 'device_id'],
      ];

      // Hak kayitlari yalnizca AKTIF abonelik yoksa silinir.
      if (!subscriptionRetained) {
        tables.push(['subscription_entitlements', 'subject_id']);
        tables.push(['revenuecat_subscriptions', 'revenuecat_app_user_id']);
      }

      for (const [table, column] of tables) {
        const result = await manager.query(
          `DELETE FROM ${table} WHERE ${column} = $1`,
          [deviceId],
        );
        // node-postgres DELETE icin etkilenen satiri rowCount'ta doner;
        // TypeORM query() surucu sonucunu oldugu gibi aktarir.
        deletedRows[table] = Array.isArray(result) ? result.length : (result?.rowCount ?? 0);
      }
    });

    const deletedCacheKeys = await this.clearCache(deviceId);

    this.logger.log(
      `Veri silme tamamlandi: device=${deviceId.slice(0, 8)}… ` +
        `satirlar=${JSON.stringify(deletedRows)} onbellek=${deletedCacheKeys} ` +
        `abonelikKorundu=${subscriptionRetained}`,
    );

    return { deletedRows, deletedCacheKeys, subscriptionRetained };
  }

  /**
   * Cihaza ait onbellek anahtarlarini siler.
   *
   * KEYS degil dogrudan silme: KEYS tum veritabanini tarar ve Redis'i
   * bloke eder. Anahtar desenleri bilindigi icin taramaya gerek yok.
   */
  private async clearCache(deviceId: string): Promise<number> {
    const keys = [`telemetry:latest:${deviceId}`];

    try {
      return await this.redis.del(...keys);
    } catch (err) {
      // Onbellek silinemese bile kalici veri gitti; TTL'ler zaten kisa
      // (telemetri 10 dakika). Islem basarisiz sayilmaz.
      this.logger.warn(
        `Onbellek temizlenemedi: ${err instanceof Error ? err.message : String(err)}`,
      );
      return 0;
    }
  }
}

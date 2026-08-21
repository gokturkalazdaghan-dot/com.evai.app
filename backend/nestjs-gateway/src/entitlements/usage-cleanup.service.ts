// backend/nestjs-gateway/src/entitlements/usage-cleanup.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { Cron, CronExpression } from '@nestjs/schedule';
import { InjectRepository } from '@nestjs/typeorm';
import { LessThan, Repository } from 'typeorm';

import { FeatureUsageCounterEntity } from './entitlement.entity';

/**
 * Kota sayaclari bu gunden eskiyse silinir.
 *
 * 30 gun: kota GUNLUK sifirlandigi icin dunun sayaci bile islevsizdir.
 * Yine de kisa bir gecmis tutuluyor -- "bu kullanici duvara ne siklikta
 * tosluyor" sorusu urun kararlari icin degerli. Daha uzunu, hicbir isi
 * olmayan satirlarin sonsuza kadar birikmesi demek.
 */
const RETENTION_DAYS = 30;

/**
 * Eski kullanim sayaclarini temizler.
 *
 * NEDEN GEREKLI: `feature_usage_counters` her kullanici, her ozellik ve
 * HER GUN icin bir satir uretir. Temizlenmezse tablo suresiz buyur;
 * 10 bin kullanici x 3 ozellik x 365 gun = yilda ~11 milyon satir.
 */
@Injectable()
export class UsageCleanupService {
  private readonly logger = new Logger(UsageCleanupService.name);

  constructor(
    @InjectRepository(FeatureUsageCounterEntity)
    private readonly usageRepo: Repository<FeatureUsageCounterEntity>,
  ) {}

  /**
   * Gecede bir calisir.
   *
   * Saat 03:00 UTC: kota UTC gun basinda (00:00) sifirlanir, temizligi
   * hemen o ana koymak sifirlama ile yarisirdi.
   */
  @Cron(CronExpression.EVERY_DAY_AT_3AM)
  async cleanupOldCounters(): Promise<void> {
    const cutoff = new Date();
    cutoff.setUTCDate(cutoff.getUTCDate() - RETENTION_DAYS);
    const cutoffDate = cutoff.toISOString().slice(0, 10);

    try {
      const result = await this.usageRepo.delete({
        usageDate: LessThan(cutoffDate),
      });

      if (result.affected && result.affected > 0) {
        this.logger.log(
          `${result.affected} eski kota sayaci silindi (${cutoffDate} oncesi).`,
        );
      }
    } catch (err) {
      // Temizlik basarisiz olsa da servis calismaya devam etmeli:
      // bu bir bakim isi, kritik yol degil.
      this.logger.error(
        `Kota sayaclari temizlenemedi: ${err instanceof Error ? err.message : String(err)}`,
      );
    }
  }
}

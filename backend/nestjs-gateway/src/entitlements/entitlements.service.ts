// backend/nestjs-gateway/src/entitlements/entitlements.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';

import {
  ENTITLEMENT_PREMIUM,
  EntitlementSource,
  EntitlementStatus,
  FeatureUsageCounterEntity,
  SubscriptionEntitlementEntity,
} from './entitlement.entity';

export interface EntitlementCheck {
  isEntitled: boolean;
  /** Neden hak sahibi: 'ACTIVE' | 'IN_GRACE' | null. */
  reason: EntitlementStatus | null;
  expiresAt: Date | null;
}

export interface QuotaState {
  used: number;
  limit: number;
  remaining: number;
}

/** Yazma icin gelen normallestirilmis hak. */
export interface EntitlementUpsert {
  subjectId: string;
  entitlementKey?: string;
  status: EntitlementStatus;
  source: EntitlementSource;
  providerRef?: string | null;
  productId?: string | null;
  expiresAt?: Date | null;
  graceUntil?: Date | null;
  eventId?: string | null;
}

@Injectable()
export class EntitlementsService {
  private readonly logger = new Logger(EntitlementsService.name);

  constructor(
    @InjectRepository(SubscriptionEntitlementEntity)
    private readonly entitlementRepo: Repository<SubscriptionEntitlementEntity>,
    @InjectRepository(FeatureUsageCounterEntity)
    private readonly usageRepo: Repository<FeatureUsageCounterEntity>,
  ) {}

  /**
   * Bir oznenin hakki var mi?
   *
   * ZAMAN KONTROLU KODDA, VERITABANINDA DEGIL: `status` alani webhook
   * geldiginde yazilir, ama abonelik webhook beklemeden de suresi dolarak
   * biter. Yalnizca status'e bakmak, suresi gecmis bir aboneligi gecerli
   * saymak olurdu.
   */
  async check(
    subjectId: string,
    entitlementKey: string = ENTITLEMENT_PREMIUM,
  ): Promise<EntitlementCheck> {
    if (!subjectId) {
      return { isEntitled: false, reason: null, expiresAt: null };
    }

    const row = await this.entitlementRepo.findOne({
      where: { subjectId, entitlementKey },
    });

    if (!row) {
      return { isEntitled: false, reason: null, expiresAt: null };
    }

    // Iade/geri alim: sure dolmamis olsa bile erisim DERHAL kesilir.
    if (row.status === EntitlementStatus.REVOKED) {
      return { isEntitled: false, reason: EntitlementStatus.REVOKED, expiresAt: row.expiresAt };
    }

    const now = Date.now();

    if (row.status === EntitlementStatus.ACTIVE) {
      // expiresAt null = suresiz (promosyonel).
      const stillValid = row.expiresAt === null || row.expiresAt.getTime() > now;
      return {
        isEntitled: stillValid,
        reason: stillValid ? EntitlementStatus.ACTIVE : EntitlementStatus.EXPIRED,
        expiresAt: row.expiresAt,
      };
    }

    if (row.status === EntitlementStatus.IN_GRACE) {
      // Odeme yeniden denenirken erisim surer -- kullaniciyi gecici bir
      // kart reddi yuzunden aninda kilitlemek kotu deneyimdir.
      const inGrace = row.graceUntil !== null && row.graceUntil.getTime() > now;
      return {
        isEntitled: inGrace,
        reason: inGrace ? EntitlementStatus.IN_GRACE : EntitlementStatus.EXPIRED,
        expiresAt: row.expiresAt,
      };
    }

    return { isEntitled: false, reason: EntitlementStatus.EXPIRED, expiresAt: row.expiresAt };
  }

  /**
   * Webhook'lardan gelen hakki yazar (saglayicidan bagimsiz).
   *
   * Webhook'lar EN AZ BIR KEZ teslim edilir: ayni olay tekrar gelebilir.
   * `eventId` ayni ise islem atlanir.
   */
  async upsert(input: EntitlementUpsert): Promise<void> {
    const entitlementKey = input.entitlementKey ?? ENTITLEMENT_PREMIUM;

    const existing = await this.entitlementRepo.findOne({
      where: { subjectId: input.subjectId, entitlementKey },
    });

    if (existing && input.eventId && existing.lastEventId === input.eventId) {
      this.logger.debug(`Tekrarlanan webhook olayi atlandi: ${input.eventId}`);
      return;
    }

    await this.entitlementRepo.save({
      ...(existing ?? {}),
      subjectId: input.subjectId,
      entitlementKey,
      status: input.status,
      source: input.source,
      providerRef: input.providerRef ?? null,
      productId: input.productId ?? null,
      expiresAt: input.expiresAt ?? null,
      graceUntil: input.graceUntil ?? null,
      lastEventId: input.eventId ?? null,
    });

    this.logger.log(
      `Hak guncellendi: subject=${input.subjectId.slice(0, 8)}… ` +
        `key=${entitlementKey} durum=${input.status} kaynak=${input.source}`,
    );
  }

  /**
   * Ucretsiz kotadan bir kullanim harcar.
   *
   * ATOMIK: sayac tek bir UPSERT ile artirilir. Once okuyup sonra yazmak,
   * es zamanli iki istekte kotanin iki kez harcanmasina (ya da hic
   * harcanmamasina) yol acardi.
   *
   * @returns kullanim izni verildiyse true.
   */
  async consumeQuota(
    subjectId: string,
    featureKey: string,
    limit: number,
  ): Promise<{ allowed: boolean; quota: QuotaState }> {
    if (limit <= 0) {
      return { allowed: false, quota: { used: 0, limit: 0, remaining: 0 } };
    }

    const usageDate = utcDateString(new Date());

    // ON CONFLICT ... WHERE: sayac yalnizca SINIRIN ALTINDAYSA artar.
    // Guncellenen satir donmezse kota dolmus demektir.
    const updated: Array<{ used_count: number }> = await this.usageRepo.query(
      `INSERT INTO feature_usage_counters (subject_id, feature_key, usage_date, used_count)
            VALUES ($1, $2, $3, 1)
       ON CONFLICT (subject_id, feature_key, usage_date)
       DO UPDATE SET used_count = feature_usage_counters.used_count + 1,
                     updated_at = now()
             WHERE feature_usage_counters.used_count < $4
         RETURNING used_count`,
      [subjectId, featureKey, usageDate, limit],
    );

    if (updated.length > 0) {
      const used = Number(updated[0].used_count);
      return { allowed: true, quota: { used, limit, remaining: Math.max(0, limit - used) } };
    }

    return { allowed: false, quota: { used: limit, limit, remaining: 0 } };
  }

  /** Kotayi HARCAMADAN durumu okur (ekranda "3/5 hakkın kaldı" icin). */
  async peekQuota(
    subjectId: string,
    featureKey: string,
    limit: number,
  ): Promise<QuotaState> {
    const row = await this.usageRepo.findOne({
      where: { subjectId, featureKey, usageDate: utcDateString(new Date()) },
    });
    const used = row?.usedCount ?? 0;
    return { used, limit, remaining: Math.max(0, limit - used) };
  }
}

/** UTC gunu 'YYYY-MM-DD' olarak. */
function utcDateString(date: Date): string {
  return date.toISOString().slice(0, 10);
}

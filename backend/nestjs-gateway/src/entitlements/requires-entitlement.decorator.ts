// backend/nestjs-gateway/src/entitlements/requires-entitlement.decorator.ts
import { SetMetadata } from '@nestjs/common';
import { ENTITLEMENT_PREMIUM } from './entitlement.entity';

export const ENTITLEMENT_METADATA_KEY = 'eva:requires-entitlement';

export interface EntitlementRequirement {
  /**
   * Kota sayacinin anahtari. Uc bazinda AYRI tutulur: "fiyat trendi"
   * ile "AI tahmini" ayri degerler ve tek bir ortak sayaci paylasmalari,
   * birini kullanan kullanicinin digerini kaybetmesi demek olurdu.
   */
  feature: string;

  /** Gerekli hak. Varsayilan: premium. */
  entitlement?: string;

  /**
   * Ucretsiz kullanicinin GUNLUK deneme hakki.
   *
   * 0 = ozellik tamamen kapali. Sifirdan buyuk bir deger, kullanicinin
   * neyin parasini odeyecegini gormesini saglar -- hicbir sey
   * denemeden odeme yapmasini beklemek satisi da dusurur.
   */
  freeDailyQuota?: number;
}

/**
 * Bir ucu odeme duvarinin arkasina alir.
 *
 * @example
 * ```ts
 * @Get('trends')
 * @RequiresEntitlement({ feature: 'price_trend', freeDailyQuota: 5 })
 * getTrends() { ... }
 * ```
 */
export const RequiresEntitlement = (requirement: EntitlementRequirement) =>
  SetMetadata(ENTITLEMENT_METADATA_KEY, {
    entitlement: ENTITLEMENT_PREMIUM,
    freeDailyQuota: 0,
    ...requirement,
  } satisfies EntitlementRequirement);

// backend/nestjs-gateway/src/entitlements/subscription.guard.ts
import {
  CanActivate,
  ExecutionContext,
  ForbiddenException,
  HttpException,
  HttpStatus,
  Injectable,
  Logger,
} from '@nestjs/common';
import { Reflector } from '@nestjs/core';

import { EntitlementsService, QuotaState } from './entitlements.service';
import {
  ENTITLEMENT_METADATA_KEY,
  EntitlementRequirement,
} from './requires-entitlement.decorator';

/** Imza guard'inin istege yazdigi dogrulanmis cihaz kimligi. */
interface SignedRequest {
  verifiedDeviceId?: string;
  /** Guard'in ardindan controller'in okuyabilmesi icin. */
  paywall?: {
    isEntitled: boolean;
    quota: QuotaState | null;
  };
}

/**
 * Istemciye donen odeme duvari govdesi.
 *
 * Duz bir 403 yeterli DEGIL: istemcinin neyin kilitli oldugunu, kotanin
 * ne zaman yenilenecegini ve hangi ekrani acacagini bilmesi gerekir.
 * Aksi halde uygulama yalnizca "yetkiniz yok" diyebilirdi.
 */
export interface PaywallResponseBody {
  error: 'PAYWALL';
  message: string;
  feature: string;
  requiredEntitlement: string;
  quota: QuotaState | null;
  /** Kotanin sifirlanacagi an (ISO-8601, UTC gun basi). */
  quotaResetsAt: string | null;
}

/**
 * Odeme duvari.
 *
 * SIRA: hak -> kota -> ret.
 *  1. Hak sahibi ise sinirsiz gecer.
 *  2. Degilse gunluk ucretsiz kotadan bir kullanim harcanir.
 *  3. Kota da bittiyse yapilandirilmis bir paywall govdesiyle 402 doner.
 *
 * NEDEN 402 (Payment Required):
 * 403, "bu kaynak sana kapali" demektir ve istemci bunu genelde oturum
 * hatasi olarak ele alir. 402 ise "odeme gerekli" anlamini tasir ve
 * istemcinin dogrudan paywall ekranini acmasini saglar.
 *
 * NEDEN CONTROLLER'DA DEGIL:
 * Kontrolun uclarin icine dagilmasi, yeni bir uc eklendiginde kontrolun
 * unutulmasi demektir -- yani ozellik sessizce bedava olur.
 */
@Injectable()
export class SubscriptionGuard implements CanActivate {
  private readonly logger = new Logger(SubscriptionGuard.name);

  constructor(
    private readonly reflector: Reflector,
    private readonly entitlements: EntitlementsService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const requirement = this.reflector.getAllAndOverride<EntitlementRequirement | undefined>(
      ENTITLEMENT_METADATA_KEY,
      [context.getHandler(), context.getClass()],
    );

    // Dekoratorsuz uclar bu guard'i ilgilendirmez.
    if (!requirement) return true;

    const request = context.switchToHttp().getRequest<SignedRequest>();
    const subjectId = request.verifiedDeviceId;

    if (!subjectId) {
      // Imza guard'i once calismali. Kimlik yoksa hak da kota da
      // olculemez; ozellige acmak, herkese bedava vermek olurdu.
      this.logger.warn('Odeme duvari kimliksiz istek gordu; imza guard sirasi yanlis olabilir.');
      throw new ForbiddenException('Bu işlem için cihaz doğrulaması gerekli.');
    }

    const entitlementKey = requirement.entitlement ?? 'premium';
    const check = await this.entitlements.check(subjectId, entitlementKey);

    if (check.isEntitled) {
      request.paywall = { isEntitled: true, quota: null };
      return true;
    }

    const freeQuota = requirement.freeDailyQuota ?? 0;
    const { allowed, quota } = await this.entitlements.consumeQuota(
      subjectId,
      requirement.feature,
      freeQuota,
    );

    if (allowed) {
      // Controller kalan hakki yanita ekleyebilsin diye tasiniyor.
      request.paywall = { isEntitled: false, quota };
      return true;
    }

    throw new HttpException(
      {
        error: 'PAYWALL',
        message:
          freeQuota > 0
            ? 'Bugünkü ücretsiz hakkın doldu. Premium ile sınırsız kullanabilirsin.'
            : 'Bu özellik Premium aboneliğe dahildir.',
        feature: requirement.feature,
        requiredEntitlement: entitlementKey,
        quota: freeQuota > 0 ? quota : null,
        quotaResetsAt: freeQuota > 0 ? nextUtcMidnight().toISOString() : null,
      } satisfies PaywallResponseBody,
      HttpStatus.PAYMENT_REQUIRED,
    );
  }
}

/** Kota UTC gun basinda sifirlanir (bkz. migration 005). */
function nextUtcMidnight(): Date {
  const now = new Date();
  return new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1, 0, 0, 0, 0),
  );
}

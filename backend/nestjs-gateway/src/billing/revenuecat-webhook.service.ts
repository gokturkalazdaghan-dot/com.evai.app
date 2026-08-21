// backend/nestjs-gateway/src/billing/revenuecat-webhook.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';

import { EntitlementsService } from '../entitlements/entitlements.service';
import {
  EntitlementSource,
  EntitlementStatus,
} from '../entitlements/entitlement.entity';
import {
  RevenueCatEventType,
  RevenueCatWebhookEvent,
} from './dto/revenuecat-webhook-event.dto';
import { RevenueCatSubscriptionEntity, SubscriptionTier } from './entities/subscription.entity';

@Injectable()
export class RevenueCatWebhookService {
  private readonly logger = new Logger(RevenueCatWebhookService.name);

  constructor(
    @InjectRepository(RevenueCatSubscriptionEntity)
    private readonly subscriptionRepo: Repository<RevenueCatSubscriptionEntity>,
    private readonly entitlements: EntitlementsService,
  ) {}

  async processEvent(event: RevenueCatWebhookEvent): Promise<void> {
    // TEST event'i RevenueCat Dashboard'daki "Send Test Event" butonuyla
    // gönderilir — gerçek bir abonelik değişikliği temsil etmez, yalnızca
    // endpoint'in erişilebilir ve 200 döndürdüğünü doğrulamak içindir.
    if (event.type === RevenueCatEventType.TEST) {
      this.logger.log('RevenueCat TEST event alındı — işlem yapılmadan onaylandı.');
      return;
    }

    // SUBSCRIBER_ALIAS, iki app_user_id'nin RevenueCat tarafında
    // birleştirildiğini bildirir (örn. anonim kullanıcı sonradan giriş
    // yaptı). Bu event'te abonelik durumu bilgisi taşınmaz, bu yüzden
    // tier güncellemesi yapmadan sadece logluyoruz.
    if (event.type === RevenueCatEventType.SUBSCRIBER_ALIAS) {
      this.logger.log(
        `RevenueCat kullanıcı takma adı birleştirme: ${event.original_app_user_id} -> ${event.app_user_id}`,
      );
      return;
    }

    const tier = this.mapEventToTier(event);

    try {
      await this.subscriptionRepo.upsert(
        {
          revenuecatAppUserId: event.app_user_id,
          originalAppUserId: event.original_app_user_id ?? null,
          productId: event.product_id,
          entitlementIds: event.entitlement_ids ?? [],
          tier,
          expirationDate: event.expiration_at_ms ? new Date(event.expiration_at_ms) : null,
          willAutoRenew: this.deriveWillAutoRenew(event, tier),
          environment: event.environment,
          store: event.store,
          lastEventId: event.id,
        },
        ['revenuecatAppUserId'],
      );

      // ORTAK HAK TABLOSU: guard bu tabloya bakar (bkz. migration 005).
      // revenuecat_subscriptions kaydi RevenueCat'e ozgu alanlari (store,
      // environment, auto-renew) saklamaya devam eder; hak kontrolu ise
      // saglayicidan bagimsiz olmali.
      //
      // app_user_id, istemcinin RevenueCat'i yapilandirirken verdigi
      // imzali CIHAZ kimligidir -- bu sayede olay dogru cihaza baglanir.
      await this.entitlements.upsert({
        subjectId: event.app_user_id,
        status: mapTierToEntitlementStatus(tier),
        source: event.store === 'APP_STORE' ? EntitlementSource.APP_STORE : EntitlementSource.PLAY_STORE,
        providerRef: event.app_user_id,
        productId: event.product_id,
        expiresAt: event.expiration_at_ms ? new Date(event.expiration_at_ms) : null,
        // Odeme sorununda erisim, aboneligin bitis anina kadar surer.
        graceUntil:
          tier === SubscriptionTier.GRACE_PERIOD || tier === SubscriptionTier.BILLING_ISSUE
            ? (event.expiration_at_ms ? new Date(event.expiration_at_ms) : null)
            : null,
        eventId: event.id,
      });

      this.logger.log(
        `RevenueCat event işlendi: type=${event.type}, app_user_id=${event.app_user_id}, tier=${tier}`,
      );
    } catch (err) {
      this.logger.error(
        `Abonelik kaydı güncellenemedi: app_user_id=${event.app_user_id}`,
        err instanceof Error ? err.stack : String(err),
      );
      throw err;
    }
  }

  private mapEventToTier(event: RevenueCatWebhookEvent): SubscriptionTier {
    switch (event.type) {
      case RevenueCatEventType.INITIAL_PURCHASE:
      case RevenueCatEventType.RENEWAL:
      case RevenueCatEventType.UNCANCELLATION:
      case RevenueCatEventType.PRODUCT_CHANGE:
      case RevenueCatEventType.NON_RENEWING_PURCHASE:
        return event.period_type === 'TRIAL' || event.period_type === 'INTRO'
          ? SubscriptionTier.TRIALING
          : SubscriptionTier.ACTIVE;

      case RevenueCatEventType.CANCELLATION:
        // İptal edildi ama dönem sonuna kadar erişim devam edebilir —
        // expiration_at_ms hâlâ gelecekteyse ACTIVE, geçmişteyse EXPIRED.
        return event.expiration_at_ms && event.expiration_at_ms > Date.now()
          ? SubscriptionTier.ACTIVE
          : SubscriptionTier.EXPIRED;

      case RevenueCatEventType.EXPIRATION:
        return SubscriptionTier.EXPIRED;

      case RevenueCatEventType.BILLING_ISSUE:
        return SubscriptionTier.BILLING_ISSUE;

      case RevenueCatEventType.SUBSCRIPTION_PAUSED:
        return SubscriptionTier.EXPIRED;

      default:
        this.logger.warn(`Bilinmeyen RevenueCat event tipi: ${event.type} — ACTIVE varsayılıyor.`);
        return SubscriptionTier.ACTIVE;
    }
  }

  private deriveWillAutoRenew(event: RevenueCatWebhookEvent, tier: SubscriptionTier): boolean {
    if (event.type === RevenueCatEventType.CANCELLATION) return false;
    if (event.type === RevenueCatEventType.EXPIRATION) return false;
    if (event.type === RevenueCatEventType.SUBSCRIPTION_PAUSED) return false;
    return tier === SubscriptionTier.ACTIVE || tier === SubscriptionTier.TRIALING;
  }

  /**
   * Gateway'in diğer servislerinin (örn. StationsService'in premium
   * kullanıcıya öncelik tanıması) kullanabileceği hızlı okuma metodu.
   */
  async getSubscriptionState(
    revenuecatAppUserId: string,
  ): Promise<RevenueCatSubscriptionEntity | null> {
    return this.subscriptionRepo.findOne({ where: { revenuecatAppUserId } });
  }
}

/**
 * RevenueCat "tier" degerini saglayicidan bagimsiz hak durumuna cevirir.
 *
 * GRACE_PERIOD ve BILLING_ISSUE ayni sonucu verir: ikisinde de odeme
 * yeniden deneniyor ve kullanicinin erisimi hemen kesilmemeli.
 * TRIALING de ACTIVE'dir -- denemenin amaci ozelligi kullanabilmektir.
 */
function mapTierToEntitlementStatus(tier: SubscriptionTier): EntitlementStatus {
  switch (tier) {
    case SubscriptionTier.ACTIVE:
    case SubscriptionTier.TRIALING:
      return EntitlementStatus.ACTIVE;

    case SubscriptionTier.GRACE_PERIOD:
    case SubscriptionTier.BILLING_ISSUE:
      return EntitlementStatus.IN_GRACE;

    case SubscriptionTier.REVOKED:
      // Iade/geri alim: sure dolmamis olsa bile erisim DERHAL kesilir.
      return EntitlementStatus.REVOKED;

    case SubscriptionTier.FREE:
    case SubscriptionTier.EXPIRED:
    default:
      return EntitlementStatus.EXPIRED;
  }
}

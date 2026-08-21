// backend/nestjs-gateway/src/entitlements/entitlements.module.ts
import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';

import { EntitlementsService } from './entitlements.service';
import { SubscriptionGuard } from './subscription.guard';
import { UsageCleanupService } from './usage-cleanup.service';
import { StripeWebhookController } from './stripe-webhook.controller';
import {
  FeatureUsageCounterEntity,
  SubscriptionEntitlementEntity,
} from './entitlement.entity';

@Module({
  imports: [
    TypeOrmModule.forFeature([SubscriptionEntitlementEntity, FeatureUsageCounterEntity]),
  ],
  controllers: [StripeWebhookController],
  providers: [EntitlementsService, SubscriptionGuard, UsageCleanupService],
  // Odeme duvari uygulayacak her modul bunlari kullanir.
  exports: [EntitlementsService, SubscriptionGuard],
})
export class EntitlementsModule {}

// backend/nestjs-gateway/src/billing/billing.module.ts
import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { ConfigModule } from '@nestjs/config';
import { EntitlementsModule } from '../entitlements/entitlements.module';
import { RevenueCatWebhookController } from './revenuecat-webhook.controller';
import { RevenueCatWebhookService } from './revenuecat-webhook.service';
import { RevenueCatWebhookAuthGuard } from './revenuecat-webhook-auth.guard';
import { RevenueCatSubscriptionEntity } from './entities/subscription.entity';

@Module({
  imports: [
    EntitlementsModule,TypeOrmModule.forFeature([RevenueCatSubscriptionEntity]), ConfigModule],
  controllers: [RevenueCatWebhookController],
  providers: [RevenueCatWebhookService, RevenueCatWebhookAuthGuard],
  exports: [RevenueCatWebhookService],
})
export class BillingModule {}

// backend/nestjs-gateway/src/app.module.ts
import { MiddlewareConsumer, Module, NestModule, RequestMethod } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { TypeOrmModule } from '@nestjs/typeorm';
import { RedisModule } from '@nestjs-modules/ioredis';
import { ThrottlerModule, ThrottlerGuard } from '@nestjs/throttler';
import { ScheduleModule } from '@nestjs/schedule';
import { APP_GUARD } from '@nestjs/core';
import { StationsModule } from './stations/stations.module';
import { BillingModule } from './billing/billing.module';
import { RoutingModule } from './routing/routing.module';
import { EntitlementsModule } from './entitlements/entitlements.module';
import { TelemetryModule } from './telemetry/telemetry.module';
import {
  FeatureUsageCounterEntity,
  SubscriptionEntitlementEntity,
} from './entitlements/entitlement.entity';
import { DevicesModule } from './devices/devices.module';
import { HealthController } from './health/health.controller';
import { PrivacyModule } from './privacy/privacy.module';
import { EvaModule } from './eva/eva.module';
import { CacheModule } from './cache/cache.module';
import { IpAllowlistMiddleware } from './common/middleware/ip-allowlist.middleware';
import {
  ChargingStationEntity,
  ChargingNetworkOperatorEntity,
  StationConnectorEntity,
} from './stations/entities/station.entity';
import { GeohashRegionCacheMetaEntity } from './stations/entities/geohash-region-cache-meta.entity';
import { SnakeNamingStrategy } from './common/database/snake-naming.strategy';
import { RevenueCatSubscriptionEntity } from './billing/entities/subscription.entity';
import { DevicePublicKeyEntity } from './devices/entities/device-public-key.entity';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      envFilePath: '.env',
    }),

    // Genel API rate-limiting: aynı IP'den dakikada 100'den fazla istek
    // gelirse 429 döner. Tek başına scraping'i engellemez — ana savunma
    // katmanları RequestSignatureGuard ve DeviceAttestationGuard'dır —
    // ama kaba kuvvet ve otomatik tarama girişimlerini yavaşlatır.
    ThrottlerModule.forRoot([
      {
        name: 'default',
        ttl: 60_000,
        limit: 100,
      },
    ]),

    TypeOrmModule.forRoot({
      type: 'postgres',
      url: process.env.DATABASE_URL,
      entities: [
        ChargingStationEntity,
        ChargingNetworkOperatorEntity,
        StationConnectorEntity,
        GeohashRegionCacheMetaEntity,
        RevenueCatSubscriptionEntity,
        DevicePublicKeyEntity,
        SubscriptionEntitlementEntity,
        FeatureUsageCounterEntity,
      ],
      // Entity property'leri camelCase, kanonik şema (database/schema.sql)
      // snake_case. Bu strateji ikisini tek noktadan hizalar.
      namingStrategy: new SnakeNamingStrategy(),
      // Şemanın tek kaynağı database/schema.sql'dir; Postgres konteyneri onu
      // ilk açılışta otomatik yükler (bkz. docker-compose.yml). synchronize
      // açık kalırsa TypeORM bu şemanın üzerine kendi modelini dayatmaya
      // çalışır ve mevcut tabloları bozar — bu yüzden her ortamda kapalı.
      synchronize: false,
      logging: process.env.NODE_ENV !== 'production',
    }),

    RedisModule.forRoot({
      type: 'single',
      url: process.env.REDIS_URL ?? 'redis://localhost:6379',
      // Not: burada isGlobal gibi bir seçenek YOK ve gerekmiyor —
      // @nestjs-modules/ioredis'in RedisCoreModule'ü zaten @Global()
      // olarak işaretli, bu yüzden @InjectRedis() her modülde çalışır
      // (RequestSignatureGuard, GeohashCacheService, DataDeletionService).
    }),

    CacheModule,
    DevicesModule,
    StationsModule,
    BillingModule,
    RoutingModule,
    // @Cron dekoratorleri bu modul olmadan SESSIZCE calismaz.
    ScheduleModule.forRoot(),
    EntitlementsModule,
    TelemetryModule,
    PrivacyModule,
    EvaModule,
  ],
  controllers: [HealthController],
  providers: [
    {
      provide: APP_GUARD,
      useClass: ThrottlerGuard,
    },
  ],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer): void {
    // IP allowlist, ileride ekleyeceğiniz admin/orchestration
    // endpoint'leri (örn. /v1/orchestration/runs) için hazır bir savunma
    // katmanı — şu an bu path altında tanımlı bir route YOK (Orchestration
    // modülü bu paketin kapsamında değil), bu yüzden middleware şu anda
    // hiçbir isteği etkilemiyor (no-op). Admin endpoint'i eklediğinizde
    // buradaki path deseni ve ADMIN_IP_ALLOWLIST .env değeri devreye girer.
    consumer
      .apply(IpAllowlistMiddleware)
      .forRoutes({ path: 'v1/admin/*', method: RequestMethod.ALL });
  }
}

// backend/nestjs-gateway/src/cache/cache.module.ts
import { Module } from '@nestjs/common';
import { GeohashCacheService } from './geohash-cache.service';

@Module({
  providers: [GeohashCacheService],
  exports: [GeohashCacheService],
})
export class CacheModule {}

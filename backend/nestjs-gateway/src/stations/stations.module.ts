// backend/nestjs-gateway/src/stations/stations.module.ts
import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { StationsController } from './stations.controller';
import { StationsService } from './stations.service';
import { StationFilterService } from './station-filter.service';
import { PriceTrendService } from './price-trend.service';
import { ChargingStationEntity } from './entities/station.entity';
import { CacheModule } from '../cache/cache.module';
import { AppAttestVerifierService } from '../common/services/app-attest-verifier.service';
import { PlayIntegrityVerifierService } from '../common/services/play-integrity-verifier.service';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';
import { DevicesModule } from '../devices/devices.module';
import { EntitlementsModule } from '../entitlements/entitlements.module';

@Module({
  imports: [
    TypeOrmModule.forFeature([ChargingStationEntity]),
    CacheModule,
    DevicesModule,
    EntitlementsModule,
  ],
  controllers: [StationsController],
  providers: [
    StationsService,
    StationFilterService,
    PriceTrendService,
    AppAttestVerifierService,
    PlayIntegrityVerifierService,
    RequestSignatureGuard,
  ],
  exports: [StationsService, StationFilterService],
})
export class StationsModule {}

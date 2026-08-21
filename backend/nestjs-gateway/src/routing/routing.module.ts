// backend/nestjs-gateway/src/routing/routing.module.ts
import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';

import { RoutingController } from './routing.controller';
import { RoutingService } from './routing.service';
import { ChargingStationEntity } from '../stations/entities/station.entity';
import { AppAttestVerifierService } from '../common/services/app-attest-verifier.service';
import { PlayIntegrityVerifierService } from '../common/services/play-integrity-verifier.service';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';
import { DevicesModule } from '../devices/devices.module';

@Module({
  imports: [TypeOrmModule.forFeature([ChargingStationEntity]), DevicesModule],
  controllers: [RoutingController],
  providers: [
    RoutingService,
    AppAttestVerifierService,
    PlayIntegrityVerifierService,
    RequestSignatureGuard,
  ],
  exports: [RoutingService],
})
export class RoutingModule {}

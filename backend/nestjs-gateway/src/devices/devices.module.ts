// backend/nestjs-gateway/src/devices/devices.module.ts
import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { DevicesController } from './devices.controller';
import { DevicesService } from './devices.service';
import { DevicePublicKeyEntity } from './entities/device-public-key.entity';
import { AppAttestVerifierService } from '../common/services/app-attest-verifier.service';
import { PlayIntegrityVerifierService } from '../common/services/play-integrity-verifier.service';

@Module({
  imports: [TypeOrmModule.forFeature([DevicePublicKeyEntity])],
  controllers: [DevicesController],
  providers: [DevicesService, AppAttestVerifierService, PlayIntegrityVerifierService],
  exports: [DevicesService],
})
export class DevicesModule {}

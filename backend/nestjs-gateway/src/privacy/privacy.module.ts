// backend/nestjs-gateway/src/privacy/privacy.module.ts
import { Module } from '@nestjs/common';

import { PrivacyController } from './privacy.controller';
import { DataDeletionService } from '../devices/data-deletion.service';
import { AppAttestVerifierService } from '../common/services/app-attest-verifier.service';
import { PlayIntegrityVerifierService } from '../common/services/play-integrity-verifier.service';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';
import { DevicesModule } from '../devices/devices.module';

/**
 * DevicesModule import ediliyor cunku RequestSignatureGuard, imzayi
 * dogrulamak icin DevicesService uzerinden cihazin genel anahtarini
 * okuyor. Redis ayrica import edilmiyor: RedisCoreModule @Global().
 */
@Module({
  imports: [DevicesModule],
  controllers: [PrivacyController],
  providers: [
    DataDeletionService,
    AppAttestVerifierService,
    PlayIntegrityVerifierService,
    RequestSignatureGuard,
  ],
})
export class PrivacyModule {}

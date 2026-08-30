// backend/nestjs-gateway/src/eva/eva.module.ts
import { Module } from '@nestjs/common';

import { EvaController } from './eva.controller';
import { EvaService } from './eva.service';
import { AppAttestVerifierService } from '../common/services/app-attest-verifier.service';
import { PlayIntegrityVerifierService } from '../common/services/play-integrity-verifier.service';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';
import { DevicesModule } from '../devices/devices.module';

@Module({
  imports: [DevicesModule],
  controllers: [EvaController],
  providers: [
    EvaService,
    AppAttestVerifierService,
    PlayIntegrityVerifierService,
    RequestSignatureGuard,
  ],
  exports: [EvaService],
})
export class EvaModule {}

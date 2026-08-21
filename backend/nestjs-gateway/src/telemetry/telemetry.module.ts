// backend/nestjs-gateway/src/telemetry/telemetry.module.ts
import { Module } from '@nestjs/common';

import { TelemetryController } from './telemetry.controller';
import { TelemetryGateway } from './telemetry.gateway';
import { TelemetryService } from './telemetry.service';
import { AppAttestVerifierService } from '../common/services/app-attest-verifier.service';
import { PlayIntegrityVerifierService } from '../common/services/play-integrity-verifier.service';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';
import { DevicesModule } from '../devices/devices.module';

@Module({
  imports: [DevicesModule],
  controllers: [TelemetryController],
  providers: [
    TelemetryService,
    TelemetryGateway,
    AppAttestVerifierService,
    PlayIntegrityVerifierService,
    RequestSignatureGuard,
  ],
  exports: [TelemetryService, TelemetryGateway],
})
export class TelemetryModule {}

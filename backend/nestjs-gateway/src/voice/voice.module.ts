// backend/nestjs-gateway/src/voice/voice.module.ts
import { Module } from '@nestjs/common';
import { HttpModule } from '@nestjs/axios';
import { VoiceController } from './voice.controller';
import { VoiceService } from './voice.service';
import { InternalHttpService } from '../common/internal/internal-http.service';
import { AppAttestVerifierService } from '../common/services/app-attest-verifier.service';
import { PlayIntegrityVerifierService } from '../common/services/play-integrity-verifier.service';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';
import { DevicesModule } from '../devices/devices.module';

@Module({
  imports: [HttpModule, DevicesModule],
  controllers: [VoiceController],
  providers: [
    VoiceService,
    InternalHttpService,
    AppAttestVerifierService,
    PlayIntegrityVerifierService,
    RequestSignatureGuard,
  ],
})
export class VoiceModule {}

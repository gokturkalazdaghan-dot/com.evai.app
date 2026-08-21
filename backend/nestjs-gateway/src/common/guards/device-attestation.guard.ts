// backend/nestjs-gateway/src/common/guards/device-attestation.guard.ts
import {
  CanActivate,
  ExecutionContext,
  Injectable,
  Logger,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createHash } from 'crypto';
import { Request } from 'express';
import { AppAttestVerifierService } from '../services/app-attest-verifier.service';
import { PlayIntegrityVerifierService } from '../services/play-integrity-verifier.service';

export interface AttestedRequest extends Request {
  deviceAttestation?: {
    platform: 'ios' | 'android';
    verified: boolean;
    attestationHash: string; // ham cihaz ID DEĞİL — tek yönlü hash, oturum-lokal
    verifiedAt: number;
  };
}

@Injectable()
export class DeviceAttestationGuard implements CanActivate {
  private readonly logger = new Logger(DeviceAttestationGuard.name);
  private readonly attestationRequired: boolean;

  constructor(
    private readonly configService: ConfigService,
    private readonly appAttestVerifier: AppAttestVerifierService,
    private readonly playIntegrityVerifier: PlayIntegrityVerifierService,
  ) {
    this.attestationRequired =
      this.configService.get<string>('DEVICE_ATTESTATION_ENFORCED', 'true') === 'true';
  }

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AttestedRequest>();

    const platform = request.headers['x-eva-platform'] as string | undefined;
    const attestationToken = request.headers['x-eva-attestation'] as string | undefined;

    if (!platform || !attestationToken) {
      if (this.attestationRequired) {
        this.logger.warn('Attestation header eksik, istek reddedildi.');
        throw new UnauthorizedException('Cihaz doğrulama bilgisi eksik.');
      }
      request.deviceAttestation = {
        platform: 'ios',
        verified: false,
        attestationHash: 'DEV_MODE_BYPASS',
        verifiedAt: Date.now(),
      };
      return true;
    }

    try {
      let verified = false;

      if (platform === 'ios') {
        verified = await this.appAttestVerifier.verify(attestationToken);
      } else if (platform === 'android') {
        verified = await this.playIntegrityVerifier.verify(attestationToken);
      } else {
        this.logger.warn(`Bilinmeyen platform: ${platform}`);
        throw new UnauthorizedException('Desteklenmeyen platform.');
      }

      if (!verified) {
        this.logger.warn(`Attestation doğrulaması başarısız: platform=${platform}`);
        throw new UnauthorizedException('Cihaz doğrulaması başarısız.');
      }

      const attestationHash = createHash('sha256')
        .update(attestationToken)
        .digest('hex')
        .slice(0, 16);

      request.deviceAttestation = {
        platform: platform as 'ios' | 'android',
        verified: true,
        attestationHash,
        verifiedAt: Date.now(),
      };

      return true;
    } catch (err) {
      if (err instanceof UnauthorizedException) throw err;
      this.logger.error(
        `Attestation doğrulama sırasında beklenmeyen hata: platform=${platform}`,
        err instanceof Error ? err.stack : String(err),
      );
      throw new UnauthorizedException('Cihaz doğrulaması işlenemedi.');
    }
  }
}

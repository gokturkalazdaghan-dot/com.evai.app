// backend/nestjs-gateway/src/billing/revenuecat-webhook-auth.guard.ts
import {
  CanActivate,
  ExecutionContext,
  Injectable,
  Logger,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { timingSafeEqual } from 'crypto';
import { Request } from 'express';

/**
 * RevenueCat, webhook isteklerini kimlik doğrulamak için imza (HMAC) DEĞİL,
 * basit bir "Authorization: Bearer <secret>" header'ı kullanır — bu secret
 * RevenueCat Dashboard → Project Settings → Integrations → Webhooks
 * bölümünde sizin belirlediğiniz bir değerdir ve Gateway'in .env'inde
 * REVENUECAT_WEBHOOK_AUTH_SECRET olarak tutulur.
 *
 * Bu guard, Admin API Key doğrulamasıyla aynı sabit-zamanlı karşılaştırma
 * prensibini uygular — zamanlama saldırılarına karşı.
 */
@Injectable()
export class RevenueCatWebhookAuthGuard implements CanActivate {
  private readonly logger = new Logger(RevenueCatWebhookAuthGuard.name);
  private readonly expectedSecret: string;

  constructor(private readonly configService: ConfigService) {
    const secret = this.configService.get<string>('REVENUECAT_WEBHOOK_AUTH_SECRET');
    if (!secret || secret.length < 24) {
      throw new Error(
        'REVENUECAT_WEBHOOK_AUTH_SECRET tanımlı değil ya da yetersiz uzunlukta (min 24 karakter). ' +
          'RevenueCat webhook endpoint\'i güvenli bir secret olmadan başlatılamaz.',
      );
    }
    this.expectedSecret = secret;
  }

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<Request>();
    const authHeader = request.headers['authorization'] as string | undefined;

    if (!authHeader?.startsWith('Bearer ')) {
      this.logger.warn('RevenueCat webhook isteğinde Authorization header eksik veya hatalı formatta.');
      throw new UnauthorizedException('Webhook kimlik doğrulaması eksik.');
    }

    const providedSecret = authHeader.slice('Bearer '.length);

    if (!this.constantTimeEquals(providedSecret, this.expectedSecret)) {
      this.logger.warn('RevenueCat webhook isteği geçersiz secret ile geldi — reddedildi.');
      throw new UnauthorizedException('Geçersiz webhook secret.');
    }

    return true;
  }

  private constantTimeEquals(a: string, b: string): boolean {
    const bufferA = Buffer.from(a);
    const bufferB = Buffer.from(b);

    if (bufferA.length !== bufferB.length) {
      return false;
    }

    return timingSafeEqual(bufferA, bufferB);
  }
}

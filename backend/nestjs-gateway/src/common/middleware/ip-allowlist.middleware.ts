// backend/nestjs-gateway/src/common/middleware/ip-allowlist.middleware.ts
import { Injectable, NestMiddleware, Logger, ForbiddenException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Request, Response, NextFunction } from 'express';

/**
 * Yalnızca belirli endpoint gruplarına (örn. admin/orchestration
 * endpoint'leri) uygulanır — genel API trafiğine DEĞİL. Amaç: Fiyat
 * Tasarruf Ajanı'nın tetikleme endpoint'i, admin görünürlük endpoint'i
 * gibi "yalnızca sizin altyapınızdan çağrılmalı" uç noktaların, yanlış
 * yapılandırma sonucu dışarıya açık kalsa bile ek bir savunma katmanına
 * sahip olmasıdır (defense-in-depth — tek başına yeterli değil, AdminApiKeyGuard
 * gibi diğer katmanlarla birlikte kullanılmalı).
 *
 * ALLOWLIST boşsa (yapılandırılmamışsa) middleware devre dışı kalır ve
 * hiçbir isteği reddetmez — bu, yalnızca bulut sağlayıcınızın sabit
 * çıkış IP'lerini bildiğiniz production ortamında dolduracağınız
 * OPSİYONEL bir katmandır.
 */
@Injectable()
export class IpAllowlistMiddleware implements NestMiddleware {
  private readonly logger = new Logger(IpAllowlistMiddleware.name);
  private readonly allowedIps: Set<string>;

  constructor(private readonly configService: ConfigService) {
    const rawList = this.configService.get<string>('ADMIN_IP_ALLOWLIST', '');
    this.allowedIps = new Set(
      rawList
        .split(',')
        .map((ip) => ip.trim())
        .filter((ip) => ip.length > 0),
    );
  }

  use(req: Request, res: Response, next: NextFunction): void {
    if (this.allowedIps.size === 0) {
      // Yapılandırılmamış — bu katman opsiyonel, geçiliyor.
      next();
      return;
    }

    const clientIp = this.extractClientIp(req);

    if (!this.allowedIps.has(clientIp)) {
      this.logger.warn(`IP allowlist reddi: ${clientIp} izinli listede değil.`);
      throw new ForbiddenException('Bu adresten erişim izni yok.');
    }

    next();
  }

  private extractClientIp(req: Request): string {
    // X-Forwarded-For, ters proxy (nginx/ALB) arkasında çalışırken gerçek
    // istemci IP'sini taşır — yalnızca GÜVENİLİR bir proxy'nin arkasında
    // çalışıyorsanız bu header'a güvenin (aksi halde spoofable'dır).
    // trustProxy ayarı main.ts'de app.set('trust proxy', ...) ile
    // yapılandırılmalı.
    const forwardedFor = req.headers['x-forwarded-for'];
    if (typeof forwardedFor === 'string') {
      return forwardedFor.split(',')[0].trim();
    }
    return req.ip ?? req.socket.remoteAddress ?? 'unknown';
  }
}

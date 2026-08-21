// backend/nestjs-gateway/src/common/guards/request-signature.guard.ts
import {
  CanActivate,
  ExecutionContext,
  Injectable,
  Logger,
  UnauthorizedException,
} from '@nestjs/common';
import { InjectRedis } from '@nestjs-modules/ioredis';
import Redis from 'ioredis';
import { createHash, createVerify } from 'crypto';
import { Request } from 'express';
import { DevicesService } from '../../devices/devices.service';

const SIGNATURE_MAX_AGE_MS = 5 * 60 * 1000; // 5 dakika

export interface SignedRequest extends Request {
  verifiedDeviceId?: string;
}

/**
 * Android tarafındaki RequestSigner.kt ile üretilen imzayı doğrular.
 * Bu guard İKİ farklı saldırı sınıfına karşı koruma sağlar:
 *
 *  1. İçerik değiştirme: İmza method+path+timestamp+bodyHash üzerinden
 *     üretildiği için, biri isteği yakalayıp gövdesini değiştirse (örn.
 *     Postman ile "aynı" isteği tekrar oynatmaya çalışsa), imza artık
 *     eşleşmez.
 *  2. Replay (tekrar oynatma): SIGNATURE_MAX_AGE_MS penceresi dışındaki
 *     timestamp'ler reddedilir VE her (deviceId, timestamp) çifti Redis'te
 *     tek kullanımlık olarak işaretlenir — aynı imzalı istek iki kez
 *     gönderilemez.
 *
 * DeviceAttestationGuard'dan SONRA çalışacak şekilde tasarlandı (route
 * dekoratöründe @UseGuards(DeviceAttestationGuard, RequestSignatureGuard)
 * sırası önemlidir) — önce "gerçek Eva uygulaması mı" sorusu, sonra "bu
 * spesifik istek kurcalanmış mı" sorusu cevaplanır.
 */
@Injectable()
export class RequestSignatureGuard implements CanActivate {
  private readonly logger = new Logger(RequestSignatureGuard.name);

  constructor(
    private readonly devicesService: DevicesService,
    @InjectRedis() private readonly redis: Redis,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<SignedRequest>();

    const deviceId = request.headers['x-eva-device-id'] as string | undefined;
    const signature = request.headers['x-eva-signature'] as string | undefined;
    const timestampHeader = request.headers['x-eva-signature-timestamp'] as string | undefined;

    if (!deviceId || !signature || !timestampHeader) {
      this.logger.warn('İmza header\'ları eksik.');
      throw new UnauthorizedException('İstek imzası eksik.');
    }

    const timestamp = parseInt(timestampHeader, 10);
    if (isNaN(timestamp)) {
      throw new UnauthorizedException('Geçersiz zaman damgası.');
    }

    const age = Date.now() - timestamp;
    if (age > SIGNATURE_MAX_AGE_MS || age < -30_000) {
      // -30sn tolerans: istemci saati sunucudan biraz ileride olabilir.
      this.logger.warn(`İmza zaman damgası pencere dışında: age=${age}ms`);
      throw new UnauthorizedException('İstek süresi dolmuş, tekrar deneyin.');
    }

    const device = await this.devicesService.findPublicKey(deviceId);
    if (!device) {
      this.logger.warn(`Kayıtsız cihaz imza denemesi: deviceId=${deviceId.slice(0, 8)}...`);
      throw new UnauthorizedException('Cihaz kayıtlı değil.');
    }

    const method = request.method;
    const path = request.route?.path ?? request.path;
    const bodyHash = createHash('sha256')
      .update(request.method === 'GET' ? '' : JSON.stringify(request.body ?? {}))
      .digest('hex');

    const payload = `${method}|${this.normalizePath(request)}|${timestamp}|${bodyHash}`;

    const isValid = this.verifySignature(payload, signature, device.publicKeyBase64);
    if (!isValid) {
      this.logger.warn(`İmza doğrulaması başarısız: deviceId=${deviceId.slice(0, 8)}...`);
      throw new UnauthorizedException('İstek imzası geçersiz.');
    }

    const replayGuardOk = await this.enforceReplayProtection(deviceId, timestamp, signature);
    if (!replayGuardOk) {
      this.logger.warn(`Replay saldırısı tespit edildi: deviceId=${deviceId.slice(0, 8)}...`);
      throw new UnauthorizedException('Bu istek daha önce işlendi.');
    }

    request.verifiedDeviceId = deviceId;
    void this.devicesService.touchLastUsed(deviceId);

    return true;
  }

  /**
   * Android istemcisi imzayı üretirken `path` olarak isteğin gönderildiği
   * ham path'i kullanıyor (örn. "/v1/stations/nearby") — NestJS'in route
   * pattern'i (örn. "/v1/stations/:id" gibi parametreli path'ler) ile
   * karışmaması için ham URL path'i (query string hariç) kullanılıyor.
   */
  private normalizePath(request: Request): string {
    return request.originalUrl.split('?')[0];
  }

  private verifySignature(payload: string, signatureBase64: string, publicKeyBase64: string): boolean {
    try {
      const publicKeyDer = Buffer.from(publicKeyBase64, 'base64');
      const publicKeyPem = this.derToPem(publicKeyDer);

      const verifier = createVerify('SHA256');
      verifier.update(payload);
      verifier.end();

      return verifier.verify(publicKeyPem, Buffer.from(signatureBase64, 'base64'));
    } catch (err) {
      this.logger.error(
        'İmza doğrulama işlemi sırasında hata.',
        err instanceof Error ? err.stack : String(err),
      );
      return false;
    }
  }

  private derToPem(der: Buffer): string {
    const base64 = der.toString('base64');
    const lines = base64.match(/.{1,64}/g) ?? [];
    return `-----BEGIN PUBLIC KEY-----\n${lines.join('\n')}\n-----END PUBLIC KEY-----\n`;
  }

  /**
   * Redis SET NX ile "bu imza daha önce görüldü mü" kontrolü — anahtarın
   * kendisi imzanın hash'i, TTL imza penceresiyle (+ küçük bir pay) eşit.
   * Bu sayede Redis, penceresi geçmiş eski replay kayıtlarını kendiliğinden
   * temizler; manuel bir temizlik job'ı gerekmez.
   */
  private async enforceReplayProtection(
    deviceId: string,
    timestamp: number,
    signature: string,
  ): Promise<boolean> {
    const replayKey = `sig-replay:${deviceId}:${createHash('sha256').update(signature).digest('hex')}`;

    try {
      const result = await this.redis.set(
        replayKey,
        '1',
        'PX',
        SIGNATURE_MAX_AGE_MS + 60_000,
        'NX',
      );
      return result === 'OK';
    } catch (err) {
      this.logger.error(
        'Replay koruması için Redis erişilemedi — güvenli taraf: isteği reddet.',
        err instanceof Error ? err.stack : String(err),
      );
      // Redis çökmüşse replay korumasını atlamak yerine isteği reddetmek
      // tercih edildi — kritik bir güvenlik kontrolünün "fail open" olması
      // istenmez.
      return false;
    }
  }
}

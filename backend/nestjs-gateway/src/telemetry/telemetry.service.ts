// backend/nestjs-gateway/src/telemetry/telemetry.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRedis } from '@nestjs-modules/ioredis';
import Redis from 'ioredis';

import type { LiveTelemetry } from './telemetry.types';

/**
 * Son okuma bu sure sonunda "bayat" sayilir ve silinir.
 *
 * 10 dakika: arac park edilip telefon uzaklastiginda panel, saatler once
 * alinmis bir okumayi CANLI gibi gostermemeli.
 */
const LATEST_TTL_SECONDS = 10 * 60;

/**
 * Panel oturum belirteclerinin gecerlilik suresi.
 * Kisa tutuluyor: belirtec sizarsa hasar penceresi dar olsun.
 */
const PANEL_TOKEN_TTL_SECONDS = 15 * 60;

@Injectable()
export class TelemetryService {
  private readonly logger = new Logger(TelemetryService.name);

  constructor(@InjectRedis() private readonly redis: Redis) {}

  /** Son okumayi saklar (panel acildiginda hemen gosterilebilsin diye). */
  async storeLatest(telemetry: LiveTelemetry): Promise<void> {
    try {
      await this.redis.set(
        latestKey(telemetry.subjectId),
        JSON.stringify(telemetry),
        'EX',
        LATEST_TTL_SECONDS,
      );
    } catch (err) {
      // Onbellek yazilamazsa canli yayin YINE DE calisir; panel yalnizca
      // acilista bos baslar.
      this.logger.warn(`Son telemetri yazilamadi: ${err instanceof Error ? err.message : err}`);
    }
  }

  async getLatest(subjectId: string): Promise<LiveTelemetry | null> {
    try {
      const raw = await this.redis.get(latestKey(subjectId));
      return raw ? (JSON.parse(raw) as LiveTelemetry) : null;
    } catch (err) {
      this.logger.warn(`Son telemetri okunamadi: ${err instanceof Error ? err.message : err}`);
      return null;
    }
  }

  /**
   * Panel icin kisa omurlu bir izleme belirteci uretir.
   *
   * NEDEN GEREKLI: panel bir tarayicidir; cihazin imzalama anahtarina
   * sahip DEGILDIR ve olmamalidir (anahtar tarayiciya konsa herkes
   * cihaz taklidi yapabilirdi). Bunun yerine imzali bir HTTP istegiyle
   * kisa omurlu bir belirtec alinir ve WS aboneliginde o kullanilir.
   */
  async issuePanelToken(subjectId: string): Promise<{ token: string; expiresInSeconds: number }> {
    const token = randomToken();
    await this.redis.set(panelTokenKey(token), subjectId, 'EX', PANEL_TOKEN_TTL_SECONDS);
    return { token, expiresInSeconds: PANEL_TOKEN_TTL_SECONDS };
  }

  /**
   * Abonelik yetkisi dogrular.
   *
   * Belirtec, ISTENEN subjectId ile eslesmeli. Yalnizca "belirtec var mi"
   * diye bakmak, gecerli bir belirtece sahip herkesin baskasinin aracini
   * izlemesine izin verirdi.
   */
  async verifySubjectAccess(subjectId: string, token?: string): Promise<boolean> {
    if (!token) return false;
    try {
      const owner = await this.redis.get(panelTokenKey(token));
      return owner === subjectId;
    } catch (err) {
      // Dogrulanamiyorsa REDDET. Hata durumunda erisim vermek, Redis
      // kesintisini yetki atlatma yoluna cevirirdi.
      this.logger.error(`Panel belirteci dogrulanamadi: ${err instanceof Error ? err.message : err}`);
      return false;
    }
  }
}

function latestKey(subjectId: string): string {
  return `telemetry:latest:${subjectId}`;
}

function panelTokenKey(token: string): string {
  return `telemetry:panel-token:${token}`;
}

function randomToken(): string {
  // 32 bayt: tahmin edilemez olmasi icin fazlasiyla yeterli.
  return require('crypto').randomBytes(32).toString('base64url');
}

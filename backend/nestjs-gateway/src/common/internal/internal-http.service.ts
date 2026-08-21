// backend/nestjs-gateway/src/common/internal/internal-http.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { HttpService } from '@nestjs/axios';
import { firstValueFrom } from 'rxjs';
import { InternalKeyDeriver } from './internal-key.util';

/**
 * Python AI servisine (Fiyat Tasarruf Ajanı / Voice Co-pilot) giden TÜM
 * dahili isteklerin tek geçiş noktası. Her istek otomatik olarak dönen
 * X-Internal-Service-Key header'ı ile imzalanır — Python servisi bu
 * header olmadan hiçbir isteği kabul etmez (bkz. services/internal_auth.py).
 *
 * Bu, Python servisinin (ve içindeki Anthropic API key'inin) YALNIZCA
 * Gateway üzerinden erişilebilir olmasını garanti eder; Python servisi
 * yanlışlıkla dış dünyaya açık bir portta çalışsa bile (örn. yanlış
 * docker-compose port mapping), bu anahtar olmadan istekler reddedilir.
 */
@Injectable()
export class InternalHttpService {
  private readonly logger = new Logger(InternalHttpService.name);
  private readonly keyDeriver: InternalKeyDeriver;
  private readonly priceAgentBaseUrl: string;

  constructor(
    private readonly httpService: HttpService,
    private readonly configService: ConfigService,
  ) {
    const masterSecret = this.configService.get<string>('INTERNAL_SERVICE_MASTER_SECRET', '');
    this.keyDeriver = new InternalKeyDeriver(masterSecret);
    this.priceAgentBaseUrl = this.configService.get<string>(
      'PRICE_AGENT_SERVICE_URL',
      'http://price-saving-agent:8000',
    );
  }

  async post<Response>(path: string, body: unknown, timeoutMs = 15_000): Promise<Response> {
    try {
      const response = await firstValueFrom(
        this.httpService.post<Response>(`${this.priceAgentBaseUrl}${path}`, body, {
          timeout: timeoutMs,
          headers: {
            'X-Internal-Service-Key': this.keyDeriver.currentKey(),
            'Content-Type': 'application/json',
          },
        }),
      );
      return response.data;
    } catch (err) {
      this.logger.error(
        `Dahili Python servis çağrısı başarısız: path=${path}`,
        err instanceof Error ? err.stack : String(err),
      );
      throw err;
    }
  }
}

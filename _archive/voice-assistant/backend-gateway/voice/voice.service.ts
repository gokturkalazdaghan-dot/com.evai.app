// backend/nestjs-gateway/src/voice/voice.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InternalHttpService } from '../common/internal/internal-http.service';
import { VoiceQueryRequestDto, VoiceQueryResponseDto } from './dto/voice-query.dto';

/**
 * price-saving-agent'ın /v1/voice/interpret yanıt sözleşmesi (snake_case).
 * Python tarafındaki models/voice_schemas.py -> VoiceQueryResponse ile
 * birebir aynı olmalıdır.
 */
export interface TranscriptionResult {
  text: string;
  recognized: boolean;
}

interface VoiceAgentResponse {
  spoken_reply: string;
  action?: 'none' | 'navigate';
  recommended_station_id: string | null;
  recommended_station_name: string | null;
  distance_meters: number | null;
  estimated_price_per_kwh: number | null;
  follow_up_suggested: boolean;
}

@Injectable()
export class VoiceService {
  private readonly logger = new Logger(VoiceService.name);

  constructor(private readonly internalHttpService: InternalHttpService) {}

  /**
   * Ses -> metin. Uygulama mikrofon kaydini base64 olarak gonderir,
   * Gateway bunu price-saving-agent'a iletir.
   *
   * NEDEN GATEWAY UZERINDEN: Groq API anahtari SUNUCUDA kalir. Anahtari
   * APK'ya gomup telefondan dogrudan Groq'a gitmek, anahtarin bir string
   * dump'iyla calinabilmesi demektir.
   */
  async transcribe(audioBase64: string, languageCode: string): Promise<TranscriptionResult> {
    try {
      return await this.internalHttpService.post<TranscriptionResult>(
        '/v1/voice/transcribe-base64',
        { audio_base64: audioBase64, language: languageCode },
        30_000,
      );
    } catch (err) {
      this.logger.error(
        'Transkripsiyon cagrisi basarisiz.',
        err instanceof Error ? err.stack : String(err),
      );
      // Uydurma bir metin DONDURULMEZ; istemci "anlasilmadi" gosterir.
      return { text: '', recognized: false };
    }
  }

  async processQuery(
    query: VoiceQueryRequestDto,
    deviceId: string,
  ): Promise<VoiceQueryResponseDto> {
    try {
      const agentResponse = await this.internalHttpService.post<VoiceAgentResponse>(
        '/v1/voice/interpret',
        {
          transcript: query.transcript,
          lat: query.lat,
          lon: query.lon,
          battery_soc_percent: query.batterySocPercent ?? null,
          vehicle_connector_types: query.vehicleConnectorTypes ?? [],
          language_code: query.languageCode ?? 'tr',
          // Konusma hafizasi anahtari: imza dogrulamasindan gecmis cihaz
          // kimligi. Istemciden GELMEZ -- gonderilseydi bir kullanici
          // baskasinin session_id'sini yazip konusmasini okuyabilirdi.
          session_id: deviceId,
        },
      );

      // Python servisi snake_case döndürür; istemci sözleşmesi (ve aşağıdaki
      // hata yolu fallback'i) camelCase'dir. Bu eşleme OLMADAN yanıt olduğu
      // gibi geçiyordu — istek yolu (battery_soc_percent vb.) özenle
      // çevrilmişken yanıt yolu yalnızca bir TypeScript tip iddiasıydı.
      // Sonuç: istemci BASARI durumunda spokenReply yerine undefined okuyor,
      // HATA durumunda ise doğru alanı görüyordu.
      return {
        spokenReply: agentResponse.spoken_reply,
        recommendedStationId: agentResponse.recommended_station_id ?? undefined,
        recommendedStationName: agentResponse.recommended_station_name ?? undefined,
        distanceMeters: agentResponse.distance_meters ?? undefined,
        estimatedPricePerKwh: agentResponse.estimated_price_per_kwh ?? undefined,
        followUpSuggested: agentResponse.follow_up_suggested ?? false,
        // Ajan bu alani bilmeyen eski bir surumse 'none' varsayilir:
        // istenmeden rota cizilmesindense hic cizilmemesi yeglenir.
        action: agentResponse.action ?? 'none',
      };
    } catch (err) {
      this.logger.error(
        'Voice Co-pilot Ajanı çağrısı başarısız.',
        err instanceof Error ? err.stack : String(err),
      );
      // Kullanıcı sesli bir hata mesajı duymalı, teknik bir 500 değil —
      // sürücü direksiyondayken JSON hata gövdesi göremez.
      return {
        spokenReply: 'Şu anda yanıt veremiyorum, az sonra tekrar dener misin?',
        followUpSuggested: false,
        action: 'none',
      };
    }
  }
}

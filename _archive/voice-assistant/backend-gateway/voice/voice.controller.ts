// backend/nestjs-gateway/src/voice/voice.controller.ts
import { Body, Controller, Post, Req, UseGuards, Logger } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { VoiceService } from './voice.service';
import { VoiceQueryRequestDto, VoiceQueryResponseDto } from './dto/voice-query.dto';
import { VoiceTranscribeRequestDto, VoiceTranscribeResponseDto } from './dto/voice-transcribe.dto';
import { DeviceAttestationGuard } from '../common/guards/device-attestation.guard';
import { RequestSignatureGuard, SignedRequest } from '../common/guards/request-signature.guard';

@Controller('v1/voice')
@UseGuards(DeviceAttestationGuard, RequestSignatureGuard)
export class VoiceController {
  private readonly logger = new Logger(VoiceController.name);

  constructor(private readonly voiceService: VoiceService) {}

  /**
   * Mikrofon kaydini metne cevirir.
   *
   * Ses base64 olarak JSON govdesinde gelir. Multipart yerine base64
   * secildi cunku RequestSignatureGuard govdeyi JSON.stringify uzerinden
   * hash'liyor; multipart bu sozlesmeyi kirardi.
   */
  @Post('transcribe')
  @Throttle({ default: { limit: 20, ttl: 60_000 } })
  async transcribe(@Body() body: VoiceTranscribeRequestDto): Promise<VoiceTranscribeResponseDto> {
    return this.voiceService.transcribe(body.audioBase64, body.languageCode ?? 'tr');
  }

  @Post('query')
  // Sesli sorgular Claude API çağrısı tetikliyor (maliyetli) — genel
  // Gateway rate-limit'inden daha sıkı, dakikada 6 istek (kullanıcı
  // başına ~10 saniyede bir) ile sınırlandırılıyor.
  @Throttle({ default: { limit: 6, ttl: 60_000 } })
  async query(
    @Body() body: VoiceQueryRequestDto,
    @Req() request: SignedRequest,
  ): Promise<VoiceQueryResponseDto> {
    // Konusma hafizasi anahtari olarak DOGRULANMIS cihaz kimligi kullanilir
    // (RequestSignatureGuard tarafindan set edilir). Istemcinin gonderdigi
    // bir deger kullanilsaydi, baskasinin oturumunu okumak mumkun olurdu.
    return this.voiceService.processQuery(body, request.verifiedDeviceId ?? '');
  }
}

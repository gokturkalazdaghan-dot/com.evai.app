// backend/nestjs-gateway/src/voice/dto/voice-transcribe.dto.ts
import { IsBase64, IsOptional, IsString, MaxLength } from 'class-validator';

/**
 * Mikrofon kaydının metne çevrilmesi isteği.
 *
 * Ses neden base64/JSON? RequestSignatureGuard gövdeyi
 * JSON.stringify(body) üzerinden hash'liyor (bkz. request-signature.guard.ts).
 * Multipart gönderim bu imza sözleşmesini kırardı; base64 mevcut akışa
 * dokunmadan çalışır.
 */
export class VoiceTranscribeRequestDto {
  @IsString()
  @IsBase64()
  // ~1.5 MB base64 ≈ 1.1 MB ham ses ≈ 35 sn (16 kHz mono 16-bit).
  // Bundan uzun bir kayıt yanlışlıkla açık kalmış mikrofon demektir;
  // kotayı yakmasın diye reddedilir.
  @MaxLength(1_500_000)
  audioBase64!: string;

  @IsOptional()
  @IsString()
  languageCode?: string;
}

export class VoiceTranscribeResponseDto {
  text!: string;
  /** false ise ses anlaşılmadı — istemci UYDURMA bir metin göstermemeli. */
  recognized!: boolean;
}

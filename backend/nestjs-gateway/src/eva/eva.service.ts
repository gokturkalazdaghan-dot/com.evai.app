// backend/nestjs-gateway/src/eva/eva.service.ts
import {
  Injectable,
  Logger,
  ServiceUnavailableException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

import { EvaChatTurnDto } from './dto/eva-chat.dto';

const DEFAULT_XAI_BASE_URL = 'https://api.x.ai/v1';
const DEFAULT_XAI_MODEL = 'grok-4.6';
const REQUEST_TIMEOUT_MS = 30_000;

const EVA_SYSTEM_PROMPT = [
  'Sen Eva adinda akilli, yardimsever ve samimi bir yol asistanisin.',
  'Suruculere rotalar, trafik, arac bakimi, sarj ve yolculuk boyunca',
  'eslik etme konularinda akici, dogal ve sohbet havasinda yardimci oluyorsun.',
  'Urun adi Eva Ai. Tum haklar Armanalabs\'a aittir.',
  'Direksiyonda okunacak kadar kisa yaz. Uydurma sarj fiyati, istasyon',
  'doluluk veya mesafe soyleme; bilmiyorsan soyle.',
].join(' ');

export interface EvaChatResult {
  reply: string;
  model: string;
}

interface XaiChatCompletionResponse {
  model?: string;
  choices?: Array<{ message?: { content?: string } }>;
}

/**
 * Eva sohbetini xAI Grok'a tasir.
 *
 * NEDEN GATEWAY
 * -------------
 * Fiyat ajanı LLM kullanmaz (sesli asistan arsivde). Grok anahtarini
 * uygulamaya gommek APK'dan cikarilabilir. Anahtar burada kalir, istemci
 * yalnizca /v1/eva/chat konusur.
 *
 * NEDEN CHAT COMPLETIONS
 * ----------------------
 * Android tarafindaki sohbet gecmisi istemcide tutulur; her istek
 * stateless. Responses API'nin sunucu tarafli oturumu bu sozlesmeye
 * gerekmeden karmasiklik ekler.
 */
@Injectable()
export class EvaService {
  private readonly logger = new Logger(EvaService.name);

  constructor(private readonly config: ConfigService) {}

  async chat(message: string, history: EvaChatTurnDto[] = []): Promise<EvaChatResult> {
    const apiKey = this.config.get<string>('XAI_API_KEY')?.trim();
    if (!apiKey) {
      throw new ServiceUnavailableException('Eva su anda sohbet edemiyor.');
    }

    const baseUrl = (
      this.config.get<string>('XAI_BASE_URL') ?? DEFAULT_XAI_BASE_URL
    ).replace(/\/$/, '');
    const model = this.config.get<string>('XAI_MODEL') ?? DEFAULT_XAI_MODEL;

    const messages = [
      { role: 'system', content: EVA_SYSTEM_PROMPT },
      ...history.map((turn) => ({ role: turn.role, content: turn.content })),
      { role: 'user', content: message },
    ];

    let response: Response;
    try {
      response = await this.fetchWithTimeout(`${baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          model,
          messages,
          temperature: 0.7,
        }),
      });
    } catch (err) {
      this.logger.error(
        'xAI istegi ulasilamadi.',
        err instanceof Error ? err.stack : String(err),
      );
      throw new ServiceUnavailableException('Eva su anda sohbet edemiyor.');
    }

    if (!response.ok) {
      const body = await response.text().catch(() => '');
      this.logger.error(`xAI ${response.status}: ${body.slice(0, 240)}`);
      throw new ServiceUnavailableException('Eva su anda sohbet edemiyor.');
    }

    const payload = (await response.json()) as XaiChatCompletionResponse;
    const reply = payload.choices?.[0]?.message?.content?.trim();
    if (!reply) {
      this.logger.error('xAI bos cevap dondu.');
      throw new ServiceUnavailableException('Eva su anda sohbet edemiyor.');
    }

    return {
      reply,
      model: payload.model ?? model,
    };
  }

  private async fetchWithTimeout(url: string, init: RequestInit): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      return await fetch(url, { ...init, signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }
}

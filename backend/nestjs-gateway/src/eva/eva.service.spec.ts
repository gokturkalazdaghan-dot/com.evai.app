// backend/nestjs-gateway/src/eva/eva.service.spec.ts
import { ServiceUnavailableException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

import { EvaService } from './eva.service';

describe('EvaService', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
    jest.restoreAllMocks();
  });

  function build(env: Record<string, string | undefined> = {}): EvaService {
    const config = {
      get: (key: string) => env[key],
    } as unknown as ConfigService;
    return new EvaService(config);
  }

  it('XAI_API_KEY yoksa sohbeti acmaz', async () => {
    const service = build({});

    await expect(service.chat('merhaba')).rejects.toBeInstanceOf(
      ServiceUnavailableException,
    );
  });

  it('Grok cevabini reply olarak doner', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        model: 'grok-4.6',
        choices: [{ message: { content: 'En yakin DC istasyonu soyleyeyim.' } }],
      }),
    }) as unknown as typeof fetch;

    const service = build({ XAI_API_KEY: 'test-key' });
    const result = await service.chat('nerede sarj var?');

    expect(result.reply).toBe('En yakin DC istasyonu soyleyeyim.');
    expect(result.model).toBe('grok-4.6');

    const [url, init] = (global.fetch as jest.Mock).mock.calls[0];
    expect(url).toBe('https://api.x.ai/v1/chat/completions');
    expect(init.headers.Authorization).toBe('Bearer test-key');
    const body = JSON.parse(init.body);
    expect(body.model).toBe('grok-4.6');
    expect(body.messages[0].role).toBe('system');
    expect(body.messages.at(-1)).toEqual({
      role: 'user',
      content: 'nerede sarj var?',
    });
  });

  it('xAI hata donerse anahtari sizdirmadan dusurur', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 401,
      text: async () => 'unauthorized',
    }) as unknown as typeof fetch;

    const service = build({ XAI_API_KEY: 'test-key' });

    await expect(service.chat('merhaba')).rejects.toBeInstanceOf(
      ServiceUnavailableException,
    );
  });
});

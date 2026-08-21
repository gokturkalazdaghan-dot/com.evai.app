// backend/nestjs-gateway/src/billing/revenuecat-webhook.controller.ts
import {
  Body,
  Controller,
  Post,
  HttpCode,
  HttpStatus,
  Logger,
  UseGuards,
} from '@nestjs/common';
import { RevenueCatWebhookService } from './revenuecat-webhook.service';
import { RevenueCatWebhookAuthGuard } from './revenuecat-webhook-auth.guard';
import { RevenueCatWebhookPayload } from './dto/revenuecat-webhook-event.dto';

@Controller('v1/billing/webhooks/revenuecat')
export class RevenueCatWebhookController {
  private readonly logger = new Logger(RevenueCatWebhookController.name);

  constructor(private readonly webhookService: RevenueCatWebhookService) {}

  // Not: Bu endpoint DeviceAttestationGuard KULLANMAZ — çağıran taraf
  // kullanıcının cihazı değil, RevenueCat'in sunucularıdır. Kimlik
  // doğrulama RevenueCatWebhookAuthGuard (Bearer secret) ile sağlanır.
  @Post('events')
  @UseGuards(RevenueCatWebhookAuthGuard)
  @HttpCode(HttpStatus.OK)
  async handleWebhookEvent(
    @Body() payload: RevenueCatWebhookPayload,
  ): Promise<{ status: string }> {
    try {
      await this.webhookService.processEvent(payload.event);
      return { status: 'processed' };
    } catch (err) {
      this.logger.error(
        `RevenueCat webhook işleme hatası: eventId=${payload.event?.id}`,
        err instanceof Error ? err.stack : String(err),
      );
      // RevenueCat, 2xx dışındaki yanıtlarda event'i yeniden dener
      // (exponential backoff ile, birkaç gün boyunca). Geçici hatalarda
      // (DB kesintisi vb.) bu retry mekanizması işimize yarıyor, bu yüzden
      // hatayı yutmadan yükseltiyoruz.
      throw err;
    }
  }
}

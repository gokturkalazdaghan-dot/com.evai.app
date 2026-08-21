// backend/nestjs-gateway/src/entitlements/stripe-webhook.controller.ts
import {
  BadRequestException,
  Body,
  Controller,
  Headers,
  HttpCode,
  HttpStatus,
  Logger,
  Post,
  RawBodyRequest,
  Req,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createHmac, timingSafeEqual } from 'crypto';
import type { Request } from 'express';

import { EntitlementsService } from './entitlements.service';
import { EntitlementSource, EntitlementStatus } from './entitlement.entity';

/**
 * Stripe imzasinin gecerli sayilacagi en buyuk zaman farki.
 * Tekrar saldirisina (replay) karsi: eski ama gecerli imzali bir govde
 * tekrar gonderilip abonelik uzatilamasin.
 */
const SIGNATURE_TOLERANCE_SECONDS = 300;

/**
 * Stripe abonelik olaylari.
 *
 * KAPSAM UYARISI
 * --------------
 * Google Play, Android uygulamasi icindeki dijital icerigin Play
 * Billing ile satilmasini ZORUNLU tutar; mobil premium'u Stripe ile
 * acmak uygulamanin magazadan kaldirilmasina yol acabilir. Bu yuzden
 * mobil satin alimlar RevenueCat/Play Billing uzerinden akmaya devam
 * eder.
 *
 * Bu uc, ayni hak tablosunu besleyen IKINCI bir kaynaktir ve WEB
 * yuzeyi (operator paneli, kurumsal abonelik) icin tasarlanmistir --
 * orada Stripe serbesttir. Guard hangi kaynaktan geldigini umursamaz.
 */
@Controller('v1/billing/webhooks/stripe')
export class StripeWebhookController {
  private readonly logger = new Logger(StripeWebhookController.name);

  constructor(
    private readonly entitlements: EntitlementsService,
    private readonly config: ConfigService,
  ) {}

  @Post('events')
  @HttpCode(HttpStatus.OK)
  async handleEvent(
    @Req() request: RawBodyRequest<Request>,
    @Headers('stripe-signature') signature: string | undefined,
    @Body() body: StripeEventBody,
  ): Promise<{ received: true }> {
    const secret = this.config.get<string>('STRIPE_WEBHOOK_SECRET');
    if (!secret) {
      // Yapilandirilmamis bir odeme webhook'unu ACIK BIRAKMAK, herkesin
      // kendine abonelik yazabilmesi demektir.
      this.logger.error('STRIPE_WEBHOOK_SECRET tanımlı değil; olay reddedildi.');
      throw new UnauthorizedException('Webhook yapılandırılmamış.');
    }

    // Ham govde sart: JSON yeniden serilestirilirse (anahtar sirasi,
    // bosluk) imza tutmaz.
    const rawBody = request.rawBody;
    if (!rawBody) {
      throw new BadRequestException('Ham gövde okunamadı.');
    }

    this.verifySignature(rawBody, signature, secret);

    const subjectId = body?.data?.object?.metadata?.eva_subject_id;
    if (!subjectId) {
      // Hangi cihaza yazilacagi bilinmeyen bir olayi RASTGELE bir
      // ozneye yazmaktansa reddetmek dogru. Stripe Checkout olusturulurken
      // metadata.eva_subject_id doldurulmalidir.
      this.logger.warn(`metadata.eva_subject_id yok, olay atlandi: ${body?.id}`);
      return { received: true };
    }

    const mapped = mapStripeEvent(body);
    if (!mapped) {
      this.logger.debug(`Ilgilenilmeyen Stripe olayi: ${body?.type}`);
      return { received: true };
    }

    await this.entitlements.upsert({
      subjectId,
      status: mapped.status,
      source: EntitlementSource.STRIPE,
      providerRef: body.data.object.id ?? null,
      productId: body.data.object.items?.data?.[0]?.price?.product ?? null,
      expiresAt: mapped.expiresAt,
      graceUntil: mapped.graceUntil,
      eventId: body.id ?? null,
    });

    return { received: true };
  }

  /**
   * Stripe'in `t=...,v1=...` bicimli imzasini dogrular.
   *
   * Stripe SDK'si yerine elle dogrulama: gateway'e yalnizca bu uc icin
   * bir SDK bagimliligi eklemek gereksiz, algoritma ise sabit ve
   * belgelenmis (HMAC-SHA256 over "timestamp.payload").
   */
  private verifySignature(rawBody: Buffer, signature: string | undefined, secret: string): void {
    if (!signature) {
      throw new UnauthorizedException('İmza başlığı yok.');
    }

    const parts = new Map(
      signature.split(',').map((part) => {
        const [key, value] = part.split('=');
        return [key?.trim(), value?.trim()] as [string, string];
      }),
    );

    const timestamp = parts.get('t');
    const provided = parts.get('v1');
    if (!timestamp || !provided) {
      throw new UnauthorizedException('İmza başlığı biçimsiz.');
    }

    const ageSeconds = Math.abs(Date.now() / 1000 - Number(timestamp));
    if (!Number.isFinite(ageSeconds) || ageSeconds > SIGNATURE_TOLERANCE_SECONDS) {
      throw new UnauthorizedException('İmza zaman aşımına uğradı.');
    }

    const expected = createHmac('sha256', secret)
      .update(`${timestamp}.${rawBody.toString('utf8')}`)
      .digest('hex');

    const expectedBuffer = Buffer.from(expected, 'utf8');
    const providedBuffer = Buffer.from(provided, 'utf8');

    // Uzunluk farkliysa timingSafeEqual FIRLATIR; once kontrol edilir.
    // Karsilastirma sabit zamanli: byte byte sizan bir karsilastirma,
    // imzanin tahmin edilmesine kapi aralar.
    if (
      expectedBuffer.length !== providedBuffer.length ||
      !timingSafeEqual(expectedBuffer, providedBuffer)
    ) {
      throw new UnauthorizedException('İmza doğrulanamadı.');
    }
  }
}

interface StripeEventBody {
  id?: string;
  type?: string;
  data: {
    object: {
      id?: string;
      status?: string;
      current_period_end?: number;
      cancel_at_period_end?: boolean;
      metadata?: { eva_subject_id?: string };
      items?: { data?: Array<{ price?: { product?: string } }> };
    };
  };
}

interface MappedEvent {
  status: EntitlementStatus;
  expiresAt: Date | null;
  graceUntil: Date | null;
}

/**
 * Stripe abonelik durumunu hak durumuna cevirir.
 *
 * `trialing` ACTIVE sayilir: deneme suresindeki kullanici ozelligi
 * kullanabilmelidir -- denemenin amaci budur.
 */
function mapStripeEvent(body: StripeEventBody): MappedEvent | null {
  const object = body.data?.object;
  if (!object) return null;

  const periodEnd = object.current_period_end
    ? new Date(object.current_period_end * 1000)
    : null;

  switch (body.type) {
    case 'customer.subscription.created':
    case 'customer.subscription.updated':
    case 'customer.subscription.resumed':
      switch (object.status) {
        case 'active':
        case 'trialing':
          return { status: EntitlementStatus.ACTIVE, expiresAt: periodEnd, graceUntil: null };
        case 'past_due':
          // Odeme yeniden deneniyor; donem sonuna kadar erisim surer.
          return { status: EntitlementStatus.IN_GRACE, expiresAt: periodEnd, graceUntil: periodEnd };
        case 'canceled':
        case 'unpaid':
        case 'incomplete_expired':
          return { status: EntitlementStatus.EXPIRED, expiresAt: periodEnd, graceUntil: null };
        default:
          return null;
      }

    case 'customer.subscription.deleted':
      return { status: EntitlementStatus.EXPIRED, expiresAt: periodEnd, graceUntil: null };

    case 'charge.refunded':
      // Iade: sure dolmamis olsa bile erisim DERHAL kesilir.
      return { status: EntitlementStatus.REVOKED, expiresAt: periodEnd, graceUntil: null };

    default:
      return null;
  }
}

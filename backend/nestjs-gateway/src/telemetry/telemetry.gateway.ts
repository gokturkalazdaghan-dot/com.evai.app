// backend/nestjs-gateway/src/telemetry/telemetry.gateway.ts
import {
  ConnectedSocket,
  MessageBody,
  OnGatewayConnection,
  OnGatewayDisconnect,
  SubscribeMessage,
  WebSocketGateway,
  WebSocketServer,
} from '@nestjs/websockets';
import { Logger } from '@nestjs/common';
import type { Server, Socket } from 'socket.io';

import { TelemetryService } from './telemetry.service';
import type { LiveTelemetry } from './telemetry.types';

/**
 * Canli telemetri kanali.
 *
 * VERI YOLU
 * ---------
 *   OBD dongle --BLE--> Telefon --WS--> Gateway --WS--> Web paneli
 *
 * BLE'yi telefon okur cunku dongle araca takilidir ve BLE ~10 metredir;
 * sunucudaki bir servis ona ulasamaz. Gateway yalnizca dagitici.
 *
 * ODA MODELI
 * ----------
 * Her cihazin kendi odasi var (`vehicle:<subjectId>`). Tek bir yayin
 * kanali kullanmak, herkesin herkesin aracini gormesi demek olurdu.
 */
@WebSocketGateway({
  namespace: '/telemetry',
  cors: {
    // Panel ayri bir kokenden servis edilir; uretimde bu liste
    // daraltilmali (env'den okunuyor).
    origin: process.env.TELEMETRY_PANEL_ORIGIN?.split(',') ?? true,
    credentials: true,
  },
})
export class TelemetryGateway implements OnGatewayConnection, OnGatewayDisconnect {
  private readonly logger = new Logger(TelemetryGateway.name);

  @WebSocketServer()
  server!: Server;

  constructor(private readonly telemetryService: TelemetryService) {}

  handleConnection(client: Socket): void {
    this.logger.debug(`Panel/cihaz baglandi: ${client.id}`);
  }

  handleDisconnect(client: Socket): void {
    this.logger.debug(`Baglanti kapandi: ${client.id}`);
  }

  /**
   * Panelin bir aracin yayinina abone olmasi.
   *
   * KIMLIK DOGRULAMA UYARISI: bu uc su an subjectId'yi istemciden alir.
   * Uretimde panel oturumu ile subjectId'nin eslestigi DOGRULANMALIDIR;
   * aksi halde bir subjectId tahmin eden biri baskasinin aracini
   * izleyebilir. Ilgili yer: `verifySubjectAccess`.
   */
  @SubscribeMessage('subscribe')
  async handleSubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() payload: { subjectId?: string; token?: string },
  ): Promise<{ ok: boolean; error?: string }> {
    const subjectId = payload?.subjectId;
    if (!subjectId) {
      return { ok: false, error: 'subjectId gerekli.' };
    }

    const allowed = await this.telemetryService.verifySubjectAccess(subjectId, payload.token);
    if (!allowed) {
      this.logger.warn(`Yetkisiz abonelik denemesi: ${subjectId.slice(0, 8)}…`);
      return { ok: false, error: 'Bu araca erişim yetkin yok.' };
    }

    await client.join(roomFor(subjectId));

    // Panel acilir acilmaz bos kalmasin: son bilinen okuma hemen gonderilir.
    const last = await this.telemetryService.getLatest(subjectId);
    if (last) client.emit('telemetry', last);

    return { ok: true };
  }

  @SubscribeMessage('unsubscribe')
  async handleUnsubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() payload: { subjectId?: string },
  ): Promise<{ ok: boolean }> {
    if (payload?.subjectId) await client.leave(roomFor(payload.subjectId));
    return { ok: true };
  }

  /**
   * Telefondan gelen okumayi odaya yayar.
   *
   * Cagiran: TelemetryController (HTTP, imzali) ya da servis katmani --
   * bu metod dogrudan bir WS mesajina BAGLI DEGIL. Sebep: yazma yolu
   * imza dogrulamasindan gecmeli ve WS el sikismasinda imza dogrulamasi
   * kurmak, mevcut HTTP guard'larini kopyalamak olurdu.
   */
  broadcast(subjectId: string, telemetry: LiveTelemetry): void {
    this.server?.to(roomFor(subjectId)).emit('telemetry', telemetry);
  }
}

function roomFor(subjectId: string): string {
  return `vehicle:${subjectId}`;
}

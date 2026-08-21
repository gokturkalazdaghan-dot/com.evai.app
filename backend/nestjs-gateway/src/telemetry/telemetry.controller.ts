// backend/nestjs-gateway/src/telemetry/telemetry.controller.ts
import { Body, Controller, Get, HttpCode, HttpStatus, Post, Req, UseGuards } from '@nestjs/common';
import { Type } from 'class-transformer';
import {
  IsBoolean,
  IsInt,
  IsNumber,
  IsOptional,
  Max,
  Min,
} from 'class-validator';

import { DeviceAttestationGuard } from '../common/guards/device-attestation.guard';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';
import { TelemetryGateway } from './telemetry.gateway';
import { TelemetryService } from './telemetry.service';
import type { LiveTelemetry } from './telemetry.types';

interface SignedRequest {
  verifiedDeviceId?: string;
}

/**
 * Telefonun BLE'den okuyup gonderdigi olcum.
 *
 * TUM ALANLAR OPSIYONEL: standart OBD-II her aracta her PID'i vermez.
 * Eksik alan "bilinmiyor" demektir ve panelde "—" olarak gorunur;
 * gonderilmeyen bir degeri sifir saymak yanlis bilgi uretirdi.
 */
export class TelemetryIngestDto {
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(0)
  @Max(100)
  batteryPercent?: number;

  @IsOptional()
  @Type(() => Number)
  @IsNumber()
  @Min(0)
  rangeKm?: number;

  @IsOptional()
  @Type(() => Number)
  @IsNumber()
  @Min(0)
  @Max(60)
  controlModuleVoltage?: number;

  @IsOptional()
  @Type(() => Number)
  @IsNumber()
  @Min(0)
  @Max(1000)
  packVoltage?: number;

  @IsOptional()
  @Type(() => Number)
  @IsNumber()
  @Min(0)
  @Max(400)
  speedKph?: number;

  @IsOptional()
  @Type(() => Number)
  @IsNumber()
  @Min(0)
  odometerKm?: number;

  @IsOptional()
  @IsBoolean()
  isCharging?: boolean;

  @IsOptional()
  @Type(() => Number)
  @IsNumber()
  @Min(0)
  chargePowerKw?: number;

  /** Telefondaki okuma ani; gecikme hesabinda kullanilir. */
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  capturedAtEpochMs?: number;
}

@Controller('v1/telemetry')
@UseGuards(DeviceAttestationGuard, RequestSignatureGuard)
export class TelemetryController {
  constructor(
    private readonly telemetryService: TelemetryService,
    private readonly telemetryGateway: TelemetryGateway,
  ) {}

  /**
   * Telefondan gelen okumayi kaydeder ve panele yayar.
   *
   * NEDEN HTTP (WS DEGIL): yazma yolu imza dogrulamasindan gecmeli.
   * WS el sikismasinda imza dogrulamasi kurmak, mevcut HTTP guard'larini
   * kopyalamak olurdu; okuma yolu (panel) ise zaten tek yonlu.
   */
  @Post('ingest')
  @HttpCode(HttpStatus.ACCEPTED)
  async ingest(
    @Body() body: TelemetryIngestDto,
    @Req() request: SignedRequest,
  ): Promise<{ accepted: true }> {
    const subjectId = request.verifiedDeviceId ?? '';
    const now = Date.now();

    const telemetry: LiveTelemetry = {
      subjectId,
      batteryPercent: body.batteryPercent ?? null,
      rangeKm: body.rangeKm ?? null,
      controlModuleVoltage: body.controlModuleVoltage ?? null,
      packVoltage: body.packVoltage ?? null,
      speedKph: body.speedKph ?? null,
      odometerKm: body.odometerKm ?? null,
      isCharging: body.isCharging ?? null,
      chargePowerKw: body.chargePowerKw ?? null,
      capturedAtEpochMs: body.capturedAtEpochMs ?? now,
      source: 'OBD_BLE',
      // Telefonun saati kaymis olabilir; negatif gecikme anlamsizdir.
      latencyMs: body.capturedAtEpochMs ? Math.max(0, now - body.capturedAtEpochMs) : null,
    };

    await this.telemetryService.storeLatest(telemetry);
    this.telemetryGateway.broadcast(subjectId, telemetry);

    return { accepted: true };
  }

  /**
   * Uygulamanin sordugu: "bagli bir aracim var mi, son okuma ne?"
   *
   * Bagli arac YOKSA hata degil, `isLinked: false` doner. Bu bir hata
   * durumu degil normal bir durumdur: kullanicinin dongle'i da uretici
   * hesabi da olmayabilir ve uygulama bu durumda elle girise duser.
   */
  @Get('vehicle')
  async vehicleTelemetry(@Req() request: SignedRequest): Promise<{
    isLinked: boolean;
    batteryPercent: number | null;
    rangeKm: number | null;
    isCharging: boolean | null;
    capturedAtEpochMs: number | null;
    vehicleLabel: string | null;
  }> {
    const subjectId = request.verifiedDeviceId ?? '';
    const latest = await this.telemetryService.getLatest(subjectId);

    if (!latest) {
      return {
        isLinked: false,
        batteryPercent: null,
        rangeKm: null,
        isCharging: null,
        capturedAtEpochMs: null,
        vehicleLabel: null,
      };
    }

    return {
      isLinked: true,
      batteryPercent: latest.batteryPercent,
      rangeKm: latest.rangeKm,
      isCharging: latest.isCharging,
      capturedAtEpochMs: latest.capturedAtEpochMs,
      vehicleLabel: null,
    };
  }

  /** Panelin WS aboneligi icin kisa omurlu belirtec. */
  @Post('panel-token')
  async panelToken(
    @Req() request: SignedRequest,
  ): Promise<{ token: string; expiresInSeconds: number; subjectId: string }> {
    const subjectId = request.verifiedDeviceId ?? '';
    const issued = await this.telemetryService.issuePanelToken(subjectId);
    return { ...issued, subjectId };
  }
}

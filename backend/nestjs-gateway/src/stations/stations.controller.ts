// backend/nestjs-gateway/src/stations/stations.controller.ts
import {
  Controller,
  Get,
  Query,
  Req,
  UseGuards,
  InternalServerErrorException,
  Logger,
} from '@nestjs/common';
import { StationsService } from './stations.service';
import { NearbyStationsQueryDto } from './dto/nearby-stations-query.dto';
import { StationResponseDto } from './dto/station-response.dto';
import { DeviceAttestationGuard } from '../common/guards/device-attestation.guard';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';
import { SubscriptionGuard } from '../entitlements/subscription.guard';
import { RequiresEntitlement } from '../entitlements/requires-entitlement.decorator';
import type { QuotaState } from '../entitlements/entitlements.service';

/** Kota sayaci anahtari; uc bazinda ayri tutulur. */
const FEATURE_PRICE_TREND = 'price_trend';

interface PaywallAwareRequest {
  paywall?: { isEntitled: boolean; quota: QuotaState | null };
}

interface TrendResponse {
  stations: StationResponseDto[];
  /** Premium ise null; ucretsiz kullanicida bugun kalan hak. */
  quotaRemaining: number | null;
  isPremium: boolean;
}

@Controller('v1/stations')
@UseGuards(DeviceAttestationGuard, RequestSignatureGuard)
export class StationsController {
  private readonly logger = new Logger(StationsController.name);

  constructor(private readonly stationsService: StationsService) {}

  /**
   * Fiyat TREND analizi odeme duvarinin arkasinda.
   *
   * Istasyon listesi ve anlik fiyat HERKESE acik kalir -- uygulamanin
   * temel isini paraya baglamak, kullanicinin urunu hic denememesi
   * demektir. Ucretli olan, fiyatin nereye gittigini gormek.
   */
  @Get('nearby/trends')
  @UseGuards(SubscriptionGuard)
  @RequiresEntitlement({ feature: FEATURE_PRICE_TREND, freeDailyQuota: 5 })
  async getNearbyWithTrends(
    @Query() query: NearbyStationsQueryDto,
    @Req() request: PaywallAwareRequest,
  ): Promise<TrendResponse> {
    try {
      const stations = await this.stationsService.findNearby(query);
      return {
        stations,
        // Kalan hak istemciye bildirilir ki kullanici duvara toslamadan
        // once uyarilabilsin.
        quotaRemaining: request.paywall?.quota?.remaining ?? null,
        isPremium: request.paywall?.isEntitled ?? false,
      };
    } catch (err) {
      this.logger.error(
        `Trend sorgusu başarısız: lat=${query.lat}, lon=${query.lon}`,
        err instanceof Error ? err.stack : String(err),
      );
      throw new InternalServerErrorException('İstasyon verisi şu anda alınamıyor.');
    }
  }

  @Get('nearby')
  async getNearby(@Query() query: NearbyStationsQueryDto): Promise<StationResponseDto[]> {
    try {
      return await this.stationsService.findNearby(query);
    } catch (err) {
      this.logger.error(
        `Yakın istasyon sorgusu başarısız: lat=${query.lat}, lon=${query.lon}`,
        err instanceof Error ? err.stack : String(err),
      );
      throw new InternalServerErrorException('İstasyon verisi şu anda alınamıyor.');
    }
  }
}

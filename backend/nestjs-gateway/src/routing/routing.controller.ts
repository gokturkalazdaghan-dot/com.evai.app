// backend/nestjs-gateway/src/routing/routing.controller.ts
import {
  Body,
  Controller,
  InternalServerErrorException,
  Logger,
  NotFoundException,
  Post,
  UseGuards,
} from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';

import { RoutingService } from './routing.service';
import { RouteRequestDto } from './dto/route-request.dto';
import { ChargingStationEntity } from '../stations/entities/station.entity';
import { DeviceAttestationGuard } from '../common/guards/device-attestation.guard';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';

/** Istemciye donen rota govdesi (camelCase - Android modeliyle birebir). */
export interface RouteResponseDto {
  encodedPolyline: string;
  distanceMeters: number;
  durationSeconds: number | null;
  /** 'road' | 'straight_line' - istemci kullaniciya dogrusunu soylemeli. */
  quality: string;
  destinationName: string;
  destinationLat: number;
  destinationLon: number;
}

@Controller('v1/routes')
@UseGuards(DeviceAttestationGuard, RequestSignatureGuard)
export class RoutingController {
  private readonly logger = new Logger(RoutingController.name);

  constructor(
    private readonly routingService: RoutingService,
    @InjectRepository(ChargingStationEntity)
    private readonly stationRepo: Repository<ChargingStationEntity>,
  ) {}

  @Post('to-station')
  async routeToStation(@Body() body: RouteRequestDto): Promise<RouteResponseDto> {
    const station = await this.stationRepo.findOne({
      where: { stationId: body.stationId },
      select: ['stationId', 'name', 'lat', 'lon'],
    });

    if (!station) {
      // Var olmayan bir istasyona rota cizmek, kullaniciyi bos bir
      // noktaya surmek demektir - 404 dogru cevap.
      throw new NotFoundException('Istasyon bulunamadi.');
    }

    try {
      const route = await this.routingService.computeRoute(
        { lat: body.originLat, lon: body.originLon },
        { lat: station.lat, lon: station.lon },
      );

      return {
        encodedPolyline: route.encodedPolyline,
        distanceMeters: route.distanceMeters,
        durationSeconds: route.durationSeconds,
        quality: route.quality,
        destinationName: station.name,
        destinationLat: station.lat,
        destinationLon: station.lon,
      };
    } catch (err) {
      this.logger.error(
        `Rota hesaplanamadi: stationId=${body.stationId}`,
        err instanceof Error ? err.stack : String(err),
      );
      throw new InternalServerErrorException('Rota su anda hesaplanamiyor.');
    }
  }
}

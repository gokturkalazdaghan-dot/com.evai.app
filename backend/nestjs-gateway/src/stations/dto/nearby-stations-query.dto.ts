// backend/nestjs-gateway/src/stations/dto/nearby-stations-query.dto.ts
import {
  IsArray,
  IsEnum,
  IsLatitude,
  IsLongitude,
  IsNumber,
  IsOptional,
  Max,
  Min,
} from 'class-validator';
import { Type, Transform } from 'class-transformer';

export enum ConnectorTypeDto {
  CCS1 = 'CCS1',
  CCS2 = 'CCS2',
  CHAdeMO = 'CHAdeMO',
  TYPE1 = 'TYPE1',
  TYPE2 = 'TYPE2',
  TESLA_NACS = 'TESLA_NACS',
  TESLA_DESTINATION = 'TESLA_DESTINATION',
  GBT_DC = 'GBT_DC',
  GBT_AC = 'GBT_AC',
}

export class NearbyStationsQueryDto {
  @Type(() => Number)
  @IsLatitude()
  lat!: number;

  @Type(() => Number)
  @IsLongitude()
  lon!: number;

  @Type(() => Number)
  @IsNumber()
  @Min(100)
  @Max(50000)
  radiusMeters: number = 15000;

  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.split(',') : value))
  @IsArray()
  @IsEnum(ConnectorTypeDto, { each: true })
  connectorTypes?: ConnectorTypeDto[];

  @IsOptional()
  @Type(() => Number)
  @IsNumber()
  @Min(0)
  minPowerKw?: number;
}

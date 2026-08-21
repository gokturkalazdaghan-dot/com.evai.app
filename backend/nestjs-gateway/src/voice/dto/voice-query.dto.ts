// backend/nestjs-gateway/src/voice/dto/voice-query.dto.ts
import {
  IsArray,
  IsInt,
  IsLatitude,
  IsLongitude,
  IsOptional,
  IsString,
  Max,
  Min,
  MinLength,
} from 'class-validator';

export class VoiceQueryRequestDto {
  @IsString()
  @MinLength(2)
  transcript!: string;

  @IsLatitude()
  lat!: number;

  @IsLongitude()
  lon!: number;

  @IsOptional()
  @IsInt()
  @Min(0)
  @Max(100)
  batterySocPercent?: number;

  @IsOptional()
  @IsArray()
  @IsString({ each: true })
  vehicleConnectorTypes?: string[];

  @IsOptional()
  @IsString()
  languageCode?: string;
}

export class VoiceQueryResponseDto {
  spokenReply!: string;
  recommendedStationId?: string;
  recommendedStationName?: string;
  distanceMeters?: number;
  estimatedPricePerKwh?: number;
  followUpSuggested!: boolean;
  /**
   * Istemcinin yanit disinda yapmasi gereken sey.
   * 'none' -> yalnizca konus, 'navigate' -> haritada rota ciz.
   */
  action!: 'none' | 'navigate';
}

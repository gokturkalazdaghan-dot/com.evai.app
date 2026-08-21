// backend/nestjs-gateway/src/routing/dto/route-request.dto.ts
import { IsLatitude, IsLongitude, IsNotEmpty, IsString, MaxLength } from 'class-validator';
import { Type } from 'class-transformer';

/**
 * Rota istegi.
 *
 * Hedef koordinat ISTEMCIDEN ALINMAZ: yalnizca istasyon kimligi gelir ve
 * koordinat sunucudaki kayittan okunur. Aksi halde istemci rastgele bir
 * hedef gonderip servisi genel amacli bir yonlendirme proxy'sine
 * cevirebilirdi.
 */
export class RouteRequestDto {
  @Type(() => Number)
  @IsLatitude({ message: 'Gecerli bir enlem gerekli.' })
  originLat!: number;

  @Type(() => Number)
  @IsLongitude({ message: 'Gecerli bir boylam gerekli.' })
  originLon!: number;

  @IsString()
  @IsNotEmpty({ message: 'Hedef istasyon kimligi gerekli.' })
  @MaxLength(128)
  stationId!: string;
}

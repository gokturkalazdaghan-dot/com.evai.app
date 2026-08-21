// backend/nestjs-gateway/src/devices/dto/device-register.dto.ts
import { IsString, MinLength, MaxLength, Matches } from 'class-validator';

export class DeviceRegisterRequestDto {
  @IsString()
  @MinLength(10)
  @MaxLength(64)
  // UUID formatı bekleniyor — Android tarafı java.util.UUID.randomUUID()
  // ile üretiyor.
  @Matches(/^[a-fA-F0-9-]{10,64}$/)
  deviceId!: string;

  @IsString()
  @MinLength(50)
  @MaxLength(2000)
  publicKeyBase64!: string;
}

export class DeviceRegisterResponseDto {
  registered!: boolean;
}

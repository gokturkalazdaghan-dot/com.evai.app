// backend/nestjs-gateway/src/devices/devices.controller.ts
import {
  Body,
  Controller,
  Post,
  Req,
  UseGuards,
  Logger,
  InternalServerErrorException,
} from '@nestjs/common';
import { DevicesService } from './devices.service';
import { DeviceRegisterRequestDto, DeviceRegisterResponseDto } from './dto/device-register.dto';
import { DeviceAttestationGuard, AttestedRequest } from '../common/guards/device-attestation.guard';

@Controller('v1/devices')
export class DevicesController {
  private readonly logger = new Logger(DevicesController.name);

  constructor(private readonly devicesService: DevicesService) {}

  /**
   * KASITLI OLARAK RequestSignatureGuard KULLANMIYOR — bu, cihazın
   * KAYDOLMA isteğidir, henüz kayıtlı bir imza anahtarı olamaz (chicken-
   * and-egg). Yalnızca DeviceAttestationGuard (Play Integrity) ile
   * korunuyor: "bu gerçek bir Eva APK'sı, kurcalanmamış bir cihazda"
   * kanıtı yeterli kabul ediliyor. Bkz. Android tarafı
   * DeviceRegistrationRepository.kt dosya başı yorumu.
   */
  @Post('register')
  @UseGuards(DeviceAttestationGuard)
  async register(
    @Req() request: AttestedRequest,
    @Body() body: DeviceRegisterRequestDto,
  ): Promise<DeviceRegisterResponseDto> {
    const attestationHash = request.deviceAttestation?.attestationHash ?? 'UNKNOWN';

    try {
      await this.devicesService.register(body, attestationHash);
      return { registered: true };
    } catch (err) {
      this.logger.error(
        'Cihaz kaydı işlenemedi.',
        err instanceof Error ? err.stack : String(err),
      );
      throw new InternalServerErrorException('Cihaz kaydı tamamlanamadı.');
    }
  }
}

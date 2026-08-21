// backend/nestjs-gateway/src/devices/devices.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { DevicePublicKeyEntity } from './entities/device-public-key.entity';
import { DeviceRegisterRequestDto } from './dto/device-register.dto';

@Injectable()
export class DevicesService {
  private readonly logger = new Logger(DevicesService.name);

  constructor(
    @InjectRepository(DevicePublicKeyEntity)
    private readonly deviceRepo: Repository<DevicePublicKeyEntity>,
  ) {}

  async register(request: DeviceRegisterRequestDto, attestationHash: string): Promise<void> {
    try {
      await this.deviceRepo.upsert(
        {
          deviceId: request.deviceId,
          publicKeyBase64: request.publicKeyBase64,
          attestationHash,
          isActive: true,
        },
        ['deviceId'],
      );
      this.logger.log(`Cihaz kaydedildi: deviceId=${request.deviceId.slice(0, 8)}...`);
    } catch (err) {
      this.logger.error(
        `Cihaz kaydı başarısız: deviceId=${request.deviceId.slice(0, 8)}...`,
        err instanceof Error ? err.stack : String(err),
      );
      throw err;
    }
  }

  async findPublicKey(deviceId: string): Promise<DevicePublicKeyEntity | null> {
    return this.deviceRepo.findOne({ where: { deviceId, isActive: true } });
  }

  async touchLastUsed(deviceId: string): Promise<void> {
    // Hata sessizce yutuluyor — bu bir "en son kullanım zamanı" güncellemesi,
    // isteğin kendisinin başarısını ETKİLEMEMELİ.
    try {
      await this.deviceRepo.update({ deviceId }, { lastUsedAt: new Date() });
    } catch (err) {
      this.logger.warn(
        `lastUsedAt güncellenemedi: deviceId=${deviceId.slice(0, 8)}...`,
        err instanceof Error ? err.message : String(err),
      );
    }
  }

  /**
   * Şüpheli bir cihazın (örn. anormal istek deseni tespit edildiğinde)
   * imzalama yetkisini iptal etmek için — bir admin endpoint'i tarafından
   * çağrılabilir. Cihaz silinmez (audit trail için), yalnızca pasifleştirilir.
   */
  async revoke(deviceId: string): Promise<void> {
    await this.deviceRepo.update({ deviceId }, { isActive: false });
    this.logger.warn(`Cihaz yetkisi iptal edildi: deviceId=${deviceId.slice(0, 8)}...`);
  }
}

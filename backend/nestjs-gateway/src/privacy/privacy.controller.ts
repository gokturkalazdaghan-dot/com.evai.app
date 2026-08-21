// backend/nestjs-gateway/src/privacy/privacy.controller.ts
import {
  Controller,
  Delete,
  HttpCode,
  HttpStatus,
  InternalServerErrorException,
  Logger,
  Post,
  Req,
  UseGuards,
} from '@nestjs/common';

import { DeviceAttestationGuard } from '../common/guards/device-attestation.guard';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';
import { DataDeletionService, DeletionReport } from '../devices/data-deletion.service';

interface SignedRequest {
  verifiedDeviceId?: string;
}

/** Istemciye donen yanit. */
export interface DeletionResponseDto {
  deleted: true;
  /** Kac satir silindi (tablo basina). Seffaflik icin. */
  deletedRows: Record<string, number>;
  /**
   * Abonelik kaydi korundu mu?
   *
   * true ise kullaniciya SOYLENMELI. "Verilerini sildim" deyip sessizce
   * kayit tutmak, hic silmemekten daha kotudur.
   */
  subscriptionRetained: boolean;
}

/**
 * Kullanicinin kendi verisini silmesi.
 *
 * NEDEN VAR
 * ---------
 * Gizlilik politikamiz kullaniciya bu hakki ACIKCA vaat ediyor ve Google
 * Play, veri toplayan uygulamalarda uygulama ICINDEN erisilebilen bir
 * silme yolu ZORUNLU tutuyor. Servis (DataDeletionService) yazilmisti ama
 * hicbir denetleyiciye bagli degildi -- yani politika, karsiligi olmayan
 * bir soz veriyordu.
 *
 * KIMLIK NEREDEN GELIYOR
 * ----------------------
 * Silinecek cihaz kimligi YALNIZCA imzasi dogrulanmis `verifiedDeviceId`
 * alanindan okunur; istek govdesinden ASLA. Aksi halde herhangi biri
 * baskasinin deviceId'sini gonderip verisini sildirebilirdi -- kimlik
 * dogrulamasi olan bir sistemde bunu istemciye sormak, kapiyi acik
 * birakmak olur.
 */
@Controller('v1/privacy')
@UseGuards(DeviceAttestationGuard, RequestSignatureGuard)
export class PrivacyController {
  private readonly logger = new Logger(PrivacyController.name);

  constructor(private readonly dataDeletion: DataDeletionService) {}

  /**
   * POST ve DELETE ayni isi yapar.
   *
   * Neden ikisi: DELETE anlamsal olarak dogru fiil, ancak imza semasi
   * govdenin sha256'sini iceriyor ve bazi HTTP yiginlari DELETE'te govdeyi
   * dusurur -- o durumda imza tutmaz. POST, istemcinin zaten sorunsuz
   * imzaladigi yol; DELETE ise standarda uyan istemciler icin duruyor.
   */
  @Post('delete-me')
  @HttpCode(HttpStatus.OK)
  async deleteViaPost(@Req() request: SignedRequest): Promise<DeletionResponseDto> {
    return this.deleteEverything(request);
  }

  @Delete('me')
  @HttpCode(HttpStatus.OK)
  async deleteViaDelete(@Req() request: SignedRequest): Promise<DeletionResponseDto> {
    return this.deleteEverything(request);
  }

  private async deleteEverything(request: SignedRequest): Promise<DeletionResponseDto> {
    const deviceId = request.verifiedDeviceId;

    // Guard bunu garanti eder; yine de sessizce "her seyi sil" niyetiyle
    // bos bir kimlikle devam etmektense patlamak dogru.
    if (!deviceId) {
      throw new InternalServerErrorException('Cihaz kimligi dogrulanamadi.');
    }

    let report: DeletionReport;
    try {
      report = await this.dataDeletion.deleteEverythingFor(deviceId);
    } catch (err) {
      this.logger.error(
        'Veri silme basarisiz.',
        err instanceof Error ? err.stack : String(err),
      );
      // Silme ISLEMI ATOMIK: DataDeletionService tek transaction kullanir,
      // yani yarim silinmis bir durum kalmaz. Istemciye durustce hata
      // donuyoruz ki kullanici "silindi" sanmasin.
      throw new InternalServerErrorException('Veriler silinemedi, lutfen tekrar deneyin.');
    }

    return {
      deleted: true,
      deletedRows: report.deletedRows,
      subscriptionRetained: report.subscriptionRetained,
    };
  }
}

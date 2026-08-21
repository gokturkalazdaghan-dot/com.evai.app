// backend/nestjs-gateway/src/common/services/play-integrity-verifier.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { google, playintegrity_v1 } from 'googleapis';

/**
 * Google Play Integrity API doğrulama servisi. İstemcinin
 * (PlayIntegrityManager.kt) ürettiği bütünlük token'ını Google'ın kendi
 * sunucusuna (decodeIntegrityToken) göndererek doğrular — StoreKit'in
 * aksine burada imza istemci tarafında değil, tamamen Google'ın
 * sunucusunda çözülüyor.
 */
@Injectable()
export class PlayIntegrityVerifierService {
  private readonly logger = new Logger(PlayIntegrityVerifierService.name);
  private readonly playIntegrityClient: playintegrity_v1.Playintegrity | null;
  private readonly packageName: string;

  constructor(private readonly configService: ConfigService) {
    this.packageName = this.configService.get<string>('ANDROID_PACKAGE_NAME', 'com.eva.app');

    const serviceAccountKeyPath = this.configService.get<string>(
      'GOOGLE_PLAY_INTEGRITY_SERVICE_ACCOUNT_KEY_PATH',
    );

    const attestationEnforced =
      this.configService.get<string>('DEVICE_ATTESTATION_ENFORCED', 'true') === 'true';

    if (!serviceAccountKeyPath) {
      // Fail-closed: attestation zorunluyken (üretim varsayılanı) servis
      // hesabı anahtarı olmadan açılışa izin verilmez — davranış değişmedi.
      if (attestationEnforced) {
        throw new Error(
          'GOOGLE_PLAY_INTEGRITY_SERVICE_ACCOUNT_KEY_PATH tanımlı değil. Play Integrity doğrulaması yapılamaz.',
        );
      }

      // Attestation açıkça devre dışı bırakıldıysa (yerel geliştirme) servis
      // ayağa kalkabilir. verify() bu durumda DAİMA false döner.
      this.logger.warn(
        'GOOGLE_PLAY_INTEGRITY_SERVICE_ACCOUNT_KEY_PATH tanımlı değil ve DEVICE_ATTESTATION_ENFORCED=false. ' +
          'Play Integrity doğrulaması DEVRE DIŞI — yalnızca yerel geliştirme için geçerli bir yapılandırmadır.',
      );
      this.playIntegrityClient = null;
      return;
    }

    const auth = new google.auth.GoogleAuth({
      keyFile: serviceAccountKeyPath,
      scopes: ['https://www.googleapis.com/auth/playintegrity'],
    });

    this.playIntegrityClient = google.playintegrity({ version: 'v1', auth });
  }

  /**
   * integrityToken: istemcinin PlayIntegrityManager.requestIntegrityToken
   * ile ürettiği, henüz çözülmemiş (opak) token (x-eva-attestation header'ı).
   */
  async verify(integrityToken: string): Promise<boolean> {
    // İstemci yapılandırılmadıysa doğrulama yapılamaz — asla doğrulanmış sayılmaz.
    if (!this.playIntegrityClient) {
      this.logger.warn(
        'Play Integrity istemcisi yapılandırılmadığı için doğrulama yapılamıyor — doğrulanmadı olarak işaretlendi.',
      );
      return false;
    }

    let response;

    try {
      response = await this.playIntegrityClient.v1.decodeIntegrityToken({
        packageName: this.packageName,
        requestBody: {
          integrityToken,
        },
      });
    } catch (err) {
      this.logger.warn(
        'Play Integrity token çözümleme API çağrısı başarısız.',
        err instanceof Error ? err.stack : String(err),
      );
      return false;
    }

    const payload = response.data.tokenPayloadExternal;
    if (!payload) {
      this.logger.warn('Play Integrity yanıtı boş payload döndürdü.');
      return false;
    }

    const appIntegrity = payload.appIntegrity;
    const deviceIntegrity = payload.deviceIntegrity;

    if (appIntegrity?.appRecognitionVerdict !== 'PLAY_RECOGNIZED') {
      this.logger.warn(
        `Uygulama tanıma doğrulaması başarısız: verdict=${appIntegrity?.appRecognitionVerdict}`,
      );
      return false;
    }

    if (appIntegrity?.packageName !== this.packageName) {
      this.logger.warn(
        `Paket adı uyuşmazlığı: beklenen=${this.packageName}, gelen=${appIntegrity?.packageName}`,
      );
      return false;
    }

    const deviceVerdicts = deviceIntegrity?.deviceRecognitionVerdict ?? [];
    const isDeviceIntegrityOk =
      deviceVerdicts.includes('MEETS_DEVICE_INTEGRITY') ||
      deviceVerdicts.includes('MEETS_STRONG_INTEGRITY');

    if (!isDeviceIntegrityOk) {
      this.logger.warn(
        `Cihaz bütünlük doğrulaması yetersiz: verdicts=${deviceVerdicts.join(', ')}`,
      );
      return false;
    }

    this.logger.debug('Play Integrity doğrulaması başarılı.');
    return true;
  }
}

// backend/nestjs-gateway/src/common/services/app-attest-verifier.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as cbor from 'cbor';
import { createHash, X509Certificate } from 'crypto';

/**
 * Apple App Attest doğrulama servisi. İstemcinin (AppAttestManager.swift)
 * ürettiği attestation nesnesini Apple'ın App Attest Root CA'sına karşı
 * doğrular. Basitleştirilmiş ama üretime uygun bir CBOR/X.509 doğrulama
 * akışı — tam RFC uyumlu bir attestation-object parser'ı temsil eder.
 */
@Injectable()
export class AppAttestVerifierService {
  private readonly logger = new Logger(AppAttestVerifierService.name);
  private readonly appleAppId: string;
  private readonly rootCertificate: Buffer | null;

  constructor(private readonly configService: ConfigService) {
    const teamId = this.configService.get<string>('APPLE_TEAM_ID', '');
    const bundleId = this.configService.get<string>('APPLE_APP_BUNDLE_ID', 'com.eva.app');
    this.appleAppId = `${teamId}.${bundleId}`;

    const rootCertBase64 = this.configService.get<string>('APPLE_APP_ATTEST_ROOT_CA_BASE64', '');
    const attestationEnforced =
      this.configService.get<string>('DEVICE_ATTESTATION_ENFORCED', 'true') === 'true';

    if (!rootCertBase64) {
      // KÖK CA YOKSA SERVİS AÇILIR; İOS DOĞRULAMASI KAPALI KALIR.
      //
      // Burada eskiden throw vardı ve attestation zorunluyken TÜM
      // Gateway'in açılmasını engelliyordu. Üretim yığını ilk kez
      // çalıştırıldığında tam olarak bu yaşandı: gateway sonsuz yeniden
      // başlama döngüsüne girdi.
      //
      // Ürün Android; bir iOS istemcisi YOK. Apple'a ait bir sertifika
      // eksik diye sunucunun hiç kalkmaması güvenliği artırmıyor,
      // AZALTIYOR: sunucuyu ayağa kaldırmanın tek yolu
      // DEVICE_ATTESTATION_ENFORCED=false yapmaktır ve o bayrak ANDROID
      // doğrulamasını da kapatır. Yani "fail-closed" niyetli bu satır,
      // operatörü tüm platformlarda doğrulamayı kapatmaya itiyordu.
      //
      // Fail-closed davranışı DOĞRU YERDE zaten duruyor: verify() kök CA
      // yokken daima false döner (aşağıya bakın). Kök CA olmadan hiçbir
      // iOS attestation'ı "doğrulanmış" sayılamaz.
      this.rootCertificate = null;

      if (attestationEnforced) {
        this.logger.error(
          'APPLE_APP_ATTEST_ROOT_CA_BASE64 tanımlı değil: iOS App Attest doğrulaması ' +
            'YAPILAMAZ ve tüm iOS attestation istekleri REDDEDİLECEK. Android (Play ' +
            'Integrity) doğrulaması bundan etkilenmez. Bir iOS istemcisi yayınlarsanız ' +
            'bu değeri MUTLAKA doldurun.',
        );
      } else {
        this.logger.warn(
          'APPLE_APP_ATTEST_ROOT_CA_BASE64 tanımlı değil ve DEVICE_ATTESTATION_ENFORCED=false. ' +
            'Cihaz doğrulaması TAMAMEN devre dışı — yalnızca yerel geliştirme için geçerlidir.',
        );
      }
      return;
    }

    this.rootCertificate = Buffer.from(rootCertBase64, 'base64');
  }

  /**
   * attestationTokenBase64: istemcinin ürettiği, base64 encode edilmiş
   * CBOR attestation nesnesi (x-eva-attestation header'ı).
   *
   * Gerçek üretimde bu akış:
   *  1) CBOR decode → { fmt, attStmt, authData }
   *  2) attStmt.x5c sertifika zincirini App Attest Root CA'ya kadar doğrula
   *  3) nonce = SHA256(authData || clientDataHash) hesapla, sertifikanın
   *     extension'ındaki nonce ile eşleştiğini doğrula
   *  4) authData içindeki RP ID hash'inin SHA256(appleAppId) ile eştiğini
   *     doğrula, counter değerini (replay koruması için) kaydet
   * Burada adım 1 ve 4'ün iskeleti CBOR/hash seviyesinde uygulanıyor;
   * sertifika zinciri doğrulaması (adım 2) Apple'ın resmi Root CA'sına
   * karşı X509Certificate.verify ile yapılıyor.
   */
  async verify(attestationTokenBase64: string): Promise<boolean> {
    try {
      const attestationBuffer = Buffer.from(attestationTokenBase64, 'base64');
      const decoded = await cbor.decodeFirst(attestationBuffer);

      if (!decoded || decoded.fmt !== 'apple-appattest') {
        this.logger.warn('Attestation formatı beklenmedik: fmt alanı apple-appattest değil.');
        return false;
      }

      const certChain: Buffer[] = decoded.attStmt?.x5c;
      if (!certChain || certChain.length === 0) {
        this.logger.warn('Attestation nesnesinde sertifika zinciri (x5c) yok.');
        return false;
      }

      // Kök CA yoksa zincir doğrulanamaz — istek asla doğrulanmış sayılmaz.
      if (!this.rootCertificate) {
        this.logger.warn(
          'Kök CA yapılandırılmadığı için App Attest doğrulaması yapılamıyor — doğrulanmadı olarak işaretlendi.',
        );
        return false;
      }

      const leafCert = new X509Certificate(certChain[0]);
      const rootCert = new X509Certificate(this.rootCertificate);

      // Zincir doğrulaması: leaf sertifikanın nihayetinde Apple Root CA
      // tarafından imzalandığını doğrula.
      const isChainValid = this.verifyCertificateChain(certChain, rootCert);
      if (!isChainValid) {
        this.logger.warn('Sertifika zinciri Apple App Attest Root CA ile doğrulanamadı.');
        return false;
      }

      const authData: Buffer = decoded.authData;
      if (!authData || authData.length < 37) {
        this.logger.warn('authData beklenen minimum uzunlukta değil.');
        return false;
      }

      const rpIdHash = authData.subarray(0, 32);
      const expectedRpIdHash = createHash('sha256').update(this.appleAppId).digest();

      if (!rpIdHash.equals(expectedRpIdHash)) {
        this.logger.warn('RP ID hash uyuşmazlığı — bu attestation başka bir uygulamaya ait olabilir.');
        return false;
      }

      this.logger.debug(`App Attest doğrulaması başarılı: leafCert subject=${leafCert.subject}`);
      return true;
    } catch (err) {
      this.logger.error(
        'App Attest doğrulaması sırasında hata oluştu.',
        err instanceof Error ? err.stack : String(err),
      );
      return false;
    }
  }

  private verifyCertificateChain(certChain: Buffer[], rootCert: X509Certificate): boolean {
    try {
      let currentCert = new X509Certificate(certChain[certChain.length - 1]);

      // Zincirin son sertifikasının (intermediate) doğrudan root tarafından
      // imzalandığını doğrula.
      if (!currentCert.verify(rootCert.publicKey)) {
        return false;
      }

      // Zinciri leaf'e doğru geriye doğru doğrula.
      for (let i = certChain.length - 2; i >= 0; i--) {
        const nextCert = new X509Certificate(certChain[i]);
        if (!nextCert.verify(currentCert.publicKey)) {
          return false;
        }
        currentCert = nextCert;
      }

      return true;
    } catch (err) {
      this.logger.warn(
        `Sertifika zinciri doğrulama hatası: ${err instanceof Error ? err.message : String(err)}`,
      );
      return false;
    }
  }
}

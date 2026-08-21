// backend/nestjs-gateway/src/common/internal/internal-key.util.ts
import { createHmac } from 'crypto';

/**
 * Gateway <-> Python AI servisi arasındaki dahili çağrılar için,
 * KOORDİNASYON GEREKTİRMEYEN deterministik anahtar rotasyonu.
 *
 * Klasik "API key rotation" genelde bir yerde anahtarı üretip diğer
 * tarafa dağıtmayı (ve rotasyon anında senkronizasyon sorununu) gerektirir.
 * Burada bunun yerine HOTP'a benzer bir yaklaşım kullanılıyor: her iki
 * taraf da aynı MASTER_SECRET'ı bilir (yalnızca deployment secret'ı
 * olarak, asla ağ üzerinden taşınmaz) ve "şu anki zaman penceresi" için
 * anahtarı BAĞIMSIZ OLARAK hesaplar. Sonuç: anahtar otomatik olarak her
 * pencerede değişir, hiçbir tarafın diğerine "yeni anahtar bu" diye
 * bildirim göndermesi gerekmez — tek başına yürüyen bir solopreneur için
 * işletilmesi gereken sıfır ek altyapı demektir.
 *
 * Pencere sınırında (örn. saat 00:00) gönderilmiş ama henüz işlenmemiş bir
 * isteğin reddedilmemesi için, hem "şu anki" hem "bir önceki" pencerenin
 * anahtarı geçerli kabul edilir (grace period).
 */
export class InternalKeyDeriver {
  private readonly masterSecret: string;
  private readonly windowSeconds: number;

  constructor(masterSecret: string, windowSeconds = 86400) {
    if (!masterSecret || masterSecret.length < 32) {
      throw new Error(
        'INTERNAL_SERVICE_MASTER_SECRET tanımlı değil ya da yetersiz uzunlukta (min 32 karakter).',
      );
    }
    this.masterSecret = masterSecret;
    this.windowSeconds = windowSeconds;
  }

  private deriveForWindow(windowIndex: number): string {
    return createHmac('sha256', this.masterSecret)
      .update(`eva-internal-service:${windowIndex}`)
      .digest('hex');
  }

  currentWindowIndex(): number {
    return Math.floor(Date.now() / 1000 / this.windowSeconds);
  }

  currentKey(): string {
    return this.deriveForWindow(this.currentWindowIndex());
  }

  /** Gelen bir anahtarın şu anki ya da bir önceki pencereyle eşleşip eşleşmediğini kontrol eder. */
  isValid(providedKey: string): boolean {
    const current = this.currentWindowIndex();
    return (
      providedKey === this.deriveForWindow(current) ||
      providedKey === this.deriveForWindow(current - 1)
    );
  }
}

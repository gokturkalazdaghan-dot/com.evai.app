// backend/nestjs-gateway/src/health/health.controller.ts
import { Controller, Get } from '@nestjs/common';

export interface HealthResponseDto {
  status: 'ok';
  service: 'eva-gateway';
  /** Sürecin ayakta kaldığı süre (saniye). */
  uptimeSeconds: number;
}

/**
 * Ayaktalık kontrolü.
 *
 * NEDEN VAR
 * ---------
 * deploy/yayin-oncesi-kontrol.ps1, üretime çıkmadan önce
 * `GET {api}/health` çağırıp sunucunun ayakta ve sertifikasının geçerli
 * olduğunu doğruluyor. Böyle bir uç YOKTU: Gateway 404 dönüyordu, yani
 * kontrol mükemmel çalışan bir kurulumda bile "ÜRETİME ÇIKMAYIN"
 * diyordu. Yanlış alarm veren bir kontrol, bir süre sonra hiç bakılmayan
 * bir kontrole dönüşür.
 *
 * Ayrıca konteyner sağlık yoklaması, yük dengeleyici probu ve çalışma
 * süresi izleme için de gereken standart uçtur.
 *
 * KASITLI OLARAK KORUMASIZ ve SESSİZ
 * ----------------------------------
 * Ne imza ne de bütünlük doğrulaması ister: bir yoklama ucunun kimlik
 * doğrulaması gerektirmesi anlamsızdır. Buna karşılık sürüm, veritabanı
 * adresi, bağımlılık durumu gibi HİÇBİR ayrıntı sızdırmaz — dışarıya
 * yalnızca "ayaktayım" bilgisi verilir. Bileşen bazlı ayrıntı isteyen
 * iç izleme için ayrı, korumalı bir uç eklenmelidir.
 */
@Controller('health')
export class HealthController {
  @Get()
  check(): HealthResponseDto {
    return {
      status: 'ok',
      service: 'eva-gateway',
      uptimeSeconds: Math.floor(process.uptime()),
    };
  }
}

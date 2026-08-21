// backend/nestjs-gateway/src/devices/entities/device-public-key.entity.ts
import { Entity, Column, PrimaryColumn, CreateDateColumn, UpdateDateColumn } from 'typeorm';

/**
 * Sıfır-PII: deviceId, Android tarafında rastgele üretilen (donanım
 * kimliğine bağlı OLMAYAN) bir UUID'dir. publicKeyDer, imza doğrulaması
 * için gereken matematiksel bir değerdir, kimseyi tanımlamaz.
 */
@Entity('device_public_keys')
export class DevicePublicKeyEntity {
  @PrimaryColumn({ type: 'varchar', length: 64 })
  deviceId!: string;

  @Column({ type: 'text' })
  publicKeyBase64!: string;

  @Column({ type: 'varchar', length: 32, nullable: true })
  attestationHash!: string | null;

  @Column({ type: 'boolean', default: true })
  isActive!: boolean;

  @CreateDateColumn({ type: 'timestamptz' })
  registeredAt!: Date;

  @UpdateDateColumn({ type: 'timestamptz' })
  lastUsedAt!: Date;
}

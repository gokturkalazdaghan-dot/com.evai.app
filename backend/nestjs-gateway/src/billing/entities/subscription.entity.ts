// backend/nestjs-gateway/src/billing/entities/subscription.entity.ts
import { Entity, Column, PrimaryGeneratedColumn, Index, UpdateDateColumn } from 'typeorm';

export enum SubscriptionTier {
  FREE = 'free',
  TRIALING = 'trialing',
  ACTIVE = 'active',
  EXPIRED = 'expired',
  GRACE_PERIOD = 'gracePeriod',
  BILLING_ISSUE = 'billingIssue',
  REVOKED = 'revoked',
}

/**
 * RevenueCat mimarisinde bu tablo artık bir "birincil doğrulama kaydı"
 * değil, bir SUNUCU TARAFI YANSIMASI (mirror) — gerçek doğruluk kaynağı
 * (source of truth) her zaman RevenueCat'in kendisidir. Bu tablo, Gateway'in
 * kendi iş mantığında (örn. "bu kullanıcı premium mı, Fiyat Tasarruf
 * Ajanı'nın öncelikli bölgesine mi giriyor") hızlı, düşük gecikmeli bir
 * kontrol yapabilmesi için tutuluyor — her istek için RevenueCat API'sine
 * gitmek yerine.
 *
 * Anahtar alan artık device_attestation_hash DEĞİL, RevenueCat'in kendi
 * ürettiği (ve zaten anonim/sıfır-PII olan) app_user_id'dir.
 */
@Entity('revenuecat_subscriptions')
@Index(['revenuecatAppUserId'], { unique: true })
export class RevenueCatSubscriptionEntity {
  @PrimaryGeneratedColumn('uuid')
  id!: string;

  @Column({ type: 'varchar', length: 128 })
  revenuecatAppUserId!: string;

  @Column({ type: 'varchar', length: 128, nullable: true })
  originalAppUserId!: string | null;

  @Column({ type: 'varchar', length: 128 })
  productId!: string;

  @Column({ type: 'text', array: true, default: '{}' })
  entitlementIds!: string[];

  @Column({ type: 'enum', enum: SubscriptionTier, default: SubscriptionTier.FREE })
  tier!: SubscriptionTier;

  @Column({ type: 'timestamptz', nullable: true })
  expirationDate!: Date | null;

  @Column({ type: 'boolean', default: false })
  willAutoRenew!: boolean;

  @Column({ type: 'varchar', length: 16, default: 'PRODUCTION' })
  environment!: 'SANDBOX' | 'PRODUCTION';

  @Column({ type: 'varchar', length: 16, default: 'PLAY_STORE' })
  store!: string;

  @Column({ type: 'varchar', length: 64, nullable: true })
  lastEventId!: string | null;

  @UpdateDateColumn({ type: 'timestamptz' })
  lastSyncedAt!: Date;
}

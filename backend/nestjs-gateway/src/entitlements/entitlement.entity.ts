// backend/nestjs-gateway/src/entitlements/entitlement.entity.ts
import {
  Column,
  CreateDateColumn,
  Entity,
  Index,
  PrimaryGeneratedColumn,
  UpdateDateColumn,
} from 'typeorm';

export enum EntitlementStatus {
  ACTIVE = 'ACTIVE',
  IN_GRACE = 'IN_GRACE',
  EXPIRED = 'EXPIRED',
  REVOKED = 'REVOKED',
}

export enum EntitlementSource {
  PLAY_STORE = 'PLAY_STORE',
  APP_STORE = 'APP_STORE',
  STRIPE = 'STRIPE',
  PROMOTIONAL = 'PROMOTIONAL',
}

/** Tek hak anahtari kullaniliyor; ileride 'pro' vb. eklenebilir. */
export const ENTITLEMENT_PREMIUM = 'premium';

/**
 * Sunucu tarafi hak sahipligi (migration 005).
 *
 * Odeme saglayicisindan BAGIMSIZ: Play/App Store satin alimlari
 * RevenueCat webhook'u, olasi bir web yuzeyi ise Stripe webhook'u ile
 * ayni satiri yazar. Guard hangi saglayicidan geldigine bakmaz.
 */
@Entity('subscription_entitlements')
@Index(['subjectId', 'entitlementKey'], { unique: true })
export class SubscriptionEntitlementEntity {
  @PrimaryGeneratedColumn('uuid')
  entitlementId!: string;

  /** Imza dogrulamasindan gecen cihaz kimligi. */
  @Column({ type: 'varchar', length: 128 })
  subjectId!: string;

  @Column({ type: 'varchar', length: 64 })
  entitlementKey!: string;

  @Column({ type: 'enum', enum: EntitlementStatus, default: EntitlementStatus.EXPIRED })
  status!: EntitlementStatus;

  @Column({ type: 'enum', enum: EntitlementSource })
  source!: EntitlementSource;

  @Column({ type: 'varchar', length: 190, nullable: true })
  providerRef!: string | null;

  @Column({ type: 'varchar', length: 190, nullable: true })
  productId!: string | null;

  /** null = suresiz (promosyonel erisim). */
  @Column({ type: 'timestamptz', nullable: true })
  expiresAt!: Date | null;

  /** Odeme basarisizken erisimin surdurulecegi son an. */
  @Column({ type: 'timestamptz', nullable: true })
  graceUntil!: Date | null;

  @Column({ type: 'varchar', length: 190, nullable: true })
  lastEventId!: string | null;

  @CreateDateColumn({ type: 'timestamptz' })
  createdAt!: Date;

  @UpdateDateColumn({ type: 'timestamptz' })
  updatedAt!: Date;
}

/**
 * Ucretsiz kullanicilarin gunluk ozellik kullanimi.
 *
 * Kilitli ozellikler tamamen kapali degil: kullanici neyin parasini
 * odeyecegini gorebilmeli.
 */
@Entity('feature_usage_counters')
export class FeatureUsageCounterEntity {
  @Column({ type: 'varchar', length: 128, primary: true })
  subjectId!: string;

  @Column({ type: 'varchar', length: 64, primary: true })
  featureKey!: string;

  /** UTC gun; yerel gun saat dilimi degistirene kotayi iki kez verirdi. */
  @Column({ type: 'date', primary: true })
  usageDate!: string;

  @Column({ type: 'integer', default: 0 })
  usedCount!: number;

  @UpdateDateColumn({ type: 'timestamptz' })
  updatedAt!: Date;
}

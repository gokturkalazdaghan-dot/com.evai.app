// backend/nestjs-gateway/src/stations/entities/geohash-region-cache-meta.entity.ts
import { Entity, Column, PrimaryColumn } from 'typeorm';

@Entity('geohash_region_cache_meta')
export class GeohashRegionCacheMetaEntity {
  @PrimaryColumn({ type: 'varchar', length: 5 })
  geohash5!: string;

  @Column({ type: 'int', default: 0 })
  stationCount!: number;

  @Column({ type: 'timestamptz' })
  lastMutationAt!: Date;

  @Column({ type: 'bigint', default: 1 })
  cacheVersion!: number;
}

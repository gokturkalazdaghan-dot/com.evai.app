// backend/nestjs-gateway/src/stations/entities/station.entity.ts
import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  ManyToOne,
  OneToMany,
  JoinColumn,
  Index,
} from 'typeorm';

export enum ConnectorType {
  CCS1 = 'CCS1',
  CCS2 = 'CCS2',
  CHAdeMO = 'CHAdeMO',
  TYPE1 = 'TYPE1',
  TYPE2 = 'TYPE2',
  TESLA_NACS = 'TESLA_NACS',
  TESLA_DESTINATION = 'TESLA_DESTINATION',
  GBT_DC = 'GBT_DC',
  GBT_AC = 'GBT_AC',
}

export enum StationStatus {
  OPERATIONAL = 'OPERATIONAL',
  DEGRADED = 'DEGRADED',
  OFFLINE = 'OFFLINE',
  UNKNOWN = 'UNKNOWN',
  PLANNED = 'PLANNED',
}

@Entity('charging_network_operators')
export class ChargingNetworkOperatorEntity {
  @PrimaryGeneratedColumn('uuid')
  cpoId!: string;

  @Column({ type: 'varchar', length: 32, unique: true })
  cpoCode!: string;

  @Column({ type: 'varchar', length: 128 })
  displayName!: string;

  @Column({ type: 'boolean', default: false })
  supportsRealtimePricing!: boolean;

  @Column({ type: 'boolean', default: true })
  isActive!: boolean;
}

// NOT: Sinif tanim sirasi onemlidir. `emitDecoratorMetadata` acikken
// TypeScript her iliski alani icin `__metadata("design:type", X)` cagrisini
// EAGER olarak uretir. Derlenmis JS'te siniflar TDZ'ye tabi `let` benzeri
// binding'ler oldugundan, henuz tanimlanmamis bir sinifa referans veren
// metadata "Cannot access 'X' before initialization" hatasiyla coker.
// Bu yuzden ChargingStationEntity, ona referans veren
// StationConnectorEntity'den ONCE tanimlanmalidir.
// (`() => Entity` ok fonksiyonlari lazy calistigi icin sorun degildir.)
@Entity('charging_stations')
@Index(['geohash7'])
@Index(['geohash5'])
export class ChargingStationEntity {
  @PrimaryGeneratedColumn('uuid')
  stationId!: string;

  @Column({ type: 'uuid' })
  cpoId!: string;

  @ManyToOne(() => ChargingNetworkOperatorEntity)
  @JoinColumn({ name: 'cpo_id' })
  cpo!: ChargingNetworkOperatorEntity;

  @Column({ type: 'varchar', length: 256 })
  name!: string;

  @Column({ type: 'double precision' })
  lat!: number;

  @Column({ type: 'double precision' })
  lon!: number;

  @Column({ type: 'varchar', length: 9 })
  geohash9!: string;

  @Column({ type: 'varchar', length: 7 })
  geohash7!: string;

  @Column({ type: 'varchar', length: 5 })
  geohash5!: string;

  @Column({ type: 'char', length: 2 })
  countryCode!: string;

  @Column({ type: 'enum', enum: StationStatus, default: StationStatus.UNKNOWN })
  status!: StationStatus;

  @Column({ type: 'numeric', precision: 6, scale: 2 })
  maxPowerKw!: number;

  @Column({ type: 'enum', enum: ConnectorType, array: true })
  connectorTypes!: ConnectorType[];

  @Column({ type: 'numeric', precision: 3, scale: 2, default: 0.5 })
  dataConfidenceScore!: number;

  @OneToMany(() => StationConnectorEntity, (connector) => connector.station)
  connectors!: StationConnectorEntity[];
}

@Entity('station_connectors')
export class StationConnectorEntity {
  @PrimaryGeneratedColumn('uuid')
  connectorId!: string;

  @Column({ type: 'uuid' })
  stationId!: string;

  @ManyToOne(() => ChargingStationEntity, (station) => station.connectors)
  @JoinColumn({ name: 'station_id' })
  station!: ChargingStationEntity;

  @Column({ type: 'enum', enum: ConnectorType })
  connectorType!: ConnectorType;

  @Column({ type: 'numeric', precision: 6, scale: 2 })
  powerKw!: number;

  @Column({ type: 'enum', enum: StationStatus, default: StationStatus.UNKNOWN })
  status!: StationStatus;
}

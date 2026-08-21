// backend/nestjs-gateway/src/stations/dto/station-response.dto.ts
export class StationConnectorResponseDto {
  connectorId!: string;
  connectorType!: string;
  powerKw!: number;
  status!: string;
}

export class StationResponseDto {
  stationId!: string;
  name!: string;
  lat!: number;
  lon!: number;
  distanceMeters!: number;
  status!: string;
  maxPowerKw!: number;
  connectors!: StationConnectorResponseDto[];
  cpoDisplayName!: string;
  dataConfidenceScore!: number;
  /**
   * Redis'teki tariff:live:{stationId} anahtarından (Fiyat Tasarruf
   * Ajanı'nın yazdığı) canlı fiyat. Henüz hiç tarife çekilmemiş bir
   * istasyon için null olabilir — bu durumda istemci tarafı "fiyat
   * bilgisi yok" göstermeli, 0 veya uydurma bir değer GÖSTERMEMELİ.
   */
  pricePerKwh!: number | null;
  currency!: string | null;
  /**
   * Fiyatin bir onceki olcume gore yonu: 'UP' | 'DOWN' | 'STABLE'.
   * null ise KARSILASTIRACAK gecmis olcum yok -- istemci ok
   * GOSTERMEMELI. "Degismedi" ile "bilmiyoruz" ayri seylerdir.
   */
  priceTrend!: 'UP' | 'DOWN' | 'STABLE' | null;
  /** Yuzde degisim; priceTrend null ise bu da null. */
  priceChangePercent!: number | null;
}

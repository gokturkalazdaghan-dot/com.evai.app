// backend/nestjs-gateway/src/telemetry/telemetry.types.ts

/**
 * Panele giden canli telemetri.
 *
 * TUM OLCUM ALANLARI NULL OLABILIR -- bu bilincli.
 * Standart OBD-II her degeri VERMEZ: paket voltaji (405V gibi), menzil,
 * lastik basinci ve batarya sicakligi ureticiye ozel PID'lerdedir.
 * Bilinmeyeni sifir gostermek, soforu olmayan bir menzile guvendirir.
 * Panel null gordugunde "—" basar.
 */
export interface LiveTelemetry {
  subjectId: string;

  /** Surus bataryasi yuzdesi (OBD PID 0x5B). */
  batteryPercent: number | null;

  /** Tahmini menzil; OBD'den GELMEZ, kapasite+verimlilikten hesaplanir. */
  rangeKm: number | null;

  /**
   * 12V kontrol modulu voltaji (PID 0x42).
   * DIKKAT: bu PAKET voltaji DEGILDIR -- panelde oylece etiketlenmemeli.
   */
  controlModuleVoltage: number | null;

  /** Paket voltaji. Ureticiye ozel PID gerekir; genelde null. */
  packVoltage: number | null;

  speedKph: number | null;
  odometerKm: number | null;

  isCharging: boolean | null;
  chargePowerKw: number | null;

  /** Okumanin alindigi an (epoch ms). */
  capturedAtEpochMs: number;

  /** Baglanti kalitesi gostergesi. */
  source: 'OBD_BLE' | 'ANDROID_AUTOMOTIVE' | 'OEM_CLOUD' | 'MANUAL';

  /** Telefon -> gateway gidis suresi (ms); panelde "Latency" olarak gosterilir. */
  latencyMs: number | null;
}

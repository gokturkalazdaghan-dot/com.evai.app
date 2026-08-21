// android/app/src/main/java/com/eva/app/vehicle/telemetry/VehicleTelemetry.kt
package com.eva.app.vehicle.telemetry

import java.time.Duration
import java.time.Instant

/**
 * Telemetrinin nereden geldigi.
 *
 * Kaynak KULLANICIYA gosterilir: "aracindan canli" ile "senin girdigin
 * deger" ayni sey degildir ve sofor hangisine baktigini bilmeli.
 */
enum class TelemetrySource {
    /** Ureticinin bulut API'si (Smartcar/Tesla vb.), gateway uzerinden. */
    OEM_CLOUD,

    /** Uygulama aracin kendi ekraninda calisiyor (Android Automotive OS). */
    ANDROID_AUTOMOTIVE,

    /** OBD-II dongle, Bluetooth uzerinden. */
    OBD_DONGLE,

    /** Kullanicinin elle girdigi deger. Son care. */
    MANUAL,
}

/**
 * Aracin anlik durumu.
 *
 * TUM ALANLAR NULL OLABILIR -- NEDEN
 * ----------------------------------
 * Bilinmeyen bir batarya seviyesi icin 0 ya da son bilinen deger
 * dondurmek, soforu bos bir yolda yakit bittigini fark etmeden
 * birakabilir. "Bilmiyorum" demek, yanlis bir sayi soylemekten iyidir --
 * fiyatlarda uyguladigimiz kuralin aynisi, burada sonuclari daha agir.
 */
data class VehicleTelemetry(
    val batteryPercent: Int?,
    val rangeKm: Double?,
    /** Sarj oluyor mu? null = bilinmiyor. */
    val isCharging: Boolean?,
    val source: TelemetrySource,
    val capturedAt: Instant,
) {
    /**
     * Okuma bayat mi?
     *
     * Elle girilen bir deger saatler icinde anlamsizlasir; canli bir
     * kaynak ise birkac dakika icinde tazelenmezse (arac cevrimdisi,
     * dongle baglantisi koptu) guvenilmez olur.
     */
    fun isStale(now: Instant = Instant.now()): Boolean {
        val age = Duration.between(capturedAt, now)
        return age > staleAfter(source)
    }

    /** Guvenilir bir batarya okumasi var mi? */
    val hasUsableBattery: Boolean
        get() = batteryPercent != null && !isStale()

    companion object {
        /** Hicbir kaynak veri veremediginde. */
        fun unknown(source: TelemetrySource) = VehicleTelemetry(
            batteryPercent = null,
            rangeKm = null,
            isCharging = null,
            source = source,
            capturedAt = Instant.now(),
        )
    }
}

/**
 * Kaynak basina bayatlama esigi.
 *
 * MANUAL uzun: kullanicinin sabah girdigi deger ogleden sonra hala kaba
 * bir fikir verir. Canli kaynaklar kisa: canli oldugunu iddia eden bir
 * okumanin eskimesi, "canli" etiketini yalan yapar.
 */
private fun staleAfter(source: TelemetrySource): Duration = when (source) {
    TelemetrySource.OEM_CLOUD -> Duration.ofMinutes(30)
    TelemetrySource.ANDROID_AUTOMOTIVE -> Duration.ofMinutes(2)
    TelemetrySource.OBD_DONGLE -> Duration.ofMinutes(5)
    TelemetrySource.MANUAL -> Duration.ofHours(12)
}

/** Telemetri kaynagi baglanmis mi, calisiyor mu? */
sealed class TelemetryConnection {
    /** Hicbir kaynak baglanmamis; kullanici hala elle giriyor. */
    data object NotConnected : TelemetryConnection()

    data class Connected(val source: TelemetrySource, val vehicleLabel: String?) :
        TelemetryConnection()

    /** Baglanti vardi ama su an veri gelmiyor. */
    data class Interrupted(val source: TelemetrySource, val reason: String) :
        TelemetryConnection()
}

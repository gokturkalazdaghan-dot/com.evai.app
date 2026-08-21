// android/app/src/main/java/com/eva/app/ui/stations/StationModels.kt
package com.eva.app.ui.stations

import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@Serializable
data class StationConnectorDto(
    val connectorId: String,
    val connectorType: String,
    val powerKw: Double,
    val status: String,
)

@Serializable
data class StationDto(
    val stationId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Int,
    val status: String,
    val maxPowerKw: Double,
    val connectors: List<StationConnectorDto>,
    val cpoDisplayName: String,
    val dataConfidenceScore: Double,
    /** Gateway'den null gelebilir — bu istasyon için henüz canlı tarife
     * çekilmemiş demektir. UI bu durumda "fiyat bekleniyor" göstermeli,
     * asla varsayılan bir rakam UYDURMAMALI. */
    val pricePerKwh: Double? = null,
    val currency: String? = null,
    /**
     * Fiyatin bir onceki olcume gore yonu: "UP" | "DOWN" | "STABLE".
     * null ise KARSILASTIRACAK gecmis olcum yok -- ok gosterilmemeli.
     * "Degismedi" ile "bilmiyoruz" ayri seylerdir.
     */
    val priceTrend: String? = null,
    val priceChangePercent: Double? = null,
)

/** Fiyat trendini ekranda gosterilecek ok + renge cevirir. */
enum class PriceTrendVisual(val arrow: String) {
    RISING("↑"),
    FALLING("↓"),
    ;

    companion object {
        /**
         * STABLE ve null AYNI sekilde ele alinir: ok gosterilmez.
         * Anlamsiz bir "yatay ok", surucunun dikkatini bosa harcar --
         * onemli olan fiyatin gercekten hareket etmesi.
         */
        fun from(trend: String?): PriceTrendVisual? = when (trend) {
            "UP" -> RISING
            "DOWN" -> FALLING
            else -> null
        }
    }
}

enum class UnitSystem { METRIC, IMPERIAL }

/**
 * Mil kullanan ulkeler. Geri kalan her yerde metrik varsayilir -- bu liste
 * "istisna" listesidir, tersi degil.
 */
private val IMPERIAL_REGIONS = setOf("US", "LR", "MM")

/**
 * Kullanicinin yasadigi ulkeye gore olcu birimi. Cihaz yereli (locale)
 * bunun standart gostergesidir; uygulama zaten ayni kaynagi kullaniyor
 * (bkz. APIClient'taki "x-eva-locale" header'i).
 *
 * Daha once bu deger IKI YERDE SABIT KODLANMISTI ve birbiriyle celisiyordu:
 * DashboardScreen IMPERIAL, StationsViewModel METRIC. Ayni istasyon iki
 * ekranda farkli birimle gorunuyordu.
 */
fun unitSystemFor(locale: Locale = Locale.getDefault()): UnitSystem =
    if (locale.country.uppercase(Locale.ROOT) in IMPERIAL_REGIONS) {
        UnitSystem.IMPERIAL
    } else {
        UnitSystem.METRIC
    }

/**
 * kWh basina fiyati kullanicinin yereline gore bicimlendirir.
 *
 * BURADA PARA BIRIMI CEVRILMEZ. `currencyCode` fiyatin GERCEKTEN hangi
 * birimde oldugunu soyler ve backend'den gelir: Istanbul'daki bir istasyon
 * TRY ile satar, San Francisco'daki USD ile. Kullanicinin ulkesi yalnizca
 * SUNUMU belirler -- ondalik ayraci, binlik ayraci ve sembolun konumu.
 *
 * Ornekler:
 *   tr-TR yereli + TRY -> "₺8,32/kWh"
 *   tr-TR yereli + USD -> "$0,48/kWh"   (ayrac virgul: Turkiye kurali)
 *   en-US yereli + TRY -> "TRY8.32/kWh" (ayrac nokta: ABD kurali)
 *
 * Onceki hali fiyatin onune KOSULSUZ "$" koyuyordu; TRY bir fiyat ekranda
 * dolar gibi gorunuyordu.
 *
 * currencyCode null ya da taninmayan bir kod ise sembol HIC gosterilmez --
 * yanlis bir para birimi gostermektense hic gostermemek dogrudur.
 */
fun formatPricePerKwh(
    pricePerKwh: Double,
    currencyCode: String?,
    locale: Locale = Locale.getDefault(),
): String {
    val currency = currencyCode?.let {
        runCatching { Currency.getInstance(it.uppercase(Locale.ROOT)) }.getOrNull()
    }

    val formatter = if (currency != null) {
        NumberFormat.getCurrencyInstance(locale).apply { this.currency = currency }
    } else {
        NumberFormat.getNumberInstance(locale)
    }

    // Bazi para birimleri (orn. JPY) varsayilan olarak ondalik gostermez;
    // kWh basina fiyatlar kucuk oldugu icin 2 hane sabitleniyor.
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2

    return "${formatter.format(pricePerKwh)}/kWh"
}

fun formatDistance(distanceMeters: Int, unitSystem: UnitSystem): String {
    return when (unitSystem) {
        UnitSystem.METRIC -> {
            val km = distanceMeters / 1000.0
            if (km < 1.0) "$distanceMeters m" else "%.1f km".format(km)
        }
        UnitSystem.IMPERIAL -> {
            val miles = distanceMeters / 1609.344
            "%.1f mi".format(miles)
        }
    }
}

fun connectorDisplayLabel(connectorType: String): String {
    return when (connectorType) {
        "CCS1", "CCS2" -> "CCS"
        "CHAdeMO" -> "CHAdeMO"
        "TESLA_NACS", "TESLA_DESTINATION" -> "Tesla (NACS)"
        "TYPE1" -> "Type 1"
        "TYPE2" -> "Type 2"
        "GBT_DC", "GBT_AC" -> "GB/T"
        else -> connectorType
    }
}

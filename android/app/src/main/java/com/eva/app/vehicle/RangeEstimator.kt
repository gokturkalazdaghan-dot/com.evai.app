// android/app/src/main/java/com/eva/app/vehicle/RangeEstimator.kt
package com.eva.app.vehicle

import com.eva.app.ui.stations.UnitSystem
import kotlin.math.roundToInt

/**
 * Ortalama verimlilik (km/kWh).
 *
 * 6.2: orta sinif bir elektrikli otomobilin karisik cevrim ortalamasi
 * (WLTP degerleri tipik olarak 5.5-7.0 arasi). Aracin GERCEK verimliligi
 * olculdukce bu deger kisisellestirilmeli -- surus tarzi, hava sicakligi
 * ve yol egimi menzili kolayca %30 oynatir.
 */
const val AVERAGE_KM_PER_KWH = 6.2

private const val KM_PER_MILE = 1.609344

/**
 * Kullanilabilir batarya orani.
 *
 * Uretici bir tampon birakir: gosterilen %0, bataryanin gercekten bos
 * oldugu nokta degildir. Yine de kullaniciya bu tamponu MENZIL olarak
 * vaat etmiyoruz -- "%0'da 15 km daha gidersin" demek, birini yolda
 * birakabilecek bir vaattir.
 */
private const val USABLE_FRACTION = 1.0

/**
 * Kalan sarja gore menzil tahmini.
 *
 * @param chargePercent 0-100 arasi mevcut sarj.
 * @param batteryCapacityKwh aracin batarya kapasitesi.
 * @return kilometre cinsinden tahmin; girdi gecersizse null.
 *
 * NEDEN NULL DONEBILIR: kapasite bilinmiyorsa menzil de bilinmiyor.
 * "Yaklasik" bile olsa bir sayi uydurmak, surucunun ona gore karar
 * vermesine yol acar.
 */
fun estimateRangeKm(
    chargePercent: Int?,
    batteryCapacityKwh: Double?,
    kmPerKwh: Double = AVERAGE_KM_PER_KWH,
): Double? {
    if (chargePercent == null || batteryCapacityKwh == null) return null
    if (chargePercent !in 0..100 || batteryCapacityKwh <= 0) return null

    val usableKwh = (chargePercent / 100.0) * batteryCapacityKwh * USABLE_FRACTION
    return usableKwh * kmPerKwh
}

/**
 * Menzili kullanicinin birim sistemine gore bicimlendirir.
 *
 * Bilinmiyorsa "—" DEGIL, ne eksik oldugunu soyleyen bir metin doner:
 * kullanici neden sayi gormedigini anlamali ve duzeltebilmeli
 * (batarya kapasitesini girerek).
 */
fun formatRange(rangeKm: Double?, unitSystem: UnitSystem): String {
    if (rangeKm == null) return "Menzil için kapasite gerekli"

    return when (unitSystem) {
        UnitSystem.METRIC -> "${rangeKm.roundToInt()} km"
        UnitSystem.IMPERIAL -> "${(rangeKm / KM_PER_MILE).roundToInt()} mi"
    }
}

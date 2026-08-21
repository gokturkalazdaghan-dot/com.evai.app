// android/app/src/main/java/com/eva/app/vehicle/VehicleProfile.kt
package com.eva.app.vehicle

import kotlinx.serialization.Serializable

/**
 * Kullanıcının aracına dair, cihazda şifreli olarak saklanan profil.
 * Sıfır-PII: bu veri bir kişiyi değil bir ARACI tanımlar, sunucuya
 * yalnızca soket tipi/batarya kapasitesi gibi filtre parametreleri olarak
 * gönderilir (bkz. VoiceQueryRequest.vehicleConnectorTypes) — plaka, VIN
 * numarası gibi gerçek dünya kimlikleri BU MODELDE YOK ve asla eklenmemeli.
 *
 * currentChargePercent: DÜRÜSTLÜK NOTU — bu alan gerçek bir araç
 * telemetrisi (OBD-II / üretici API'si) entegrasyonundan GELMİYOR, çünkü
 * böyle bir entegrasyon henüz projede yok. Kullanıcı bunu manuel girer
 * (bkz. ChargeLevelUpdateDialog, ileride eklenecek). Dashboard'da "68%"
 * gösteriliyorsa bu kullanıcının en son girdiği değerdir, canlı bir sensör
 * okuması değildir — bunu UI metninde de netleştireceğiz.
 */
@Serializable
data class VehicleProfile(
    val brand: String,
    val model: String,
    val batteryCapacityKwh: Double,
    val connectorType: String, // "CCS2", "TESLA_NACS", "TYPE2" vb. — backend enum'uyla birebir
    val currentChargePercent: Int = 80,
    val estimatedRangeMiles: Int? = null,
) {
    init {
        require(batteryCapacityKwh > 0) { "Batarya kapasitesi pozitif olmalı" }
        require(currentChargePercent in 0..100) { "Şarj yüzdesi 0-100 aralığında olmalı" }
    }

    val displayName: String
        get() = "$brand $model"
}

/**
 * Onboarding ekranında kullanıcıya gösterilecek yaygın soket tipi
 * seçenekleri — backend'deki connector_type enum'uyla (database/schema.sql)
 * BİREBİR aynı string değerleri kullanır, aksi halde /v1/stations/nearby
 * filtresi sessizce hiçbir sonuç döndürmez.
 */
enum class ConnectorOption(val backendValue: String, val displayLabel: String) {
    CCS2("CCS2", "CCS2 (Avrupa Standardı)"),
    CCS1("CCS1", "CCS1 (Kuzey Amerika)"),
    TESLA_NACS("TESLA_NACS", "Tesla (NACS)"),
    CHADEMO("CHAdeMO", "CHAdeMO"),
    TYPE2("TYPE2", "Type 2 (AC)"),
}

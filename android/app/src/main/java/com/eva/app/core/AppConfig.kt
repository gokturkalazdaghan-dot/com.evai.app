// android/app/src/main/java/com/eva/app/core/AppConfig.kt
package com.eva.app.core

import com.eva.app.BuildConfig

/**
 * Uygulama genelinde kullanılan derleme-zamanı yapılandırma değerleri.
 * Tüm değerler build.gradle.kts'teki buildConfigField tanımlarından gelir —
 * bu sayede debug/release build'leri farklı Gateway adreslerine otomatik
 * bağlanır, kod içinde hardcoded URL veya "TODO: production'da değiştir"
 * yorumu olmaz.
 */
object AppConfig {

    val gatewayBaseUrl: String
        get() = if (BuildConfig.DEBUG) {
            BuildConfig.EVA_GATEWAY_BASE_URL_DEBUG
        } else {
            BuildConfig.EVA_GATEWAY_BASE_URL_RELEASE
        }

    val revenueCatPublicApiKey: String
        get() = BuildConfig.REVENUECAT_PUBLIC_API_KEY

    val googleCloudProjectNumber: Long
        get() = BuildConfig.GOOGLE_CLOUD_PROJECT_NUMBER

    /**
     * Certificate pinning için Gateway sunucu sertifikasının SHA-256 SPKI
     * pin değer(ler)i. En az 2 değer önerilir: mevcut sertifika + gelecekte
     * yenileneceği sertifika (backup pin) — aksi halde sertifika yenileme
     * günü TÜM kullanıcılar bağlanamaz hale gelir.
     *
     * BOŞ LİSTE = pinning DEVRE DIŞI. Bu yalnızca local.properties'te
     * EVA_GATEWAY_BASE_URL_DEBUG localhost/10.0.2.2 gibi kendinden imzalı
     * bir sertifika kullandığınız yerel geliştirme ortamı için kabul
     * edilebilir. Production release build'inde local.properties'te
     * GATEWAY_CERT_PIN_1 ve GATEWAY_CERT_PIN_2 MUTLAKA doldurulmalı.
     */
    val gatewayCertificatePins: List<String>
        get() = listOfNotNull(
            BuildConfig.GATEWAY_CERT_PIN_1.takeIf { it.isNotBlank() },
            BuildConfig.GATEWAY_CERT_PIN_2.takeIf { it.isNotBlank() },
        )

    /**
     * Google Maps API anahtarı (local.properties -> MAPS_API_KEY).
     * Boşsa harita katmanı HİÇ oluşturulmaz.
     */
    val mapsApiKey: String
        get() = BuildConfig.MAPS_API_KEY

    /**
     * Harita gösterilsin mi?
     *
     * Anahtar yokken Google Maps boş/gri bir alan ya da "for development
     * purposes only" filigranı gösterir — bu, temiz bir liste görünümünden
     * DAHA KÖTÜ bir deneyimdir. Bu yüzden harita yalnızca gerçek bir anahtar
     * varken devreye girer; aksi halde uygulama liste görünümüyle çalışır.
     */
    val isMapEnabled: Boolean
        get() = mapsApiKey.isNotBlank()

    /**
     * RevenueCat'te tanımlı "premium" entitlement kimliği. RevenueCat
     * Dashboard → Entitlements bölümünde bu ID ile bir entitlement
     * oluşturulmuş ve Google Play'deki aylık/yıllık ürünler buna
     * bağlanmış olmalı.
     */
    const val REVENUECAT_ENTITLEMENT_ID = "premium"
}

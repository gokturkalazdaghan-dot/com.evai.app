// android/app/src/main/java/com/eva/app/vehicle/telemetry/AutomotiveTelemetryProvider.kt
package com.eva.app.vehicle.telemetry

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

private const val TAG = "AutomotiveTelemetry"

/** Wh -> yuzde cevrimi icin batarya kapasitesi okunamazsa kullanilmaz. */
private const val WH_PER_KWH = 1000.0

/** Metre -> kilometre. */
private const val METERS_PER_KM = 1000.0

/**
 * Aracin KENDI verisini okur (Android Automotive OS).
 *
 * NE ZAMAN CALISIR
 * ----------------
 * Yalnizca uygulama aracin bas unitesinde calisiyorsa. Telefondaki
 * Android Auto (projeksiyon) bu veriyi VERMEZ -- orada uygulama hala
 * telefonda kosar, arac yalnizca ekran gorevi gorur.
 *
 * IZIN
 * ----
 * `android.car.permission.CAR_ENERGY` ayricalikli bir izindir; normal
 * bir uygulama Play uzerinden yukleyerek alamaz. Uretici imzasi ya da
 * sistem uygulamasi olarak dagitim gerekir. Bu yuzden bu saglayici
 * telefon dagitiminda sessizce devre disi kalir ve repository bir
 * sonraki kaynaga duser.
 */
class AutomotiveTelemetryProvider(private val context: Context) {

    /** Cihaz bir arac mi? Telefonda bu daima false. */
    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    /**
     * Batarya ve menzil degisimlerini yayinlar.
     *
     * Anlik degil OLAY TABANLI: `registerCallback` yalnizca deger
     * degistiginde tetiklenir, bu yuzden surekli sorgulamaya gore hem
     * daha taze hem daha ucuzdur.
     */
    fun telemetryFlow(batteryCapacityKwh: Double?): Flow<VehicleTelemetry> = callbackFlow {
        if (!isSupported()) {
            close()
            return@callbackFlow
        }

        var car: Car? = null
        var propertyManager: CarPropertyManager? = null

        // Son bilinen degerler: her ozellik AYRI olay olarak gelir,
        // birlestirmek icin tutuluyor.
        var batteryWh: Float? = null
        var rangeMeters: Float? = null
        var charging: Boolean? = null

        fun emit() {
            val percent = batteryWh?.let { wh ->
                batteryCapacityKwh?.takeIf { it > 0 }?.let { capacity ->
                    ((wh / (capacity * WH_PER_KWH)) * 100).toInt().coerceIn(0, 100)
                }
            }

            trySend(
                VehicleTelemetry(
                    batteryPercent = percent,
                    rangeKm = rangeMeters?.let { it / METERS_PER_KM },
                    isCharging = charging,
                    source = TelemetrySource.ANDROID_AUTOMOTIVE,
                    capturedAt = Instant.now(),
                ),
            )
        }

        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                when (value.propertyId) {
                    VehiclePropertyIds.EV_BATTERY_LEVEL ->
                        batteryWh = value.value as? Float

                    VehiclePropertyIds.RANGE_REMAINING ->
                        rangeMeters = value.value as? Float

                    VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED ->
                        charging = value.value as? Boolean
                }
                emit()
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                // Tek bir ozellik okunamadi; akisi kapatmiyoruz -- diger
                // ozellikler hala gelebilir.
                Log.w(TAG, "Arac ozelligi okunamadi: propertyId=$propertyId")
            }
        }

        try {
            car = Car.createCar(context)
            propertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager

            if (propertyManager == null) {
                Log.w(TAG, "CarPropertyManager alinamadi.")
                close()
                return@callbackFlow
            }

            listOf(
                VehiclePropertyIds.EV_BATTERY_LEVEL,
                VehiclePropertyIds.RANGE_REMAINING,
                VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED,
            ).forEach { propertyId ->
                runCatching {
                    propertyManager.registerCallback(
                        callback,
                        propertyId,
                        CarPropertyManager.SENSOR_RATE_ONCHANGE,
                    )
                }.onFailure {
                    // Izin yoksa SecurityException gelir. Uygulama
                    // COKMEMELI: bu saglayici opsiyoneldir.
                    Log.w(TAG, "Ozellik kaydi basarisiz (izin?): $propertyId", it)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Arac servisine baglanilamadi.", e)
            close()
            return@callbackFlow
        }

        awaitClose {
            runCatching { propertyManager?.unregisterCallback(callback) }
            runCatching { car?.disconnect() }
        }
    }
}

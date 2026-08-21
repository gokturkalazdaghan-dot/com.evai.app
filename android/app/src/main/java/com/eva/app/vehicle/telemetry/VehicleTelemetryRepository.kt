// android/app/src/main/java/com/eva/app/vehicle/telemetry/VehicleTelemetryRepository.kt
package com.eva.app.vehicle.telemetry

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.eva.app.network.APIClient
import com.eva.app.network.APIClientException
import com.eva.app.vehicle.VehicleProfileRepository
import com.eva.app.vehicle.telemetry.obd.ObdTelemetryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VehicleTelemetryRepo"

/**
 * Arac bulutu ne siklikta sorgulanir.
 *
 * 5 dakika: uretici API'lerinin cogu hiz siniri uygular ve daha sik
 * sorgu, aracin 12V aksesuar bataryasini bosaltacak sekilde uyanik
 * tutabilir (Tesla'nin belgelerinde acikca uyarilir).
 */
private const val CLOUD_POLL_INTERVAL_MS = 5 * 60 * 1000L

@Serializable
private data class TelemetryResponse(
    val batteryPercent: Int? = null,
    val rangeKm: Double? = null,
    val isCharging: Boolean? = null,
    val capturedAtEpochMs: Long? = null,
    /** Kullanicinin bagli bir araci var mi? */
    val isLinked: Boolean = false,
    val vehicleLabel: String? = null,
)

/**
 * Aracin anlik durumunu, mevcut EN IYI kaynaktan saglar.
 *
 * KAYNAK SIRASI
 * -------------
 *  1. ANDROID_AUTOMOTIVE -- uygulama aracin icinde calisiyorsa en taze
 *     ve en dogru veri; ag gerektirmez.
 *  2. OBD_DONGLE -- BLE uzerinden aracin CAN veri yolu. Uretici hesabi
 *     gerektirmez; ~20 dolarlik bir dongle yeter.
 *  3. OEM_CLOUD -- ureticinin bulut API'si (hesap baglantisi ister).
 *  4. MANUAL -- kullanicinin en son girdigi deger.
 *
 * MANUAL NEDEN HALA VAR: bir kullanicinin araci desteklenmiyor ya da
 * hesabini baglamak istemiyor olabilir. Ama artik VARSAYILAN yol degil,
 * SON CARE -- ve degerin elle girildigi ekranda acikca yaziyor.
 */
@Singleton
class VehicleTelemetryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: APIClient,
    private val vehicleProfileRepository: VehicleProfileRepository,
) {
    private val automotive = AutomotiveTelemetryProvider(context)
    private val obd = ObdTelemetryProvider(context)

    private val _connection = MutableStateFlow<TelemetryConnection>(TelemetryConnection.NotConnected)
    val connection = _connection.asStateFlow()

    /**
     * Sürekli telemetri akisi.
     *
     * Kaynak SECILIR, birlestirilmez: iki kaynagin birbiriyle celisen
     * degerlerini ortalamak, hicbirine ait olmayan bir sayi uretirdi.
     */
    fun telemetryFlow(): Flow<VehicleTelemetry> {
        val capacity = vehicleProfileRepository.currentVehicle.value?.batteryCapacityKwh

        if (automotive.isSupported()) {
            _connection.value = TelemetryConnection.Connected(
                TelemetrySource.ANDROID_AUTOMOTIVE,
                vehicleProfileRepository.currentVehicle.value?.displayName,
            )
            return automotive.telemetryFlow(capacity)
        }

        // OBD dongle, uretici hesabi GEREKTIRMEDEN calisan tek canli
        // kaynak: kullanicinin markasi desteklenmese, OAuth izni vermek
        // istemese bile veri gelir.
        if (obd.hasPermissions() && obd.hasPairedDongle()) {
            _connection.value = TelemetryConnection.Connected(
                TelemetrySource.OBD_DONGLE,
                vehicleProfileRepository.currentVehicle.value?.displayName,
            )
            return obd.telemetryFlow(capacity)
        }

        return cloudTelemetryFlow()
    }

    /** Uretici bulutunu periyodik sorgular (gateway uzerinden). */
    private fun cloudTelemetryFlow(): Flow<VehicleTelemetry> = flow {
        while (true) {
            val reading = fetchCloudTelemetry()
            if (reading != null) emit(reading)
            kotlinx.coroutines.delay(CLOUD_POLL_INTERVAL_MS)
        }
    }

    /**
     * Gateway'den anlik durumu ceker.
     *
     * TOKEN'LAR UYGULAMADA DEGIL: uretici hesabinin erisim anahtarlari
     * sunucuda durur. APK'ya konsaydi, cikarilan bir token ile baskasinin
     * aracinin kapisi acilabilirdi -- fiyat API anahtarindan cok daha
     * agir bir sonuc.
     */
    private suspend fun fetchCloudTelemetry(): VehicleTelemetry? {
        return try {
            val response: TelemetryResponse = apiClient.get(
                path = "/v1/telemetry/vehicle",
                requiresAuth = true,
            )

            if (!response.isLinked) {
                _connection.value = TelemetryConnection.NotConnected
                return null
            }

            _connection.value = TelemetryConnection.Connected(
                TelemetrySource.OEM_CLOUD,
                response.vehicleLabel,
            )

            VehicleTelemetry(
                batteryPercent = response.batteryPercent,
                rangeKm = response.rangeKm,
                isCharging = response.isCharging,
                source = TelemetrySource.OEM_CLOUD,
                // Sunucudaki okuma anini kullan: istegin YAPILDIGI ani
                // kullanmak, saatlik eski bir veriyi taze gostermek olurdu.
                capturedAt = response.capturedAtEpochMs
                    ?.let(Instant::ofEpochMilli)
                    ?: Instant.now(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: APIClientException) {
            Log.w(TAG, "Arac telemetrisi alinamadi: ${e.message}")
            _connection.value = TelemetryConnection.Interrupted(
                TelemetrySource.OEM_CLOUD,
                "Araca şu an ulaşılamıyor.",
            )
            null
        } catch (e: Exception) {
            Log.e(TAG, "Arac telemetrisi alinirken beklenmeyen hata", e)
            null
        }
    }

    /**
     * Elle girilen degeri telemetri olarak sarmalar.
     *
     * Kaynak MANUAL isaretlenir; UI ve uyari motoru bunun canli bir
     * okuma OLMADIGINI bilir.
     */
    fun manualTelemetry(): Flow<VehicleTelemetry> {
        val vehicle = vehicleProfileRepository.currentVehicle.value
            ?: return flowOf(VehicleTelemetry.unknown(TelemetrySource.MANUAL))

        return flowOf(
            VehicleTelemetry(
                batteryPercent = vehicle.currentChargePercent,
                rangeKm = null,
                isCharging = null,
                source = TelemetrySource.MANUAL,
                capturedAt = vehicleProfileRepository.chargeUpdatedAt() ?: Instant.now(),
            ),
        )
    }
}

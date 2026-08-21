// android/app/src/main/java/com/eva/app/vehicle/VehicleProfileRepository.kt
package com.eva.app.vehicle

import android.util.Log
import com.eva.app.security.SecureTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "VehicleProfileRepo"
private const val KEY_VEHICLE_PROFILE = "eva.vehicle.profile"
private const val KEY_CHARGE_UPDATED_AT = "eva.vehicle.charge.updatedAt"

/**
 * SecureTokenStore (EncryptedSharedPreferences) üzerinde araç profilini
 * JSON olarak saklar. Aynı depoyu oturum token'ları için kullandığımız
 * mekanizmayla paylaşır — ayrı bir şifreleme katmanı icat etmiyoruz.
 */
class VehicleProfileRepository(private val secureTokenStore: SecureTokenStore) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _currentVehicle = MutableStateFlow(loadFromDisk())
    val currentVehicle: StateFlow<VehicleProfile?> = _currentVehicle.asStateFlow()

    val hasVehicle: Boolean
        get() = _currentVehicle.value != null

    private fun loadFromDisk(): VehicleProfile? {
        val raw = secureTokenStore.read(KEY_VEHICLE_PROFILE) ?: return null
        return try {
            json.decodeFromString<VehicleProfile>(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Kayıtlı araç profili çözümlenemedi, sıfırlanıyor.", e)
            null
        }
    }

    fun saveVehicle(profile: VehicleProfile) {
        val encoded = json.encodeToString(profile)
        secureTokenStore.save(KEY_VEHICLE_PROFILE, encoded)
        _currentVehicle.value = profile
        Log.i(TAG, "Araç profili kaydedildi: ${profile.displayName}")
    }

    /**
     * Elle girilen sarj degerinin NE ZAMAN girildigi.
     *
     * Gerekli cunku elle girilen bir deger zamanla anlamsizlasir: sabah
     * girilen %80, aksam hala %80 gibi gosterilirse kullanici olmayan
     * bir menzile guvenir. Telemetri katmani bu zamana bakip okumayi
     * "bayat" isaretler (bkz. VehicleTelemetry.isStale).
     */
    fun chargeUpdatedAt(): java.time.Instant? =
        secureTokenStore.read(KEY_CHARGE_UPDATED_AT)
            ?.toLongOrNull()
            ?.let(java.time.Instant::ofEpochMilli)

    fun updateChargeLevel(newPercent: Int) {
        val current = _currentVehicle.value ?: run {
            Log.w(TAG, "Kayıtlı araç yokken şarj seviyesi güncellenmeye çalışıldı.")
            return
        }
        saveVehicle(current.copy(currentChargePercent = newPercent.coerceIn(0, 100)))
        secureTokenStore.save(KEY_CHARGE_UPDATED_AT, System.currentTimeMillis().toString())
    }

    fun clearVehicle() {
        secureTokenStore.delete(KEY_VEHICLE_PROFILE)
        _currentVehicle.value = null
    }
}

// android/app/src/main/java/com/eva/app/security/DeviceRegistrationRepository.kt
package com.eva.app.security

import android.util.Log
import com.eva.app.network.APIClient
import com.eva.app.network.APIClientException
import kotlinx.serialization.Serializable
import java.util.UUID

private const val TAG = "DeviceRegistration"

@Serializable
data class DeviceRegisterRequest(
    val deviceId: String,
    val publicKeyBase64: String,
)

@Serializable
data class DeviceRegisterResponse(
    val registered: Boolean,
)

sealed class DeviceRegistrationState {
    data object NotRegistered : DeviceRegistrationState()
    data object Registered : DeviceRegistrationState()
    data class Failed(val reason: String) : DeviceRegistrationState()
}

/**
 * İlk uygulama açılışında BİR KEZ çalışan "chicken-and-egg" çözümü:
 * RequestSignatureGuard bir isteği doğrulayabilmek için önce cihazın genel
 * anahtarını (public key) bilmesi gerekir — ama o anahtarı Gateway'e
 * göndermek için de bir istek atmak gerekir (henüz imzalanamayan bir
 * istek). Bu döngü şöyle kırılıyor: kayıt isteği RequestSignatureGuard
 * DEĞİL, yalnızca DeviceAttestationGuard (Play Integrity) ile korunuyor
 * — yani "bu gerçek bir Eva uygulaması" kanıtı yeterli, henüz "bu daha
 * önce kayıtlı bir cihaz" kanıtı aranmıyor. Kayıttan SONRAKİ tüm istekler
 * her iki guard'dan da geçmek zorunda.
 */
class DeviceRegistrationRepository(
    private val apiClient: APIClient,
    private val secureTokenStore: SecureTokenStore,
    private val requestSigner: RequestSigner,
) {
    companion object {
        private const val KEY_DEVICE_ID = SecureTokenStore.KEY_DEVICE_SIGNING_ID
    }

    /**
     * Rastgele, kimliksiz bir UUID — hiçbir donanım kimliğine (IMEI, Android
     * ID, Advertising ID) bağlı DEĞİL. Sıfır-PII ilkesine uygun: bu ID
     * yalnızca "hangi genel anahtarın hangi isteklere ait olduğunu" eşlemek
     * için var, cihazı veya kullanıcıyı gerçek dünyada tanımlamaz.
     */
    private fun getOrCreateDeviceId(): String {
        secureTokenStore.read(KEY_DEVICE_ID)?.let { return it }

        val newId = UUID.randomUUID().toString()
        secureTokenStore.save(KEY_DEVICE_ID, newId)
        return newId
    }

    suspend fun ensureRegistered(): DeviceRegistrationState {
        if (!requestSigner.ensureKeyExists()) {
            return DeviceRegistrationState.Failed("İmzalama anahtarı oluşturulamadı.")
        }

        val deviceId = getOrCreateDeviceId()
        val publicKeyBase64 = requestSigner.getPublicKeyBase64()

        return try {
            // Tip parametreleri APIClient.post<Req, Res> sirasiyla verilir:
            // ONCE istek govdesi tipi, SONRA yanit tipi.
            apiClient.post<DeviceRegisterRequest, DeviceRegisterResponse>(
                path = "/v1/devices/register",
                body = DeviceRegisterRequest(deviceId = deviceId, publicKeyBase64 = publicKeyBase64),
                // requiresAuth=true -> yalnızca DeviceAttestationGuard (Play
                // Integrity) uygulanır; APIClient bu path için imza header'ı
                // EKLEMEZ (henüz kayıtlı olmadığımız için eklenemez de) —
                // bkz. APIClient.attachSignatureHeaders'daki path istisnası.
                requiresAuth = true,
            )
            Log.i(TAG, "Cihaz kaydı başarılı.")
            DeviceRegistrationState.Registered
        } catch (e: APIClientException) {
            Log.e(TAG, "Cihaz kaydı başarısız.", e)
            DeviceRegistrationState.Failed(e.message ?: "Bilinmeyen hata")
        }
    }

    fun deviceId(): String = getOrCreateDeviceId()
}

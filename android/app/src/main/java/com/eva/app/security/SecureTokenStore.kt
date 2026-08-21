// android/app/src/main/java/com/eva/app/security/SecureTokenStore.kt
package com.eva.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "SecureTokenStore"
private const val PREFS_FILE_NAME = "com.eva.app.securetokenstore"

/**
 * AndroidX Security (EncryptedSharedPreferences) tabanlı, şifreli yerel
 * token/kimlik bilgisi deposu. Sıfır-PII kuralına uygun olarak burada
 * YALNIZCA oturum token'ları ve kısa ömürlü attestation artefaktları
 * saklanır — asla e-posta, ham konum veya cihaz kimliği tutulmaz.
 *
 * Şifreleme anahtarı Android Keystore'da (donanım destekli, mümkünse)
 * tutulur; EncryptedSharedPreferences bu anahtarla hem key hem value'yu
 * AES256-GCM ile şifreler.
 */
sealed class SecureTokenStoreException(message: String) : Exception(message) {
    data class InitializationFailed(val reason: String) :
        SecureTokenStoreException("Şifreli depo başlatılamadı: $reason")

    data class WriteFailed(val key: String, val reason: String) :
        SecureTokenStoreException("Yazım başarısız ($key): $reason")
}

interface SecureTokenStoreInterface {
    fun save(key: String, value: String)
    fun read(key: String): String?
    fun delete(key: String)
    fun deleteAll()
}

class SecureTokenStore(context: Context) : SecureTokenStoreInterface {

    private val encryptedPrefs: SharedPreferences

    init {
        encryptedPrefs = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences başlatılamadı.", e)
            throw SecureTokenStoreException.InitializationFailed(
                e.message ?: "Bilinmeyen hata"
            )
        }
    }

    override fun save(key: String, value: String) {
        try {
            encryptedPrefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Yazım başarısız: key=$key", e)
            throw SecureTokenStoreException.WriteFailed(key, e.message ?: "Bilinmeyen hata")
        }
    }

    override fun read(key: String): String? {
        return try {
            encryptedPrefs.getString(key, null)
        } catch (e: Exception) {
            // Şifre çözme başarısız olabilir (örn. cihaz keystore'u sıfırlandıysa) —
            // bu durumda sessizce null dönmek yerine logluyoruz, ama çağıran
            // taraf null'ı "token yok" olarak ele alabilir (yeniden giriş akışı).
            Log.e(TAG, "Okuma başarısız: key=$key", e)
            null
        }
    }

    override fun delete(key: String) {
        try {
            encryptedPrefs.edit().remove(key).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Silme başarısız: key=$key", e)
        }
    }

    override fun deleteAll() {
        try {
            encryptedPrefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Tüm kayıtlar silinemedi.", e)
        }
    }

    companion object {
        const val KEY_SESSION_ACCESS_TOKEN = "eva.session.accessToken"
        const val KEY_SESSION_REFRESH_TOKEN = "eva.session.refreshToken"
        const val KEY_LAST_INTEGRITY_NONCE = "eva.integrity.lastNonce"
        /** RequestSigner ile eşleştirilen, kimliksiz cihaz kayıt ID'si. */
        const val KEY_DEVICE_SIGNING_ID = "eva.device.signingDeviceId"
    }
}

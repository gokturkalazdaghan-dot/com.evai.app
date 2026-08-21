// android/app/src/main/java/com/eva/app/security/PlayIntegrityManager.kt
package com.eva.app.security

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityToken
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PlayIntegrityManager"

sealed class PlayIntegrityError(message: String) : Exception(message) {
    data class ProviderPreparationFailed(val reason: String) :
        PlayIntegrityError("Integrity token provider hazırlanamadı: $reason")

    data class TokenRequestFailed(val reason: String) :
        PlayIntegrityError("Integrity token alınamadı: $reason")

    data object ProviderNotReady :
        PlayIntegrityError("Integrity token provider henüz hazır değil, önce prepareProvider() çağrılmalı.")
}

/**
 * Google Play Integrity API (Standard API) tabanlı cihaz/uygulama bütünlük
 * doğrulama yöneticisi. iOS tarafındaki AppAttestManager'ın Android
 * karşılığıdır — sıfır-PII: yalnızca Google'ın imzaladığı, sunucu
 * tarafında doğrulanabilen bir bütünlük token'ı üretir; kullanıcı kimliği
 * veya ham cihaz tanımlayıcısı asla üretilmez/saklanmaz.
 *
 * Standard Integrity API, Classic API'ye göre tercih edilir çünkü provider
 * önceden hazırlanıp (prepareIntegrityToken) önbelleğe alınabilir — her
 * istek için ayrı bir ağ round-trip'i gerekmez, bu da rota/şarj sorguları
 * gibi sık yapılan isteklerde gecikmeyi azaltır.
 */
class PlayIntegrityManager(
    private val context: Context,
    private val cloudProjectNumber: Long,
) {
    private val integrityManager: StandardIntegrityManager =
        IntegrityManagerFactory.createStandard(context.applicationContext)

    @Volatile
    private var tokenProvider: StandardIntegrityTokenProvider? = null

    /**
     * Uygulama başlangıcında bir kez çağrılmalı. Provider hazırlığı ağ
     * çağrısı gerektirir ve birkaç saniye sürebilir — bu yüzden erken
     * (örn. splash screen sırasında) tetiklenmesi önerilir.
     */
    suspend fun prepareProvider() {
        val request = PrepareIntegrityTokenRequest.builder()
            .setCloudProjectNumber(cloudProjectNumber)
            .build()

        tokenProvider = suspendCancellableCoroutine { continuation ->
            integrityManager.prepareIntegrityToken(request)
                .addOnSuccessListener { provider ->
                    continuation.resume(provider)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Integrity provider hazırlığı başarısız.", exception)
                    continuation.resumeWithException(
                        PlayIntegrityError.ProviderPreparationFailed(
                            exception.message ?: "Bilinmeyen hata"
                        )
                    )
                }
        }
    }

    val isProviderReady: Boolean
        get() = tokenProvider != null

    /**
     * Her API isteği için bir bütünlük token'ı üretir. requestHash,
     * isteğin içeriğine bağlı benzersiz bir hash olmalı (örn. istek
     * gövdesinin SHA-256'sı) — bu, token'ın yalnızca ilgili istek için
     * geçerli olmasını sağlar ve replay saldırılarını zorlaştırır.
     */
    suspend fun requestIntegrityToken(requestHash: String): String {
        val provider = tokenProvider ?: run {
            Log.w(TAG, "Provider hazır değil, senkron olarak hazırlanmaya çalışılıyor.")
            prepareProvider()
            tokenProvider ?: throw PlayIntegrityError.ProviderNotReady
        }

        val request = StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
            .setRequestHash(requestHash)
            .build()

        val token: StandardIntegrityToken = suspendCancellableCoroutine { continuation ->
            provider.request(request)
                .addOnSuccessListener { result ->
                    continuation.resume(result)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Integrity token isteği başarısız.", exception)
                    continuation.resumeWithException(
                        PlayIntegrityError.TokenRequestFailed(
                            exception.message ?: "Bilinmeyen hata"
                        )
                    )
                }
        }

        return token.token()
    }
}
